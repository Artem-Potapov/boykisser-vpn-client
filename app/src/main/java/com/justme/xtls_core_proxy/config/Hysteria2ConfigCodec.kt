package com.justme.xtls_core_proxy.config

// i18n: audited 2026-06-20, no user-visible strings

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class Hysteria2Profile(
    val auth: String,
    val host: String,
    val port: Int,
    val portHopPorts: String? = null,
    val serverName: String,
    val alpn: String = "h3",
    val allowInsecure: Boolean = false,
    val pinnedPeerCertSha256: String = "",
    val salamanderPassword: String? = null,
    val congestion: String = "",
    val uploadBandwidth: String = "",
    val downloadBandwidth: String = "",
    val udpHopInterval: String = "",
    val finalmaskJson: String? = null
)

data class Hysteria2SimpleFields(
    val auth: String,
    val host: String,
    val port: String,
    val portHopPorts: String,
    val serverName: String,
    val alpn: String,
    val allowInsecure: String,
    val pinnedPeerCertSha256: String,
    val salamanderPassword: String,
    val congestion: String,
    val uploadBandwidth: String,
    val downloadBandwidth: String,
    val udpHopInterval: String,
    val finalmaskJson: String
) {
    fun toProfile(): Hysteria2Profile {
        val parsedPort = port.trim().toIntOrNull()
            ?: throw IllegalArgumentException("Port must be a number")
        require(parsedPort in 1..65535) { "Port must be between 1 and 65535" }
        require(auth.trim().isNotBlank()) { "Auth is required" }
        require(host.trim().isNotBlank()) { "Server host is required" }
        val normalizedPortHopPorts = portHopPorts.trim().ifBlank { null }?.also {
            Hysteria2ConfigCodec.validatePortHopPorts(it)
        }
        val normalizedFinalmask = parseJsonObjectOrNull("FinalMask", finalmaskJson)
        return Hysteria2Profile(
            auth = auth.trim(),
            host = host.trim(),
            port = parsedPort,
            portHopPorts = normalizedPortHopPorts,
            serverName = serverName.trim().ifBlank { host.trim() },
            alpn = alpn.trim().ifBlank { "h3" },
            allowInsecure = allowInsecure.trim().equals("true", ignoreCase = true) ||
                allowInsecure.trim() == "1",
            pinnedPeerCertSha256 = pinnedPeerCertSha256.trim(),
            salamanderPassword = salamanderPassword.trim().ifBlank { null },
            congestion = congestion.trim(),
            uploadBandwidth = uploadBandwidth.trim(),
            downloadBandwidth = downloadBandwidth.trim(),
            udpHopInterval = udpHopInterval.trim(),
            finalmaskJson = normalizedFinalmask
        )
    }

    companion object {
        fun fromProfile(profile: Hysteria2Profile): Hysteria2SimpleFields {
            return Hysteria2SimpleFields(
                auth = profile.auth,
                host = profile.host,
                port = profile.port.toString(),
                portHopPorts = profile.portHopPorts.orEmpty(),
                serverName = profile.serverName,
                alpn = profile.alpn,
                allowInsecure = if (profile.allowInsecure) "true" else "false",
                pinnedPeerCertSha256 = profile.pinnedPeerCertSha256,
                salamanderPassword = profile.salamanderPassword.orEmpty(),
                congestion = profile.congestion,
                uploadBandwidth = profile.uploadBandwidth,
                downloadBandwidth = profile.downloadBandwidth,
                udpHopInterval = profile.udpHopInterval,
                finalmaskJson = profile.finalmaskJson.orEmpty()
            )
        }

        private fun parseJsonObjectOrNull(fieldLabel: String, rawValue: String): String? {
            val trimmed = rawValue.trim()
            if (trimmed.isBlank()) return null
            return try {
                JSONObject(trimmed)
                trimmed
            } catch (_: Exception) {
                throw IllegalArgumentException("$fieldLabel must be a valid JSON object")
            }
        }
    }
}

object Hysteria2ConfigCodec {
    fun isHysteria2Uri(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.startsWith("hysteria2://", ignoreCase = true) ||
            trimmed.startsWith("hy2://", ignoreCase = true)
    }

