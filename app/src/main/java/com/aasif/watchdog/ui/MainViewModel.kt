package com.aasif.watchdog.ui

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aasif.watchdog.BuildConfig
import com.aasif.watchdog.WatchdogApp
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.data.ChargeSource
import com.aasif.watchdog.data.EvidenceItem
import com.aasif.watchdog.data.GmailAccountStatus
import com.aasif.watchdog.data.gmail.GmailAuth
import com.aasif.watchdog.data.notification.PaymentNotificationListener
import com.aasif.watchdog.work.Workers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

sealed interface Screen {
    data object Dashboard : Screen
    data class Detail(val key: String) : Screen
    data object AddManual : Screen
    data object Profile : Screen
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = (app as WatchdogApp).repository

    /** Null until the first load finishes. */
    val subscriptions: StateFlow<List<Subscription>?> =
        repo.subscriptions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var screen: Screen by mutableStateOf(Screen.Dashboard)
        private set

    /** Whether the dashboard is behind the current screen, so back returns to it instead of leaving. */
    var canGoBack: Boolean by mutableStateOf(false)
        private set

    var gmailImportEnabled: Boolean by mutableStateOf(repo.gmailImportEnabled)
        private set

    /** True once a sync came back needing the scope re-approved — otherwise the import card just goes quiet. */
    var gmailNeedsConsent: Boolean by mutableStateOf(repo.gmailNeedsConsent)
        private set

    /** Every connected account with its own import status, for the profile page. */
    var gmailAccounts: List<GmailAccountStatus> by mutableStateOf(repo.gmailAccountStatuses())
        private set

    /** See [WatchdogRepository.gmailBackfillComplete] — drives the dashboard's "still importing" card. */
    var gmailBackfillComplete: Boolean by mutableStateOf(repo.gmailBackfillComplete)
        private set

    /**
     * A one-off message about the Gmail connection — permission declined, Play Services
     * unreachable — so a tap on "Connect Gmail" is never answered with silence.
     */
    var gmailMessage: String? by mutableStateOf(null)
        private set

    fun gmailMessageShown() {
        gmailMessage = null
    }

    /** Source and arrival time per charge on the open detail screen, keyed by charge. */
    var chargeSources: Map<String, ChargeSource> by mutableStateOf(emptyMap())
        private set

    /** Everything the app saw for the open detail screen's merchant: the proof it exists. */
    var evidence: List<EvidenceItem> by mutableStateOf(emptyList())
        private set

    init {
        viewModelScope.launch { repo.purgeSmsIfUnsupported() }
        // Pick up anything that arrived while the app was closed.
        if (hasSmsAccess()) Workers.scanNow(app)
        // An update that changed how mail is read only reaches old mail by re-reading it, so
        // do that now rather than at the next daily run.
        if (repo.gmailImportEnabled && repo.gmailNeedsReread) {
            gmailBackfillComplete = false
            Workers.gmailSyncNow(app)
        }
    }

    /** Whether this build reads SMS at all: the `play` variant does not. */
    val smsSupported: Boolean = BuildConfig.SMS_ENABLED

    fun hasSmsAccess() = smsSupported &&
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationAccess() = PaymentNotificationListener.isEnabled(app)

    /**
     * Set right before an "add account" consent screen is shown, so [onGmailConsentResult] knows
     * which account the grant that comes back belongs to. A brand-new account can never be granted
     * silently, so without this the grant would land with nothing recording it.
     */
    private var pendingAccount: String? = null

    fun onGmailConsentResult(data: Intent?) {
        val account = pendingAccount.orEmpty()
        pendingAccount = null
        val auth = repo.gmailAuth.fromConsentResult(data) as? GmailAuth.Granted
        if (auth == null) {
            gmailMessage = "Gmail access wasn't granted, so nothing will be imported from that account."
            return
        }
        viewModelScope.launch {
            if (repo.finishAddAccount(auth, account)) startImport()
            else gmailMessage = "Couldn't tell which Google account was approved. Please try again."
        }
    }

    private fun startImport() {
        refreshGmailState()
        gmailBackfillComplete = false
        Workers.gmailSyncNow(app)
    }

