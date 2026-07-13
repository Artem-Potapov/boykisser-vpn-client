package com.justme.xtls_core_proxy.log

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
