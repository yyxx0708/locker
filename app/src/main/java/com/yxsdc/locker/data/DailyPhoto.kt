package com.yxsdc.locker.data

import androidx.room.Entity
import androidx.room.Index

/**
 * 某个人物某一天的照片，作为月历格子的背景。
 * 照片按 JPEG 压缩后存入数据库，避免额外的文件权限与路径管理。
 */
@Entity(
    tableName = "daily_photos",
    primaryKeys = ["personId", "epochDay"],
    indices = [Index("personId")],
)
data class DailyPhoto(
    val personId: Long,
    val epochDay: Long,
    val jpeg: ByteArray,
    val updatedAt: Long,
)
