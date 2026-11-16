package com.yxsdc.locker.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yxsdc.locker.data.EventColors
import com.yxsdc.locker.data.EventRecord
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.domain.EventTypeStat
import com.yxsdc.locker.domain.SegmentDetail
import com.yxsdc.locker.domain.formatDateTime
import com.yxsdc.locker.domain.formatDuration
import com.yxsdc.locker.domain.formatTime
import com.yxsdc.locker.ui.theme.chartPalette
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MonthCalendar(
    anchor: YearMonth,
    dayWear: List<Long>,
    eventStats: List<EventTypeStat>,
    dayPhotos: Map<Int, ImageBitmap>,
    onDayClick: (day: Int, originOnScreen: Offset?) -> Unit,
) {
    val days = anchor.lengthOfMonth()
    val firstDayOffset = anchor.atDay(1).dayOfWeek.value - 1
    val totalCells = ((firstDayOffset + days + 6) / 7) * 7
    val maxWear = dayWear.maxOrNull() ?: 0L
    val today = LocalDate.now()
    val todayDay = if (YearMonth.from(today) == anchor) today.dayOfMonth else null

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        for (weekStart in 0 until totalCells step 7) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (cell in weekStart until weekStart + 7) {
                    val day = cell - firstDayOffset + 1
                    if (day in 1..days) {
                        DayCell(
                            modifier = Modifier.weight(1f),
                            day = day,
                            wearMillis = dayWear.getOrElse(day - 1) { 0L },
                            eventStats = eventStats,
                            photo = dayPhotos[day],
                            maxWear = maxWear,
                            isToday = day == todayDay,
                            onClick = { origin -> onDayClick(day, origin) },
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    modifier: Modifier = Modifier,
    day: Int,
    wearMillis: Long,
    eventStats: List<EventTypeStat>,
    photo: ImageBitmap?,
    maxWear: Long,
    isToday: Boolean,
    onClick: (originOnScreen: Offset?) -> Unit,
) {
    val palette = chartPalette()
    val view = LocalView.current
    val cellCoordinates = remember { Ref<LayoutCoordinates>() }
    val fraction = if (maxWear > 0L) {
        (wearMillis.toFloat() / maxWear.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val cellBackground = if (wearMillis > 0L) {
        palette.series1.copy(alpha = 0.08f + 0.30f * fraction)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    val dayEvents = eventStats.mapNotNull { stat ->
        val count = stat.buckets.getOrElse(day - 1) { 0 }
        if (count > 0) stat.type to count else null
    }
    val eventsDescription = dayEvents.joinToString("，") {
        "${it.first.name} ${it.second} 次"
    }
    val hasPhoto = photo != null
    // 照片异步加载完成时淡入，而不是瞬间弹出
    val photoAlpha by animateFloatAsState(
        targetValue = if (hasPhoto) 1f else 0f,
        animationSpec = tween(250),
        label = "day-photo-alpha",
    )
    val dayColor by animateColorAsState(
        targetValue = when {
            hasPhoto -> Color.White
            isToday -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(250),
        label = "day-number-color",
    )
    val wearColor by animateColorAsState(
        targetValue = if (hasPhoto) Color.White else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(250),
        label = "day-wear-color",
    )
    val dotBorder = if (hasPhoto) {
        Color.White.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Box(
        modifier = modifier
            .height(60.dp)
            .onPlaced { cellCoordinates.value = it }
            .clip(RoundedCornerShape(6.dp))
            .background(cellBackground)
            .then(
                if (isToday) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(6.dp),
                    )
                } else {
                    Modifier
                }
            )
            .clickable { onClick(cellCoordinates.value?.centerOnScreen(view)) }
            .semantics {
                contentDescription = "$day 日" +
                    (if (isToday) "（今天）" else "") +
                    "，佩戴 ${formatDuration(wearMillis)}" +
                    (if (hasPhoto) "，有照片" else "") +
                    (if (eventsDescription.isNotEmpty()) "，$eventsDescription" else "")
            },
    ) {
        if (photo != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = photoAlpha },
            ) {
                Image(
                    bitmap = photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.38f))
                )
            }
        }
        Column(
            modifier = Modifier
                .matchParentSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = dayColor,
                fontWeight = if (isToday) FontWeight.Bold else null,
            )
            Text(
                text = compactWear(wearMillis),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = wearColor,
                maxLines = 1,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dayEvents.take(3).forEach { (type, _) ->
                    EventColorDot(
                        colorArgb = type.colorArgb,
                        size = 6.dp,
                        borderColor = dotBorder,
                    )
                }
                if (dayEvents.size > 3) {
                    Text(
                        text = "+${dayEvents.size - 3}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = if (hasPhoto) {
                            Color.White.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun DayDetailDialog(
    anchor: YearMonth,
    day: Int,
    dayWearMillis: Long,
    randomNumber: String?,
    photo: ByteArray?,
    photoEditable: Boolean,
    photoReadOnlyMessage: String?,
    onPhotoSelected: (ByteArray) -> Unit,
    onPhotoDeleted: () -> Unit,
    eventStats: List<EventTypeStat>,
    segmentDetails: List<SegmentDetail>,
    events: List<EventRecord>,
    eventTypes: List<EventTypeEntity>,
    now: Long,
    onDismiss: () -> Unit,
    originOnScreen: Offset? = null,
) {
    val zone = ZoneId.systemDefault()
    val dayStart = anchor.atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = anchor.atDay(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val daySegments = segmentDetails.mapNotNull { detail ->
        val segment = detail.segment
        val segmentEnd = (segment.endAt ?: now).coerceAtLeast(segment.startAt)
        val overlapStart = maxOf(segment.startAt, dayStart)
        val overlapEnd = minOf(segmentEnd, dayEnd)
        if (overlapEnd > overlapStart) {
            DaySegment(
                startAt = segment.startAt,
                endAt = segment.endAt,
                dayMillis = overlapEnd - overlapStart,
                crosses = segment.startAt < dayStart || segmentEnd > dayEnd,
            )
        } else {
            null
        }
    }.sortedBy { it.startAt }

    val dayEvents = events
        .filter { it.occurredAt in dayStart until dayEnd }
        .sortedBy { it.occurredAt }
    val dayEventCounts = eventStats.mapNotNull { stat ->
        val count = stat.buckets.getOrElse(day - 1) { 0 }
        if (count > 0) stat.type to count else null
    }
    val typeByKey = eventTypes.associateBy { it.typeKey }

    val title = "${anchor.year}年${anchor.monthValue}月${day}日"
    PopDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { paneTitle = title },
        originOnScreen = originOnScreen,
        startScale = 0.85f,
        maxWidth = 420.dp,
    ) { dismiss ->
        Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "佩戴 ${formatDuration(dayWearMillis)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                randomNumber?.let { number ->
                    Text(
                        text = "当日随机数 $number",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
                DayTimeline(
                    dayStart = dayStart,
                    dayEnd = dayEnd,
                    segments = daySegments,
                    events = dayEvents.map { event ->
                        event.occurredAt to (typeByKey[event.type]?.colorArgb ?: EventColors.SLATE)
                    },
                )
                if (dayEventCounts.isEmpty() && daySegments.isNotEmpty()) {
                    Text(
                        text = "当天没有事件记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (dayEventCounts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        dayEventCounts.chunked(2).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                row.forEach { (type, count) ->
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        EventColorDot(colorArgb = type.colorArgb)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "${type.name} $count 次",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                if (row.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                DayPhotoSection(
                    photo = photo,
                    randomNumber = randomNumber,
                    editable = photoEditable,
                    readOnlyMessage = photoReadOnlyMessage,
                    onPhotoSelected = onPhotoSelected,
                    onPhotoDeleted = onPhotoDeleted,
                )

                if (daySegments.isNotEmpty()) {
                    Text(
                        text = "分段",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    daySegments.forEach { segment ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${formatDateTime(segment.startAt)} - " +
                                    (segment.endAt?.let { formatDateTime(it) } ?: "佩戴中"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "当日计入 ${formatDuration(segment.dayMillis)}" +
                                    if (segment.crosses) "（跨日）" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (dayEvents.isNotEmpty()) {
                    Text(
                        text = "事件",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    dayEvents.forEach { event ->
                        val type = typeByKey[event.type]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EventColorDot(
                                colorArgb = type?.colorArgb ?: EventColors.SLATE,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${formatTime(event.occurredAt)} · " +
                                    (type?.name ?: "未知事件") +
                                    (event.note?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (daySegments.isEmpty() && dayEvents.isEmpty()) {
                    Text(
                        text = "当天没有记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { dismiss() },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun DayPhotoSection(
    photo: ByteArray?,
    randomNumber: String?,
    editable: Boolean,
    readOnlyMessage: String?,
    onPhotoSelected: (ByteArray) -> Unit,
    onPhotoDeleted: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showFullPhoto by remember(photo) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var ocrRunning by remember(photo) { mutableStateOf(false) }
    var ocrResult by remember(photo) { mutableStateOf<String?>(null) }

    // 主线程不做大图解码：照片在 IO 线程解码完成后再显示
    val bitmap by produceState<ImageBitmap?>(initialValue = null, photo) {
        value = withContext(Dispatchers.IO) {
            photo?.let { bytes ->
                runCatching {
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        ?.asImageBitmap()
                }.getOrNull()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "当天照片",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val preview = bitmap
        if (photo != null) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = "当天照片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { showFullPhoto = true },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            val jpeg = photo ?: return@TextButton
                            ocrRunning = true
                            scope.launch {
                                ocrResult = try {
                                    val candidates = recognizeNumberCandidates(jpeg)
                                    when {
                                        randomNumber == null -> "当天没有随机数可对照"
                                        randomNumber in candidates ->
                                            "✓ 照片号码与当日随机数 $randomNumber 一致"
                                        candidates.isEmpty() -> "未在照片中识别到 5 位号码"
                                        else -> "✗ 识别到 ${
                                            candidates.take(3).joinToString("、")
                                        }，与当日 $randomNumber 不一致"
                                    }
                                } catch (throwable: Throwable) {
                                    "识别失败：${throwable.message ?: "未知错误"}"
                                } finally {
                                    ocrRunning = false
                                }
                            }
                        },
                        enabled = !ocrRunning,
                    ) {
                        Text(if (ocrRunning) "识别中…" else "识别照片号码")
                    }
                    ocrResult?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                result.startsWith("✓") -> MaterialTheme.colorScheme.primary
                                result.startsWith("✗") -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "正在加载照片…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (readOnlyMessage == null) {
            Text(
                text = "还没有上传照片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (readOnlyMessage != null) {
            Text(
                text = readOnlyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhotoSourceButton(
                    text = if (photo == null) "上传照片" else "更换照片",
                    onPhotoReady = onPhotoSelected,
                    enabled = editable,
                )
                if (photo != null) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = editable,
                    ) {
                        Text("删除照片")
                    }
                }
            }
        }
    }

    val fullPhoto = bitmap
    if (showFullPhoto && fullPhoto != null) {
        val appear = remember { Animatable(0f) }
        val viewerScope = rememberCoroutineScope()
        var viewerClosing by remember { mutableStateOf(false) }
        // 强 ease-out：开始就快，收起利落
        val viewerEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
        LaunchedEffect(Unit) {
            appear.animateTo(1f, tween(220, easing = viewerEasing))
        }
        val dismissViewer: () -> Unit = {
            if (!viewerClosing) {
                viewerClosing = true
                viewerScope.launch {
                    appear.animateTo(0f, tween(160, easing = viewerEasing))
                    showFullPhoto = false
                    viewerClosing = false
                }
            }
        }
        Dialog(
            onDismissRequest = dismissViewer,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = appear.value
                        val scale = 0.94f + 0.06f * appear.value
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(Color.Black)
                    .clickable { dismissViewer() },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = fullPhoto,
                    contentDescription = "当天照片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
                IconButton(
                    onClick = { dismissViewer() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除当天照片？",
            message = "删除后仍可重新上传。",
            confirmText = "删除",
            icon = Icons.Rounded.Delete,
            tone = ConfirmTone.Danger,
            onConfirm = onPhotoDeleted,
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * 24 小时时间轴：佩戴分段画成圆角横条，事件画成带描边的圆点。
 */
@Composable
private fun DayTimeline(
    dayStart: Long,
    dayEnd: Long,
    segments: List<DaySegment>,
    events: List<Pair<Long, Long>>,
    modifier: Modifier = Modifier,
) {
    val span = (dayEnd - dayStart).coerceAtLeast(1L)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
        ) {
            val barHeight = 8.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            segments.forEach { segment ->
                val start = maxOf(segment.startAt, dayStart)
                val end = minOf(segment.endAt ?: dayEnd, dayEnd)
                if (end > start) {
                    val startFraction = (start - dayStart).toFloat() / span
                    val endFraction = (end - dayStart).toFloat() / span
                    Box(
                        Modifier
                            .offset(x = maxWidth * startFraction)
                            .width(maxWidth * (endFraction - startFraction))
                            .height(barHeight)
                            .align(Alignment.CenterStart)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            events.forEach { (occurredAt, colorArgb) ->
                val fraction = ((occurredAt - dayStart).toFloat() / span).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .offset(
                            x = (maxWidth * fraction - 5.dp)
                                .coerceIn(0.dp, (maxWidth - 10.dp).coerceAtLeast(0.dp)),
                        )
                        .size(10.dp)
                        .align(Alignment.CenterStart)
                        .clip(CircleShape)
                        .background(Color(colorArgb))
                        .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("0", "6", "12", "18", "24").forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class DaySegment(
    val startAt: Long,
    val endAt: Long?,
    val dayMillis: Long,
    val crosses: Boolean,
)

private fun compactWear(millis: Long): String {
    val totalMinutes = millis.coerceAtLeast(0) / 60_000
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}天"
        hours > 0 -> "${hours}时"
        minutes > 0 -> "${minutes}分"
        else -> ""
    }
}
