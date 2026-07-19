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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.state.PingPreferences
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class PingTestSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { PingTestScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PingTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { PingPreferences.load(context) }

    var target by rememberSaveable { mutableStateOf(initial.targetUrl) }
    var timeout by rememberSaveable { mutableStateOf(initial.timeoutMs.toString()) }
    var concurrency by rememberSaveable { mutableStateOf(initial.concurrency.toString()) }
    var auto by rememberSaveable { mutableStateOf(initial.autoOnOpen) }

    val targetValid = PingPreferences.isValidTarget(target)
    val timeoutValid = timeout.trim().toLongOrNull()
        ?.let { it in PingPreferences.TIMEOUT_MIN..PingPreferences.TIMEOUT_MAX } == true
    val concurrencyValid = concurrency.trim().toIntOrNull()
        ?.let { it in PingPreferences.CONCURRENCY_MIN..PingPreferences.CONCURRENCY_MAX } == true
    val inputsValid = targetValid && timeoutValid && concurrencyValid

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ping_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ping_cd_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = inputsValid,
                        onClick = {
                            PingPreferences.save(
                                context,
                                PingPreferences(
                                    targetUrl = target.trim(),
                                    timeoutMs = timeout.trim().toLong(),
                                    concurrency = concurrency.trim().toInt(),
                                    autoOnOpen = auto,
                                )
                            )
                            onBack()
                        }
                    ) { Text(stringResource(R.string.ping_save)) }
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
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text(stringResource(R.string.ping_target)) },
                isError = !targetValid,
                supportingText = {
                    if (!targetValid) Text(stringResource(R.string.ping_target_error))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = timeout,
                onValueChange = { timeout = it },
                label = { Text(stringResource(R.string.ping_timeout)) },
                isError = !timeoutValid,
                supportingText = {
                    if (!timeoutValid) Text(stringResource(R.string.ping_timeout_error))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = concurrency,
                onValueChange = { concurrency = it },
                label = { Text(stringResource(R.string.ping_concurrency)) },
                isError = !concurrencyValid,
                supportingText = {
                    if (!concurrencyValid) Text(stringResource(R.string.ping_concurrency_error))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.ping_auto), modifier = Modifier.weight(1f))
                Switch(checked = auto, onCheckedChange = { auto = it })
            }
            Text(
                text = stringResource(R.string.ping_hint),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
