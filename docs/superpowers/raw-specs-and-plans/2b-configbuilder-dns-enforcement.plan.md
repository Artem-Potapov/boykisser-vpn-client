# Implementation Plan — 2B ConfigBuilder DNS-Leak Enforcement

**Goal:** Guarantee a secure DNS posture (Cloudflare DoH + port-53 routed into Xray's DNS module, never direct) for every config the app runs, with a warn-and-fix flow for pasted configs and silent auto-fix-plus-flag for subscriptions.
**Tech Stack:** Kotlin, `org.json` (Android + `org.json:json:20260522` for unit tests), Room 2.x, Jetpack Compose, JUnit4, mockito-kotlin 6.3.0. No product flavors — unit test task is `:app:testDebugUnitTest`.
**Prerequisites:** The repo builds (`./gradlew :app:assembleDebug`), and `./gradlew :app:testDebugUnitTest` passes before starting.

## Coordination with 2A (do these hand-in-hand)

2B makes the proxy server's hostname resolve over Cloudflare DoH; 2A changes how Xray's own sockets (including that DoH query) bypass the tunnel (whole-app exclusion → `VpnService.protect()`). The DoH `dns` block added here is what keeps the server name from leaking once 2A routes the whole app through the tun. Build 2A aware that this block exists and must stay reachable on the bypass path; do not have 2A remove self-exclusion without preserving DoH reachability for Xray's own DNS dialer.

## File Structure

- `app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt` — **Modify**: add `replaceJsonInboundsWithTun` (inbound sanitization, Task 1) and delete `rejectLocalProxyInbounds`; add `DnsStatus`, `dnsDiagnosis`, `replaceJsonDnsWithSecureDns`, helpers, `secureDnsBlock`, `CLOUDFLARE_DOH`; embed secure DNS in `buildXrayJson`; rewire `fromJson` (sanitize inbounds, then gate DNS). Add `DirtyDnsException` at file top level (next to `VlessProfile`).
- `app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderTest.kt` — **Modify**: flip the two inbound-rejection tests to sanitization and add `replaceJsonInboundsWithTun` coverage (Task 1).
- `app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderDnsTest.kt` — **Create**: unit tests for the DNS API.
- `app/src/main/java/com/justme/xtls_core_proxy/db/Profile.kt` — **Modify**: add `sanitizedDns: Boolean = false`.
- `app/src/main/java/com/justme/xtls_core_proxy/db/AppDatabase.kt` — **Modify**: version 3 + `MIGRATION_2_3`, registered on the builder.
- `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParser.kt` — **Modify**: `ParsedConfig.sanitizedDns`; per-entry diagnose → fix → flag.
- `app/src/test/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParserTest.kt` — **Modify**: add dirty-entry tests.
- `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionRefreshCoordinator.kt` — **Modify**: propagate `sanitizedDns` into `Profile`.
- `app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt` — **Modify**: `dnsWarning` state + confirm/dismiss; gate `addProfile`.
- `app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt` — **Modify**: observe `dnsWarning` → dialog; per-profile badge.
- `app/src/main/res/values/strings.xml` and `app/src/main/res/values-ru/strings.xml` — **Modify**: dialog + badge strings.

---

### Task 1: JSON inbound sanitization (separate concern, bundled here)

Conceptually independent of the DNS work — this is about *importing real-world configs* — but it edits the same `fromJson`, so it ships here and lands first. Panel configs (3x-ui and every other source) ship local `socks`/`http`/`mixed` inbounds for tun2socks/SOCKS clients and no `tun` inbound. Today `fromJson` *rejects* any `socks`/`http` inbound — and inconsistently lets `mixed`/`dokodemo-door` through to be silently replaced. We swap that guard for one pure method that strips whatever inbounds a config ships and forces our canonical tun inbound. (A stray local listener can't leak under the TUN, but it's useless, and a config with no tun inbound can't carry the `VpnService` fd → no connection.)

**Files:**
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt`
- Modify: `app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderTest.kt`

**Step 1: Flip the rejection tests and add coverage**

In `ConfigBuilderTest.kt`, delete the two tests `fromJson_rejectsSocksInbound` and `fromJson_rejectsHttpInbound` (both `@Test(expected = IllegalArgumentException::class)`) and add these in their place:

```kotlin
    @Test
    fun fromJson_sanitizesSocksInbound() {
        val input = """
            {
              "inbounds": [{"protocol": "socks", "tag": "loopback"}],
              "outbounds": [{"protocol": "freedom", "tag": "direct"}]
            }
        """.trimIndent()

        val config = JSONObject(ConfigBuilder.fromJson(input))
        val inbounds = config.getJSONArray("inbounds")

        assertEquals(1, inbounds.length())
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))
    }

    @Test
    fun fromJson_sanitizesHttpInbound() {
        val input = """
            {
              "inbounds": [{"protocol": "http", "tag": "loopback"}],
              "outbounds": [{"protocol": "freedom", "tag": "direct"}]
            }
        """.trimIndent()

        val config = JSONObject(ConfigBuilder.fromJson(input))
        val inbounds = config.getJSONArray("inbounds")

        assertEquals(1, inbounds.length())
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))
    }

    @Test
    fun replaceJsonInboundsWithTun_dropsLocalProxiesKeepsOutbounds() {
        // The shape a 3x-ui panel emits: mixed + http local inbounds, real proxy outbound.
        val input = """
            {
              "inbounds": [
                {"protocol": "mixed", "port": 10808, "tag": "mixed"},
                {"protocol": "http", "port": 10809, "tag": "http"}
              ],
              "outbounds": [
                {"protocol": "vless", "tag": "proxy"},
                {"protocol": "freedom", "tag": "direct"}
              ]
            }
        """.trimIndent()

        val config = JSONObject(ConfigBuilder.replaceJsonInboundsWithTun(input))
        val inbounds = config.getJSONArray("inbounds")

        assertEquals(1, inbounds.length())
        assertEquals("tun", inbounds.getJSONObject(0).getString("protocol"))
        assertEquals(
            "vless",
            config.getJSONArray("outbounds").getJSONObject(0).getString("protocol")
        )
    }

    @Test
    fun replaceJsonInboundsWithTun_replacesAnExistingForeignTun() {
        // Even a config that already declares a tun inbound gets ours imposed, so its
        // name/MTU match what the VpnService actually sets up.
        val input = """
            {
              "inbounds": [{"protocol": "tun", "settings": {"name": "foreign_tun", "MTU": 9000}}],
              "outbounds": [{"protocol": "freedom", "tag": "direct"}]
            }
        """.trimIndent()

        val settings = JSONObject(ConfigBuilder.replaceJsonInboundsWithTun(input))
            .getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")

        assertEquals("xray_tun", settings.getString("name"))
        assertEquals(1500, settings.getInt("MTU"))
    }
