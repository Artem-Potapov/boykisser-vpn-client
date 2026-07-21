package com.justme.xtls_core_proxy.settings

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NEW-M2 / M2-a regression for commit e87215e: the Config-Sanitization failure message must be a
 * self-contained localized sentence with NO %1$s interpolation, so the Russian string never carries an
 * English tail. Mirrors LanguagePickerTest's locale-set + getString pattern (deterministic, no Compose).
 */
@RunWith(AndroidJUnit4::class)
class SanitizationFailureLocalizationTest {

    @After
    fun resetLocale() = setLanguage(SupportedLanguage.AUTO)

    @Test
    fun failureMessage_russian_isSelfContained_noEnglishTail_noPlaceholder() {
        setLanguage(SupportedLanguage.RUSSIAN)
        ActivityScenario.launch(ConfigSanitizationActivity::class.java).use { scenario ->
            val msg = scenario.getString(R.string.sanitization_failure)
            assertEquals("Конфиг этого профиля не удалось обработать.", msg)
            assertFalse("no English tail", msg.contains("could not be processed", ignoreCase = true))
            assertFalse("no format placeholder (fix drops %1\$s)", msg.contains("%"))
        }
    }

    @Test
    fun failureMessage_english_hasNoPlaceholder() {
        setLanguage(SupportedLanguage.ENGLISH)
        ActivityScenario.launch(ConfigSanitizationActivity::class.java).use { scenario ->
            val msg = scenario.getString(R.string.sanitization_failure)
            assertEquals("This profile's config couldn't be processed.", msg)
            assertFalse("no format placeholder", msg.contains("%"))
        }
    }

    private fun setLanguage(language: SupportedLanguage) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            SupportedLanguage.apply(context, language)
        }
    }

    private fun ActivityScenario<ConfigSanitizationActivity>.getString(resId: Int): String {
        var result = ""
        onActivity { result = it.getString(resId) }
        return result
    }
}
