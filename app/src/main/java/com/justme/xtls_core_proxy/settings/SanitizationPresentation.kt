package com.justme.xtls_core_proxy.settings

import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.config.Finding
import com.justme.xtls_core_proxy.config.FindingCategory
import com.justme.xtls_core_proxy.config.FindingId
import com.justme.xtls_core_proxy.config.SanitizationReport
import com.justme.xtls_core_proxy.config.Status

/** Grouped findings for the two Config Sanitization section headers. */
data class SanitizationFindingGroups(
    val security: List<Finding>,
    val global: List<Finding>,
)

/** Read-only screen states derived from load flags + [SanitizationReport]. */
sealed class SanitizationUiState {
    data object Loading : SanitizationUiState()
    data object NoProfile : SanitizationUiState()
    data class Failed(val reason: String) : SanitizationUiState()
    data class Ready(val groups: SanitizationFindingGroups) : SanitizationUiState()
}

/** Maps a finding id to its localized title string resource. */
internal fun findingTitleRes(id: FindingId): Int = when (id) {
    FindingId.INBOUNDS_TUN -> R.string.san_inbounds_tun
    FindingId.DNS_DOH -> R.string.san_dns_doh
    FindingId.FORCED_LOG -> R.string.san_forced_log
    FindingId.PORT53_DNSOUT -> R.string.san_port53
    FindingId.FORCE_IP -> R.string.san_force_ip
    FindingId.FRAGMENTATION -> R.string.san_fragmentation
    FindingId.MUX -> R.string.san_mux
    FindingId.SNIFFING -> R.string.san_sniffing
    FindingId.MTU -> R.string.san_mtu
    FindingId.IPV6 -> R.string.san_ipv6
    FindingId.DNS_RESOLVER -> R.string.san_dns_resolver
    FindingId.ROUTING -> R.string.san_routing
    FindingId.DOMAIN_STRATEGY -> R.string.san_domain_strategy
    FindingId.HEALTH_PROBE_CARVEOUT -> R.string.san_health_probe_carveout
    FindingId.IMPORTED_ROUTING_NORMALIZED -> R.string.san_imported_routing
}

/** Splits a success report's findings into the two on-screen groups. */
internal fun groupSanitizationFindings(findings: List<Finding>): SanitizationFindingGroups =
    SanitizationFindingGroups(
        security = findings.filter { it.category == FindingCategory.SECURITY_ENFORCEMENT },
        global = findings.filter { it.category == FindingCategory.GLOBAL_SETTING },
    )

/** NotApplicable findings use the error-container chip color. */
internal fun statusUsesWarningContainer(status: Status): Boolean =
    status is Status.NotApplicable

/**
 * Pure: map load outcome to screen state. [loading] wins over any prior report so an ON_RESUME
 * refresh can show the spinner while prefs/profile are re-read off the main thread.
 */
internal fun resolveSanitizationUiState(
    loading: Boolean,
    noProfile: Boolean,
    report: SanitizationReport?,
): SanitizationUiState {
    if (loading) return SanitizationUiState.Loading
    if (noProfile) return SanitizationUiState.NoProfile
    return when (val r = report) {
        null -> SanitizationUiState.NoProfile
        is SanitizationReport.Failure -> SanitizationUiState.Failed(r.reason)
        is SanitizationReport.Success -> SanitizationUiState.Ready(groupSanitizationFindings(r.findings))
    }
}
