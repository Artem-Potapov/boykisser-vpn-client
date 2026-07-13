package com.justme.xtls_core_proxy

import android.app.Application
import android.content.Context
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.log.LogPreferences
import com.justme.xtls_core_proxy.log.LogRepository

class XtlsApplication : Application() {
    override fun attachBaseContext(base: Context) {
        SupportedLanguage.applyFromStorage(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        LogRepository.setMaxLines(LogPreferences.getBufferLines(this))
    }
}
