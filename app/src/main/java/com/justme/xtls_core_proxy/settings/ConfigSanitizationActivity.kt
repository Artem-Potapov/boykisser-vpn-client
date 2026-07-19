package com.justme.xtls_core_proxy.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.config.ConfigSanitizer
import com.justme.xtls_core_proxy.config.DnsPreferences
import com.justme.xtls_core_proxy.config.Finding
import com.justme.xtls_core_proxy.config.FragmentationPreferences
import com.justme.xtls_core_proxy.config.LogSettings
import com.justme.xtls_core_proxy.config.MuxPreferences
import com.justme.xtls_core_proxy.config.RoutingPreferences
import com.justme.xtls_core_proxy.config.SanitizationReport
import com.justme.xtls_core_proxy.config.Status
import com.justme.xtls_core_proxy.config.TuningSettings
import com.justme.xtls_core_proxy.config.XrayCorePreferences
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.log.LogPreferences
import com.justme.xtls_core_proxy.state.ActiveProfileRepository
import com.justme.xtls_core_proxy.ui.SettingsSectionHeader
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only report of how [ConfigSanitizer] rewrites the active profile under the current global
 * settings. Recomputes on every [Lifecycle.Event.ON_RESUME] so changes made in sibling settings
 * screens while this Activity stays alive are reflected (mirrors the DnsSettingsActivity observer).
 */
class ConfigSanitizationActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { ConfigSanitizationScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigSanitizationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var reloadEpoch by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<SanitizationReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var noProfile by remember { mutableStateOf(false) }

    // Re-read active profile + prefs on every resume (including the first) so global settings
    // changed while this screen stayed in the back stack are reflected without leaving.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reloadEpoch++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reloadEpoch) {
        if (reloadEpoch == 0) return@LaunchedEffect
        loading = true
        noProfile = false
        report = null
        val result = withContext(Dispatchers.Default) {
            val id = ActiveProfileRepository.getActiveProfileId(context) ?: return@withContext null
            val profile = AppDatabase.get(context).profileDao().getById(id) ?: return@withContext null
            // Analyze the SAME log posture a real session builds: the app-private error-log path
            // (see XrayVpnService). Read-only — this only forms the path string; unlike the service it
            // never creates the logs/ directory or file (the sanitizer must not touch the filesystem).
            val log = LogSettings(
                LogPreferences.getLogLevel(context),
                File(context.filesDir, "logs/xray-core.log").absolutePath,
            )
            val tuning = TuningSettings(
                fragmentation = FragmentationPreferences.load(context),
                mux = MuxPreferences.load(context),
                dns = DnsPreferences.load(context),
                routing = RoutingPreferences.load(context),
                core = XrayCorePreferences.load(context),
            )
            ConfigSanitizer.analyze(profile.config, log, tuning)
        }
        if (result == null) {
            noProfile = true
            report = null
        } else {
            noProfile = false
            report = result
        }
        loading = false
    }

    val uiState = resolveSanitizationUiState(loading, noProfile, report)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sanitization_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sanitization_cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            SanitizationUiState.Loading -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            SanitizationUiState.NoProfile -> {
                Text(
                    stringResource(R.string.sanitization_empty),
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            is SanitizationUiState.Failed -> {
                Text(
                    stringResource(R.string.sanitization_failure, state.reason),
                    modifier = Modifier
                        .padding(innerPadding)
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            is SanitizationUiState.Ready -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SettingsSectionHeader(stringResource(R.string.sanitization_group_security))
                    state.groups.security.forEach { FindingRow(it) }
                    SettingsSectionHeader(stringResource(R.string.sanitization_group_global))
                    state.groups.global.forEach { FindingRow(it) }
                }
            }
        }
    }
}

@Composable
private fun FindingRow(finding: Finding) {
    val notApplicable = statusUsesWarningContainer(finding.status)
    val statusText = when (val s = finding.status) {
        Status.Rewrote -> stringResource(R.string.sanitization_status_rewrote)
        Status.Added -> stringResource(R.string.sanitization_status_added)
        Status.AlreadyCompliant -> stringResource(R.string.sanitization_status_compliant)
        Status.Applied -> stringResource(R.string.sanitization_status_applied)
        is Status.NotApplicable -> "${stringResource(R.string.sanitization_status_na)} — ${s.reason}"
    }
    val container = if (notApplicable) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(findingTitleRes(finding.id)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Surface(color = container, shape = MaterialTheme.shapes.small) {
                Text(
                    statusText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (finding.detail.isNotBlank()) {
            Text(
                finding.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
