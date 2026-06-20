package com.justme.xtls_core_proxy.add

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardAddRouterTest {

    @Test
    fun emptyOrWhitespace_returnsEmpty() {
        val inputs = listOf("", "   ", "\n\t ")

        inputs.forEach { raw ->
            assertEquals(ClipboardKind.Empty, ClipboardAddRouter.classify(raw))
        }
    }

    @Test
    fun httpAndHttpsUrls_returnSubscription_evenWithUppercaseScheme() {
        assertEquals(
            ClipboardKind.Subscription("https://example.com/sub"),
            ClipboardAddRouter.classify("https://example.com/sub")
        )
        assertEquals(
            ClipboardKind.Subscription("HTTPS://EXAMPLE.COM/SUB"),
            ClipboardAddRouter.classify("HTTPS://EXAMPLE.COM/SUB")
        )
    }

    @Test
    fun invalidOrMalformedHttpUrls_returnInvalid() {
        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify("https://"))
        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify("http://bad host"))
    }

    @Test
    fun validVlessUri_returnsVless() {
        val uri = "vless://01234567-89ab-cdef-0123-456789abcdef@example.com:443?security=tls#demo"
        assertEquals(ClipboardKind.Vless(uri), ClipboardAddRouter.classify(uri))
    }

    @Test
    fun validHysteria2Uri_returnsHysteria2() {
        val uri = "hysteria2://secret@example.com:443/?sni=cdn.example.com#demo"
        assertEquals(ClipboardKind.Hysteria2(uri), ClipboardAddRouter.classify(uri))
    }

    @Test
    fun validHy2UriWithMultiPort_returnsHysteria2() {
        val uri = "hy2://secret@example.com:123,5000-6000/?sni=cdn.example.com#demo"
        assertEquals(ClipboardKind.Hysteria2(uri), ClipboardAddRouter.classify(uri))
    }

    @Test
    fun hysteria2MissingAuthOrBadObfs_returnsInvalid() {
        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify("hysteria2://example.com:443/"))
        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify("hy2://secret@example.com:443/?obfs=gecko"))
    }

    @Test
    fun vlessMissingUuidOrPort_returnsInvalid() {
        val missingUuidEmpty = "vless://@example.com:443?security=tls#demo"
        val missingUuidNoAt = "vless://example.com:443?security=tls#demo"
        val missingPort = "vless://01234567-89ab-cdef-0123-456789abcdef@example.com"

        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify(missingUuidEmpty))
        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify(missingUuidNoAt))
        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify(missingPort))
    }

    @Test
    fun vlessOutOfRangePort_returnsInvalid() {
        val port65536 = "vless://01234567-89ab-cdef-0123-456789abcdef@example.com:65536?security=tls#demo"
        val port70000 = "vless://01234567-89ab-cdef-0123-456789abcdef@example.com:70000?security=tls#demo"

        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify(port65536))
        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify(port70000))
    }

    @Test
    fun trojanScheme_returnsUnsupportedScheme() {
        assertEquals(
            ClipboardKind.UnsupportedScheme("trojan"),
            ClipboardAddRouter.classify("trojan://password@example.com:443")
        )
    }

    @Test
    fun ssScheme_returnsUnsupportedScheme() {
        assertEquals(
            ClipboardKind.UnsupportedScheme("ss"),
            ClipboardAddRouter.classify("ss://YWVzLTI1Ni1nY206cGFzc0BleGFtcGxlLmNvbTo0NDM=")
        )
    }

    @Test
    fun validJsonConfig_returnsJson() {
        val json = """{"outbounds":[{"protocol":"freedom"}]}"""
        assertEquals(ClipboardKind.Json(json), ClipboardAddRouter.classify(json))
    }

    @Test
    fun malformedJsonAndPlainText_returnInvalid() {
        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify("""{"outbounds": [}"""))
        assertEquals(ClipboardKind.Invalid, ClipboardAddRouter.classify("just some plain text"))
    }

    @Test
    fun trimsWhitespaceBeforeClassifying() {
        val raw = "   https://example.com/sub   "
        assertEquals(
            ClipboardKind.Subscription("https://example.com/sub"),
            ClipboardAddRouter.classify(raw)
        )
    }
}
