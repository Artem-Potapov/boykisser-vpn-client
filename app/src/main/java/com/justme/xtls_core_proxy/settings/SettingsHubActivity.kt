package com.justme.xtls_core_proxy.settings

import android.content.Intent
import android.os.Bundle
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LanguageSettingsActivity
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.killswitch.KillSwitchSettingsActivity
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsHubScreen()
                }
            }
        }
    }
}

@Composable
private fun SettingsHubScreen() {
    val context = LocalContext.current
    val currentLang = SupportedLanguage.current()
    val langLabel = when (currentLang) {
        SupportedLanguage.AUTO -> stringResource(R.string.settings_language_subtitle_auto)
        SupportedLanguage.ENGLISH -> stringResource(R.string.lang_english)
        SupportedLanguage.RUSSIAN -> stringResource(R.string.lang_russian)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))

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
            onClick = {
                context.startActivity(Intent(context, LanguageSettingsActivity::class.java))
            }
        )
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
