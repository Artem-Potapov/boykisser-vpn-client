package com.justme.xtls_core_proxy.config

/**
 * Global routing overlay applied by [ConfigBuilder] `applyRouting`. A 3-way exclusive mode plus
 * LAN-bypass and ad-block toggles. Country selects the geo dataset for the regional modes.
 * There is no value-based "off": [ConfigBuilder.TuningSettings.routing] is nullable, and null means
 * "apply nothing" (probes). [USER_DEFAULT] (LAN bypass ON) is what the UI/prefs default to.
 */
data class RoutingSettings(
    val mode: RoutingMode,
    val country: RoutingCountry,
    val bypassLan: Boolean,
    val blockAds: Boolean,
) {
    companion object {
        val USER_DEFAULT = RoutingSettings(RoutingMode.PROXY_ALL, RoutingCountry.RU, bypassLan = true, blockAds = false)
    }
}

enum class RoutingMode { PROXY_ALL, EXCEPT_COUNTRY, BLOCKED_ONLY }
enum class RoutingCountry(val geoipFile: String, val geositeFile: String) {
    RU("geoip_RU.dat", "geosite_RU.dat"),
    IR("geoip_IR.dat", "geosite_IR.dat"),
}

/** {rule item → outbound} lists, verified against the bundled .dat files. */
fun directTags(country: RoutingCountry): List<Pair<String, String>> = when (country) {
    RoutingCountry.RU -> listOf(
        "domain" to "geosite:category-ru",
        "ip" to "geoip:ru",
        "domain" to "ext:geosite_RU.dat:ru-available-only-inside",
    )
    RoutingCountry.IR -> listOf(
        "domain" to "geosite:category-ir",
        "ip" to "geoip:ir",
    )
}

/** Blocked-in-country lists routed → proxy in BLOCKED_ONLY mode. Empty if unsupported. */
fun blockedTags(country: RoutingCountry): List<Pair<String, String>> = when (country) {
    RoutingCountry.RU -> listOf(
        "domain" to "ext:geosite_RU.dat:ru-blocked",
        "ip" to "ext:geoip_RU.dat:ru-blocked",
        "ip" to "ext:geoip_RU.dat:ru-blocked-community",
    )
    RoutingCountry.IR -> emptyList()
}

fun blockedSupported(country: RoutingCountry): Boolean = blockedTags(country).isNotEmpty()

/** Domain-based rules only match with sniffing on; IP-only rules (LAN) don't. */
fun routingNeedsDomainRules(r: RoutingSettings?): Boolean =
    r != null && (r.mode != RoutingMode.PROXY_ALL || r.blockAds)

/** Geo .dat files a settings value needs present to build without a core-start failure. */
fun requiredGeoFiles(r: RoutingSettings): Set<String> {
    val files = mutableSetOf<String>()
    if (r.bypassLan) files += "geoip.dat"
    if (r.blockAds) files += "geosite.dat"
    when (r.mode) {
        RoutingMode.PROXY_ALL -> {}
        RoutingMode.EXCEPT_COUNTRY -> {
            files += "geosite.dat"; files += "geoip.dat"          // geosite:category-*, geoip:*
            files += r.country.geositeFile                        // ext:geosite_XX
        }
        RoutingMode.BLOCKED_ONLY -> {
            files += r.country.geositeFile; files += r.country.geoipFile
        }
    }
    return files
}

/** Downgrades to a buildable value: drops the mode/ads if their datasets are missing or (IR) absent. */
fun sanitizeForAvailability(r: RoutingSettings, availableFiles: Set<String>): RoutingSettings {
    var out = r
    if (out.blockAds && "geosite.dat" !in availableFiles) out = out.copy(blockAds = false)
    if (out.bypassLan && "geoip.dat" !in availableFiles) out = out.copy(bypassLan = false)
    val modeOk = when (out.mode) {
        RoutingMode.PROXY_ALL -> true
        RoutingMode.BLOCKED_ONLY -> blockedSupported(out.country) &&
            requiredGeoFiles(out).all { it in availableFiles }
        RoutingMode.EXCEPT_COUNTRY -> requiredGeoFiles(out).all { it in availableFiles }
    }
    if (!modeOk) out = out.copy(mode = RoutingMode.PROXY_ALL)
    return out
}
