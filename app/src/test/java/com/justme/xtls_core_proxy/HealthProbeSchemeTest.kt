package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.state.PingTester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ONE property of the health-probe target that no other test can see: its **scheme**.
 *
 * This exists because `http://` here was a shipped, merge-blocking defect that 669 green unit tests,
 * a clean lint and a green R8 release build all passed over. `Http204HealthProbe` dials with
 * `HttpURLConnection`, which Android's `NetworkSecurityPolicy` governs; the app is `targetSdk = 36`
 * with no `usesCleartextTraffic` and no `networkSecurityConfig`, and at targetSdk >= 28 the platform
 * default is cleartext **denied**. Every probe therefore threw
 * `IOException: Cleartext HTTP traffic ... not permitted`, `runProbe` caught it and returned `false`,
 * and the watchdog read a perfectly healthy tunnel as dead — rotating through the whole pool and
 * giving up, potentially UNPROTECTED, which stops the service.
 *
 * **Why every other test missed it:** they all inject a fake `opener` into `Http204HealthProbe`, so
 * the platform check is never exercised. That injection is correct — a unit test must not do real
 * I/O — which is precisely why the scheme needs pinning *separately*, as a property of the constant
 * rather than of the request.
 *
 * **What this test does NOT prove.** It cannot prove the probe works; only a device can, and only
 * against a live tunnel. It proves the one thing that made success impossible. Do not let its
 * presence stand in for QA.
 */
class HealthProbeSchemeTest {

    @Test
    fun healthProbeTargetIsHttps_becauseCleartextIsDeniedAtTargetSdk36() {
        assertTrue(
            "HEALTH_PROBE_TARGET_URL must be https://. Reverting it to http:// makes EVERY probe " +
                "fail on a real device (cleartext denied at targetSdk >= 28 with no " +
                "usesCleartextTraffic / networkSecurityConfig), which the watchdog reads as a dead " +
                "tunnel and answers with a rotation storm and a give-up over healthy servers. " +
                "Actual: ${ConfigBuilder.HEALTH_PROBE_TARGET_URL}",
            ConfigBuilder.HEALTH_PROBE_TARGET_URL.startsWith("https://"),
        )
    }

    @Test
    fun healthProbeTargetStillNamesTheCarvedOutHost() {
        // The scheme change must not have moved the host: the routing carve-out is emitted for
        // HEALTH_PROBE_HOST specifically, so a target pointing anywhere else would be routed by the
        // imported config's own rules and could return 204 with the proxy dead. Scheme and host are
        // one decision, and this is the half that keeps the carve-out honest.
        assertEquals(
            "https://${ConfigBuilder.HEALTH_PROBE_HOST}/generate_204",
            ConfigBuilder.HEALTH_PROBE_TARGET_URL,
        )
    }

    @Test
    fun theCarveOutRulesNameNoPort_soTheySurviveTheMoveTo443() {
        // Load-bearing for the fix: http is 80, https is 443. Had either carve-out half pinned a
        // port, moving the scheme would have silently un-carved the probe and reopened the
        // false-healthy hole the ip rule was added to close.
        val config = ConfigBuilder.buildRuntimeConfig(
            VLESS_URI,
            tuning = com.justme.xtls_core_proxy.config.TuningSettings(
                routing = com.justme.xtls_core_proxy.config.RoutingSettings.USER_DEFAULT,
            ),
        )
        val rules = org.json.JSONObject(config)
            .getJSONObject("routing")
            .getJSONArray("rules")

        var domainHalf: org.json.JSONObject? = null
        var ipHalf: org.json.JSONObject? = null
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val domains = rule.optJSONArray("domain")
            if (domains != null && (0 until domains.length())
                    .any { domains.optString(it) == "full:${ConfigBuilder.HEALTH_PROBE_HOST}" }
            ) domainHalf = rule
            val ips = rule.optJSONArray("ip")
            if (ips != null && (0 until ips.length())
                    .any { ips.optString(it) == ConfigBuilder.HEALTH_PROBE_IPS.first() }
            ) ipHalf = rule
        }

        assertTrue("the domain half of the carve-out must still be emitted", domainHalf != null)
        assertTrue("the ip half of the carve-out must still be emitted", ipHalf != null)
        assertTrue(
            "the domain carve-out must not pin a port, or moving to https would un-carve it",
            !domainHalf!!.has("port"),
        )
        assertTrue(
            "the ip carve-out must not pin a port, or moving to https would un-carve it",
            !ipHalf!!.has("port"),
        )
    }

    @Test
    fun thePingTestTargetIsDeliberatelyLeftOnCleartext() {
        // NOT an oversight, and not the same decision. The Ping Test is dialled by MeasureLatency in
        // the Go bridge — raw native sockets, which NetworkSecurityPolicy does not govern — so it is
        // unaffected by the defect above. PingPreferences.isValidTarget actively REJECTS https://,
        // so "fixing" this one to match would break the ping feature outright. Pinned here so the
        // asymmetry reads as a decision rather than as a missed spot.
        assertTrue(
            "PING_TEST_TARGET is http:// by design (Go dialer, not HttpURLConnection)",
            PingTester.PING_TEST_TARGET.startsWith("http://"),
        )
    }

    private companion object {
        const val VLESS_URI =
            "vless://00000000-0000-0000-0000-000000000001@example.com:443" +
                "?encryption=none&security=tls&sni=example.com&type=tcp#probe-scheme-fixture"
    }
}
