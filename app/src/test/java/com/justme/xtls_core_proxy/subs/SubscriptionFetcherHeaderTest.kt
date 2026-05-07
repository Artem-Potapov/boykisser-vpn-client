package com.justme.xtls_core_proxy.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionFetcherHeaderTest {

    @Test
    fun parsesIntervalHeaderCaseInsensitive() {
        assertEquals(
            12,
            SubscriptionFetcher.parseIntervalHeader(
                mapOf("Profile-Update-Interval" to listOf("12"))
            )
        )
        assertEquals(
            6,
            SubscriptionFetcher.parseIntervalHeader(
                mapOf("PROFILE-UPDATE-INTERVAL" to listOf("6"))
            )
        )
        assertEquals(
            24,
            SubscriptionFetcher.parseIntervalHeader(
                mapOf("profile-update-interval" to listOf(" 24 "))
            )
        )
    }

    @Test
    fun returnsNullWhenHeaderMissing() {
        assertNull(SubscriptionFetcher.parseIntervalHeader(emptyMap()))
        assertNull(
            SubscriptionFetcher.parseIntervalHeader(
                mapOf("Content-Type" to listOf("text/plain"))
            )
        )
    }

    @Test
    fun returnsNullWhenValueIsNotInteger() {
        assertNull(
            SubscriptionFetcher.parseIntervalHeader(
                mapOf("profile-update-interval" to listOf("twelve"))
            )
        )
    }

    @Test
    fun returnsNullWhenValueIsLessThanOne() {
        assertNull(
            SubscriptionFetcher.parseIntervalHeader(
                mapOf("profile-update-interval" to listOf("0"))
            )
        )
        assertNull(
            SubscriptionFetcher.parseIntervalHeader(
                mapOf("profile-update-interval" to listOf("-3"))
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun ignoresNullKeyEntries() {
        // Mimic HttpURLConnection.getHeaderFields(), which puts the status line under a null key.
        val raw = linkedMapOf<String?, List<String>>(
            null to listOf("HTTP/1.1 200 OK"),
            "profile-update-interval" to listOf("8")
        )
        val coerced = raw as Map<String, List<String>>
        assertEquals(8, SubscriptionFetcher.parseIntervalHeader(coerced))
    }

    @Test
    fun picksFirstValidValueWhenHeaderHasMultiples() {
        assertEquals(
            18,
            SubscriptionFetcher.parseIntervalHeader(
                mapOf("profile-update-interval" to listOf("not-a-number", "18", "5"))
            )
        )
    }
}
