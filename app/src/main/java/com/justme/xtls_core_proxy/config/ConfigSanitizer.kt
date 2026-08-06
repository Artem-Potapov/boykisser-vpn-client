package com.justme.xtls_core_proxy.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only diagnostic that runs a stored profile through [ConfigBuilder.buildRuntimeConfig] and
 * reports the resulting security enforcement and global overlays. It has no persistence or export
 * path, and delegates normalization and proxy selection to [ConfigBuilder].
 */
object ConfigSanitizer {
    // Defense-in-depth backstop over the primary structural allowlist (safe scheme+host labels and a
    // generic failure reason). All finding details are structural, so this can only fire on an
    // unexpected credential-bearing string. Prefer NOT exposing raw fields over widening this further.
    private val sensitiveIdentifier = Regex(
        """(?i)(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""" +
            """|publickey|shortid|password|secret|token|\bpbk\b|\bsid\b|\bauth\b)""",
    )

    private const val GENERIC_FAILURE = "Config could not be processed"

    fun analyze(
        stored: String,
        log: LogSettings,
        tuning: TuningSettings,
    ): SanitizationReport {
        // Fail-closed: never surface a parser exception message — it can echo the submitted URI/JSON
        // (user-info, pbk/sid, tokens). A generic, known-safe reason is always returned instead.
        val finalJson = runCatching { ConfigBuilder.buildRuntimeConfig(stored, log, tuning) }
            .getOrNull()
            ?: return SanitizationReport.Failure(GENERIC_FAILURE)
        val original = runCatching { JSONObject(stored) }.getOrNull() ?: JSONObject()
        val final = JSONObject(finalJson)

        val findings = securityFindings(stored, original, final, log) + globalFindings(final, tuning)
        return SanitizationReport.Success(findings.map(::redactFinding))
    }

    private fun securityFindings(
        stored: String,
        original: JSONObject,
        final: JSONObject,
        log: LogSettings,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()

        val originalProtocols = protocolsOf(original.optJSONArray("inbounds"))
        findings += if (originalProtocols == listOf("tun")) {
            Finding(
                FindingCategory.SECURITY_ENFORCEMENT,
                FindingId.INBOUNDS_TUN,
                Status.AlreadyCompliant,
                "tun",
            )
        } else {
            Finding(
                FindingCategory.SECURITY_ENFORCEMENT,
                FindingId.INBOUNDS_TUN,
                Status.Rewrote,
                "non-tun inbound → tun",
            )
        }

        val finalServers = final.optJSONObject("dns")?.optJSONArray("servers")
        // AlreadyCompliant only when the stored DNS already equals what the FULL pipeline produces for
        // THIS profile — the hostname bootstrap pair where needed, AND any global resolver override
        // (e.g. Quad9) actually in effect. Delegate that classification to ConfigBuilder rather than
        // re-deriving makeSecureDns/applyDns here.
        val dnsCompliant = ConfigBuilder.storedDnsSurvivesPipeline(stored, finalServers)
        findings += Finding(
            FindingCategory.SECURITY_ENFORCEMENT,
            FindingId.DNS_DOH,
            if (dnsCompliant) Status.AlreadyCompliant else Status.Rewrote,
            safeResolverSummary(finalServers),
        )

        findings += forcedLogFinding(original, final, log)
        importedRoutingFinding(stored)?.let { findings += it }
        findings += Finding(
            FindingCategory.SECURITY_ENFORCEMENT,
            FindingId.PORT53_DNSOUT,
            // Added when the stored config had no port-53 rule (the pipeline injected one); Applied when
            // an existing port-53 rule was enforced/redirected to dns-out.
            if (originalHasPort53Rule(original)) Status.Applied else Status.Added,
            "port 53 → dns-out",
        )
        val forceIp = firstProxySockoptStrategy(final) == "ForceIP"
        findings += Finding(
            FindingCategory.SECURITY_ENFORCEMENT,
            FindingId.FORCE_IP,
            if (forceIp) Status.Applied else Status.NotApplicable("no proxy outbound"),
            "domainStrategy=ForceIP",
        )
        return findings
    }

