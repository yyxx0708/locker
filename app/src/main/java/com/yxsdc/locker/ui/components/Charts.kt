package com.yxsdc.locker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.domain.EventTypeStat
import com.yxsdc.locker.domain.StatsMode
import com.yxsdc.locker.domain.formatDuration
import com.yxsdc.locker.domain.formatDurationShort
import com.yxsdc.locker.ui.theme.ChartPalette
import com.yxsdc.locker.ui.theme.chartPalette

@Composable
fun WearBarChart(
    values: List<Long>,
    labels: List<String>,
    mode: StatsMode,
) {
    val palette = chartPalette()
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val max = values.maxOrNull() ?: 0L

    if (max <= 0L) {
        EmptyChart("该期间暂无佩戴记录", palette)
        return
    }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            1f,
            tween(durationMillis = 400, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)),
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = selected?.let {
                    "${bucketLabel(labels, it, mode)} · ${formatDuration(values[it])}"
                } ?: "点按柱子查看数值",
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary,
            )
            Text(
                text = "最高 ${formatDurationShort(max)}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            values.forEachIndexed { index, value ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            selected = if (selected == index) null else index
                        }
                        .semantics {
                            contentDescription =
                                "${bucketLabel(labels, index, mode)}：${formatDuration(value)}"
                        },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (value > 0L) {
                        val barAlpha by animateFloatAsState(
                            targetValue = if (selected == null || selected == index) 1f else 0.35f,
                            animationSpec = tween(200),
                            label = "wear-bar-alpha",
                        )
                        val barHeight by animateFloatAsState(
                            targetValue = value.toFloat() / max.toFloat(),
                            animationSpec = tween(400),
                            label = "wear-bar-height",
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(0.7f)
                                .fillMaxHeight(
                                    (barHeight * appear.value).coerceIn(0.01f, 1f)
                                )
                                .background(
                                    color = palette.series1.copy(alpha = barAlpha),
                                    shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                                )
                        )
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = palette.baseline)
        Spacer(Modifier.height(4.dp))
        XAxisLabels(labels, palette.muted)
    }
}

@Composable
fun EventStackedBarChart(
    eventStats: List<EventTypeStat>,
    labels: List<String>,
    mode: StatsMode,
) {
    val palette = chartPalette()
    var selected by remember(eventStats, labels) { mutableStateOf<Int?>(null) }
    val totals = labels.indices.map { index ->
        eventStats.sumOf { it.buckets.getOrElse(index) { 0 } }
    }
    val max = totals.maxOrNull() ?: 0

    if (max <= 0) {
        EmptyChart("该期间暂无事件记录", palette)
        return
    }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            1f,
            tween(durationMillis = 400, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)),
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = selected?.let { index ->
                    val parts = eventStats.mapNotNull { stat ->
                        val value = stat.buckets.getOrElse(index) { 0 }
                        if (value > 0) "${stat.type.name} $value 次" else null
                    }
                    "${bucketLabel(labels, index, mode)} · " +
                        parts.ifEmpty { listOf("无事件") }.joinToString("，")
                } ?: "点按柱子查看数值",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "最高 $max 次",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            labels.indices.forEach { index ->
                val total = totals[index]
                val description = eventStats.mapNotNull { stat ->
                    val value = stat.buckets.getOrElse(index) { 0 }
                    if (value > 0) "${stat.type.name} $value 次" else null
                }.joinToString("，")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            selected = if (selected == index) null else index
                        }
                        .semantics {
                            contentDescription = "${bucketLabel(labels, index, mode)}：" +
                                description.ifEmpty { "无事件" }
                        },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (total > 0) {
                        val barAlpha by animateFloatAsState(
                            targetValue = if (selected == null || selected == index) 1f else 0.35f,
                            animationSpec = tween(200),
                            label = "event-bar-alpha",
                        )
                        val segments = eventStats.mapNotNull { stat ->
                            val value = stat.buckets.getOrElse(index) { 0 }
                            if (value > 0) stat to value else null
                        }
                        val barHeight by animateFloatAsState(
                            targetValue = total.toFloat() / max.toFloat(),
                            animationSpec = tween(400),
                            label = "event-bar-height",
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .fillMaxHeight(
                                    (barHeight * appear.value).coerceIn(0.02f, 1f)
                                ),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            segments.forEachIndexed { segmentIndex, (stat, value) ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(value.toFloat())
                                        .background(
                                            color = Color(stat.type.colorArgb)
                                                .copy(alpha = barAlpha),
                                            shape = if (segmentIndex == segments.lastIndex) {
                                                RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                                            } else {
                                                RectangleShape
                                            },
                                        )
                                )
                                if (segmentIndex != segments.lastIndex) {
                                    Spacer(Modifier.height(1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = palette.baseline)
        Spacer(Modifier.height(4.dp))
        XAxisLabels(labels, palette.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventChartLegend(types: List<EventTypeEntity>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        types.forEach { type ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EventColorDot(colorArgb = type.colorArgb, size = 10.dp)
                Text(
                    text = type.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun XAxisLabels(labels: List<String>, color: Color) {
    val step = if (labels.size > 15) 5 else 1
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val show = labels.size <= 15 || index == 0 || (index + 1) % step == 0
            Text(
                text = if (show) label else "",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = color,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyChart(text: String, palette: ChartPalette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
        )
    }
}

private fun bucketLabel(labels: List<String>, index: Int, mode: StatsMode): String {
    val label = labels.getOrElse(index) { "" }
    return if (mode == StatsMode.MONTH) "${label}日" else label
}
