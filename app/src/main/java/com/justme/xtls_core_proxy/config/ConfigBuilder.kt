package com.justme.xtls_core_proxy.config

// i18n: audited 2026-05-21, no user-visible strings

import org.json.JSONArray
import org.json.JSONObject

object ConfigBuilder {
    fun buildRuntimeConfig(
        input: String,
        log: LogSettings = LogSettings(XrayLogLevel.WARNING, null),
        tuning: TuningSettings = TuningSettings.NONE
    ): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Configuration input is empty" }

        val base = when {
            trimmed.startsWith("vless://", ignoreCase = true) -> fromVlessUri(trimmed)
            Hysteria2ConfigCodec.isHysteria2Uri(trimmed) -> fromHysteria2Uri(trimmed)
            else -> fromJson(trimmed)
        }
        val withLog = forceLog(base, log)
        val withFragmentation = applyFragmentation(withLog, tuning.fragmentation)
        val withMux = applyMux(withFragmentation, tuning.mux)
        val withDns = applyDns(withMux, tuning.dns)
        val withRouting = applyRouting(withDns, tuning.routing)
        val forceSniffing = tuning.core.sniffing || routingNeedsDomainRules(tuning.routing)
        return applyCoreSettings(withRouting, tuning.core, forceSniffing)
    }

    /** Overwrites the `log` object on a runtime config with the forced posture.
     *  Overwrite (not merge) so a pasted config cannot aim Xray's writes elsewhere. */
    private fun forceLog(configJson: String, log: LogSettings): String {
        val root = JSONObject(configJson)
        val logObj = JSONObject()
            .put("access", "none")
            .put("loglevel", log.level.wire)
        if (log.errorFilePath != null) logObj.put("error", log.errorFilePath)
        root.put("log", logObj)
        return root.toString()
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

    /**
     * Dialer-only config for a latency probe: the canonical runtime config with the tun
     * inbound removed (a probe has no VpnService fd; it dials via core.Dial) and geo-referencing
     * routing rules stripped. The probe runs in a throwaway core instance with no geo asset
     * directory, so rules that reference `geoip:` or `geosite:` fail to build (geoip.dat /
     * geosite.dat not found). Those rules govern user-traffic exceptions (LAN bypass, geo splits)
     * that are irrelevant to a single probe to a public target through the proxy; the proxy is the
     * default (first) outbound, so the probe still routes through it. The port-53 -> dns-out rule
     * and the DoH dns block have no geo dependency and are preserved — secure-DNS posture is intact.
     */
    fun toPingTestConfig(stored: String): String {
        val root = JSONObject(buildRuntimeConfig(stored, LogSettings(XrayLogLevel.NONE, null)))
        root.remove("inbounds")
        stripGeoRoutingRules(root)
        return root.toString()
    }

    /**
     * Removes routing rules referencing geo databases (geoip:/geosite:). The latency probe runs in a
     * throwaway core instance with no geo asset dir, so such rules fail to build (geoip.dat not found).
     * They govern user-traffic exceptions (LAN bypass, geo splits), irrelevant to a single probe to a
     * public target through the proxy (the default outbound). The port-53 -> dns-out rule and the DoH
     * dns block have no geo dependency and are preserved, so the probe keeps the secure-DNS posture.
     */
    private fun stripGeoRoutingRules(root: JSONObject) {
        val routing = root.optJSONObject("routing") ?: return
        val rules = routing.optJSONArray("rules") ?: return
        val kept = JSONArray()
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i)
            if (rule != null && !ruleReferencesGeo(rule)) kept.put(rule)
        }
        routing.put("rules", kept)
    }

    private fun ruleReferencesGeo(rule: JSONObject): Boolean {
        rule.optJSONArray("ip")?.let { ip ->
            for (i in 0 until ip.length()) {
                val v = ip.optString(i)
                if (v.startsWith("geoip:") || v.startsWith("ext:")) return true
            }
        }
        rule.optJSONArray("domain")?.let { domain ->
            for (i in 0 until domain.length()) {
                val v = domain.optString(i)
                if (v.startsWith("geosite:") || v.startsWith("ext:")) return true
            }
        }
        return false
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

    // Transports that ride TCP — the only ones sockopt.fragment applies to. Blank network == "tcp".
    private val TCP_NETWORKS = setOf("tcp", "ws", "grpc", "h2", "httpupgrade", "xhttp")

    /**
     * Merges TLS-ClientHello fragmentation into the proxy outbound's sockopt when enabled AND the
     * outbound is TCP-based. Merge (not overwrite): makeSecureDns already wrote domainStrategy=ForceIP
     * into the same sockopt. QUIC/kcp/Hysteria2 outbounds are skipped (fragment is a TCP-dialer knob).
     */
    private fun applyFragmentation(configJson: String, frag: FragmentationSettings): String {
        if (!frag.enabled) return configJson
        val root = JSONObject(configJson)
        val outbounds = root.optJSONArray("outbounds") ?: return configJson
        val proxy = firstProxyOutbound(outbounds) ?: return configJson
        if (!isTcpBasedOutbound(proxy)) return configJson

        val ss = proxy.optJSONObject("streamSettings")
            ?: JSONObject().also { proxy.put("streamSettings", it) }
        val sockopt = ss.optJSONObject("sockopt")
            ?: JSONObject().also { ss.put("sockopt", it) }
        sockopt.put(
            "fragment",
            JSONObject()
                .put("packets", frag.packets)
                .put("length", frag.length)
                .put("interval", frag.interval)
        )
        return root.toString()
    }

    // Transports Mux.Cool is paired with. Blank network == "tcp". xhttp is excluded (uses XMUX);
    // kcp/quic are UDP-based transports where Mux.Cool is redundant.
    private val MUX_TRANSPORTS = setOf("tcp", "ws", "grpc", "h2", "httpupgrade")

    /**
     * Merges Mux.Cool onto the proxy outbound when enabled AND the outbound is a VLESS proxy with a
     * blank flow (XTLS Vision is incompatible with mux) over a MUX_TRANSPORTS transport. Overwrites any
     * existing mux object; disabled leaves the config's own mux untouched.
     */
    private fun applyMux(configJson: String, mux: MuxSettings): String {
        if (!mux.enabled) return configJson
        val root = JSONObject(configJson)
        val outbounds = root.optJSONArray("outbounds") ?: return configJson
        val proxy = firstProxyOutbound(outbounds) ?: return configJson
        if (!isMuxEligible(proxy)) return configJson
        proxy.put(
            "mux",
            JSONObject()
                .put("enabled", true)
                .put("concurrency", mux.concurrency)
                .put("xudpConcurrency", mux.xudpConcurrency)
                .put("xudpProxyUDP443", mux.quicHandling.wire)
        )
        return root.toString()
    }

    /**
     * Overlays the global DoH resolver choice + query strategy onto the dns block makeSecureDns built.
     * FROM_CONFIG is a no-op. An explicit preset replaces the unscoped resolver pair AND rewrites the
     * `+local` proxy-hostname bootstrap pair to the chosen resolver (privacy: don't leak the proxy name
     * to Cloudflare when the user picked another resolver). Config-owned domain-scoped servers are
     * preserved verbatim. A hostname custom URL is pinned via
     * `dns.hosts`. queryStrategy is force-overwritten (a global knob must win over makeSecureDns's
     * set-if-absent). applyDns runs BEFORE applyRouting so routing's mode-3 DoH-guard derives from the
     * swapped resolver.
     */
    private fun applyDns(configJson: String, dns: DnsSettings): String {
        if (dns.resolver == DnsResolver.FROM_CONFIG) return configJson
        // Fail-closed: a corrupt/blank CUSTOM url must not re-introduce a plaintext/empty resolver
        // past makeSecureDns's plaintext strip — no-op keeps the secure Cloudflare DoH posture.
        if (dns.resolver == DnsResolver.CUSTOM && !DohUrl.isValidHttps(dns.customUrl)) return configJson
        val root = JSONObject(configJson)
        val dnsObj = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }

        // Resolve the chosen primary/secondary DoH endpoints.
        val (primary, secondary) = when (dns.resolver) {
            DnsResolver.CUSTOM -> dns.customUrl.trim() to null
            else -> dns.resolver.presetPair() ?: return configJson
        }

        // Is the effective resolver host a hostname needing a hosts pin?
        val customHost = if (dns.resolver == DnsResolver.CUSTOM) DohUrl.host(dns.customUrl) else null
        val pinnedIp = dns.customPinnedIp.trim()
        val needsPin = customHost != null && !isIpLiteral(customHost) && pinnedIp.isNotBlank()
        // The IP form used for the +local proxy bootstrap: preset secondary/primary IP, or the pinned IP.
        val bootstrapIpUrl = when {
            needsPin -> "https+local://$pinnedIp/dns-query"
            dns.resolver == DnsResolver.CUSTOM -> customHost?.takeIf { isIpLiteral(it) }?.let { "https+local://$it/dns-query" }
            else -> primary.replaceFirst("https://", "https+local://")
        }

        // Rebuild the servers array: rewrite ONLY makeSecureDns's own +local proxy-hostname bootstrap
        // entries (the only `https+local://` servers that can exist here — makeSecureDns strips any
        // pasted `https+local` entry, its secure-prefix check doesn't match it). A config-owned
        // domain-scoped DoH server is preserved verbatim: rewriting it would silently swap its
        // resolver AND pull those queries out of the tunnel via `+local`. Unscoped entries are
        // dropped and replaced by the chosen resolver pair below.
        val oldServers = dnsObj.optJSONArray("servers") ?: JSONArray()
        val newServers = JSONArray()
        for (i in 0 until oldServers.length()) {
            val entry = oldServers.opt(i) as? JSONObject ?: continue
            if (!entry.has("domains")) continue
            val isBootstrap = entry.optString("address").startsWith("https+local://", ignoreCase = true)
            if (isBootstrap && bootstrapIpUrl != null) {
                // Point the scoped bootstrap at the chosen resolver's IP form. If no IP form exists
                // (hostname custom without a pin — UI-unreachable, but prefs could be stale/corrupt),
                // the else-branch keeps the original bootstrap instead of dropping it: losing the
                // scoped entry would deadlock a hostname-addressed proxy's own name resolution.
                newServers.put(JSONObject().put("address", bootstrapIpUrl).put("domains", entry.getJSONArray("domains")))
            } else {
                newServers.put(entry)
            }
        }
        // Unscoped resolver entries: the chosen primary (+ secondary if a preset).
        newServers.put(primary)
        if (secondary != null) newServers.put(secondary)
        dnsObj.put("servers", newServers)

        // Hosts pin for a hostname custom resolver (merge, don't overwrite existing hosts).
        if (needsPin) {
            val hosts = dnsObj.optJSONObject("hosts") ?: JSONObject().also { dnsObj.put("hosts", it) }
            hosts.put(customHost, pinnedIp)
        }

        dnsObj.put("queryStrategy", dns.queryStrategy.wire)
        return root.toString()
    }

    /**
     * Global routing overlay. null → no-op (probes). Owns the geoip:private LAN rule (strips the baked
     * one, re-injects per toggle). Injected order, spliced after the forced port-53 rule:
     * DoH-guard(mode3) · LAN · ads · mode rules · config's own rules · catch-all direct(mode3, last).
     * applyCoreSettings later inserts the IPv6 ::/0-block at index 1. Ensures direct/block outbounds and
     * a proxy tag exist before referencing them.
     */
    private fun applyRouting(configJson: String, routing: RoutingSettings?): String {
        if (routing == null) return configJson
        val root = JSONObject(configJson)
        val outbounds = root.optJSONArray("outbounds") ?: return configJson
        val proxy = firstProxyOutbound(outbounds) ?: return configJson
        val proxyTag = ensureTag(proxy, "proxy")
        val directTag = ensureHelperOutbound(outbounds, "freedom", "direct")
        // Fail-closed: a BLOCKED_ONLY value whose country has no blocked dataset would emit a catch-all
        // direct with nothing routed to proxy — 100% of traffic egressing direct while the UI says
        // "connected". Degrade to PROXY_ALL here at the chokepoint so no caller can construct that config.
        val effectiveMode = if (routing.mode == RoutingMode.BLOCKED_ONLY && !blockedSupported(routing.country)) {
            RoutingMode.PROXY_ALL
        } else {
            routing.mode
        }
        val blockTag = if (routing.blockAds || effectiveMode == RoutingMode.BLOCKED_ONLY) {
            ensureHelperOutbound(outbounds, "blackhole", "block")
        } else null

        val routingObj = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val existing = routingObj.optJSONArray("rules") ?: JSONArray()

        // Partition existing rules: the forced port-53 rule (kept first), the owned LAN rule (dropped),
        // and everything else (preserved after the injected block).
        var port53: JSONObject? = null
        val passthrough = JSONArray()
        for (i in 0 until existing.length()) {
            val rule = existing.optJSONObject(i) ?: continue
            when {
                ruleMatchesDnsPort(rule) && port53 == null -> port53 = rule
                isOwnedLanRule(rule, directTag) -> {} // drop; re-injected per toggle
                else -> passthrough.put(rule)
            }
        }

        val out = JSONArray()
        if (port53 != null) out.put(port53)
        if (effectiveMode == RoutingMode.BLOCKED_ONLY) dohGuardRules(root, proxyTag).forEach { out.put(it) }
        if (routing.bypassLan) out.put(fieldRule("ip", listOf("geoip:private"), directTag))
        if (routing.blockAds && blockTag != null) out.put(fieldRule("domain", listOf("geosite:category-ads-all"), blockTag))
        when (effectiveMode) {
            RoutingMode.PROXY_ALL -> {}
            RoutingMode.EXCEPT_COUNTRY -> directTags(routing.country).forEach { (k, v) -> out.put(fieldRule(k, listOf(v), directTag)) }
            RoutingMode.BLOCKED_ONLY -> blockedTags(routing.country).forEach { (k, v) -> out.put(fieldRule(k, listOf(v), proxyTag)) }
        }
        for (i in 0 until passthrough.length()) out.put(passthrough.get(i))
        if (effectiveMode == RoutingMode.BLOCKED_ONLY) {
            out.put(JSONObject().put("type", "field").put("network", "tcp,udp").put("outboundTag", directTag))
        }
        routingObj.put("rules", out)
        return root.toString()
    }

    /**
     * Last overlay. Each sub-action is guarded so DEFAULT + no forced sniffing is a byte-identical
     * no-op (probes stay clean). MTU rewrites the tun inbound; IPv6-off injects ::/0->block at index 1
     * (after the forced port-53 rule) and force-overwrites queryStrategy=UseIPv4 (last-writer over
     * applyDns); sniffing writes the single OR-ed block; domainStrategy overwrites routing.domainStrategy
     * for explicit values only.
     */
    private fun applyCoreSettings(configJson: String, core: XrayCoreSettings, forceSniffing: Boolean): String {
        val root = JSONObject(configJson)

        if (core.mtu != TUN_MTU) {
            root.optJSONArray("inbounds")?.optJSONObject(0)?.optJSONObject("settings")?.put("MTU", core.mtu)
        }

        if (!core.ipv6) {
            val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
            dns.put("queryStrategy", "UseIPv4")
            val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
            val rules = routing.optJSONArray("rules") ?: JSONArray().also { routing.put("rules", it) }
            // Ensure a blackhole exists, then insert ::/0 -> block at index 1 (after port-53).
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
            val blockTag = ensureHelperOutbound(outbounds, "blackhole", "block")
            val v6Block = fieldRule("ip", listOf("::/0"), blockTag)
            val rebuilt = JSONArray()
            if (rules.length() > 0) rebuilt.put(rules.get(0)) // port-53 stays first
            rebuilt.put(v6Block)
            for (i in 1 until rules.length()) rebuilt.put(rules.get(i))
            routing.put("rules", rebuilt)
        }

        if (forceSniffing) {
            root.optJSONArray("inbounds")?.optJSONObject(0)?.put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                    .put("routeOnly", true)
            )
        }

        core.domainStrategy.wire?.let { strategy ->
            val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
            routing.put("domainStrategy", strategy)
        }

        return root.toString()
    }

    private fun fieldRule(key: String, items: List<String>, tag: String): JSONObject =
        JSONObject().put("type", "field").put(key, JSONArray(items)).put("outboundTag", tag)

    /** True for the exact baked-in LAN rule shape (single geoip:private ip → the direct outbound). */
    private fun isOwnedLanRule(rule: JSONObject, directTag: String): Boolean {
        if (rule.optString("outboundTag") != directTag) return false
        val ip = rule.optJSONArray("ip") ?: return false
        return ip.length() == 1 && ip.optString(0) == "geoip:private"
    }

    /** DoH-guard: route the dns block's own resolver endpoints to the proxy (mode-3 direct default). */
    private fun dohGuardRules(root: JSONObject, proxyTag: String): List<JSONObject> {
        val dnsObj = root.optJSONObject("dns") ?: return emptyList()
        val servers = dnsObj.optJSONArray("servers") ?: return emptyList()
        val ips = mutableListOf<String>()
        val domains = mutableListOf<String>()
        for (i in 0 until servers.length()) {
            val entry = servers.opt(i)
            if (entry is JSONObject && entry.has("domains")) continue // +local bootstrap, already carved out
            val addr = if (entry is JSONObject) entry.optString("address") else entry?.toString() ?: continue
            val host = dohResolverHost(addr)
            if (host.isBlank()) continue
            if (isIpLiteral(host)) ips.add(host) else domains.add("full:$host")
        }
        // A hostname resolver pinned via dns.hosts is dialled at its pinned IP; without an ip-side rule the
        // DoH connection falls through the BLOCKED_ONLY catch-all to direct. Guard both forms.
        dnsObj.optJSONObject("hosts")?.let { hosts ->
            for (key in hosts.keys()) {
                when (val v = hosts.opt(key)) {
                    is JSONArray -> for (j in 0 until v.length()) {
                        v.optString(j).takeIf { it.isNotBlank() && isIpLiteral(it) }?.let { ips.add(it) }
                    }
                    is String -> if (v.isNotBlank() && isIpLiteral(v)) ips.add(v)
                }
            }
        }
        val rules = mutableListOf<JSONObject>()
        if (ips.isNotEmpty()) rules.add(fieldRule("ip", ips.distinct(), proxyTag))
        if (domains.isNotEmpty()) rules.add(fieldRule("domain", domains.distinct(), proxyTag))
        return rules
    }

    /** Host of a DoH server address, bracket-aware for IPv6 literals (…//[2606::1]:443/… → 2606::1). */
    private fun dohResolverHost(addr: String): String {
        val hostPort = addr.substringAfter("://").substringBefore("/").trim()
        return if (hostPort.startsWith("[")) {
            hostPort.substring(1).substringBefore("]")   // bracketed IPv6: drop [ ] and any :port after ]
        } else {
            hostPort.substringBefore(":")                // hostname / IPv4: strip :port
        }.trim()
    }

    /** Returns the outbound's tag, assigning [fallback] if it has none. */
    private fun ensureTag(outbound: JSONObject, fallback: String): String {
        val tag = outbound.optString("tag")
        if (tag.isNotBlank()) return tag
        outbound.put("tag", fallback)
        return fallback
    }

    /** Returns the tag of a usable existing outbound with [protocol], else appends one. */
    private fun ensureHelperOutbound(outbounds: JSONArray, protocol: String, preferredTag: String): String {
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            if (!ob.optString("protocol").equals(protocol, ignoreCase = true)) continue
            // Don't adopt a redirecting freedom (e.g. a WARP-chain outbound): pointing "direct" traffic at
            // its local redirect port would black-hole every direct rule. Append a clean one instead.
            if (ob.optJSONObject("settings")?.has("redirect") == true) continue
            return ob.optString("tag").ifBlank { ensureTag(ob, preferredTag) }
        }
        val tag = uniqueOutboundTag(outbounds, preferredTag)
        outbounds.put(JSONObject().put("tag", tag).put("protocol", protocol))
        return tag
    }

    /** [preferred] if free, else the first "preferred-N" not already taken by another outbound. */
    private fun uniqueOutboundTag(outbounds: JSONArray, preferred: String): String {
        val taken = mutableSetOf<String>()
        for (i in 0 until outbounds.length()) {
            outbounds.optJSONObject(i)?.optString("tag")?.takeIf { it.isNotBlank() }?.let { taken.add(it) }
        }
        if (preferred !in taken) return preferred
        var n = 2
        while ("$preferred-$n" in taken) n++
        return "$preferred-$n"
    }

    private fun isMuxEligible(outbound: JSONObject): Boolean {
        if (!outbound.optString("protocol").equals("vless", ignoreCase = true)) return false
        if (vlessFlow(outbound).isNotBlank()) return false
        val network = outbound.optJSONObject("streamSettings")?.optString("network")?.lowercase().orEmpty()
        val net = if (network.isBlank()) "tcp" else network
        return net in MUX_TRANSPORTS
    }

    /** The VLESS user's flow, at settings.vnext[0].users[0].flow; blank if absent. */
    private fun vlessFlow(outbound: JSONObject): String =
        outbound.optJSONObject("settings")
            ?.optJSONArray("vnext")?.optJSONObject(0)
            ?.optJSONArray("users")?.optJSONObject(0)
            ?.optString("flow").orEmpty()

    private fun isTcpBasedOutbound(outbound: JSONObject): Boolean {
        if (outbound.optString("protocol").lowercase().startsWith("hysteria")) return false
        val ss = outbound.optJSONObject("streamSettings")
        val network = ss?.optString("network")?.lowercase().orEmpty()
        val net = if (network.isBlank()) "tcp" else network
        if (net !in TCP_NETWORKS) return false
        // XHTTP is the one TCP_NETWORKS member that can also ride HTTP/3 (QUIC/UDP), where sockopt.fragment
        // (a TCP-dialer knob) is inert. QUIC mandates TLS 1.3, and h3 is never the ALPN default, so xhttp is
        // HTTP/3 only when security==tls AND ALPN opts into "h3". Every other xhttp (none / reality /
        // tls-without-h3) is TCP.
        if (net == "xhttp" && isHttp3(ss)) return false
        return true
    }

    /** True only for an HTTP/3 (QUIC) transport: security==tls AND tlsSettings.alpn contains "h3". */
    private fun isHttp3(streamSettings: JSONObject?): Boolean {
        val ss = streamSettings ?: return false
        if (!ss.optString("security").equals("tls", ignoreCase = true)) return false
        val alpn = ss.optJSONObject("tlsSettings")?.optJSONArray("alpn") ?: return false
        for (i in 0 until alpn.length()) {
            if (alpn.optString(i).equals("h3", ignoreCase = true)) return true
        }
        return false
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
