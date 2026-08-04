package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.RoutingSettings
import com.justme.xtls_core_proxy.config.TuningSettings
import com.justme.xtls_core_proxy.state.PingTester
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Couples the health-probe target to the **manifest exemption that makes it legal**, which is the
 * one relationship no other test can see and the one whose breakage is silent and total.
 *
 * `Http204HealthProbe` dials with `HttpURLConnection`, governed by Android's
 * `NetworkSecurityPolicy`. The app is `targetSdk = 36`, where cleartext is denied by platform
 * default. A plaintext target therefore only works because
 * `res/xml/network_security_config.xml` carves out exactly [ConfigBuilder.HEALTH_PROBE_HOST]. Break
 * that pairing — change the host, drop the file, drop the manifest attribute — and **every** probe
 * throws `IOException: Cleartext HTTP traffic ... not permitted`, which the probe reports as
 * "unhealthy", so the watchdog rotates through the whole pool and gives up over healthy servers.
 * That defect shipped once already and 669 green unit tests, a clean lint and a green R8 release
 * build all passed over it, because every unit test injects a fake `opener`.
 *
 * **What this test does NOT prove.** It cannot prove the probe works — only a device can, against a
 * live tunnel. It proves the constant and the exemption still describe the same host, and that the
 * routing carve-outs still cover it. Do not let it stand in for QA.
 *
 * The manifest and `network_security_config.xml` are declared as inputs of the unit-test task in
 * `app/build.gradle.kts`, so editing either one alone invalidates `testDebugUnitTest` (without that
 * declaration the task stayed `UP-TO-DATE` and this guard silently did not run).
 */
class HealthProbeSchemeTest {

    // PARSED, not substring-matched. The first cut of this test scanned raw text and failed on its
    // own file, because the explanatory comment inside the XML mentions `<base-config>`. Matching
    // markup with `contains` is exactly the kind of check that passes or fails for the wrong reason.
    private val nsc: Element by lazy {
        // AGP runs unit tests with the module directory as the working directory; the repo-root form
        // is accepted too so the test survives being run from either place.
        val candidates = listOf(
            "src/main/res/xml/network_security_config.xml",
            "app/src/main/res/xml/network_security_config.xml",
        )
        val found = candidates.map(::File).firstOrNull { it.isFile }
        assertTrue(
            "network_security_config.xml must exist — it is what makes the cleartext health probe " +
                "legal at targetSdk 36. Looked in: $candidates (cwd=${File(".").absolutePath})",
            found != null,
        )
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(found!!).documentElement
    }

    private fun elements(tag: String): List<Element> {
        val nodes = nsc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun theCleartextExemptionNamesExactlyTheProbeHost() {
        val domains = elements("domain").map { it.textContent.trim() }
        assertTrue(
            "network_security_config.xml must carve out ${ConfigBuilder.HEALTH_PROBE_HOST}. Without " +
                "it every probe throws 'Cleartext HTTP traffic not permitted', which the watchdog " +
                "reads as a dead tunnel and answers with a rotation storm over healthy servers. " +
                "Found: $domains",
            domains.contains(ConfigBuilder.HEALTH_PROBE_HOST),
        )
        assertTrue(
            "the domain-config carving out the probe host must actually permit cleartext",
            elements("domain-config").any { it.getAttribute("cleartextTrafficPermitted") == "true" },
        )
    }

    @Test
    fun theExemptionIsScopedToOneHost_notTheWholeApp() {
        // The whole justification for a config file over android:usesCleartextTraffic="true" is that
        // it loosens plaintext for ONE destination. A base-config permitting cleartext, or a second
        // domain, would quietly restore app-wide plaintext and this test is the only guard on that.
        assertEquals(
            "exactly one <domain> entry is expected in the cleartext carve-out",
            1,
            elements("domain").size,
        )
        assertTrue(
            "a <base-config> permitting cleartext would re-open plaintext app-wide, defeating the " +
                "entire point of scoping this to one host",
            elements("base-config").none { it.getAttribute("cleartextTrafficPermitted") == "true" },
        )
    }

    @Test
    fun theManifestActuallyWiresTheConfigIn() {
        // The file is inert unless <application> references it. Dropping the attribute is a one-line
        // change with the same total, silent consequence as deleting the file.
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File).first { it.isFile }.readText()
        assertTrue(
            "AndroidManifest must set android:networkSecurityConfig, or the carve-out is inert",
            manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
        )
    }

    @Test
    fun theProbeTargetStillNamesTheCarvedOutHost() {
        // Scheme and host are one decision: the exemption is per-host and the routing carve-out is
        // per-host, so a target pointing anywhere else is both cleartext-denied AND routed by the
        // imported config's own rules.
        assertEquals(
            "http://${ConfigBuilder.HEALTH_PROBE_HOST}/generate_204",
            ConfigBuilder.HEALTH_PROBE_TARGET_URL,
        )
    }

    @Test
    fun theCarveOutRulesNameNoPort() {
        // Load-bearing if the scheme is ever revisited: http is 80, https is 443. A ported rule would
        // silently un-carve the probe and reopen the false-healthy hole the ip rule closed.
        val config = ConfigBuilder.buildRuntimeConfig(
            VLESS_URI,
            tuning = TuningSettings(routing = RoutingSettings.USER_DEFAULT),
        )
        val rules = JSONObject(config).getJSONObject("routing").getJSONArray("rules")

        var domainHalf: JSONObject? = null
        var ipHalf: JSONObject? = null
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
        assertTrue("the domain carve-out must not pin a port", !domainHalf!!.has("port"))
        assertTrue("the ip carve-out must not pin a port", !ipHalf!!.has("port"))
    }

    @Test
    fun thePingTestTargetIsCleartextForAnUnrelatedReason() {
        // NOT the same decision, and it needs no manifest entry: the Ping Test is dialled by
        // MeasureLatency in the Go bridge — raw native sockets, which NetworkSecurityPolicy does not
        // govern at all. PingPreferences.isValidTarget actively REJECTS https://, so "unifying" the
        // two schemes would break the ping feature. Pinned so the asymmetry reads as a decision.
        assertTrue(
            "PING_TEST_TARGET is http:// because the Go dialer, not HttpURLConnection, sends it",
            PingTester.PING_TEST_TARGET.startsWith("http://"),
        )
    }

    private companion object {
        const val VLESS_URI =
            "vless://00000000-0000-0000-0000-000000000001@example.com:443" +
                "?encryption=none&security=tls&sni=example.com&type=tcp#probe-scheme-fixture"
    }
}
