package com.justme.xtls_core_proxy.settings

import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.config.Finding
import com.justme.xtls_core_proxy.config.FindingCategory
import com.justme.xtls_core_proxy.config.FindingId
import com.justme.xtls_core_proxy.config.SanitizationReport
import com.justme.xtls_core_proxy.config.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the Config Sanitization screen's presentation helpers —
 * title resource mapping, finding grouping, and load→UI state resolution —
 * extracted out of [ConfigSanitizationActivity] so Compose stays thin.
 */
class SanitizationPresentationTest {

    @Test
    fun findingTitleRes_mapsEveryFindingId() {
        assertEquals(R.string.san_inbounds_tun, findingTitleRes(FindingId.INBOUNDS_TUN))
        assertEquals(R.string.san_dns_doh, findingTitleRes(FindingId.DNS_DOH))
        assertEquals(R.string.san_forced_log, findingTitleRes(FindingId.FORCED_LOG))
        assertEquals(R.string.san_port53, findingTitleRes(FindingId.PORT53_DNSOUT))
        assertEquals(R.string.san_force_ip, findingTitleRes(FindingId.FORCE_IP))
        assertEquals(R.string.san_fragmentation, findingTitleRes(FindingId.FRAGMENTATION))
        assertEquals(R.string.san_mux, findingTitleRes(FindingId.MUX))
        assertEquals(R.string.san_sniffing, findingTitleRes(FindingId.SNIFFING))
        assertEquals(R.string.san_mtu, findingTitleRes(FindingId.MTU))
        assertEquals(R.string.san_ipv6, findingTitleRes(FindingId.IPV6))
        assertEquals(R.string.san_dns_resolver, findingTitleRes(FindingId.DNS_RESOLVER))
        assertEquals(R.string.san_routing, findingTitleRes(FindingId.ROUTING))
        assertEquals(R.string.san_domain_strategy, findingTitleRes(FindingId.DOMAIN_STRATEGY))
        assertEquals(R.string.san_health_probe_carveout, findingTitleRes(FindingId.HEALTH_PROBE_CARVEOUT))
    }

    @Test
    fun groupSanitizationFindings_splitsSecurityAndGlobal() {
        val security = Finding(
            FindingCategory.SECURITY_ENFORCEMENT,
            FindingId.INBOUNDS_TUN,
            Status.Rewrote,
            "non-tun inbound → tun",
        )
        val global = Finding(
            FindingCategory.GLOBAL_SETTING,
            FindingId.MTU,
            Status.Applied,
            "1500",
        )
        val groups = groupSanitizationFindings(listOf(security, global))
        assertEquals(listOf(security), groups.security)
        assertEquals(listOf(global), groups.global)
    }

    @Test
    fun statusUsesWarningContainer_onlyForNotApplicable() {
        assertFalse(statusUsesWarningContainer(Status.Rewrote))
        assertFalse(statusUsesWarningContainer(Status.Added))
        assertFalse(statusUsesWarningContainer(Status.AlreadyCompliant))
        assertFalse(statusUsesWarningContainer(Status.Applied))
        assertTrue(statusUsesWarningContainer(Status.NotApplicable("Hysteria2 (QUIC)")))
    }

    @Test
    fun resolveSanitizationUiState_loadingWins() {
        assertEquals(
            SanitizationUiState.Loading,
            resolveSanitizationUiState(
                loading = true,
                noProfile = true,
                report = SanitizationReport.Failure("ignored"),
            ),
        )
    }

    @Test
    fun resolveSanitizationUiState_noProfileAndFailureAndSuccess() {
        assertEquals(
            SanitizationUiState.NoProfile,
            resolveSanitizationUiState(loading = false, noProfile = true, report = null),
        )
        assertEquals(
            SanitizationUiState.Failed("bad json"),
            resolveSanitizationUiState(
                loading = false,
                noProfile = false,
                report = SanitizationReport.Failure("bad json"),
            ),
        )
        val finding = Finding(
            FindingCategory.SECURITY_ENFORCEMENT,
            FindingId.DNS_DOH,
            Status.AlreadyCompliant,
            "DoH",
        )
        val ready = resolveSanitizationUiState(
            loading = false,
            noProfile = false,
            report = SanitizationReport.Success(listOf(finding)),
        ) as SanitizationUiState.Ready
        assertEquals(listOf(finding), ready.groups.security)
        assertTrue(ready.groups.global.isEmpty())
    }
}
