package com.yxsdc.locker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.yxsdc.locker.MainActivity
import com.yxsdc.locker.R
import com.yxsdc.locker.data.AppDatabase
import com.yxsdc.locker.data.Person
import com.yxsdc.locker.data.WearSegment
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 只读小组件：每个实例绑定一个人物，显示本次佩戴时长与今日照片打卡状态。
 *
 * - 不做任何写操作，点卡片只打开 App 并切到绑定人物
 * - 「本次已持续」由 Chronometer 自己走字，小组件无需每秒刷新
 * - 未佩戴时显示「未佩戴」，不显示数字
 */
class LockerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        runSafely {
            render(context, appWidgetManager, appWidgetIds, pruneStale = true)
        }
    }

    /** 尺寸变化时切换紧凑/宽版布局。 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        runSafely {
            // 刚添加/刚拉伸时 options 可能还没写回 manager，直接用回调给的新值
            render(
                context,
                appWidgetManager,
                intArrayOf(appWidgetId),
                widthOverride = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                    .takeIf { it > 0 },
            )
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetPrefs.unbind(context, it) }
    }

    /**
     * 备份恢复（换机、启动器还原）后系统会重新分配 appWidgetId，
     * 旧绑定已失效；这里清空残留，让实例回到「未绑定人物」由用户重选。
     */
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        oldWidgetIds.forEach { WidgetPrefs.unbind(context, it) }
        newWidgetIds.forEach { WidgetPrefs.unbind(context, it) }
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            runSafely {
                val manager = AppWidgetManager.getInstance(context) ?: return@runSafely
                render(context, manager, widgetIds(context), pruneStale = true)
            }
        }
    }

    private suspend fun render(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        pruneStale: Boolean = false,
        widthOverride: Int? = null,
    ) {
        if (ids.isEmpty()) return

        // 对账只在完整刷新时做：必须用系统返回的完整实例列表，
        // 否则「只刷新某个实例」会把其他实例的绑定当成过期删掉。
        if (pruneStale) {
            val live = runCatching { widgetIds(context) }.getOrDefault(IntArray(0))
            if (live.isNotEmpty()) {
                WidgetPrefs.pruneStaleBindings(context, live.toSet())
            }
        }

        val dao = runCatching { AppDatabase.get(context).lockerDao() }.getOrNull()
        val today = LocalDate.now().toEpochDay()
        // 同一次刷新里按人物去重，避免多实例重复查询
        val stateByPerson = mutableMapOf<Long, PersonState>()

        ids.forEach { widgetId ->
            val views = runCatching {
                if (dao == null) {
                    buildFallbackViews(context, widgetId, manager, widthOverride)
                } else {
                    val boundId = WidgetPrefs.boundPersonId(context, widgetId)
                    val person = boundId?.let { dao.getPerson(it) }
                    when {
                        boundId == null -> buildPlaceholderViews(
                            context,
                            widgetId,
                            manager,
                            widthOverride,
                            title = R.string.widget_not_bound,
                            action = R.string.widget_choose_person,
                        )

                        person == null -> buildPlaceholderViews(
                            context,
                            widgetId,
                            manager,
                            widthOverride,
                            title = R.string.widget_person_deleted,
                            action = R.string.widget_person_deleted_action,
                        )

                        else -> {
                            val state = stateByPerson.getOrPut(person.id) {
                                val active: WearSegment? = dao.getActiveSegment(person.id)
                                // 只查存在性，不把 JPEG 读进内存
                                val checkedIn = dao.hasDailyPhoto(person.id, today)
                                PersonState(person.name, active?.startAt, checkedIn)
                            }
                            // 名称可能被改过，顺手更新，导入恢复时按名称匹配更可靠
                            WidgetPrefs.updateName(context, widgetId, state.personName)
                            buildPersonViews(
                                context, widgetId, boundId, person, state, manager, widthOverride
                            )
                        }
                    }
                }
            }.getOrElse {
                // 兜底也要能点，否则用户连重新配置的入口都没有
                buildFallbackViews(context, widgetId, manager, widthOverride)
            }

            // updateAppWidget 本身也可能抛异常，单独兜住
            runCatching { manager.updateAppWidget(widgetId, views) }
        }
    }

    /** 极端情况下的最简视图：只显示名称，点击打开配置界面。 */
    private fun buildFallbackViews(
        context: Context,
        widgetId: Int,
        manager: AppWidgetManager,
        widthOverride: Int? = null,
    ): RemoteViews {
        val layout = if (isWide(manager, widgetId, widthOverride)) {
            R.layout.locker_widget_wide
        } else {
            R.layout.locker_widget
        }
        return RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.widget_person, context.getString(R.string.app_name))
            setViewVisibility(R.id.widget_pulse, View.GONE)
            setViewVisibility(R.id.widget_wearing_label, View.GONE)
            setViewVisibility(R.id.widget_chronometer, View.GONE)
            setViewVisibility(R.id.widget_idle_duration, View.VISIBLE)
            setTextViewText(
                R.id.widget_idle_duration,
                context.getString(R.string.widget_choose_person),
            )
            setViewVisibility(R.id.widget_checkin, View.GONE)
            setOnClickPendingIntent(
                R.id.widget_root,
                configurePendingIntent(context, widgetId),
            )
        }
    }

    private fun buildPersonViews(
        context: Context,
        widgetId: Int,
        personId: Long,
        person: Person,
        state: PersonState,
        manager: AppWidgetManager,
        widthOverride: Int? = null,
    ): RemoteViews {
        val wide = isWide(manager, widgetId, widthOverride)
        val layout = if (wide) R.layout.locker_widget_wide else R.layout.locker_widget
        val views = RemoteViews(context.packageName, layout)

        views.setTextViewText(R.id.widget_person, person.name)

        val wearing = state.activeStartAt != null
        if (wearing) {
            // 模仿 App 主页：卡片换成 primaryContainer，人物名后跟呼吸点与「佩戴中」
            views.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                R.drawable.widget_background_wearing,
            )
            views.setViewVisibility(R.id.widget_pulse, View.VISIBLE)
            views.setViewVisibility(R.id.widget_wearing_label, View.VISIBLE)
            views.setViewVisibility(R.id.widget_chronometer, View.VISIBLE)
            views.setViewVisibility(R.id.widget_idle_duration, View.GONE)
            // Chronometer 的 base 走 elapsedRealtime，不能直接传墙上时钟
            val elapsed = (System.currentTimeMillis() - state.activeStartAt!!)
                .coerceAtLeast(0L)
            views.setChronometer(
                R.id.widget_chronometer,
                SystemClock.elapsedRealtime() - elapsed,
                null,
                true,
            )
        } else {
            views.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                R.drawable.widget_background,
            )
            views.setViewVisibility(R.id.widget_pulse, View.GONE)
            views.setViewVisibility(R.id.widget_wearing_label, View.GONE)
            views.setViewVisibility(R.id.widget_chronometer, View.GONE)
            views.setViewVisibility(R.id.widget_idle_duration, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_idle_duration,
                context.getString(R.string.widget_status_idle),
            )
            views.setTextColor(
                R.id.widget_idle_duration,
                context.getColor(R.color.widget_muted),
            )
        }

        views.setViewVisibility(R.id.widget_checkin, View.VISIBLE)
        views.setTextViewText(
            R.id.widget_checkin,
            context.getString(
                if (state.checkedIn) R.string.widget_checkin_done else R.string.widget_checkin_pending
            ),
        )
        views.setTextColor(
            R.id.widget_checkin,
            context.getColor(
                if (state.checkedIn) {
                    R.color.widget_badge_text_checked
                } else {
                    R.color.widget_badge_text_pending
                }
            ),
        )
        views.setInt(
            R.id.widget_checkin,
            "setBackgroundResource",
            if (state.checkedIn) {
                R.drawable.widget_badge_checked
            } else {
                R.drawable.widget_badge_pending
            },
        )

        // 整卡点击：打开 App 并切到该人物
        views.setOnClickPendingIntent(
            R.id.widget_root,
            openPendingIntent(context, widgetId, personId),
        )
        return views
    }

    /** 未绑定或人物已删除时的占位视图，点击打开配置界面。 */
    private fun buildPlaceholderViews(
        context: Context,
        widgetId: Int,
        manager: AppWidgetManager,
        widthOverride: Int? = null,
        title: Int,
        action: Int,
    ): RemoteViews {
        val layout = if (isWide(manager, widgetId, widthOverride)) {
            R.layout.locker_widget_wide
        } else {
            R.layout.locker_widget
        }
        val views = RemoteViews(context.packageName, layout)
        views.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            R.drawable.widget_background,
        )
        views.setTextViewText(R.id.widget_person, context.getString(title))
        views.setViewVisibility(R.id.widget_pulse, View.GONE)
        views.setViewVisibility(R.id.widget_wearing_label, View.GONE)
        views.setViewVisibility(R.id.widget_chronometer, View.GONE)
        views.setViewVisibility(R.id.widget_idle_duration, View.VISIBLE)
        views.setTextViewText(R.id.widget_idle_duration, context.getString(action))
        views.setTextColor(
            R.id.widget_idle_duration,
            context.getColor(R.color.widget_ink),
        )
        views.setTextViewText(R.id.widget_checkin, "")
        views.setViewVisibility(R.id.widget_checkin, View.GONE)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            configurePendingIntent(context, widgetId),
        )
        return views
    }

    /** 打开配置界面，让用户重新选择人物。 */
    private fun configurePendingIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetConfigActivity::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 按实际宽度选择布局。
     *
     * 必须用 `OPTION_APPWIDGET_MIN_WIDTH`：它反映当前实际宽度，
     * 而 `OPTION_APPWIDGET_MAX_WIDTH` 是可拉伸到的最大宽度（接近屏幕宽），
     * 用它判断会让 2×2 也误判成宽版，数字被挤没。
     */
    private fun isWide(
        manager: AppWidgetManager,
        widgetId: Int,
        widthOverride: Int? = null,
    ): Boolean {
        val minWidth = widthOverride ?: runCatching {
            manager.getAppWidgetOptions(widgetId)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        }.getOrDefault(0)
        // 2×2 约 110dp，4×2 约 250dp 以上；0 表示尚未测量，按紧凑处理
        return minWidth >= 220
    }

    /**
     * 点卡片的 PendingIntent：直接用 getActivity 打开 App 并带人物 ID，
     * 由 MainActivity 消费，避免冷启动时读到旧偏好。
     */
    private fun openPendingIntent(
        context: Context,
        widgetId: Int,
        personId: Long,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SELECT_PERSON_ID, personId)
            // data 必须唯一，否则多个实例会共用同一个 PendingIntent
            data = android.net.Uri.parse("locker://widget/$widgetId")
        }
        return PendingIntent.getActivity(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private data class PersonState(
        val personName: String,
        val activeStartAt: Long?,
        val checkedIn: Boolean,
    )

    companion object {
        const val ACTION_REFRESH = "com.yxsdc.locker.widget.REFRESH"

        /**
         * 小组件协程作用域。
         * SupervisorJob 隔离失败，CoroutineExceptionHandler 兜住未捕获异常，
         * 避免数据库或 RemoteViews 异常把整个进程带崩。
         */
        private val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
                // 小组件刷新失败不影响 App 主流程，静默忽略
            }
        )

        private fun runSafely(block: suspend () -> Unit) {
            scope.launch { runCatching { block() } }
        }

        private fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)?.getAppWidgetIds(
                ComponentName(context, LockerWidgetProvider::class.java)
            ) ?: IntArray(0)

        /** 通知所有实例刷新。应用内数据变化后调用。 */
        fun updateAll(context: Context) {
            if (widgetIds(context).isEmpty()) return
            context.sendBroadcast(
                Intent(context, LockerWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH
                }
            )
        }

        /** 只刷新指定实例（配置保存后）。 */
        fun updateWidget(context: Context, appWidgetId: Int) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            scope.launch {
                runCatching {
                    LockerWidgetProvider().render(context, manager, intArrayOf(appWidgetId))
                }
            }
        }

        /** 人物被删除时清理绑定并刷新。 */
        fun onPersonDeleted(context: Context, personId: Long) {
            WidgetPrefs.clearForPerson(context, personId)
            updateAll(context)
        }
    }
}
