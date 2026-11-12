package com.yxsdc.locker.data

import androidx.room.Entity
import androidx.room.Index

/**
 * 每个「人物 + 日期」固定一个 5 位随机数。
 * 同一天不同人物的号码不允许相同。
 */
@Entity(
    tableName = "daily_random_numbers",
    primaryKeys = ["personId", "epochDay"],
    indices = [Index(value = ["epochDay", "number"], unique = true)],
)
data class DailyRandomNumber(
    val personId: Long,
    val epochDay: Long,
    val number: String,
    val createdAt: Long,
)
