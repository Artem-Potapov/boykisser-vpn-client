package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.Hysteria2ConfigCodec
import com.justme.xtls_core_proxy.config.Hysteria2Profile
import com.justme.xtls_core_proxy.config.Hysteria2SimpleFields
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Hysteria2ConfigCodecTest {
    @Test
    fun parseHysteria2Uri_readsCoreTlsAndSalamanderFields() {
        val uri = "hysteria2://secret@example.com:443/?" +
            "sni=cdn.example.com&alpn=h3&insecure=1&pinSHA256=ABCD" +
            "&obfs=salamander&obfs-password=mask#Demo"

        val profile = Hysteria2ConfigCodec.parseUri(uri)

        assertEquals("secret", profile.auth)
        assertEquals("example.com", profile.host)
        assertEquals(443, profile.port)
        assertNull(profile.portHopPorts)
        assertEquals("cdn.example.com", profile.serverName)
        assertEquals("h3", profile.alpn)
        assertTrue(profile.allowInsecure)
        assertEquals("ABCD", profile.pinnedPeerCertSha256)
        assertEquals("mask", profile.salamanderPassword)
    }

    @Test
    fun parseHy2Uri_supportsMultiPortAuthority() {
        val profile = Hysteria2ConfigCodec.parseUri(
            "hy2://secret@example.com:123,5000-6000/?sni=cdn.example.com"
        )

        assertEquals(123, profile.port)
        assertEquals("123,5000-6000", profile.portHopPorts)
    }

    @Test
    fun toXrayJson_writesRequiredHysteria2Shape() {
        val json = Hysteria2ConfigCodec.toXrayJson(
            Hysteria2Profile(
                auth = "secret",
                host = "example.com",
                port = 443,
                portHopPorts = null,
                serverName = "cdn.example.com",
                alpn = "h3",
                allowInsecure = true,
                pinnedPeerCertSha256 = "ABCD",
                salamanderPassword = "mask",
                congestion = "brutal",
                uploadBandwidth = "100mbps",
                downloadBandwidth = "200mbps",
                udpHopInterval = "30",
                finalmaskJson = null
            )
        )

        val outbound = JSONObject(json).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria", outbound.getString("protocol"))
        assertEquals(2, outbound.getJSONObject("settings").getInt("version"))
        assertEquals("example.com", outbound.getJSONObject("settings").getString("address"))
        val ss = outbound.getJSONObject("streamSettings")
        assertEquals("hysteria", ss.getString("network"))
        assertEquals("tls", ss.getString("security"))
        assertEquals(2, ss.getJSONObject("hysteriaSettings").getInt("version"))
        assertEquals("secret", ss.getJSONObject("hysteriaSettings").getString("auth"))
        val tls = ss.getJSONObject("tlsSettings")
        assertEquals("cdn.example.com", tls.getString("serverName"))
        assertTrue(tls.getBoolean("allowInsecure"))
        assertEquals("ABCD", tls.getString("pinnedPeerCertSha256"))
        val finalmask = ss.getJSONObject("finalmask")
        assertEquals("salamander", finalmask.getJSONArray("udp").getJSONObject(0).getString("type"))
        assertEquals("brutal", finalmask.getJSONObject("quicParams").getString("congestion"))
        assertEquals("100mbps", finalmask.getJSONObject("quicParams").getString("brutalUp"))
        assertEquals("200mbps", finalmask.getJSONObject("quicParams").getString("brutalDown"))
    }

    @Test
    fun parseProfileFromJson_readsFinalmaskAndTlsFields() {
        val json = """
            {
              "outbounds": [{
                "tag": "proxy",
                "protocol": "hysteria",
                "settings": { "version": 2, "address": "example.com", "port": 443 },
                "streamSettings": {
                  "network": "hysteria",
                  "security": "tls",
                  "tlsSettings": {
                    "serverName": "cdn.example.com",
                    "alpn": ["h3"],
                    "allowInsecure": true,
                    "pinnedPeerCertSha256": "ABCD"
                  },
                  "hysteriaSettings": { "version": 2, "auth": "secret" },
                  "finalmask": {
                    "udp": [{ "type": "salamander", "settings": { "password": "mask" } }],
                    "quicParams": {
                      "congestion": "brutal",
                      "brutalUp": "100mbps",
                      "brutalDown": "200mbps",
                      "udpHop": { "ports": "20000-50000", "interval": "30" },
                      "maxIdleTimeout": 60
                    }
                  }
                }
              }]
            }
        """.trimIndent()

        val profile = Hysteria2ConfigCodec.parseProfileFromJson(json)

        assertEquals("secret", profile.auth)
        assertEquals("example.com", profile.host)
        assertEquals(443, profile.port)
        assertEquals("cdn.example.com", profile.serverName)
        assertEquals("h3", profile.alpn)
        assertTrue(profile.allowInsecure)
        assertEquals("ABCD", profile.pinnedPeerCertSha256)
        assertEquals("mask", profile.salamanderPassword)
        assertEquals("brutal", profile.congestion)
        assertEquals("100mbps", profile.uploadBandwidth)
        assertEquals("200mbps", profile.downloadBandwidth)
        assertEquals("20000-50000", profile.portHopPorts)
        assertEquals("30", profile.udpHopInterval)
        assertEquals(
            60,
            JSONObject(requireNotNull(profile.finalmaskJson))
                .getJSONObject("quicParams")
                .getInt("maxIdleTimeout")
        )
    }

    @Test
    fun simpleFieldsRoundTripToProfileAndJson() {
        val fields = Hysteria2SimpleFields(
            auth = "secret",
            host = "example.com",
            port = "443",
            portHopPorts = "443,20000-21000",
            serverName = "cdn.example.com",
            alpn = "h3",
            allowInsecure = "true",
            pinnedPeerCertSha256 = "ABCD",
            salamanderPassword = "mask",
            congestion = "brutal",
            uploadBandwidth = "100mbps",
            downloadBandwidth = "200mbps",
            udpHopInterval = "15",
            finalmaskJson = """{"quicParams":{"maxIdleTimeout":60}}"""
        )

        val json = Hysteria2ConfigCodec.toXrayJson(fields.toProfile())
        val profile = Hysteria2ConfigCodec.parseProfileFromJson(json)

        assertEquals("secret", profile.auth)
        assertEquals("443,20000-21000", profile.portHopPorts)
        assertEquals("mask", profile.salamanderPassword)
        assertEquals("brutal", profile.congestion)
        assertEquals("100mbps", profile.uploadBandwidth)
        assertEquals("200mbps", profile.downloadBandwidth)
        assertEquals("15", profile.udpHopInterval)
        assertEquals(
            60,
            JSONObject(requireNotNull(profile.finalmaskJson))
                .getJSONObject("quicParams")
                .getInt("maxIdleTimeout")
        )
    }

    @Test
    fun mergeProfileIntoJson_preservesUnknownFinalmaskKeys() {
        val source = """
            {
              "outbounds": [{
                "protocol": "hysteria",
                "settings": { "version": 2, "address": "old.example.com", "port": 443 },
                "streamSettings": {
                  "network": "hysteria",
                  "security": "tls",
                  "tlsSettings": { "serverName": "old.example.com" },
                  "hysteriaSettings": { "version": 2, "auth": "old" },
                  "finalmask": { "quicParams": { "maxIdleTimeout": 60 } }
                }
              }]
            }
        """.trimIndent()

        val merged = Hysteria2ConfigCodec.mergeProfileIntoJson(
            source,
            Hysteria2Profile(
                auth = "new",
                host = "new.example.com",
                port = 8443,
                portHopPorts = "8443,20000-21000",
                serverName = "cdn.new.example.com",
                alpn = "h3",
                allowInsecure = false,
                pinnedPeerCertSha256 = "",
                salamanderPassword = "mask",
                congestion = "brutal",
                uploadBandwidth = "",
                downloadBandwidth = "",
                udpHopInterval = "15",
                finalmaskJson = """{"quicParams":{"maxIdleTimeout":60}}"""
            )
        )

        val outbound = JSONObject(merged).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("new.example.com", outbound.getJSONObject("settings").getString("address"))
        assertEquals(8443, outbound.getJSONObject("settings").getInt("port"))
        val finalmask = outbound.getJSONObject("streamSettings").getJSONObject("finalmask")
        assertEquals(60, finalmask.getJSONObject("quicParams").getInt("maxIdleTimeout"))
        assertEquals("brutal", finalmask.getJSONObject("quicParams").getString("congestion"))
        assertEquals(
            "8443,20000-21000",
            finalmask.getJSONObject("quicParams").getJSONObject("udpHop").getString("ports")
        )
    }

    @Test
    fun mergeProfileIntoJson_preservesNonSalamanderUdpEntries() {
        val source = """
            {
              "outbounds": [{
                "protocol": "hysteria",
                "settings": { "version": 2, "address": "old.example.com", "port": 443 },
                "streamSettings": {
                  "network": "hysteria",
                  "security": "tls",
                  "tlsSettings": { "serverName": "old.example.com" },
                  "hysteriaSettings": { "version": 2, "auth": "old" },
                  "finalmask": {
                    "udp": [
                      { "type": "mkcp-original", "settings": { "seed": "keep-me" } },
                      { "type": "salamander", "settings": { "password": "old-mask" } }
                    ],
                    "quicParams": { "maxIdleTimeout": 60 }
                  }
                }
              }]
            }
        """.trimIndent()

        val merged = Hysteria2ConfigCodec.mergeProfileIntoJson(
            source,
            Hysteria2Profile(
                auth = "new",
                host = "new.example.com",
                port = 8443,
                serverName = "cdn.new.example.com",
                salamanderPassword = "new-mask",
                finalmaskJson = """
                    {
                      "udp": [
                        { "type": "mkcp-original", "settings": { "seed": "keep-me" } },
                        { "type": "salamander", "settings": { "password": "old-mask" } }
                      ],
                      "quicParams": { "maxIdleTimeout": 60 }
                    }
                """.trimIndent()
            )
        )

        val udp = JSONObject(merged)
            .getJSONArray("outbounds")
            .getJSONObject(0)
            .getJSONObject("streamSettings")
            .getJSONObject("finalmask")
            .getJSONArray("udp")

        assertEquals(2, udp.length())
        assertEquals("mkcp-original", udp.getJSONObject(0).getString("type"))
        assertEquals("keep-me", udp.getJSONObject(0).getJSONObject("settings").getString("seed"))
        assertEquals("salamander", udp.getJSONObject(1).getString("type"))
        assertEquals("new-mask", udp.getJSONObject(1).getJSONObject("settings").getString("password"))
    }

    @Test
    fun mergeProfileIntoJson_preservesOutboundTagAndUnrelatedKeys() {
        val source = """
            {
              "outbounds": [{
                "tag": "custom-hy2",
                "protocol": "hysteria",
                "sendThrough": "192.0.2.10",
                "domainStrategy": "UseIPv4",
                "settings": { "version": 2, "address": "old.example.com", "port": 443 },
                "streamSettings": {
                  "network": "hysteria",
                  "security": "tls",
                  "tlsSettings": { "serverName": "old.example.com" },
                  "hysteriaSettings": { "version": 2, "auth": "old" }
                }
              }]
            }
        """.trimIndent()

        val merged = Hysteria2ConfigCodec.mergeProfileIntoJson(
            source,
            Hysteria2Profile(
                auth = "new",
                host = "new.example.com",
                port = 8443,
                serverName = "cdn.new.example.com",
                alpn = "h3"
            )
        )

        val outbound = JSONObject(merged).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("custom-hy2", outbound.getString("tag"))
        assertEquals("192.0.2.10", outbound.getString("sendThrough"))
        assertEquals("UseIPv4", outbound.getString("domainStrategy"))
        assertEquals("new.example.com", outbound.getJSONObject("settings").getString("address"))
        assertEquals(8443, outbound.getJSONObject("settings").getInt("port"))
        assertEquals("new", outbound.getJSONObject("streamSettings").getJSONObject("hysteriaSettings").getString("auth"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseUri_rejectsMissingAuth() {
        Hysteria2ConfigCodec.parseUri("hysteria2://example.com:443/")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseUri_rejectsUnsupportedObfs() {
        Hysteria2ConfigCodec.parseUri("hysteria2://secret@example.com:443/?obfs=gecko")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseUri_rejectsInvalidMultiPort() {
        Hysteria2ConfigCodec.parseUri("hy2://secret@example.com:443,70000/")
    }
}