    /**
     * Derives the forced-log finding structurally from the FINAL `log` object (never a hard-coded
     * status). The app-private posture is confirmed only when access is forced off, the level matches
     * the requested one, and an error path is present; otherwise it is NOT claimed applied. The absolute
     * path is never echoed — only the safe `"access: none, app-private error log"` label. Added when the
     * stored config had no `log` object, Applied when an existing one was overwritten.
     */
    private fun forcedLogFinding(original: JSONObject, final: JSONObject, log: LogSettings): Finding {
        val logObj = final.optJSONObject("log")
        val access = logObj?.optString("access")
        val loglevel = logObj?.optString("loglevel")
        val error = logObj?.optString("error").orEmpty()
        val conforms = logObj != null &&
            access == "none" &&
            loglevel == log.level.wire &&
            error.isNotBlank()
        if (!conforms) {
            return Finding(
                FindingCategory.SECURITY_ENFORCEMENT,
                FindingId.FORCED_LOG,
                Status.NotApplicable("log not enforced"),
                "",
            )
        }
        val added = original.optJSONObject("log") == null
        return Finding(
            FindingCategory.SECURITY_ENFORCEMENT,
            FindingId.FORCED_LOG,
            if (added) Status.Added else Status.Applied,
            "access: none, app-private error log",
        )
    }

    /**
     * The chokepoint's two mandatory routing normalizations, reported only when they **did**
     * something to this config.
     *
     * `sanitizeProxyBalancers` and `reconcileInboundTagRules` rewrite the user's own routing on
     * every build, and a dropped `direct` rule changes where their traffic goes. The maintainer
     * ruling that the imported config's routing wins is precisely what makes a *silent* edit to it
     * unacceptable, and this screen is the app's only non-silence mechanism.
     *
     * The classification is entirely [ConfigBuilder]'s — this reads counters off
     * [ConfigBuilder.importedRoutingNormalization], which runs the production functions themselves.
     * Nothing about balancer selectors, fallback tags or inbound-tag direction is re-derived here;
     * a second copy of a forward rule is how `forceSniffingFor` came to exist.
     *
     * Null when nothing changed, so an untouched config produces no row rather than a "we did
     * nothing" row.
     */
    private fun importedRoutingFinding(stored: String): Finding? {
        val info = ConfigBuilder.importedRoutingNormalization(stored)
        if (!info.changedAnything) return null
        val parts = buildList {
            if (info.balancerSelectorsExpanded > 0) {
                add("${count(info.balancerSelectorsExpanded, "balancer selector")} expanded to exact proxy members")
            }
            if (info.fallbackTagsStripped > 0) {
                add("${count(info.fallbackTagsStripped, "helper fallbackTag")} stripped")
            }
            if (info.rulesRetargetedToTun > 0) {
                add("${count(info.rulesRetargetedToTun, "rule")} retargeted to tun-in")
            }
            if (info.rulesDropped > 0) {
                add("${count(info.rulesDropped, "direct/block rule")} dropped")
            }
        }
        return Finding(
            FindingCategory.SECURITY_ENFORCEMENT,
            FindingId.IMPORTED_ROUTING_NORMALIZED,
            Status.Rewrote,
            parts.joinToString("; "),
        )
    }

    private fun count(n: Int, noun: String): String = if (n == 1) "$n $noun" else "$n ${noun}s"

