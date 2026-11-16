package com.yxsdc.locker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [Index(value = ["personId", "occurredAt"])],
)
data class EventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val type: String,
    val occurredAt: Long,
    val note: String? = null,
)
