package com.yxsdc.locker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yxsdc.locker.data.BackupJson
import com.yxsdc.locker.data.EventColors
import com.yxsdc.locker.data.EventRecord
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.data.Person
import com.yxsdc.locker.data.WearSegment
import com.yxsdc.locker.ui.components.ConfirmDialog
import com.yxsdc.locker.ui.components.ConfirmTone
import com.yxsdc.locker.ui.components.EventColorDot
import com.yxsdc.locker.ui.components.EventTypeEditDialog
import com.yxsdc.locker.ui.components.PersonNameDialog
import com.yxsdc.locker.ui.theme.ThemeMode
import com.yxsdc.locker.ui.theme.lockerCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    viewModel: LockerViewModel,
    persons: List<Person>,
    currentPerson: Person?,
    eventTypes: List<EventTypeEntity>,
    segments: List<WearSegment>,
    events: List<EventRecord>,
    snackbarHostState: SnackbarHostState,
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

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) { viewModel.buildBackupText() }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(text.toByteArray(Charsets.UTF_8))
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
                Text(
                    text = "当前人物：${currentPerson?.name ?: "—"} · " +
                        "${segments.size} 个分段，${events.size} 条事件",
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
                Text("Locker 1.9", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "本地离线的多人物佩戴计时与事件记录工具。无网络权限、无账号、无云端同步。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    try {
                        withContext(Dispatchers.IO) { viewModel.importBackup(data) }
                        pendingImport = null
                        snackbarHostState.showSnackbar("导入完成")
                    } catch (throwable: Throwable) {
                        snackbarHostState.showSnackbar(throwable.message ?: "导入失败")
                    }
                }
            },
            onDismiss = { showImportDialog = false },
        )
    }
}
