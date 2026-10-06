package com.justme.xtls_core_proxy.log

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the compile-time tripwire that keeps the temporary 1M log buffer from shipping.
 *
 * The 1M in-memory cap is debug-only. [app/build.gradle.kts] must fail any release compile
 * (the marker [TEMPORARY_DEBUG_ONLY_1M_LOG_BUFFER]) so `:app:assembleRelease` cannot
 * succeed while this override is live. Declared as a unit-test task input in
 * `app/build.gradle.kts` so editing the Gradle file alone invalidates the suite.
 */
class LogBufferReleaseGuardTest {

    @Test
    fun releaseCompileTripwire_isPresentInAppBuildGradle() {
        // Prefer the app-module file: a repo-root cwd also has a plugins-only build.gradle.kts.
        val candidates = listOf(
            "app/build.gradle.kts",
            "build.gradle.kts",
        )
        val found = candidates.map(::File).firstOrNull {
            it.isFile && it.readText().contains("namespace = \"com.justme.xtls_core_proxy\"")
        }
        assertTrue(
            "app/build.gradle.kts must exist. Looked in: $candidates (cwd=${File(".").absolutePath})",
            found != null,
        )
        val text = found!!.readText()
        assertTrue(
            "Release compile must throw while the temporary 1M log buffer is live. " +
                "Expected marker TEMPORARY_DEBUG_ONLY_1M_LOG_BUFFER in ${found.path}.",
            text.contains("TEMPORARY_DEBUG_ONLY_1M_LOG_BUFFER"),
        )
        assertTrue(
            "The tripwire must actually throw GradleException on a release compile task, " +
                "not just mention the marker in a comment.",
            text.contains("throw GradleException") &&
                text.contains("compile") &&
                text.contains("Release"),
        )
    }
}
