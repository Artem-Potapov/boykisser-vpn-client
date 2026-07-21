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
}
