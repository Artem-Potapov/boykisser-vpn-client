package com.justme.xtls_core_proxy.subs

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.ui.components.DropdownField
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class SubscriptionEditActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val subscriptionId = intent.getLongExtra(EXTRA_SUBSCRIPTION_ID, -1L)
        val initialName = intent.getStringExtra(EXTRA_INITIAL_NAME).orEmpty()
        val initialUrl = intent.getStringExtra(EXTRA_INITIAL_URL).orEmpty()
        val initialUserAgent = intent.getStringExtra(EXTRA_INITIAL_USER_AGENT).orEmpty()
        val initialAllowInsecure = intent.getBooleanExtra(EXTRA_INITIAL_ALLOW_INSECURE, false)
        val initialIntervalRaw = intent.getIntExtra(EXTRA_INITIAL_INTERVAL_HOURS, -1)
        val initialIntervalText = if (initialIntervalRaw > 0) initialIntervalRaw.toString() else ""

        setContent {
            XTLS_CORE_PROXYTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SubscriptionEditScreen(
                        isEdit = subscriptionId != -1L,
                        initialName = initialName,
                        initialUrl = initialUrl,
                        initialUserAgent = initialUserAgent,
                        initialAllowInsecure = initialAllowInsecure,
                        initialIntervalText = initialIntervalText,
                        onBack = { finish() },
                        onSave = { saved ->
                            val resultIntent = Intent().apply {
                                putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
                                putExtra(EXTRA_RESULT_NAME, saved.name)
                                putExtra(EXTRA_RESULT_URL, saved.url)
                                putExtra(EXTRA_RESULT_USER_AGENT, saved.userAgent ?: "")
                                putExtra(EXTRA_RESULT_ALLOW_INSECURE, saved.allowInsecure)
                                putExtra(EXTRA_RESULT_INTERVAL_HOURS, saved.intervalHours ?: -1)
                                putExtra(EXTRA_RESULT_REFRESH_NOW, saved.refreshNow)
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        }
                    )
                }
            }
        }
    }

    data class SaveOutput(
        val name: String,
        val url: String,
        val userAgent: String?,
        val allowInsecure: Boolean,
        val intervalHours: Int?,
        val refreshNow: Boolean
    )

    companion object {
        const val EXTRA_SUBSCRIPTION_ID = "extra_subscription_id"
        const val EXTRA_INITIAL_NAME = "extra_initial_name"
        const val EXTRA_INITIAL_URL = "extra_initial_url"
        const val EXTRA_INITIAL_USER_AGENT = "extra_initial_user_agent"
        const val EXTRA_INITIAL_ALLOW_INSECURE = "extra_initial_allow_insecure"
        const val EXTRA_INITIAL_INTERVAL_HOURS = "extra_initial_interval_hours"

        const val EXTRA_RESULT_NAME = "extra_result_name"
        const val EXTRA_RESULT_URL = "extra_result_url"
        const val EXTRA_RESULT_USER_AGENT = "extra_result_user_agent"
        const val EXTRA_RESULT_ALLOW_INSECURE = "extra_result_allow_insecure"
        const val EXTRA_RESULT_INTERVAL_HOURS = "extra_result_interval_hours"
        const val EXTRA_RESULT_REFRESH_NOW = "extra_result_refresh_now"

        fun createIntent(
            context: Context,
            subscriptionId: Long,
            initialName: String,
            initialUrl: String,
            initialUserAgent: String?,
            initialAllowInsecure: Boolean,
            initialIntervalHours: Int?
        ): Intent {
            return Intent(context, SubscriptionEditActivity::class.java).apply {
                putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
                putExtra(EXTRA_INITIAL_NAME, initialName)
                putExtra(EXTRA_INITIAL_URL, initialUrl)
                putExtra(EXTRA_INITIAL_USER_AGENT, initialUserAgent.orEmpty())
                putExtra(EXTRA_INITIAL_ALLOW_INSECURE, initialAllowInsecure)
                putExtra(EXTRA_INITIAL_INTERVAL_HOURS, initialIntervalHours ?: -1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionEditScreen(
    isEdit: Boolean,
    initialName: String,
    initialUrl: String,
    initialUserAgent: String,
    initialAllowInsecure: Boolean,
    initialIntervalText: String,
    onBack: () -> Unit,
    onSave: (SubscriptionEditActivity.SaveOutput) -> Unit
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(initialName) }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var userAgent by rememberSaveable { mutableStateOf(initialUserAgent) }
    var allowInsecure by rememberSaveable { mutableStateOf(initialAllowInsecure) }
    var intervalText by rememberSaveable { mutableStateOf(initialIntervalText) }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }

    fun validateAndBuild(refreshNow: Boolean): SubscriptionEditActivity.SaveOutput? {
        saveError = null
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        if (trimmedName.isBlank()) {
            saveError = context.getString(R.string.subs_edit_error_name_required)
            return null
        }
        if (!trimmedUrl.matches(Regex("^https?://.+", RegexOption.IGNORE_CASE))) {
            saveError = context.getString(R.string.subs_edit_error_url_format)
            return null
        }
        val intervalHours = intervalText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (intervalText.isNotBlank() && intervalHours == null) {
            saveError = context.getString(R.string.subs_edit_error_interval_not_integer)
            return null
        }
        if (intervalHours != null && intervalHours < 1) {
            saveError = context.getString(R.string.subs_edit_error_interval_min)
            return null
        }
        return SubscriptionEditActivity.SaveOutput(
            name = trimmedName,
            url = trimmedUrl,
            userAgent = userAgent.trim().ifBlank { null },
            allowInsecure = allowInsecure,
            intervalHours = intervalHours,
            refreshNow = refreshNow
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEdit) R.string.subs_edit_title_edit else R.string.subs_edit_title_add
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.subs_cd_back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { validateAndBuild(refreshNow = false)?.let(onSave) }) {
                        Text(stringResource(R.string.subs_edit_button_save))
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
            val tabTitles = listOf(
                stringResource(R.string.subs_edit_tab_simple),
                stringResource(R.string.subs_edit_tab_advanced),
            )
            TabRow(selectedTabIndex = tabIndex) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (tabIndex == 0) {
                SimpleSubscriptionEditor(
                    name = name,
                    onNameChange = { name = it },
                    url = url,
                    onUrlChange = { url = it },
                    intervalText = intervalText,
                    onIntervalChange = { intervalText = it }
                )
            } else {
                AdvancedSubscriptionEditor(
                    userAgent = userAgent,
                    onUserAgentChange = { userAgent = it },
                    allowInsecure = allowInsecure,
                    onAllowInsecureChange = { allowInsecure = it }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { validateAndBuild(refreshNow = true)?.let(onSave) }) {
                    Text(stringResource(R.string.subs_edit_button_save_refresh))
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSubscriptionEditor(
    name: String,
    onNameChange: (String) -> Unit,
    url: String,
    onUrlChange: (String) -> Unit,
    intervalText: String,
    onIntervalChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.subs_edit_label_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.subs_edit_label_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = intervalText,
            onValueChange = onIntervalChange,
            label = { Text(stringResource(R.string.subs_edit_label_interval)) },
            placeholder = { Text(stringResource(R.string.subs_edit_placeholder_interval)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSubscriptionEditor(
    userAgent: String,
    onUserAgentChange: (String) -> Unit,
    allowInsecure: Boolean,
    onAllowInsecureChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = userAgent,
            onValueChange = onUserAgentChange,
            label = { Text(stringResource(R.string.subs_edit_label_user_agent)) },
            placeholder = { Text(stringResource(R.string.subs_edit_placeholder_user_agent)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        DropdownField(
            value = if (allowInsecure) "true" else "false",
            onValueChange = { onAllowInsecureChange(it == "true") },
            label = stringResource(R.string.subs_edit_label_allow_insecure_tls),
            options = listOf(
                "false" to stringResource(R.string.subs_edit_option_no),
                "true" to stringResource(R.string.subs_edit_option_yes_insecure),
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
