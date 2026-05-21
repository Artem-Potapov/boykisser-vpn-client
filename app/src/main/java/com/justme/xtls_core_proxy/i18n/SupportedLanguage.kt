package com.justme.xtls_core_proxy.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The set of languages the picker exposes. `tag` is the BCP-47 language tag
 * passed to AppCompatDelegate, or null for "follow system locale".
 *
 * To add a new language: add the enum value, create app/src/main/res/values-<tag>/strings.xml,
 * and surface any per-language UI footnotes (e.g., machine-translation disclaimers).
 */
// TODO(localization): add PERSIAN("fa") once translated.
// When added, surface R.string.lang_persian_machine_translated_notice as a
// subtitle/footnote below the Persian option in the picker.
enum class SupportedLanguage(val tag: String?) {
    AUTO(null),
    ENGLISH("en"),
    RUSSIAN("ru");

    companion object {
        fun fromTag(tag: String?): SupportedLanguage {
            if (tag.isNullOrEmpty()) return AUTO
            return entries.firstOrNull { it.tag == tag } ?: AUTO
        }

        fun current(): SupportedLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return AUTO
            return fromTag(locales[0]?.language)
        }

        fun apply(language: SupportedLanguage) {
            val list = if (language.tag == null) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
            AppCompatDelegate.setApplicationLocales(list)
        }
    }
}
