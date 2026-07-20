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
import com.justme.xtls_core_proxy.config.MuxPreferences
import com.justme.xtls_core_proxy.config.MuxSettings
import com.justme.xtls_core_proxy.config.QuicHandling
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.components.DropdownField
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class MuxSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { MuxScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MuxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { MuxPreferences.load(context) }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var concurrency by remember { mutableStateOf(initial.concurrency.toString()) }
    var xudp by remember { mutableStateOf(initial.xudpConcurrency.toString()) }
    var quic by remember { mutableStateOf(initial.quicHandling) }

    fun persist() {
        MuxPreferences.save(
            context,
            MuxSettings(
                enabled,
                concurrency.trim().toIntOrNull()?.coerceIn(1, 1024) ?: initial.concurrency,
                xudp.trim().toIntOrNull()?.coerceAtLeast(0) ?: initial.xudpConcurrency,
                quic
            )
        )
    }

    val concurrencyValid = concurrency.trim().toIntOrNull()?.let { it in 1..1024 } == true
    val xudpValid = xudp.trim().toIntOrNull()?.let { it >= 0 } == true
    val inputsValid = concurrencyValid && xudpValid

    // DropdownField is String-keyed (verified: ui/components/DropdownField.kt) — map via enum name.
    val quicOptions = listOf(
        QuicHandling.BLOCK.name to stringResource(R.string.mux_quic_block),
        QuicHandling.ALLOW.name to stringResource(R.string.mux_quic_allow),
        QuicHandling.SKIP.name to stringResource(R.string.mux_quic_skip),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mux_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mux_cd_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mux_enable), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it; persist() })
            }
            OutlinedTextField(
                value = concurrency, onValueChange = { concurrency = it; persist() },
                label = { Text(stringResource(R.string.mux_concurrency)) },
                isError = enabled && !concurrencyValid,
                supportingText = { if (enabled && !concurrencyValid) Text(stringResource(R.string.mux_error_concurrency)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = xudp, onValueChange = { xudp = it; persist() },
                label = { Text(stringResource(R.string.mux_xudp_concurrency)) },
                isError = enabled && !xudpValid, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            DropdownField(
                value = quic.name, onValueChange = { quic = QuicHandling.valueOf(it); persist() },
                label = stringResource(R.string.mux_quic_handling_label),
                options = quicOptions, modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.mux_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