    fun parseUri(uri: String): Hysteria2Profile {
        // Strip the fragment before URI parsing: real share-link fragments carry unencoded
        // spaces/emoji that java.net.URI rejects, and the fragment is display-name-only here.
        val parsed = URI(uri.trim().substringBefore('#'))
        require(
            parsed.scheme.equals("hysteria2", ignoreCase = true) ||
                parsed.scheme.equals("hy2", ignoreCase = true)
        ) { "Unsupported URI scheme: ${parsed.scheme}" }

        val rawAuthority = parsed.rawAuthority?.trim().orEmpty()
        require(rawAuthority.isNotEmpty()) { "Missing authority in Hysteria2 link" }

        val authority = parseAuthority(rawAuthority)
        val params = parseQuery(parsed.rawQuery)
        val obfs = params["obfs"].orEmpty()
        require(obfs.isBlank() || obfs.equals("salamander", ignoreCase = true)) {
            "Unsupported Hysteria2 obfs: $obfs"
        }
        val salamanderPassword = params["obfs-password"]?.trim()?.ifBlank { null }
        require(!obfs.equals("salamander", ignoreCase = true) || salamanderPassword != null) {
            "Salamander obfs requires a non-empty obfs-password"
        }

        return Hysteria2Profile(
            auth = authority.auth,
            host = authority.host,
            port = authority.port,
            portHopPorts = authority.portHopPorts,
            serverName = params["sni"]?.trim().orEmpty().ifBlank { authority.host },
            alpn = params["alpn"]?.trim().orEmpty().ifBlank { "h3" },
            allowInsecure = params["insecure"] == "1",
            pinnedPeerCertSha256 = params["pinSHA256"].orEmpty(),
            salamanderPassword = salamanderPassword
        )
    }

