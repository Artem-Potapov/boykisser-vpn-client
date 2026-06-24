package com.justme.xtls_core_proxy.config

// i18n: audited 2026-05-21, no user-visible strings

import org.json.JSONArray
import org.json.JSONObject

object ConfigBuilder {
    fun buildRuntimeConfig(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Configuration input is empty" }

        return when {
            trimmed.startsWith("vless://", ignoreCase = true) -> fromVlessUri(trimmed)
            Hysteria2ConfigCodec.isHysteria2Uri(trimmed) -> fromHysteria2Uri(trimmed)
            else -> fromJson(trimmed)
        }
    }

    fun fromVlessUri(uri: String): String {
        val profile = ProfileConfigCodec.parseVlessUri(uri)
        return buildXrayJson(profile).toString()
    }

    fun templateJsonFromVlessProfile(profile: VlessProfile): String {
        return buildXrayJson(profile).toString()
    }

    fun fromHysteria2Uri(uri: String): String {
        val profile = Hysteria2ConfigCodec.parseUri(uri)
        return Hysteria2ConfigCodec.toXrayJson(profile)
    }

    fun templateJsonFromHysteria2Profile(profile: Hysteria2Profile): String {
        return Hysteria2ConfigCodec.toXrayJson(profile)
    }

    fun fromJson(raw: String): String {
        val sanitized = replaceJsonInboundsWithTun(raw)
        if (!JSONObject(sanitized).has("outbounds")) {
            throw IllegalArgumentException("Runtime config must include outbounds")
        }
        val secure = makeSecureDns(sanitized)
        if (dnsDiagnosis(secure) == DnsStatus.DIRTY) {
            // makeSecureDns drops every port-53->freedom rule, so this is unreachable
            // unless makeSecureDns regresses. Fail closed if it ever does.
            throw DirtyDnsException()
        }
        return secure
    }

    fun toProfileStorageConfig(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Configuration input is empty" }
        return when {
            trimmed.startsWith("vless://", ignoreCase = true) -> fromVlessUri(trimmed)
            Hysteria2ConfigCodec.isHysteria2Uri(trimmed) -> fromHysteria2Uri(trimmed)
            // Canonicalize imported JSON to the single tun inbound at storage time, matching the
            // generated vless:// / hysteria2:// paths and the runtime backstop (fromJson). Foreign
            // inbounds (socks/http/dokodemo) are inert without tun2socks and only confuse the stored
            // config. Fall back to raw for non-JSON input, which is rejected later at runtime.
            else -> runCatching { replaceJsonInboundsWithTun(trimmed) }.getOrDefault(trimmed)
        }
    }

    fun replaceJsonInboundsWithTun(config: String): String {
        val root = JSONObject(config)
        root.put("inbounds", JSONArray().put(tunInboundJson()))
        return root.toString()
    }

    enum class DnsStatus { ABSENT, SECURE, DIRTY }

    /**
     * MTU for the canonical tun inbound and the OS TUN interface. Kept below the usual 1500 path
     * MTU so inner packets still fit after outbound encapsulation — VLESS TCP/TLS and especially
     * Hysteria2 QUIC/UDP (+ Salamander) — without fragmenting under DF. Single source of truth for
     * **every** tun-in: both `tunInboundJson` builders and `VpnService.Builder.setMtu`; keep them equal.
     */
    const val TUN_MTU = 1400

    const val CLOUDFLARE_DOH = "https://1.1.1.1/dns-query"
    const val CLOUDFLARE_DOH_SECONDARY = "https://1.0.0.1/dns-query"
    // IP-literal `https+local://` form of the Cloudflare resolvers. `+local` dials the DoH endpoint
    // directly via Xray's system dialer (which the 2A protector carves out of the tun) instead of
    // dispatching it through the routing table — so it never loops back through the proxy. Used only
    // to bootstrap a hostname-addressed proxy server's own name (see makeSecureDns step 1b).
    const val CLOUDFLARE_DOH_LOCAL = "https+local://1.1.1.1/dns-query"
    const val CLOUDFLARE_DOH_LOCAL_SECONDARY = "https+local://1.0.0.1/dns-query"
    private const val DNS_OUT_TAG = "dns-out"
    private val SECURE_DNS_PREFIXES = listOf("https://", "tls://", "quic://", "h3://", "h2c://")
    private val NON_PROXY_PROTOCOLS = setOf("freedom", "blackhole", "dns")

