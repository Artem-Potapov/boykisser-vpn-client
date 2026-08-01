package com.justme.xtls_core_proxy.failover

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FailoverSettingsPersistTest {

    @get:Rule val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("xray_prefs", Context.MODE_PRIVATE)
    private lateinit var snapshot: Map<String, Any?>

    @Before
    fun setBaseline() {
        snapshot = prefs.all.toMap()
        FailoverPreferences.save(
            context,
            FailoverPreferences.DEFAULT.copy(enabled = false, probeIntervalMs = 20_000L),
        )
    }

    @After
    fun restorePrefs() {
        prefs.edit {
            clear()
            for ((k, v) in snapshot) when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
                is Float -> putFloat(k, v)
                is String -> putString(k, v)
                is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, v as Set<String>)
            }
        }
    }

    private fun label(id: Int) = SupportedLanguage.localize(context).getString(id)

    @Test
    fun invalidInterval_doesNotVetoEnableToggle() {
        ActivityScenario.launch(FailoverSettingsActivity::class.java).use {
            composeRule.waitForIdle()

            val field = composeRule.onNode(hasSetTextAction() and hasText(label(R.string.failover_interval)))
            field.performTextClearance()
            field.performTextInput("3")            // below INTERVAL_MIN

            composeRule.onNodeWithTag(FAILOVER_ENABLED_SWITCH_TAG).performClick()
            composeRule.waitForIdle()

            assertTrue(
                "the enable toggle must not be dropped by an invalid interval",
                FailoverPreferences.load(context).enabled,
            )
            assertEquals(20_000L, FailoverPreferences.load(context).probeIntervalMs)
        }
    }
}