```

**Step 2: Run the tests to confirm they fail**

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.ConfigBuilderTest"`
Expected: FAIL — `unresolved reference: replaceJsonInboundsWithTun`, and the two `fromJson_sanitizes*` tests fail because `fromJson` still throws on `socks`/`http`.

**Step 3: Implement**

In `config/ConfigBuilder.kt`, add the pure method (public, alongside the other pure methods):

```kotlin
    fun replaceJsonInboundsWithTun(config: String): String {
        val root = JSONObject(config)
        root.put("inbounds", JSONArray().put(tunInboundJson()))
        return root.toString()
    }
```

Delete the entire `rejectLocalProxyInbounds` private function.

Rewrite `fromJson` to use the new method (no DNS gate yet — Task 2 adds that):

```kotlin
    fun fromJson(raw: String): String {
        val sanitized = replaceJsonInboundsWithTun(raw)
        if (!JSONObject(sanitized).has("outbounds")) {
            throw IllegalArgumentException("Runtime config must include outbounds")
        }
        return sanitized
    }
```

**Step 4: Run the tests to confirm they pass**

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.ConfigBuilderTest"`
Expected: PASS — all existing tests (including `fromJson_injectsTunInbound`, which still replaces a `dokodemo-door` inbound), the two flipped tests, and the two `replaceJsonInboundsWithTun` tests.

**Step 5: Commit**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt \
        app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderTest.kt
git commit -m "feat(config): sanitize foreign inbounds into the tun inbound instead of rejecting socks/http"
```

---

### Task 2: ConfigBuilder DNS API (the crux)

**Files:**
- Create: `app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderDnsTest.kt`
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt`

**Step 1: Write the failing tests**

Create `app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderDnsTest.kt`:

```kotlin
package com.justme.xtls_core_proxy

import com.justme.xtls_core_proxy.config.ConfigBuilder
import com.justme.xtls_core_proxy.config.DirtyDnsException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderDnsTest {

    private val dirty = """
        {"outbounds":[{"protocol":"vless","tag":"proxy"},{"protocol":"freedom","tag":"direct"}],
         "routing":{"rules":[{"type":"field","port":53,"outboundTag":"direct"}]}}
    """.trimIndent()

    private val absent = """
        {"outbounds":[{"protocol":"vless","tag":"proxy"},{"protocol":"freedom","tag":"direct"}],
         "routing":{"rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"direct"}]}}
    """.trimIndent()

    private val secureRouted = """
        {"dns":{"servers":["https://1.1.1.1/dns-query"]},
         "outbounds":[{"protocol":"vless","tag":"proxy"},{"protocol":"dns","tag":"dns-out"}],
         "routing":{"rules":[{"type":"field","port":53,"outboundTag":"dns-out"}]}}
    """.trimIndent()

    @Test
    fun dnsDiagnosis_dirty_whenPort53RoutedToFreedom() {
        assertEquals(ConfigBuilder.DnsStatus.DIRTY, ConfigBuilder.dnsDiagnosis(dirty))
    }

    @Test
    fun dnsDiagnosis_absent_whenNoDnsConfig() {
        assertEquals(ConfigBuilder.DnsStatus.ABSENT, ConfigBuilder.dnsDiagnosis(absent))
    }

    @Test
    fun dnsDiagnosis_secure_whenPort53RoutedToDnsOut() {
        assertEquals(ConfigBuilder.DnsStatus.SECURE, ConfigBuilder.dnsDiagnosis(secureRouted))
    }

    @Test
    fun replaceJsonDnsWithSecureDns_makesDirtyConfigSecure() {
        val fixed = ConfigBuilder.replaceJsonDnsWithSecureDns(dirty)
        assertEquals(ConfigBuilder.DnsStatus.SECURE, ConfigBuilder.dnsDiagnosis(fixed))
        val root = JSONObject(fixed)
        assertEquals(
            ConfigBuilder.CLOUDFLARE_DOH,
            root.getJSONObject("dns").getJSONArray("servers").getString(0)
        )
        val firstRule = root.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("dns-out", firstRule.getString("outboundTag"))
        assertEquals(53, firstRule.getInt("port"))
    }

    @Test
    fun replaceJsonDnsWithSecureDns_isIdempotent() {
        val twice = ConfigBuilder.replaceJsonDnsWithSecureDns(ConfigBuilder.replaceJsonDnsWithSecureDns(dirty))
        assertEquals(ConfigBuilder.DnsStatus.SECURE, ConfigBuilder.dnsDiagnosis(twice))
        val obs = JSONObject(twice).getJSONArray("outbounds")
        var dnsOut = 0
        for (i in 0 until obs.length()) {
            if (obs.getJSONObject(i).optString("tag") == "dns-out") dnsOut++
        }
        assertEquals(1, dnsOut)
    }

    @Test
    fun buildRuntimeConfig_vless_isSecure() {
        val cfg = ConfigBuilder.buildRuntimeConfig(
            "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none"
        )
        assertEquals(ConfigBuilder.DnsStatus.SECURE, ConfigBuilder.dnsDiagnosis(cfg))
    }

    @Test
    fun buildRuntimeConfig_absentJson_getsSecured() {
        val cfg = ConfigBuilder.buildRuntimeConfig(absent)
        assertEquals(ConfigBuilder.DnsStatus.SECURE, ConfigBuilder.dnsDiagnosis(cfg))
    }

    @Test(expected = DirtyDnsException::class)
    fun buildRuntimeConfig_dirtyJson_throws() {
        ConfigBuilder.buildRuntimeConfig(dirty)
    }

    @Test
    fun port53StringRange_isDetectedAsDirty() {
        val cfg = """
            {"outbounds":[{"protocol":"freedom","tag":"direct"}],
             "routing":{"rules":[{"type":"field","port":"53-54","outboundTag":"direct"}]}}
        """.trimIndent()
        assertEquals(ConfigBuilder.DnsStatus.DIRTY, ConfigBuilder.dnsDiagnosis(cfg))
    }
}
```

