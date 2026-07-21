package com.justme.xtls_core_proxy.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoofIdentitiesTest {

    @Test
    fun seed_isDeterministicPerHwid() {
        assertEquals(SpoofIdentities.seed("a983997074675192"), SpoofIdentities.seed("a983997074675192"))
        assertNotEquals(SpoofIdentities.seed("a983997074675192"), SpoofIdentities.seed("0000000000000001"))
    }

    @Test
    fun resolveAndroid_auto_isDeterministicAndPlausible() {
        val a = SpoofIdentities.resolveAndroid("a983997074675192", null, null)
        val b = SpoofIdentities.resolveAndroid("a983997074675192", null, null)
        assertEquals(a, b) // stable across calls
        assertEquals("Android", a.os)
        assertTrue(a.verOs in SpoofIdentities.ANDROID_VERSIONS)
        assertTrue(a.model.isNotBlank())
    }

    @Test
    fun resolveAndroid_versionPin_isRespected() {
        val r = SpoofIdentities.resolveAndroid("a983997074675192", "16", null)
        assertEquals("16", r.verOs)
    }

    @Test
    fun resolveAndroid_modelFamilyPin_constrainsModel() {
        val r = SpoofIdentities.resolveAndroid("a983997074675192", null, "pixel")
        assertTrue(r.model.startsWith("Pixel"))
    }

    @Test
    fun resolveAndroid_differentHwidsSpreadAcrossPool() {
        val models = (0 until 40).map {
            SpoofIdentities.resolveAndroid(DeviceIdentityRepository.formatHwid(it.toLong()), null, null).model
        }.toSet()
        assertTrue("expected variety across HWIDs", models.size >= 3)
    }

    @Test
    fun resolveIphone_auto_isPlausible() {
        val r = SpoofIdentities.resolveIphone("a983997074675192", null, null)
        assertEquals("iOS", r.os)
        assertTrue(r.verOs in SpoofIdentities.IOS_VERSIONS)
        assertTrue(r.model in SpoofIdentities.IOS_MODELS)
    }

    @Test
    fun resolveIphone_modelPin_isRespected() {
        val r = SpoofIdentities.resolveIphone("a983997074675192", null, "iPhone 15 Pro")
        assertEquals("iPhone 15 Pro", r.model)
    }

    @Test
    fun resolveAndroid_huaweiFamily_onlyCuratedAndroidModels() {
        val allowed = setOf("ELS-NX9", "VOG-L29", "JAD-LX9")
        val models = (0 until 64).map {
            SpoofIdentities.resolveAndroid(
                DeviceIdentityRepository.formatHwid(it.toLong()),
                versionPin = null,
                modelFamilyPin = "huawei",
            ).model
        }.toSet()
        assertTrue("expected only curated Huawei Android models, got $models", models.all { it in allowed })
        assertTrue("expected all curated Huawei models to appear, got $models", models == allowed)
    }

    // --- Contradictory-pin fallback: the model axis wins over an impossible version pin, ---
    // --- and the resolved pair is always a real, internally-plausible curated row.         ---

    @Test
    fun resolveAndroid_contradictoryPin_modelAxisWins_pixelOnAndroid9() {
        // No Pixel row ships Android 9 in the table; the family pin must win and the
        // impossible version relax to a real Pixel version — NOT "Android 9 on a Pixel 8 Pro".
        val r = SpoofIdentities.resolveAndroid("a983997074675192", versionPin = "9", modelFamilyPin = "pixel")
        assertEquals("Android", r.os)
        assertTrue("expected a Pixel model, got ${r.model}", r.model.startsWith("Pixel"))
        assertNotEquals("9", r.verOs)
        assertTrue("expected a real Pixel version, got ${r.verOs}", r.verOs in setOf("13", "14", "15", "16"))
    }

    @Test
    fun resolveAndroid_contradictoryPin_modelAxisWins_huaweiOnAndroid16() {
        val allowed = setOf("ELS-NX9", "VOG-L29", "JAD-LX9")
        val r = SpoofIdentities.resolveAndroid("a983997074675192", versionPin = "16", modelFamilyPin = "huawei")
        assertTrue("expected a curated Huawei model, got ${r.model}", r.model in allowed)
        assertNotEquals("16", r.verOs)
        assertTrue("expected a real Huawei version, got ${r.verOs}", r.verOs in setOf("9", "11"))
    }

    @Test
    fun resolveAndroid_satisfiablePin_honorsBothAxes() {
        // Regression lock: the only Pixel-on-Android-14 row is Pixel 8 Pro; both pins stay honored.
        val r = SpoofIdentities.resolveAndroid("a983997074675192", versionPin = "14", modelFamilyPin = "pixel")
        assertEquals("14", r.verOs)
        assertEquals("Pixel 8 Pro", r.model)
    }

    @Test
    fun resolveIphone_contradictoryPin_modelAxisWins_iphone17() {
        // iPhone 17 ships iOS 26.1 in the table; the impossible 16.7 pin is relaxed to it.
        val r = SpoofIdentities.resolveIphone("a983997074675192", versionPin = "16.7", modelPin = "iPhone 17")
        assertEquals("iPhone 17", r.model)
        assertEquals("26.1", r.verOs)
    }

    @Test
    fun resolveIphone_satisfiablePin_honorsBothAxes() {
        // Regression lock: iOS 18.5 + iPhone 15 Pro is a real table row; both pins stay honored.
        val r = SpoofIdentities.resolveIphone("a983997074675192", versionPin = "18.5", modelPin = "iPhone 15 Pro")
        assertEquals("18.5", r.verOs)
        assertEquals("iPhone 15 Pro", r.model)
    }
}
