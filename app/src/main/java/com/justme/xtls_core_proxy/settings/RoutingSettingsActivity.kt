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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.justme.xtls_core_proxy.config.RoutingCountry
import com.justme.xtls_core_proxy.config.RoutingMode
import com.justme.xtls_core_proxy.config.RoutingPreferences
import com.justme.xtls_core_proxy.config.RoutingSettings
import com.justme.xtls_core_proxy.config.blockedSupported
import com.justme.xtls_core_proxy.config.sanitizeForAvailability
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.components.DropdownField
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class RoutingSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { RoutingScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { RoutingPreferences.load(context) }
    val available = remember { RoutingPreferences.availableGeoFiles(context) }

    var mode by remember { mutableStateOf(initial.mode) }
    var country by remember { mutableStateOf(initial.country) }
    var bypassLan by remember { mutableStateOf(initial.bypassLan) }
    var blockAds by remember { mutableStateOf(initial.blockAds) }

    // DropdownField is String-keyed and has no per-option disabling, so an unbuildable
    // combination is flagged with a label suffix; sanitizeForAvailability on save is the
    // actual enforcement (an impossible combo can never be persisted).
    val unavailable = stringResource(R.string.routing_option_unavailable)
    fun suffixed(label: String, availableFor: Boolean) =
        if (availableFor) label else "$label — $unavailable"

    fun modeAvailable(candidate: RoutingMode): Boolean {
        val probe = RoutingSettings(candidate, country, bypassLan, blockAds)
        if (candidate == RoutingMode.BLOCKED_ONLY && !blockedSupported(country)) return false
        return sanitizeForAvailability(probe, available).mode == candidate
    }

    fun countryAvailable(candidate: RoutingCountry): Boolean {
        val probe = RoutingSettings(mode, candidate, bypassLan, blockAds)
        if (mode == RoutingMode.BLOCKED_ONLY && !blockedSupported(candidate)) return false
        return sanitizeForAvailability(probe, available).mode == mode
    }

    val lanAvailable = "geoip.dat" in available
    val adsAvailable = "geosite.dat" in available

    val modeOptions = listOf(
        RoutingMode.PROXY_ALL.name to
            suffixed(stringResource(R.string.routing_mode_proxy_all), modeAvailable(RoutingMode.PROXY_ALL)),
        RoutingMode.EXCEPT_COUNTRY.name to
            suffixed(stringResource(R.string.routing_mode_except), modeAvailable(RoutingMode.EXCEPT_COUNTRY)),
        RoutingMode.BLOCKED_ONLY.name to
            suffixed(stringResource(R.string.routing_mode_blocked), modeAvailable(RoutingMode.BLOCKED_ONLY)),
    )
    val countryOptions = listOf(
        RoutingCountry.RU.name to
            suffixed(stringResource(R.string.routing_country_ru), countryAvailable(RoutingCountry.RU)),
        RoutingCountry.IR.name to
            suffixed(stringResource(R.string.routing_country_ir), countryAvailable(RoutingCountry.IR)),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.routing_cd_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            RoutingPreferences.save(
                                context,
                                sanitizeForAvailability(RoutingSettings(mode, country, bypassLan, blockAds), available)
                            )
                            onBack()
                        }
                    ) { Text(stringResource(R.string.routing_save)) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DropdownField(
                value = mode.name, onValueChange = { mode = RoutingMode.valueOf(it) },
                label = stringResource(R.string.routing_mode_label), options = modeOptions,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            if (mode != RoutingMode.PROXY_ALL) {
                DropdownField(
                    value = country.name, onValueChange = { country = RoutingCountry.valueOf(it) },
                    label = stringResource(R.string.routing_country_label), options = countryOptions,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(suffixed(stringResource(R.string.routing_bypass_lan), lanAvailable), modifier = Modifier.weight(1f))
                Switch(checked = bypassLan, onCheckedChange = { bypassLan = it }, enabled = lanAvailable)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(suffixed(stringResource(R.string.routing_block_ads), adsAvailable), modifier = Modifier.weight(1f))
                Switch(checked = blockAds, onCheckedChange = { blockAds = it }, enabled = adsAvailable)
            }
            if (mode == RoutingMode.BLOCKED_ONLY && modeAvailable(mode)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        stringResource(R.string.routing_blocked_caution),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            if (!modeAvailable(mode)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        stringResource(R.string.routing_mode_fallback_notice),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Text(stringResource(R.string.routing_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