**Step 2: Run the test to confirm it fails**

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.ConfigBuilderDnsTest"`
Expected: FAIL — compilation error, `unresolved reference: DnsStatus` / `dnsDiagnosis` / `DirtyDnsException` / `CLOUDFLARE_DOH`.

**Step 3: Write the implementation**

In `config/ConfigBuilder.kt`, add `DirtyDnsException` at the **top level** of the file (after the `VlessProfile` data class, same as it lives in the file):

```kotlin
class DirtyDnsException(
    message: String = "Config routes DNS (port 53) to a direct outbound and would leak queries"
) : IllegalArgumentException(message)
```

Inside the `ConfigBuilder` object, add the public DNS API and helpers:

```kotlin
    enum class DnsStatus { ABSENT, SECURE, DIRTY }

    const val CLOUDFLARE_DOH = "https://1.1.1.1/dns-query"
    private const val DNS_OUT_TAG = "dns-out"
    private val SECURE_DNS_PREFIXES = listOf("https://", "tls://", "quic://", "h3://", "h2c://")

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

    fun replaceJsonDnsWithSecureDns(config: String): String {
        val root = JSONObject(config)

        val dns = root.optJSONObject("dns") ?: JSONObject()
        val servers = dns.optJSONArray("servers") ?: JSONArray()
        if (!serversContainSecure(servers)) {
            val merged = JSONArray().put(CLOUDFLARE_DOH)
            for (i in 0 until servers.length()) merged.put(servers.opt(i))
            dns.put("servers", merged)
        }
        if (!dns.has("queryStrategy")) dns.put("queryStrategy", "UseIP")
        root.put("dns", dns)

        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        if (!hasOutboundTag(outbounds, DNS_OUT_TAG)) {
            outbounds.put(JSONObject().put("tag", DNS_OUT_TAG).put("protocol", "dns"))
        }

        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val existing = routing.optJSONArray("rules") ?: JSONArray()
        val cleaned = JSONArray()
        cleaned.put(
            JSONObject().put("type", "field").put("port", 53).put("outboundTag", DNS_OUT_TAG)
        )
        for (i in 0 until existing.length()) {
            val rule = existing.optJSONObject(i) ?: continue
            if (ruleMatchesDnsPort(rule)) continue
            cleaned.put(rule)
        }
        routing.put("rules", cleaned)
        root.put("routing", routing)

        return root.toString()
    }

    private fun secureDnsBlock(): JSONObject =
        JSONObject()
            .put("servers", JSONArray().put(CLOUDFLARE_DOH))
            .put("queryStrategy", "UseIP")

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

    private fun serversContainSecure(servers: JSONArray?): Boolean {
        if (servers == null) return false
        for (i in 0 until servers.length()) {
            val addr = when (val entry = servers.opt(i)) {
                is String -> entry
                is JSONObject -> entry.optString("address")
                else -> null
            } ?: continue
            if (SECURE_DNS_PREFIXES.any { addr.startsWith(it, ignoreCase = true) }) return true
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

    private fun portStringIncludes53(s: String): Boolean {
        return s.split(",").any { token ->
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
    }
```

Replace `buildXrayJson` so the generated config is secure by construction. Change the start (add the `dns` block) and the `outbounds`/`routing` sections:

```kotlin
    private fun buildXrayJson(profile: VlessProfile): JSONObject {
        val root = JSONObject()
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })
        root.put("dns", secureDnsBlock())
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
        outbounds.put(JSONObject().apply {
            put("tag", DNS_OUT_TAG)
            put("protocol", "dns")
        })
        root.put("outbounds", outbounds)

        root.put("routing", JSONObject().apply {
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("port", 53)
                    put("outboundTag", DNS_OUT_TAG)
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().put("geoip:private"))
                    put("outboundTag", "direct")
                })
            })
        })
        return root
    }
```

Update `fromJson` (from Task 1, which now strips inbounds via `replaceJsonInboundsWithTun`) to add the DNS gate:

```kotlin
    fun fromJson(raw: String): String {
        val sanitized = replaceJsonInboundsWithTun(raw)
        if (!JSONObject(sanitized).has("outbounds")) {
            throw IllegalArgumentException("Runtime config must include outbounds")
        }
        return when (dnsDiagnosis(sanitized)) {
            DnsStatus.DIRTY -> throw DirtyDnsException()
            DnsStatus.ABSENT -> replaceJsonDnsWithSecureDns(sanitized)
            DnsStatus.SECURE -> sanitized
        }
    }
```

