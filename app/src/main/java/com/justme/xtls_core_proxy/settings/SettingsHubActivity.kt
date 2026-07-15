package com.justme.xtls_core_proxy.settings

import android.content.Intent
import android.os.Bundle
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.BuildConfig
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LanguageSettingsActivity
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.killswitch.KillSwitchSettingsActivity
import com.justme.xtls_core_proxy.log.LogsActivity
import com.justme.xtls_core_proxy.sideload.SideloadWarningDialog
import com.justme.xtls_core_proxy.split.SplitTunnelSettingsActivity
import com.justme.xtls_core_proxy.ui.SettingsRow
import com.justme.xtls_core_proxy.ui.SettingsSectionHeader
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

/**
 * Top-level settings hub. Single entry point from MainActivity; a sectioned,
 * single-scroll list of every sub-settings screen. Debug-only placeholder rows
 * (and the whole Advanced section) render only under [BuildConfig.DEBUG].
 */
class SettingsHubActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { SettingsHubScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHubScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showSideloadWarning by remember { mutableStateOf(false) }
    var showAutoConnectInfo by remember { mutableStateOf(false) }
    val badge = stringResource(R.string.settings_badge_debug)
    val langLabel = when (SupportedLanguage.current(context)) {
        SupportedLanguage.AUTO -> stringResource(R.string.lang_auto)
        SupportedLanguage.ENGLISH -> stringResource(R.string.lang_english)
        SupportedLanguage.RUSSIAN -> stringResource(R.string.lang_russian)
    }
    fun open(cls: Class<*>) = context.startActivity(Intent(context, cls))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_hub_cd_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding)
                .padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
        ) {
            // UI
            SettingsSectionHeader(stringResource(R.string.settings_section_ui))
            SettingsRow(
                title = stringResource(R.string.settings_language_title),
                trailingValue = langLabel,
                leadingIcon = Icons.Default.Settings,
                onClick = { open(LanguageSettingsActivity::class.java) }
            )
            HorizontalDivider()
            SettingsRow(
                title = stringResource(R.string.settings_appearance_title),
                subtitle = stringResource(R.string.settings_appearance_subtitle),
                onClick = { open(AppearanceSettingsActivity::class.java) }
            )

            // Tunnel
            SettingsSectionHeader(stringResource(R.string.settings_section_tunnel))
            SettingsRow(
                title = stringResource(R.string.settings_split_title),
                subtitle = stringResource(R.string.settings_split_subtitle),
                onClick = { open(SplitTunnelSettingsActivity::class.java) }
            )
            HorizontalDivider()
            SettingsRow(
                title = stringResource(R.string.settings_kill_title),
                subtitle = stringResource(R.string.settings_kill_subtitle),
                onClick = { open(KillSwitchSettingsActivity::class.java) }
            )
            HorizontalDivider()
            SettingsRow(
                title = stringResource(R.string.settings_autoconnect_title),
                subtitle = stringResource(R.string.settings_autoconnect_subtitle),
                onClick = { showAutoConnectInfo = true }
            )
            HorizontalDivider()
            SettingsRow(
                title = stringResource(R.string.settings_fragmentation_title),
                subtitle = stringResource(R.string.settings_fragmentation_subtitle),
                onClick = { open(FragmentationSettingsActivity::class.java) }
            )
            if (BuildConfig.DEBUG) {
                SettingsRow(title = stringResource(R.string.settings_ph_mux), enabled = false, badge = badge)
            }

            // Advanced (whole section debug-only)
            if (BuildConfig.DEBUG) {
                SettingsSectionHeader(stringResource(R.string.settings_section_advanced))
                SettingsRow(title = stringResource(R.string.settings_ph_xray), enabled = false, badge = badge)
                SettingsRow(title = stringResource(R.string.settings_ph_dns), enabled = false, badge = badge)
                SettingsRow(title = stringResource(R.string.settings_ph_sanitization), enabled = false, badge = badge)
                SettingsRow(title = stringResource(R.string.settings_ph_routing), enabled = false, badge = badge)
            }

            // Diagnostics
            SettingsSectionHeader(stringResource(R.string.settings_section_diagnostics))
            SettingsRow(
                title = stringResource(R.string.settings_logs_title),
                leadingIcon = Icons.AutoMirrored.Filled.List,
                onClick = { open(LogsActivity::class.java) }
            )
            if (BuildConfig.DEBUG) SettingsRow(
                title = stringResource(R.string.settings_ph_ping), enabled = false, badge = badge
            )

            // About
            SettingsSectionHeader(stringResource(R.string.settings_section_about))
            SettingsRow(
                title = stringResource(R.string.settings_sideload_title),
                subtitle = stringResource(R.string.settings_sideload_subtitle),
                leadingIcon = Icons.Default.Warning,
                onClick = { showSideloadWarning = true }
            )
            HorizontalDivider()
            SettingsRow(
                title = stringResource(R.string.settings_about_title),
                leadingIcon = Icons.Default.Info,
                onClick = { open(AboutActivity::class.java) }
            )
            if (BuildConfig.DEBUG) SettingsRow(
                title = stringResource(R.string.settings_ph_check_update), enabled = false, badge = badge
            )
        }
        if (showSideloadWarning) {
            SideloadWarningDialog(onDismiss = { showSideloadWarning = false })
        }
        if (showAutoConnectInfo) {
            AutoConnectInfoDialog(onDismiss = { showAutoConnectInfo = false })
        }
    }
}
