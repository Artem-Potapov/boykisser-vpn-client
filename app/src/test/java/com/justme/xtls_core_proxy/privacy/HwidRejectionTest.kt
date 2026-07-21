package com.justme.xtls_core_proxy.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HwidRejectionTest {

    @Test
    fun maxDevices_detected_caseInsensitive() {
        assertEquals(
            HwidRejection.MAX_DEVICES,
            HwidRejectionDetector.detect(mapOf("X-Hwid-Max-Devices-Reached" to listOf("true")), true)
        )
    }

    @Test
    fun legacyHwidLimit_detected() {
        assertEquals(
            HwidRejection.MAX_DEVICES,
            HwidRejectionDetector.detect(mapOf("x-hwid-limit" to listOf("TRUE")), true)
        )
    }

    @Test
    fun notSupported_onlyWhenHwidNotSent() {
        assertEquals(
            HwidRejection.NOT_SUPPORTED,
            HwidRejectionDetector.detect(mapOf("x-hwid-not-supported" to listOf("true")), hwidWasSent = false)
        )
        // Our header WAS sent -> not the user's fault -> no rejection, fall through to generic error.
        assertNull(
            HwidRejectionDetector.detect(mapOf("x-hwid-not-supported" to listOf("true")), hwidWasSent = true)
        )
    }

    @Test
    fun malformedValue_isIgnored() {
        assertNull(HwidRejectionDetector.detect(mapOf("x-hwid-max-devices-reached" to listOf("yes")), true))
    }

    @Test
    fun cleanHeaders_and_nullStatusKey_yieldNoRejection() {
        @Suppress("UNCHECKED_CAST")
        val raw = linkedMapOf<String?, List<String>>(
            null to listOf("HTTP/1.1 404 Not Found"),
            "content-type" to listOf("text/plain"),
        ) as Map<String, List<String>>
        assertNull(HwidRejectionDetector.detect(raw, true))
    }

    @Test
    fun maxDevices_takesPrecedenceOverNotSupported() {
        val headers = mapOf(
            "x-hwid-not-supported" to listOf("true"),
            "x-hwid-max-devices-reached" to listOf("true"),
        )
        assertEquals(HwidRejection.MAX_DEVICES, HwidRejectionDetector.detect(headers, hwidWasSent = false))
    }
}
