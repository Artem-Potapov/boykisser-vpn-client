package com.justme.xtls_core_proxy.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end parser smoke-test against a real subscription body.
 * The fixture is committed at app/src/test/resources/real_subscription.b64.
 */
class SubscriptionIntegrationTest {

    @Test
    fun realBase64BodyParsesAllConfigs() {
        val resource = javaClass.classLoader!!.getResource("real_subscription.b64")
            ?: error("real_subscription.b64 not found in test resources")

        val b64 = File(resource.toURI()).readText().trim()
        assertTrue("b64 fixture must not be empty", b64.isNotEmpty())

        val outcome = SubscriptionBodyParser.parseBody(b64)

        println("=== Real subscription parse results ===")
        println("Parsed: ${outcome.parsed.size} configs, ${outcome.parseErrorCount} parse errors")
        outcome.parsed.forEachIndexed { i, c ->
            println("  [$i] ${c.displayName}")
        }

        assertEquals(
            "All 11 configs should parse with 0 errors",
            0, outcome.parseErrorCount
        )
        assertEquals(
            "Expected exactly 11 parsed configs",
            11, outcome.parsed.size
        )

        // All display names must be non-blank
        outcome.parsed.forEach { c ->
            assertTrue("Display name must be non-blank for ${c.config.take(60)}", c.displayName.isNotBlank())
        }

        // At least one name should contain Cyrillic or emoji (from #fragment)
        val hasCyrillicOrEmoji = outcome.parsed.any { c ->
            c.displayName.any { ch -> ch.code > 0x400 }
        }
        assertTrue("Expected at least one display name with non-ASCII content", hasCyrillicOrEmoji)
    }
}
