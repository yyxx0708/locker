package com.yxsdc.locker.data

object DefaultEventTypes {
    const val LOCKED_KEY = "locked"
    const val UNLOCKED_KEY = "unlocked"

    const val LOCKED_NAME = "锁射"
    const val UNLOCKED_NAME = "开锁射精"

    const val LOCKED_COLOR_ARGB = EventColors.BLUE
    const val UNLOCKED_COLOR_ARGB = EventColors.ORANGE

    fun builtIns(): List<EventTypeEntity> = listOf(
        EventTypeEntity(
            typeKey = LOCKED_KEY,
            name = LOCKED_NAME,
            colorArgb = LOCKED_COLOR_ARGB,
            sortOrder = 0,
            builtIn = true,
        ),
        EventTypeEntity(
            typeKey = UNLOCKED_KEY,
            name = UNLOCKED_NAME,
            colorArgb = UNLOCKED_COLOR_ARGB,
            sortOrder = 1,
            builtIn = true,
        ),
    )

    val builtInKeys: Set<String> = setOf(LOCKED_KEY, UNLOCKED_KEY)
}
