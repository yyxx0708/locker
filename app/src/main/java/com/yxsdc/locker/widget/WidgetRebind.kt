package com.yxsdc.locker.widget

import android.content.Context
import com.yxsdc.locker.data.AppDatabase
import com.yxsdc.locker.data.Person

/** 导入备份后可恢复的绑定候选。 */
data class WidgetRebindCandidate(
    val appWidgetId: Int,
    val personName: String,
    val newPersonId: Long,
)

/** 导入备份后无法自动恢复的绑定。 */
data class WidgetRebindFailure(
    val appWidgetId: Int,
    val personName: String,
)

data class WidgetRebindPlan(
    val candidates: List<WidgetRebindCandidate>,
    val failures: List<WidgetRebindFailure>,
) {
    val isEmpty: Boolean get() = candidates.isEmpty() && failures.isEmpty()
}

/**
 * 导入 JSON 备份会重建人物、ID 全变，小组件绑定会全部失效。
 *
 * 这里按「绑定时的名称」做精确匹配，只生成候选，不静默改绑；
 * 由用户确认后再调用 [applyRebind]。
 */
object WidgetRebind {

    /** 读取现存人物生成恢复候选。会访问数据库，必须在后台线程调用。 */
    suspend fun plan(context: Context): WidgetRebindPlan {
        val bindings = WidgetPrefs.allBindings(context)
        if (bindings.isEmpty()) return WidgetRebindPlan(emptyList(), emptyList())

        val dao = AppDatabase.get(context).lockerDao()
        val persons: List<Person> = dao.getPersons()
        val byName = persons.associateBy { it.name }
        val byId = persons.associateBy { it.id }

        val candidates = mutableListOf<WidgetRebindCandidate>()
        val failures = mutableListOf<WidgetRebindFailure>()

        bindings.forEach { (widgetId, boundId) ->
            // 已经指向现存人物：无需恢复
            if (byId.containsKey(boundId)) return@forEach
            val name = WidgetPrefs.boundPersonName(context, widgetId) ?: return@forEach
            val match = byName[name]
            if (match != null) {
                candidates += WidgetRebindCandidate(widgetId, name, match.id)
            } else {
                failures += WidgetRebindFailure(widgetId, name)
            }
        }
        return WidgetRebindPlan(candidates, failures)
    }

    /** 用户确认后执行重新绑定。同一人物只允许一个实例，重复的保留第一个。 */
    fun applyRebind(context: Context, candidates: List<WidgetRebindCandidate>) {
        val used = WidgetPrefs.allBindings(context)
            .filterKeys { key -> candidates.none { it.appWidgetId == key } }
            .values
            .toMutableSet()
        candidates.forEach { candidate ->
            if (used.add(candidate.newPersonId)) {
                WidgetPrefs.rebind(
                    context,
                    candidate.appWidgetId,
                    candidate.newPersonId,
                    candidate.personName,
                )
            } else {
                WidgetPrefs.unbind(context, candidate.appWidgetId)
            }
        }
        LockerWidgetProvider.updateAll(context)
    }
}
