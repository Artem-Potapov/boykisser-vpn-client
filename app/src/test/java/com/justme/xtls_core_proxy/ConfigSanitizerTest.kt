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

    // --- P3-R1: fail-closed redaction (structural labels + generic failure) ---

    @Test
    fun dns_finding_hides_resolver_userinfo_query_and_fragment() {
        // A secure (https) resolver whose URL carries user-info, a query credential, and a fragment.
        // makeSecureDns keeps it (secure prefix); the finding must show only a safe scheme+host label.
        val sensitiveDns = dirty.replace(
            "\"8.8.8.8\"",
            "\"https://LEAKUSER:LEAKPWD@resolver.example/dns-query?apikey=LEAKQUERY#LEAKFRAG\"",
        )

        val report = ConfigSanitizer.analyze(sensitiveDns, log, TuningSettings.NONE)
        val detail = byId(report, FindingId.DNS_DOH)!!.detail

        assertFalse(detail.contains("LEAKUSER"))
        assertFalse(detail.contains("LEAKPWD"))
        assertFalse(detail.contains("LEAKQUERY"))
        assertFalse(detail.contains("LEAKFRAG"))
        // Safe structural representation is retained (scheme + host), not a full URL.
        assertTrue(detail.contains("resolver.example"))
    }

    @Test
    fun malformed_vless_failure_hides_non_literal_pbk_sid() {
        // Opaque pbk/sid payloads that do NOT contain the literal words publicKey/shortId and are not
        // UUID-shaped: the old narrow regex would miss them, so passing the parser exception message
        // through would leak them. A generic failure reason must not echo any input.
        val pbkValue = "AAAABBBBCCCCDDDDEEEEFFFF00"
        val sidValue = "1122334455667788"
        val malformed = "vless://someuser@example.com:443?pbk=$pbkValue&sid=$sidValue xyz"

        val report = ConfigSanitizer.analyze(malformed, log, TuningSettings.NONE)
        val reason = (report as SanitizationReport.Failure).reason

        assertFalse(reason.contains(pbkValue))
        assertFalse(reason.contains(sidValue))
        assertFalse(reason.contains("example.com"))
    }

    // --- P3-R2: forced-log finding derived from the final JSON, not hard-coded ---

    @Test
    fun forced_log_added_when_path_present_and_no_original_log() {
        val logWithPath = LogSettings(
            XrayLogLevel.WARNING,
            "/data/user/0/com.justme.xtls_core_proxy/files/logs/xray-core.log",
        )

        val report = ConfigSanitizer.analyze(dirty, logWithPath, TuningSettings.NONE)
        val finding = byId(report, FindingId.FORCED_LOG)!!

        // dirty has no `log` object; the pipeline added the forced posture → Added.
        assertEquals(Status.Added, finding.status)
        assertEquals("access: none, app-private error log", finding.detail)
        // The absolute path must never surface in the UI detail.
        assertFalse(finding.detail.contains("/data/user/0"))
    }

    @Test
    fun forced_log_applied_when_original_had_log_object() {
        val withLog = dirty.replace(
            "{\"inbounds\"",
            "{\"log\":{\"access\":\"/sdcard/Download/leak.log\",\"loglevel\":\"debug\"},\"inbounds\"",
        )
        val logWithPath = LogSettings(
            XrayLogLevel.WARNING,
            "/data/user/0/com.justme.xtls_core_proxy/files/logs/xray-core.log",
        )

        val report = ConfigSanitizer.analyze(withLog, logWithPath, TuningSettings.NONE)
        val finding = byId(report, FindingId.FORCED_LOG)!!

        // A caller/config-supplied log object is overwritten (not added) → Applied.
        assertEquals(Status.Applied, finding.status)
        assertFalse(finding.detail.contains("/sdcard/Download/leak.log"))
    }

    @Test
    fun forced_log_not_claimed_applied_when_final_error_absent() {
        // A null path yields no `error` key in the forced log object — the app-private log posture
        // cannot be confirmed and must NOT be reported as successfully applied/added.
        val report = ConfigSanitizer.analyze(dirty, LogSettings(XrayLogLevel.WARNING, null), TuningSettings.NONE)
        val finding = byId(report, FindingId.FORCED_LOG)!!

        assertTrue(finding.status is Status.NotApplicable)
    }

    // --- P3-R3: DNS compliance requires the full hostname bootstrap pair ---

    @Test
    fun hostname_proxy_without_bootstrap_pair_is_rewritten() {
        // Hostname proxy + a single secure unscoped resolver but NO scoped +local bootstrap pair.
        // The pipeline must add the pair, so this is not already-compliant.
        val hostnameNoBootstrap = dirty
            .replace("\"1.2.3.4\"", "\"proxy.example.com\"")
            .replace(
                "\"dns\":{\"servers\":[\"8.8.8.8\"]}",
                "\"dns\":{\"servers\":[\"https://1.1.1.1/dns-query\"]}",
            )

        val report = ConfigSanitizer.analyze(hostnameNoBootstrap, log, TuningSettings.NONE)

        assertEquals(Status.Rewrote, byId(report, FindingId.DNS_DOH)?.status)
    }

    @Test
    fun hostname_proxy_with_partial_bootstrap_pair_is_rewritten() {
        // Only ONE of the two required scoped +local bootstrap entries is present.
        val onlyOneBootstrap = dirty
            .replace("\"1.2.3.4\"", "\"proxy.example.com\"")
            .replace(
                "\"dns\":{\"servers\":[\"8.8.8.8\"]}",
                "\"dns\":{\"servers\":[" +
                    "{\"address\":\"https+local://1.1.1.1/dns-query\",\"domains\":[\"full:proxy.example.com\"]}," +
                    "\"https://1.1.1.1/dns-query\",\"https://1.0.0.1/dns-query\"]}",
            )

        val report = ConfigSanitizer.analyze(onlyOneBootstrap, log, TuningSettings.NONE)

        assertEquals(Status.Rewrote, byId(report, FindingId.DNS_DOH)?.status)
    }

    @Test
    fun ip_literal_proxy_with_secure_unscoped_resolver_needs_no_bootstrap() {
        // IP-literal proxy → makeSecureDns adds no bootstrap; an already-secure resolver pair complies.
        val ipProxySecureDns = dirty.replace(
            "\"dns\":{\"servers\":[\"8.8.8.8\"]}",
            "\"dns\":{\"servers\":[\"https://1.1.1.1/dns-query\",\"https://1.0.0.1/dns-query\"]}",
        )

        val report = ConfigSanitizer.analyze(ipProxySecureDns, log, TuningSettings.NONE)

        assertEquals(Status.AlreadyCompliant, byId(report, FindingId.DNS_DOH)?.status)
    }

    @Test
    fun global_resolver_override_that_changes_the_stored_servers_is_rewritten() {
        // Stored DNS already matches makeSecureDns's baseline shape (IP-literal proxy, Cloudflare
        // pair) — but a global resolver override (QUAD9) is active and applyDns swaps the servers to
        // Quad9 in the final config. DNS_DOH must report the profile's OWN outcome (its stored servers
        // don't survive the pipeline unchanged), not just baseline-shape compliance.
        val ipProxySecureDns = dirty.replace(
            "\"dns\":{\"servers\":[\"8.8.8.8\"]}",
            "\"dns\":{\"servers\":[\"https://1.1.1.1/dns-query\",\"https://1.0.0.1/dns-query\"]}",
        )
        val tuning = TuningSettings(dns = DnsSettings.FROM_CONFIG.copy(resolver = DnsResolver.QUAD9))

        val report = ConfigSanitizer.analyze(ipProxySecureDns, log, tuning)

        assertEquals(Status.Rewrote, byId(report, FindingId.DNS_DOH)?.status)
    }

    @Test
    fun config_owned_scoped_secure_resolver_is_preserved_and_compliant() {
        // A config-owned domain-scoped https resolver (NOT a pipeline https+local bootstrap) is kept
        // verbatim by makeSecureDns for an IP-literal proxy → already compliant.
        val scopedSecure = dirty.replace(
            "\"dns\":{\"servers\":[\"8.8.8.8\"]}",
            "\"dns\":{\"servers\":[{\"address\":\"https://dns.example/dns-query\"," +
                "\"domains\":[\"geosite:cn\"]}]}",
        )

        val report = ConfigSanitizer.analyze(scopedSecure, log, TuningSettings.NONE)

        assertEquals(Status.AlreadyCompliant, byId(report, FindingId.DNS_DOH)?.status)
    }

    // --- P3-R10: Added vs Applied proven from the final JSON ---

    @Test
    fun port53_dns_out_added_when_original_had_no_port53_rule() {
        // dirty has no routing rules → the pipeline adds the port 53 → dns-out rule.
        val report = ConfigSanitizer.analyze(dirty, log, TuningSettings.NONE)

        assertEquals(Status.Added, byId(report, FindingId.PORT53_DNSOUT)?.status)
    }

    @Test
    fun port53_dns_out_applied_when_original_had_port53_rule() {
        val withPort53 = dirty.replace(
            "\"outbounds\"",
            "\"routing\":{\"rules\":[{\"type\":\"field\",\"port\":53,\"outboundTag\":\"proxy\"}]},\"outbounds\"",
        )

        val report = ConfigSanitizer.analyze(withPort53, log, TuningSettings.NONE)

        assertEquals(Status.Applied, byId(report, FindingId.PORT53_DNSOUT)?.status)
    }

    @Test
    fun unsupported_blocked_only_with_user_direct_catch_all_reports_proxy_everything() {
        val withUserCatchAll = """
            {"outbounds":[
            {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,
            "users":[{"id":"u"}]}]},"streamSettings":{"network":"tcp","security":"reality"}},
            {"tag":"user-direct","protocol":"freedom"}],
            "routing":{"rules":[{"type":"field","network":"tcp,udp","outboundTag":"user-direct"}]}}
        """.trimIndent()
        val tuning = TuningSettings(
            routing = RoutingSettings(
                mode = RoutingMode.BLOCKED_ONLY,
                country = RoutingCountry.IR,
                bypassLan = false,
                blockAds = false,
            ),
        )

        val report = ConfigSanitizer.analyze(withUserCatchAll, log, tuning)

        assertEquals("Proxy everything", byId(report, FindingId.ROUTING)?.detail)
    }

    // M-G — the health-probe carve-out overrides EXCEPT_COUNTRY / imported-direct for one host and
    // those IPs; the diagnostic must surface that real exception to a user-visible setting.
    @Test
    fun health_probe_carve_out_is_reported_when_routing_overlay_applies() {
        val tuning = TuningSettings(
            routing = RoutingSettings(
                mode = RoutingMode.EXCEPT_COUNTRY,
                country = RoutingCountry.RU,
                bypassLan = true,
                blockAds = false,
            ),
        )
        val report = ConfigSanitizer.analyze(dirty, log, tuning)
        val finding = byId(report, FindingId.HEALTH_PROBE_CARVEOUT)
        assertTrue("carve-out must appear as a finding when routing applies; report=$report", finding != null)
        assertEquals(Status.Applied, finding!!.status)
        assertTrue(
            "detail must name the probe host override; detail=${finding.detail}",
            finding.detail.contains(ConfigBuilder.HEALTH_PROBE_HOST),
        )
        assertTrue(
            "detail must say the carve-out overrides country-direct / imported-direct; detail=${finding.detail}",
            finding.detail.contains("overrides", ignoreCase = true),
        )
    }

    // Residual: address change + sniffing off can silently neutralize failover — surface it when
    // the default posture does not force sniffing.
    @Test
    fun health_probe_carve_out_surfaces_address_list_residual_when_sniffing_not_forced() {
        val tuning = TuningSettings(routing = RoutingSettings.USER_DEFAULT) // PROXY_ALL, ads off
        val report = ConfigSanitizer.analyze(dirty, log, tuning)
        val finding = byId(report, FindingId.HEALTH_PROBE_CARVEOUT)!!
        assertTrue(
            "default posture must warn about the address-list residual; detail=${finding.detail}",
            finding.detail.contains("address", ignoreCase = true) &&
                finding.detail.contains("sniffing", ignoreCase = true),
        )
    }

    @Test
    fun health_probe_carve_out_omitted_when_routing_overlay_absent() {
        val report = ConfigSanitizer.analyze(dirty, log, TuningSettings.NONE)
        assertNull(
            "no routing overlay → no carve-out emission → no finding",
            byId(report, FindingId.HEALTH_PROBE_CARVEOUT),
        )
    }
}