    /**
     * Classifies a config's DNS posture.
     *
     * Returns [DnsStatus.DIRTY] only when a port-53 routing rule sends traffic to a `freedom`-protocol
     * outbound — the specific, user-warnable case where DNS queries are leaking in plaintext to the
     * network. [DnsStatus.SECURE] and [DnsStatus.ABSENT] indicate no detected leak.
     *
     * **Asymmetry with [makeSecureDns]:** [makeSecureDns] re-routes ALL port-53 rules to `dns-out`
     * regardless of their original target, so the running config is always secure even when this
     * function returns [DnsStatus.SECURE] or [DnsStatus.ABSENT]. Do NOT widen this classifier to
     * mirror that broader behavior — doing so would only expand the user-facing nag/badge without
     * improving safety, since [makeSecureDns] already guarantees a clean runtime config on every path.
     */
    fun dnsDiagnosis(config: String): DnsStatus {
        val root = JSONObject(config)
        val tagToProtocol = outboundTagProtocolMap(root)
        val rules = root.optJSONObject("routing")?.optJSONArray("rules") ?: JSONArray()

        var hasPort53Rule = false
        var leaking = false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (!ruleMatchesDnsPort(rule)) continue
            hasPort53Rule = true
            if (tagToProtocol[rule.optString("outboundTag")] == "freedom") leaking = true
        }

        val servers = root.optJSONObject("dns")?.optJSONArray("servers")
        val hasDnsBlock = servers != null && servers.length() > 0

