package com.yxsdc.locker.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 发送给掌管者时的数据范围选项。
 */
enum class SyncRange(val label: String, val days: Long?) {
    TODAY("当天", 1),
    THREE_DAYS("三天内", 3),
    WEEK("一周内", 7),
    MONTH("一个月内", 30),
    ALL("全部导出", null),
    ;

    /**
     * 以 [todayEpochDay] 为终点，返回窗口的天数范围（闭区间）。
     * [ALL] 从 epoch 0 开始。
     */
    fun windowFor(todayEpochDay: Long): Pair<Long, Long> {
        val to = todayEpochDay
        val from = days?.let { (todayEpochDay - it + 1).coerceAtLeast(0) } ?: 0L
        return from to to
    }

    /**
     * 从上次发送的日期开始到今天；从未发送过时等同于 [TODAY]。
     * 用于日常增量发送，避免每次都重发整段历史。
     */
    fun incrementalWindow(todayEpochDay: Long, lastExportedAt: Long?): Pair<Long, Long> {
        if (lastExportedAt == null) return windowFor(todayEpochDay)
        val lastDay = Instant.ofEpochMilli(lastExportedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
        return lastDay.coerceAtMost(todayEpochDay) to todayEpochDay
    }

    companion object {
        fun fromLabelOrNull(label: String?): SyncRange? =
            entries.firstOrNull { it.name == label }
    }
}

/** 增量窗口的起点是否早于今天（用于 UI 提示）。 */
fun Pair<Long, Long>.isSingleDay(todayEpochDay: Long): Boolean =
    first == second && second == todayEpochDay

/** 将 epochDay 格式化为「M月d日」。 */
fun formatEpochDay(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(
        java.time.format.DateTimeFormatter.ofPattern("M月d日", java.util.Locale.CHINA)
    )