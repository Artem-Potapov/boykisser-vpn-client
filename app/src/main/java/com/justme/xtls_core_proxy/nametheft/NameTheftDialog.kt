package com.justme.xtls_core_proxy.nametheft

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.justme.xtls_core_proxy.R
import kotlinx.coroutines.delay

/** Seconds the Dismiss button stays disabled so the message can't be skipped instantly. */
private const val COUNTDOWN_SECONDS = 5

/**
 * Tappable links in the body, keyed by the literal substring as it appears in the
 * (localized) body text -> the URL to open. The impostor channel (t.me/femboiVPN)
 * is deliberately absent, so it renders as plain, non-tappable text.
 */
private val NAME_THEFT_LINKS = linkedMapOf(
    "t.me/boykisservpn_news" to "https://t.me/boykisservpn_news",
    "t.me/boykisser_vpn_bot" to "https://t.me/boykisser_vpn_bot",
)

/**
 * Fully-modal warning that a rival channel copied the app's name. Back button and
 * outside taps are blocked; the single Dismiss button is disabled behind a live
 * 5-second countdown. The caller decides what dismissal means via [onDismiss].
 */
@Composable
fun NameTheftDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val raw = stringResource(R.string.name_theft_body)
    val body = remember(raw, linkColor) {
        buildNameTheftBody(raw, linkColor) { url -> openUrl(context, url) }
    }

    var remaining by remember { mutableIntStateOf(COUNTDOWN_SECONDS) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }

    AlertDialog(
        onDismissRequest = { /* modal: ignore back press / outside tap */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.name_theft_title)) },
        text = { Text(text = body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(enabled = remaining == 0, onClick = onDismiss) {
                Text(
                    if (remaining > 0) {
                        stringResource(R.string.name_theft_dismiss_countdown, remaining)
                    } else {
                        stringResource(R.string.name_theft_dismiss)
                    }
                )
            }
        },
    )
}

/**
 * Builds the body as an [AnnotatedString], wrapping every occurrence of a known
 * link substring (see [NAME_THEFT_LINKS]) in a tappable, underlined link. Tapping
 * routes through [onLinkClick]. Not @Composable so the substring logic is plain Kotlin.
 */
private fun buildNameTheftBody(
    raw: String,
    linkColor: Color,
    onLinkClick: (String) -> Unit,
): AnnotatedString {
    // (start, end, url) for every link occurrence, sorted by position.
    val spans = mutableListOf<Triple<Int, Int, String>>()
    for ((token, url) in NAME_THEFT_LINKS) {
        var index = raw.indexOf(token)
        while (index >= 0) {
            spans += Triple(index, index + token.length, url)
            index = raw.indexOf(token, index + token.length)
        }
    }
    spans.sortBy { it.first }

    return buildAnnotatedString {
        var cursor = 0
        for ((start, end, url) in spans) {
            if (start < cursor) continue // overlap guard (shouldn't happen with distinct tokens)
            append(raw.substring(cursor, start))
            withLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        )
                    ),
                    linkInteractionListener = { onLinkClick(url) },
                )
            ) {
                append(raw.substring(start, end))
            }
            cursor = end
        }
        append(raw.substring(cursor))
    }
}

/** Opens [url] in a browser/Telegram; falls back to a Toast of the URL if nothing handles it. */
private fun openUrl(context: Context, url: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, url, Toast.LENGTH_SHORT).show()
    }
}
