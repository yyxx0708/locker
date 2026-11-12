package com.yxsdc.locker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yxsdc.locker.data.EventColors
import com.yxsdc.locker.data.EventRecord
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.data.WearSegment
import com.yxsdc.locker.domain.formatDateCompact
import com.yxsdc.locker.domain.formatTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeRow(
    label: String,
    value: Long,
    onChange: (Long) -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onChange(System.currentTimeMillis()) }) {
                Text("现在")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.weight(1.3f),
            ) {
                Text(formatDateCompact(value), maxLines = 1)
            }
            OutlinedButton(
                onClick = { showTimePicker = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(formatTime(value), maxLines = 1)
            }
        }
    }

    if (showDatePicker) {
        val current = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = current.toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = datePickerState.selectedDateMillis
                        if (selected != null) {
                            val date = Instant.ofEpochMilli(selected)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            onChange(
                                date.atTime(current.hour, current.minute)
                                    .atZone(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                            )
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("取消")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val current = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())
        val timePickerState = rememberTimePickerState(
            initialHour = current.hour,
            initialMinute = current.minute,
            is24Hour = true,
        )
        PopDialog(
            onDismissRequest = { showTimePicker = false },
            maxWidth = 400.dp,
        ) { dismiss ->
            Column(
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "选择时间",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                TimePicker(state = timePickerState)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { dismiss() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("取消", maxLines = 1)
                    }
                    Button(
                        onClick = {
                            if (dismiss()) {
                                onChange(
                                    current.toLocalDate()
                                        .atTime(timePickerState.hour, timePickerState.minute)
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant()
                                        .toEpochMilli()
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("确定", maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEditDialog(
    initial: EventRecord?,
    eventTypes: List<EventTypeEntity>,
    initialTypeKey: String? = null,
    onDismiss: () -> Unit,
    onSave: (String, Long, String?) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var typeKey by remember(initial?.id, initialTypeKey) {
        mutableStateOf(
            initial?.type
                ?: initialTypeKey
                ?: eventTypes.firstOrNull()?.typeKey.orEmpty()
        )
    }
    var occurredAt by remember(initial?.id) {
        mutableLongStateOf(initial?.occurredAt ?: System.currentTimeMillis())
    }
    var note by remember(initial?.id) {
        mutableStateOf(initial?.note ?: "")
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val deleteAction: (@Composable () -> Unit)? = if (onDelete != null) {
        {
            TextButton(onClick = { showDeleteConfirm = true }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        null
    }

    FormDialog(
        title = if (initial == null) "记录事件" else "编辑事件",
        confirmEnabled = typeKey.isNotEmpty(),
        onConfirm = { onSave(typeKey, occurredAt, note.ifBlank { null }) },
        onDismiss = onDismiss,
        headerAction = deleteAction,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (eventTypes.isEmpty()) {
                Text(
                    text = "请先在设置中创建事件类型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    eventTypes.forEach { type ->
                        FilterChip(
                            selected = typeKey == type.typeKey,
                            onClick = { typeKey = type.typeKey },
                            label = {
                                EventTypeLabel(
                                    type = type,
                                    textStyle = MaterialTheme.typography.labelLarge,
                                )
                            },
                        )
                    }
                }
            }
            DateTimeRow(
                label = "发生时间",
                value = occurredAt,
                onChange = { occurredAt = it },
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
        }
    }

    if (showDeleteConfirm && onDelete != null) {
        // onDelete 会让调用方移除整个编辑弹窗，等确认弹窗收回后再执行
        var deleteConfirmed by remember { mutableStateOf(false) }
        ConfirmDialog(
            title = "删除这条事件？",
            message = "删除后无法恢复。",
            confirmText = "删除",
            icon = Icons.Rounded.Delete,
            tone = ConfirmTone.Danger,
            onConfirm = { deleteConfirmed = true },
            onDismiss = {
                showDeleteConfirm = false
                if (deleteConfirmed) onDelete()
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventTypeEditDialog(
    title: String,
    initialName: String,
    initialColorArgb: Long,
    takenNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var colorArgb by remember(initialColorArgb) { mutableLongStateOf(initialColorArgb) }
    val trimmed = name.trim()
    val duplicate = trimmed.isNotEmpty() && trimmed in takenNames
    val empty = trimmed.isEmpty() && name.isNotEmpty()
    val valid = trimmed.isNotEmpty() && !duplicate

    FormDialog(
        title = title,
        confirmEnabled = valid,
        onConfirm = { onSave(trimmed, colorArgb) },
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(20) },
                label = { Text("名称") },
                singleLine = true,
                isError = duplicate || empty,
                supportingText = {
                    Text(
                        when {
                            duplicate -> "已存在同名事件类型"
                            empty -> "名称不能为空"
                            else -> "最多 20 个字符"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "统计颜色",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EventColors.palette.forEach { color ->
                    val selected = colorArgb == color
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        width = 3.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape,
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { colorArgb = color }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                EventColorDot(colorArgb = colorArgb, size = 12.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = trimmed.ifEmpty { "预览" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "该颜色用于统计图表、月历圆点和事件列表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PersonNameDialog(
    title: String,
    initialName: String,
    takenNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val duplicate = trimmed.isNotEmpty() && trimmed in takenNames
    val empty = trimmed.isEmpty() && name.isNotEmpty()

    FormDialog(
        title = title,
        confirmEnabled = trimmed.isNotEmpty() && !duplicate,
        onConfirm = { onSave(trimmed) },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(20) },
            label = { Text("名称") },
            singleLine = true,
            isError = duplicate || empty,
            supportingText = {
                Text(
                    when {
                        duplicate -> "已存在同名人物"
                        empty -> "名称不能为空"
                        else -> "最多 20 个字符"
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SegmentEditDialog(
    initial: WearSegment?,
    onDismiss: () -> Unit,
    onSave: (Long, Long?) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val now = remember(initial?.id) { System.currentTimeMillis() }
    var startAt by remember(initial?.id) {
        mutableLongStateOf(initial?.startAt ?: (now - 60 * 60 * 1000L))
    }
    var stillWearing by remember(initial?.id) {
        mutableStateOf(if (initial == null) false else initial.endAt == null)
    }
    var endAt by remember(initial?.id) {
        mutableStateOf<Long?>(
            when {
                initial == null -> now
                initial.endAt == null -> null
                else -> initial.endAt
            }
        )
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val invalidEnd = !stillWearing && (endAt == null || endAt!! < startAt)

    val deleteAction: (@Composable () -> Unit)? = if (onDelete != null) {
        {
            TextButton(onClick = { showDeleteConfirm = true }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        null
    }

    FormDialog(
        title = if (initial == null) "补录分段" else "编辑分段",
        confirmEnabled = !invalidEnd,
        onConfirm = { onSave(startAt, if (stillWearing) null else endAt) },
        onDismiss = onDismiss,
        headerAction = deleteAction,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DateTimeRow(
                label = "戴上时间",
                value = startAt,
                onChange = { newStart ->
                    startAt = newStart
                    val currentEnd = endAt
                    if (!stillWearing && currentEnd != null && currentEnd < newStart) {
                        endAt = newStart
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "仍在佩戴中",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = stillWearing,
                    onCheckedChange = { checked ->
                        stillWearing = checked
                        endAt = if (checked) {
                            null
                        } else {
                            maxOf(System.currentTimeMillis(), startAt)
                        }
                    },
                )
            }
            if (!stillWearing) {
                DateTimeRow(
                    label = "取下时间",
                    value = endAt ?: startAt,
                    onChange = { endAt = it },
                )
            }
            if (invalidEnd) {
                Text(
                    text = "取下时间不能早于戴上时间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "分段之间不能重叠；补录或修改会自动校验。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDeleteConfirm && onDelete != null) {
        // onDelete 会让调用方移除整个编辑弹窗，等确认弹窗收回后再执行
        var deleteConfirmed by remember { mutableStateOf(false) }
        ConfirmDialog(
            title = "删除这个分段？",
            message = "删除后，这段佩戴时长将从统计中移除，且无法恢复。",
            confirmText = "删除",
            icon = Icons.Rounded.Delete,
            tone = ConfirmTone.Danger,
            onConfirm = { deleteConfirmed = true },
            onDismiss = {
                showDeleteConfirm = false
                if (deleteConfirmed) onDelete()
            },
        )
    }
}
