package com.yxsdc.locker.widget

import android.content.Context

/**
 * 小组件实例与人物的一对一绑定。
 *
 * 每个实例用 appWidgetId 作键，同时保存人物 ID 与绑定时的名称：
 * 名称用于导入备份后按「精确同名」找回绑定（导入会重建人物、ID 全变）。
 */
object WidgetPrefs {
    private const val PREFIX_ID = "widget_person_"
    private const val PREFIX_NAME = "widget_person_name_"

    private fun prefs(context: Context) =
        context.getSharedPreferences("locker_settings", Context.MODE_PRIVATE)

    fun bind(context: Context, appWidgetId: Int, personId: Long, personName: String) {
        prefs(context).edit()
            .putLong(PREFIX_ID + appWidgetId, personId)
            .putString(PREFIX_NAME + appWidgetId, personName)
            .apply()
    }

    fun boundPersonId(context: Context, appWidgetId: Int): Long? {
        val id = prefs(context).getLong(PREFIX_ID + appWidgetId, -1L)
        return id.takeIf { it > 0 }
    }

    fun boundPersonName(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(PREFIX_NAME + appWidgetId, null)

    fun unbind(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(PREFIX_ID + appWidgetId)
            .remove(PREFIX_NAME + appWidgetId)
            .apply()
    }

    /** 所有实例绑定：appWidgetId -> personId。 */
    fun allBindings(context: Context): Map<Int, Long> =
        prefs(context).all
            .filterKeys { it.startsWith(PREFIX_ID) }
            .mapNotNull { (key, value) ->
                val widgetId = key.removePrefix(PREFIX_ID).toIntOrNull()
                val personId = (value as? Long)?.takeIf { it > 0 }
                if (widgetId != null && personId != null) widgetId to personId else null
            }
            .toMap()

    /** 该人物是否已被某个实例绑定（可排除正在配置的实例）。 */
    fun isPersonBound(context: Context, personId: Long, excludeWidgetId: Int? = null): Boolean =
        allBindings(context).any { (widgetId, bound) ->
            bound == personId && widgetId != excludeWidgetId
        }

    /**
     * 清理指向已不存在实例的绑定。
     *
     * `onDeleted` 不保证一定送达（应用处于 stopped 状态、启动器崩溃或漏发），
     * 残留绑定会让对应人物在配置界面永久置灰。因此每次刷新前用系统给的
     * 实例列表对账一次。
     */
    fun pruneStaleBindings(context: Context, liveWidgetIds: Set<Int>) {
        val stale = allBindings(context).keys.filter { it !in liveWidgetIds }
        if (stale.isEmpty()) return
        val editor = prefs(context).edit()
        stale.forEach { widgetId ->
            editor.remove(PREFIX_ID + widgetId)
            editor.remove(PREFIX_NAME + widgetId)
        }
        editor.apply()
    }

    /** 人物被删除时，清掉所有指向它的绑定。 */
    fun clearForPerson(context: Context, personId: Long) {
        val editor = prefs(context).edit()
        allBindings(context)
            .filterValues { it == personId }
            .keys
            .forEach { widgetId ->
                editor.remove(PREFIX_ID + widgetId)
                editor.remove(PREFIX_NAME + widgetId)
            }
        editor.apply()
    }

    /** 导入备份后把绑定从旧人物 ID 迁到新人物 ID。 */
    fun rebind(context: Context, appWidgetId: Int, personId: Long, personName: String) {
        bind(context, appWidgetId, personId, personName)
    }

    /** 刷新时顺带更新保存的名称，保证改名后仍能按名称恢复。 */
    fun updateName(context: Context, appWidgetId: Int, personName: String) {
        if (boundPersonName(context, appWidgetId) == personName) return
        prefs(context).edit().putString(PREFIX_NAME + appWidgetId, personName).apply()
    }
}
