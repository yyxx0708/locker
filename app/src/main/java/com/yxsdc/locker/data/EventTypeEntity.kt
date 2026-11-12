package com.yxsdc.locker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 事件类型定义。内置类型使用固定 typeKey（locked / unlocked），可改名改色但不能删除。
 */
@Entity(
    tableName = "event_types",
    indices = [Index(value = ["typeKey"], unique = true)],
)
data class EventTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val typeKey: String,
    val name: String,
    val colorArgb: Long,
    val sortOrder: Int,
    val builtIn: Boolean,
)
