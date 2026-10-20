package com.aasif.watchdog.ui

import com.aasif.watchdog.core.merchant.MerchantNormalizer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import com.aasif.watchdog.core.engine.ReplacementQuestion
import com.aasif.watchdog.core.model.Charge
import com.aasif.watchdog.core.model.Confidence
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Money
import com.aasif.watchdog.core.model.PriceChange
import com.aasif.watchdog.core.model.RenewalSource
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.core.model.SubscriptionStatus
import com.aasif.watchdog.data.ChargeSource
import com.aasif.watchdog.data.EvidenceItem
import com.aasif.watchdog.data.GmailAccountStatus
import com.aasif.watchdog.data.GmailImportProgress
import com.aasif.watchdog.data.DayUsage
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Debug builds only. Renders one screen or state with made-up data, chosen by the `screen` intent extra, so every design state can
 * be opened and screenshotted without touching the user's own data:
 * `adb shell am start -n com.aasif.watchdog/.ui.UiGalleryActivity --es screen dashboard`.
 */
class UiGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = intent.getStringExtra("screen") ?: "dashboard"
        setContent { WatchdogTheme { Gallery(screen) } }
    }
}

private val today: LocalDate = LocalDate.now()
private fun inr(v: Int) = Money(BigDecimal(v))

private fun sub(
    name: String, amount: Int, frequency: Frequency = Frequency.MONTHLY, renewIn: Int? = 30,
    status: SubscriptionStatus = SubscriptionStatus.ACTIVE, source: RenewalSource = RenewalSource.PAYMENT_HISTORY,
    shared: Boolean = false, charges: List<Charge> = listOf(Charge(today.minusDays(28), inr(amount))), category: String = "Entertainment",
    cancelUrl: String? = "https://example.com/cancel",
) = Subscription(
    key = name.lowercase().replace(' ', '-'), merchantId = MerchantNormalizer().findKnownIn(name)?.id ?: name.lowercase(), name = name, category = category,
    amount = inr(amount), frequency = frequency, nextRenewal = renewIn?.let { today.plusDays(it.toLong()) },
    renewalSource = source, confidence = Confidence.HIGH, status = status, charges = charges, cancelUrl = cancelUrl, sharedBilling = shared,
)

private val netflix = sub("Netflix", 499, renewIn = 2, source = RenewalSource.PRE_DEBIT_NOTICE).copy(
    priceChange = PriceChange(inr(449), inr(499), today.minusDays(90)),
    charges = listOf(Charge(today.minusDays(28), inr(499)), Charge(today.minusDays(59), inr(499)), Charge(today.minusDays(90), inr(449))),
)
private val spotify = sub("Spotify Premium", 119, renewIn = 6)
private val chatgpt = sub("ChatGPT Plus", 1999, renewIn = 15, category = "Cloud & software")
private val hotstar = sub("Disney+ Hotstar", 299, renewIn = 4, status = SubscriptionStatus.TRIAL, source = RenewalSource.TRIAL_ESTIMATE)
private val play = sub("Google Play", 299, renewIn = 24, shared = true, category = "Apps")
private val googleOne = sub("Google One", 1300, Frequency.YEARLY, renewIn = 105, category = "Cloud & software")
private val prime = sub("Prime Video", 299, renewIn = null, status = SubscriptionStatus.CANCELLED).copy(unplacedRevocationOn = today.minusDays(2))
private val apple = sub("Apple Music", 139, renewIn = null, status = SubscriptionStatus.ENDING).copy(activeUntil = today.plusDays(100))
private val needsInput = sub("NordVPN", 0, renewIn = null).copy(amountKnown = false, frequencyKnown = false)
private val all = listOf(netflix, hotstar, spotify, chatgpt, play, googleOne, apple, prime)

private val permissions = listOf(
    PermissionItem("sms", "Bank texts (SMS)", "Reads payment and AutoPay texts from your bank to find subscriptions and their renewal dates. They stay on this phone.", "Read bank texts for AutoPay dates", false) {},
    PermissionItem("gmail", "Gmail (read only)", "Finds receipts and renewal mail in [you]@gmail.com.", "Read Gmail receipts", true) {},
    PermissionItem("notification-access", "Payment app alerts", "Reads AutoPay alerts from GPay, PhonePe, Paytm and bank apps. Only payment apps are read.", "Catch AutoPay alerts from UPI apps", false) {},
    PermissionItem("notifications", "Reminders", "Lets Watchdog warn you before a subscription renews.", "Get warned before a renewal", false) {},
    PermissionItem("usage", "Screen time (Usage access)", "Tells you when you barely used a streaming or AI app before it renews.", "See low use before a renewal", true) {},
)

