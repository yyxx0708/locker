package com.yxsdc.locker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

const val DEFAULT_PERSON_NAME = "我"

/**
 * 独立的计时对象。每个人物拥有自己的佩戴状态、分段与事件。
 */
@Entity(tableName = "persons")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)
