package com.justme.xtls_core_proxy.failover

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.IOException
import java.net.HttpURLConnection

class Http204HealthProbeTest {

    private fun probe(conn: HttpURLConnection) =
        Http204HealthProbe(targetUrl = "http://probe.test/204", timeoutMs = 5_000L) { conn }

    @Test
    fun status204_isHealthy() = runTest {
        val conn: HttpURLConnection = mock { on { responseCode } doReturn 204 }
        assertTrue(probe(conn).isHealthy())
        verify(conn).disconnect()
    }

    @Test
    fun status200_isUnhealthy() = runTest {
        // A captive portal or an injected block page answers 200, not 204.
        val conn: HttpURLConnection = mock { on { responseCode } doReturn 200 }
        assertFalse(probe(conn).isHealthy())
    }

    @Test
    fun ioException_isUnhealthy_andDoesNotThrow() = runTest {
        val conn: HttpURLConnection = mock { on { responseCode } doThrow IOException("no route") }
        assertFalse(probe(conn).isHealthy())
        verify(conn).disconnect()
    }

    @Test
    fun openerThrowing_isUnhealthy_andDoesNotThrow() = runTest {
        val p = Http204HealthProbe("http://probe.test/204", 5_000L) { throw IOException("dns fail") }
        assertFalse(p.isHealthy())
    }
}
