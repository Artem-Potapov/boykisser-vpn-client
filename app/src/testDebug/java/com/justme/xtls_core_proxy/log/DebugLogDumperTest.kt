package com.justme.xtls_core_proxy.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * Unit spec for the debug-only log-dump pure helpers (debug source set, debug unit-test
 * source set): dump marker shaping, full-buffer rendering, UTF-8-safe chunking, logcat
 * escaping, and unique timestamped dump-file naming under the app's external `files/tmp/`.
 */
class DebugLogDumperTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fixedInstant: Instant = Instant.parse("2026-11-20T10:11:12.123Z")

    // ---- dump marker ----

    @Test fun marker_containsExactUtcTimestampProfileAndLineCount() {
        assertEquals(
            "[log-dump] utc=2026-11-20T10:11:12.123Z profile=My Server lines=42",
            DebugLogDumper.buildDumpMarker(fixedInstant, "My Server", 42))
    }

    @Test fun marker_missingOrBlankProfile_rendersNone() {
        assertEquals(
            "[log-dump] utc=2026-11-20T10:11:12.123Z profile=<none> lines=0",
            DebugLogDumper.buildDumpMarker(fixedInstant, null, 0))
        assertEquals(
            "[log-dump] utc=2026-11-20T10:11:12.123Z profile=<none> lines=0",
            DebugLogDumper.buildDumpMarker(fixedInstant, "   ", 0))
    }

    @Test fun marker_flattensControlCharactersSoItStaysOneLine() {
        val marker = DebugLogDumper.buildDumpMarker(fixedInstant, "Bad\nName\rWith\tCtrl\u0007", 1)
        assertEquals("single line", 0, marker.count { it == '\n' })
        assertFalse(marker.contains('\r'))
        assertFalse(marker.contains('\t'))
        assertTrue("control chars flattened to spaces and name trimmed, got: $marker",
            marker.contains("profile=Bad Name With Ctrl lines=1"))
    }

    // ---- full-buffer rendering ----

    @Test fun renderDump_emptyBuffer_isMarkerOnly() {
        assertEquals("M", DebugLogDumper.renderDump(emptyList(), "M"))
    }

    @Test fun renderDump_markerFirstThenEveryBufferLineInOrder() {
        val dump = DebugLogDumper.renderDump(listOf("first", "second", "third"), "M")
        assertEquals("M\nfirst\nsecond\nthird", dump)
        assertEquals("marker is the first line", "M", dump.lineSequence().first())
    }

    // ---- UTF-8-safe chunking ----

    @Test fun chunkUtf8_emptyText_hasNoChunks() {
        assertTrue(DebugLogDumper.chunkUtf8("").isEmpty())
    }

    @Test fun chunkUtf8_textUnderBudget_isOneExactChunk() {
        val text = "alpha\nbravo-é-日-😀"
        assertEquals(listOf(text), DebugLogDumper.chunkUtf8(text))
    }

    @Test fun chunkUtf8_everyChunkStaysWithinBudget_andReassemblesLosslessly() {
        val text = (0 until 400).joinToString("\n") { "line-%03d-".format(it) + "x".repeat(it % 70) }
        val chunks = DebugLogDumper.chunkUtf8(text)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue("chunk over budget: ${chunk.toByteArray(Charsets.UTF_8).size}",
                chunk.toByteArray(Charsets.UTF_8).size <= DebugLogDumper.CHUNK_MAX_BYTES)
        }
        assertEquals("chunks must reassemble to the exact input", text, chunks.joinToString(""))
    }

    @Test fun chunkUtf8_multibyteCharacterNeverSplitAcrossChunks() {
        // Each 日 is 3 UTF-8 bytes: 1000 of them fill exactly 3000 bytes, one more rolls over.
        val text = "日".repeat(1001)
        val chunks = DebugLogDumper.chunkUtf8(text)
        assertEquals(listOf("日".repeat(1000), "日"), chunks)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test fun chunkUtf8_surrogatePairEmojiNeverSplit() {
        val emoji = "😀" // 4-byte UTF-8 code point (surrogate pair in UTF-16)
        val text = "a".repeat(2999) + emoji + "b".repeat(2999)
        val chunks = DebugLogDumper.chunkUtf8(text)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size > 1)
        chunks.forEach { chunk ->
            // A chunk holding a split surrogate pair would not survive a UTF-8 round trip.
            assertEquals("chunk must round-trip UTF-8", chunk,
                String(chunk.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertTrue("emoji kept whole in one chunk", chunks.any { it.contains(emoji) })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test fun chunkUtf8_oversizedSingleLine_splitsIntoBudgetedPieces() {
        val chunks = DebugLogDumper.chunkUtf8("a".repeat(10_000))
        assertEquals(listOf(3000, 3000, 3000, 1000),
            chunks.map { it.toByteArray(Charsets.UTF_8).size })
        assertEquals("a".repeat(10_000), chunks.joinToString(""))
    }

    @Test fun chunkUtf8_respectsCustomBudget() {
        assertEquals(listOf("abcd", "e"), DebugLogDumper.chunkUtf8("abcde", 4))
    }

    // ---- logcat line escaping (keeps every emitted entry single-line and lossless) ----

    @Test fun escapeForLogcat_outputNeverContainsRawLineBreaks() {
        val escaped = DebugLogDumper.escapeForLogcat("a\nb\rc\\nd")
        assertFalse(escaped.contains('\n'))
        assertFalse(escaped.contains('\r'))
    }

    @Test fun escapeRoundTrip_isLossless_includingLiteralBackslashN() {
        // "d\\ne" holds a literal backslash+n two-char sequence that must NOT decode to a newline.
        val nasty = "a\\b\nc\rd\\ne"
        assertEquals(nasty,
            DebugLogDumper.unescapeFromLogcat(DebugLogDumper.escapeForLogcat(nasty)))
        val unicode = "маркер 日 😀\nnew-line"
        assertEquals(unicode,
            DebugLogDumper.unescapeFromLogcat(DebugLogDumper.escapeForLogcat(unicode)))
    }

    // ---- dump file naming ----

    @Test fun dumpFileName_encodesUtcTimestampWithSafeCharacters() {
        assertEquals("log-dump-20261120-101112-123.txt",
            DebugLogDumper.buildDumpFileName(fixedInstant))
        assertTrue(DebugLogDumper.buildDumpFileName(Instant.now())
            .matches(Regex("^[A-Za-z0-9._-]+\\.txt$")))
    }

    @Test fun dumpFileName_distinctInstantsProduceDistinctNames() {
        assertNotEquals(
            DebugLogDumper.buildDumpFileName(fixedInstant),
            DebugLogDumper.buildDumpFileName(fixedInstant.plusMillis(1)))
    }

    // ---- dump file writing ----

    @Test fun writeDumpFile_writesExactUtf8ContentUnderTmpDir() {
        val dir = tmp.newFolder("tmp")
        val text = "[log-dump] utc=2026-11-20T10:11:12.123Z profile=X lines=2\nalpha\nbeta\n"
        val file = DebugLogDumper.writeDumpFile(dir, text, fixedInstant)
        assertEquals("log-dump-20261120-101112-123.txt", file.name)
        assertEquals(text, file.readText(Charsets.UTF_8))
        assertTrue("marker is the first line", file.readLines().first().startsWith("[log-dump]"))
    }

    @Test fun writeDumpFile_sameInstantDumpsResolveToUniqueFiles() {
        val dir = tmp.newFolder("tmp2")
        val first = DebugLogDumper.writeDumpFile(dir, "one\n", fixedInstant)
        val second = DebugLogDumper.writeDumpFile(dir, "two\n", fixedInstant)
        assertNotEquals("same-instant dumps must not overwrite each other",
            first.absolutePath, second.absolutePath)
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertEquals("one\n", first.readText())
        assertEquals("two\n", second.readText())
    }

    @Test fun writeDumpFile_createsMissingTargetDirectory() {
        val dir = File(tmp.root, "deep/nested/tmp")
        val file = DebugLogDumper.writeDumpFile(dir, "x\n", fixedInstant)
        assertTrue(file.exists())
    }

    @Test fun writeDumpFile_creationFailsUnderRegularFileParent_throwsExplicitError() {
        val blocker = tmp.newFile("parent-is-a-file")
        val target = File(blocker, "tmp")
        val result = runCatching { DebugLogDumper.writeDumpFile(target, "x\n", fixedInstant) }
        assertTrue("expected a creation-failure error, got $result", result.isFailure)
        val message = result.exceptionOrNull()?.message
        assertTrue("error must name the target and report creation failure, got: $message",
            message?.startsWith("Cannot use dump directory") == true &&
                message.contains(target.path) && message.contains("creation failed"))
    }

    @Test fun writeDumpFile_targetPathIsRegularFile_throwsExplicitError() {
        val blocker = tmp.newFile("tmp-is-a-file")
        val result = runCatching { DebugLogDumper.writeDumpFile(blocker, "x\n", fixedInstant) }
        assertTrue("expected an explicit error, got $result", result.isFailure)
        val message = result.exceptionOrNull()?.message
        assertTrue("error must be an explicit directory error naming the target, got: $message",
            message?.startsWith("Cannot use dump directory") == true && message.contains(blocker.path))
    }

    @Test fun writeDumpFile_existingDirectory_isAccepted() {
        val dir = tmp.newFolder("existing-tmp")
        val file = DebugLogDumper.writeDumpFile(dir, "x\n", fixedInstant)
        assertEquals(dir, file.parentFile)
        assertEquals("x\n", file.readText(Charsets.UTF_8))
    }
}
