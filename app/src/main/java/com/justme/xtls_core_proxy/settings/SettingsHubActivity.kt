package com.justme.xtls_core_proxy.settings

import android.content.Intent
import android.os.Bundle
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LanguageSettingsActivity
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.killswitch.KillSwitchSettingsActivity
import com.justme.xtls_core_proxy.sideload.SideloadWarningDialog
import com.justme.xtls_core_proxy.split.SplitTunnelSettingsActivity
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

/**
 * Top-level settings hub. Single entry point from MainActivity; lists each
 * sub-settings screen (Split tunneling, Kill on foreground, ServerSettings if
 * it shouldn't have its own entry — see Task 5).
 */
class SettingsHubActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XTLS_CORE_PROXYTheme {
                SettingsHubScreen(
                    onBack = { finish() },
                    onOpenLanguage = {
                        startActivity(Intent(this, LanguageSettingsActivity::class.java))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHubScreen(
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
) {
    val context = LocalContext.current
    var showSideloadWarning by remember { mutableStateOf(false) }
    val currentLang = SupportedLanguage.current(context)
    val langLabel = when (currentLang) {
        SupportedLanguage.AUTO -> stringResource(R.string.lang_auto)
        SupportedLanguage.ENGLISH -> stringResource(R.string.lang_english)
        SupportedLanguage.RUSSIAN -> stringResource(R.string.lang_russian)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_hub_cd_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_split_title),
                subtitle = stringResource(R.string.settings_split_subtitle),
                onClick = {
                    context.startActivity(Intent(context, SplitTunnelSettingsActivity::class.java))
                }
            )
            HorizontalDivider()
            SettingsRow(
                title = stringResource(R.string.settings_kill_title),
                subtitle = stringResource(R.string.settings_kill_subtitle),
                onClick = {
                    context.startActivity(Intent(context, KillSwitchSettingsActivity::class.java))
                }
            )
            HorizontalDivider()
            SettingsRow(
                title = stringResource(R.string.settings_language_title),
                subtitle = langLabel,
                onClick = onOpenLanguage
            )
            HorizontalDivider()
            SettingsRow(
                title = stringResource(R.string.settings_sideload_title),
                subtitle = stringResource(R.string.settings_sideload_subtitle),
                onClick = { showSideloadWarning = true }
            )
            if (showSideloadWarning) {
                SideloadWarningDialog(onDismiss = { showSideloadWarning = false })
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
}
