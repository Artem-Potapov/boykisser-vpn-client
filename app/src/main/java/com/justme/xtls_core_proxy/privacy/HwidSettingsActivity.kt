package com.justme.xtls_core_proxy.privacy

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.components.DropdownField
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme
import java.util.Locale

private const val AUTO_KEY = "auto"

class HwidSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Match SubscriptionRefreshCoordinator: device default, not in-app override.
        val realLanguage = Locale.getDefault().language
        setContent {
            XTLS_CORE_PROXYTheme {
                HwidScreen(onBack = { finish() }, realLanguage = realLanguage)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HwidScreen(onBack: () -> Unit, realLanguage: String) {
    val context = LocalContext.current
    val initial = remember { DeviceIdentityRepository.load(context) }

    var sendHwid by remember { mutableStateOf(initial.sendHwid) }
    var hwid by remember { mutableStateOf(initial.hwid) }
    var mode by remember { mutableStateOf(initial.identityMode) }
    var androidVersion by remember { mutableStateOf(initial.androidVersionPin ?: AUTO_KEY) }
    var androidModel by remember { mutableStateOf(initial.androidModelPin ?: AUTO_KEY) }
    var iosVersion by remember { mutableStateOf(initial.iosVersionPin ?: AUTO_KEY) }
    var iosModel by remember { mutableStateOf(initial.iosModelPin ?: AUTO_KEY) }
    var customEnabled by remember { mutableStateOf(initial.customEnabled) }
    var customOs by remember { mutableStateOf(initial.customOs.orEmpty()) }
    var customOsVersion by remember { mutableStateOf(initial.customOsVersion.orEmpty()) }
    var customModel by remember { mutableStateOf(initial.customModel.orEmpty()) }
    var customLocale by remember { mutableStateOf(initial.customLocale.orEmpty()) }
    var happUa by remember { mutableStateOf(initial.userAgentMode == UserAgentMode.HAPP_LIKE) }
    var showResetConfirm by remember { mutableStateOf(false) }

    fun blankToNull(value: String) = value.trim().ifBlank { null }
    fun keyToPin(key: String) = if (key == AUTO_KEY) null else key

    fun current(): DeviceIdentitySettings = DeviceIdentitySettings(
        sendHwid = sendHwid,
        hwid = hwid,
        identityMode = mode,
        androidVersionPin = keyToPin(androidVersion),
        androidModelPin = keyToPin(androidModel),
        iosVersionPin = keyToPin(iosVersion),
        iosModelPin = keyToPin(iosModel),
        customEnabled = customEnabled,
        customOs = blankToNull(customOs),
        customOsVersion = blankToNull(customOsVersion),
        customModel = blankToNull(customModel),
        customLocale = blankToNull(customLocale),
        userAgentMode = if (happUa) UserAgentMode.HAPP_LIKE else UserAgentMode.DEFAULT,
    )

    fun persist() = DeviceIdentityRepository.save(context, current())

    val autoLabel = stringResource(R.string.hwid_auto)
    val identityOptions = listOf(
        IdentityMode.REAL_DEVICE.name to stringResource(R.string.hwid_identity_real),
        IdentityMode.ANDROID.name to stringResource(R.string.hwid_identity_android),
        IdentityMode.IPHONE.name to stringResource(R.string.hwid_identity_iphone),
        IdentityMode.NONE.name to stringResource(R.string.hwid_identity_none),
    )
    val androidVersionOptions = listOf(AUTO_KEY to autoLabel) +
        SpoofIdentities.ANDROID_VERSIONS.map { it to it }
    val androidModelOptions = listOf(
        AUTO_KEY to autoLabel,
        "pixel" to stringResource(R.string.hwid_model_pixel),
        "samsung" to stringResource(R.string.hwid_model_samsung),
        "xiaomi" to stringResource(R.string.hwid_model_xiaomi),
        "huawei" to stringResource(R.string.hwid_model_huawei),
    )
    val iosVersionOptions = listOf(AUTO_KEY to autoLabel) +
        SpoofIdentities.IOS_VERSIONS.map { it to it }
    val iosModelOptions = listOf(AUTO_KEY to autoLabel) +
        SpoofIdentities.IOS_MODELS.map { it to it }

    // Use the same pure builders as the subscription fetch for a live, wire-shaped preview.
    val previewSettings = current()
    val previewHeaders = DeviceIdentityHeaders.build(
        settings = previewSettings,
        realOsVersion = Build.VERSION.RELEASE ?: "",
        realModel = Build.MODEL ?: "",
        realLanguage = realLanguage,
    )
    val previewUa = UserAgentBuilder.build(previewSettings, "XTLSCoreProxy")
    val previewText = if (previewHeaders.isEmpty()) {
        stringResource(R.string.hwid_preview_none)
    } else {
        previewHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hwid_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.hwid_cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SwitchRow(
                label = stringResource(R.string.hwid_send_label),
                checked = sendHwid,
                onChange = { sendHwid = it; persist() },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.hwid_your_id_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(hwid, style = MaterialTheme.typography.bodyLarge)
                }
                TextButton(onClick = { copyToClipboardMarkedSensitive(context, "HWID", hwid) }) {
                    Text(stringResource(R.string.hwid_copy))
                }
                TextButton(onClick = { showResetConfirm = true }) {
                    Text(stringResource(R.string.hwid_reset))
                }
            }

            // Identity controls remain visible for context but are disabled while HWID is off.
            SwitchRow(
                label = stringResource(R.string.hwid_custom_label),
                checked = customEnabled,
                enabled = sendHwid,
                onChange = { customEnabled = it; persist() },
            )
            if (customEnabled) {
                OutlinedTextField(
                    value = customOs,
                    onValueChange = { customOs = it; persist() },
                    enabled = sendHwid,
                    label = { Text(stringResource(R.string.hwid_custom_os)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = customOsVersion,
                    onValueChange = { customOsVersion = it; persist() },
                    enabled = sendHwid,
                    label = { Text(stringResource(R.string.hwid_custom_os_version)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = customModel,
                    onValueChange = { customModel = it; persist() },
                    enabled = sendHwid,
                    label = { Text(stringResource(R.string.hwid_custom_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = customLocale,
                    onValueChange = { customLocale = it; persist() },
                    enabled = sendHwid,
                    label = { Text(stringResource(R.string.hwid_custom_locale)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.hwid_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = disabledAwareColor(sendHwid),
                )
            } else {
                DropdownField(
                    value = mode.name,
                    onValueChange = {
                        mode = IdentityMode.valueOf(it)
                        persist()
                    },
                    label = stringResource(R.string.hwid_identity_label),
                    options = identityOptions,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sendHwid,
                )

                when (mode) {
                    IdentityMode.ANDROID -> {
                        DropdownField(
                            value = androidVersion,
                            onValueChange = { androidVersion = it; persist() },
                            label = stringResource(R.string.hwid_android_version_label),
                            options = androidVersionOptions,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = sendHwid,
                        )
                        DropdownField(
                            value = androidModel,
                            onValueChange = { androidModel = it; persist() },
                            label = stringResource(R.string.hwid_android_model_label),
                            options = androidModelOptions,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = sendHwid,
                        )
                    }

                    IdentityMode.IPHONE -> {
                        DropdownField(
                            value = iosVersion,
                            onValueChange = { iosVersion = it; persist() },
                            label = stringResource(R.string.hwid_ios_version_label),
                            options = iosVersionOptions,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = sendHwid,
                        )
                        DropdownField(
                            value = iosModel,
                            onValueChange = { iosModel = it; persist() },
                            label = stringResource(R.string.hwid_ios_model_label),
                            options = iosModelOptions,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = sendHwid,
                        )
                    }

                    else -> Unit
                }
            }

            Text(
                stringResource(R.string.hwid_preview_label),
                style = MaterialTheme.typography.labelMedium,
                color = disabledAwareColor(sendHwid),
            )
            Text(
                previewText,
                style = MaterialTheme.typography.bodySmall,
                color = disabledAwareColor(sendHwid),
            )

            // User-Agent is intentionally independent of the HWID master switch.
            Text(
                stringResource(R.string.hwid_ua_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            SwitchRow(
                label = stringResource(R.string.hwid_ua_happ_label),
                checked = happUa,
                onChange = { happUa = it; persist() },
            )
            Text(
                stringResource(R.string.hwid_ua_preview_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                previewUa,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text(stringResource(R.string.hwid_reset_confirm_title)) },
                text = { Text(stringResource(R.string.hwid_reset_confirm_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hwid = DeviceIdentityRepository.resetHwid(context)
                            showResetConfirm = false
                        },
                    ) {
                        Text(stringResource(R.string.hwid_reset_confirm_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) {
                        Text(stringResource(R.string.hwid_reset_confirm_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = disabledAwareColor(enabled),
        )
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onChange else null,
            enabled = enabled,
        )
    }
}

@Composable
private fun disabledAwareColor(enabled: Boolean): Color =
    if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

private fun copyToClipboardMarkedSensitive(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.hwid_copied, Toast.LENGTH_SHORT).show()
    }
}
