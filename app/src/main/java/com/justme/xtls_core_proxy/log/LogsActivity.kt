package com.justme.xtls_core_proxy.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.config.XrayLogLevel
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.SettingsRow
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme
import android.widget.Toast
import kotlinx.coroutines.launch

class LogsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { LogsScreen(onBack = { finish() }) } }
    }
}

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
                    // Clear as the one toolbar icon; Copy/Share/Export live in the overflow
                    // menu (material-icons-core has no dedicated copy icon, and adding
                    // material-icons-extended is forbidden by Global Constraints).
                    IconButton(onClick = { LogRepository.clear() }) {
                        Icon(Icons.Default.Clear, contentDescription =
                            stringResource(R.string.logs_action_clear))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription =
                            stringResource(R.string.logs_action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logs_action_copy)) },
                            onClick = {
                                menuOpen = false
                                copyLogs(context, logs.joinToString("\n"))
                                Toast.makeText(context, resources.getString(R.string.logs_copied_toast),
                                    Toast.LENGTH_SHORT).show()
                            })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logs_action_share)) },
                            onClick = { menuOpen = false; shareLogs(context, logs.joinToString("\n")) })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logs_action_export)) },
                            onClick = { menuOpen = false; exportLauncher.launch("boykisser-log.txt") })
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
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = choice == level, onClick = {
                                level = choice
                                LogPreferences.setLogLevel(context, choice)
                                levelDialog = false
                            })
                            Text(stringResource(levelLabelRes(choice)))
                        }
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
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = preset == buffer, onClick = {
                                buffer = preset
                                LogPreferences.setBufferLines(context, preset)
                                LogRepository.setMaxLines(preset)
                                bufferDialog = false
                            })
                            Text(stringResource(R.string.logs_buffer_lines, preset))
                        }
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
}

private fun copyLogs(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("logs", text))
}

private fun shareLogs(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}
