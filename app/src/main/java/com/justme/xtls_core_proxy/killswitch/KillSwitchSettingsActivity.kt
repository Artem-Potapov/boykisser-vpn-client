package com.justme.xtls_core_proxy.killswitch

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.apps.AppPickerActivity
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class KillSwitchSettingsActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XTLS_CORE_PROXYTheme {
                KillSwitchSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KillSwitchSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var prefs by remember { mutableStateOf(KillSwitchRepository.load(context)) }
    var permissionGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var showConsentGate by rememberSaveable { mutableStateOf(false) }

    // Re-check permission on every ON_RESUME so returning from system Settings
    // immediately unlocks the toggle. (LaunchedEffect(Unit) only runs once.)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = hasUsageAccess(context)
                prefs = KillSwitchRepository.load(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val newSelection = result.data
                ?.getStringArrayExtra(AppPickerActivity.EXTRA_RESULT_SELECTION)
                ?.toSet() ?: emptySet()
            KillSwitchRepository.save(context, enabled = prefs.enabled, packages = newSelection)
            prefs = KillSwitchRepository.load(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.kill_switch_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.kill_switch_cd_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = stringResource(R.string.kill_switch_description))

            if (!permissionGranted) {
                Text(
                    text = stringResource(R.string.kill_switch_permission_required),
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) {
                    Text(stringResource(R.string.kill_switch_open_settings))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.kill_switch_enabled),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = prefs.enabled,
                    onCheckedChange = { newValue ->
                        if (newValue) {
                            showConsentGate = true
                        } else {
                            KillSwitchRepository.save(context, enabled = false, packages = prefs.packages)
                            prefs = KillSwitchRepository.load(context)
                        }
                    },
                    enabled = permissionGranted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = stringResource(R.string.kill_switch_selected_count, prefs.packages.size))
            Button(onClick = {
                val initial = prefs.packages.toTypedArray()
                val intent = Intent(context, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_TITLE, resources.getString(R.string.kill_switch_title))
                    .putExtra(AppPickerActivity.EXTRA_INITIAL_SELECTION, initial)
                pickerLauncher.launch(intent)
            }) {
                Text(stringResource(R.string.kill_switch_choose_apps))
            }
        }
    }

    if (showConsentGate) {
        KillSwitchConsentDialog(
            isFirstConsent = !KillSwitchRepository.hasConsented(context),
            onAccept = {
                KillSwitchRepository.save(context, enabled = true, packages = prefs.packages)
                KillSwitchRepository.markConsented(context)
                prefs = KillSwitchRepository.load(context)
                showConsentGate = false
            },
            onDecline = {
                showConsentGate = false
            },
        )
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