@Composable
private fun Gallery(screen: String) {
    when (screen) {
        "signin" -> SignInScreen(null, {}, {}, {})
        "explainer" -> SignInScreen(null, {}, {}, {}, startOnExplainer = true)
        "issue-declined" -> SignInScreen(ConsentIssue.Declined, {}, {}, {})
        "issue-unavailable" -> SignInScreen(ConsentIssue.Unavailable, {}, {}, {})
        "permissions" -> PermissionsScreen("[you]@gmail.com", permissions, onContinue = {})
        "dashboard-skeleton" -> dashboard(null)
        "dashboard-empty" -> dashboard(emptyList(), missing = permissions.filter { !it.allowed })
        "dashboard-import" -> dashboard(listOf(netflix, spotify, chatgpt), importing = true, progress = GmailImportProgress(1860, 3000, 0.62f))
        "dashboard-offline" -> dashboard(all, online = false)
        "dashboard-needs" -> dashboard(
            all + needsInput, questions = listOf(netflix), replacements = listOf(ReplacementQuestion(spotify, play)),
            revocations = listOf(prime), autopayDue = setOf(chatgpt.key), missing = permissions.filter { !it.allowed },
            hints = mapOf(netflix.key to "You watched 42 min in the last 30 days."),
        )
        "detail" -> detail(netflix)
        "detail-loading" -> detail(netflix, loading = true)
        "detail-skeleton" -> DetailSkeleton(onBack = {})
        "detail-guide" -> detail(netflix, planned = today.plusDays(2))
        "detail-shared" -> detail(play)
        "detail-trial" -> detail(hotstar)
        "profile" -> profile()
        "add" -> AddManualScreen(onBack = {}, onSave = { _, _, _, _, _ -> })
        "usage-sheet" -> UsageDisclosureSheet({}, {})
        else -> dashboard(all, hints = emptyMap())
    }
}

@Composable
private fun dashboard(
    subs: List<Subscription>?, importing: Boolean = false, progress: GmailImportProgress? = null, online: Boolean = true,
    questions: List<Subscription> = emptyList(), replacements: List<ReplacementQuestion> = emptyList(),
    revocations: List<Subscription> = emptyList(), autopayDue: Set<String> = emptySet(),
    missing: List<PermissionItem> = emptyList(), hints: Map<String, String> = emptyMap(),
) = DashboardScreen(
    subscriptions = subs, hasSmsAccess = true, missingPermissions = missing, onOpen = {}, onAdd = {}, onRescan = {},
    gmailImportEnabled = true, gmailImporting = importing, onOpenProfile = {}, onSaveUserFact = { _, _, _, _, _ -> },
    onDismissUserFact = {}, onHide = {}, pendingQuestions = questions, onKeep = {}, onPlanCancel = {}, onSnooze = {},
    usageHints = hints, usageFigures = mapOf(netflix.key to UsageFigure(today.minusDays(30), null, barelyUsed = true)),
    autopayDue = autopayDue, onAutopayCancelled = {}, onAutopayStillActive = {}, replacementQuestions = replacements,
    onReplaces = {}, onDifferentApp = {}, revocationQuestions = revocations, onRevocationYes = {}, onRevocationNo = {},
    accountEmail = "[you]@gmail.com", gmailProgress = progress, rescanning = false, online = online,
)

@Composable
private fun detail(sub: Subscription, loading: Boolean = false, planned: LocalDate? = null) = DetailScreen(
    sub = sub, isManual = false,
    chargeSources = if (loading) emptyMap() else sub.charges.associate {
        com.aasif.watchdog.data.WatchdogRepository.chargeKey(it.date, it.amount) to ChargeSource("HDFC Bank SMS", 0L)
    },
    evidence = if (loading) emptyList() else listOf(
        EvidenceItem("AutoPay mandate created", inr(sub.amount.amount.toInt()), "SMS (HDFC)", 0L, today.minusDays(90)),
        EvidenceItem("Payment", inr(sub.amount.amount.toInt()), "Gmail ([you]@gmail.com)", 0L, today.minusDays(28)),
    ),
    onName = {}, onBack = {}, onMarkCancelled = {}, userCancelled = false, onUndoCancel = {}, onHide = {}, onDelete = {},
    plannedCancelDate = planned, onKeepAfterAll = {}, onPlanLength = {}, onStillActive = {}, autopayAsk = false,
    onAutopayCancelled = {}, onAutopayStillActive = {},
    usageFigure = UsageFigure(today.minusDays(30), null, barelyUsed = sub.key == netflix.key), loading = loading,
    // A sample month: a few days unrecorded, mostly unused, three evenings of use.
    usageDays = (29 downTo 0).map { back ->
        DayUsage(today.minusDays(back.toLong()), when (back) { 27, 26 -> null; 21 -> 18L; 12 -> 9L; 3 -> 15L; else -> 0L })
    },
)

@Composable
private fun profile() = ProfileScreen(
    accountEmail = "[you]@gmail.com", onBack = {}, themeMode = ThemeMode.SYSTEM, onThemeMode = {},
    reminderLeadDays = 5, onReminderLeadDays = {}, onlineLogos = false, onOnlineLogos = {}, gmailImportEnabled = true,
    gmailAccounts = listOf(GmailAccountStatus("[you]@gmail.com", needsConsent = false, importing = false), GmailAccountStatus("[work]@gmail.com", needsConsent = true, importing = false)),
    onConnectGmail = {}, onAddAccount = {}, onRemoveAccount = {}, yearlySavings = BigDecimal(5388),
    appVersion = "0.1.0", onOpenAppSettings = {}, onSignOut = {},
)