    fun toXrayJson(profile: Hysteria2Profile): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })
        root.put("inbounds", JSONArray().put(tunInboundJson()))
        root.put("outbounds", JSONArray().apply {
            put(buildHysteria2Outbound(profile))
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
            })
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
            })
        })
        root.put("routing", JSONObject().apply {
            put("rules", JSONArray().put(JSONObject().apply {
                put("type", "field")
                put("ip", JSONArray().put("geoip:private"))
                put("outboundTag", "direct")
            }))
        })
        return ConfigBuilder.makeSecureDns(root.toString())
    }

    fun parseProfileFromJson(rawJson: String): Hysteria2Profile {
        val root = JSONObject(rawJson.trim())
        val outbound = findFirstHysteria2Outbound(root)
            ?: throw IllegalArgumentException("JSON config does not contain a Hysteria2 outbound")

        val settings = outbound.optJSONObject("settings")
            ?: throw IllegalArgumentException("Missing settings in Hysteria2 outbound")
        val host = settings.optString("address").trim()
        require(host.isNotBlank()) { "Missing Hysteria2 outbound address" }

        val port = settings.optInt("port", -1)
        require(port in 1..65535) { "Missing or invalid Hysteria2 outbound port" }

        val ss = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val tls = ss.optJSONObject("tlsSettings") ?: JSONObject()
        val hysteriaSettings = ss.optJSONObject("hysteriaSettings") ?: JSONObject()
        val finalmask = ss.optJSONObject("finalmask")
        val quicParams = finalmask?.optJSONObject("quicParams")
        val udpHop = quicParams?.optJSONObject("udpHop")

        return Hysteria2Profile(
            auth = hysteriaSettings.optString("auth").trim().also {
                require(it.isNotBlank()) { "Missing Hysteria2 auth" }
            },
            host = host,
            port = port,
            portHopPorts = udpHop?.optString("ports")?.trim()?.ifBlank { null },
            serverName = tls.optString("serverName").trim().ifBlank { host },
            alpn = readAlpn(tls.optJSONArray("alpn")).ifBlank { "h3" },
            allowInsecure = tls.optBoolean("allowInsecure", false),
            pinnedPeerCertSha256 = tls.optString("pinnedPeerCertSha256").trim(),
            salamanderPassword = readSalamanderPassword(finalmask),
            congestion = quicParams?.optString("congestion")?.trim().orEmpty(),
            uploadBandwidth = quicParams?.optString("brutalUp")?.trim().orEmpty(),
            downloadBandwidth = quicParams?.optString("brutalDown")?.trim().orEmpty(),
            udpHopInterval = udpHop?.opt("interval")?.toString()?.trim().orEmpty(),
            finalmaskJson = finalmask?.toString(2)
        )
    }

    fun mergeProfileIntoJson(rawJson: String, updatedProfile: Hysteria2Profile): String {
        val root = JSONObject(rawJson.trim())
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("JSON config must include outbounds")
        val index = findFirstHysteria2OutboundIndex(outbounds)
        if (index == -1) {
            throw IllegalArgumentException("JSON config does not contain a Hysteria2 outbound")
        }

        val outbound = outbounds.optJSONObject(index)
            ?: throw IllegalArgumentException("Invalid Hysteria2 outbound")
        outbound.put("protocol", "hysteria")
        outbound.put("settings", JSONObject().apply {
            put("version", 2)
            put("address", updatedProfile.host)
            put("port", updatedProfile.port)
        })
        // Patch the existing streamSettings in place so sockopt (makeSecureDns' ForceIP) and any
        // unknown stream-settings keys survive the edit, mirroring the VLESS merge.
        val existingStreamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject()
        outbound.put("streamSettings", buildStreamSettings(updatedProfile, existingStreamSettings))
        return root.toString()
    }

    fun validatePortHopPorts(value: String) {
        require(value.isNotBlank()) { "Port hop ports must not be blank" }
        value.split(",").map { it.trim() }.forEach { token ->
            require(token.isNotBlank()) { "Port hop ports must not contain empty entries" }
            if ("-" in token) {
                val start = token.substringBefore("-").trim().toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port range: $token")
                val end = token.substringAfter("-").trim().toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port range: $token")
                require(start in 1..65535) { "Port must be between 1 and 65535" }
                require(end in 1..65535) { "Port must be between 1 and 65535" }
                require(start <= end) { "Port range start must be <= end" }
            } else {
                val port = token.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port: $token")
                require(port in 1..65535) { "Port must be between 1 and 65535" }
            }
        }
    }

    private data class ParsedAuthority(
        val auth: String,
        val host: String,
        val port: Int,
        val portHopPorts: String?
    )

    private fun parseAuthority(rawAuthority: String): ParsedAuthority {
        val atIndex = rawAuthority.lastIndexOf('@')
        require(atIndex != -1) { "Missing auth in Hysteria2 link" }

        val rawAuth = rawAuthority.substring(0, atIndex)
        val hostPort = rawAuthority.substring(atIndex + 1)

        val auth = decode(rawAuth).trim()
        require(auth.isNotBlank()) { "Missing auth in Hysteria2 link" }
        require(hostPort.isNotBlank()) { "Missing host in Hysteria2 link" }

        val (host, rawPortExpression) = if (hostPort.startsWith("[")) {
            val closeIndex = hostPort.indexOf(']')
            require(closeIndex > 1) { "Missing host in Hysteria2 link" }
            val host = hostPort.substring(1, closeIndex).trim()
            val remainder = hostPort.substring(closeIndex + 1)
            when {
                remainder.isEmpty() -> host to ""
                remainder.startsWith(":") -> host to remainder.substring(1)
                else -> throw IllegalArgumentException("Invalid Hysteria2 authority")
            }
        } else {
            val colonCount = hostPort.count { it == ':' }
            require(colonCount <= 1) { "IPv6 host must be bracketed" }
            if (colonCount == 1) {
                hostPort.substringBeforeLast(":").trim() to hostPort.substringAfterLast(":")
            } else {
                hostPort.trim() to ""
            }
        }

        require(host.isNotBlank()) { "Missing host in Hysteria2 link" }
        val (port, portHopPorts) = parsePortExpression(rawPortExpression)
        return ParsedAuthority(auth = auth, host = host, port = port, portHopPorts = portHopPorts)
    }

    private fun parsePortExpression(raw: String): Pair<Int, String?> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return 443 to null

        val singlePort = trimmed.toIntOrNull()
        if (singlePort != null) {
            require(singlePort in 1..65535) { "Port must be between 1 and 65535" }
            return singlePort to null
        }

        validatePortHopPorts(trimmed)
        return firstPortFromPortExpression(trimmed) to trimmed
    }

    private fun firstPortFromPortExpression(value: String): Int {
        val firstToken = value.substringBefore(",").trim()
        return if ("-" in firstToken) {
            firstToken.substringBefore("-").trim().toInt()
        } else {
            firstToken.toInt()
        }
    }

    private fun buildHysteria2Outbound(profile: Hysteria2Profile): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", "proxy")
        outbound.put("protocol", "hysteria")
        outbound.put("settings", JSONObject().apply {
            put("version", 2)
            put("address", profile.host)
            put("port", profile.port)
        })
        outbound.put("streamSettings", buildStreamSettings(profile))
        return outbound
    }

    private fun buildStreamSettings(
        profile: Hysteria2Profile,
        base: JSONObject = JSONObject()
    ): JSONObject {
        base.put("network", "hysteria")
        base.put("security", "tls")
        base.put("tlsSettings", JSONObject().apply {
            put("serverName", profile.serverName)
            if (profile.alpn.isNotBlank()) {
                put("alpn", alpnToJsonArray(profile.alpn))
            }
            if (profile.allowInsecure) {
                put("allowInsecure", true)
            }
            if (profile.pinnedPeerCertSha256.isNotBlank()) {
                put("pinnedPeerCertSha256", profile.pinnedPeerCertSha256)
            }
        })
        base.put("hysteriaSettings", JSONObject().apply {
            put("version", 2)
            put("auth", profile.auth)
        })

        val finalmask = buildFinalmask(profile)
        if (finalmask != null) {
            base.put("finalmask", finalmask)
        } else {
            base.remove("finalmask")
        }
        return base
    }

    private fun buildFinalmask(profile: Hysteria2Profile): JSONObject? {
        val finalmask = profile.finalmaskJson?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { JSONObject(it) }
            ?: JSONObject()

        val salamanderPassword = profile.salamanderPassword?.trim().orEmpty()
        if (salamanderPassword.isNotBlank()) {
            mergeSalamanderUdpEntry(finalmask, salamanderPassword)
        }

        val quicParams = finalmask.optJSONObject("quicParams") ?: JSONObject()
        if (profile.congestion.isNotBlank()) {
            quicParams.put("congestion", profile.congestion)
        }
        if (profile.uploadBandwidth.isNotBlank()) {
            quicParams.put("brutalUp", profile.uploadBandwidth)
        }
        if (profile.downloadBandwidth.isNotBlank()) {
            quicParams.put("brutalDown", profile.downloadBandwidth)
        }
        if (profile.portHopPorts.orEmpty().isNotBlank() || profile.udpHopInterval.isNotBlank()) {
            val udpHop = quicParams.optJSONObject("udpHop") ?: JSONObject()
            profile.portHopPorts?.takeIf { it.isNotBlank() }?.let { udpHop.put("ports", it) }
            if (profile.udpHopInterval.isNotBlank()) {
                udpHop.put("interval", profile.udpHopInterval)
            }
            if (udpHop.length() > 0) {
                quicParams.put("udpHop", udpHop)
            }
        }
        if (quicParams.length() > 0) {
            finalmask.put("quicParams", quicParams)
        }

        return finalmask.takeIf { it.length() > 0 }
    }

    private fun mergeSalamanderUdpEntry(finalmask: JSONObject, salamanderPassword: String) {
        val udp = finalmask.optJSONArray("udp") ?: JSONArray()
        for (index in 0 until udp.length()) {
            val entry = udp.optJSONObject(index) ?: continue
            if (!entry.optString("type").equals("salamander", ignoreCase = true)) continue
            val settings = entry.optJSONObject("settings") ?: JSONObject().also { entry.put("settings", it) }
            settings.put("password", salamanderPassword)
            finalmask.put("udp", udp)
            return
        }

        udp.put(JSONObject().apply {
            put("type", "salamander")
            put("settings", JSONObject().put("password", salamanderPassword))
        })
        finalmask.put("udp", udp)
    }

    private fun findFirstHysteria2Outbound(root: JSONObject): JSONObject? {
        val outbounds = root.optJSONArray("outbounds") ?: return null
        val index = findFirstHysteria2OutboundIndex(outbounds)
        return if (index == -1) null else outbounds.optJSONObject(index)
    }

    private fun findFirstHysteria2OutboundIndex(outbounds: JSONArray): Int {
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (!outbound.optString("protocol").equals("hysteria", ignoreCase = true)) {
                continue
            }
            val settings = outbound.optJSONObject("settings") ?: continue
            if (settings.optInt("version", -1) != 2) continue
            val hysteriaSettings = outbound.optJSONObject("streamSettings")
                ?.optJSONObject("hysteriaSettings")
                ?: continue
            if (hysteriaSettings.optInt("version", -1) == 2) {
                return index
            }
        }
        return -1
    }

    private fun tunInboundJson(): JSONObject {
        return JSONObject().apply {
            put("tag", "tun-in")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray_tun")
                put("network", "tcp,udp")
                put("MTU", 1500)
            })
        }
    }

    private fun alpnToJsonArray(alpn: String): JSONArray {
        return JSONArray().apply {
            alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
        }
    }

    private fun readAlpn(alpn: JSONArray?): String {
        if (alpn == null) return ""
        return (0 until alpn.length()).joinToString(",") { alpn.optString(it) }
    }

    private fun readSalamanderPassword(finalmask: JSONObject?): String? {
        val udp = finalmask?.optJSONArray("udp") ?: return null
        for (index in 0 until udp.length()) {
            val entry = udp.optJSONObject(index) ?: continue
            if (!entry.optString("type").equals("salamander", ignoreCase = true)) continue
            return entry.optJSONObject("settings")
                ?.optString("password")
                ?.trim()
                ?.ifBlank { null }
        }
        return null
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .filter { it.isNotBlank() }
            .mapNotNull { pair ->
                val split = pair.split("=", limit = 2)
                if (split.isEmpty()) return@mapNotNull null
                val key = decode(split[0])
                if (key.isBlank()) return@mapNotNull null
                val value = if (split.size == 2) decode(split[1]) else ""
                key to value
            }
            .toMap()
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
