package com.justme.xtls_core_proxy.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class LogRepositoryBufferTest {
    @After fun tearDown() {
        LogRepository.clear()
        LogRepository.setMaxLines(5000)
    }

    @Test fun setMaxLines_trimsCurrentBufferImmediately() {
        LogRepository.clear()
        repeat(200) { LogRepository.append("line-$it") }
        LogRepository.setMaxLines(100)
        assertEquals(100, LogRepository.logs.value.size)
    }

    @Test fun append_respectsChangedCap() {
        LogRepository.clear()
        LogRepository.setMaxLines(100)
        repeat(150) { LogRepository.append("x-$it") }
        assertEquals(100, LogRepository.logs.value.size)
    }

    @Test fun setMaxLines_coercesBelowMinimumUpToFloor() {
        LogRepository.setMaxLines(1)
        assertEquals(100, LogRepository.maxLines)
    }

    @Test fun setMaxLines_coercesAboveMaximumDownToCeiling() {
        LogRepository.setMaxLines(1_000_000)
        assertEquals(50_000, LogRepository.maxLines)
    }

    @Test fun formatLogTimestamp_usesUs24HourMillisecondsFormat() {
        assertEquals(
            "03:04:05.678",
            formatLogTimestamp(
                instant = Instant.parse("2026-07-13T03:04:05.678Z"),
                zoneId = ZoneOffset.UTC,
            )
        )
    }
}
