package com.justme.xtls_core_proxy.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.config.XrayLogLevel
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.SettingsRow
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme
import kotlinx.coroutines.launch

class LogsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { LogsScreen(onBack = { finish() }) } }
    }
}

/** Which inline (Binder-bound) action a large-log explainer dialog is confirming. */
private enum class LogShareAction { COPY, SHARE }

private fun levelLabelRes(level: XrayLogLevel): Int = when (level) {
    XrayLogLevel.DEBUG -> R.string.logs_level_debug
    XrayLogLevel.INFO -> R.string.logs_level_info
    XrayLogLevel.WARNING -> R.string.logs_level_warning
    XrayLogLevel.ERROR -> R.string.logs_level_error
    XrayLogLevel.NONE -> R.string.logs_level_warning // NONE is internal; never shown in the picker
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val logs by LogRepository.logs.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var levelDialog by remember { mutableStateOf(false) }
    var bufferDialog by remember { mutableStateOf(false) }
    // Non-null while the "log is large" explainer is up; carries which action to run on confirm.
    var pendingShare by remember { mutableStateOf<LogShareAction?>(null) }
    var level by remember { mutableStateOf(LogPreferences.getLogLevel(context)) }
    var buffer by remember { mutableStateOf(LogPreferences.getBufferLines(context)) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // "Following" = user is at (or near) the bottom. Auto-scroll only while following.
    val following by remember {
        derivedStateOf {
            val last = listState.layoutInfo.totalItemsCount - 1
            last < 0 || listState.layoutInfo.visibleItemsInfo.any { it.index == last }
        }
    }
    LaunchedEffect(logs.size, following) {
        if (following && logs.isNotEmpty()) listState.scrollToItem(logs.size - 1)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(logs.joinToString("\n").toByteArray(Charsets.UTF_8))
            }
        }
    }

    // Copy/Share inline their whole payload through a single ~1 MB Binder transaction, so the
    // full buffer would throw TransactionTooLargeException (and, sharing this process with the
    // VpnService, crash the tunnel). Bound the payload to the newest lines that fit; if that
    // dropped anything, surface the explainer instead of acting silently. Export streams and
    // needs no bounding.
    fun onCopy() {
        val bounded = LogShareBudget.bound(logs)
        if (bounded.truncated) pendingShare = LogShareAction.COPY else runCopy(context, resources, bounded)
    }
    fun onShare() {
        val bounded = LogShareBudget.bound(logs)
        if (bounded.truncated) pendingShare = LogShareAction.SHARE else runShare(context, resources, bounded)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    // Single overflow entry point. Copy/Share/Export/Clear are all labeled
                    // items inside it — Clear used to be a bare, unlabeled toolbar icon that
                    // read as "close", and it's destructive, so it belongs behind the menu.
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription =
                            stringResource(R.string.logs_action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logs_action_copy)) },
                            onClick = { menuOpen = false; onCopy() })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logs_action_share)) },
                            onClick = { menuOpen = false; onShare() })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logs_action_export)) },
                            onClick = { menuOpen = false; exportLauncher.launch("boykisser-log.txt") })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logs_action_clear)) },
                            onClick = { menuOpen = false; LogRepository.clear() })
                    }
                }
            )
        },
        floatingActionButton = {
            if (!following && logs.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.logs_jump_latest)) },
                    icon = {},
                    onClick = { scope.launch { listState.scrollToItem(logs.lastIndex) } }
                )
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            SettingsRow(
                title = stringResource(R.string.logs_level_title),
                subtitle = stringResource(R.string.logs_level_caption),
                trailingValue = stringResource(levelLabelRes(level)),
                onClick = { levelDialog = true })
            HorizontalDivider()
            SettingsRow(
                title = stringResource(R.string.logs_buffer_title),
                trailingValue = stringResource(R.string.logs_buffer_lines, buffer),
                onClick = { bufferDialog = true })
            HorizontalDivider()
            Surface(Modifier.fillMaxWidth().weight(1f), tonalElevation = 1.dp) {
                LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                    items(logs) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    if (levelDialog) {
        val choices = listOf(XrayLogLevel.DEBUG, XrayLogLevel.INFO, XrayLogLevel.WARNING, XrayLogLevel.ERROR)
        AlertDialog(
            onDismissRequest = { levelDialog = false },
            title = { Text(stringResource(R.string.logs_level_title)) },
            text = {
                Column {
                    choices.forEach { choice ->
                        SelectableRow(
                            selected = choice == level,
                            onSelect = {
                                level = choice
                                LogPreferences.setLogLevel(context, choice)
                                levelDialog = false
                            },
                            label = stringResource(levelLabelRes(choice)),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { levelDialog = false }) {
                    Text(stringResource(R.string.logs_dialog_cancel))
                }
            }
        )
    }

    if (bufferDialog) {
        AlertDialog(
            onDismissRequest = { bufferDialog = false },
            title = { Text(stringResource(R.string.logs_buffer_title)) },
            text = {
                Column {
                    LogPreferences.BUFFER_PRESETS.forEach { preset ->
                        SelectableRow(
                            selected = preset == buffer,
                            onSelect = {
                                buffer = preset
                                LogPreferences.setBufferLines(context, preset)
                                LogRepository.setMaxLines(preset)
                                bufferDialog = false
                            },
                            label = stringResource(R.string.logs_buffer_lines, preset),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { bufferDialog = false }) {
                    Text(stringResource(R.string.logs_dialog_cancel))
                }
            }
        )
    }

    pendingShare?.let { action ->
        // Recomputed live so the counts stay accurate even if lines arrive while it's open.
        val bounded = LogShareBudget.bound(logs)
        AlertDialog(
            onDismissRequest = { pendingShare = null },
            title = { Text(stringResource(R.string.logs_large_title)) },
            text = {
                Text(stringResource(
                    R.string.logs_large_message, bounded.totalLines, bounded.includedLines))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingShare = null
                    when (action) {
                        LogShareAction.COPY -> runCopy(context, resources, bounded)
                        LogShareAction.SHARE -> runShare(context, resources, bounded)
                    }
                }) {
                    Text(stringResource(
                        if (action == LogShareAction.COPY) R.string.logs_large_copy_recent
                        else R.string.logs_large_share_recent))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingShare = null }) {
                    Text(stringResource(R.string.logs_dialog_cancel))
                }
            }
        )
    }
}

/** A full-width, fully-tappable radio row (the whole row is the hit target, not just the button). */
@Composable
private fun SelectableRow(selected: Boolean, onSelect: () -> Unit, label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // onClick = null: the row's selectable owns the click, so the button doesn't
        // double-handle it (and the row stays a single accessibility node).
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

private fun runCopy(context: Context, resources: Resources, bounded: BoundedLog) {
    val ok = performCopy(context, bounded.text)
    val message = when {
        !ok -> resources.getString(R.string.logs_share_failed_toast)
        bounded.truncated -> resources.getString(
            R.string.logs_copied_truncated_toast, bounded.includedLines, bounded.totalLines)
        else -> resources.getString(R.string.logs_copied_toast)
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun runShare(context: Context, resources: Resources, bounded: BoundedLog) {
    if (!performShare(context, bounded.text)) {
        Toast.makeText(context, resources.getString(R.string.logs_share_failed_toast),
            Toast.LENGTH_SHORT).show()
    }
}

// The Binder-crossing calls are wrapped so an unexpected TransactionTooLargeException (or a
// missing share target) degrades to a toast instead of an uncaught throw that would take down
// the whole process — and with it the VpnService. The byte budget already keeps us clear of
// the limit; this is the belt-and-suspenders backstop.
private fun performCopy(context: Context, text: String): Boolean = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("logs", text))
}.isSuccess

private fun performShare(context: Context, text: String): Boolean = runCatching {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}.isSuccess
