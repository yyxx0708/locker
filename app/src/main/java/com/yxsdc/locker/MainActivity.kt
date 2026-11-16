package com.yxsdc.locker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yxsdc.locker.ui.LockerRoot
import com.yxsdc.locker.ui.LockerViewModel
import com.yxsdc.locker.ui.theme.LockerTheme
import com.yxsdc.locker.widget.LockerWidgetProvider

class MainActivity : ComponentActivity() {
    private val viewModel: LockerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShortcutIntent(intent)
        handleShareIntent(intent)
        handleWidgetPersonIntent(intent)
        // 打开 App 时同步一次小组件，避免隔夜后「今日」数据与随机数过期
        LockerWidgetProvider.updateAll(this)
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            LockerTheme(themeMode) {
                LockerRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
        handleShareIntent(intent)
        handleWidgetPersonIntent(intent)
    }

    /**
     * 从桌面小组件打开：把 App 内当前人物切到该实例绑定的人物。
     * 在 Activity 里落盘，保证 ViewModel 初始化时读到的就是新值。
     */
    private fun handleWidgetPersonIntent(intent: Intent?) {
        if (intent == null) return
        val personId = intent.getLongExtra(EXTRA_SELECT_PERSON_ID, -1L)
        if (personId <= 0) return
        // 消费掉，避免旋转或重建时重复触发
        intent.removeExtra(EXTRA_SELECT_PERSON_ID)
        viewModel.selectPersonById(personId)
    }

    /**
     * 桌面长按快捷方式「发送今日数据」：转到打卡页并打开发送弹窗。
     * 处理后把 action 改回 MAIN，避免 Activity 重建时重复触发。
     */
    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action != ACTION_SEND_TODAY) return
        intent.action = Intent.ACTION_MAIN
        viewModel.requestSendToday()
    }

    /**
     * 接收 QQ 等应用发来的 zip：
     * 「分享给 Locker」(ACTION_SEND) 或「用其他应用打开」(ACTION_VIEW) 都会进入同步导入流程。
     */
    private fun handleShareIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            viewModel.onSharedZip(uri)
        }
    }

    companion object {
        const val ACTION_SEND_TODAY = "com.yxsdc.locker.action.SEND_TODAY"
        const val EXTRA_SELECT_PERSON_ID = "com.yxsdc.locker.extra.SELECT_PERSON_ID"
    }
}
