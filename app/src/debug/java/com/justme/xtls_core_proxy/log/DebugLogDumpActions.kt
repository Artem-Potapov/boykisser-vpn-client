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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOGCAT_TAG = "XTLS.LogDump"

/** The one process-global holder the menu actions and the composable share. */
private val dumpStatusHolder = DebugLogDumpStatusHolder()

/**
 * Process-scoped dump work outlives the Logs screen's composition: closing the overflow menu does
 * not cancel a launched dump. Status survives navigation for the rest of the process or until the
 * next request, by design.
 */
private val dumpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Debug-only Logs overflow-menu items ("Dump to tmp file" / "Dump to logcat"). Lives in
 * the debug source set: the release source set ships empty stubs, so no labels, actions,
 * or dump logic are packaged in release builds.
 */
@Composable
fun DebugLogDumpMenuItems(logs: List<String>, closeMenu: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    DropdownMenuItem(
        text = { Text(stringResource(R.string.debug_logs_dump_to_file)) },
        onClick = {
            closeMenu()
            dumpScope.launch {
                // begin() must share Main with complete()/toast presentation; the dispatcher hop
                // is the production ordering guarantee, not an incidental click-thread detail.
                val requestId = withContext(Dispatchers.Main) { dumpStatusHolder.begin() }
                runFileDump(requestId, context, resources, logs)
            }
        })
    DropdownMenuItem(
        text = { Text(stringResource(R.string.debug_logs_dump_to_logcat)) },
        onClick = {
            closeMenu()
            dumpScope.launch {
                val requestId = withContext(Dispatchers.Main) { dumpStatusHolder.begin() }
                runLogcatDump(requestId, context, resources, logs)
            }
        })
}

/**
 * Persistent debug-only status shown on the Logs screen: only the latest requested action's
 * state/outcome (in-flight, file path, logcat chunk count, or failure). Renders nothing until a
 * dump runs, so the screen is unchanged before first use.
 */
@Composable
fun DebugLogDumpStatus() {
    val status by dumpStatusHolder.status.collectAsState()
    if (status == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        status?.filePath?.let {
            Text(
                stringResource(R.string.debug_logs_dump_status_file, it),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }
        status?.logcatChunks?.let {
            Text(
                stringResource(R.string.debug_logs_dump_status_logcat, it, LOGCAT_TAG),
                style = MaterialTheme.typography.bodySmall)
        }
        status?.failure?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private suspend fun runFileDump(
    requestId: Long, context: Context, resources: Resources, logs: List<String>,
) {
    try {
        val marker = resolveDumpMarker(context, logs)
        val dumpText = DebugLogDumper.renderDump(logs, marker) + "\n"
        val externalDir = withContext(Dispatchers.IO) { context.getExternalFilesDir(null) }
            ?: throw IllegalStateException("external files dir unavailable")
        val file = withContext(Dispatchers.IO) {
            DebugLogDumper.writeDumpFile(File(externalDir, "tmp"), dumpText, Instant.now())
        }
        publishAndToast(
            DebugLogDumpStatusReducer.fileWritten(
                requestId, file.absolutePath, System.currentTimeMillis()),
            context, resources) { r -> r.getString(R.string.debug_logs_dump_toast_file) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportFailure(requestId, context, resources, e)
    }
}

private suspend fun runLogcatDump(
    requestId: Long, context: Context, resources: Resources, logs: List<String>,
) {
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
        publishAndToast(
            DebugLogDumpStatusReducer.logcatWritten(
                requestId, chunks.size, System.currentTimeMillis()),
            context, resources) { r ->
                r.getString(R.string.debug_logs_dump_toast_logcat, chunks.size)
            }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        reportFailure(requestId, context, resources, e)
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

private suspend fun reportFailure(
    requestId: Long, context: Context, resources: Resources, error: Exception,
) {
    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    publishAndToast(
        DebugLogDumpStatusReducer.failed(
            requestId,
            resources.getString(R.string.debug_logs_dump_failed, detail),
            System.currentTimeMillis()),
        context, resources) { r -> r.getString(R.string.debug_logs_dump_failed, detail) }
}

/**
 * The single production boundary for begin/complete/present: [outcome] is published atomically on
 * Main, and only when that publication is accepted is its Toast formatted and shown in the same
 * non-suspending Main segment. A newer Main click therefore always wins over a queued older
 * completion; no second eligibility state crosses dispatchers. Toast creation/formatting failures
 * are presentation-only and cannot replace or crash out of the completed dump outcome.
 */
private suspend fun publishAndToast(
    outcome: DebugLogDumpStatus,
    context: Context,
    resources: Resources,
    messageFactory: (Resources) -> String,
) {
    withContext(Dispatchers.Main) {
        if (dumpStatusHolder.complete(outcome)) {
            runCatching { Toast.makeText(context, messageFactory(resources), Toast.LENGTH_SHORT).show() }
        }
    }
}
