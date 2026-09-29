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
import com.aasif.watchdog.WatchdogApp
import com.aasif.watchdog.core.model.Frequency
import com.aasif.watchdog.core.model.Subscription
import com.aasif.watchdog.data.ChargeSource
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
    data object Onboarding : Screen
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

    var screen: Screen by mutableStateOf(if (hasSmsAccess()) Screen.Dashboard else Screen.Onboarding)
        private set

    /**
     * Whether the dashboard is behind the current screen.
     *
     * Onboarding is both the first screen of a fresh install and somewhere the dashboard can send
     * the user to grant access later, and back means different things in the two cases: leave the
     * app, or return to the dashboard. Only how the screen was reached tells them apart.
     */
    var canGoBack: Boolean by mutableStateOf(false)
        private set

    var gmailImportEnabled: Boolean by mutableStateOf(repo.gmailImportEnabled)
        private set

    /** True once a sync came back needing the scope re-approved — otherwise the import card just goes quiet. */
    var gmailNeedsConsent: Boolean by mutableStateOf(repo.gmailNeedsConsent)
        private set

    var gmailAccountEmail: String? by mutableStateOf(repo.gmailAccountEmail)
        private set

    /** Source and arrival time per charge on the open detail screen, keyed by charge. */
    var chargeSources: Map<String, ChargeSource> by mutableStateOf(emptyMap())
        private set

    init {
        // Pick up anything that arrived while the app was closed.
        if (hasSmsAccess()) Workers.scanNow(app)
    }

    fun hasSmsAccess() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationAccess() = PaymentNotificationListener.isEnabled(app)

    /**
     * Asks Play Services for Gmail read access. [onConsent] is called with the screen to show
     * when the user still has to approve the scope.
     */
    fun connectGmail(onConsent: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            when (val auth = repo.gmailAuth.authorize()) {
                is GmailAuth.Granted -> enableGmailImport(auth)
                is GmailAuth.NeedsConsent -> onConsent(auth.pendingIntent)
                GmailAuth.Unavailable -> Unit
            }
        }
    }

    /**
     * Set right before a "switch account" consent screen is shown, so [onGmailConsentResult]
     * knows the grant that comes back has to reset the sync cursors — otherwise a switch to a
     * brand-new account (which can never be granted silently) "succeeds" but keeps the previous
     * account's cursor, and the new account silently syncs nothing.
     */
    private var pendingAccountSwitch: String? = null

    fun onGmailConsentResult(data: Intent?) {
        val auth = repo.gmailAuth.fromConsentResult(data) as? GmailAuth.Granted ?: return
        val switchingTo = pendingAccountSwitch
        pendingAccountSwitch = null
        if (switchingTo != null) applyAccountSwitch(auth, switchingTo) else enableGmailImport(auth)
    }

    private fun enableGmailImport(auth: GmailAuth.Granted) {
        repo.gmailImportEnabled = true
        repo.gmailNeedsConsent = false
        repo.rememberGmailAccount(auth.accountEmail)
        gmailImportEnabled = true
        gmailNeedsConsent = false
        gmailAccountEmail = repo.gmailAccountEmail
        Workers.gmailSyncNow(app)
    }

    private fun applyAccountSwitch(auth: GmailAuth.Granted, accountName: String) {
        viewModelScope.launch {
            repo.finishAccountSwitch(auth, accountName)
            gmailImportEnabled = true
            gmailNeedsConsent = false
            gmailAccountEmail = repo.gmailAccountEmail
            Workers.gmailSyncNow(app)
        }
    }

    /** Re-checks state that can only change outside this screen: Settings, or a background sync. */
    fun refreshGmailState() {
        gmailImportEnabled = repo.gmailImportEnabled
        gmailNeedsConsent = repo.gmailNeedsConsent
        gmailAccountEmail = repo.gmailAccountEmail
    }

    /** Only Google's own "choose an account" screen can offer a different Google account. */
    fun chooseGmailAccountIntent(): Intent = repo.gmailAuth.chooseAccountIntent()

    fun switchGmailAccount(accountName: String, onConsent: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            when (val auth = repo.switchGmailAccount(accountName)) {
                // repo.switchGmailAccount already ran finishAccountSwitch for this branch.
                is GmailAuth.Granted -> {
                    gmailImportEnabled = true
                    gmailNeedsConsent = false
                    gmailAccountEmail = repo.gmailAccountEmail
                    Workers.gmailSyncNow(app)
                }
                is GmailAuth.NeedsConsent -> {
                    pendingAccountSwitch = accountName
                    onConsent(auth.pendingIntent)
                }
                GmailAuth.Unavailable -> Unit
            }
        }
    }

    fun disconnectGmail() {
        viewModelScope.launch {
            repo.disconnectGmail()
            Workers.cancelGmailWork(app)
            gmailImportEnabled = false
            gmailNeedsConsent = false
            gmailAccountEmail = null
        }
    }

    fun open(target: Screen) {
        canGoBack = true
        screen = target
    }

    fun loadChargeSources(merchantId: String) {
        chargeSources = emptyMap()
        viewModelScope.launch { chargeSources = repo.chargeSources(merchantId) }
    }

    /** Returns false when there is nowhere to go back to. */
    fun back(): Boolean {
        if (!canGoBack) return false
        goToDashboard()
        return true
    }

    fun onPermissionsResult(smsGranted: Boolean) {
        if (smsGranted) Workers.scanNow(app)
        goToDashboard()
    }

    private fun goToDashboard() {
        canGoBack = false
        screen = Screen.Dashboard
    }

    fun rescan() {
        Workers.scanNow(app)
        if (repo.gmailImportEnabled) Workers.gmailSyncNow(app)
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

    fun dismissUserFact(key: String) {
        viewModelScope.launch { repo.dismissUserFact(key) }
    }

    private fun launchAndGoBack(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
        goToDashboard()
    }
}
