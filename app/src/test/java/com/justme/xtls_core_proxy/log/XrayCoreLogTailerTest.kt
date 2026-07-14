package com.justme.xtls_core_proxy.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class XrayCoreLogTailerTest {
    @Test fun strips_xray_leading_timestamp() {
        assertEquals(
            "[Info] core: Xray started",
            XrayCoreLogTailer.stripXrayTimestamp("2026/07/11 12:00:00.123456 [Info] core: Xray started")
        )
    }

    @Test fun strips_timestamp_without_micros() {
        assertEquals(
            "[Warning] something",
            XrayCoreLogTailer.stripXrayTimestamp("2026/07/11 12:00:00 [Warning] something")
        )
    }

    @Test fun leaves_untimestamped_line_unchanged() {
        assertEquals("plain line", XrayCoreLogTailer.stripXrayTimestamp("plain line"))
    }

    @Test fun partial_line_does_not_emit_until_newline() {
        val emitted = mutableListOf<String>()
        val acc = BoundedLogLineAccumulator(onLine = { emitted += it })

        acc.accept("hello".toByteArray(StandardCharsets.UTF_8))
        assertTrue(emitted.isEmpty())

        acc.accept(" world\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals(listOf("hello world"), emitted)
    }

    @Test fun utf8_multibyte_character_split_across_chunks_round_trips() {
        // U+1F600 😀 is F0 9F 98 80 — split mid-sequence across two accepts.
        val full = "prefix 😀 suffix\n".toByteArray(StandardCharsets.UTF_8)
        val splitAt = full.indexOf(0xF0.toByte()) + 2 // after F0 9F, before 98 80
        val emitted = mutableListOf<String>()
        val acc = BoundedLogLineAccumulator(onLine = { emitted += it })

        acc.accept(full.copyOfRange(0, splitAt))
        assertTrue(emitted.isEmpty())

        acc.accept(full.copyOfRange(splitAt, full.size))
        assertEquals(listOf("prefix 😀 suffix"), emitted)
    }

    @Test fun oversized_unterminated_line_is_discarded_until_next_newline() {
        val maxPending = 32
        val emitted = mutableListOf<String>()
        val acc = BoundedLogLineAccumulator(
            maxPendingBytes = maxPending,
            onLine = { emitted += it },
        )

        // First chunk fills past the cap with no newline → discard mode, no emit.
        acc.accept(ByteArray(maxPending + 8) { 'A'.code.toByte() })
        assertTrue(emitted.isEmpty())

        // Trailing garbage still before newline stays suppressed.
        acc.accept("TAIL".toByteArray(StandardCharsets.UTF_8))
        assertTrue(emitted.isEmpty())

        // Resync on newline, then a normal line can emit.
        acc.accept("\nok-line\n".toByteArray(StandardCharsets.UTF_8))
        assertEquals(listOf("ok-line"), emitted)
    }

    @Test fun oversized_policy_never_emits_truncated_secret_fragment() {
        val maxPending = 16
        val emitted = mutableListOf<String>()
        val acc = BoundedLogLineAccumulator(
            maxPendingBytes = maxPending,
            onLine = { emitted += it },
        )

        acc.accept("secret-token-ABCDEFGHIJKLMNOP".toByteArray(StandardCharsets.UTF_8))
        acc.accept("\n".toByteArray(StandardCharsets.UTF_8))
        assertTrue(
            "oversized pending must not emit any prefix of the discarded line",
            emitted.none { it.contains("secret") || it.contains("ABCD") },
        )
    }
}
