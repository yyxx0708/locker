package com.yxsdc.locker.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yxsdc.locker.data.DailyPhoto
import com.yxsdc.locker.data.DailyRandomNumber
import com.yxsdc.locker.data.EventRecord
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.data.Person
import com.yxsdc.locker.data.WearSegment
import com.yxsdc.locker.domain.EventTypeStat
import com.yxsdc.locker.domain.SegmentDetail
import com.yxsdc.locker.domain.StatsMode
import com.yxsdc.locker.domain.StatsResult
import com.yxsdc.locker.domain.computeStats
import com.yxsdc.locker.domain.formatDate
import com.yxsdc.locker.domain.formatDateTime
import com.yxsdc.locker.domain.formatDuration
import com.yxsdc.locker.domain.formatMonth
import com.yxsdc.locker.domain.formatTime
import com.yxsdc.locker.ui.components.ConfirmDialog
import com.yxsdc.locker.ui.components.ConfirmTone
import com.yxsdc.locker.ui.components.DayDetailDialog
import com.yxsdc.locker.ui.components.EmptyStateHint
import com.yxsdc.locker.ui.components.EventChartLegend
import com.yxsdc.locker.ui.components.EventColorDot
import com.yxsdc.locker.ui.components.EventEditDialog
import com.yxsdc.locker.ui.components.EventStackedBarChart
import com.yxsdc.locker.ui.components.MonthCalendar
import com.yxsdc.locker.ui.components.PersonSelector
import com.yxsdc.locker.ui.components.SegmentEditDialog
import com.yxsdc.locker.ui.components.WearBarChart
import com.yxsdc.locker.ui.theme.chartPalette
import com.yxsdc.locker.ui.theme.lockerCardColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private data class StatsPeriodData(
    val anchor: YearMonth,
    val mode: StatsMode,
    val segments: List<WearSegment>,
    val events: List<EventRecord>,
)

private data class ThumbnailKey(val personId: Long, val epochDay: Long)

private class CachedThumbnail(val updatedAt: Long, val bitmap: ImageBitmap)

/** 月历缩略图跨月缓存：按 (人物, 日期) 键控，照片更新后自动失效。 */
private val monthThumbnailCache = LruCache<ThumbnailKey, CachedThumbnail>(64)

private fun cachedThumbnail(photo: DailyPhoto): ImageBitmap? {
    val cached = monthThumbnailCache.get(ThumbnailKey(photo.personId, photo.epochDay))
    return cached?.takeIf { it.updatedAt == photo.updatedAt }?.bitmap
}

