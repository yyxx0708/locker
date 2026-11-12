package com.yxsdc.locker.data

/**
 * 事件类型可选颜色。数值为 ARGB，供数据库与 Compose 共同使用。
 * 8 色色板：色相互相拉开，保证在月历 6dp 圆点和图例里也能分辨；
 * 取中等明度，兼顾白天与黑暗主题。
 */
object EventColors {
    const val BLUE = 0xFF2A78D6L
    const val ORANGE = 0xFFEB6834L
    const val GREEN = 0xFF2E9E5BL
    const val PURPLE = 0xFF8B5CF6L
    const val RED = 0xFFD64545L
    const val TEAL = 0xFF0E9F9AL
    const val PINK = 0xFFEC4899L
    const val GOLD = 0xFFC89B1EL

    /** 未知事件类型的兜底色，不参与可选色板。 */
    const val SLATE = 0xFF6B7280L

    val palette: List<Long> = listOf(
        BLUE,
        ORANGE,
        GREEN,
        PURPLE,
        RED,
        TEAL,
        PINK,
        GOLD,
    )
}