    private fun originalHasPort53Rule(original: JSONObject): Boolean {
        val rules = original.optJSONObject("routing")?.optJSONArray("rules") ?: return false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            when (val port = rule.opt("port")) {
                is Int -> if (port == 53) return true
                is String -> if (port.split(",").any { it.trim() == "53" }) return true
            }
        }
        return false
    }

    private fun globalFindings(final: JSONObject, tuning: TuningSettings): List<Finding> {
        val findings = mutableListOf<Finding>()
        val proxy = firstProxyOutbound(final)

        if (tuning.fragmentation.enabled) {
            val applied = proxy?.let(ConfigBuilder::isTcpBasedOutbound) == true
            findings += if (applied) {
                Finding(
                    FindingCategory.GLOBAL_SETTING,
                    FindingId.FRAGMENTATION,
                    Status.Applied,
                    "packets=${tuning.fragmentation.packets}, " +
                        "length=${tuning.fragmentation.length}, " +
                        "interval=${tuning.fragmentation.interval}",
                )
            } else {
                Finding(
                    FindingCategory.GLOBAL_SETTING,
                    FindingId.FRAGMENTATION,
                    Status.NotApplicable(fragmentationSkipReason(proxy)),
                    "",
                )
            }
        }

        if (tuning.mux.enabled) {
            val applied = proxy?.let(ConfigBuilder::isMuxEligible) == true
            findings += if (applied) {
                Finding(
                    FindingCategory.GLOBAL_SETTING,
                    FindingId.MUX,
                    Status.Applied,
                    "concurrency=${tuning.mux.concurrency}",
                )
            } else {
                Finding(
                    FindingCategory.GLOBAL_SETTING,
                    FindingId.MUX,
                    Status.NotApplicable(muxSkipReason(proxy)),
                    "",
                )
            }
        }

        val sniffingEnabled = ConfigBuilder.forceSniffingFor(tuning.core, tuning.routing)
        if (sniffingEnabled) {
            findings += Finding(
                FindingCategory.GLOBAL_SETTING,
                FindingId.SNIFFING,
                Status.Applied,
                if (routingNeedsDomainRules(tuning.routing)) "on (required by routing)" else "on",
            )
        }

        val mtu = final.optJSONArray("inbounds")
            ?.optJSONObject(0)
            ?.optJSONObject("settings")
            ?.optInt("MTU", ConfigBuilder.TUN_MTU)
            ?: ConfigBuilder.TUN_MTU
        findings += Finding(
            FindingCategory.GLOBAL_SETTING,
            FindingId.MTU,
            Status.Applied,
            if (mtu == ConfigBuilder.TUN_MTU) "$mtu (default)" else mtu.toString(),
        )
        findings += Finding(
            FindingCategory.GLOBAL_SETTING,
            FindingId.IPV6,
            Status.Applied,
            if (tuning.core.ipv6) "on (default)" else "off — blocked in-tunnel",
        )
        findings += Finding(
            FindingCategory.GLOBAL_SETTING,
            FindingId.DNS_RESOLVER,
            Status.Applied,
            effectiveDnsResolver(final, tuning.dns),
        )
        findings += Finding(
            FindingCategory.GLOBAL_SETTING,
            FindingId.ROUTING,
            Status.Applied,
            routingSummary(tuning.routing),
        )
        findings += Finding(
            FindingCategory.GLOBAL_SETTING,
            FindingId.DOMAIN_STRATEGY,
            Status.Applied,
            tuning.core.domainStrategy.wire ?: "From config",
        )

        // Health-probe carve-out: real exception to EXCEPT_COUNTRY / imported-direct for one host + IPs.
        // Delegate presence/target/residual to ConfigBuilder — do not re-parse carve-out shape here.
        val forceSniffing = ConfigBuilder.forceSniffingFor(tuning.core, tuning.routing)
        ConfigBuilder.healthProbeCarveOutInfo(final, forceSniffing)?.let { info ->
            val residual = if (info.addressListResidual) {
                "; residual: stale address list + sniffing off can neutralize failover"
            } else {
                ""
            }
            findings += Finding(
                FindingCategory.GLOBAL_SETTING,
                FindingId.HEALTH_PROBE_CARVEOUT,
                Status.Applied,
                "overrides country-direct/imported-direct for ${ConfigBuilder.HEALTH_PROBE_HOST} " +
                    "and pinned IPs → ${info.targetLabel}$residual",
            )
        }
        return findings
    }

    private fun routingSummary(routing: RoutingSettings?): String {
        val configured = routing ?: return "Proxy everything"
        val effectiveMode = ConfigBuilder.effectiveRoutingMode(configured)
        if (effectiveMode == RoutingMode.PROXY_ALL) {
            return buildList {
                add("Proxy everything")
                if (configured.bypassLan) add("LAN bypass on")
                if (configured.blockAds) add("ads blocked")
            }.joinToString("; ")
        }
        val mode = when (effectiveMode) {
            RoutingMode.EXCEPT_COUNTRY -> "Proxy all except ${configured.country}"
            RoutingMode.BLOCKED_ONLY -> "Proxy only blocked (${configured.country})"
            RoutingMode.PROXY_ALL -> error("Handled above")
        }
        return buildList {
            add(mode)
            if (configured.bypassLan) add("LAN bypass on")
            if (configured.blockAds) add("ads blocked")
        }.joinToString("; ")
    }

    private fun fragmentationSkipReason(proxy: JSONObject?): String {
        proxy ?: return "no proxy outbound"
        if (proxy.optString("protocol").startsWith("hysteria", ignoreCase = true)) {
            return "Hysteria2 (QUIC)"
        }
        val network = proxy.optJSONObject("streamSettings")?.optString("network")?.lowercase().orEmpty()
        return if (network == "quic" || network == "kcp") {
            "UDP-based transport ($network)"
        } else {
            "non-TCP outbound"
        }
    }

    private fun muxSkipReason(proxy: JSONObject?): String {
        proxy ?: return "no proxy outbound"
        if (!proxy.optString("protocol").equals("vless", ignoreCase = true)) {
            return "non-VLESS outbound"
        }
        val flow = proxy.optJSONObject("settings")
            ?.optJSONArray("vnext")
            ?.optJSONObject(0)
            ?.optJSONArray("users")
            ?.optJSONObject(0)
            ?.optString("flow")
            .orEmpty()
        if (flow.isNotBlank()) return "XTLS Vision flow"
        val network = proxy.optJSONObject("streamSettings")?.optString("network")?.lowercase().orEmpty()
        return if (network == "xhttp") "xhttp (uses XMUX)" else "$network transport"
    }

    private fun protocolsOf(inbounds: JSONArray?): List<String> {
        inbounds ?: return emptyList()
        return (0 until inbounds.length()).mapNotNull {
            inbounds.optJSONObject(it)?.optString("protocol")?.ifBlank { null }
        }
    }

    private fun serverAddresses(servers: JSONArray?): List<String> {
        servers ?: return emptyList()
        return (0 until servers.length()).map {
            val server = servers.opt(it)
            if (server is JSONObject) server.optString("address") else server.toString()
        }
    }

    /** Comma-joined safe structural labels for the effective resolver list — no full URLs. */
    private fun safeResolverSummary(finalServers: JSONArray?): String =
        serverAddresses(finalServers).map(::safeResolverLabel).distinct().joinToString(", ")

    /**
     * Reduces a resolver address to a safe `scheme://host` label, stripping user-info, port, path,
     * query, and fragment (all of which can carry credentials/tokens). Bracket-aware for IPv6 literals.
     * Falls back to a generic `"configured resolver"` when parsing is uncertain.
     */
    private fun safeResolverLabel(address: String): String {
        val trimmed = address.trim()
        val schemeIdx = trimmed.indexOf("://")
        if (schemeIdx <= 0) return "configured resolver"
        val scheme = trimmed.substring(0, schemeIdx).lowercase()
        var authority = trimmed.substring(schemeIdx + 3)
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
        if (authority.contains("@")) authority = authority.substringAfterLast("@")
        val host = if (authority.startsWith("[")) {
            authority.substring(1).substringBefore("]")
        } else {
            authority.substringBefore(":")
        }.trim()
        if (scheme.isBlank() || host.isBlank()) return "configured resolver"
        return "$scheme://$host"
    }

    private fun effectiveDnsResolver(final: JSONObject, settings: DnsSettings): String {
        val servers = final.optJSONObject("dns")?.optJSONArray("servers")
        val unscoped = buildList {
            servers ?: return@buildList
            for (i in 0 until servers.length()) {
                val entry = servers.opt(i)
                if (entry is JSONObject && entry.has("domains")) continue
                val address = when (entry) {
                    is String -> entry
                    is JSONObject -> entry.optString("address")
                    else -> ""
                }
                if (address.isNotBlank()) add(address)
            }
        }
        DnsResolver.entries.firstOrNull { resolver ->
            resolver.presetPair()?.let { (primary, secondary) ->
                primary in unscoped && secondary in unscoped
            } == true
        }?.let { return it.displayName() }
        if (settings.resolver == DnsResolver.CUSTOM &&
            settings.customUrl.trim() in unscoped
        ) {
            return "Custom"
        }
        return "From config"
    }

    private fun DnsResolver.displayName(): String =
        name.lowercase().replaceFirstChar { it.uppercase() }

    private fun redactFinding(finding: Finding): Finding {
        val status = when (val status = finding.status) {
            is Status.NotApplicable -> Status.NotApplicable(redactText(status.reason))
            else -> status
        }
        return finding.copy(status = status, detail = redactText(finding.detail))
    }

    private fun redactText(value: String): String =
        if (sensitiveIdentifier.containsMatchIn(value)) "redacted configuration value" else value

    private fun firstProxyOutbound(root: JSONObject): JSONObject? =
        root.optJSONArray("outbounds")?.let(ConfigBuilder::firstProxyOutbound)

    private fun firstProxySockoptStrategy(root: JSONObject): String? =
        firstProxyOutbound(root)
            ?.optJSONObject("streamSettings")
            ?.optJSONObject("sockopt")
            ?.optString("domainStrategy")
            ?.ifBlank { null }
}

sealed class SanitizationReport {
    data class Success(val findings: List<Finding>) : SanitizationReport()
    data class Failure(val reason: String) : SanitizationReport()
}

data class Finding(
    val category: FindingCategory,
    val id: FindingId,
    val status: Status,
    val detail: String,
)

enum class FindingCategory { SECURITY_ENFORCEMENT, GLOBAL_SETTING }

enum class FindingId {
    INBOUNDS_TUN,
    DNS_DOH,
    FORCED_LOG,
    PORT53_DNSOUT,
    FORCE_IP,
    FRAGMENTATION,
    MUX,
    SNIFFING,
    MTU,
    IPV6,
    DNS_RESOLVER,
    ROUTING,
    DOMAIN_STRATEGY,
    HEALTH_PROBE_CARVEOUT,
    IMPORTED_ROUTING_NORMALIZED,
}

sealed class Status {
    object Rewrote : Status()
    object Added : Status()
    object AlreadyCompliant : Status()
    object Applied : Status()
    data class NotApplicable(val reason: String) : Status()
}
