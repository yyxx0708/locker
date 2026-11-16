package com.yxsdc.locker.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.yxsdc.locker.MainActivity
import com.yxsdc.locker.R
import com.yxsdc.locker.data.AppDatabase
import com.yxsdc.locker.data.Person
import com.yxsdc.locker.ui.PersonPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 小组件配置界面：为某个实例选择绑定的人物。
 *
 * 用普通 Activity + 代码构建界面，避免为一个简单的单选列表引入 Compose。
 * 保存后写入绑定并立即刷新该实例；取消则返回 RESULT_CANCELED，桌面不会留下空小组件。
 */
class WidgetConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedPersonId: Long? = null
    private var selectedName: String = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var contentHost: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 默认结果：用户中途返回即取消添加
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // 旋转后恢复用户已选的人物
        savedInstanceState?.let { state ->
            state.getLong(KEY_SELECTED_ID, -1L).takeIf { it > 0 }?.let {
                selectedPersonId = it
                selectedName = state.getString(KEY_SELECTED_NAME).orEmpty()
            }
        }

        contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            setBackgroundColor(if (isDark()) 0xFF0D0D0D.toInt() else 0xFFF9F9F7.toInt())
        }
        setContentView(contentHost)
        showLoading()
        loadPersons()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_SELECTED_ID, selectedPersonId ?: -1L)
        outState.putString(KEY_SELECTED_NAME, selectedName)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun showLoading() {
        contentHost.removeAllViews()
        contentHost.gravity = Gravity.CENTER
        contentHost.addView(ProgressBar(this))
    }

    /** 数据库查询放到 IO 线程，避免配置界面白屏或 ANR。 */
    private fun loadPersons() {
        scope.launch {
            val persons = withContext(Dispatchers.IO) {
                runCatching {
                    AppDatabase.get(this@WidgetConfigActivity).lockerDao().getPersons()
                }.getOrDefault(emptyList())
            }
            if (isFinishing || isDestroyed) return@launch
            val current = selectedPersonId
                ?: WidgetPrefs.boundPersonId(this@WidgetConfigActivity, appWidgetId)
                ?: PersonPreferences(this@WidgetConfigActivity).selectedPersonId
            contentHost.gravity = Gravity.NO_GRAVITY
            contentHost.removeAllViews()
            buildContent(persons, current)
        }
    }

    private fun buildContent(persons: List<Person>, preselected: Long?) {
        val ink = if (isDark()) 0xFFF0EFEC.toInt() else 0xFF141412.toInt()
        val muted = if (isDark()) 0xFF96948C.toInt() else 0xFF6E6C66.toInt()

        contentHost.addView(TextView(this).apply {
            text = getString(R.string.widget_config_title)
            textSize = 22f
            setTextColor(ink)
        })
        contentHost.addView(TextView(this).apply {
            text = getString(R.string.widget_config_hint)
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(6), 0, dp(12))
        })

        if (persons.isEmpty()) {
            contentHost.addView(TextView(this).apply {
                text = getString(R.string.widget_config_no_person)
                textSize = 16f
                setTextColor(ink)
            })
            contentHost.addView(TextView(this).apply {
                text = getString(R.string.widget_config_no_person_hint)
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(6), 0, dp(16))
            })
            contentHost.addView(Button(this).apply {
                text = getString(R.string.widget_config_open_app)
                setOnClickListener {
                    startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java))
                    // 不创建小组件，用户建好人物后可重新添加
                    finish()
                }
            })
            return
        }

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)

        persons.forEach { person ->
            val inUse = WidgetPrefs.isPersonBound(this, person.id, excludeWidgetId = appWidgetId)
            val radio = RadioButton(this).apply {
                text = if (inUse) {
                    "${person.name}  ·  ${getString(R.string.widget_config_in_use)}"
                } else {
                    person.name
                }
                textSize = 16f
                setTextColor(if (inUse) muted else ink)
                isEnabled = !inUse
                isChecked = !inUse && person.id == preselected
                tag = person
                setOnClickListener {
                    selectedPersonId = person.id
                    selectedName = person.name
                    syncRadios(list)
                }
            }
            if (radio.isChecked) {
                selectedPersonId = person.id
                selectedName = person.name
            }
            list.addView(
                radio,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        contentHost.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(Button(this).apply {
            text = getString(R.string.widget_config_cancel)
            setOnClickListener { finish() }
        })
        val confirm = Button(this).apply {
            text = getString(R.string.widget_config_confirm)
            isEnabled = selectedPersonId != null
            setOnClickListener {
                val personId = selectedPersonId ?: return@setOnClickListener
                save(personId, selectedName)
            }
        }
        actions.addView(confirm)
        contentHost.addView(actions)
    }

    private fun syncRadios(list: LinearLayout) {
        for (index in 0 until list.childCount) {
            val radio = list.getChildAt(index) as? RadioButton ?: continue
            val person = radio.tag as? Person ?: continue
            radio.isChecked = person.id == selectedPersonId
        }
    }

    private fun save(personId: Long, personName: String) {
        WidgetPrefs.bind(this, appWidgetId, personId, personName)
        LockerWidgetProvider.updateWidget(this, appWidgetId)
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        finish()
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_SELECTED_ID = "selected_person_id"
        const val KEY_SELECTED_NAME = "selected_person_name"
    }
}
