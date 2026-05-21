package com.justme.xtls_core_proxy.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportedLanguageTagTest {

    @Test
    fun autoMapsToNullTag() {
        assertNull(SupportedLanguage.AUTO.tag)
    }

    @Test
    fun englishMapsToEnTag() {
        assertEquals("en", SupportedLanguage.ENGLISH.tag)
    }

    @Test
    fun russianMapsToRuTag() {
        assertEquals("ru", SupportedLanguage.RUSSIAN.tag)
    }

    @Test
    fun fromTagRoundTripsForKnownTags() {
        assertEquals(SupportedLanguage.AUTO, SupportedLanguage.fromTag(null))
        assertEquals(SupportedLanguage.AUTO, SupportedLanguage.fromTag(""))
        assertEquals(SupportedLanguage.ENGLISH, SupportedLanguage.fromTag("en"))
        assertEquals(SupportedLanguage.RUSSIAN, SupportedLanguage.fromTag("ru"))
    }

    @Test
    fun fromTagFallsBackToAutoForUnknown() {
        assertEquals(SupportedLanguage.AUTO, SupportedLanguage.fromTag("xx"))
        assertEquals(SupportedLanguage.AUTO, SupportedLanguage.fromTag("fa"))
    }
}
