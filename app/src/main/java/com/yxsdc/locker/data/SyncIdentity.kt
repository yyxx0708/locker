package com.yxsdc.locker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 人物与同步身份的绑定。
 * 被掌管者端：本人物的身份码与盐（code 为明文，仅存本机，zip 内只放哈希）。
 * 掌管者端：绑定导入来源的身份，code 为首次验证时输入的明文，用于后续解密。
 */
@Entity(
    tableName = "sync_identities",
    indices = [Index(value = ["personId"], unique = true)],
)
data class SyncIdentity(
    @PrimaryKey val identityId: String,
    val personId: Long,
    val displayName: String,
    val code: String,
    val codeSalt: String,
    val createdAt: Long,
    val lastExportedAt: Long? = null,
)
