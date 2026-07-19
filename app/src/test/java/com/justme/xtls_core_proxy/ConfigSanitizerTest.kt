package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.ConfigSanitizer
import com.justme.xtls_core_proxy.config.DnsQueryStrategy
import com.justme.xtls_core_proxy.config.DnsResolver
import com.justme.xtls_core_proxy.config.DnsSettings
import com.justme.xtls_core_proxy.config.FindingId
import com.justme.xtls_core_proxy.config.FragmentationSettings
import com.justme.xtls_core_proxy.config.LogSettings
import com.justme.xtls_core_proxy.config.MuxSettings
import com.justme.xtls_core_proxy.config.RoutingCountry
import com.justme.xtls_core_proxy.config.RoutingMode
import com.justme.xtls_core_proxy.config.RoutingSettings
import com.justme.xtls_core_proxy.config.SanitizationReport
import com.justme.xtls_core_proxy.config.Status
import com.justme.xtls_core_proxy.config.TuningSettings
import com.justme.xtls_core_proxy.config.XrayCoreSettings
import com.justme.xtls_core_proxy.config.XrayLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSanitizerTest {
    private val log = LogSettings(XrayLogLevel.WARNING, null)

    private fun findings(report: SanitizationReport) = (report as SanitizationReport.Success).findings
    private fun byId(report: SanitizationReport, id: FindingId) = findings(report).firstOrNull { it.id == id }

    // socks inbound + plaintext DNS → both get rewritten.
    private val dirty = """
        {"inbounds":[{"protocol":"socks","port":1080}],
        "dns":{"servers":["8.8.8.8"]},
        "outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,
        "users":[{"id":"u"}]}]},"streamSettings":{"network":"tcp","security":"reality"}}]}
    """.trimIndent()

    @Test
    fun rewrites_inbounds_and_dns() {
        val report = ConfigSanitizer.analyze(dirty, log, TuningSettings.NONE)

        assertEquals(Status.Rewrote, byId(report, FindingId.INBOUNDS_TUN)?.status)
        assertEquals(Status.Rewrote, byId(report, FindingId.DNS_DOH)?.status)
    }

    @Test
    fun malformed_config_is_failure() {
        val report = ConfigSanitizer.analyze("not json at all", log, TuningSettings.NONE)

        assertTrue(report is SanitizationReport.Failure)
    }

    @Test
    fun fragmentation_on_but_quic_is_not_applicable() {
        val quic = dirty.replace("\"network\":\"tcp\"", "\"network\":\"quic\"")
        val tuning = TuningSettings(fragmentation = FragmentationSettings.DISABLED.copy(enabled = true))

        val report = ConfigSanitizer.analyze(quic, log, tuning)
        val finding = byId(report, FindingId.FRAGMENTATION)!!

        assertTrue(finding.status is Status.NotApplicable)
    }

    @Test
    fun fragmentation_off_is_omitted() {
        val report = ConfigSanitizer.analyze(dirty, log, TuningSettings.NONE)

        assertNull(byId(report, FindingId.FRAGMENTATION))
    }

    @Test
    fun always_effective_properties_shown_at_default() {
        val report = ConfigSanitizer.analyze(dirty, log, TuningSettings.NONE)

        assertTrue(byId(report, FindingId.MTU) != null)
        assertTrue(byId(report, FindingId.IPV6) != null)
        assertTrue(byId(report, FindingId.ROUTING) != null)
    }

    @Test
    fun canonical_config_reports_existing_inbounds_and_dns_as_compliant() {
        val canonical = ConfigBuilder.buildRuntimeConfig(dirty, log, TuningSettings.NONE)
        val report = ConfigSanitizer.analyze(canonical, log, TuningSettings.NONE)

        assertEquals(Status.AlreadyCompliant, byId(report, FindingId.INBOUNDS_TUN)?.status)
        assertEquals(Status.AlreadyCompliant, byId(report, FindingId.DNS_DOH)?.status)
    }

    @Test
    fun mux_on_with_vision_flow_is_not_applicable() {
        val vision = dirty.replace(
            "\"users\":[{\"id\":\"u\"}]",
            "\"users\":[{\"id\":\"u\",\"flow\":\"xtls-rprx-vision\"}]",
        )
        val tuning = TuningSettings(mux = MuxSettings.OFF.copy(enabled = true))

        val report = ConfigSanitizer.analyze(vision, log, tuning)
        val status = byId(report, FindingId.MUX)!!.status

        assertEquals(Status.NotApplicable("XTLS Vision flow"), status)
    }

    @Test
    fun effective_dns_routing_and_ipv6_values_reflect_tuning() {
        val tuning = TuningSettings(
            dns = DnsSettings(
                resolver = DnsResolver.QUAD9,
                customUrl = "",
                customPinnedIp = "",
                queryStrategy = DnsQueryStrategy.USE_IPV6,
            ),
            routing = RoutingSettings(
                mode = RoutingMode.EXCEPT_COUNTRY,
                country = RoutingCountry.RU,
                bypassLan = true,
                blockAds = true,
            ),
            core = XrayCoreSettings.DEFAULT.copy(ipv6 = false),
        )

        val report = ConfigSanitizer.analyze(dirty, log, tuning)

        assertEquals("Quad9", byId(report, FindingId.DNS_RESOLVER)?.detail)
        assertEquals(
            "Proxy all except RU; LAN bypass on; ads blocked",
            byId(report, FindingId.ROUTING)?.detail,
        )
        assertEquals("off — blocked in-tunnel", byId(report, FindingId.IPV6)?.detail)
    }

    @Test
    fun dns_finding_redacts_sensitive_identifiers() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val sensitiveDns = dirty.replace(
            "\"8.8.8.8\"",
            "\"https://resolver.example/dns-query?publicKey=abc&shortId=def&token=$uuid\"",
        )

        val report = ConfigSanitizer.analyze(sensitiveDns, log, TuningSettings.NONE)
        val detail = byId(report, FindingId.DNS_DOH)!!.detail

        assertFalse(detail.contains(uuid))
        assertFalse(detail.contains("publicKey", ignoreCase = true))
        assertFalse(detail.contains("shortId", ignoreCase = true))
    }

    @Test
    fun fragmentation_on_quic_with_stored_fragment_is_not_applicable() {
        val quicWithFragment = dirty.replace(
            "\"streamSettings\":{\"network\":\"tcp\",\"security\":\"reality\"}",
            "\"streamSettings\":{\"network\":\"quic\",\"security\":\"reality\"," +
                "\"sockopt\":{\"fragment\":{\"packets\":\"tlshello\"}}}",
        )
        val tuning = TuningSettings(fragmentation = FragmentationSettings.DISABLED.copy(enabled = true))

        val report = ConfigSanitizer.analyze(quicWithFragment, log, tuning)

        assertEquals(
            Status.NotApplicable("UDP-based transport (quic)"),
            byId(report, FindingId.FRAGMENTATION)?.status,
        )
    }

    @Test
    fun mux_on_vision_with_stored_mux_is_not_applicable() {
        val visionWithMux = dirty
            .replace(
                "\"protocol\":\"vless\"",
                "\"protocol\":\"vless\",\"mux\":{\"enabled\":true}",
            )
            .replace(
                "\"users\":[{\"id\":\"u\"}]",
                "\"users\":[{\"id\":\"u\",\"flow\":\"xtls-rprx-vision\"}]",
            )
        val tuning = TuningSettings(mux = MuxSettings.OFF.copy(enabled = true))

        val report = ConfigSanitizer.analyze(visionWithMux, log, tuning)

        assertEquals(Status.NotApplicable("XTLS Vision flow"), byId(report, FindingId.MUX)?.status)
    }

    @Test
    fun canonical_hostname_proxy_dns_with_local_bootstraps_is_already_compliant() {
        val hostnameProxy = dirty.replace("\"1.2.3.4\"", "\"proxy.example.com\"")
        val canonical = ConfigBuilder.buildRuntimeConfig(hostnameProxy, log, TuningSettings.NONE)

        val report = ConfigSanitizer.analyze(canonical, log, TuningSettings.NONE)

        assertEquals(Status.AlreadyCompliant, byId(report, FindingId.DNS_DOH)?.status)
    }

    @Test
    fun inbound_finding_redacts_untrusted_protocol_identifiers() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val identifierBearingInbound = dirty.replace(
            "\"protocol\":\"socks\"",
            "\"protocol\":\"$uuid-publicKey-secret-shortId-secret\"",
        )

        val report = ConfigSanitizer.analyze(identifierBearingInbound, log, TuningSettings.NONE)
        val output = findings(report).flatMap { finding ->
            listOf(finding.detail) + listOfNotNull((finding.status as? Status.NotApplicable)?.reason)
        }.joinToString()

        assertFalse(output.contains(uuid))
        assertFalse(output.contains("publicKey", ignoreCase = true))
        assertFalse(output.contains("shortId", ignoreCase = true))
    }

    @Test
    fun unsupported_blocked_only_country_reports_effective_proxy_all_routing() {
        val tuning = TuningSettings(
            routing = RoutingSettings(
                mode = RoutingMode.BLOCKED_ONLY,
                country = RoutingCountry.IR,
                bypassLan = true,
                blockAds = false,
            ),
        )

        val report = ConfigSanitizer.analyze(dirty, log, tuning)

        assertEquals("Proxy everything; LAN bypass on", byId(report, FindingId.ROUTING)?.detail)
    }

    @Test
    fun invalid_custom_dns_reports_final_cloudflare_resolver() {
        val tuning = TuningSettings(
            dns = DnsSettings(
                resolver = DnsResolver.CUSTOM,
                customUrl = "not-a-doh-url",
                customPinnedIp = "1.2.3.4",
                queryStrategy = DnsQueryStrategy.USE_IP,
            ),
        )

        val report = ConfigSanitizer.analyze(dirty, log, tuning)

        assertEquals("Cloudflare", byId(report, FindingId.DNS_RESOLVER)?.detail)
    }

    @Test
    fun stored_sniffing_does_not_report_when_no_global_overlay_is_active() {
        val withStoredSniffing = dirty.replace(
            "\"port\":1080}",
            "\"port\":1080,\"sniffing\":{\"enabled\":true}}",
        )

        val report = ConfigSanitizer.analyze(withStoredSniffing, log, TuningSettings.NONE)

        assertNull(byId(report, FindingId.SNIFFING))
    }

    @Test
    fun malformed_sensitive_vless_failure_reason_is_redacted() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        val malformed = "vless://$uuid@example.com:443?pbk=publicKey-secret&sid=shortId-secret bad"

        val report = ConfigSanitizer.analyze(malformed, log, TuningSettings.NONE)
        val reason = (report as SanitizationReport.Failure).reason

        assertFalse(reason.contains(uuid))
        assertFalse(reason.contains("publicKey", ignoreCase = true))
        assertFalse(reason.contains("shortId", ignoreCase = true))
    }

    @Test
    fun arbitrary_scoped_local_resolver_is_rewritten_not_canonical() {
        val arbitraryScopedLocal = dirty.replace(
            "\"dns\":{\"servers\":[\"8.8.8.8\"]}",
            "\"dns\":{\"servers\":[{\"address\":\"https+local://203.0.113.1/dns-query\"," +
                "\"domains\":[\"full:arbitrary.example\"]}]}",
        )

        val report = ConfigSanitizer.analyze(arbitraryScopedLocal, log, TuningSettings.NONE)

        assertEquals(Status.Rewrote, byId(report, FindingId.DNS_DOH)?.status)
    }
}
