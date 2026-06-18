package com.justme.xtls_core_proxy.killswitch

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.justme.xtls_core_proxy.R
import kotlinx.coroutines.delay

private const val COUNTDOWN_FIRST = 5
private const val COUNTDOWN_RETURNING = 2

@Composable
fun KillSwitchConsentDialog(
    isFirstConsent: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val countdownStart = if (isFirstConsent) COUNTDOWN_FIRST else COUNTDOWN_RETURNING
    var remaining by rememberSaveable { mutableIntStateOf(countdownStart) }
    val canAct = remaining == 0

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }

    // Block back while the countdown runs; after it completes this handler disables and
    // back falls through to AlertDialog's dismissOnBackPress (= canAct), which declines.
    BackHandler(enabled = !canAct) {}

    AlertDialog(
        onDismissRequest = { onDecline() },
        properties = DialogProperties(
            dismissOnBackPress = canAct,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.kill_switch_consent_title)) },
        text = {
            Text(
                text = stringResource(R.string.kill_switch_consent_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(enabled = canAct, onClick = onAccept) {
                Text(
                    if (remaining > 0) {
                        stringResource(R.string.kill_switch_consent_accept_countdown, remaining)
                    } else {
                        stringResource(R.string.kill_switch_consent_accept)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(enabled = canAct, onClick = onDecline) {
                Text(stringResource(R.string.kill_switch_consent_cancel))
            }
        },
    )
}
