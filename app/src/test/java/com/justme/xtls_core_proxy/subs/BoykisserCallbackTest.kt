package com.justme.xtls_core_proxy.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoykisserCallbackTest {

    @Test
    fun validate_returnsApprovedSubUnchanged() {
        val sub = "https://a.boykiss3r.site/sub/abc"
        assertEquals(sub, BoykisserCallback.validate(sub))
    }

    @Test
    fun validate_trimsThenReturnsApprovedSub() {
        assertEquals(
            "https://a.boykiss3r.site/sub/abc",
            BoykisserCallback.validate("  https://a.boykiss3r.site/sub/abc  ")
        )
    }

    @Test
    fun validate_returnsNullForNonApprovedHost() {
        assertNull(BoykisserCallback.validate("https://evil.com/sub"))
    }

    @Test
    fun validate_returnsNullForBlankOrMissing() {
        assertNull(BoykisserCallback.validate(null))
        assertNull(BoykisserCallback.validate(""))
        assertNull(BoykisserCallback.validate("   "))
    }

    @Test
    fun validate_returnsNullForMalformedUrl() {
        assertNull(BoykisserCallback.validate("not a url"))
    }

    @Test
    fun validate_returnsNullForUserinfoHostSpoof() {
        // Approved domain only in the userinfo (before '@') -> real host is evil.com.
        assertNull(BoykisserCallback.validate("https://boykiss3r.site@evil.com/sub"))
    }

    @Test
    fun constants_matchDocumentedManifestValues() {
        assertEquals("bkvpn", BoykisserCallback.SCHEME)
        assertEquals("boykiss3r.site", BoykisserCallback.APPLINK_HOST)
        assertEquals("/app/add", BoykisserCallback.APPLINK_PATH)
        assertEquals("sub", BoykisserCallback.PARAM_SUB)
    }
}
