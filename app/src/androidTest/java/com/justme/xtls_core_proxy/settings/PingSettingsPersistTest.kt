package com.justme.xtls_core_proxy.settings

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.state.PingPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Same all-or-nothing persist() regression as XraySettingsPersistTest, on the Ping settings screen:
 * an out-of-range timeout used to veto the whole write, so flipping the auto-ping switch mid-edit was
 * silently discarded. Each field must hold its own last-good persisted value independently.
 *
 * The auto-ping switch is lower-stakes than XRAY's IPv6 toggle (no privacy fail-open), but it is the
 * same defect shape, so it gets the same guard and the same coverage.
 *
 * Non-destructive: xray_prefs (which also backs Ping) is snapshotted and restored around the test.
 */
@RunWith(AndroidJUnit4::class)
class PingSettingsPersistTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("xray_prefs", Context.MODE_PRIVATE)
    private lateinit var snapshot: Map<String, Any?>

    @Before
    fun setBaseline() {
        snapshot = prefs.all.toMap()
        // Distinct, valid starting values; auto OFF so the toggle has somewhere to move. The timeout
        // value 7000 is what the field will display before we invalidate it.
        PingPreferences.save(
            context,
            PingPreferences(
                targetUrl = "http://ping.test/",
                timeoutMs = 7000,
                concurrency = 3,
                autoOnOpen = false,
            ),
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

    private fun label(id: Int): String = SupportedLanguage.localize(context).getString(id)

    @Test
    fun invalidTimeout_doesNotVetoAutoToggle() {
        ActivityScenario.launch(PingTestSettingsActivity::class.java).use {
            composeRule.waitForIdle()

            // Match the timeout field by its (stable) label rather than its value, so the reference
            // survives the value changing to empty/invalid between the two actions below.
            val timeoutField = composeRule.onNode(hasSetTextAction() and hasText(label(R.string.ping_timeout)))
            timeoutField.performTextClearance()
            timeoutField.performTextInput("50") // < TIMEOUT_MIN (1000) — invalid

            // Auto-ping is the only switch on the screen.
            composeRule.onNode(isToggleable()).performClick()
            composeRule.waitForIdle()

            // The switch must have committed despite the invalid timeout...
            assertTrue(
                "auto-ping toggle was silently dropped by an invalid timeout",
                PingPreferences.load(context).autoOnOpen,
            )
            // ...and the invalid timeout must have held the last-good value, not persisted 50.
            assertEquals(7000L, PingPreferences.load(context).timeoutMs)
        }
    }
}
