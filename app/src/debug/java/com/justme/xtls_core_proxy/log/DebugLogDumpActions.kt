package com.justme.xtls_core_proxy.log

import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.state.ActiveProfileRepository
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOGCAT_TAG = "XTLS.LogDump"

/**
 * Process-global results of the last debug dump, rendered persistently by
 * [DebugLogDumpStatus] so autonomous QA agents can read the exact emitted path (and any
 * failure) via `uiautomator dump` after the menu has closed. The redacted buffer itself
 * is never exposed here — only paths, counts, and error summaries.
 */
object DebugLogDumpStatusHolder {
    val dumpedFilePath = MutableStateFlow<String?>(null)
    val logcatChunks = MutableStateFlow<Int?>(null)
    val failure = MutableStateFlow<String?>(null)
}

/**
 * Debug-only Logs overflow-menu items ("Dump to tmp file" / "Dump to logcat"). Lives in
 * the debug source set: the release source set ships empty stubs, so no labels, actions,
 * or dump logic are packaged in release builds.
 */
@Composable
fun DebugLogDumpMenuItems(logs: List<String>, closeMenu: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.debug_logs_dump_to_file)) },
        onClick = {
            closeMenu()
            scope.launch { runFileDump(context, resources, logs) }
        })
    DropdownMenuItem(
        text = { Text(stringResource(R.string.debug_logs_dump_to_logcat)) },
        onClick = {
            closeMenu()
            scope.launch { runLogcatDump(context, resources, logs) }
        })
}

/**
 * Persistent debug-only status shown on the Logs screen: the exact pullable path of the
 * last file dump, the last logcat dump's chunk count, and any failure summary. Renders
 * nothing until a dump runs, so the screen is unchanged before first use.
 */
@Composable
fun DebugLogDumpStatus() {
    val dumpedFilePath by DebugLogDumpStatusHolder.dumpedFilePath.collectAsState()
    val logcatChunks by DebugLogDumpStatusHolder.logcatChunks.collectAsState()
    val failure by DebugLogDumpStatusHolder.failure.collectAsState()
    if (dumpedFilePath == null && logcatChunks == null && failure == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        dumpedFilePath?.let {
            Text(
                stringResource(R.string.debug_logs_dump_status_file, it),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }
        logcatChunks?.let {
            Text(
                stringResource(R.string.debug_logs_dump_status_logcat, it, LOGCAT_TAG),
                style = MaterialTheme.typography.bodySmall)
        }
        failure?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private suspend fun runFileDump(context: Context, resources: Resources, logs: List<String>) {
    try {
        val marker = resolveDumpMarker(context, logs)
        val dumpText = DebugLogDumper.renderDump(logs, marker) + "\n"
        val externalDir = withContext(Dispatchers.IO) { context.getExternalFilesDir(null) }
            ?: throw IllegalStateException("external files dir unavailable")
        val file = withContext(Dispatchers.IO) {
            DebugLogDumper.writeDumpFile(File(externalDir, "tmp"), dumpText, Instant.now())
        }
        DebugLogDumpStatusHolder.dumpedFilePath.value = file.absolutePath
        Toast.makeText(context, resources.getString(R.string.debug_logs_dump_toast_file),
            Toast.LENGTH_SHORT).show()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportFailure(context, resources, e)
    }
}

private suspend fun runLogcatDump(context: Context, resources: Resources, logs: List<String>) {
    try {
        val marker = resolveDumpMarker(context, logs)
        val dumpText = DebugLogDumper.renderDump(logs, marker) + "\n"
        val chunks = DebugLogDumper.chunkUtf8(DebugLogDumper.escapeForLogcat(dumpText))
        withContext(Dispatchers.IO) {
            val total = chunks.size.toString().padStart(4, '0')
            Log.i(LOGCAT_TAG, "log-dump begin chunks=${chunks.size} lines=${logs.size}")
            chunks.forEachIndexed { index, chunk ->
                Log.i(LOGCAT_TAG,
                    "log-dump " + (index + 1).toString().padStart(4, '0') + "/" + total +
                        " " + chunk)
            }
            Log.i(LOGCAT_TAG, "log-dump end")
        }
        DebugLogDumpStatusHolder.logcatChunks.value = chunks.size
        Toast.makeText(context,
            resources.getString(R.string.debug_logs_dump_toast_logcat, chunks.size),
            Toast.LENGTH_SHORT).show()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportFailure(context, resources, e)
    }
}

/**
 * Marker profile is resolved at dump time: the display name of the profile that is
 * CONNECTED right now, or `<none>` when disconnected or unresolvable. The Room lookup
 * and the (possibly first-access) SharedPreferences read both run off the main thread.
 */
private suspend fun resolveDumpMarker(context: Context, logs: List<String>): String =
    withContext(Dispatchers.IO) {
        val profileName = if (LogRepository.connectionState.value == VpnConnectionState.CONNECTED) {
            ActiveProfileRepository.getActiveProfileId(context)?.let { id ->
                AppDatabase.get(context).profileDao().getById(id)?.name
            }
        } else {
            null
        }
        DebugLogDumper.buildDumpMarker(Instant.now(), profileName, logs.size)
    }

private fun reportFailure(context: Context, resources: Resources, error: Exception) {
    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    val message = resources.getString(R.string.debug_logs_dump_failed, detail)
    DebugLogDumpStatusHolder.failure.value = message
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
