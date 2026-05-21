package com.justme.xtls_core_proxy.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate

/**
 * Base activity that applies the user-chosen app locale to its base context.
 *
 * On API 33+, the system applies per-app locales automatically via LocaleManager
 * and this override is a no-op for an empty locale list. On API 30-32, this
 * override is what actually makes the chosen locale visible to resources.
 */
abstract class LocalizedComponentActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) {
            super.attachBaseContext(newBase)
            return
        }
        val systemLocales = Array(locales.size()) { locales[it]!! }
        val config = Configuration(newBase.resources.configuration)
        config.setLocales(LocaleList(*systemLocales))
        val wrapped = newBase.createConfigurationContext(config)
        super.attachBaseContext(wrapped)
    }
}
