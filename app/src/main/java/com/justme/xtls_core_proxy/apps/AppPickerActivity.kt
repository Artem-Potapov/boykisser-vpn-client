package com.justme.xtls_core_proxy.apps

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.split.AppEntry
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

/**
 * Generic multi-select app picker. Pass an initial selection and a title via
 * [Intent] extras; receive the new selection back via [Activity.setResult].
 *
 * Caller persists the result — this Activity has zero knowledge of which
 * feature's prefs it's editing.
 */
class AppPickerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE = "com.justme.xtls_core_proxy.apps.EXTRA_TITLE"
        const val EXTRA_INITIAL_SELECTION = "com.justme.xtls_core_proxy.apps.EXTRA_INITIAL_SELECTION"
        const val EXTRA_RESULT_SELECTION = "com.justme.xtls_core_proxy.apps.EXTRA_RESULT_SELECTION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Select apps"
        val initialSelection = intent.getStringArrayExtra(EXTRA_INITIAL_SELECTION)?.toSet() ?: emptySet()

        setContent {
            XTLS_CORE_PROXYTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppPickerScreen(
                        title = title,
                        initialSelection = initialSelection,
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        onSave = { selection ->
                            val data = Intent().putExtra(
                                EXTRA_RESULT_SELECTION,
                                selection.toTypedArray()
                            )
                            setResult(Activity.RESULT_OK, data)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppPickerScreen(
    title: String,
    initialSelection: Set<String>,
    onCancel: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    val appsState = remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var selected by remember { mutableStateOf(initialSelection) }
    var query by remember { mutableStateOf("") }
    val trimmedQuery = query.trim()

    val filteredApps by remember(appsState.value, trimmedQuery) {
        derivedStateOf {
            if (trimmedQuery.isBlank()) {
                appsState.value
            } else {
                val queryLower = trimmedQuery.lowercase()
                appsState.value.filter { entry ->
                    entry.appName.lowercase().contains(queryLower) ||
                        entry.packageName.lowercase().contains(queryLower)
                }
            }
        }
    }

    LaunchedEffect(context) {
        appsState.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            InstalledAppsLoader.loadInstalled(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search apps") },
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (filteredApps.isEmpty()) {
                item {
                    Text("No apps found", modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            items(filteredApps, key = { it.packageName }) { app ->
                val isSelected = app.packageName in selected
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + app.packageName
                                       else selected - app.packageName
                        }
                    )
                    Column {
                        Text(app.appName, style = MaterialTheme.typography.bodyLarge)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(modifier = Modifier.weight(1f), onClick = { onSave(selected) }) {
                Text("Save")
            }
        }
    }
}