        return when {
            leaking -> DnsStatus.DIRTY
            hasDnsBlock || hasPort53Rule -> DnsStatus.SECURE
            else -> DnsStatus.ABSENT
        }
    }

    fun makeSecureDns(config: String): String {
        val root = JSONObject(config)
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        val proxyOutbound = firstProxyOutbound(outbounds)
        // The proxy server's own address, if it's a hostname that ForceIP must resolve (step 3).
        val proxyHostname = proxyOutbound
            ?.let { proxyServerAddress(it) }
            ?.takeIf { it.isNotBlank() && !isIpLiteral(it) }

        // 1. DoH-only resolver: keep secure entries, strip plaintext, inject Cloudflare if none.
        val dns = root.optJSONObject("dns") ?: JSONObject()
        val existing = dns.optJSONArray("servers") ?: JSONArray()
        val secure = JSONArray()
        for (i in 0 until existing.length()) {
            val addr = serverAddress(existing.opt(i)) ?: continue
            if (SECURE_DNS_PREFIXES.any { addr.startsWith(it, ignoreCase = true) }) secure.put(existing.opt(i))
        }
        // No secure resolver survived: inject Cloudflare's primary + secondary anycast endpoints.
        // Both are IP-literal DoH (cert carries the IP as a SAN), so neither needs bootstrapping,
        // and Xray's serialQuery tries them in order — 1.0.0.1 is a failover if 1.1.1.1 is unreachable.
        if (secure.length() == 0) {
            secure.put(CLOUDFLARE_DOH)
            secure.put(CLOUDFLARE_DOH_SECONDARY)
        }
        // 1b. Server-name bootstrap. When the proxy is addressed by a hostname, ForceIP (step 3)
        // resolves it through this DNS module — but the unscoped DoH query above is dispatched
        // through routing, where it falls to the default outbound (the proxy itself). That is a
        // deadlock: the proxy can't connect until its hostname resolves, and the hostname can't
        // resolve until the proxy connects. Break it with a `https+local` resolver scoped to ONLY
        // that hostname (`full:`): `+local` dials the DoH endpoint directly via the system dialer
        // (2A's protector carves it out of the tun) instead of through routing, so it never re-enters
        // the proxy. Every *other* DNS query still has no matching `domains` here and falls through to
        // the unscoped resolvers above — i.e. through the proxy. IP-literal servers need no resolution,
        // so they get no bootstrap (proxyHostname is null). These prepend so they match first.
        val servers = JSONArray()
        if (proxyHostname != null) {
            servers.put(localBootstrapServer(CLOUDFLARE_DOH_LOCAL, proxyHostname))
            servers.put(localBootstrapServer(CLOUDFLARE_DOH_LOCAL_SECONDARY, proxyHostname))
        }
        for (i in 0 until secure.length()) servers.put(secure.opt(i))
        dns.put("servers", servers)
        if (!dns.has("queryStrategy")) dns.put("queryStrategy", "UseIP")
        root.put("dns", dns)

        // 2. Ensure the dns-out outbound exists.
        if (!hasOutboundTag(outbounds, DNS_OUT_TAG)) {
            outbounds.put(JSONObject().put("tag", DNS_OUT_TAG).put("protocol", "dns"))
        }

        // 3. ForceIP on the proxy outbound (first non-direct/block/dns), merged into sockopt.
        if (proxyOutbound != null) {
            val ss = proxyOutbound.optJSONObject("streamSettings")
                ?: JSONObject().also { proxyOutbound.put("streamSettings", it) }
            val sockopt = ss.optJSONObject("sockopt") ?: JSONObject().also { ss.put("sockopt", it) }
            sockopt.put("domainStrategy", "ForceIP")
        }

        // 4. port-53 -> dns-out, first; drop any pre-existing port-53 rules; preserve the rest.
        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val existingRules = routing.optJSONArray("rules") ?: JSONArray()
        val cleaned = JSONArray()
        cleaned.put(JSONObject().put("type", "field").put("port", 53).put("outboundTag", DNS_OUT_TAG))
        for (i in 0 until existingRules.length()) {
            val rule = existingRules.optJSONObject(i) ?: continue
            if (ruleMatchesDnsPort(rule)) continue
            cleaned.put(rule)
        }
        routing.put("rules", cleaned)
        root.put("routing", routing)

        return root.toString()
    }

    private fun serverAddress(entry: Any?): String? = when (entry) {
        is String -> entry
        is JSONObject -> entry.optString("address").ifBlank { null }
        else -> null
    }

    /** First outbound that isn't a `freedom`/`blackhole`/`dns` helper — the actual proxy. */
    private fun firstProxyOutbound(outbounds: JSONArray): JSONObject? {
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            if (ob.optString("protocol").lowercase() in NON_PROXY_PROTOCOLS) continue
            return ob
        }
        return null
    }

    /**
     * The proxy server's address across the outbound shapes this app produces and the common panel
     * ones: Hysteria2 (`settings.address`), VLESS/VMess (`settings.vnext[0].address`), and
     * Trojan/Shadowsocks (`settings.servers[0].address`).
     */
    private fun proxyServerAddress(outbound: JSONObject): String? {
        val settings = outbound.optJSONObject("settings") ?: return null
        settings.optString("address").ifBlank { null }?.let { return it }
        settings.optJSONArray("vnext")?.optJSONObject(0)?.optString("address")?.ifBlank { null }?.let { return it }
        settings.optJSONArray("servers")?.optJSONObject(0)?.optString("address")?.ifBlank { null }?.let { return it }
        return null
    }

    private fun localBootstrapServer(address: String, host: String): JSONObject =
        JSONObject()
            .put("address", address)
            .put("domains", JSONArray().put("full:${host.lowercase()}"))

    /** True for an IPv4/IPv6 literal (needs no DNS resolution), false for a hostname. */
    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(":")) return true // IPv6 literal (hostnames never contain ':')
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun outboundTagProtocolMap(root: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val obs = root.optJSONArray("outbounds") ?: return map
        for (i in 0 until obs.length()) {
            val ob = obs.optJSONObject(i) ?: continue
            val tag = ob.optString("tag")
            if (tag.isNotBlank()) map[tag] = ob.optString("protocol").lowercase()
        }
        return map
    }

    private fun hasOutboundTag(outbounds: JSONArray, tag: String): Boolean {
        for (i in 0 until outbounds.length()) {
            if (outbounds.optJSONObject(i)?.optString("tag") == tag) return true
        }
        return false
    }

    private fun ruleMatchesDnsPort(rule: JSONObject): Boolean {
        if (!rule.has("port")) return false
        return when (val portVal = rule.opt("port")) {
            is Int -> portVal == 53
            is String -> portStringIncludes53(portVal)
            else -> portStringIncludes53(portVal?.toString() ?: return false)
        }
    }

    private fun portStringIncludes53(s: String): Boolean = s.split(",").any { token ->
        val t = token.trim()
        when {
            t == "53" -> true
            t.contains("-") -> {
                val lo = t.substringBefore("-").trim().toIntOrNull()
                val hi = t.substringAfter("-").trim().toIntOrNull()
                lo != null && hi != null && 53 in lo..hi
            }
            else -> false
        }
    }

    private fun buildXrayJson(profile: VlessProfile): JSONObject {
        val root = JSONObject()
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })
        root.put("inbounds", JSONArray().put(tunInboundJson()))

        val outbounds = JSONArray()
        outbounds.put(buildVlessOutbound(profile))
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        })
        root.put("outbounds", outbounds)

        root.put("routing", JSONObject().apply {
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().put("geoip:private"))
                    put("outboundTag", "direct")
                })
            })
        })
        // Delegate DNS + ForceIP to makeSecureDns so the canonical shape lives in one place:
        // adds the DoH dns block, the dns-out outbound, the port-53 -> dns-out rule (first,
        // preserving the geoip:private rule), and ForceIP on the proxy (vless) outbound.
        return JSONObject(makeSecureDns(root.toString()))
    }

    private fun buildVlessOutbound(profile: VlessProfile): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", "proxy")
        outbound.put("protocol", "vless")
        outbound.put("settings", JSONObject().apply {
            put("vnext", JSONArray().put(JSONObject().apply {
                put("address", profile.host)
                put("port", profile.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", profile.uuid)
                    put("encryption", profile.encryption)
                    if (profile.flow.isNotBlank()) {
                        put("flow", profile.flow)
                    }
                }))
            }))
        })

        outbound.put("streamSettings", buildStreamSettings(profile))
        return outbound
    }

    private fun buildStreamSettings(profile: VlessProfile): JSONObject {
        val ss = JSONObject()
        ss.put("network", profile.network)
        ss.put("security", profile.security)

        when (profile.security.lowercase()) {
            "reality" -> {
                require(!profile.publicKey.isNullOrBlank()) { "Missing pbk for REALITY config" }
                ss.put("realitySettings", JSONObject().apply {
                    put("serverName", profile.serverName)
                    put("fingerprint", profile.fingerprint)
                    put("publicKey", profile.publicKey)
                    put("shortId", profile.shortId ?: "")
                    if (profile.alpn.isNotBlank()) {
                        put("alpn", alpnToJsonArray(profile.alpn))
                    }
                    if (!profile.spiderX.isNullOrBlank()) {
                        put("spiderX", profile.spiderX)
                    }
                })
            }
            "tls" -> {
                ss.put("tlsSettings", JSONObject().apply {
                    put("serverName", profile.serverName)
                    if (profile.fingerprint.isNotBlank()) {
                        put("fingerprint", profile.fingerprint)
                    }
                    if (profile.allowInsecure) {
                        put("allowInsecure", true)
                    }
                    if (profile.alpn.isNotBlank()) {
                        put("alpn", alpnToJsonArray(profile.alpn))
                    }
                })
            }
        }

        putTransportSettings(ss, profile)
        applyFinalmaskSettings(ss, profile)
        return ss
    }

    private fun putTransportSettings(ss: JSONObject, profile: VlessProfile) {
        when (profile.network.lowercase()) {
            "tcp" -> if (!profile.headerType.isNullOrBlank() && !profile.headerType.equals("none", ignoreCase = true)) {
                ss.put("tcpSettings", JSONObject().apply {
                    put("header", JSONObject().put("type", profile.headerType))
                })
            }
            "ws" -> if (!profile.transportPath.isNullOrBlank() || !profile.transportHost.isNullOrBlank()) {
                ss.put("wsSettings", JSONObject().apply {
                    if (!profile.transportPath.isNullOrBlank()) put("path", profile.transportPath)
                    if (!profile.transportHost.isNullOrBlank()) {
                        put("headers", JSONObject().put("Host", profile.transportHost))
                    }
                })
            }
            "httpupgrade" -> if (!profile.transportPath.isNullOrBlank() || !profile.transportHost.isNullOrBlank()) {
                ss.put("httpupgradeSettings", JSONObject().apply {
                    if (!profile.transportPath.isNullOrBlank()) put("path", profile.transportPath)
                    if (!profile.transportHost.isNullOrBlank()) put("host", profile.transportHost)
                })
            }
            "h2" -> if (!profile.transportPath.isNullOrBlank() || !profile.transportHost.isNullOrBlank()) {
                ss.put("httpSettings", JSONObject().apply {
                    if (!profile.transportPath.isNullOrBlank()) put("path", profile.transportPath)
                    if (!profile.transportHost.isNullOrBlank()) {
                        put("host", JSONArray().put(profile.transportHost))
                    }
                })
            }
            "xhttp" -> {
                val merged = mergeXhttpSettings(ss.optJSONObject("xhttpSettings"), profile)
                if (merged.length() == 0) {
                    ss.remove("xhttpSettings")
                } else {
                    ss.put("xhttpSettings", merged)
                }
            }
            "grpc" -> if (!profile.grpcServiceName.isNullOrBlank() || !profile.grpcAuthority.isNullOrBlank() || !profile.mode.isNullOrBlank()) {
                ss.put("grpcSettings", JSONObject().apply {
                    if (!profile.grpcServiceName.isNullOrBlank()) put("serviceName", profile.grpcServiceName)
                    if (!profile.grpcAuthority.isNullOrBlank()) put("authority", profile.grpcAuthority)
                    if (!profile.mode.isNullOrBlank()) put("mode", profile.mode)
                })
            }
            "kcp" -> if (!profile.kcpSeed.isNullOrBlank() ||
                (!profile.headerType.isNullOrBlank() && !profile.headerType.equals("none", ignoreCase = true))) {
                ss.put("kcpSettings", JSONObject().apply {
                    if (!profile.kcpSeed.isNullOrBlank()) put("seed", profile.kcpSeed)
                    if (!profile.headerType.isNullOrBlank() && !profile.headerType.equals("none", ignoreCase = true)) {
                        put("header", JSONObject().put("type", profile.headerType))
                    }
                })
            }
            "quic" -> if (!profile.quicKey.isNullOrBlank() || !profile.quicSecurity.isNullOrBlank()) {
                ss.put("quicSettings", JSONObject().apply {
                    if (!profile.quicSecurity.isNullOrBlank()) put("security", profile.quicSecurity)
                    if (!profile.quicKey.isNullOrBlank()) put("key", profile.quicKey)
                })
            }
        }
    }

    private fun mergeXhttpSettings(existingSettings: JSONObject?, profile: VlessProfile): JSONObject {
        val merged = JSONObject()
        if (existingSettings != null) {
            val keys = existingSettings.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                merged.put(key, existingSettings.opt(key))
            }
        }

        if (!profile.transportPath.isNullOrBlank()) {
            merged.put("path", profile.transportPath)
        } else {
            merged.remove("path")
        }
        if (!profile.transportHost.isNullOrBlank()) {
            merged.put("host", profile.transportHost)
        } else {
            merged.remove("host")
        }
        if (!profile.mode.isNullOrBlank()) {
            merged.put("mode", profile.mode)
        }
        if (!profile.xhttpExtraJson.isNullOrBlank()) {
            merged.put("extra", JSONObject(profile.xhttpExtraJson))
        } else {
            merged.remove("extra")
        }
        return merged
    }

    private fun applyFinalmaskSettings(ss: JSONObject, profile: VlessProfile) {
        if (profile.finalmaskJson.isNullOrBlank()) {
            ss.remove("finalmask")
            return
        }
        ss.put("finalmask", JSONObject(profile.finalmaskJson))
    }

    private fun alpnToJsonArray(alpn: String): JSONArray {
        return JSONArray().apply {
            alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) }
        }
    }

    private fun tunInboundJson(): JSONObject {
        return JSONObject().apply {
            put("tag", "tun-in")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray_tun")
                put("network", "tcp,udp")
                put("MTU", TUN_MTU)
            })
        }
    }
}

data class VlessProfile(
    val uuid: String,
    val host: String,
    val port: Int,
    val flow: String,
    val security: String,
    val publicKey: String?,
    val shortId: String?,
    val fingerprint: String,
    val serverName: String,
    val network: String,
    val alpn: String = "",
    val spiderX: String? = null,
    val allowInsecure: Boolean = false,
    val transportPath: String? = null,
    val transportHost: String? = null,
    val grpcServiceName: String? = null,
    val grpcAuthority: String? = null,
    val kcpSeed: String? = null,
    val quicKey: String? = null,
    val xhttpExtraJson: String? = null,
    val finalmaskJson: String? = null,
    val encryption: String = "none",
    val mode: String? = null,
    val headerType: String? = null,
    val quicSecurity: String? = null
)

class DirtyDnsException(
    message: String = "DNS normalization failed to produce a secure config"
) : IllegalArgumentException(message)
