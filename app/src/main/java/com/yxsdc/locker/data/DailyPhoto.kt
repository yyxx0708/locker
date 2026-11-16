package com.yxsdc.locker.data

import androidx.room.Entity

/**
 * 某个人物某一天的照片，作为月历格子的背景。
 * 照片按 JPEG 压缩后存入数据库，避免额外的文件权限与路径管理。
 * [fingerprint] 在写入时算好，同步比对与打包时无需再读取整张照片。
 */
@Entity(
    tableName = "daily_photos",
    primaryKeys = ["personId", "epochDay"],
)
data class DailyPhoto(
    val personId: Long,
    val epochDay: Long,
    val jpeg: ByteArray,
    val updatedAt: Long,
    val fingerprint: String = "",
)

/** 照片元信息（不含 JPEG 字节），供同步比对与体积预估使用。 */
data class DailyPhotoMeta(
    val epochDay: Long,
    val updatedAt: Long,
    val fingerprint: String,
    /** JPEG 体积，由 SQL 的 LENGTH(jpeg) 计算。 */
    val byteSize: Long,
)
