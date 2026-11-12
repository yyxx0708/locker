package com.yxsdc.locker.domain

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
private val dateFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
private val dateCompactFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
private val timeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

fun formatDateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime().format(dateTimeFormatter)

fun formatDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(dateFormatter)

fun formatDateCompact(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().format(dateCompactFormatter)

fun formatTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime().format(timeFormatter)

fun formatMonth(anchor: YearMonth): String = "${anchor.year}年${anchor.monthValue}月"

fun formatDuration(millis: Long): String {
    val totalMinutes = millis.coerceAtLeast(0) / 60_000
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "$days 天 $hours 小时 $minutes 分"
        hours > 0 -> "$hours 小时 $minutes 分"
        else -> "$minutes 分"
    }
}

fun formatDurationWithSeconds(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    val clock = String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    return if (days > 0) "${days}天 $clock" else clock
}

fun formatDurationShort(millis: Long): String {
    val totalMinutes = millis.coerceAtLeast(0) / 60_000
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}天${hours}时"
        hours > 0 -> "${hours}时${minutes}分"
        else -> "${minutes}分"
    }
}
