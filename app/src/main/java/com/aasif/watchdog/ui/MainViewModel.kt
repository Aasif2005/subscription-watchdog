package com.aasif.watchdog.ui

import android.Manifest
import android.app.Application
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
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = (app as WatchdogApp).repository

    /** Null until the first load finishes. */
    val subscriptions: StateFlow<List<Subscription>?> =
        repo.subscriptions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var screen: Screen by mutableStateOf(if (hasSmsAccess()) Screen.Dashboard else Screen.Onboarding)
        private set

    init {
        // Pick up anything that arrived while the app was closed.
        if (hasSmsAccess()) Workers.scanNow(app)
    }

    fun hasSmsAccess() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun open(target: Screen) {
        screen = target
    }

    /** Returns false when there is nowhere to go back to. */
    fun back(): Boolean {
        if (screen == Screen.Dashboard || screen == Screen.Onboarding) return false
        screen = Screen.Dashboard
        return true
    }

    fun onPermissionsResult(smsGranted: Boolean) {
        if (smsGranted) Workers.scanNow(app)
        screen = Screen.Dashboard
    }

    fun rescan() = Workers.scanNow(app)

    fun markCancelled(key: String) = launchAndGoBack { repo.markCancelled(key) }
    fun hide(key: String) = launchAndGoBack { repo.hide(key) }
    fun undoMark(key: String) = viewModelScope.launch { repo.clearMark(key) }
    fun deleteManual(key: String) = launchAndGoBack { repo.deleteManual(key) }
    fun isManual(key: String) = repo.isManual(key)

    fun addManual(name: String, amount: BigDecimal, frequency: Frequency, firstCharge: LocalDate, isTrial: Boolean) =
        launchAndGoBack { repo.addManual(name, amount, frequency, firstCharge, isTrial) }

    private fun launchAndGoBack(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
        screen = Screen.Dashboard
    }
}
