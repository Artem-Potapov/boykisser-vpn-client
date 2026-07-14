package com.justme.xtls_core_proxy.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit spec for the pure payload-bounding logic behind the Copy/Share fix.
 *
 * Copy (`setPrimaryClip`) and Share (`ACTION_SEND` `EXTRA_TEXT`) marshal their whole
 * payload through a single ~1 MB per-process Binder transaction; the full 10 000-line
 * buffer overruns it and throws `TransactionTooLargeException`, which — because the
 * `VpnService` lives in the same process — crashes the tunnel. `LogShareBudget.bound`
 * keeps the *newest* lines that fit under a byte budget so the inline payload can never
 * reach the Binder ceiling. (Export is exempt: it streams to a content:// output.)
 */
class LogShareBudgetTest {

    @Test fun empty_bufferProducesEmptyUntruncatedResult() {
        val result = LogShareBudget.bound(emptyList())
        assertEquals("", result.text)
        assertEquals(0, result.includedLines)
        assertEquals(0, result.totalLines)
        assertFalse(result.truncated)
    }

    @Test fun underBudget_keepsEveryLineInOrder() {
        val lines = listOf("alpha", "bravo", "charlie")
        val result = LogShareBudget.bound(lines, maxBytes = 64 * 1024)
        assertEquals("alpha\nbravo\ncharlie", result.text)
        assertEquals(3, result.includedLines)
        assertEquals(3, result.totalLines)
        assertFalse(result.truncated)
    }

    @Test fun overBudget_keepsOnlyTheNewestTailUnderTheByteBudget() {
        // 2000 lines of 200 bytes each (~402 KB with joiners) against a 256 KB budget.
        val lines = (0 until 2000).map { "line-%04d-".format(it) + "x".repeat(190) }
        val maxBytes = 256 * 1024
        val result = LogShareBudget.bound(lines, maxBytes = maxBytes)

        assertTrue("payload must stay under the Binder byte budget",
            result.text.toByteArray(Charsets.UTF_8).size <= maxBytes)
        assertTrue("must have dropped some lines", result.truncated)
        assertTrue(result.includedLines in 1 until lines.size)
        assertEquals(lines.size, result.totalLines)
        // The tail we keep is the NEWEST lines: the very last line is always present,
        // and the very first (oldest) line is dropped.
        assertTrue("newest line must be kept", result.text.endsWith(lines.last()))
        assertFalse("oldest line must be dropped", result.text.contains(lines.first()))
    }

    @Test fun singleLineLargerThanBudget_isStillReturnedRatherThanEmpty() {
        // A lone line that itself exceeds the budget: return it best-effort (the
        // defensive runCatching around the Binder call is the backstop for this
        // theoretical case; real Xray lines are capped at 64 KiB by the accumulator).
        val huge = "z".repeat(300 * 1024)
        val result = LogShareBudget.bound(listOf(huge), maxBytes = 256 * 1024)
        assertEquals(1, result.includedLines)
        assertEquals(1, result.totalLines)
        assertEquals(huge, result.text)
        assertFalse("a whole-buffer payload is not 'truncated' even if oversized",
            result.truncated)
    }
}