private fun cacheThumbnail(photo: DailyPhoto, bitmap: ImageBitmap) {
    monthThumbnailCache.put(
        ThumbnailKey(photo.personId, photo.epochDay),
        CachedThumbnail(photo.updatedAt, bitmap),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(
    viewModel: LockerViewModel,
    persons: List<Person>,
    currentPerson: Person?,
    eventTypes: List<EventTypeEntity>,
) {
    val mode by viewModel.statsMode.collectAsStateWithLifecycle()
    val anchor by viewModel.statsAnchor.collectAsStateWithLifecycle()
    // 周期数据带上自己的 anchor/mode：切换周期时，新数据到达前继续用上一份结果，
    // 避免旧周期的数据被误当成新周期统计。
    val periodData by remember(anchor, mode) {
        combine(
            viewModel.observeStatsSegments(anchor, mode),
            viewModel.observeStatsEvents(anchor, mode),
        ) { segments, events -> StatsPeriodData(anchor, mode, segments, events) }
    }.collectAsStateWithLifecycle(initialValue = null)
    val hasActive = periodData?.segments?.any { it.endAt == null } == true

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasActive, currentPerson?.id) {
        if (!hasActive) {
            now = System.currentTimeMillis()
            return@LaunchedEffect
        }
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val currentStats = remember(periodData, eventTypes, now) {
        periodData?.let { data ->
            computeStats(
                segments = data.segments,
                events = data.events,
                eventTypes = eventTypes,
                mode = data.mode,
                anchor = data.anchor,
                zone = ZoneId.systemDefault(),
                now = now,
            )
        }
    }
    var lastStats by remember { mutableStateOf<Pair<StatsMode, StatsResult>?>(null) }
    // 保存每个访问过的月份的统计快照，月历切换动画期间旧月份仍显示自己的数据
    val monthStatsByAnchor = remember { mutableStateMapOf<YearMonth, StatsResult>() }
    LaunchedEffect(periodData, currentStats) {
        val data = periodData ?: return@LaunchedEffect
        val snapshot = currentStats ?: return@LaunchedEffect
        lastStats = data.mode to snapshot
        if (data.mode == StatsMode.MONTH) {
            monthStatsByAnchor[data.anchor] = snapshot
            if (monthStatsByAnchor.size > 12) {
                monthStatsByAnchor.keys.firstOrNull()?.let { monthStatsByAnchor.remove(it) }
            }
        }
    }
    // 切换周期时先用上一条同模式的结果占位，避免月/年数据形态不匹配
    val stats = currentStats
        ?: lastStats?.takeIf { it.first == mode }?.second
        ?: computeStats(
            segments = emptyList(),
            events = emptyList(),
            eventTypes = eventTypes,
            mode = mode,
            anchor = anchor,
        )
    val typeByKey = remember(eventTypes) { eventTypes.associateBy { it.typeKey } }

    // 月历只在「月」模式显示，年模式不再加载整月照片与随机数
    val dailyPhotos by remember(anchor, mode) {
        if (mode == StatsMode.MONTH) {
            viewModel.dailyPhotosForMonth(anchor)
        } else {
            flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList<DailyPhoto>())
    val photosByDay = remember(dailyPhotos) {
        dailyPhotos.associateBy { LocalDate.ofEpochDay(it.epochDay).dayOfMonth }
    }
    // 按 epochDay 键控的缩略图缓存：切换月份时不会把上个月的照片错配到新月份
    val photoBitmaps by produceState(
        initialValue = emptyMap<Long, ImageBitmap>(),
        dailyPhotos,
    ) {
        value = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 8
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            dailyPhotos.mapNotNull { photo ->
                val bitmap = cachedThumbnail(photo) ?: runCatching {
                    BitmapFactory.decodeByteArray(
                        photo.jpeg,
                        0,
                        photo.jpeg.size,
                        options,
                    )?.asImageBitmap()
                }.getOrNull()?.also { cacheThumbnail(photo, it) }
                bitmap?.let { photo.epochDay to it }
            }.toMap()
        }
    }

    var addingSegment by remember { mutableStateOf(false) }
    var editingSegment by remember { mutableStateOf<WearSegment?>(null) }
    var editingEvent by remember { mutableStateOf<EventRecord?>(null) }
    var selectedDay by remember(anchor, mode, currentPerson?.id) {
        mutableStateOf<Int?>(null)
    }
    var selectedDayOrigin by remember { mutableStateOf<Offset?>(null) }
    var quickDeleteSegment by remember { mutableStateOf<WearSegment?>(null) }
    var quickDeleteEvent by remember { mutableStateOf<EventRecord?>(null) }

    val monthlyNumbers by remember(anchor, mode) {
        if (mode == StatsMode.MONTH) {
            viewModel.observeMonthlyRandomNumbers(anchor)
        } else {
            flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList<DailyRandomNumber>())
    val numbersByEpochDay = remember(monthlyNumbers) {
        monthlyNumbers.associateBy { it.epochDay }
    }

    val groupedEvents = remember(stats.eventDetails) {
        stats.eventDetails.groupBy { formatDate(it.occurredAt) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text("统计", style = MaterialTheme.typography.headlineMedium)
        }

        stickyHeader(key = "controls") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PersonSelector(
                    persons = persons,
                    selectedPersonId = currentPerson?.id,
                    onSelect = viewModel::selectPerson,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = mode == StatsMode.MONTH,
                        onClick = { viewModel.setStatsMode(StatsMode.MONTH) },
                        label = { Text("月") },
                    )
                    FilterChip(
                        selected = mode == StatsMode.YEAR,
                        onClick = { viewModel.setStatsMode(StatsMode.YEAR) },
                        label = { Text("年") },
                    )
                    Spacer(Modifier.weight(1f))
                    AnimatedVisibility(
                        visible = anchor != YearMonth.now(),
                        enter = fadeIn(tween(200)) + expandHorizontally(tween(220)),
                        exit = fadeOut(tween(120)) + shrinkHorizontally(tween(160)),
                    ) {
                        Surface(
                            onClick = { viewModel.goToCurrentPeriod() },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("回到本期", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.movePeriod(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一期")
                    }
                    AnimatedContent(
                        targetState = mode to anchor,
                        modifier = Modifier.weight(1f),
                        transitionSpec = {
                            if (targetState.second == initialState.second) {
                                fadeIn(tween(200)) togetherWith fadeOut(tween(120))
                            } else {
                                val forward = targetState.second > initialState.second
                                (slideInHorizontally(tween(250)) { width ->
                                    if (forward) width / 3 else -width / 3
                                } + fadeIn(tween(250))).togetherWith(
                                    slideOutHorizontally(tween(200)) { width ->
                                        if (forward) -width / 3 else width / 3
                                    } + fadeOut(tween(150))
                                )
                            }
                        },
                        label = "period-label",
                    ) { (currentMode, currentAnchor) ->
                        Text(
                            text = if (currentMode == StatsMode.MONTH) {
                                formatMonth(currentAnchor)
                            } else {
                                "${currentAnchor.year}年"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    IconButton(onClick = { viewModel.movePeriod(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一期")
                    }
                }
            }
        }

        item(key = "summary") {
            SummaryCard(
                totalWearMillis = stats.totalWearMillis,
                eventStats = stats.eventStats,
                modifier = Modifier.animateItem(),
            )
        }

        if (mode == StatsMode.MONTH) {
            item(key = "calendar") {
                CalendarCard(
                    anchor = anchor,
                    wearBuckets = stats.wearBuckets,
                    eventStats = stats.eventStats,
                    monthStatsByAnchor = monthStatsByAnchor,
                    dayPhotoBitmaps = photoBitmaps,
                    onDayClick = { day, origin ->
                        selectedDayOrigin = origin
                        selectedDay = day
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        } else {
            item(key = "wear-chart") {
                ChartCard(
                    title = "佩戴时长",
                    subtitle = "按月",
                    modifier = Modifier.animateItem(),
                ) {
                    WearBarChart(stats.wearBuckets, stats.bucketLabels, mode)
                }
            }

            item(key = "event-chart") {
                ChartCard(
                    title = "事件次数",
                    subtitle = "按月",
                    modifier = Modifier.animateItem(),
                ) {
                    EventStackedBarChart(
                        eventStats = stats.eventStats,
                        labels = stats.bucketLabels,
                        mode = mode,
                    )
                    val legendTypes = stats.eventStats
                        .filter { it.count > 0 }
                        .map { it.type }
                    if (legendTypes.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        EventChartLegend(legendTypes)
                    }
                }
            }
        }

        item(key = "segments-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("分段明细", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { addingSegment = true }) {
                    Text("补录")
                }
            }
        }

        if (stats.segmentDetails.isEmpty()) {
            item(key = "segments-empty") {
                EmptyStateHint(
                    text = "该期间没有分段记录",
                    modifier = Modifier.animateItem(),
                )
            }
        } else {
            items(
                items = stats.segmentDetails,
                key = { "segment-${it.segment.id}" },
            ) { detail ->
                SegmentDetailCard(
                    detail = detail,
                    now = now,
                    onClick = { editingSegment = detail.segment },
                    onLongClick = { quickDeleteSegment = detail.segment },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        item(key = "events-header") {
            Text(
                "事件明细",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.animateItem(),
            )
        }

        if (stats.eventDetails.isEmpty()) {
            item(key = "events-empty") {
                EmptyStateHint(
                    text = "该期间没有事件记录",
                    modifier = Modifier.animateItem(),
                )
            }
        } else {
            groupedEvents.forEach { (date, dayEvents) ->
                item(key = "event-date-$date") {
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .animateItem(),
                    )
                }
                items(
                    items = dayEvents,
                    key = { "event-${it.id}" },
                ) { event ->
                    EventDetailCard(
                        event = event,
                        type = typeByKey[event.type],
                        onClick = { editingEvent = event },
                        onLongClick = { quickDeleteEvent = event },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    selectedDay?.let { day ->
        val dayDate = anchor.atDay(day)
        val isFuture = dayDate.isAfter(LocalDate.now())
        DayDetailDialog(
            anchor = anchor,
            day = day,
            dayWearMillis = stats.wearBuckets.getOrElse(day - 1) { 0L },
            randomNumber = numbersByEpochDay[dayDate.toEpochDay()]?.number,
            photo = photosByDay[day]?.jpeg,
            photoEditable = currentPerson != null && !isFuture,
            photoReadOnlyMessage = if (isFuture) {
                "今天之后的日期不能上传照片。"
            } else {
                null
            },
            onPhotoSelected = { bytes ->
                viewModel.setDailyPhoto(dayDate.toEpochDay(), bytes)
            },
            onPhotoDeleted = {
                viewModel.deleteDailyPhoto(dayDate.toEpochDay())
            },
            eventStats = stats.eventStats,
            segmentDetails = stats.segmentDetails,
            events = stats.eventDetails,
            eventTypes = eventTypes,
            now = now,
            onDismiss = { selectedDay = null },
            originOnScreen = selectedDayOrigin,
        )
    }

    if (addingSegment) {
        SegmentEditDialog(
            initial = null,
            onDismiss = { addingSegment = false },
            onSave = { startAt, endAt ->
                viewModel.addManualSegment(startAt, endAt)
                addingSegment = false
            },
        )
    }

    editingSegment?.let { segment ->
        SegmentEditDialog(
            initial = segment,
            onDismiss = { editingSegment = null },
            onSave = { startAt, endAt ->
                viewModel.updateSegment(segment.id, segment.personId, startAt, endAt)
                editingSegment = null
            },
            onDelete = {
                viewModel.deleteSegment(segment)
                editingSegment = null
            },
        )
    }

    editingEvent?.let { event ->
        EventEditDialog(
            initial = event,
            eventTypes = eventTypes,
            initialTypeKey = event.type,
            onDismiss = { editingEvent = null },
            onSave = { typeKey, occurredAt, note ->
                viewModel.updateEvent(
                    event.id,
                    event.personId,
                    typeKey,
                    occurredAt,
                    note,
                )
                editingEvent = null
            },
            onDelete = {
                viewModel.deleteEvent(event)
                editingEvent = null
            },
        )
    }

    quickDeleteSegment?.let { segment ->
        ConfirmDialog(
            title = "删除这个分段？",
            message = "删除后，这段佩戴时长将从统计中移除，且无法恢复。",
            confirmText = "删除",
            icon = Icons.Rounded.Delete,
            tone = ConfirmTone.Danger,
            onConfirm = { viewModel.deleteSegment(segment) },
            onDismiss = { quickDeleteSegment = null },
        )
    }

    quickDeleteEvent?.let { event ->
        ConfirmDialog(
            title = "删除这条事件？",
            message = "删除后无法恢复。",
            confirmText = "删除",
            icon = Icons.Rounded.Delete,
            tone = ConfirmTone.Danger,
            onConfirm = { viewModel.deleteEvent(event) },
            onDismiss = { quickDeleteEvent = null },
        )
    }
}

@Composable
private fun CalendarCard(
    anchor: YearMonth,
    wearBuckets: List<Long>,
    eventStats: List<EventTypeStat>,
    monthStatsByAnchor: Map<YearMonth, StatsResult>,
    dayPhotoBitmaps: Map<Long, ImageBitmap>,
    onDayClick: (day: Int, originOnScreen: Offset?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = lockerCardColors(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("月历", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "按天",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = anchor,
                transitionSpec = {
                    val forward = targetState > initialState
                    (
                        slideInHorizontally(tween(280)) { width ->
                            if (forward) width / 4 else -width / 4
                        } + fadeIn(tween(280))
                    ).togetherWith(
                        slideOutHorizontally(tween(220)) { width ->
                            if (forward) -width / 4 else width / 4
                        } + fadeOut(tween(180))
                    )
                },
                label = "month-calendar",
            ) { month ->
                // 过渡期间每个月份用自己那一份快照，避免旧月份格子上闪现新月份的热度
                val monthStats = if (month == anchor) null else monthStatsByAnchor[month]
                val monthWear = monthStats?.wearBuckets ?: wearBuckets
                val monthEventStats = monthStats?.eventStats ?: eventStats
                // 当前月跟随最新缩略图；退出中的月份保留离开时的快照，照片不会在收起过程中被清空
                var photoSource by remember(month) { mutableStateOf(dayPhotoBitmaps) }
                SideEffect {
                    if (month == anchor) photoSource = dayPhotoBitmaps
                }
                val monthPhotos = remember(photoSource, month) {
                    buildMap<Int, ImageBitmap> {
                        for (day in 1..month.lengthOfMonth()) {
                            dayPhotoBitmapsForDay(photoSource, month, day)?.let {
                                put(day, it)
                            }
                        }
                    }
                }
                Column {
                    MonthCalendar(
                        anchor = month,
                        dayWear = monthWear,
                        eventStats = monthEventStats,
                        dayPhotos = monthPhotos,
                        onDayClick = onDayClick,
                    )
                    val legendTypes = monthEventStats.filter { it.count > 0 }.map { it.type }
                    if (legendTypes.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        EventChartLegend(legendTypes)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "颜色越深，当天佩戴越久；点按日期查看明细、上传当天照片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun dayPhotoBitmapsForDay(
    bitmapsByEpochDay: Map<Long, ImageBitmap>,
    month: YearMonth,
    day: Int,
): ImageBitmap? = bitmapsByEpochDay[month.atDay(day).toEpochDay()]

@Composable
private fun SummaryCard(
    totalWearMillis: Long,
    eventStats: List<EventTypeStat>,
    modifier: Modifier = Modifier,
) {
    val visibleStats = eventStats.filter { it.count > 0 }
    Card(
        colors = lockerCardColors(),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(tween(250)),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "总佩戴时长",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatDuration(totalWearMillis),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))
            if (visibleStats.isEmpty()) {
                Text(
                    text = "本期没有事件记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    visibleStats.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            row.forEach { stat ->
                                EventCountBlock(
                                    stat = stat,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCountBlock(
    stat: EventTypeStat,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EventColorDot(colorArgb = stat.type.colorArgb)
            Spacer(Modifier.width(6.dp))
            Text(
                text = stat.type.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${stat.count} 次",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        colors = lockerCardColors(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentDetailCard(
    detail: SegmentDetail,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val segment = detail.segment
    val end = segment.endAt
    val fullDuration = ((end ?: now) - segment.startAt).coerceAtLeast(0)

    Card(
        colors = lockerCardColors(),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DetailLine(
                label = "戴上",
                value = formatDateTime(segment.startAt),
            )
            DetailLine(
                label = "取下",
                value = if (end == null) "佩戴中" else formatDateTime(end),
                valueColor = if (end == null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Unspecified
                },
            )
            DetailLine(
                label = "时长",
                value = formatDuration(fullDuration),
            )
            if (detail.crossesPeriodBoundary) {
                Text(
                    text = "本期计入 ${formatDuration(detail.clippedMillis)}；明细显示完整分段",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventDetailCard(
    event: EventRecord,
    type: EventTypeEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dotColor = type?.let { Color(it.colorArgb) } ?: chartPalette().muted

    Card(
        colors = lockerCardColors(),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = type?.name ?: "未知事件",
                    style = MaterialTheme.typography.titleSmall,
                )
                event.note?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = formatTime(event.occurredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
