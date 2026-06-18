package com.justme.xtls_core_proxy.killswitch

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * No-bypass coverage for the kill-on-foreground consent gate.
 *
 * Uses the v2 createEmptyComposeRule + ActivityScenario.launch (not the
 * auto-launching createAndroidComposeRule) so prefs/consent state can be set
 * BEFORE the activity reads them in onCreate. The v2 rule drives the real device
 * clock, so it stays connected to the ActivityScenario-launched compose roots and
 * lets the delay()-driven countdown advance in real time (the v1 rule ran on a
 * virtual-time UnconfinedTestDispatcher detached from those roots, which produced
 * "No compose hierarchies found" / ComposeTimeoutException flakes). After each
 * launch we call waitForIdle() so the activity's roots register with the rule.
 *
 * The master toggle is `enabled = permissionGranted` (Usage Access), so
 * @BeforeClass grants GET_USAGE_STATS via the shell uid.
 *
 * The countdown is delay()-driven, so timing is awaited in real time with
 * waitUntil — mainClock.advanceTimeBy would NOT move a coroutine delay timer.
 */
@RunWith(AndroidJUnit4::class)
class KillSwitchConsentGateTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    companion object {
        @BeforeClass
        @JvmStatic
        fun grantUsageAccess() {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("appops set ${ctx.packageName} GET_USAGE_STATS allow")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    @Before
    fun resetState() {
        context.getSharedPreferences("xray_prefs", Context.MODE_PRIVATE).edit { clear() }
        KillSwitchRepository.load(context)
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        SupportedLanguage.localize(context).getString(id, *args)

    private fun waitForGateToOpen() {
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText(str(R.string.kill_switch_consent_accept))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun togglingOn_showsGate_andDoesNotCommitUntilAccept() {
        ActivityScenario.launch(KillSwitchSettingsActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNode(isToggleable()).performClick()

            composeRule.onNodeWithText(str(R.string.kill_switch_consent_title)).assertIsDisplayed()
            composeRule.onNodeWithText(str(R.string.kill_switch_consent_accept), substring = true)
                .assertIsNotEnabled()
            composeRule.onNodeWithText(str(R.string.kill_switch_consent_cancel)).assertIsNotEnabled()
            assertFalse(KillSwitchRepository.load(context).enabled)

            waitForGateToOpen()
            composeRule.onNodeWithText(str(R.string.kill_switch_consent_accept)).performClick()
            // The repository write after accept is async; wait (≤2s) for it to land before asserting.
            composeRule.waitUntil(timeoutMillis = 2_000) {
                KillSwitchRepository.load(context).enabled
            }
            assertTrue(KillSwitchRepository.load(context).enabled)
        }
    }

    @Test
    fun decline_leavesFeatureOff() {
        ActivityScenario.launch(KillSwitchSettingsActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNode(isToggleable()).performClick()
            composeRule.onNodeWithText(str(R.string.kill_switch_consent_title)).assertIsDisplayed()
            // During the countdown, cancel must be inert (mirrors the security guard in test 1).
            composeRule.onNodeWithText(str(R.string.kill_switch_consent_cancel)).assertIsNotEnabled()

            waitForGateToOpen()
            composeRule.onNodeWithText(str(R.string.kill_switch_consent_cancel)).performClick()

            composeRule.onNodeWithText(str(R.string.kill_switch_consent_title)).assertDoesNotExist()
            assertFalse(KillSwitchRepository.load(context).enabled)
        }
    }

    @Test
    fun gateFires_evenAfterPriorConsent_andNeverAutoEnables() {
        KillSwitchRepository.markConsented(context)

        ActivityScenario.launch(KillSwitchSettingsActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNode(isToggleable()).performClick()

            composeRule.onNodeWithText(str(R.string.kill_switch_consent_title)).assertIsDisplayed()
            assertFalse(KillSwitchRepository.load(context).enabled)

            // The flag only SHORTENS the countdown — the returning gate must open well
            // within the first-consent 5s window (here within 4s). A regression that
            // always used 5s (or suppressed the shortening) would time out here.
            composeRule.waitUntil(timeoutMillis = 4_000) {
                composeRule.onAllNodesWithText(str(R.string.kill_switch_consent_accept))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(KillSwitchRepository.load(context).enabled)
        }
    }

    @Test
    fun rotation_duringCountdown_doesNotBypassGate() {
        ActivityScenario.launch(KillSwitchSettingsActivity::class.java).use { scenario ->
            composeRule.waitForIdle()
            composeRule.onNode(isToggleable()).performClick()
            composeRule.onNodeWithText(str(R.string.kill_switch_consent_title)).assertIsDisplayed()

            scenario.recreate()
            composeRule.waitForIdle()

            composeRule.onNodeWithText(str(R.string.kill_switch_consent_title)).assertIsDisplayed()
            assertFalse(KillSwitchRepository.load(context).enabled)
        }
    }
}
