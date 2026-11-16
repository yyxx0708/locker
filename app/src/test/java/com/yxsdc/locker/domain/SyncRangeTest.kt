package com.yxsdc.locker.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SyncRangeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = 20_000L

    private fun millisOf(epochDay: Long): Long =
        java.time.LocalDate.ofEpochDay(epochDay)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun fixedWindowsEndToday() {
        assertEquals(20_000L to 20_000L, SyncRange.TODAY.windowFor(today))
        assertEquals(19_998L to 20_000L, SyncRange.THREE_DAYS.windowFor(today))
        assertEquals(19_994L to 20_000L, SyncRange.WEEK.windowFor(today))
        assertEquals(19_971L to 20_000L, SyncRange.MONTH.windowFor(today))
        assertEquals(0L to 20_000L, SyncRange.ALL.windowFor(today))
    }

    @Test
    fun incrementalStartsAtLastExportDay() {
        val lastExport = millisOf(19_997L) + 3_600_000L
        assertEquals(19_997L to 20_000L, SyncRange.TODAY.incrementalWindow(today, lastExport))
    }

    @Test
    fun incrementalWithoutHistoryFallsBackToToday() {
        assertEquals(20_000L to 20_000L, SyncRange.TODAY.incrementalWindow(today, null))
    }

    @Test
    fun incrementalClampsFutureExportTime() {
        val future = Instant.now().plusSeconds(86_400).toEpochMilli()
        val (from, to) = SyncRange.TODAY.incrementalWindow(today, future)
        assertEquals(today, from)
        assertEquals(today, to)
    }

    @Test
    fun singleDayWindowIsDetected() {
        assert((20_000L to 20_000L).isSingleDay(today))
        assert(!(19_998L to 20_000L).isSingleDay(today))
    }
}