**Step 4: Run the test to confirm it passes**

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.ConfigBuilderDnsTest"`
Expected: PASS (9 tests).

Also run the existing ConfigBuilder tests to confirm no regression:

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.ConfigBuilderTest"`
Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt \
        app/src/test/java/com/justme/xtls_core_proxy/ConfigBuilderDnsTest.kt
git commit -m "feat(dns): secure-DNS diagnosis, sanitize, and runtime gate in ConfigBuilder"
```

---

### Task 3: Profile flag + Room migration (infra)

No failing-test-first: this is a schema/infra change; Room validates the schema at compile time and migration correctness is an instrumented concern (justified TDD exception).

**Files:**
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/db/Profile.kt`
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/db/AppDatabase.kt`

**Step 1: Add the column**

In `db/Profile.kt`, add the field to the `Profile` data class:

```kotlin
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val config: String,
    val subscriptionId: Long? = null,
    val sanitizedDns: Boolean = false
)
```

**Step 2: Bump version and add the migration**

In `db/AppDatabase.kt`, add imports:

```kotlin
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
```

Change the `@Database` version from `2` to `3`:

```kotlin
@Database(
    entities = [Profile::class, Subscription::class],
    version = 3,
    exportSchema = false
)
```

Add the migration inside the `companion object` and register it on the builder (keep the existing destructive-from-1 fallback):

```kotlin
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE profiles ADD COLUMN sanitizedDns INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
```

In `get(context)`, add `.addMigrations(MIGRATION_2_3)` to the builder chain (immediately after `.fallbackToDestructiveMigrationFrom(false, 1)`):

```kotlin
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xraytun.db"
                )
                    .fallbackToDestructiveMigrationFrom(false, 1)
                    .addMigrations(MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
```

**Step 3: Verify it compiles (Room schema check)**

Command: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Room fails the build if the entity and version are inconsistent.)

**Step 4: Manual upgrade smoke test (record, don't automate here)**

Install the prior version, add a profile, install this build over it, open the app. Expected: no crash, the existing profile is still present (additive migration preserves data).

**Step 5: Commit**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/db/Profile.kt \
        app/src/main/java/com/justme/xtls_core_proxy/db/AppDatabase.kt
git commit -m "feat(db): add Profile.sanitizedDns column with migration 2->3"
```

---

### Task 4: Subscription auto-fix + flag (mechanism 2)

**Files:**
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParser.kt`
- Modify: `app/src/test/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParserTest.kt`
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionRefreshCoordinator.kt`

**Step 1: Write the failing tests**

Add to `subs/SubscriptionBodyParserTest.kt` (add the imports if missing: `com.justme.xtls_core_proxy.config.ConfigBuilder`, `org.junit.Assert.assertFalse`, `org.junit.Assert.assertTrue`):

```kotlin
    @Test
    fun parseBody_flagsAndFixesDirtyDnsJsonEntry() {
        val dirtyJson = """{"outbounds":[{"protocol":"vless","tag":"proxy"},""" +
            """{"protocol":"freedom","tag":"direct"}],""" +
            """"routing":{"rules":[{"type":"field","port":53,"outboundTag":"direct"}]}}"""

        val outcome = SubscriptionBodyParser.parseBody(dirtyJson)

        assertEquals(1, outcome.parsed.size)
        val entry = outcome.parsed.first()
        assertTrue(entry.sanitizedDns)
        assertEquals(ConfigBuilder.DnsStatus.SECURE, ConfigBuilder.dnsDiagnosis(entry.config))
    }

    @Test
    fun parseBody_doesNotFlagCleanUri() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@demo.example:443?security=none"

        val outcome = SubscriptionBodyParser.parseBody(uri)

        assertEquals(1, outcome.parsed.size)
        assertFalse(outcome.parsed.first().sanitizedDns)
    }
```

**Step 2: Run the test to confirm it fails**

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.subs.SubscriptionBodyParserTest"`
Expected: FAIL — `ParsedConfig` has no `sanitizedDns` property (compilation error).

**Step 3: Implement**

In `subs/SubscriptionBodyParser.kt`, add the flag to `ParsedConfig`:

```kotlin
data class ParsedConfig(
    val displayName: String,
    val config: String,
    val sanitizedDns: Boolean = false
)
```

Replace the body of the `for ((index, raw) in lines.withIndex())` loop in `parseBody` with diagnose → fix → flag:

```kotlin
        for ((index, raw) in lines.withIndex()) {
            val candidate = unwrapPerLineBase64(raw) ?: raw

            val storage = runCatching { ConfigBuilder.toProfileStorageConfig(candidate) }.getOrNull()
            if (storage == null) {
                errors++
                continue
            }

            val dirty = runCatching { ConfigBuilder.dnsDiagnosis(storage) }
                .getOrNull() == ConfigBuilder.DnsStatus.DIRTY
            val finalConfig = if (dirty) ConfigBuilder.replaceJsonDnsWithSecureDns(storage) else storage

            val accepted = runCatching { ConfigBuilder.buildRuntimeConfig(finalConfig) }.isSuccess
            if (!accepted) {
                errors++
                continue
            }

            val baseName = deriveDisplayName(candidate, index)
            val unique = uniquify(baseName, nameCounts)
            parsed += ParsedConfig(displayName = unique, config = finalConfig, sanitizedDns = dirty)
        }
```

In `subs/SubscriptionRefreshCoordinator.kt`, propagate the flag when mapping parsed entries to `Profile` (the `newProfiles` map around line 59):

```kotlin
                val newProfiles = outcome.parsed.map { p ->
                    Profile(
                        name = p.displayName,
                        config = p.config,
                        subscriptionId = subId,
                        sanitizedDns = p.sanitizedDns
                    )
                }
```

**Step 4: Run the test to confirm it passes**

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.subs.SubscriptionBodyParserTest"`
Expected: PASS (existing tests plus the 2 new ones).

**Step 5: Commit**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParser.kt \
        app/src/test/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParserTest.kt \
        app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionRefreshCoordinator.kt
git commit -m "feat(subs): auto-fix dirty DNS in subscription configs and flag them"
```

---

### Task 5: Pasted warn-and-fix dialog + badge (UI)

The decision logic is already covered by `ConfigBuilderDnsTest`; this task is thin glue, verified by build + manual run.

**Files:**
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt`
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`

**Step 1: Add the warning state and actions to the ViewModel**

In `state/VpnViewModel.kt`, add imports if missing (`kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.flow.StateFlow`, `kotlinx.coroutines.flow.asStateFlow`, `com.justme.xtls_core_proxy.config.ConfigBuilder`, `com.justme.xtls_core_proxy.db.Profile`). Add the state and a small data holder to the class:

```kotlin
    data class DnsWarning(val name: String, val rawConfig: String)

    private val _dnsWarning = MutableStateFlow<DnsWarning?>(null)
    val dnsWarning: StateFlow<DnsWarning?> = _dnsWarning.asStateFlow()
```

Replace the existing `addProfile` so it gates on diagnosis:

```kotlin
    fun addProfile(name: String, config: String) {
        viewModelScope.launch {
            val storage = runCatching { ConfigBuilder.toProfileStorageConfig(config) }.getOrNull()
            if (storage != null &&
                runCatching { ConfigBuilder.dnsDiagnosis(storage) }.getOrNull() ==
                ConfigBuilder.DnsStatus.DIRTY
            ) {
                _dnsWarning.value = DnsWarning(name = name, rawConfig = config)
                return@launch
            }
            dao.insert(Profile(name = name, config = ConfigBuilder.toProfileStorageConfig(config)))
        }
    }

    fun confirmDnsFixAndAdd() {
        val warning = _dnsWarning.value ?: return
        viewModelScope.launch {
            val secured = ConfigBuilder.replaceJsonDnsWithSecureDns(
                ConfigBuilder.toProfileStorageConfig(warning.rawConfig)
            )
            dao.insert(Profile(name = warning.name, config = secured, sanitizedDns = true))
            _dnsWarning.value = null
        }
    }

    fun dismissDnsWarning() {
        _dnsWarning.value = null
    }
```

> Note: the existing three `viewModel.addProfile(...)` call sites in `MainActivity.kt` (the manual add and the two clipboard `kind` paths) need no change — the gate is inside `addProfile`. If `dao` is not the field name in this ViewModel, use whatever the existing `addProfile` already uses (it currently calls `dao.insert(...)`).

**Step 2: Add the dialog and badge in MainActivity**

In `MainActivity.kt`, collect the warning state near the top-level Compose content (where other VM state is collected) and render the dialog:

```kotlin
val dnsWarning by viewModel.dnsWarning.collectAsState()
val context = LocalContext.current
if (dnsWarning != null) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissDnsWarning() },
        title = { Text(stringResource(R.string.dns_warn_title)) },
        text = { Text(stringResource(R.string.dns_warn_message)) },
        confirmButton = {
            TextButton(onClick = { viewModel.confirmDnsFixAndAdd() }) {
                Text(stringResource(R.string.dns_warn_fix))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.dismissDnsWarning()
                Toast.makeText(
                    context,
                    R.string.dns_warn_declined_toast,
                    Toast.LENGTH_LONG
                ).show()
            }) {
                Text(stringResource(R.string.dns_warn_decline))
            }
        }
    )
}
```

Add the per-profile badge composable (place the definition near the other private composables in `MainActivity.kt`):

```kotlin
@Composable
private fun DnsCorrectedBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = stringResource(R.string.profile_badge_dns_corrected_cd),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.profile_badge_dns_corrected),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
```

In the composable that renders a single profile row (the item inside the profiles list), after the profile name, add:

```kotlin
if (profile.sanitizedDns) {
    DnsCorrectedBadge()
}
```

Ensure the imports used above are present (`androidx.compose.material3.AlertDialog`, `TextButton`, `Text`, `Icon`, `MaterialTheme`; `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.filled.Warning`; `androidx.compose.ui.res.stringResource`; `androidx.compose.ui.platform.LocalContext`; `android.widget.Toast`; `androidx.compose.foundation.layout.Row`/`Spacer`; `androidx.compose.ui.unit.dp`; `androidx.compose.runtime.collectAsState`).

**Step 3: Add strings (en)**

In `app/src/main/res/values/strings.xml`:

```xml
<string name="dns_warn_title">Unsafe DNS in this config</string>
<string name="dns_warn_message">This config sends DNS straight out, which can reveal which sites you visit. Want to fix it? If you\'re not sure what this means, decline — the server won\'t be added.</string>
<string name="dns_warn_fix">Fix it</string>
<string name="dns_warn_decline">Don\'t add</string>
<string name="dns_warn_declined_toast">Not added — this config would leak DNS</string>
<string name="profile_badge_dns_corrected">DNS fixed</string>
<string name="profile_badge_dns_corrected_cd">This subscription delivered unsafe DNS, which was corrected automatically</string>
```

**Step 4: Add strings (ru)**

In `app/src/main/res/values-ru/strings.xml` (review by a native speaker; you are the right person to confirm tone):

```xml
<string name="dns_warn_title">Небезопасный DNS в этой конфигурации</string>
<string name="dns_warn_message">Эта конфигурация отправляет DNS напрямую — это может раскрыть, какие сайты вы посещаете. Исправить? Если вы не уверены, что это значит, откажитесь — сервер не будет добавлен.</string>
<string name="dns_warn_fix">Исправить</string>
<string name="dns_warn_decline">Не добавлять</string>
<string name="dns_warn_declined_toast">Не добавлено — эта конфигурация раскрыла бы DNS</string>
<string name="profile_badge_dns_corrected">DNS исправлен</string>
<string name="profile_badge_dns_corrected_cd">Эта подписка передала небезопасный DNS, он был исправлен автоматически</string>
```

**Step 5: Verify build + manual checks**

Command: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Manual:
1. Paste a JSON config whose routing sends `port 53` to a `freedom`/`direct` outbound → the warn dialog appears. *Fix it* → the profile is added and shows the "DNS fixed" badge. Re-paste → *Don't add* → no profile is added and the toast shows.
2. Add a subscription whose body contains a raw-JSON entry with `port 53 → direct` → after refresh, that profile appears with the "DNS fixed" badge; a clean `vless://` entry has no badge.
3. Connect to any normal profile → connects as before (no dialog, no regression).

**Step 6: Commit**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt \
        app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-ru/strings.xml
git commit -m "feat(ui): warn-and-fix dialog for dirty DNS and a DNS-fixed profile badge"
```

---

## Final verification

Run the full unit suite to confirm nothing regressed:

Command: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (including `ConfigBuilderDnsTest`, `ConfigBuilderTest`, `SubscriptionBodyParserTest`).

Then `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
