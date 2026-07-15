package com.justme.xtls_core_proxy.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.justme.xtls_core_proxy.R

/**
 * Explains Android's Always-on VPN (the robust, boot-persistent path) and deep-links to the system
 * VPN settings screen. This feature keeps no local state — Android owns the on/off truth — so the
 * hub row is a launcher + explainer, not a toggle. Mirrors SideloadWarningDialog.
 */
@Composable
fun AutoConnectInfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autoconnect_dialog_title)) },
        text = { Text(stringResource(R.string.autoconnect_dialog_body)) },
        confirmButton = {
            TextButton(onClick = {
                val primary = Intent(Settings.ACTION_VPN_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val fallback = Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Rare OEMs don't expose ACTION_VPN_SETTINGS; fall back to the top-level Settings app.
                if (primary.resolveActivity(context.packageManager) != null) {
                    context.startActivity(primary)
                } else {
                    context.startActivity(fallback)
                }
                onDismiss()
            }) {
                Text(stringResource(R.string.autoconnect_open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.autoconnect_dismiss))
            }
        }
    )
}
