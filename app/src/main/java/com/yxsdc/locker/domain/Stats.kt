package com.yxsdc.locker.domain

import com.yxsdc.locker.data.EventColors
import com.yxsdc.locker.data.EventRecord
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.data.WearSegment
import java.time.YearMonth
import java.time.ZoneId

enum class StatsMode { MONTH, YEAR }

data class SegmentDetail(
    val segment: WearSegment,
    val clippedMillis: Long,
    val crossesPeriodBoundary: Boolean,
)

data class EventTypeStat(
    val type: EventTypeEntity,
    val count: Int,
    val buckets: List<Int>,
)

data class StatsResult(
    val totalWearMillis: Long,
    val eventStats: List<EventTypeStat>,
    val wearBuckets: List<Long>,
    val bucketLabels: List<String>,
    val segmentDetails: List<SegmentDetail>,
    val eventDetails: List<EventRecord>,
)

fun bucketBoundaries(mode: StatsMode, anchor: YearMonth, zone: ZoneId): List<Long> {
    val starts = when (mode) {
        StatsMode.MONTH -> (1..anchor.lengthOfMonth()).map { day ->
            anchor.atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        }

        StatsMode.YEAR -> (1..12).map { month ->
            YearMonth.of(anchor.year, month)
                .atDay(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        }
    }
    val end = when (mode) {
        StatsMode.MONTH -> anchor.plusMonths(1)
            .atDay(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        StatsMode.YEAR -> YearMonth.of(anchor.year + 1, 1)
            .atDay(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }
    return starts + end
}

fun bucketLabels(mode: StatsMode, anchor: YearMonth): List<String> = when (mode) {
    StatsMode.MONTH -> (1..anchor.lengthOfMonth()).map { it.toString() }
    StatsMode.YEAR -> (1..12).map { "${it}月" }
}

fun computeStats(
    segments: List<WearSegment>,
    events: List<EventRecord>,
    eventTypes: List<EventTypeEntity>,
    mode: StatsMode,
    anchor: YearMonth,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Long = System.currentTimeMillis(),
): StatsResult {
    val boundaries = bucketBoundaries(mode, anchor, zone)
    val periodStart = boundaries.first()
    val periodEnd = boundaries.last()
    val bucketCount = boundaries.size - 1

    val wearBuckets = LongArray(bucketCount)
    var totalWear = 0L
    val details = mutableListOf<SegmentDetail>()

    segments.forEach { segment ->
        val segmentStart = segment.startAt
        val segmentEnd = (segment.endAt ?: now).coerceAtLeast(segmentStart)
        val overlapStart = maxOf(segmentStart, periodStart)
        val overlapEnd = minOf(segmentEnd, periodEnd)

        if (overlapEnd > overlapStart) {
            totalWear += overlapEnd - overlapStart

            for (index in 0 until bucketCount) {
                val bucketStart = maxOf(overlapStart, boundaries[index])
                val bucketEnd = minOf(overlapEnd, boundaries[index + 1])
                if (bucketEnd > bucketStart) {
                    wearBuckets[index] += bucketEnd - bucketStart
                }
            }

            details.add(
                SegmentDetail(
                    segment = segment,
                    clippedMillis = overlapEnd - overlapStart,
                    crossesPeriodBoundary = segmentStart < periodStart || segmentEnd > periodEnd,
                )
            )
        }
    }

    val periodEvents = events.filter { it.occurredAt in periodStart until periodEnd }
    val countsByType = HashMap<String, Int>()
    val bucketsByType = HashMap<String, IntArray>()
    periodEvents.forEach { event ->
        countsByType[event.type] = (countsByType[event.type] ?: 0) + 1
        val index = bucketIndex(boundaries, event.occurredAt)
        if (index >= 0) {
            val buckets = bucketsByType.getOrPut(event.type) { IntArray(bucketCount) }
            buckets[index]++
        }
    }

    val orderedTypes = eventTypes
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        .toMutableList()
    val knownKeys = orderedTypes.map { it.typeKey }.toSet()
    val unknownKeys = countsByType.keys
        .filter { it !in knownKeys }
        .sorted()
    unknownKeys.forEach { key ->
        orderedTypes.add(
            EventTypeEntity(
                typeKey = key,
                name = "未知事件",
                colorArgb = EventColors.SLATE,
                sortOrder = Int.MAX_VALUE,
                builtIn = false,
            )
        )
    }

    val eventStats = orderedTypes.map { type ->
        EventTypeStat(
            type = type,
            count = countsByType[type.typeKey] ?: 0,
            buckets = (bucketsByType[type.typeKey] ?: IntArray(bucketCount)).toList(),
        )
    }

    return StatsResult(
        totalWearMillis = totalWear,
        eventStats = eventStats,
        wearBuckets = wearBuckets.toList(),
        bucketLabels = bucketLabels(mode, anchor),
        segmentDetails = details.sortedByDescending { it.segment.startAt },
        eventDetails = periodEvents.sortedByDescending { it.occurredAt },
    )
}

private fun bucketIndex(boundaries: List<Long>, time: Long): Int {
    for (index in 0 until boundaries.size - 1) {
        if (time >= boundaries[index] && time < boundaries[index + 1]) {
            return index
        }
    }
    return -1
}
