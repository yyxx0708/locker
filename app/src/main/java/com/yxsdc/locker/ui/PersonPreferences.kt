package com.yxsdc.locker.ui

import android.content.Context

/**
 * 记住上次选中的当前人物。人物本身存储在数据库中。
 */
class PersonPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("locker_settings", Context.MODE_PRIVATE)

    var selectedPersonId: Long?
        get() = prefs.getLong(KEY_SELECTED_PERSON, NO_PERSON).takeIf { it > 0 }
        set(value) {
            prefs.edit().putLong(KEY_SELECTED_PERSON, value ?: NO_PERSON).apply()
        }

    private companion object {
        const val KEY_SELECTED_PERSON = "selected_person_id"
        const val NO_PERSON = -1L
    }
}
