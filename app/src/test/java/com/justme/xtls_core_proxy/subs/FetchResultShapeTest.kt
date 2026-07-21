package com.justme.xtls_core_proxy.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchResultShapeTest {

    @Test
    fun failure_defaultsAreBackwardCompatible() {
        val f = FetchResult.Failure("boom")
        assertEquals("boom", f.message)
        assertEquals(null, f.httpStatus)
        assertTrue(f.responseHeaders.isEmpty())
    }

    @Test
    fun failure_carriesStatusAndHeaders() {
        val f = FetchResult.Failure(
            message = "HTTP 403",
            httpStatus = 403,
            responseHeaders = mapOf("x-hwid-max-devices-reached" to listOf("true")),
        )
        assertEquals(403, f.httpStatus)
        assertEquals(listOf("true"), f.responseHeaders["x-hwid-max-devices-reached"])
    }
}
