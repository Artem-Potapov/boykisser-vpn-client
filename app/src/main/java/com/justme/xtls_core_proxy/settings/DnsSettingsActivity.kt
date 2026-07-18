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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.config.DnsPreferences
import com.justme.xtls_core_proxy.config.DnsQueryStrategy
import com.justme.xtls_core_proxy.config.DnsResolver
import com.justme.xtls_core_proxy.config.DnsSettings
import com.justme.xtls_core_proxy.config.DohUrl
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.components.DropdownField
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { DnsScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { DnsPreferences.load(context) }

    var resolver by remember { mutableStateOf(initial.resolver) }
    var customUrl by remember { mutableStateOf(initial.customUrl) }
    var pinnedIp by remember { mutableStateOf(initial.customPinnedIp) }
    var strategy by remember { mutableStateOf(initial.queryStrategy) }
    var resolveError by remember { mutableStateOf(false) }

    val isCustom = resolver == DnsResolver.CUSTOM
    val urlValid = !isCustom || DohUrl.isValidHttps(customUrl)
    val customHost = if (isCustom) DohUrl.host(customUrl) else null
    val needsPin = customHost != null && !isIpLiteralClient(customHost)
    val pinValid = !needsPin || pinnedIp.trim().isNotBlank()
    val canSave = urlValid && pinValid

    // DropdownField is String-keyed (see Task 5) — options map via enum name.
    val resolverOptions = listOf(
        DnsResolver.FROM_CONFIG.name to stringResource(R.string.dns_resolver_from_config),
        DnsResolver.CLOUDFLARE.name to stringResource(R.string.dns_resolver_cloudflare),
        DnsResolver.GOOGLE.name to stringResource(R.string.dns_resolver_google),
        DnsResolver.QUAD9.name to stringResource(R.string.dns_resolver_quad9),
        DnsResolver.ADGUARD.name to stringResource(R.string.dns_resolver_adguard),
        DnsResolver.CUSTOM.name to stringResource(R.string.dns_resolver_custom),
    )
    val strategyOptions = listOf(
        DnsQueryStrategy.USE_IP.name to stringResource(R.string.dns_strategy_use_ip),
        DnsQueryStrategy.USE_IPV4.name to stringResource(R.string.dns_strategy_use_ipv4),
        DnsQueryStrategy.USE_IPV6.name to stringResource(R.string.dns_strategy_use_ipv6),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dns_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.dns_cd_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            DnsPreferences.save(context, DnsSettings(resolver, customUrl.trim(), pinnedIp.trim(), strategy))
                            onBack()
                        }
                    ) { Text(stringResource(R.string.dns_save)) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DropdownField(
                value = resolver.name, onValueChange = { resolver = DnsResolver.valueOf(it) },
                label = stringResource(R.string.dns_resolver_label), options = resolverOptions,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            if (isCustom) {
                OutlinedTextField(
                    value = customUrl, onValueChange = { customUrl = it; resolveError = false },
                    label = { Text(stringResource(R.string.dns_custom_url)) },
                    isError = !urlValid,
                    supportingText = { if (!urlValid) Text(stringResource(R.string.dns_custom_url_error)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (needsPin) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pinnedIp, onValueChange = { pinnedIp = it },
                            label = { Text(stringResource(R.string.dns_pinned_ip)) },
                            isError = !pinValid || resolveError,
                            supportingText = {
                                if (resolveError) Text(stringResource(R.string.dns_resolve_failed))
                                else if (!pinValid) Text(stringResource(R.string.dns_pinned_ip_error))
                            },
                            singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                val host = customHost ?: return@OutlinedButton
                                scope.launch {
                                    val ip = withContext(Dispatchers.IO) { DohUrl.resolveHostname(host) }
                                    if (ip != null) { pinnedIp = ip; resolveError = false } else resolveError = true
                                }
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) { Text(stringResource(R.string.dns_resolve)) }
                    }
                }
            }
            DropdownField(
                value = strategy.name, onValueChange = { strategy = DnsQueryStrategy.valueOf(it) },
                label = stringResource(R.string.dns_query_strategy_label), options = strategyOptions,
                modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.dns_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Local IP-literal check for the UI (mirrors ConfigBuilder.isIpLiteral, which is private).
private fun isIpLiteralClient(host: String): Boolean {
    if (host.contains(":")) return true
    val parts = host.split(".")
    return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
}
