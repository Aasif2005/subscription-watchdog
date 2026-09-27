package com.aasif.watchdog.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

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
    val subscriptions by vm.subscriptions.collectAsStateWithLifecycle()
    BackHandler(enabled = vm.screen is Screen.Detail || vm.screen is Screen.AddManual) { vm.back() }

    when (val screen = vm.screen) {
        Screen.Onboarding -> OnboardingScreen(onResult = vm::onPermissionsResult)
        Screen.Dashboard -> DashboardScreen(
            subscriptions = subscriptions,
            hasSmsAccess = vm.hasSmsAccess(),
            onOpen = { vm.open(Screen.Detail(it)) },
            onAdd = { vm.open(Screen.AddManual) },
            onRescan = vm::rescan,
            onGrantAccess = { vm.open(Screen.Onboarding) },
        )
        is Screen.Detail -> {
            val sub = subscriptions?.firstOrNull { it.key == screen.key }
            if (sub == null) {
                // Hidden or deleted while open; the list no longer has it.
                LaunchedEffect(screen.key) { vm.back() }
            } else {
                DetailScreen(
                    sub = sub,
                    isManual = vm.isManual(sub.key),
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
