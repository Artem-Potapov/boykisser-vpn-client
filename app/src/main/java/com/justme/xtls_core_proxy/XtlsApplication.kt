package com.justme.xtls_core_proxy

import android.app.Application
import android.content.Context
import com.justme.xtls_core_proxy.i18n.SupportedLanguage

class XtlsApplication : Application() {
    override fun attachBaseContext(base: Context) {
        SupportedLanguage.applyFromStorage(base)
        super.attachBaseContext(base)
    }
}
