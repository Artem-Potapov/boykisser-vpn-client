package com.justme.xtls_core_proxy.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.BuildConfig
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.state.VpnViewModel
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * DEBUG-only "unrestricted" profile adder. Stores whatever is typed verbatim (via
 * VpnViewModel.addRawProfile) and activates it, bypassing the fail-closed buildRuntimeConfig ingest
 * gate — for reproducing malformed/edge profile state to exercise fail-closed UI (e.g. NEW-M2). It is
 * gated three ways (composed only under BuildConfig.DEBUG, finish() below, exported=false); the
 * production add/validation path is untouched.
 */
class DebugUnrestrictedAddProfileActivity : LocalizedComponentActivity() {

    private val viewModel: VpnViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return } // belt-and-suspenders: inert in release
        enableEdgeToEdge()
        setContent { XTLS_CORE_PROXYTheme { DebugAddScreen(onBack = { finish() }, onAdd = viewModel::addRawProfile) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugAddScreen(onBack: () -> Unit, onAdd: (String, String) -> Job) {
    var name by rememberSaveable { mutableStateOf("DEBUG raw") }
    var config by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val doneMsg = stringResource(R.string.settings_debug_add_done) // captured in composable scope for the callback
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_debug_add_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.settings_debug_add_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = config,
                onValueChange = { config = it },
                label = { Text(stringResource(R.string.settings_debug_add_config_label)) },
                minLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
            Button(
                onClick = {
                    scope.launch {
                        onAdd(name, config).join()
                        Toast.makeText(context, doneMsg, Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) { Text(stringResource(R.string.settings_debug_add_button)) }
        }
    }
}
