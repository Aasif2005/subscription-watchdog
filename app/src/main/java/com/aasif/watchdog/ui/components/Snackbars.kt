package com.aasif.watchdog.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aasif.watchdog.ui.theme.watchdog

/** Messages that used to be Toasts: night-coloured on light, light on dark, with an optional action. */
@Composable
fun WatchdogSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val w = MaterialTheme.watchdog
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            snackbarData = data,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            containerColor = w.snack,
            contentColor = w.snackInk,
            actionColor = w.snackAction,
        )
    }
}
