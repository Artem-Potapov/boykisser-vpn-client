package com.justme.xtls_core_proxy.failover

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

/** Test handle for the enable switch — shared with FailoverSettingsPersistTest so it can't drift. */
internal const val FAILOVER_ENABLED_SWITCH_TAG = "failover_enabled_switch"

class FailoverSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { FailoverScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FailoverScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { FailoverPreferences.load(context) }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var interval by remember { mutableStateOf(initial.probeIntervalMs.toString()) }
    var timeout by remember { mutableStateOf(initial.probeTimeoutMs.toString()) }
    var threshold by remember { mutableStateOf(initial.failureThreshold.toString()) }
    var maxRotations by remember { mutableStateOf(initial.maxRotations.toString()) }

    // Single source of truth for "what is actually persisted right now", kept in step with
    // persist()'s own `lastGood` re-read so display validity (below) never disagrees with what
    // gets saved. Seeded from `initial`, then advanced after every persist() call.
    var lastPersisted by remember { mutableStateOf(initial) }

    fun persist() {
        // Per-control autosave (reference: PingTestSettingsActivity.persist() /
        // XraySettingsActivity.persist()). Each invalid field holds its own last-good PERSISTED
        // value and never blocks the rest of the tuple; resolveFailoverSettings re-reads prefs
        // (via `lastGood`) rather than `initial`, so a valid edit made earlier this session is
        // kept. coerce() inside save() is the final backstop for the timeout < interval invariant.
        val lastGood = FailoverPreferences.load(context)
        FailoverPreferences.save(
            context,
            resolveFailoverSettings(enabled, interval, timeout, threshold, maxRotations, lastGood),
        )
        // Re-read (not the pre-coerce `resolveFailoverSettings` result) so lastPersisted reflects
        // exactly what save()/coerce() actually stored.
        lastPersisted = FailoverPreferences.load(context)
    }

    // Display-only validity, derived the same way persist() derives its effective interval and
    // ceiling — used solely to drive isError/supportingText on this screen; the actual saved
    // value always comes from resolveFailoverSettings() against a freshly-read FailoverPreferences
    // .load(context), not this composable's state. Falls back to `lastPersisted` (updated after
    // every persist() call), not the screen-open-time `initial`, so this can't go stale relative
    // to an edit made earlier in the same session.
    val effectiveIntervalForDisplay = interval.trim().toLongOrNull()
        ?.takeIf { it in FailoverPreferences.INTERVAL_MIN..FailoverPreferences.INTERVAL_MAX }
        ?: lastPersisted.probeIntervalMs
    val intervalValid = interval.trim().toLongOrNull()
        ?.let { it in FailoverPreferences.INTERVAL_MIN..FailoverPreferences.INTERVAL_MAX } == true
    // Ceiling mirrors FailoverPreferences.coerce() exactly (interval - TIMEOUT_HEADROOM_MS,
    // floored at TIMEOUT_MIN) — NOT interval - 1 — so this can't show "no error" for a value
    // coerce() would silently rewrite downward on save.
    val timeoutCeilingForDisplay = (effectiveIntervalForDisplay - FailoverPreferences.TIMEOUT_HEADROOM_MS)
        .coerceAtLeast(FailoverPreferences.TIMEOUT_MIN)
    val timeoutValid = timeout.trim().toLongOrNull()
        ?.let { it in FailoverPreferences.TIMEOUT_MIN..timeoutCeilingForDisplay } == true
    val thresholdValid = threshold.trim().toIntOrNull()
        ?.let { it in FailoverPreferences.THRESHOLD_MIN..FailoverPreferences.THRESHOLD_MAX } == true
    val maxRotationsValid = maxRotations.trim().toIntOrNull()
        ?.let { it in FailoverPreferences.ROTATIONS_MIN..FailoverPreferences.ROTATIONS_MAX } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.failover_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.failover_cd_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.failover_enabled), modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; persist() },
                    modifier = Modifier.testTag(FAILOVER_ENABLED_SWITCH_TAG),
                )
            }
            Text(stringResource(R.string.failover_enabled_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = it; persist() },
                label = { Text(stringResource(R.string.failover_interval)) },
                isError = !intervalValid,
                supportingText = {
                    if (!intervalValid) Text(stringResource(R.string.failover_interval_error))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = timeout,
                onValueChange = { timeout = it; persist() },
                label = { Text(stringResource(R.string.failover_timeout)) },
                isError = !timeoutValid,
                supportingText = {
                    if (!timeoutValid) Text(stringResource(R.string.failover_timeout_error))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = threshold,
                onValueChange = { threshold = it; persist() },
                label = { Text(stringResource(R.string.failover_threshold)) },
                isError = !thresholdValid,
                supportingText = {
                    if (!thresholdValid) Text(stringResource(R.string.failover_threshold_error))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = maxRotations,
                onValueChange = { maxRotations = it; persist() },
                label = { Text(stringResource(R.string.failover_max_rotations)) },
                isError = !maxRotationsValid,
                supportingText = {
                    if (!maxRotationsValid) Text(stringResource(R.string.failover_max_rotations_error))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.failover_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
