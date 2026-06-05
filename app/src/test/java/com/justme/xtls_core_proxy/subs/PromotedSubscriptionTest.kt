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
    fun isApprovedLink_rejectsUserinfoHostSpoof() {
        // The host is taken from URI.getHost(), NOT the userinfo before '@'. A link that
        // only mentions an approved domain in its userinfo resolves to the real host
        // (evil.com) and must be rejected. This is the highest-value allowlist bypass
        // class, so it is pinned here: a future swap of the parser (e.g. URI -> Uri.parse
        // or hand-rolled splitting) must keep failing these.
        assertFalse(PromotedSubscription.isApprovedLink("https://boykiss3r.site@evil.com/sub"))
        assertFalse(PromotedSubscription.isApprovedLink("https://somenewsteps.space@evil.com/"))
        // Inverse: foreign userinfo in front of a genuinely approved host is still that
        // approved host, so it is correctly accepted.
        assertTrue(PromotedSubscription.isApprovedLink("https://evil.com@boykiss3r.site/sub"))
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
