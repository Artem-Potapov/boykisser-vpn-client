package com.justme.xtls_core_proxy.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
