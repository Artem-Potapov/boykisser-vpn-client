package com.justme.xtls_core_proxy.i18n

import android.content.Context
import androidx.activity.ComponentActivity

abstract class LocalizedComponentActivity : ComponentActivity() {

    private var appliedLocaleTags: String = ""

    override fun attachBaseContext(newBase: Context) {
        appliedLocaleTags = SupportedLanguage.readLocales(newBase).toLanguageTags()
        super.attachBaseContext(SupportedLanguage.localize(newBase))
    }

    override fun onResume() {
        super.onResume()
        // LocaleManager.setApplicationLocales does not recreate back-stack activities
        // — only the activity in the foreground when the change happened. We self-recreate
        // here when the chosen locale no longer matches what attachBaseContext applied.
        val currentTags = SupportedLanguage.readLocales(this).toLanguageTags()
        if (currentTags != appliedLocaleTags) {
            recreate()
        }
    }
}
