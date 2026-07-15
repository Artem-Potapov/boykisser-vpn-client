package com.justme.xtls_core_proxy.settings

import android.os.Build
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.SettingsRow
import com.justme.xtls_core_proxy.ui.SettingsSectionHeader
import com.justme.xtls_core_proxy.ui.theme.AppearanceRepository
import com.justme.xtls_core_proxy.ui.theme.ThemeMode
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme
import com.justme.xtls_core_proxy.ui.theme.useDynamic

class AppearanceSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { AppearanceScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs by AppearanceRepository.state.collectAsState()
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val modeLabels = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.appearance_mode_system),
        ThemeMode.LIGHT to stringResource(R.string.appearance_mode_light),
        ThemeMode.DARK to stringResource(R.string.appearance_mode_dark),
        ThemeMode.TRUE_DARK to stringResource(R.string.appearance_mode_true_dark),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.appearance_cd_back)
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
            SettingsSectionHeader(stringResource(R.string.appearance_section_theme))
            modeLabels.forEach { (mode, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = prefs.themeMode == mode,
                            onClick = { AppearanceRepository.setThemeMode(context, mode) }
                        )
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(
                        selected = prefs.themeMode == mode,
                        onClick = { AppearanceRepository.setThemeMode(context, mode) }
                    )
                    Text(label)
                }
            }

            if (dynamicAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.appearance_dynamic_color_title))
                        Text(stringResource(R.string.appearance_dynamic_color_subtitle))
                    }
                    Switch(
                        checked = useDynamic(prefs.dynamicColor, Build.VERSION.SDK_INT),
                        onCheckedChange = { AppearanceRepository.setDynamicColor(context, it) }
                    )
                }
            }
        }
    }
}
