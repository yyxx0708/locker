package com.yxsdc.locker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "wear_segments",
    indices = [Index("personId")],
)
data class WearSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val startAt: Long,
    val endAt: Long? = null,
) {
    val isActive: Boolean
        get() = endAt == null
}
