package com.justme.xtls_core_proxy

import android.app.Application
import android.content.Context
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.log.LogPreferences
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.ui.theme.AppearanceRepository

class XtlsApplication : Application() {
    override fun attachBaseContext(base: Context) {
        SupportedLanguage.applyFromStorage(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        AppearanceRepository.load(this)
        LogRepository.setMaxLines(LogPreferences.getBufferLines(this))
    }
}
