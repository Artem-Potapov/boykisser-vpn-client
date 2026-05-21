package com.justme.xtls_core_proxy.i18n

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.settings.SettingsHubActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguagePickerTest {

    @After
    fun resetLocale() {
        setLanguage(SupportedLanguage.AUTO)
    }

    /**
     * A freshly launched activity reads its strings in whatever locale was set via
     * SupportedLanguage.apply before launch. This exercises the same code path the
     * picker uses in production, including the LocaleManager-direct call on API 33+
     * and the SharedPreferences-backed persistence layer.
     */
    @Test
    fun freshActivityRendersInChosenLocale() {
        setLanguage(SupportedLanguage.RUSSIAN)
        ActivityScenario.launch(SettingsHubActivity::class.java).use { scenario ->
            val title = scenario.runOnActivityAndGet { it.getString(R.string.settings_title) }
            assertEquals("Настройки", title)
        }
    }

    /**
     * The actual user-facing flow: an activity is already running, the picker
     * changes the locale, and the activity resumes from the back stack. The
     * activity must self-recreate so its UI reflects the new locale — this is
     * the exact bug class that earlier shipped broken (back-stack activities
     * don't auto-recreate, only the foreground one).
     */
    @Test
    fun resumingWithChangedLocaleRecreatesActivityInNewLocale() {
        setLanguage(SupportedLanguage.ENGLISH)
        ActivityScenario.launch(SettingsHubActivity::class.java).use { scenario ->
            val englishTitle = scenario.runOnActivityAndGet { it.getString(R.string.settings_title) }
            assertEquals("Settings", englishTitle)

            scenario.moveToState(Lifecycle.State.STARTED)
            setLanguage(SupportedLanguage.RUSSIAN)
            scenario.moveToState(Lifecycle.State.RESUMED)

            val russianTitle = scenario.runOnActivityAndGet { it.getString(R.string.settings_title) }
            assertNotEquals(englishTitle, russianTitle)
            assertEquals("Настройки", russianTitle)
        }
    }

    private fun setLanguage(language: SupportedLanguage) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SupportedLanguage.apply(context, language)
        }
    }

    private fun <T> ActivityScenario<SettingsHubActivity>.runOnActivityAndGet(
        block: (SettingsHubActivity) -> T,
    ): T {
        var result: T? = null
        onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
