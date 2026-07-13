package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.XrayLogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class LogSettingsTest {
    @Test
    fun fromName_parsesKnownLevel() {
        assertEquals(XrayLogLevel.WARNING, XrayLogLevel.fromName("WARNING"))
    }

    @Test
    fun fromName_parsesNonDefaultKnownLevel() {
        assertEquals(XrayLogLevel.DEBUG, XrayLogLevel.fromName("DEBUG"))
    }

    @Test
    fun fromName_fallsBackToWarningForUnknownName() {
        assertEquals(XrayLogLevel.WARNING, XrayLogLevel.fromName("bogus"))
    }

    @Test
    fun fromName_fallsBackToWarningForNull() {
        assertEquals(XrayLogLevel.WARNING, XrayLogLevel.fromName(null))
    }
}
