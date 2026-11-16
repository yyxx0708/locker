package com.yxsdc.locker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yxsdc.locker.data.BackupJson
import com.yxsdc.locker.data.EventColors
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.data.Person
import com.yxsdc.locker.data.SyncAction
import com.yxsdc.locker.data.SyncChange
import com.yxsdc.locker.data.SyncExportEstimate
import com.yxsdc.locker.data.defaultSelectedSyncKeys
import com.yxsdc.locker.domain.SyncRange
import com.yxsdc.locker.domain.formatEpochDay
import com.yxsdc.locker.domain.isSingleDay
import com.yxsdc.locker.ui.components.ConfirmDialog
import com.yxsdc.locker.ui.components.ConfirmTone
import com.yxsdc.locker.ui.components.EventColorDot
import com.yxsdc.locker.ui.components.EventTypeEditDialog
import com.yxsdc.locker.ui.components.PersonNameDialog
import com.yxsdc.locker.ui.components.PopDialog
import com.yxsdc.locker.ui.theme.ThemeMode
import com.yxsdc.locker.ui.theme.lockerCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: LockerViewModel,
    persons: List<Person>,
    currentPerson: Person?,
    eventTypes: List<EventTypeEntity>,
    segmentCount: Int,
    eventCount: Int,
    snackbarHostState: SnackbarHostState,
    onOpenGuide: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    var pendingImport by remember { mutableStateOf<BackupJson.BackupData?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var addingPerson by remember { mutableStateOf(false) }
    var renamingPerson by remember { mutableStateOf<Person?>(null) }
    var deletingPerson by remember { mutableStateOf<Person?>(null) }
    var addingEventType by remember { mutableStateOf(false) }
    var editingEventType by remember { mutableStateOf<EventTypeEntity?>(null) }
    var deletingEventType by remember { mutableStateOf<EventTypeEntity?>(null) }
    var deletingEventTypeCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(deletingEventType?.typeKey) {
        deletingEventTypeCount = deletingEventType?.let {
            viewModel.countEventsOfType(it.typeKey)
        }
    }
    val defaultEventColor = remember(eventTypes) {
        val usedColors = eventTypes.map { it.colorArgb }.toSet()
        EventColors.palette.firstOrNull { it !in usedColors }
            ?: EventColors.palette.first()
    }

    // ---- 掌管者同步状态 ----
    var exportDialog by remember { mutableStateOf(false) }
    var exportCode by remember { mutableStateOf("") }
    var exportLastAt by remember { mutableStateOf<Long?>(null) }
    var exportRange by remember { mutableStateOf(SyncRangePreferences.read(context)) }
    var exportIncremental by remember { mutableStateOf(false) }
    var exportEstimate by remember { mutableStateOf<SyncExportEstimate?>(null) }
    var changeCodeConfirm by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<SyncImportPreview?>(null) }
    var importDiff by remember { mutableStateOf<SyncImportDiffResult?>(null) }
    var importCode by remember { mutableStateOf("") }
    var importCodeError by remember { mutableStateOf<String?>(null) }
    var importTargetNew by remember { mutableStateOf(true) }
    var importTargetExistingId by remember { mutableStateOf<Long?>(null) }
    var checkedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteApply by remember { mutableStateOf(false) }
    var syncBusy by remember { mutableStateOf(false) }
    // 导入备份后的小组件重新绑定候选
    var widgetRebindPlan by remember {
        mutableStateOf<com.yxsdc.locker.widget.WidgetRebindPlan?>(null)
    }
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    var watermarkEnabled by remember { mutableStateOf(PhotoPreferences.watermarkEnabled(context)) }
    var checkNumberEnabled by remember {
        mutableStateOf(PhotoPreferences.checkNumberAfterUpload(context))
    }

    fun clearImportState() {
        importPreview = null
        importDiff = null
        importCode = ""
        importCodeError = null
        importTargetNew = true
        importTargetExistingId = null
        checkedKeys = emptySet()
        confirmDeleteApply = false
        viewModel.cancelPendingImport()
    }

    fun startImportFrom(uri: Uri) {
        scope.launch {
            syncBusy = true
            try {
                val stream = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                } ?: error("无法读取文件")
                val preview = viewModel.startImport(stream)
                importPreview = preview
                importDiff = null
                importCode = ""
                importCodeError = null
                importTargetNew = true
                importTargetExistingId = null
            } catch (throwable: Throwable) {
                snackbarHostState.showSnackbar(throwable.message ?: "同步包读取失败")
            } finally {
                syncBusy = false
            }
        }
    }

    fun openExportDialog() {
        scope.launch {
            try {
                val identity = viewModel.prepareSyncIdentity()
                exportCode = identity.code
                exportLastAt = identity.lastExportedAt
                exportDialog = true
            } catch (throwable: Throwable) {
                snackbarHostState.showSnackbar(throwable.message ?: "操作失败")
            }
        }
    }

    fun exportWindow(): Pair<Long, Long> {
        val today = java.time.LocalDate.now().toEpochDay()
        return if (exportIncremental) {
            exportRange.incrementalWindow(today, exportLastAt)
        } else {
            exportRange.windowFor(today)
        }
    }

    LaunchedEffect(exportDialog, exportRange, exportIncremental, exportLastAt) {
        if (!exportDialog) {
            exportEstimate = null
            return@LaunchedEffect
        }
        val (from, to) = exportWindow()
        exportEstimate = runCatching { viewModel.estimateExport(from, to) }.getOrNull()
    }

    fun sendSyncPackage() {
        scope.launch {
            syncBusy = true
            try {
                val (from, to) = exportWindow()
                val result = viewModel.exportSyncPackageWindow(from, to)
                shareSyncPackage(context, result.file)
                exportLastAt = System.currentTimeMillis()
                exportDialog = false
                snackbarHostState.showSnackbar(
                    "已生成同步包 · ${result.photoCount} 张照片，范围 ${result.dayCount} 天"
                )
            } catch (throwable: Throwable) {
                snackbarHostState.showSnackbar(throwable.message ?: "打包失败")
            } finally {
                syncBusy = false
            }
        }
    }

    fun verifyImport() {
        val preview = importPreview ?: return
        scope.launch {
            syncBusy = true
            try {
                val targetId = when {
                    preview.boundPerson != null -> preview.boundPerson.id
                    importTargetNew -> null
                    else -> importTargetExistingId
                }
                val diff = viewModel.verifyAndDiff(
                    code = importCode.takeIf { preview.needsVerification },
                    targetPersonId = targetId,
                )
                importDiff = diff
                importCodeError = null
                // 删除本机数据风险高，默认只勾选新增与修改
                checkedKeys = defaultSelectedSyncKeys(diff.changes)
            } catch (throwable: Throwable) {
                val message = throwable.message ?: "验证失败"
                if (preview.needsVerification && message.contains("验证码")) {
                    importCodeError = message
                } else {
                    snackbarHostState.showSnackbar(message)
                }
            } finally {
                syncBusy = false
            }
        }
    }

    fun applyImport() {
        val diff = importDiff ?: return
        scope.launch {
            syncBusy = true
            try {
                val result = viewModel.applyPendingImport(checkedKeys)
                val parts = listOfNotNull(
                    result.added.takeIf { it > 0 }?.let { "新增 $it" },
                    result.modified.takeIf { it > 0 }?.let { "修改 $it" },
                    result.removed.takeIf { it > 0 }?.let { "删除 $it" },
                )
                var message = "已同步 · ${diff.personName}"
                if (parts.isNotEmpty()) message += "（${parts.joinToString("、")}）"
                if (result.skippedNumberDays.isNotEmpty()) {
                    message += "；${result.skippedNumberDays.joinToString("、")} 随机数冲突未导入"
                }
                clearImportState()
                snackbarHostState.showSnackbar(message)
            } catch (throwable: Throwable) {
                snackbarHostState.showSnackbar(throwable.message ?: "同步失败")
            } finally {
                syncBusy = false
            }
        }
    }

    val sharedZip by viewModel.pendingSharedZip.collectAsStateWithLifecycle()
    LaunchedEffect(sharedZip) {
        val uri = sharedZip ?: return@LaunchedEffect
        viewModel.consumeSharedZip()
        startImportFrom(uri)
    }

    val syncPackageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) startImportFrom(uri)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) { viewModel.buildBackupText() }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                        } ?: error("无法写入备份文件")
                    }
                    snackbarHostState.showSnackbar("备份已导出")
                } catch (throwable: Throwable) {
                    snackbarHostState.showSnackbar(throwable.message ?: "导出失败")
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            ?: error("无法读取备份文件")
                    }
                    require(text.length <= 20_000_000) { "备份文件过大" }
                    val data = withContext(Dispatchers.IO) { BackupJson.decode(text) }
                    pendingImport = data
                    showImportDialog = true
                } catch (throwable: Throwable) {
                    snackbarHostState.showSnackbar(throwable.message ?: "导入失败")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        Card(
            colors = lockerCardColors(),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(tween(250)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("人物", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { addingPerson = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建人物")
                    }
                }
                Text(
                    text = "每个人物独立计时，可以同时佩戴。切换人物后，主页和统计都会跟随。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                persons.forEachIndexed { index, person ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectPerson(person) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = person.id == currentPerson?.id,
                            onClick = { viewModel.selectPerson(person) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = person.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (person.id == currentPerson?.id) {
                                Text(
                                    text = "当前人物",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        IconButton(onClick = { renamingPerson = person }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "改名")
                        }
                        IconButton(
                            onClick = { deletingPerson = person },
                            enabled = persons.size > 1,
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }

        Card(
            colors = lockerCardColors(),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(tween(250)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("事件类型", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { addingEventType = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建事件类型")
                    }
                }
                Text(
                    text = "自定义名称和统计颜色；内置类型可改名改色，但不能删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                eventTypes.forEachIndexed { index, type ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EventColorDot(colorArgb = type.colorArgb, size = 12.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = type.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (type.builtIn) {
                                Text(
                                    text = "内置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { editingEventType = type }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "编辑事件类型")
                        }
                        if (!type.builtIn) {
                            IconButton(onClick = { deletingEventType = type }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "删除事件类型")
                            }
                        }
                    }
                }
            }
        }

        Card(
            colors = lockerCardColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("外观", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text(
                    text = "当前：${themeMode.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            colors = lockerCardColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("数据", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "所有人物、事件类型、分段、事件和每日随机数都只保存在本机；" +
                        "每日随机数会进入 JSON 备份。" +
                        "每日照片只保存在本机，且不包含在 JSON 备份中。" +
                        "换手机或重装前，请先导出备份。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        val stamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                        exportLauncher.launch("locker-backup-$stamp.json")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导出备份")
                }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "application/json",
                                "text/plain",
                                "application/octet-stream",
                                "*/*",
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导入备份")
                }
                HorizontalDivider()
                Text("掌管者同步", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "把当前人物所选范围内的分段、事件、随机数和照片打包加密发送给对方；" +
                        "接收时需要验证对方告诉你的身份码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { openExportDialog() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("发送给掌管者")
                }
                OutlinedButton(
                    onClick = {
                        syncPackageLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                                "*/*",
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("接收掌管者数据")
                }
                Text(
                    text = "如果文件选择器里找不到对方发来的文件（QQ 文件常存在其私有目录），" +
                        "可在 QQ 聊天里长按文件，选择「用其他应用打开」→ Locker。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "上传照片时烧录时间水印",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "在照片右下角烧录上传时间，便于对方核验。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = watermarkEnabled,
                        onCheckedChange = {
                            watermarkEnabled = it
                            PhotoPreferences.setWatermarkEnabled(context, it)
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "上传照片后检测随机数",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "上传完成后自动识别照片中的号码，与当日随机数比对并提示结果。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = checkNumberEnabled,
                        onCheckedChange = {
                            checkNumberEnabled = it
                            PhotoPreferences.setCheckNumberAfterUpload(context, it)
                        },
                    )
                }
                Text(
                    text = "当前人物：${currentPerson?.name ?: "—"} · " +
                        "$segmentCount 个分段，$eventCount 条事件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            colors = lockerCardColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = listOf("Locker", context.appVersionName())
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "本地离线的多人物佩戴计时与事件记录工具。无网络权限、无账号、无云端同步。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onOpenGuide,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("使用指南")
                }
                HorizontalDivider()
                Text(
                    text = "开发与维护",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "yelans",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "QQ 3781994974",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("QQ", "3781994974")
                            )
                            scope.launch { snackbarHostState.showSnackbar("QQ 号已复制") }
                        },
                    ) {
                        Text("复制 QQ")
                    }
                }
            }
        }
    }

    if (exportDialog) {
        PopDialog(onDismissRequest = { exportDialog = false }) { dismiss ->
            Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text("发送给掌管者", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "我的身份码",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = exportCode,
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
                                ClipData.newPlainText("身份码", exportCode)
                            )
                            scope.launch { snackbarHostState.showSnackbar("身份码已复制") }
                        },
                    ) {
                        Text("复制")
                    }
                }
                Text(
                    text = "对方首次接收时需要输入这个码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "数据范围",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncRange.entries.forEach { range ->
                        FilterChip(
                            selected = exportRange == range,
                            onClick = {
                                exportRange = range
                                SyncRangePreferences.write(context, range)
                            },
                            label = { Text(range.label) },
                        )
                    }
                }
                if (exportLastAt != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "只发上次发送之后的数据",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "已同步过的记录不重复发送，对方只比对新数据。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = exportIncremental,
                            onCheckedChange = { exportIncremental = it },
                        )
                    }
                }
                val (windowFrom, windowTo) = exportWindow()
                val today = java.time.LocalDate.now().toEpochDay()
                val windowLabel = when {
                    windowFrom <= 0 -> "全部历史"
                    (windowFrom to windowTo).isSingleDay(today) -> formatEpochDay(windowTo)
                    else -> "${formatEpochDay(windowFrom)} – ${formatEpochDay(windowTo)}"
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("范围：$windowLabel")
                        exportEstimate?.let { estimate ->
                            append(" · ${estimate.photoCount} 张照片")
                            if (estimate.photoBytes > 0) {
                                append(" · 约 ${formatBytes(estimate.photoBytes)}")
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                exportLastAt?.let { last ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "上次发送：${formatSyncTime(last)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { sendSyncPackage() },
                    enabled = !syncBusy && currentPerson != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text(if (syncBusy) syncBusyLabel("正在打包", syncProgress) else "打包并发送")
                }
                TextButton(
                    onClick = { changeCodeConfirm = true },
                    enabled = !syncBusy,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("更换验证码", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (changeCodeConfirm) {
        ConfirmDialog(
            title = "更换验证码？",
            message = "旧验证码立即失效，对方下次接收数据时需要重新验证。",
            confirmText = "更换",
            icon = Icons.Rounded.Refresh,
            tone = ConfirmTone.Danger,
            onConfirm = {
                scope.launch {
                    try {
                        exportCode = viewModel.regenerateSyncCode().code
                        snackbarHostState.showSnackbar("验证码已更换")
                    } catch (throwable: Throwable) {
                        snackbarHostState.showSnackbar(throwable.message ?: "操作失败")
                    }
                }
            },
            onDismiss = { changeCodeConfirm = false },
        )
    }

    val syncPreview = importPreview
    if (syncPreview != null && importDiff == null) {
        PopDialog(onDismissRequest = { clearImportState() }) { dismiss ->
            Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text("接收掌管者数据", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "识别到身份：${syncPreview.manifest.identityName}",
                    style = MaterialTheme.typography.titleMedium,
                )
                val ageHours = (System.currentTimeMillis() - syncPreview.manifest.exportedAt) / 3_600_000L
                if (ageHours >= 26) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "这份数据是 ${ageHours / 24.coerceAtLeast(1)} 天前打包的",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (syncPreview.alreadyImported) {
                    Text(
                        text = "这份包与上次成功导入的完全相同，再导入不会有变化。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "数据范围：${
                        syncWindowLabel(
                            syncPreview.manifest.fromEpochDay,
                            syncPreview.manifest.toEpochDay,
                        )
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                syncPreview.boundPerson?.let { person ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "已绑定人物「${person.name}」，将直接比对差异。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (syncPreview.needsVerification) {
                    Spacer(Modifier.height(12.dp))
                    if (syncPreview.codeChanged) {
                        Text(
                            text = "对方更换了验证码，需要重新验证。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = importCode,
                        onValueChange = {
                            importCode = it
                            importCodeError = null
                        },
                        label = { Text("验证码") },
                        singleLine = true,
                        isError = importCodeError != null,
                        supportingText = importCodeError?.let { { Text(it) } },
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (syncPreview.boundPerson == null && syncPreview.sameNamePersons.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "存在同名人物，请选择数据去向：",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    syncPreview.sameNamePersons.forEach { person ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    importTargetNew = false
                                    importTargetExistingId = person.id
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = !importTargetNew && importTargetExistingId == person.id,
                                onClick = {
                                    importTargetNew = false
                                    importTargetExistingId = person.id
                                },
                            )
                            Text("归入现有「${person.name}」")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { importTargetNew = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = importTargetNew,
                            onClick = { importTargetNew = true },
                        )
                        Text("新建人物「${syncPreview.manifest.identityName}」")
                    }
                    if (!importTargetNew) {
                        Text(
                            text = "注意：会以对方数据为准，覆盖该人物在数据范围内的现有记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { dismiss() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = { verifyImport() },
                        enabled = !syncBusy,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text(if (syncBusy) syncBusyLabel("正在校验", syncProgress) else "验证并比对")
                    }
                }
            }
        }
    }

    val syncDiff = importDiff
    if (syncDiff != null) {
        val removalChanges = syncDiff.changes.filter { it.action == SyncAction.REMOVE }
        val otherChanges = syncDiff.changes.filter { it.action != SyncAction.REMOVE }
        val selectedRemovalCount = removalChanges.count { it.key in checkedKeys }
        PopDialog(onDismissRequest = { clearImportState() }) { dismiss ->
            Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp)) {
                Text("同步预览", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (syncDiff.changes.isEmpty()) {
                        "没有差异，数据已是最新。"
                    } else {
                        "与「${syncDiff.personName}」有 ${syncDiff.changes.size} 处不同，勾选要应用的内容。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (syncDiff.changes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        otherChanges.forEach { change ->
                            SyncChangeRow(
                                change = change,
                                checked = change.key in checkedKeys,
                            ) { checked ->
                                checkedKeys = if (checked) {
                                    checkedKeys + change.key
                                } else {
                                    checkedKeys - change.key
                                }
                            }
                        }
                        if (removalChanges.isNotEmpty()) {
                            if (otherChanges.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider()
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "以下 ${removalChanges.size} 项会删除本机数据，默认不勾选",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            removalChanges.forEach { change ->
                                SyncChangeRow(
                                    change = change,
                                    checked = change.key in checkedKeys,
                                ) { checked ->
                                    checkedKeys = if (checked) {
                                        checkedKeys + change.key
                                    } else {
                                        checkedKeys - change.key
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { dismiss() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("取消")
                    }
                    if (syncDiff.changes.isEmpty()) {
                        Button(
                            onClick = { clearImportState() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text("关闭")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (selectedRemovalCount > 0) {
                                    confirmDeleteApply = true
                                } else {
                                    applyImport()
                                }
                            },
                            enabled = !syncBusy && checkedKeys.isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(
                                when {
                                    syncBusy -> syncBusyLabel("正在应用", syncProgress)
                                    selectedRemovalCount > 0 ->
                                        "应用所选（${checkedKeys.size}，删除 $selectedRemovalCount）"

                                    else -> "应用所选（${checkedKeys.size}）"
                                }
                            )
                        }
                    }
                }
            }
        }
        if (confirmDeleteApply) {
            ConfirmDialog(
                title = "将删除本机数据？",
                message = "所选内容包含 $selectedRemovalCount 条删除操作，" +
                    "会从本机移除对应记录，且无法撤销。",
                confirmText = "继续应用",
                icon = Icons.Rounded.Warning,
                tone = ConfirmTone.Danger,
                onConfirm = { applyImport() },
                onDismiss = { confirmDeleteApply = false },
            )
        }
    }

    if (addingPerson) {
        PersonNameDialog(
            title = "新建人物",
            initialName = "",
            takenNames = persons.map { it.name }.toSet(),
            onDismiss = { addingPerson = false },
            onSave = { name ->
                addingPerson = false
                viewModel.addPerson(name)
            },
        )
    }

    renamingPerson?.let { person ->
        PersonNameDialog(
            title = "给「${person.name}」改名",
            initialName = person.name,
            takenNames = persons.filter { it.id != person.id }.map { it.name }.toSet(),
            onDismiss = { renamingPerson = null },
            onSave = { name ->
                renamingPerson = null
                viewModel.renamePerson(person, name)
            },
        )
    }

    deletingPerson?.let { person ->
        ConfirmDialog(
            title = "删除「${person.name}」？",
            message = "该人物名下的所有分段和事件将一并删除，且无法恢复。",
            confirmText = "删除",
            icon = Icons.Rounded.Delete,
            tone = ConfirmTone.Danger,
            onConfirm = { viewModel.deletePerson(person) },
            onDismiss = { deletingPerson = null },
        )
    }

    if (addingEventType) {
        EventTypeEditDialog(
            title = "新建事件类型",
            initialName = "",
            initialColorArgb = defaultEventColor,
            takenNames = eventTypes.map { it.name }.toSet(),
            onDismiss = { addingEventType = false },
            onSave = { name, colorArgb ->
                addingEventType = false
                viewModel.addEventType(name, colorArgb)
            },
        )
    }

    editingEventType?.let { type ->
        EventTypeEditDialog(
            title = "编辑「${type.name}」",
            initialName = type.name,
            initialColorArgb = type.colorArgb,
            takenNames = eventTypes.filter { it.id != type.id }.map { it.name }.toSet(),
            onDismiss = { editingEventType = null },
            onSave = { name, colorArgb ->
                editingEventType = null
                viewModel.updateEventType(type, name, colorArgb)
            },
        )
    }

    deletingEventType?.let { type ->
        val count = deletingEventTypeCount
        ConfirmDialog(
            title = "删除「${type.name}」？",
            message = if (count != null) {
                "该类型名下有 $count 条事件（所有人物），将一并删除，且无法恢复。"
            } else {
                "该事件类型名下的全部事件记录（所有人物）将一并删除，且无法恢复。"
            },
            confirmText = "删除",
            icon = Icons.Rounded.Delete,
            tone = ConfirmTone.Danger,
            onConfirm = { viewModel.deleteEventType(type) },
            onDismiss = { deletingEventType = null },
        )
    }

    val data = pendingImport
    if (showImportDialog && data != null) {
        ConfirmDialog(
            title = "替换全部数据？",
            message = "备份包含 ${data.persons.size} 个人物、${data.eventTypes.size} 种事件类型、" +
                "${data.segments.size} 个分段、${data.events.size} 条事件、" +
                "${data.dailyRandomNumbers.size} 条每日随机数。" +
                "导入会覆盖当前所有数据并清空每日照片，且无法撤销。",
            confirmText = "覆盖导入",
            icon = Icons.Rounded.Warning,
            tone = ConfirmTone.Danger,
            onConfirm = {
                scope.launch {
                    // 导入与后续的小组件恢复分开捕获：导入已提交后再失败，
                    // 不能误报「导入失败」，否则用户会以为数据没被替换
                    val imported = runCatching {
                        withContext(Dispatchers.IO) { viewModel.importBackup(data) }
                    }
                    if (imported.isFailure) {
                        snackbarHostState.showSnackbar(
                            imported.exceptionOrNull()?.message ?: "导入失败"
                        )
                        return@launch
                    }
                    pendingImport = null
                    snackbarHostState.showSnackbar("导入完成")
                    // 导入会重建人物，小组件绑定需要按名称确认恢复
                    widgetRebindPlan = runCatching {
                        withContext(Dispatchers.IO) {
                            com.yxsdc.locker.widget.WidgetRebind.plan(context)
                        }
                    }.getOrNull()
                }
            },
            onDismiss = { showImportDialog = false },
        )
    }

    val rebindPlan = widgetRebindPlan
    if (rebindPlan != null && !rebindPlan.isEmpty) {
        PopDialog(onDismissRequest = { widgetRebindPlan = null }) { dismiss ->
            Column(Modifier.padding(24.dp)) {
                Text("桌面小组件", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "导入备份后人物编号会变化，以下小组件可以按名称重新绑定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                rebindPlan.candidates.forEach { candidate ->
                    Text(
                        text = "· 「${candidate.personName}」的小组件",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (rebindPlan.failures.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "无法自动恢复",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    rebindPlan.failures.forEach { failure ->
                        Text(
                            text = "· 「${failure.personName}」的小组件（人物已不存在）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { widgetRebindPlan = null },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("跳过")
                    }
                    if (rebindPlan.candidates.isNotEmpty()) {
                        Button(
                            onClick = {
                                val candidates = rebindPlan.candidates
                                widgetRebindPlan = null
                                scope.launch {
                                    // 改绑是多次偏好写入，用 NonCancellable 保证中途离开页面也能写完
                                    withContext(Dispatchers.IO + NonCancellable) {
                                        com.yxsdc.locker.widget.WidgetRebind
                                            .applyRebind(context, candidates)
                                    }
                                    snackbarHostState.showSnackbar("小组件已重新绑定")
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text("全部重新绑定")
                        }
                    }
                }
            }
        }
    }
}

private fun formatSyncTime(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(
            java.time.format.DateTimeFormatter.ofPattern(
                "M月d日 HH:mm",
                java.util.Locale.CHINA,
            )
        )

private fun syncWindowLabel(fromEpochDay: Long, toEpochDay: Long): String =
    if (fromEpochDay <= 0) {
        "全部历史"
    } else {
        val formatter = java.time.format.DateTimeFormatter.ofPattern(
            "M月d日",
            java.util.Locale.CHINA,
        )
        val from = java.time.LocalDate.ofEpochDay(fromEpochDay).format(formatter)
        val to = java.time.LocalDate.ofEpochDay(toEpochDay).format(formatter)
        "$from – $to"
    }

@Composable
private fun SyncChangeRow(
    change: SyncChange,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val isRemoval = change.action == SyncAction.REMOVE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = change.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isRemoval) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = change.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (isRemoval) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