    /** Re-checks state that can only change outside this screen: Settings, or a background sync. */
    fun refreshGmailState() {
        gmailImportEnabled = repo.gmailImportEnabled
        gmailNeedsConsent = repo.gmailNeedsConsent
        gmailAccounts = repo.gmailAccountStatuses()
        gmailBackfillComplete = repo.gmailBackfillComplete
    }

    /** Only Google's own "choose an account" screen can offer a different Google account. */
    fun chooseGmailAccountIntent(): Intent = repo.gmailAuth.chooseAccountIntent()

    /**
     * Connects an account picked in the system account chooser, alongside any already connected;
     * each needs its own approval once. Picking the account first means its address is always
     * known — asking Play Services for "the" account could come back granted but unnamed, which
     * left nothing to sync and made "Connect Gmail" look like it did nothing.
     */
    fun addGmailAccount(accountName: String, onConsent: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            when (val auth = repo.addGmailAccount(accountName)) {
                // repo.addGmailAccount already recorded the account for this branch.
                is GmailAuth.Granted -> startImport()
                is GmailAuth.NeedsConsent -> {
                    pendingAccount = accountName
                    onConsent(auth.pendingIntent)
                }
                GmailAuth.Unavailable ->
                    gmailMessage = "Couldn't reach Google Play services to connect Gmail. Check your connection and try again."
            }
        }
    }

    fun removeGmailAccount(email: String) {
        viewModelScope.launch {
            repo.removeGmailAccount(email)
            if (repo.gmailAccounts.isEmpty()) Workers.cancelGmailWork(app)
            refreshGmailState()
        }
    }

    fun open(target: Screen) {
        canGoBack = true
        screen = target
    }

    fun loadChargeSources(merchantId: String) {
        chargeSources = emptyMap()
        evidence = emptyList()
        viewModelScope.launch {
            chargeSources = repo.chargeSources(merchantId)
            evidence = repo.evidence(merchantId)
        }
    }

    /** Returns false when there is nowhere to go back to. */
    fun back(): Boolean {
        if (!canGoBack) return false
        goToDashboard()
        return true
    }

    private fun goToDashboard() {
        canGoBack = false
        screen = Screen.Dashboard
    }

    /** Pulls new SMS and mail now instead of waiting for the daily runs. */
    fun rescan() {
        if (hasSmsAccess()) Workers.scanNow(app)
        if (repo.gmailImportEnabled) Workers.gmailSyncNow(app)
    }

    fun onSmsPermissionResult(granted: Boolean) {
        if (granted) Workers.scanNow(app)
    }

    /**
     * The Profile page's "Force full rescan": clears every SMS-derived event and re-reads the inbox
     * from scratch, so a classification fix can be confirmed without waiting for the next app
     * update's automatic one-time reclassification.
     */
    fun forceFullRescan() {
        viewModelScope.launch {
            repo.forceFullRescan()
            Workers.scanNow(app)
        }
    }

    fun markCancelled(key: String) = launchAndGoBack { repo.markCancelled(key) }
    fun hide(key: String) = launchAndGoBack { repo.hide(key) }
    fun undoMark(key: String) = viewModelScope.launch { repo.clearMark(key) }
    fun deleteManual(key: String) = launchAndGoBack { repo.deleteManual(key) }
    fun isManual(key: String) = repo.isManual(key)

    fun addManual(name: String, amount: BigDecimal, frequency: Frequency, firstCharge: LocalDate, isTrial: Boolean) =
        launchAndGoBack { repo.addManual(name, amount, frequency, firstCharge, isTrial) }

    /** What the user typed on a "Needs your input" card. Nulls are left as whatever was already known. */
    fun setUserFact(key: String, frequency: Frequency?, nextRenewal: LocalDate?, amount: BigDecimal?, currency: String?) {
        viewModelScope.launch { repo.setUserFact(key, frequency, nextRenewal, amount, currency) }
    }

    fun nameSubscription(key: String, name: String) {
        viewModelScope.launch { repo.nameSubscription(key, name) }
    }

    fun dismissUserFact(key: String) {
        viewModelScope.launch { repo.dismissUserFact(key) }
    }

    private fun launchAndGoBack(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
        goToDashboard()
    }
}
