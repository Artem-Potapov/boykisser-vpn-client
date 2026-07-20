package com.justme.xtls_core_proxy.settings

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.config.FragmentationPreferences
import com.justme.xtls_core_proxy.config.FragmentationSettings
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.components.DropdownField
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

private const val PRESET_TLSHELLO = "tlshello_preset"
private const val PRESET_AGGRESSIVE = "aggressive_preset"
private const val PRESET_CUSTOM = "custom_preset"

private val PACKETS_RE = Regex("""^(tlshello|\d+(-\d+)?)$""")
private val RANGE_RE = Regex("""^\d+(-\d+)?$""")

private val AGGRESSIVE = FragmentationSettings(enabled = true, packets = "1-3", length = "10-20", interval = "10-20")
private val TLSHELLO = FragmentationSettings.DISABLED.copy(enabled = true)

class FragmentationSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { FragmentationScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FragmentationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { FragmentationPreferences.load(context) }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var packets by remember { mutableStateOf(initial.packets) }
    var length by remember { mutableStateOf(initial.length) }
    var interval by remember { mutableStateOf(initial.interval) }
    var preset by remember {
        mutableStateOf(
            when {
                packets == TLSHELLO.packets && length == TLSHELLO.length && interval == TLSHELLO.interval -> PRESET_TLSHELLO
                packets == AGGRESSIVE.packets && length == AGGRESSIVE.length && interval == AGGRESSIVE.interval -> PRESET_AGGRESSIVE
                else -> PRESET_CUSTOM
            }
        )
    }

    fun persist() {
        val p = packets.trim(); val l = length.trim(); val iv = interval.trim()
        val valid = PACKETS_RE.matches(p) && RANGE_RE.matches(l) && RANGE_RE.matches(iv)
        if (!enabled || valid) {
            FragmentationPreferences.save(context, FragmentationSettings(enabled, p, l, iv))
        }
    }

    val packetsValid = PACKETS_RE.matches(packets.trim())
    val lengthValid = RANGE_RE.matches(length.trim())
    val intervalValid = RANGE_RE.matches(interval.trim())
    val inputsValid = packetsValid && lengthValid && intervalValid

    val presetOptions = listOf(
        PRESET_TLSHELLO to stringResource(R.string.fragmentation_preset_tlshello),
        PRESET_AGGRESSIVE to stringResource(R.string.fragmentation_preset_aggressive),
        PRESET_CUSTOM to stringResource(R.string.fragmentation_preset_custom),
    )

    fun applyPreset(id: String) {
        preset = id
        when (id) {
            PRESET_TLSHELLO -> { packets = TLSHELLO.packets; length = TLSHELLO.length; interval = TLSHELLO.interval }
            PRESET_AGGRESSIVE -> { packets = AGGRESSIVE.packets; length = AGGRESSIVE.length; interval = AGGRESSIVE.interval }
            PRESET_CUSTOM -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fragmentation_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.fragmentation_cd_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding)
                .padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.fragmentation_enable), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it; persist() })
            }

            DropdownField(
                value = preset,
                onValueChange = { applyPreset(it); persist() },
                label = stringResource(R.string.fragmentation_preset_label),
                options = presetOptions,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = packets,
                onValueChange = { packets = it; preset = PRESET_CUSTOM; persist() },
                label = { Text(stringResource(R.string.fragmentation_packets)) },
                isError = !packetsValid,
                supportingText = {
                    if (!packetsValid) Text(stringResource(R.string.fragmentation_error_packets))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = length,
                onValueChange = { length = it; preset = PRESET_CUSTOM; persist() },
                label = { Text(stringResource(R.string.fragmentation_length)) },
                isError = !lengthValid,
                supportingText = {
                    if (!lengthValid) Text(stringResource(R.string.fragmentation_error_range))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = it; preset = PRESET_CUSTOM; persist() },
                label = { Text(stringResource(R.string.fragmentation_interval)) },
                isError = !intervalValid,
                supportingText = {
                    if (!intervalValid) Text(stringResource(R.string.fragmentation_error_range))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.fragmentation_hint),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
