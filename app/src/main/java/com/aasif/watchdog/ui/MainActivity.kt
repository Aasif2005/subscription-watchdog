package com.aasif.watchdog.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.Manifest
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
import androidx.compose.runtime.Composable
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
import com.aasif.watchdog.data.notification.PaymentNotificationListener
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchdogTheme { App() }
        }
    }
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val subscriptions by vm.subscriptions.collectAsStateWithLifecycle()
    // Notification access is granted in system Settings, so re-check whenever the user comes back.
    var hasNotificationAccess by remember { mutableStateOf(vm.hasNotificationAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasNotificationAccess = vm.hasNotificationAccess() }
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshGmailState() }
    // Renewal reminders are notifications; asked for once on launch.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    when (val screen = vm.screen) {
        Screen.Dashboard -> DashboardScreen(
            subscriptions = subscriptions,
            smsSupported = vm.smsSupported,
            hasSmsAccess = hasSmsAccess,
            onGrantSms = { askSms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) },
            hasNotificationAccess = hasNotificationAccess,
            onOpen = { vm.open(Screen.Detail(it)) },
            onAdd = { vm.open(Screen.AddManual) },
            onRescan = vm::rescan,
            onGrantNotificationAccess = { openNotificationAccessSettings(context) },
            gmailImportEnabled = vm.gmailImportEnabled,
            gmailNeedsConsent = vm.gmailNeedsConsent,
            gmailBackfillComplete = vm.gmailBackfillComplete,
            onConnectGmail = connectGmail,
            onOpenProfile = { vm.open(Screen.Profile) },
            onSaveUserFact = vm::setUserFact,
            onDismissUserFact = vm::dismissUserFact,
            onHide = vm::hide,
        )
        Screen.Profile -> ProfileScreen(
            gmailImportEnabled = vm.gmailImportEnabled,
            gmailAccounts = vm.gmailAccounts,
            onBack = { vm.back() },
            onConnectGmail = connectGmail,
            onAddAccount = connectGmail,
            onRemoveAccount = vm::removeGmailAccount,
            smsSupported = vm.smsSupported,
            onForceFullRescan = vm::forceFullRescan,
        )
        is Screen.Detail -> {
            val sub = subscriptions?.firstOrNull { it.key == screen.key }
            if (sub == null) {
                // Hidden or deleted while open; the list no longer has it.
                LaunchedEffect(screen.key) { vm.back() }
            } else {
                LaunchedEffect(sub.merchantId) { vm.loadChargeSources(sub.merchantId) }
                DetailScreen(
                    sub = sub,
                    isManual = vm.isManual(sub.key),
                    chargeSources = vm.chargeSources,
                    evidence = vm.evidence,
                    onName = { vm.nameSubscription(sub.key, it) },
                    onBack = { vm.back() },
                    onMarkCancelled = { vm.markCancelled(sub.key) },
                    onUndoCancel = { vm.undoMark(sub.key) },
                    onHide = { vm.hide(sub.key) },
                    onDelete = { vm.deleteManual(sub.key) },
                )
            }
        }
        Screen.AddManual -> AddManualScreen(onBack = { vm.back() }, onSave = vm::addManual)
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
