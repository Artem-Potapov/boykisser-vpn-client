package com.justme.xtls_core_proxy.subs

import com.justme.xtls_core_proxy.db.Subscription
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotedSubscriptionTest {

    @Test
    fun isApprovedLink_acceptsSubdomainsApexAndHttp() {
        assertTrue(PromotedSubscription.isApprovedLink("https://a.somenewsteps.space/x"))
        assertTrue(PromotedSubscription.isApprovedLink("https://sub.boykisser-keys.top/p"))
        assertTrue(PromotedSubscription.isApprovedLink("https://boykiss3r.site/p")) // apex
        assertTrue(PromotedSubscription.isApprovedLink("http://x.boykiss3r.site"))  // http ok
    }

    @Test
    fun isApprovedLink_isCaseInsensitiveOnHost() {
        assertTrue(PromotedSubscription.isApprovedLink("https://A.BoyKiss3r.SITE/p"))
    }

    @Test
    fun isApprovedLink_rejectsSpoofingNonApprovedAndMalformed() {
        assertFalse(PromotedSubscription.isApprovedLink("https://evil.com"))
        assertFalse(PromotedSubscription.isApprovedLink("https://notsomenewsteps.space"))      // suffix spoof
        assertFalse(PromotedSubscription.isApprovedLink("https://somenewsteps.space.evil.com")) // prefix spoof
        assertFalse(PromotedSubscription.isApprovedLink("ftp://x.boykiss3r.site"))             // wrong scheme
        assertFalse(PromotedSubscription.isApprovedLink(""))
        assertFalse(PromotedSubscription.isApprovedLink("not a url"))
    }

    @Test
    fun hasValidSubscription_trueForApprovedHostFetchedAtLeastOnce() {
        val valid = sub(url = "https://a.boykiss3r.site/s", lastFetchedAt = 1_000L)
        assertTrue(PromotedSubscription.hasValidSubscription(listOf(valid)))
    }

    @Test
    fun hasValidSubscription_falseWhenApprovedButNeverFetched() {
        val neverFetched = sub(url = "https://a.boykiss3r.site/s", lastFetchedAt = null)
        assertFalse(PromotedSubscription.hasValidSubscription(listOf(neverFetched)))
    }

    @Test
    fun hasValidSubscription_falseWhenFetchedButNotApproved() {
        val foreign = sub(url = "https://example.com/s", lastFetchedAt = 1_000L)
        assertFalse(PromotedSubscription.hasValidSubscription(listOf(foreign)))
    }

    @Test
    fun hasValidSubscription_falseForEmptyList() {
        assertFalse(PromotedSubscription.hasValidSubscription(emptyList()))
    }

    @Test
    fun hasValidSubscription_trueWhenAtLeastOneApprovedAndFetched() {
        val unfetched = sub(url = "https://a.boykiss3r.site/s", lastFetchedAt = null)
        val valid = sub(url = "https://a.boykiss3r.site/s", lastFetchedAt = 1_000L)
        assertTrue(PromotedSubscription.hasValidSubscription(listOf(unfetched, valid)))
    }

    private fun sub(url: String, lastFetchedAt: Long?): Subscription =
        Subscription(id = 1L, name = "n", url = url, lastFetchedAt = lastFetchedAt)
}
