package com.justme.xtls_core_proxy.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class LogRepositoryBufferTest {
    @After fun tearDown() {
        LogRepository.clear()
        LogRepository.clearGiveUpLine()
        LogRepository.setMaxLines(1_000_000)
    }

    @Test fun clearGiveUpLine_removesARecordedGiveUpLine() {
        LogRepository.setGiveUpLine(GiveUpOngoingLine.TRAFFIC_HELD)

        LogRepository.clearGiveUpLine()

        org.junit.Assert.assertNull(LogRepository.giveUpLine.value)
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

    @Test fun setMaxLines_acceptsOneMillionLineCap() {
        LogRepository.setMaxLines(1_000_000)
        assertEquals(1_000_000, LogRepository.maxLines)
    }

    @Test fun setMaxLines_coercesAboveMaximumDownToCeiling() {
        LogRepository.setMaxLines(2_000_000)
        assertEquals(1_000_000, LogRepository.maxLines)
    }

    @Test fun defaultBuffer_isOneMillionLines() {
        assertEquals(1_000_000, LogPreferences.DEFAULT_BUFFER)
    }

    @Test fun bufferPresets_includeOneMillionLines() {
        assertEquals(listOf(1000, 2000, 5000, 10000, 1_000_000), LogPreferences.BUFFER_PRESETS)
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
