package com.justme.xtls_core_proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the release/debug source-set boundary for the QA-only log dump actions. The debug
 * `DebugLogDumpMenuItems` / `DebugLogDumpStatus` composables are real UI; the release stubs beside
 * them must stay empty so no action, label, or dump logic is packaged in release builds. A future
 * edit that adds a body to a stub would silently ship the feature — this test is the CI guard.
 *
 * The release source file is declared as a `testDebugUnitTest` input in `app/build.gradle.kts`, so
 * editing it alone invalidates the task (matching the HealthProbeSchemeTest input pattern). The
 * release APK inspection remains the packaging oracle; this is the cheap source-level guard.
 */
class ReleaseDumpStubGuardTest {

    private val releaseStub: File by lazy {
        val candidates = listOf(
            "src/release/java/com/justme/xtls_core_proxy/log/DebugLogDumpActions.kt",
            "app/src/release/java/com/justme/xtls_core_proxy/log/DebugLogDumpActions.kt",
        )
        val found = candidates.map(::File).firstOrNull { it.isFile }
        assertTrue(
            "release DebugLogDumpActions.kt must exist. Looked in: $candidates " +
                "(cwd=${File(".").absolutePath})",
            found != null,
        )
        found!!
    }

    @Test fun releaseStub_declaresBothDebugComposableSignatures() {
        val source = releaseStub.readText(Charsets.UTF_8)
        assertTrue("missing DebugLogDumpMenuItems signature in release stub",
            Regex("""fun\s+DebugLogDumpMenuItems\s*\([^)]*logs\s*:\s*List<String>[^)]*,\s*closeMenu\s*:\s*\(\)\s*->\s*Unit\s*\)""")
                .containsMatchIn(source))
        assertTrue("missing DebugLogDumpStatus signature in release stub",
            Regex("""fun\s+DebugLogDumpStatus\s*\(\s*\)""").containsMatchIn(source))
    }

    @Test fun releaseStub_composableBodiesAreEmpty() {
        val source = releaseStub.readText(Charsets.UTF_8)
        for (name in listOf("DebugLogDumpMenuItems", "DebugLogDumpStatus")) {
            val body = functionBody(source, name)
            val stripped = body
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("//") }
                .joinToString("")
            assertEquals("$name release stub must stay empty, got: $body", "", stripped)
        }
    }

    /**
     * Returns the text between the opening and closing braces of the named function. The stub
     * has no nested braces (it is required to be empty), so first-match brace tracking is enough;
     * a future non-empty body with nested blocks is still captured and fails the empty assertion.
     */
    private fun functionBody(source: String, name: String): String {
        val start = Regex("""fun\s+$name\s*\(""").find(source)
            ?: throw AssertionError("$name not found in release stub")
        val open = source.indexOf('{', start.range.last)
        assertTrue("$name has no body", open >= 0)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("$name body is not closed")
    }
}
