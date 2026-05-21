package com.justme.xtls_core_proxy.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
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

        /**
         * Returns the user's chosen app locales.
         *
         * On API 33+ this queries LocaleManager directly. AppCompatDelegate's static
         * path also uses LocaleManager — but only when its sContext WeakReference is
         * non-null, which it isn't unless an AppCompatActivity has been instantiated.
         * We extend ComponentActivity, so AppCompatDelegate's static state stays null
         * and its setApplicationLocales / getApplicationLocales become silent no-ops.
         */
        fun readLocales(context: Context): LocaleListCompat {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val lm = context.getSystemService(LocaleManager::class.java)
                LocaleListCompat.wrap(lm.applicationLocales)
            } else {
                AppCompatDelegate.getApplicationLocales()
            }
        }

        fun current(context: Context): SupportedLanguage {
            val locales = readLocales(context)
            if (locales.isEmpty) return AUTO
            return fromTag(locales[0]?.language)
        }

        private const val PREFS_NAME = "xtls_locale_prefs"
        private const val KEY_TAG = "language_tag"

        /**
         * Applies the user's stored language choice. Call this from
         * Application.attachBaseContext on every process start.
         *
         * Why we maintain our own SharedPreferences-backed storage instead of trusting
         * AppCompat's autoStoreLocales backport: AppCompat's persistence-restore on
         * API <33 depends on internal state (sContext / sLocaleManagerInitialized) that
         * is only initialized when an AppCompatActivity is instantiated. This app
         * extends ComponentActivity via LocalizedComponentActivity, so that state stays
         * uninitialized and the autoStore restore never fires — confirmed empirically
         * on an API 30 device. Maintaining our own prefs makes persistence deterministic
         * across all API levels.
         */
        fun applyFromStorage(context: Context) {
            val tag = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TAG, null)
            applyInternal(context, fromTag(tag))
        }

        fun apply(context: Context, language: SupportedLanguage) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TAG, language.tag)
                .apply()
            applyInternal(context, language)
        }

        private fun applyInternal(context: Context, language: SupportedLanguage) {
            val tag = language.tag
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val lm = context.getSystemService(LocaleManager::class.java)
                lm.applicationLocales = if (tag == null) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(tag)
                }
            } else {
                val list = if (tag == null) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tag)
                }
                AppCompatDelegate.setApplicationLocales(list)
            }
        }

        /**
         * Returns a Context whose Configuration carries the user's chosen app locale.
         *
         * Called from LocalizedComponentActivity.attachBaseContext so resources resolve
         * against the user-chosen locale. On API 33+ the system also applies it, but
         * explicitly wrapping is harmless and keeps a single code path.
         */
        fun localize(base: Context): Context {
            val locales = readLocales(base)
            if (locales.isEmpty) return base
            val systemLocales = Array(locales.size()) { locales[it]!! }
            val config = Configuration(base.resources.configuration)
            config.setLocales(LocaleList(*systemLocales))
            return base.createConfigurationContext(config)
        }
    }
}
