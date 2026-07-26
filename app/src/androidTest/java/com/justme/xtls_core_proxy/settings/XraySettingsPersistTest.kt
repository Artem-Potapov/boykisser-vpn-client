package com.justme.xtls_core_proxy.settings

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.config.XrayCorePreferences
import com.justme.xtls_core_proxy.config.XrayCoreSettings
import com.justme.xtls_core_proxy.config.XrayDomainStrategy
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the all-or-nothing persist() bug on the XRAY settings screen. An out-of-range MTU
 * used to make persist() skip the *entire* write, so flipping the IPv6 switch while the MTU field was
 * mid-edit committed nothing — the screen showed IPv6 off while prefs kept it on, and the next
 * connect emitted no `::/0 → block` rule (a privacy control failing in the fail-open direction). Each
 * control must now hold its own last-good persisted value independently.
 *
 * v2 createEmptyComposeRule + manual ActivityScenario.launch so the baseline prefs are in place
 * before the screen reads them in `remember { XrayCorePreferences.load(context) }`. Non-destructive:
 * xray_prefs is snapshotted and restored around the test (see DeviceIdentityConcurrencyTest for the
 * rationale — this branch exists to keep the stored HWID stable).
 */
@RunWith(AndroidJUnit4::class)
class XraySettingsPersistTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("xray_prefs", Context.MODE_PRIVATE)
    private lateinit var snapshot: Map<String, Any?>

    @Before
    fun setBaseline() {
        snapshot = prefs.all.toMap()
        // Known-good starting point: valid MTU, IPv6 ON, so the toggle has somewhere to move.
        XrayCorePreferences.save(
            context,
            XrayCoreSettings(
                mtu = 1400,
                ipv6 = true,
                sniffing = false,
                domainStrategy = XrayDomainStrategy.FROM_CONFIG,
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

    /** Resolve labels through the same localize() path the Activity renders with, so the matchers
     *  hold in whatever locale is persisted on the device. */
    private fun label(id: Int): String = SupportedLanguage.localize(context).getString(id)

    @Test
    fun invalidMtu_doesNotVetoIpv6Toggle() {
        ActivityScenario.launch(XraySettingsActivity::class.java).use {
            composeRule.waitForIdle()

            // The MTU field is the only editable text field on the screen (the domain-strategy
            // dropdown is readOnly); qualify by its label anyway for robustness.
            val mtuField = composeRule.onNode(hasSetTextAction() and hasText(label(R.string.xray_mtu)))
            mtuField.performTextClearance()
            mtuField.performTextInput("99999") // > MTU_MAX (1500) — invalid

            // Toggle IPv6. Matched by testTag: the two switches collapse into sibling peers in the
            // merged semantics tree, so a structural matcher can't tell IPv6 from Sniffing.
            composeRule.onNodeWithTag(IPV6_SWITCH_TAG).performClick()
            composeRule.waitForIdle()

            // The privacy control must have committed despite the invalid MTU...
            assertFalse(
                "IPv6 toggle was silently dropped by an invalid MTU",
                XrayCorePreferences.load(context).ipv6,
            )
            // ...and the invalid MTU must have held the last-good value, not zeroed or persisted 99999.
            assertEquals(1400, XrayCorePreferences.load(context).mtu)
        }
    }
}
