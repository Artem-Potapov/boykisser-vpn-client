package com.justme.xtls_core_proxy.subs

import com.justme.xtls_core_proxy.config.ConfigBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionBodyParserTest {

    private val vlessReality =
        "vless://11111111-1111-1111-1111-111111111111@a.example.com:443" +
            "?type=tcp&security=reality&pbk=abc123&sid=1a2b3c&fp=chrome&sni=cdn.example.com"

    private val vlessTls =
        "vless://22222222-2222-2222-2222-222222222222@b.example.com:8443" +
            "?type=ws&security=tls&path=%2Fws&host=b.example.com&sni=b.example.com#TLS%20Server"

    private val vlessNoFragment =
        "vless://33333333-3333-3333-3333-333333333333@c.example.com:8080" +
            "?type=tcp&security=none"

    private val rawJsonOutbound = """
        {
          "outbounds": [
            {
              "tag": "json-tag",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "json.example.com",
                    "port": 443,
                    "users": [{"id": "44444444-4444-4444-4444-444444444444", "encryption": "none"}]
                  }
                ]
              },
              "streamSettings": {"network": "tcp", "security": "none"}
            }
          ]
        }
    """.trimIndent().replace("\n", " ")

    @Test
    fun parsesPlainNewlineSeparatedVless() {
        val outcome = SubscriptionBodyParser.parseBody(
            "$vlessReality\n$vlessTls\n$vlessNoFragment\n"
        )
        assertEquals(0, outcome.parseErrorCount)
        assertEquals(3, outcome.parsed.size)
        assertEquals("a.example.com:443", outcome.parsed[0].displayName)
        assertEquals("TLS Server", outcome.parsed[1].displayName)
        assertEquals("c.example.com:8080", outcome.parsed[2].displayName)
        assertTrue(outcome.parsed[0].config.trimStart().startsWith("{"))
        assertTrue(ConfigBuilder.buildRuntimeConfig(outcome.parsed[0].config).contains("\"protocol\":\"tun\""))
    }

    @Test
    fun parsesBase64WrappedBody() {
        val combined = "$vlessReality\n$vlessTls"
        val encoded = Base64.getEncoder().encodeToString(combined.toByteArray())
        val outcome = SubscriptionBodyParser.parseBody(encoded)
        assertEquals(0, outcome.parseErrorCount)
        assertEquals(2, outcome.parsed.size)
    }

    @Test
    fun parsesBase64BodyWithWhitespace() {
        val combined = "$vlessReality\n$vlessTls"
        val encoded = Base64.getEncoder().encodeToString(combined.toByteArray())
        val chunked = encoded.chunked(20).joinToString("\n")
        val outcome = SubscriptionBodyParser.parseBody(chunked)
        assertEquals(0, outcome.parseErrorCount)
        assertEquals(2, outcome.parsed.size)
    }

    @Test
    fun parsesUrlSafeBase64Body() {
        val combined = "$vlessReality\n$vlessTls"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(combined.toByteArray())
        val outcome = SubscriptionBodyParser.parseBody(encoded)
        assertEquals(0, outcome.parseErrorCount)
        assertEquals(2, outcome.parsed.size)
    }

    @Test
    fun parsesPerLineBase64Mixed() {
        val encoded = Base64.getEncoder().encodeToString(vlessReality.toByteArray())
        val outcome = SubscriptionBodyParser.parseBody("$vlessTls\n$encoded\n")
        assertEquals(0, outcome.parseErrorCount)
        assertEquals(2, outcome.parsed.size)
    }

    @Test
    fun parsesJsonPerLine() {
        val outcome = SubscriptionBodyParser.parseBody("$rawJsonOutbound\n")
        assertEquals(0, outcome.parseErrorCount)
        assertEquals(1, outcome.parsed.size)
        assertEquals("json-tag", outcome.parsed[0].displayName)
    }

    @Test
    fun dropsCommentsAndEmptyLines() {
        val body = """
            # this is a comment
            $vlessReality

            # another
            $vlessTls
        """.trimIndent()
        val outcome = SubscriptionBodyParser.parseBody(body)
        assertEquals(0, outcome.parseErrorCount)
        assertEquals(2, outcome.parsed.size)
    }

    @Test
    fun reportsParseErrorsForMalformedLines() {
        val body = "$vlessReality\nvless://broken\n{not-json\n"
        val outcome = SubscriptionBodyParser.parseBody(body)
        assertEquals(1, outcome.parsed.size)
        assertTrue(outcome.parseErrorCount >= 1)
    }

    @Test
    fun deduplicatesNamesCaseInsensitive() {
        val withFragment = "$vlessNoFragment#Same%20Name"
        val withFragmentSecond = "$vlessReality#same%20NAME"
        val outcome = SubscriptionBodyParser.parseBody("$withFragment\n$withFragmentSecond\n")
        assertEquals(0, outcome.parseErrorCount)
        assertEquals(2, outcome.parsed.size)
        assertEquals("Same Name", outcome.parsed[0].displayName)
        assertEquals("same NAME (2)", outcome.parsed[1].displayName)
    }

    @Test
    fun handlesEmptyAndCommentOnlyBodies() {
        assertEquals(0, SubscriptionBodyParser.parseBody("").parsed.size)
        assertEquals(0, SubscriptionBodyParser.parseBody("# only comment\n# another\n").parsed.size)
    }

    @Test
    fun rejectsBase64FalsePositive() {
        // "AAAA" is valid base64 but decodes to 0x00 bytes - should fall back to raw body and parse 0 lines.
        val outcome = SubscriptionBodyParser.parseBody("AAAA")
        assertEquals(0, outcome.parsed.size)
    }

    @Test
    fun preservesActiveProfileLineWhenSurroundedByErrors() {
        val body = "garbage line 1\n$vlessReality\nzzz garbage 2\n"
        val outcome = SubscriptionBodyParser.parseBody(body)
        assertEquals(1, outcome.parsed.size)
        assertNotNull(outcome.parsed[0].displayName)
        assertFalse(outcome.parsed[0].displayName.isBlank())
    }
}
