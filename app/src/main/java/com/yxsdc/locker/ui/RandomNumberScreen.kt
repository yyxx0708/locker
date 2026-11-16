package com.yxsdc.locker.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yxsdc.locker.data.SyncExportEstimate
import com.yxsdc.locker.domain.SyncRange
import com.yxsdc.locker.domain.formatEpochDay
import com.yxsdc.locker.ui.components.PopDialog
import com.yxsdc.locker.ui.components.ReportCard
import com.yxsdc.locker.ui.components.ReportCardData
import com.yxsdc.locker.ui.components.decodeQrFromUri
import com.yxsdc.locker.ui.components.generateQrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yxsdc.locker.ui.components.ConfirmDialog
import com.yxsdc.locker.ui.components.EmptyStateHint
import com.yxsdc.locker.ui.components.PhotoSourceButton
import com.yxsdc.locker.ui.theme.lockerCardColors
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RandomNumberScreen(viewModel: LockerViewModel) {
    val currentPerson by viewModel.currentPerson.collectAsStateWithLifecycle()

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000 - (System.currentTimeMillis() % 1_000))
        }
    }
    val clockFormatter = remember {
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss", Locale.CHINA)
    }

    val todayEpochDay = now.toLocalDate().toEpochDay()
    val randomNumber by remember(todayEpochDay) {
        viewModel.observeDailyRandomNumber(todayEpochDay)
    }.collectAsStateWithLifecycle(initialValue = null)
    val todayPhoto by remember(todayEpochDay) {
        viewModel.observeDailyPhoto(todayEpochDay)
    }.collectAsStateWithLifecycle(initialValue = null)
    var pendingReplacement by remember { mutableStateOf<ByteArray?>(null) }
    var showFullScreenNumber by rememberSaveable { mutableStateOf(false) }
    var showSendDialog by remember { mutableStateOf(false) }
    var sendCode by remember { mutableStateOf("") }
    var sendBusy by remember { mutableStateOf(false) }
    var sendIncremental by remember { mutableStateOf(false) }
    var sendIncrementalAvailable by remember { mutableStateOf(false) }
    var sendEstimate by remember { mutableStateOf<SyncExportEstimate?>(null) }
    var lastSendAt by remember { mutableStateOf<Long?>(null) }
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val sendTodayRequested by viewModel.sendTodayRequested.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reportState by remember { mutableStateOf<DailyReportUi?>(null) }
    var scanPayload by remember { mutableStateOf<String?>(null) }
    var scanVerification by remember { mutableStateOf<ReportVerification?>(null) }
    var scanCodeInput by remember { mutableStateOf("") }
    var scanBusy by remember { mutableStateOf(false) }
    var reportBusy by remember { mutableStateOf(false) }

    fun openReport() {
        scope.launch {
            reportBusy = true
            try {
                reportState = viewModel.buildDailyReport()
            } catch (throwable: Throwable) {
                Toast.makeText(
                    context,
                    throwable.message ?: "无法生成日报",
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                reportBusy = false
            }
        }
    }

    /** 从相册挑图 → 本机解码 → 验证签名。 */
    fun verifyScannedText(text: String) {
        scope.launch {
            scanBusy = true
            try {
                scanVerification = viewModel.verifyDailyReport(text, null)
                scanPayload = text
            } catch (throwable: Throwable) {
                Toast.makeText(
                    context,
                    throwable.message ?: "核验失败",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                scanBusy = false
            }
        }
    }

    fun openSendToday() {
        if (currentPerson == null) {
            Toast.makeText(context, "请先选择人物", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            try {
                val identity = viewModel.prepareSyncIdentity()
                sendCode = identity.code
                lastSendAt = identity.lastExportedAt
                val today = LocalDate.now().toEpochDay()
                val lastDay = identity.lastExportedAt?.let {
                    java.time.Instant.ofEpochMilli(it)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .toEpochDay()
                }
                // 上次发送早于今天就提供「只发新数据」选项
                sendIncrementalAvailable = lastDay != null && lastDay < today
                sendIncremental = sendIncrementalAvailable
                showSendDialog = true
            } catch (throwable: Throwable) {
                Toast.makeText(
                    context,
                    throwable.message ?: "无法准备同步数据",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun sendWindow(): Pair<Long, Long> {
        val today = LocalDate.now().toEpochDay()
        return if (sendIncremental) {
            SyncRange.TODAY.incrementalWindow(today, lastSendAt)
        } else {
            today to today
        }
    }

    fun sendToday() {
        scope.launch {
            sendBusy = true
            try {
                val (from, to) = sendWindow()
                val result = viewModel.exportSyncPackageWindow(from, to)
                lastSendAt = System.currentTimeMillis()
                shareSyncPackage(context, result.file)
                showSendDialog = false
            } catch (throwable: Throwable) {
                Toast.makeText(
                    context,
                    throwable.message ?: "打包失败",
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                sendBusy = false
            }
        }
    }

    LaunchedEffect(showSendDialog, sendIncremental) {
        if (!showSendDialog) {
            sendEstimate = null
            return@LaunchedEffect
        }
        val (from, to) = sendWindow()
        sendEstimate = runCatching { viewModel.estimateExport(from, to) }.getOrNull()
    }

    // 桌面快捷方式触发：等当前人物加载完成后再打开发送弹窗
    LaunchedEffect(sendTodayRequested, currentPerson?.id) {
        if (sendTodayRequested && currentPerson != null) {
            viewModel.consumeSendTodayRequest()
            openSendToday()
        }
    }

    val qrImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            scanBusy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    decodeQrFromUri(context, uri)
                } ?: error(
                    "没能识别出二维码。请让对方在「今日日报」里原样截图（不要裁边），" +
                        "并保证整张二维码完整、清晰可见。"
                )
                scanVerification = viewModel.verifyDailyReport(text, null)
                scanPayload = text
            } catch (throwable: Throwable) {
                Toast.makeText(
                    context,
                    throwable.message ?: "核验失败",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                scanBusy = false
            }
        }
    }

    LaunchedEffect(todayEpochDay) {
        viewModel.ensureDailyRandomNumber(todayEpochDay)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "打卡",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "当前时间",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = now.format(clockFormatter),
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "当前人物：${currentPerson?.name ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        Card(
            colors = lockerCardColors(),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (randomNumber != null) {
                        Modifier.clickable(onClickLabel = "全屏显示随机数") {
                            showFullScreenNumber = true
                        }
                    } else {
                        Modifier
                    }
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "5 位随机数",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = randomNumber ?: "—",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
                if (randomNumber != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "点击全屏横屏显示 · 便于拍照核验",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { openReport() },
                enabled = currentPerson != null && !reportBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Text(if (reportBusy) "核验照片中…" else "今日日报")
            }
            OutlinedButton(
                onClick = {
                    qrImageLauncher.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                enabled = !scanBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Text(if (scanBusy) "核验中…" else "核验二维码")
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { openSendToday() },
            enabled = currentPerson != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("发送今日数据")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "仅包含今天的分段、事件、随机数和照片；对方第一次接收需要身份码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = "今日照片",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (todayPhoto == null) {
            EmptyStateHint(text = "今天还没有上传照片")
        } else {
            Text(
                text = "今天已上传照片",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        PhotoSourceButton(
            text = if (todayPhoto == null) "上传今日照片" else "更换今日照片",
            onPhotoReady = { bytes ->
                if (todayPhoto == null) {
                    viewModel.setDailyPhoto(todayEpochDay, bytes)
                } else {
                    pendingReplacement = bytes
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = currentPerson != null,
            primary = true,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "只能上传今天及之前的照片；上传后会显示在月历今天的格子里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showSendDialog) {
        PopDialog(
            onDismissRequest = { if (!sendBusy) showSendDialog = false },
        ) { dismiss ->
            Column(Modifier.padding(24.dp)) {
                Text("发送今日数据", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "只包含今天的分段、事件、随机数和照片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "我的身份码",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sendCode,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("身份码", sendCode)
                            )
                            Toast.makeText(context, "身份码已复制", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("复制")
                    }
                }
                Text(
                    text = "对方第一次接收时需要输入这个码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sendIncrementalAvailable) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "只发上次之后的数据",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "已经发过的记录不重复发送。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = sendIncremental,
                            onCheckedChange = { sendIncremental = it },
                            enabled = !sendBusy,
                        )
                    }
                }
                val (windowFrom, windowTo) = sendWindow()
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("范围：")
                        append(
                            if (windowFrom <= 0) {
                                "全部历史"
                            } else {
                                "${formatEpochDay(windowFrom)} – ${formatEpochDay(windowTo)}"
                            }
                        )
                        sendEstimate?.let { estimate ->
                            append(" · ${estimate.photoCount} 张照片")
                            if (estimate.photoBytes > 0) append(" · ${formatBytes(estimate.photoBytes)}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { sendToday() },
                    enabled = !sendBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text(
                        if (sendBusy) {
                            syncBusyLabel("正在打包", syncProgress)
                        } else {
                            "打包并发送"
                        }
                    )
                }
                TextButton(
                    onClick = { dismiss() },
                    enabled = !sendBusy,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("取消")
                }
            }
        }
    }

    if (showFullScreenNumber) {
        randomNumber?.let { number ->
            FullScreenNumberOverlay(
                number = number,
                onDismiss = { showFullScreenNumber = false },
            )
        }
    }

    pendingReplacement?.let { bytes ->
        ConfirmDialog(
            title = "更换今日照片？",
            message = "今天已经有一张照片，确定用新照片替换吗？",
            confirmText = "替换",
            icon = Icons.Rounded.Refresh,
            onConfirm = { viewModel.setDailyPhoto(todayEpochDay, bytes) },
            onDismiss = { pendingReplacement = null },
        )
    }

    val dayFormatter = remember {
        java.time.format.DateTimeFormatter.ofPattern("M月d日", java.util.Locale.CHINA)
    }

    val report = reportState
    if (report != null) {
        val qr by produceState<ImageBitmap?>(initialValue = null, report.payload) {
            value = withContext(Dispatchers.IO) {
                // 1024 像素给二维码留出更多每模块像素，截屏后也更容易识别
                generateQrBitmap(report.payload, sizePx = 1024)
            }
        }
        // 分享卡在后台合成；照片用当天已上传的缩略图
        val shareCard by produceState<android.graphics.Bitmap?>(null, report.payload) {
            value = withContext(Dispatchers.IO) {
                val photoBitmap = todayPhoto?.jpeg?.let { bytes ->
                    runCatching {
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
                val card = ReportCard.render(
                    ReportCardData(
                        identityName = report.identityName,
                        epochDay = report.report.epochDay,
                        number = report.report.number,
                        wearMinutes = report.report.wearMinutes,
                        eventCount = report.report.eventCount,
                        photoLabel = report.report.photoCheck.label,
                        photoPassed = report.report.photoCheck ==
                            com.yxsdc.locker.data.DailyReport.PhotoCheck.PASS,
                        photoBitmap = photoBitmap,
                        qrPayload = report.payload,
                    )
                )
                photoBitmap?.recycle()
                card
            }
        }
        val shareScope = rememberCoroutineScope()
        var sharing by remember { mutableStateOf(false) }
        PopDialog(onDismissRequest = { reportState = null }) { dismiss ->
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("今日日报", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${report.identityName} · ${
                        LocalDate.ofEpochDay(report.report.epochDay).format(dayFormatter)
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                val qrImage = qr
                if (qrImage != null) {
                    // 不要加圆角裁切：圆角会切掉二维码的静默区，导致对方截图后识别失败。
                    // 二维码位图本身已带 4 个模块的白边，直接白底显示即可。
                    Image(
                        bitmap = qrImage,
                        contentDescription = "日报二维码",
                        modifier = Modifier
                            .size(280.dp)
                            .background(Color.White),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "随机数 ${report.report.number} · 佩戴 ${
                        com.yxsdc.locker.domain.formatDuration(report.report.wearMinutes * 60_000)
                    } · 事件 ${report.report.eventCount} 次",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = report.report.photoCheck.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (report.report.photoCheck) {
                        com.yxsdc.locker.data.DailyReport.PhotoCheck.PASS ->
                            MaterialTheme.colorScheme.primary
                        com.yxsdc.locker.data.DailyReport.PhotoCheck.MISMATCH ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "对方在 Locker 里「扫码核验」即可确认真伪。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { dismiss() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("关闭")
                    }
                    Button(
                        onClick = {
                            val card = shareCard ?: return@Button
                            shareScope.launch {
                                sharing = true
                                try {
                                    val file = withContext(Dispatchers.IO) {
                                        writeReportCard(context, card, report.report.epochDay)
                                    }
                                    // 启动分享面板必须在主线程
                                    shareReportCard(context, file)
                                } catch (throwable: Throwable) {
                                    Toast.makeText(
                                        context,
                                        throwable.message ?: "分享失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } finally {
                                    sharing = false
                                }
                            }
                        },
                        enabled = shareCard != null && !sharing,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text(if (sharing) "正在生成…" else "分享日报卡")
                    }
                }
            }
        }
    }

    val verification = scanVerification
    if (verification != null) {
        PopDialog(
            onDismissRequest = {
                scanVerification = null
                scanPayload = null
            },
        ) { dismiss ->
            Column(Modifier.padding(24.dp)) {
                Text("核验结果", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                val scanned = verification.report
                val identityLabel = verification.identityName
                    ?: (scanned.identityId.take(8) + "…")
                Text("身份：$identityLabel", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "日期：${LocalDate.ofEpochDay(scanned.epochDay).format(dayFormatter)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "随机数：${scanned.number}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Text(
                    text = "佩戴：${
                        com.yxsdc.locker.domain.formatDuration(scanned.wearMinutes * 60_000)
                    } · 事件 ${scanned.eventCount} 次",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "照片核验（对方端）：${scanned.photoCheck.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (scanned.photoCheck) {
                        com.yxsdc.locker.data.DailyReport.PhotoCheck.PASS ->
                            MaterialTheme.colorScheme.primary
                        com.yxsdc.locker.data.DailyReport.PhotoCheck.MISMATCH ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(10.dp))
                if (verification.needsCode) {
                    Text(
                        text = "首次核验需要对方告诉你的身份码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = scanCodeInput,
                        onValueChange = { scanCodeInput = it },
                        label = { Text("验证码") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { dismiss() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text("关闭")
                        }
                        Button(
                            onClick = {
                                val payload = scanPayload ?: return@Button
                                scope.launch {
                                    scanBusy = true
                                    try {
                                        scanVerification = viewModel.verifyDailyReport(
                                            payload,
                                            scanCodeInput,
                                        )
                                    } catch (throwable: Throwable) {
                                        Toast.makeText(
                                            context,
                                            throwable.message ?: "验证失败",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    } finally {
                                        scanBusy = false
                                    }
                                }
                            },
                            enabled = !scanBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(if (scanBusy) "验证中…" else "验证")
                        }
                    }
                } else {
                    Text(
                        text = if (verification.signatureValid) {
                            "✓ 签名有效，日报可信"
                        } else {
                            "✗ 签名不匹配：可能被篡改，或验证码错误"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (verification.signatureValid) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { dismiss() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

/**
 * 全屏横屏展示当日随机数：方便用另一台设备拍摄，交给掌管者核验。
 * 显示期间强制横屏、保持屏幕常亮并隐藏系统栏；点击任意处返回。
 * 提供左右镜像开关：前置摄像头拍摄时画面会左右翻转，开启后数字方向正确。
 */
@Composable
private fun FullScreenNumberOverlay(
    number: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    var mirrored by rememberSaveable { mutableStateOf(FullScreenPreferences.isMirrored(context)) }

    DisposableEffect(view) {
        val previousKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        val controller = ViewCompat.getWindowInsetsController(view)
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            view.keepScreenOn = previousKeepScreenOn
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (previousOrientation != null) {
                activity?.requestedOrientation =
                    if (
                        previousOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
                        previousOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    ) {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    } else {
                        previousOrientation
                    }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "退出全屏随机数",
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val fontSize = with(LocalDensity.current) {
                    minOf(maxWidth / 3.9f, maxHeight / 1.8f).toSp()
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = number,
                        fontSize = fontSize,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 8.sp,
                        color = Color.White,
                        maxLines = 1,
                        // 前置摄像头会把画面左右翻转；镜像后拍到的数字方向才正确
                        modifier = Modifier.graphicsLayer {
                            scaleX = if (mirrored) -1f else 1f
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "点击任意处返回",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.45f),
                    )
                }

                // 镜像开关不影响点击返回：单独消费点击事件
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            mirrored = !mirrored
                            FullScreenPreferences.setMirrored(context, mirrored)
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (mirrored) "镜像已开启" else "镜像",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = mirrored,
                        // 由外层 Row 统一处理点击，避免一次点击触发两次切换
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color.White,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.35f),
                        ),
                    )
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
