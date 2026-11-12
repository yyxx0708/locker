package com.yxsdc.locker.domain

import com.yxsdc.locker.data.DefaultEventTypes
import com.yxsdc.locker.data.EventColors
import com.yxsdc.locker.data.EventRecord
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.data.WearSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class StatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val eventTypes = DefaultEventTypes.builtIns()
    private val customType = EventTypeEntity(
        typeKey = "custom_a",
        name = "自定义",
        colorArgb = EventColors.GREEN,
        sortOrder = 2,
        builtIn = false,
    )

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    private fun event(typeKey: String, occurredAt: Long): EventRecord =
        EventRecord(personId = 1, type = typeKey, occurredAt = occurredAt)

    private fun StatsResult.stat(typeKey: String): EventTypeStat =
        eventStats.first { it.type.typeKey == typeKey }

    @Test
    fun `month total clips segment crossing month boundary`() {
        val segment = WearSegment(
            personId = 1,
            startAt = at(2026, 10, 31, 22),
            endAt = at(2026, 11, 2, 8),
        )
        val stats = computeStats(
            segments = listOf(segment),
            events = emptyList(),
            eventTypes = eventTypes,
            mode = StatsMode.MONTH,
            anchor = YearMonth.of(2026, 11),
            zone = zone,
            now = at(2026, 11, 30, 23),
        )

        assertEquals(32L * 60 * 60 * 1000, stats.totalWearMillis)
        assertEquals(1, stats.segmentDetails.size)
        assertTrue(stats.segmentDetails.single().crossesPeriodBoundary)
        assertEquals(
            32L * 60 * 60 * 1000,
            stats.segmentDetails.single().clippedMillis,
        )
    }

    @Test
    fun `month buckets split wear across days`() {
        val segment = WearSegment(
            personId = 1,
            startAt = at(2026, 11, 10, 23),
            endAt = at(2026, 11, 11, 1),
        )
        val stats = computeStats(
            segments = listOf(segment),
            events = emptyList(),
            eventTypes = eventTypes,
            mode = StatsMode.MONTH,
            anchor = YearMonth.of(2026, 11),
            zone = zone,
            now = at(2026, 11, 30, 23),
        )

        assertEquals(2L * 60 * 60 * 1000, stats.wearBuckets.sum())
        assertEquals(1L * 60 * 60 * 1000, stats.wearBuckets[9])
        assertEquals(1L * 60 * 60 * 1000, stats.wearBuckets[10])
    }

    @Test
    fun `active segment counts up to now`() {
        val segment = WearSegment(
            personId = 1,
            startAt = at(2026, 11, 1, 0),
            endAt = null,
        )
        val stats = computeStats(
            segments = listOf(segment),
            events = emptyList(),
            eventTypes = eventTypes,
            mode = StatsMode.MONTH,
            anchor = YearMonth.of(2026, 11),
            zone = zone,
            now = at(2026, 11, 1, 10),
        )

        assertEquals(10L * 60 * 60 * 1000, stats.totalWearMillis)
    }

    @Test
    fun `event counts use selected period and type buckets`() {
        val events = listOf(
            event(DefaultEventTypes.LOCKED_KEY, at(2026, 11, 5, 10)),
            event(DefaultEventTypes.UNLOCKED_KEY, at(2026, 11, 5, 12)),
            event(DefaultEventTypes.LOCKED_KEY, at(2026, 11, 6, 9)),
            event(DefaultEventTypes.LOCKED_KEY, at(2026, 12, 1, 9)),
        )
        val stats = computeStats(
            segments = emptyList(),
            events = events,
            eventTypes = eventTypes,
            mode = StatsMode.MONTH,
            anchor = YearMonth.of(2026, 11),
            zone = zone,
            now = at(2026, 11, 30, 23),
        )

        assertEquals(2, stats.stat(DefaultEventTypes.LOCKED_KEY).count)
        assertEquals(1, stats.stat(DefaultEventTypes.UNLOCKED_KEY).count)
        assertEquals(3, stats.eventDetails.size)
        assertEquals(1, stats.stat(DefaultEventTypes.LOCKED_KEY).buckets[4])
        assertEquals(1, stats.stat(DefaultEventTypes.UNLOCKED_KEY).buckets[4])
        assertEquals(1, stats.stat(DefaultEventTypes.LOCKED_KEY).buckets[5])
    }

    @Test
    fun `custom event type keeps name color and buckets`() {
        val events = listOf(
            event(customType.typeKey, at(2026, 11, 5, 10)),
            event(DefaultEventTypes.LOCKED_KEY, at(2026, 11, 5, 11)),
        )
        val stats = computeStats(
            segments = emptyList(),
            events = events,
            eventTypes = eventTypes + customType,
            mode = StatsMode.MONTH,
            anchor = YearMonth.of(2026, 11),
            zone = zone,
            now = at(2026, 11, 30, 23),
        )

        val customStat = stats.stat(customType.typeKey)
        assertEquals(1, customStat.count)
        assertEquals("自定义", customStat.type.name)
        assertEquals(EventColors.GREEN, customStat.type.colorArgb)
        assertEquals(1, customStat.buckets[4])
        assertEquals(0, customStat.buckets[3])
    }

    @Test
    fun `events with missing definition fall back to unknown type`() {
        val events = listOf(event("gone", at(2026, 11, 5, 10)))
        val stats = computeStats(
            segments = emptyList(),
            events = events,
            eventTypes = eventTypes,
            mode = StatsMode.MONTH,
            anchor = YearMonth.of(2026, 11),
            zone = zone,
            now = at(2026, 11, 30, 23),
        )

        val unknown = stats.eventStats.first { it.type.typeKey == "gone" }
        assertEquals(1, unknown.count)
        assertEquals("未知事件", unknown.type.name)
    }

    @Test
    fun `year buckets split segment across years`() {
        val segment = WearSegment(
            personId = 1,
            startAt = at(2025, 12, 31, 23),
            endAt = at(2026, 1, 1, 1),
        )
        val stats = computeStats(
            segments = listOf(segment),
            events = emptyList(),
            eventTypes = eventTypes,
            mode = StatsMode.YEAR,
            anchor = YearMonth.of(2026, 1),
            zone = zone,
            now = at(2026, 12, 31, 23),
        )

        assertEquals(1L * 60 * 60 * 1000, stats.totalWearMillis)
        assertEquals(1L * 60 * 60 * 1000, stats.wearBuckets[0])
    }
}
