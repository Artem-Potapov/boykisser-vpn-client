package com.justme.xtls_core_proxy.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.ConfigKind
import com.justme.xtls_core_proxy.config.Hysteria2ConfigCodec
import com.justme.xtls_core_proxy.config.Hysteria2SimpleFields
import com.justme.xtls_core_proxy.config.JsonFormatter
import com.justme.xtls_core_proxy.config.ProfileConfigCodec
import com.justme.xtls_core_proxy.config.SimpleServerFields
import com.justme.xtls_core_proxy.ui.components.DropdownField
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme
import org.json.JSONObject

class ServerSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
        val initialName = intent.getStringExtra(EXTRA_INITIAL_NAME).orEmpty()
        val initialConfig = intent.getStringExtra(EXTRA_INITIAL_CONFIG).orEmpty()

        setContent {
            XTLS_CORE_PROXYTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ServerSettingsScreen(
                        initialName = initialName,
                        initialConfig = initialConfig,
                        isEdit = profileId != -1L,
                        onBack = { finish() },
                        onSave = { savedName, savedConfig ->
                            val resultIntent = Intent().apply {
                                putExtra(EXTRA_PROFILE_ID, profileId)
                                putExtra(EXTRA_RESULT_NAME, savedName)
                                putExtra(EXTRA_RESULT_CONFIG, savedConfig)
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_INITIAL_NAME = "extra_initial_name"
        const val EXTRA_INITIAL_CONFIG = "extra_initial_config"
        const val EXTRA_RESULT_NAME = "extra_result_name"
        const val EXTRA_RESULT_CONFIG = "extra_result_config"

        fun createIntent(
            context: Context,
            profileId: Long,
            initialName: String,
            initialConfig: String
        ): Intent {
            return Intent(context, ServerSettingsActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_INITIAL_NAME, initialName)
                putExtra(EXTRA_INITIAL_CONFIG, initialConfig)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSettingsScreen(
    initialName: String,
    initialConfig: String,
    isEdit: Boolean,
    onBack: () -> Unit,
    onSave: (name: String, config: String) -> Unit
) {
    val initialProtocol = remember(initialConfig) {
        detectEditableServerProtocol(initialConfig)
    }
    val initialConfigIsJson = remember(initialConfig) {
        initialConfig.isNotBlank() &&
            runCatching { ProfileConfigCodec.detectKind(initialConfig) == ConfigKind.JSON }
                .getOrDefault(false)
    }

    val initialSimpleFields = remember(initialConfig) {
        if (initialConfig.isBlank()) {
            defaultSimpleServerFields()
        } else {
            runCatching {
                SimpleServerFields.fromVlessProfile(ProfileConfigCodec.extractVlessProfile(initialConfig))
            }.getOrElse { defaultSimpleServerFields() }
        }
    }

    val initialHysteria2Fields = remember(initialConfig) {
        if (initialConfig.isBlank()) {
            defaultHysteria2SimpleFields()
        } else {
            runCatching {
                val profile = if (ProfileConfigCodec.detectKind(initialConfig) == ConfigKind.HYSTERIA2_URI) {
                    Hysteria2ConfigCodec.parseUri(initialConfig)
                } else {
                    Hysteria2ConfigCodec.parseProfileFromJson(initialConfig)
                }
                Hysteria2SimpleFields.fromProfile(profile)
            }.getOrElse { defaultHysteria2SimpleFields() }
        }
    }

    var name by rememberSaveable { mutableStateOf(initialName) }
    var configText by rememberSaveable { mutableStateOf(initialConfig) }
    var tabIndex by rememberSaveable(initialConfig) {
        mutableIntStateOf(if (initialProtocol == EditableServerProtocol.ADVANCED_ONLY) 1 else 0)
    }
    var simpleProtocol by rememberSaveable(initialConfig) { mutableStateOf(initialProtocol) }
    var vlessFields by remember { mutableStateOf(initialSimpleFields) }
    var hysteria2Fields by remember { mutableStateOf(initialHysteria2Fields) }
    var parseMessage by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val resources = LocalResources.current

    fun buildConfigFromSimple(): Result<String> {
        return runCatching {
            when (simpleProtocol) {
                EditableServerProtocol.VLESS -> {
                    val vlessProfile = vlessFields.toVlessProfile()
                    if (initialConfigIsJson) {
                        ProfileConfigCodec.mergeVlessProfileIntoJson(initialConfig, vlessProfile)
                    } else {
                        ConfigBuilder.templateJsonFromVlessProfile(vlessProfile)
                    }
                }
                EditableServerProtocol.HYSTERIA2 -> {
                    val hysteria2Profile = hysteria2Fields.toProfile()
                    if (
                        initialConfig.isNotBlank() &&
                        ProfileConfigCodec.detectKind(initialConfig) != ConfigKind.HYSTERIA2_URI
                    ) {
                        Hysteria2ConfigCodec.mergeProfileIntoJson(initialConfig, hysteria2Profile)
                    } else {
                        ConfigBuilder.templateJsonFromHysteria2Profile(hysteria2Profile)
                    }
                }
                EditableServerProtocol.ADVANCED_ONLY -> {
                    throw IllegalArgumentException(resources.getString(R.string.server_error_advanced_only))
                }
            }
        }
    }

    fun syncBasicToAdvanced() {
        buildConfigFromSimple()
            .onSuccess {
                // Format JSON with 2-space indentation if it's valid JSON
                val formatted = JsonFormatter.formatJsonIfValid(it)
                configText = formatted
                parseMessage = null
            }
            .onFailure { error ->
                parseMessage = resources.getString(
                    R.string.server_error_build_config_prefix,
                    error.message ?: error.javaClass.simpleName
                )
            }
    }

    fun syncAdvancedToBasic(): Boolean {
        return runCatching {
            when (detectEditableServerProtocol(configText)) {
                EditableServerProtocol.VLESS -> {
                    vlessFields = if (configText.isBlank()) {
                        defaultSimpleServerFields()
                    } else {
                        SimpleServerFields.fromVlessProfile(ProfileConfigCodec.extractVlessProfile(configText))
                    }
                    simpleProtocol = EditableServerProtocol.VLESS
                }
                EditableServerProtocol.HYSTERIA2 -> {
                    val profile = if (ProfileConfigCodec.detectKind(configText) == ConfigKind.HYSTERIA2_URI) {
                        Hysteria2ConfigCodec.parseUri(configText)
                    } else {
                        Hysteria2ConfigCodec.parseProfileFromJson(configText)
                    }
                    hysteria2Fields = Hysteria2SimpleFields.fromProfile(profile)
                    simpleProtocol = EditableServerProtocol.HYSTERIA2
                }
                EditableServerProtocol.ADVANCED_ONLY -> {
                    throw IllegalArgumentException(resources.getString(R.string.server_error_advanced_only))
                }
            }
        }.onSuccess {
            parseMessage = null
        }.onFailure { error ->
            parseMessage = resources.getString(
                R.string.server_error_parse_advanced_prefix,
                error.message ?: error.javaClass.simpleName
            )
        }.isSuccess
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEdit) R.string.server_title_edit else R.string.server_title_add
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.server_cd_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            saveError = null
                            val trimmedName = name.trim()
                            if (trimmedName.isBlank()) {
                                saveError = resources.getString(R.string.server_error_name_required)
                                return@TextButton
                            }
                            val candidateConfig = if (tabIndex == 0) {
                                buildConfigFromSimple().getOrElse { error ->
                                    saveError = resources.getString(
                                        R.string.server_error_simple_invalid_prefix,
                                        error.message ?: error.javaClass.simpleName
                                    )
                                    return@TextButton
                                }
                            } else {
                                configText.trim()
                            }
                            if (candidateConfig.isBlank()) {
                                saveError = resources.getString(R.string.server_error_config_required)
                                return@TextButton
                            }
                            runCatching { ConfigBuilder.buildRuntimeConfig(candidateConfig) }
                                .onFailure { error ->
                                    saveError = resources.getString(
                                        R.string.server_error_validation_failed_prefix,
                                        error.message ?: error.javaClass.simpleName
                                    )
                                }
                                .onSuccess {
                                    onSave(trimmedName, candidateConfig)
                                }
                        }
                    ) {
                        Text(stringResource(R.string.server_action_save))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.server_label_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            val tabTitles = listOf(
                stringResource(R.string.server_tab_simple),
                stringResource(R.string.server_tab_advanced)
            )
            PrimaryTabRow(selectedTabIndex = tabIndex) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = {
                            if (index == tabIndex) return@Tab
                            if (index == 1) {
                                syncBasicToAdvanced()
                                tabIndex = index
                            } else {
                                if (syncAdvancedToBasic()) {
                                    tabIndex = index
                                }
                            }
                        },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (tabIndex == 0 && simpleProtocol != EditableServerProtocol.ADVANCED_ONLY) {
                when (simpleProtocol) {
                    EditableServerProtocol.VLESS -> {
                        SimpleEditor(
                            fields = vlessFields,
                            onFieldsChange = { vlessFields = it }
                        )
                    }
                    EditableServerProtocol.HYSTERIA2 -> {
                        Hysteria2SimpleEditor(
                            fields = hysteria2Fields,
                            onFieldsChange = { hysteria2Fields = it }
                        )
                    }
                    EditableServerProtocol.ADVANCED_ONLY -> Unit
                }
            } else {
                AdvancedEditor(
                    configText = configText,
                    onConfigChange = { configText = it }
                )
            }

            if (!parseMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = parseMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (!saveError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = saveError.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun networkOptions(): List<Pair<String, String>> = listOf(
    "tcp" to stringResource(R.string.server_network_tcp),
    "kcp" to stringResource(R.string.server_network_kcp),
    "ws" to stringResource(R.string.server_network_ws),
    "httpupgrade" to stringResource(R.string.server_network_httpupgrade),
    "xhttp" to stringResource(R.string.server_network_xhttp),
    "h2" to stringResource(R.string.server_network_h2),
    "quic" to stringResource(R.string.server_network_quic),
    "grpc" to stringResource(R.string.server_network_grpc)
)

@Composable
private fun flowOptions(): List<Pair<String, String>> = listOf(
    "" to stringResource(R.string.server_flow_none),
    "xtls-rprx-vision" to stringResource(R.string.server_flow_xtls_rprx_vision),
    "xtls-rprx-vision-udp443" to stringResource(R.string.server_flow_xtls_rprx_vision_udp443)
)

@Composable
private fun securityOptions(): List<Pair<String, String>> = listOf(
    "none" to stringResource(R.string.server_security_none),
    "reality" to stringResource(R.string.server_security_reality),
    "tls" to stringResource(R.string.server_security_tls)
)

@Composable
private fun fingerprintOptions(): List<Pair<String, String>> = listOf(
    "chrome" to stringResource(R.string.server_fingerprint_chrome),
    "firefox" to stringResource(R.string.server_fingerprint_firefox),
    "safari" to stringResource(R.string.server_fingerprint_safari),
    "ios" to stringResource(R.string.server_fingerprint_ios),
    "android" to stringResource(R.string.server_fingerprint_android),
    "edge" to stringResource(R.string.server_fingerprint_edge),
    "360" to stringResource(R.string.server_fingerprint_360),
    "qq" to stringResource(R.string.server_fingerprint_qq),
    "random" to stringResource(R.string.server_fingerprint_random),
    "randomized" to stringResource(R.string.server_fingerprint_randomized)
)

@Composable
private fun alpnOptions(): List<Pair<String, String>> = listOf(
    "" to stringResource(R.string.server_alpn_none),
    "h3" to stringResource(R.string.server_alpn_h3),
    "h2" to stringResource(R.string.server_alpn_h2),
    "http/1.1" to stringResource(R.string.server_alpn_http11),
    "h3,h2,http/1.1" to stringResource(R.string.server_alpn_h3_h2_http11),
    "h3,h2" to stringResource(R.string.server_alpn_h3_h2),
    "h2,http/1.1" to stringResource(R.string.server_alpn_h2_http11)
)

@Composable
private fun allowInsecureOptions(): List<Pair<String, String>> = listOf(
    "false" to stringResource(R.string.server_allow_insecure_no),
    "true" to stringResource(R.string.server_allow_insecure_yes)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleEditor(
    fields: SimpleServerFields,
    onFieldsChange: (SimpleServerFields) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = fields.host,
            onValueChange = { onFieldsChange(fields.copy(host = it)) },
            label = { Text(stringResource(R.string.server_label_host)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fields.port,
                onValueChange = { onFieldsChange(fields.copy(port = it)) },
                label = { Text(stringResource(R.string.server_label_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            DropdownField(
                value = fields.network,
                onValueChange = { onFieldsChange(fields.copy(network = it)) },
                label = stringResource(R.string.server_label_network),
                options = networkOptions(),
                modifier = Modifier.weight(1f)
            )
        }

        TransportFields(fields, onFieldsChange)

        OutlinedTextField(
            value = fields.uuid,
            onValueChange = { onFieldsChange(fields.copy(uuid = it)) },
            label = { Text(stringResource(R.string.server_label_uuid)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField(
                value = fields.security,
                onValueChange = { onFieldsChange(fields.copy(security = it)) },
                label = stringResource(R.string.server_label_security),
                options = securityOptions(),
                modifier = Modifier.weight(1f)
            )
            DropdownField(
                value = fields.flow,
                onValueChange = { onFieldsChange(fields.copy(flow = it)) },
                label = stringResource(R.string.server_label_flow),
                options = flowOptions(),
                modifier = Modifier.weight(1f)
            )
        }

        val secLower = fields.security.lowercase()
        if (secLower == "reality") {
            RealityFields(fields, onFieldsChange)
        } else if (secLower == "tls") {
            TlsFields(fields, onFieldsChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Hysteria2SimpleEditor(
    fields: Hysteria2SimpleFields,
    onFieldsChange: (Hysteria2SimpleFields) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = fields.host,
            onValueChange = { onFieldsChange(fields.copy(host = it)) },
            label = { Text(stringResource(R.string.server_label_host)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fields.port,
                onValueChange = { onFieldsChange(fields.copy(port = it)) },
                label = { Text(stringResource(R.string.server_label_udp_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            DropdownField(
                value = fields.allowInsecure,
                onValueChange = { onFieldsChange(fields.copy(allowInsecure = it)) },
                label = stringResource(R.string.server_label_allow_insecure),
                options = allowInsecureOptions(),
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = fields.auth,
            onValueChange = { onFieldsChange(fields.copy(auth = it)) },
            label = { Text(stringResource(R.string.server_label_hysteria2_auth)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fields.serverName,
            onValueChange = { onFieldsChange(fields.copy(serverName = it)) },
            label = { Text(stringResource(R.string.server_label_sni)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        DropdownField(
            value = fields.alpn,
            onValueChange = { onFieldsChange(fields.copy(alpn = it)) },
            label = stringResource(R.string.server_label_alpn),
            options = alpnOptions(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fields.pinnedPeerCertSha256,
            onValueChange = { onFieldsChange(fields.copy(pinnedPeerCertSha256 = it)) },
            label = { Text(stringResource(R.string.server_label_pinned_cert_sha256)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fields.salamanderPassword,
            onValueChange = { onFieldsChange(fields.copy(salamanderPassword = it)) },
            label = { Text(stringResource(R.string.server_label_salamander_password)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fields.congestion,
            onValueChange = { onFieldsChange(fields.copy(congestion = it)) },
            label = { Text(stringResource(R.string.server_label_hysteria2_congestion)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fields.udpHopInterval,
            onValueChange = { onFieldsChange(fields.copy(udpHopInterval = it)) },
            label = { Text(stringResource(R.string.server_label_udp_hop_interval)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fields.uploadBandwidth,
            onValueChange = { onFieldsChange(fields.copy(uploadBandwidth = it)) },
            label = { Text(stringResource(R.string.server_label_upload_bandwidth)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fields.downloadBandwidth,
            onValueChange = { onFieldsChange(fields.copy(downloadBandwidth = it)) },
            label = { Text(stringResource(R.string.server_label_download_bandwidth)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fields.portHopPorts,
            onValueChange = { onFieldsChange(fields.copy(portHopPorts = it)) },
            label = { Text(stringResource(R.string.server_label_udp_hop_ports)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = fields.finalmaskJson,
            onValueChange = { onFieldsChange(fields.copy(finalmaskJson = it)) },
            label = { Text(stringResource(R.string.server_label_finalmask_json)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace)
        )
        Text(
            text = stringResource(R.string.server_hint_hysteria2_finalmask),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TransportFields(
    fields: SimpleServerFields,
    onFieldsChange: (SimpleServerFields) -> Unit
) {
    when (fields.network.lowercase()) {
        "ws", "httpupgrade", "h2", "xhttp" -> {
            OutlinedTextField(
                value = fields.transportPath,
                onValueChange = { onFieldsChange(fields.copy(transportPath = it)) },
                label = {
                    Text(
                        stringResource(
                            R.string.server_transport_path_label,
                            fields.network
                        )
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fields.transportHost,
                onValueChange = { onFieldsChange(fields.copy(transportHost = it)) },
                label = {
                    Text(
                        stringResource(
                            R.string.server_transport_host_label,
                            fields.network
                        )
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (fields.network.equals("xhttp", ignoreCase = true)) {
                OutlinedTextField(
                    value = fields.xhttpExtraJson,
                    onValueChange = { onFieldsChange(fields.copy(xhttpExtraJson = it)) },
                    label = { Text(stringResource(R.string.server_label_xhttp_extra_json)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace)
                )
                Text(
                    text = stringResource(R.string.server_hint_xhttp_extra),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        "grpc" -> {
            OutlinedTextField(
                value = fields.grpcServiceName,
                onValueChange = { onFieldsChange(fields.copy(grpcServiceName = it)) },
                label = { Text(stringResource(R.string.server_label_grpc_service_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fields.grpcAuthority,
                onValueChange = { onFieldsChange(fields.copy(grpcAuthority = it)) },
                label = { Text(stringResource(R.string.server_label_grpc_authority)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        "kcp" -> {
            OutlinedTextField(
                value = fields.kcpSeed,
                onValueChange = { onFieldsChange(fields.copy(kcpSeed = it)) },
                label = { Text(stringResource(R.string.server_label_kcp_seed)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = fields.finalmaskJson,
                onValueChange = { onFieldsChange(fields.copy(finalmaskJson = it)) },
                label = { Text(stringResource(R.string.server_label_finalmask_json)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace)
            )
            Text(
                text = stringResource(R.string.server_hint_finalmask),
                style = MaterialTheme.typography.bodySmall
            )
        }
        "quic" -> {
            OutlinedTextField(
                value = fields.quicKey,
                onValueChange = { onFieldsChange(fields.copy(quicKey = it)) },
                label = { Text(stringResource(R.string.server_label_quic_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RealityFields(
    fields: SimpleServerFields,
    onFieldsChange: (SimpleServerFields) -> Unit
) {
    OutlinedTextField(
        value = fields.serverName,
        onValueChange = { onFieldsChange(fields.copy(serverName = it)) },
        label = { Text(stringResource(R.string.server_label_sni)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField(
            value = fields.fingerprint,
            onValueChange = { onFieldsChange(fields.copy(fingerprint = it)) },
            label = stringResource(R.string.server_label_fingerprint),
            options = fingerprintOptions(),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = fields.shortId,
            onValueChange = { onFieldsChange(fields.copy(shortId = it)) },
            label = { Text(stringResource(R.string.server_label_short_id)) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
    OutlinedTextField(
        value = fields.publicKey,
        onValueChange = { onFieldsChange(fields.copy(publicKey = it)) },
        label = { Text(stringResource(R.string.server_label_public_key)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField(
            value = fields.alpn,
            onValueChange = { onFieldsChange(fields.copy(alpn = it)) },
            label = stringResource(R.string.server_label_alpn),
            options = alpnOptions(),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = fields.spiderX,
            onValueChange = { onFieldsChange(fields.copy(spiderX = it)) },
            label = { Text(stringResource(R.string.server_label_spiderx)) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TlsFields(
    fields: SimpleServerFields,
    onFieldsChange: (SimpleServerFields) -> Unit
) {
    OutlinedTextField(
        value = fields.serverName,
        onValueChange = { onFieldsChange(fields.copy(serverName = it)) },
        label = { Text(stringResource(R.string.server_label_sni)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField(
            value = fields.fingerprint,
            onValueChange = { onFieldsChange(fields.copy(fingerprint = it)) },
            label = stringResource(R.string.server_label_fingerprint),
            options = fingerprintOptions(),
            modifier = Modifier.weight(1f)
        )
        DropdownField(
            value = fields.alpn,
            onValueChange = { onFieldsChange(fields.copy(alpn = it)) },
            label = stringResource(R.string.server_label_alpn),
            options = alpnOptions(),
            modifier = Modifier.weight(1f)
        )
    }
    DropdownField(
        value = fields.allowInsecure,
        onValueChange = { onFieldsChange(fields.copy(allowInsecure = it)) },
        label = stringResource(R.string.server_label_allow_insecure),
        options = allowInsecureOptions(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AdvancedEditor(
    configText: String,
    onConfigChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.server_advanced_uri_or_json),
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(
                onClick = {
                    val formatted = JsonFormatter.formatJsonIfValid(configText)
                    if (formatted != configText) {
                        onConfigChange(formatted)
                    }
                },
                enabled = JsonFormatter.isValidJson(configText)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.server_cd_format_json)
                )
            }
        }
        OutlinedTextField(
            value = configText,
            onValueChange = onConfigChange,
            label = { Text("") },
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.server_hint_advanced_save),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun defaultSimpleServerFields(): SimpleServerFields {
    return SimpleServerFields(
        uuid = "",
        host = "",
        port = "",
        flow = "",
        security = "none",
        publicKey = "",
        shortId = "",
        fingerprint = "chrome",
        serverName = "",
        network = "tcp",
        xhttpExtraJson = "",
        finalmaskJson = ""
    )
}

private enum class EditableServerProtocol {
    VLESS,
    HYSTERIA2,
    ADVANCED_ONLY
}

private fun detectEditableServerProtocol(config: String): EditableServerProtocol {
    if (config.isBlank()) return EditableServerProtocol.VLESS
    return runCatching {
        when (ProfileConfigCodec.detectKind(config)) {
            ConfigKind.VLESS_URI -> EditableServerProtocol.VLESS
            ConfigKind.HYSTERIA2_URI -> EditableServerProtocol.HYSTERIA2
            ConfigKind.JSON -> {
                val root = JSONObject(config)
                val outbounds = root.optJSONArray("outbounds")
                val first = outbounds?.optJSONObject(0)
                when {
                    first?.optString("protocol").equals("vless", ignoreCase = true) -> {
                        EditableServerProtocol.VLESS
                    }
                    first?.optString("protocol").equals("hysteria", ignoreCase = true) -> {
                        EditableServerProtocol.HYSTERIA2
                    }
                    else -> EditableServerProtocol.ADVANCED_ONLY
                }
            }
        }
    }.getOrDefault(EditableServerProtocol.ADVANCED_ONLY)
}

private fun defaultHysteria2SimpleFields(): Hysteria2SimpleFields {
    return Hysteria2SimpleFields(
        auth = "",
        host = "",
        port = "443",
        portHopPorts = "",
        serverName = "",
        alpn = "h3",
        allowInsecure = "false",
        pinnedPeerCertSha256 = "",
        salamanderPassword = "",
        congestion = "",
        uploadBandwidth = "",
        downloadBandwidth = "",
        udpHopInterval = "",
        finalmaskJson = ""
    )
}
