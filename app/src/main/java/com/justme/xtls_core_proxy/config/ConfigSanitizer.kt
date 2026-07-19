package com.justme.xtls_core_proxy.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only diagnostic that runs a stored profile through [ConfigBuilder.buildRuntimeConfig] and
 * reports the resulting security enforcement and global overlays. It has no persistence or export
 * path, and delegates normalization and proxy selection to [ConfigBuilder].
 */
object ConfigSanitizer {
    private val sensitiveIdentifier = Regex(
        """(?i)(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|publickey|shortid)""",
    )

    fun analyze(
        stored: String,
        log: LogSettings,
        tuning: TuningSettings,
    ): SanitizationReport {
        val finalJson = runCatching { ConfigBuilder.buildRuntimeConfig(stored, log, tuning) }
            .getOrElse { return SanitizationReport.Failure(it.message ?: "Config could not be processed") }
        val original = runCatching { JSONObject(stored) }.getOrNull() ?: JSONObject()
        val final = JSONObject(finalJson)

        return SanitizationReport.Success(
            securityFindings(original, final) + globalFindings(final, tuning),
        )
    }

    private fun securityFindings(original: JSONObject, final: JSONObject): List<Finding> {
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
            val from = originalProtocols.ifEmpty { listOf("(none)") }.joinToString(", ")
            Finding(
                FindingCategory.SECURITY_ENFORCEMENT,
                FindingId.INBOUNDS_TUN,
                Status.Rewrote,
                "$from → tun",
            )
        }

        val originalServers = serverAddresses(original.optJSONObject("dns")?.optJSONArray("servers"))
        val allSecure = originalServers.isNotEmpty() &&
            originalServers.all { server ->
                ConfigBuilder.SECURE_DNS_PREFIXES.any { prefix ->
                    server.startsWith(prefix, ignoreCase = true)
                }
            }
        val finalServers = serverAddresses(final.optJSONObject("dns")?.optJSONArray("servers"))
        findings += Finding(
            FindingCategory.SECURITY_ENFORCEMENT,
            FindingId.DNS_DOH,
            if (allSecure) Status.AlreadyCompliant else Status.Rewrote,
            dnsDetail(finalServers),
        )

        findings += Finding(
            FindingCategory.SECURITY_ENFORCEMENT,
            FindingId.FORCED_LOG,
            Status.Applied,
            "access: none, app-private error log",
        )
        findings += Finding(
            FindingCategory.SECURITY_ENFORCEMENT,
            FindingId.PORT53_DNSOUT,
            Status.Applied,
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

    private fun globalFindings(final: JSONObject, tuning: TuningSettings): List<Finding> {
        val findings = mutableListOf<Finding>()
        val proxy = firstProxyOutbound(final)

        if (tuning.fragmentation.enabled) {
            val applied = proxy
                ?.optJSONObject("streamSettings")
                ?.optJSONObject("sockopt")
                ?.has("fragment") == true
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
            val applied = proxy?.has("mux") == true
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

        val sniffing = final.optJSONArray("inbounds")
            ?.optJSONObject(0)
            ?.optJSONObject("sniffing")
            ?.optBoolean("enabled") == true
        if (sniffing) {
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
            if (tuning.dns.resolver == DnsResolver.FROM_CONFIG) {
                "From config"
            } else {
                tuning.dns.resolver.name.lowercase().replaceFirstChar { it.uppercase() }
            },
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
        return findings
    }

    private fun routingSummary(routing: RoutingSettings?): String {
        if (routing == null || routing.mode == RoutingMode.PROXY_ALL) {
            return buildList {
                add("Proxy everything")
                if (routing?.bypassLan == true) add("LAN bypass on")
                if (routing?.blockAds == true) add("ads blocked")
            }.joinToString("; ")
        }
        val mode = when (routing.mode) {
            RoutingMode.EXCEPT_COUNTRY -> "Proxy all except ${routing.country}"
            RoutingMode.BLOCKED_ONLY -> "Proxy only blocked (${routing.country})"
            RoutingMode.PROXY_ALL -> error("Handled above")
        }
        return buildList {
            add(mode)
            if (routing.bypassLan) add("LAN bypass on")
            if (routing.blockAds) add("ads blocked")
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

    private fun dnsDetail(servers: List<String>): String =
        servers.joinToString(", ") { server ->
            if (sensitiveIdentifier.containsMatchIn(server)) "configured DoH resolver" else server
        }

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
}

sealed class Status {
    object Rewrote : Status()
    object Added : Status()
    object AlreadyCompliant : Status()
    object Applied : Status()
    data class NotApplicable(val reason: String) : Status()
}
