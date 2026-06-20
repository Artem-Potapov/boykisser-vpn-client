package com.justme.xtls_core_proxy.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class EditableServerProtocolTest {

    // --- Case 1: VLESS proxy outbound NOT first -> VLESS ---

    @Test
    fun jsonVlessOutboundNotFirst_detectsVless() {
        val json = """
            {
              "outbounds": [
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "block", "protocol": "blackhole" },
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": { "vnext": [{ "address": "a.com", "port": 443, "users": [{"id":"aaaa","encryption":"none"}] }] },
                  "streamSettings": { "network": "tcp", "security": "none" }
                }
              ]
            }
        """.trimIndent()

        assertEquals(EditableServerProtocol.VLESS, detectEditableServerProtocol(json))
    }

    // --- Case 2: Hysteria2 v2 outbound NOT first -> HYSTERIA2 ---

    @Test
    fun jsonHysteria2OutboundNotFirst_detectsHysteria2() {
        val json = """
            {
              "outbounds": [
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "block", "protocol": "blackhole" },
                {
                  "tag": "proxy",
                  "protocol": "hysteria",
                  "settings": { "version": 2, "address": "example.com", "port": 443 },
                  "streamSettings": {
                    "network": "hysteria",
                    "security": "tls",
                    "tlsSettings": { "serverName": "cdn.example.com" },
                    "hysteriaSettings": { "version": 2, "auth": "secret" }
                  }
                }
              ]
            }
        """.trimIndent()

        assertEquals(EditableServerProtocol.HYSTERIA2, detectEditableServerProtocol(json))
    }

    // --- Case 3: malformed / non-v2 Hysteria2 -> ADVANCED_ONLY ---

    @Test
    fun jsonHysteriaMissingVersion_detectsAdvancedOnly() {
        val json = """
            {
              "outbounds": [{
                "tag": "proxy",
                "protocol": "hysteria",
                "settings": { "address": "example.com", "port": 443 },
                "streamSettings": {
                  "network": "hysteria",
                  "security": "tls",
                  "tlsSettings": { "serverName": "cdn.example.com" },
                  "hysteriaSettings": { "auth": "secret" }
                }
              }]
            }
        """.trimIndent()

        assertEquals(EditableServerProtocol.ADVANCED_ONLY, detectEditableServerProtocol(json))
    }

    @Test
    fun jsonHysteriaMissingAuth_detectsAdvancedOnly() {
        val json = """
            {
              "outbounds": [{
                "tag": "proxy",
                "protocol": "hysteria",
                "settings": { "version": 2, "address": "example.com", "port": 443 },
                "streamSettings": {
                  "network": "hysteria",
                  "security": "tls",
                  "tlsSettings": { "serverName": "cdn.example.com" },
                  "hysteriaSettings": { "version": 2 }
                }
              }]
            }
        """.trimIndent()

        assertEquals(EditableServerProtocol.ADVANCED_ONLY, detectEditableServerProtocol(json))
    }

    // --- Case 4: regression guards ---

    @Test
    fun vlessUri_detectsVless() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@demo.example:443" +
            "?type=tcp&security=reality&pbk=pubkey123&sid=a1b2c3&fp=chrome&sni=cdn.example.com&flow=xtls-rprx-vision"

        assertEquals(EditableServerProtocol.VLESS, detectEditableServerProtocol(uri))
    }

    @Test
    fun hysteria2Uri_detectsHysteria2() {
        val uri = "hysteria2://secret@example.com:443/?sni=cdn.example.com"

        assertEquals(EditableServerProtocol.HYSTERIA2, detectEditableServerProtocol(uri))
    }

    @Test
    fun hy2AliasUri_detectsHysteria2() {
        val uri = "hy2://secret@example.com:443/?sni=cdn.example.com"

        assertEquals(EditableServerProtocol.HYSTERIA2, detectEditableServerProtocol(uri))
    }

    @Test
    fun blankString_detectsVless() {
        assertEquals(EditableServerProtocol.VLESS, detectEditableServerProtocol(""))
        assertEquals(EditableServerProtocol.VLESS, detectEditableServerProtocol("   "))
    }

    @Test
    fun jsonUnknownProtocol_detectsAdvancedOnly() {
        val trojan = """
            {
              "outbounds": [{
                "tag": "proxy",
                "protocol": "trojan",
                "settings": { "servers": [{ "address": "a.com", "port": 443, "password": "pw" }] }
              }]
            }
        """.trimIndent()
        val shadowsocks = """
            {
              "outbounds": [{
                "tag": "proxy",
                "protocol": "shadowsocks",
                "settings": { "servers": [{ "address": "a.com", "port": 443, "password": "pw", "method": "aes-128-gcm" }] }
              }]
            }
        """.trimIndent()

        assertEquals(EditableServerProtocol.ADVANCED_ONLY, detectEditableServerProtocol(trojan))
        assertEquals(EditableServerProtocol.ADVANCED_ONLY, detectEditableServerProtocol(shadowsocks))
    }
}
