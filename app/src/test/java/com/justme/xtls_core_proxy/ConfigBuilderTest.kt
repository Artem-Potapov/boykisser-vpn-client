package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {
    @Test
    fun toProfileStorageConfig_injectsTunInboundForJson() {
        val input = """{"outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
        val stored = ConfigBuilder.toProfileStorageConfig(input)
        val root = JSONObject(stored)

        assertEquals(1, root.getJSONArray("inbounds").length())
        assertEquals("tun", root.getJSONArray("inbounds").getJSONObject(0).getString("protocol"))
        assertEquals("freedom", root.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
    }

    @Test
    fun toProfileStorageConfig_sanitizesForeignInboundsToTun() {
        // A real imported config (e.g. a Hysteria2 panel export) ships local socks / dokodemo
        // inbounds. Storage must canonicalize to the single tun inbound, not persist them.
        val json = """
            {
              "inbounds": [
                {"protocol": "socks", "tag": "socks", "listen": "127.0.0.1", "port": 10808},
                {"protocol": "dokodemo-door", "tag": "metrics_in"}
              ],
              "outbounds": [
                {"protocol": "hysteria", "tag": "proxy",
                 "settings": {"version": 2, "address": "e.com", "port": 443},
                 "streamSettings": {"network": "hysteria", "hysteriaSettings": {"version": 2, "auth": "x"}}}
              ]
            }
        """.trimIndent()
        val inbounds = JSONObject(ConfigBuilder.toProfileStorageConfig(json)).getJSONArray("inbounds")

        assertEquals(1, inbounds.length())
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))
    }

    @Test
    fun toProfileStorageConfig_keepsNonJsonInputUnchanged() {
        // Non-JSON, non-share-link input falls through untouched; it is rejected later at runtime.
        val input = "not a json config"
        assertEquals(input, ConfigBuilder.toProfileStorageConfig(input))
    }

    @Test
    fun toProfileStorageConfig_convertsVlessToJson() {
        val input = "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none"
        val stored = ConfigBuilder.toProfileStorageConfig(input)
        val root = JSONObject(stored)

        assertTrue(stored.trimStart().startsWith("{"))
        assertEquals(
            "vless",
            root.getJSONArray("outbounds").getJSONObject(0).getString("protocol")
        )
    }

    @Test
    fun toProfileStorageConfig_convertsHysteria2ToJson() {
        val input = "hy2://secret@example.com:443/?sni=cdn.example.com#HY2"
        val stored = ConfigBuilder.toProfileStorageConfig(input)
        val outbound = JSONObject(stored).getJSONArray("outbounds").getJSONObject(0)

        assertTrue(stored.trimStart().startsWith("{"))
        assertEquals("hysteria", outbound.getString("protocol"))
        assertEquals(2, outbound.getJSONObject("settings").getInt("version"))
    }

    @Test
    fun fromVlessUri_buildsTunOnlyInboundAndVlessOutbound() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=tcp&security=reality&pbk=abc123&sid=1a2b3c&fp=chrome&sni=cdn.example.com"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("tun", inbound.getString("protocol"))
        assertEquals("vless", outbound.getString("protocol"))
        assertEquals("example.com", outbound.getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)
            .getString("address"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromVlessUri_missingPort_throws() {
        ConfigBuilder.fromVlessUri("vless://11111111-1111-1111-1111-111111111111@example.com")
    }

    @Test
    fun fromJson_injectsTunInbound() {
        val input = """
            {
              "inbounds": [
                {"protocol": "dokodemo-door", "tag": "legacy"}
              ],
              "outbounds": [
                {"protocol": "freedom", "tag": "direct"}
              ]
            }
        """.trimIndent()

        val config = JSONObject(ConfigBuilder.fromJson(input))
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)

        assertEquals(1, config.getJSONArray("inbounds").length())
        assertEquals("tun", inbound.getString("protocol"))
    }

    @Test
    fun fromJson_sanitizesSocksInbound() {
        val input = """
            {
              "inbounds": [{"protocol": "socks", "tag": "loopback"}],
              "outbounds": [{"protocol": "freedom", "tag": "direct"}]
            }
        """.trimIndent()

        val config = JSONObject(ConfigBuilder.fromJson(input))
        val inbounds = config.getJSONArray("inbounds")

        assertEquals(1, inbounds.length())
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))
    }

    @Test
    fun fromJson_sanitizesHttpInbound() {
        val input = """
            {
              "inbounds": [{"protocol": "http", "tag": "loopback"}],
              "outbounds": [{"protocol": "freedom", "tag": "direct"}]
            }
        """.trimIndent()

        val config = JSONObject(ConfigBuilder.fromJson(input))
        val inbounds = config.getJSONArray("inbounds")

        assertEquals(1, inbounds.length())
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))
    }

    @Test
    fun replaceJsonInboundsWithTun_dropsLocalProxiesKeepsOutbounds() {
        val input = """
            {
              "inbounds": [
                {"protocol": "mixed", "port": 10808, "tag": "mixed"},
                {"protocol": "http", "port": 10809, "tag": "http"}
              ],
              "outbounds": [
                {"protocol": "vless", "tag": "proxy"},
                {"protocol": "freedom", "tag": "direct"}
              ]
            }
        """.trimIndent()

        val config = JSONObject(ConfigBuilder.replaceJsonInboundsWithTun(input))
        val inbounds = config.getJSONArray("inbounds")

        assertEquals(1, inbounds.length())
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))
        assertEquals(
            "vless",
            config.getJSONArray("outbounds").getJSONObject(0).getString("protocol")
        )
    }

    @Test
    fun replaceJsonInboundsWithTun_replacesAnExistingForeignTun() {
        val input = """
            {
              "inbounds": [{"protocol": "tun", "settings": {"name": "foreign_tun", "MTU": 9000}}],
              "outbounds": [{"protocol": "freedom", "tag": "direct"}]
            }
        """.trimIndent()

        val settings = JSONObject(ConfigBuilder.replaceJsonInboundsWithTun(input))
            .getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")

        assertEquals("xray_tun", settings.getString("name"))
        assertEquals(1500, settings.getInt("MTU"))
    }

    @Test
    fun buildRuntimeConfig_acceptsVlessAndJson() {
        val vless = ConfigBuilder.buildRuntimeConfig(
            "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none"
        )
        val json = ConfigBuilder.buildRuntimeConfig(
            """{"outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
        )
        assertTrue(vless.contains("\"protocol\":\"tun\""))
        assertTrue(json.contains("\"protocol\":\"tun\""))
    }

    @Test
    fun buildRuntimeConfig_acceptsHysteria2Link() {
        val runtime = ConfigBuilder.buildRuntimeConfig("hysteria2://secret@example.com:443/?sni=cdn.example.com")
        val root = JSONObject(runtime)
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("tun", root.getJSONArray("inbounds").getJSONObject(0).getString("protocol"))
        assertEquals("hysteria", outbound.getString("protocol"))
    }

    @Test
    fun fromVlessUri_tlsWithWsProducesCorrectStreamSettings() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=ws&security=tls&sni=cdn.example.com&path=%2Fws&host=cdn.example.com&alpn=h2"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val ss = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")

        assertEquals("ws", ss.getString("network"))
        assertEquals("tls", ss.getString("security"))
        assertTrue(ss.has("tlsSettings"))
        assertEquals("cdn.example.com", ss.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("h2", ss.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
        assertTrue(ss.has("wsSettings"))
        assertEquals("/ws", ss.getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun fromVlessUri_realityWithAlpnAndSpiderX() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=tcp&security=reality&pbk=key123&sid=ab&sni=sni.com&fp=firefox&alpn=h2&spx=%2Findex"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val ss = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")

        val reality = ss.getJSONObject("realitySettings")
        assertEquals("h2", reality.getJSONArray("alpn").getString(0))
        assertEquals("/index", reality.getString("spiderX"))
    }

    @Test
    fun fromVlessUri_preservesEncryptionParam() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=tcp&security=none&encryption=mlkem768x25519"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val user = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)
            .getJSONArray("users")
            .getJSONObject(0)

        assertEquals("mlkem768x25519", user.getString("encryption"))
    }

    @Test
    fun fromVlessUri_defaultsEncryptionToNoneWhenAbsent() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443?type=tcp&security=none"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val user = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)
            .getJSONArray("users")
            .getJSONObject(0)

        assertEquals("none", user.getString("encryption"))
    }

    @Test
    fun fromVlessUri_preservesXhttpExtraJson() {
        // %7B%22xPaddingBytes%22%3A%22100-1000%22%7D == {"xPaddingBytes":"100-1000"}
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=xhttp&security=tls&sni=cdn.example.com&path=%2Fxh" +
            "&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val xhttp = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("xhttpSettings")

        assertTrue("xhttpSettings must contain extra", xhttp.has("extra"))
        assertEquals(
            "100-1000",
            xhttp.getJSONObject("extra").getString("xPaddingBytes")
        )
    }

    @Test
    fun fromVlessUri_tcpHeaderTypeWritesTcpSettings() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=tcp&security=none&headerType=http"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val ss = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")

        assertEquals(
            "http",
            ss.getJSONObject("tcpSettings").getJSONObject("header").getString("type")
        )
    }

    @Test
    fun fromVlessUri_kcpHeaderTypeWritesKcpHeader() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=kcp&security=none&seed=mySeed&headerType=wechat-video"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val kcp = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("kcpSettings")

        assertEquals("mySeed", kcp.getString("seed"))
        assertEquals("wechat-video", kcp.getJSONObject("header").getString("type"))
    }

    @Test
    fun fromVlessUri_xhttpModeWritesIntoXhttpSettings() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=xhttp&security=tls&sni=cdn.example.com&path=%2Fxh&mode=stream-up"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val xhttp = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("xhttpSettings")

        assertEquals("stream-up", xhttp.getString("mode"))
    }

    @Test
    fun fromVlessUri_grpcModeWritesIntoGrpcSettings() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=grpc&security=tls&sni=cdn.example.com&serviceName=svc&mode=multi"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val grpc = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("grpcSettings")

        assertEquals("multi", grpc.getString("mode"))
    }

    @Test
    fun fromVlessUri_quicSecurityWritesIntoQuicSettings() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=quic&security=tls&sni=cdn.example.com&quicSecurity=aes-128-gcm&key=mysecret"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val quic = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("quicSettings")

        assertEquals("aes-128-gcm", quic.getString("security"))
        assertEquals("mysecret", quic.getString("key"))
    }

    @Test
    fun fromVlessUri_grpcAuthorityWritesIntoGrpcSettings() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?type=grpc&security=tls&sni=cdn.example.com&serviceName=svc&authority=auth.example.com"

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val grpc = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("grpcSettings")

        assertEquals("auth.example.com", grpc.getString("authority"))
    }

    @Test
    fun fromVlessUri_xhttpComprehensiveLink_preservesAllParameters() {
        // Mirrors the user-reported failure: vless+xhttp with encryption,
        // extra (URL-encoded JSON), mode, full TLS+REALITY-style fields.
        val extraJson = """{"xPaddingBytes":"100-1000","scMaxEachPostBytes":"1000000"}"""
        val encodedExtra = java.net.URLEncoder.encode(extraJson, "UTF-8")
        val uri = "vless://11111111-1111-1111-1111-111111111111@89.125.89.146:443" +
            "?type=xhttp" +
            "&security=tls" +
            "&encryption=none" +
            "&sni=cdn.example.com" +
            "&fp=chrome" +
            "&alpn=h2" +
            "&path=%2Fxh" +
            "&host=cdn.example.com" +
            "&mode=stream-up" +
            "&extra=" + encodedExtra

        val config = JSONObject(ConfigBuilder.fromVlessUri(uri))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        val user = outbound.getJSONObject("settings")
            .getJSONArray("vnext")
            .getJSONObject(0)
            .getJSONArray("users")
            .getJSONObject(0)
        val ss = outbound.getJSONObject("streamSettings")
        val xhttp = ss.getJSONObject("xhttpSettings")

        // Identity / encryption
        assertEquals("11111111-1111-1111-1111-111111111111", user.getString("id"))
        assertEquals("none", user.getString("encryption"))

        // Stream / TLS
        assertEquals("xhttp", ss.getString("network"))
        assertEquals("tls", ss.getString("security"))
        val tls = ss.getJSONObject("tlsSettings")
        assertEquals("cdn.example.com", tls.getString("serverName"))
        assertEquals("chrome", tls.getString("fingerprint"))
        assertEquals("h2", tls.getJSONArray("alpn").getString(0))

        // XHTTP
        assertEquals("/xh", xhttp.getString("path"))
        assertEquals("cdn.example.com", xhttp.getString("host"))
        assertEquals("stream-up", xhttp.getString("mode"))
        assertEquals("100-1000", xhttp.getJSONObject("extra").getString("xPaddingBytes"))
        assertEquals("1000000", xhttp.getJSONObject("extra").getString("scMaxEachPostBytes"))
    }
}
