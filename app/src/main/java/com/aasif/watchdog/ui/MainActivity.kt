package com.aasif.watchdog.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aasif.watchdog.core.engine.heldForMail
import com.aasif.watchdog.data.notification.PaymentNotificationListener
import com.aasif.watchdog.work.ReminderActions
import com.aasif.watchdog.work.ReminderChannel
import java.time.LocalDate
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchdogTheme { App() }
        }
        // Only on a fresh start: after process death Android re-creates the activity with the
        // original intent, extras included, and the plan would be recorded a second time.
        if (savedInstanceState == null) handleReminderAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleReminderAction(intent)
    }

    /** The "Cancel it" button and a pointer notification arrive as extras on the launch intent. */
    private fun handleReminderAction(intent: Intent) {
        intent.getStringExtra(ReminderActions.EXTRA_OPEN_KEY)?.let { key ->
            intent.removeExtra(ReminderActions.EXTRA_OPEN_KEY)
            vm.open(Screen.Detail(key))
        }
        val key = intent.getStringExtra(ReminderActions.EXTRA_CANCEL_KEY) ?: return
        val date = intent.getStringExtra(ReminderActions.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        // Consumed, so rotating the screen or returning to the app does not record it a second time.
        intent.removeExtra(ReminderActions.EXTRA_CANCEL_KEY)
        if (date != null) vm.planCancellation(key, date, intent.getIntExtra(ReminderActions.EXTRA_NOTIFICATION_ID, key.hashCode()))
    }
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val subscriptions by vm.subscriptions.collectAsStateWithLifecycle()
    // Notification access is granted in system Settings, so re-check whenever the user comes back.
    var hasNotificationAccess by remember { mutableStateOf(vm.hasNotificationAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasNotificationAccess = vm.hasNotificationAccess()
        vm.refreshUsage(vm.subscriptions.value)
    }
    LaunchedEffect(subscriptions) { vm.refreshUsage(subscriptions) }
    if (vm.usagePromptDue) {
        UsageDisclosureDialog(
            onOpenSettings = { vm.dismissUsagePrompt(); openUsageAccessSettings(context) },
            onNotNow = vm::dismissUsagePrompt,
        )
    }
    // Google's own consent screen; it decides what the user is agreeing to, not us.
    val gmailConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onGmailConsentResult(it.data)
    }
    // Every Gmail connection starts in the system account chooser, so the account is always named.
    val chooseGmailAccount = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)?.let { account ->
            vm.addGmailAccount(account) { consent -> gmailConsent.launch(IntentSenderRequest.Builder(consent).build()) }
        }
    }
    val connectGmail = { chooseGmailAccount.launch(vm.chooseGmailAccountIntent()) }
    // "Continue with Google" is the same chooser, but the account picked also becomes the signed-in profile.
    val signInChooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)?.let { account ->
            vm.signIn(account) { consent -> gmailConsent.launch(IntentSenderRequest.Builder(consent).build()) }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshGmailState(); vm.refreshNotificationStats(); vm.syncMailOnOpen() }
    // Renewal reminders are notifications; asked for on the permission screen, and re-checked when the user comes back from Settings.
    var notificationsAllowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    // SMS is optional: the dashboard offers it, and the user decides.
    var hasSmsAccess by remember { mutableStateOf(vm.hasSmsAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasSmsAccess = vm.hasSmsAccess() }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasSmsAccess = vm.hasSmsAccess()
        vm.onSmsPermissionResult(result[Manifest.permission.READ_SMS] == true)
    }
    vm.gmailMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            vm.gmailMessageShown()
        }
    }
    // The import runs in the background; without this the "still importing" card only cleared
    // on the next resume, long after the import had finished.
    LaunchedEffect(vm.gmailImportEnabled, vm.gmailBackfillComplete) {
        while (vm.gmailImportEnabled && !vm.gmailBackfillComplete) {
            delay(5_000)
            vm.refreshGmailState()
        }
    }
    BackHandler(enabled = vm.canGoBack) { vm.back() }

    // The one list the permission pages, the summary and the dashboard all read.
    val gmailLinked = vm.gmailAccounts.isNotEmpty() && vm.gmailAccounts.none { it.needsConsent }
    val permissions = buildList {
        if (vm.smsSupported) add(
            PermissionItem(
                "sms", "Bank texts (SMS)",
                "Reads payment and AutoPay texts from your bank to find subscriptions and their renewal dates. They stay on this phone.",
                "Read bank texts for AutoPay dates",
                hasSmsAccess,
            ) { askSms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) },
        )
        add(
            PermissionItem(
                "gmail", "Gmail (read only)",
                vm.accountEmail?.let { "Finds receipts and renewal mail in $it." }
                    ?: "Finds receipts and renewal mail in the Google account you choose.",
                if (vm.gmailNeedsConsent) "Approve Gmail access again" else "Read Gmail receipts",
                gmailLinked,
            ) { connectGmail() },
        )
        add(
            PermissionItem(
                "notification-access", "Payment app alerts",
                "Reads AutoPay alerts from GPay, PhonePe, Paytm and bank apps. Only payment apps are read.",
                "Catch AutoPay alerts from UPI apps",
                hasNotificationAccess,
            ) { openNotificationAccessSettings(context) },
        )
        add(
            PermissionItem(
                "notifications", "Reminders",
                "Lets Watchdog warn you before a subscription renews.",
                "Get warned before a renewal",
                notificationsAllowed,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openReminderSettings(context)
            },
        )
        if (vm.usageSupported) add(
            PermissionItem(
                "usage", "Screen time (Usage access)",
                "Tells you when you barely used a streaming or AI app before it renews. Only the apps of your own subscriptions are read.",
                "See low use before a renewal",
                vm.usageGranted,
            ) { openUsageAccessSettings(context) },
        )
    }


    when (val screen = vm.screen) {
        Screen.Dashboard -> {
            // A "Google Play" plan waits for the mail import, which may still say which app it is.
            val shown = subscriptions?.heldForMail(vm.gmailCatchingUp)
            val questions = shown?.let(vm::pendingQuestions).orEmpty()
            DashboardScreen(
                subscriptions = shown,
                hasSmsAccess = hasSmsAccess,
                missingPermissions = permissions.filter { !it.allowed },
                onOpen = { vm.open(Screen.Detail(it)) },
                onAdd = { vm.open(Screen.AddManual) },
                onRescan = vm::rescan,
                gmailImportEnabled = vm.gmailImportEnabled,
                // Not while the import waits for the user to approve Gmail again: it is not running then.
                gmailImporting = vm.gmailCatchingUp,
                onOpenProfile = { vm.refreshNotificationStats(); vm.open(Screen.Profile) },
                pendingQuestions = questions,
                onKeep = vm::keepSubscription,
                onPlanCancel = vm::planCancelOf,
                onSnooze = vm::snoozeSubscription,
                usageHints = vm.usageHints,
                usageFigures = vm.usageFigures,
                // The renewal question above already offers Cancel; asking about the AutoPay as well is the same question twice.
                autopayDue = shown?.let(vm::autopayDue).orEmpty() - questions.map { it.key }.toSet(),
                onAutopayCancelled = vm::alreadyCancelled,
                onAutopayStillActive = vm::autopayStillActive,
                replacementQuestions = shown?.let(vm::replacementQuestions).orEmpty(),
                onReplaces = vm::replacesIt,
                onDifferentApp = vm::differentApp,
                revocationQuestions = shown?.let(vm::revocationQuestions).orEmpty(),
                onRevocationYes = vm::revocationWasThisOne,
                onRevocationNo = vm::revocationWasNot,
                onSaveUserFact = vm::setUserFact,
                onDismissUserFact = vm::dismissUserFact,
                onHide = vm::hide,
            )
        }
        Screen.Profile -> ProfileScreen(
            gmailImportEnabled = vm.gmailImportEnabled,
            gmailAccounts = vm.gmailAccounts,
            onBack = { vm.back() },
            onConnectGmail = connectGmail,
            onAddAccount = connectGmail,
            onRemoveAccount = vm::removeGmailAccount,
            smsSupported = vm.smsSupported,
            onForceFullRescan = vm::forceFullRescan,
            hasNotificationAccess = hasNotificationAccess,
            notificationStats = vm.notificationStats,
            onGrantNotificationAccess = { openNotificationAccessSettings(context) },
            onOpenReminderSettings = { openReminderSettings(context) },
            usageSupported = vm.usageSupported,
            usageGranted = vm.usageGranted,
            onUsageOpenSettings = { openUsageAccessSettings(context) },
            accountEmail = vm.accountEmail,
            onSignOut = vm::signOut,
        )
        Screen.SignIn -> SignInScreen(
            onContinueWithGoogle = { signInChooser.launch(vm.chooseGmailAccountIntent()) },
        )
        Screen.Permissions -> {
            PermissionsScreen(accountEmail = vm.accountEmail, items = permissions, onContinue = vm::finishOnboarding)
        }
        is Screen.Detail -> {
            val sub = subscriptions?.firstOrNull { it.key == screen.key }
            if (subscriptions == null) {
                // Still loading, e.g. opened from a notification into a freshly started app.
            } else if (sub == null) {
                // Hidden or deleted while open; the list no longer has it.
                LaunchedEffect(screen.key) { vm.back() }
            } else {
                // Keyed on the events, so new texts arriving while it is open refresh the list.
                LaunchedEffect(sub.key, sub.events) { vm.loadChargeSources(sub) }
                DetailScreen(
                    sub = sub,
                    isManual = vm.isManual(sub.key),
                    chargeSources = vm.chargeSources,
                    evidence = vm.evidence,
                    onName = { vm.nameSubscription(sub.key, it) },
                    onBack = { vm.back() },
                    onMarkCancelled = { vm.markCancelled(sub.key) },
                    userCancelled = vm.userCancelled(sub.key),
                    onUndoCancel = { vm.undoMark(sub.key) },
                    onHide = { vm.hide(sub.key) },
                    onDelete = { vm.deleteManual(sub.key) },
                    plannedCancelDate = vm.plannedCancelDate(sub.key),
                    onKeepAfterAll = { planned -> vm.keepAfterAll(sub.key, planned) },
                    onPlanLength = { vm.setPlanLength(sub.key, it) },
                    onStillActive = { vm.confirmStillActive(sub) },
                    autopayAsk = vm.autopayAsk(sub),
                    usageFigure = vm.usageFigures[sub.key],
                    onAutopayCancelled = { vm.alreadyCancelled(sub) },
                    onAutopayStillActive = { vm.autopayStillActive(sub) },
                )
            }
        }
        Screen.AddManual -> AddManualScreen(onBack = { vm.back() }, onSave = vm::addManual)
    }
}

/** Usage access is granted on its own Settings page, not by a dialog. */
private fun openUsageAccessSettings(context: Context) {
    val own = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(android.net.Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(own)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

/** This app's reminder channel in system settings, where banners, sound and lock-screen display are chosen. */
private fun openReminderSettings(context: Context) {
    val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, ReminderChannel.ID)
    // Some OEM builds lack the per-channel page; the app's own notification page always exists.
    try {
        context.startActivity(channel)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
}

private fun openNotificationAccessSettings(context: Context) {
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return context.startActivity(list)
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
        PaymentNotificationListener.component(context).flattenToString(),
    )
    // Some OEM builds lack the per-app detail page.
    try {
        context.startActivity(detail)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(list)
    }
}
