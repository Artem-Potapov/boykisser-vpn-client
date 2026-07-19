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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.justme.xtls_core_proxy.bridge.XrayBridge
import com.justme.xtls_core_proxy.config.RoutingPreferences
import com.justme.xtls_core_proxy.config.XrayCorePreferences
import com.justme.xtls_core_proxy.config.XrayCoreSettings
import com.justme.xtls_core_proxy.config.XrayDomainStrategy
import com.justme.xtls_core_proxy.config.routingNeedsDomainRules
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.components.DropdownField
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class XraySettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { XrayScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XrayScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val initial = remember { XrayCorePreferences.load(context) }

    var mtu by remember { mutableStateOf(initial.mtu.toString()) }
    var ipv6 by remember { mutableStateOf(initial.ipv6) }
    var sniffing by remember { mutableStateOf(initial.sniffing) }
    var domainStrategy by remember { mutableStateOf(initial.domainStrategy) }

    // Domain-based routing rules only match with sniffing on, so the switch shows a forced-on,
    // greyed state in that case. Display-only: `sniffing` (the user's value) is what Save writes.
    var sniffingForced by remember { mutableStateOf(routingNeedsDomainRules(RoutingPreferences.load(context))) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                sniffingForced = routingNeedsDomainRules(RoutingPreferences.load(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // XrayBridge.xrayVersion() is a blocking JNI call whose first touch loads gojni (~50 MB) and
    // inits the Go runtime — its KDoc mandates an off-main-thread call, so it resolves into state.
    val unknownVersion = stringResource(R.string.xray_core_version_unknown)
    val version by produceState(initialValue = unknownVersion) {
        value = withContext(Dispatchers.IO) {
            XrayBridge.xrayVersion().getOrNull()?.takeIf { it.isNotBlank() } ?: unknownVersion
        }
    }

    val mtuValid = mtu.trim().toIntOrNull()
        ?.let { it in XrayCorePreferences.MTU_MIN..XrayCorePreferences.MTU_MAX } == true

    // DropdownField is String-keyed (see ui/components/DropdownField.kt) — map via enum name.
    val strategyOptions = listOf(
        XrayDomainStrategy.FROM_CONFIG.name to stringResource(R.string.xray_ds_from_config),
        XrayDomainStrategy.AS_IS.name to stringResource(R.string.xray_ds_as_is),
        XrayDomainStrategy.IP_IF_NON_MATCH.name to stringResource(R.string.xray_ds_ip_if_non_match),
        XrayDomainStrategy.IP_ON_DEMAND.name to stringResource(R.string.xray_ds_ip_on_demand),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.xray_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.xray_cd_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = mtuValid,
                        onClick = {
                            XrayCorePreferences.save(
                                context,
                                XrayCoreSettings(mtu.trim().toInt(), ipv6, sniffing, domainStrategy)
                            )
                            onBack()
                        }
                    ) { Text(stringResource(R.string.xray_save)) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = mtu, onValueChange = { mtu = it },
                label = { Text(stringResource(R.string.xray_mtu)) },
                isError = !mtuValid,
                supportingText = { if (!mtuValid) Text(stringResource(R.string.xray_mtu_error)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.xray_ipv6), modifier = Modifier.weight(1f))
                Switch(checked = ipv6, onCheckedChange = { ipv6 = it })
            }
            Text(stringResource(R.string.xray_ipv6_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.xray_sniffing), modifier = Modifier.weight(1f))
                Switch(
                    checked = sniffingForced || sniffing,
                    onCheckedChange = { sniffing = it },
                    enabled = !sniffingForced
                )
            }
            if (sniffingForced) {
                Text(stringResource(R.string.xray_sniffing_forced), style = MaterialTheme.typography.bodySmall)
            }
            DropdownField(
                value = domainStrategy.name, onValueChange = { domainStrategy = XrayDomainStrategy.valueOf(it) },
                label = stringResource(R.string.xray_domain_strategy_label),
                options = strategyOptions, modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.xray_core_version), modifier = Modifier.weight(1f))
                Text(version, style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.xray_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
