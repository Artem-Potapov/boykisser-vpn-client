package com.justme.xtls_core_proxy.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UaHintTest {

    @Test
    fun forbidden_suggestsWhenUaNotHapp() {
        assertTrue(UaHint.shouldSuggest(httpStatus = 403, parsedCount = -1, uaIsHappLike = false))
    }

    @Test
    fun zeroParsedOn2xx_suggests() {
        assertTrue(UaHint.shouldSuggest(httpStatus = 200, parsedCount = 0, uaIsHappLike = false))
    }

    @Test
    fun someServersParsed_doesNotSuggest() {
        assertFalse(UaHint.shouldSuggest(httpStatus = 200, parsedCount = 5, uaIsHappLike = false))
    }

    @Test
    fun alreadyHappUa_neverSuggests() {
        assertFalse(UaHint.shouldSuggest(httpStatus = 403, parsedCount = -1, uaIsHappLike = true))
        assertFalse(UaHint.shouldSuggest(httpStatus = 200, parsedCount = 0, uaIsHappLike = true))
    }

    @Test
    fun otherFailures_doNotSuggest() {
        assertFalse(UaHint.shouldSuggest(httpStatus = 404, parsedCount = -1, uaIsHappLike = false))
        assertFalse(UaHint.shouldSuggest(httpStatus = null, parsedCount = -1, uaIsHappLike = false))
    }
}
