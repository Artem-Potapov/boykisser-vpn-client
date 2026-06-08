package com.justme.xtls_core_proxy.sideload

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.justme.xtls_core_proxy.R

/** Campaign page explaining Google's developer-verification mandate and how to push back. */
const val KEEP_ANDROID_OPEN_URL = "https://keepandroidopen.org/"

/**
 * Themed warning that Google's developer-verification mandate threatens
 * sideloading. Reused by MainActivity (once-per-version on launch) and by the
 * Settings hub (on demand). The caller decides what dismissal means — this
 * composable only renders and routes button taps through [onDismiss].
 *
 * Both buttons invoke [onDismiss]; "Learn more" additionally opens the campaign
 * page first.
 */
@Composable
fun SideloadWarningDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sideload_warn_title)) },
        text = { Text(stringResource(R.string.sideload_warn_body)) },
        confirmButton = {
            TextButton(onClick = {
                openUrl(context, KEEP_ANDROID_OPEN_URL)
                onDismiss()
            }) {
                Text(stringResource(R.string.sideload_warn_learn_more))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sideload_warn_dismiss))
            }
        }
    )
}

/** Opens [url] in a browser; falls back to a Toast of the URL if no handler exists. */
private fun openUrl(context: Context, url: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, url, Toast.LENGTH_SHORT).show()
    }
}
