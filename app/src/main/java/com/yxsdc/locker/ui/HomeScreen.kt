package com.yxsdc.locker.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.data.Person
import com.yxsdc.locker.data.WearSegment
import com.yxsdc.locker.domain.formatDurationWithSeconds
import com.yxsdc.locker.domain.formatTime
import com.yxsdc.locker.ui.components.ConfirmDialog
import com.yxsdc.locker.ui.components.ConfirmTone
import com.yxsdc.locker.ui.components.EventColorDot
import com.yxsdc.locker.ui.components.EventEditDialog
import com.yxsdc.locker.ui.components.LockConfirmIcon
import com.yxsdc.locker.ui.components.PersonSelector
import com.yxsdc.locker.ui.components.centerOnScreen
import com.yxsdc.locker.ui.theme.LocalDarkTheme
import com.yxsdc.locker.ui.theme.lockerCardColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: LockerViewModel,
    persons: List<Person>,
    currentPerson: Person?,
    eventTypes: List<EventTypeEntity>,
    segments: List<WearSegment>,
    snackbarHostState: SnackbarHostState,
) {
    val active = segments.lastOrNull { it.endAt == null }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var eventTypeToAdd by remember { mutableStateOf<EventTypeEntity?>(null) }
    var pendingWearAction by remember { mutableStateOf<WearAction?>(null) }
    val view = LocalView.current
    val wearButtonCoordinates = remember { Ref<LayoutCoordinates>() }
    var wearDialogOrigin by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val eventBlockAlpha = if (LocalDarkTheme.current) 0.22f else 0.12f

    fun notifyEventRecorded(typeName: String, eventId: Long) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "已记录 · $typeName",
                actionLabel = "撤销",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.deleteEventById(eventId)
            }
        }
    }

    // 退出动画期间 active 已变 null，保留最后一段用于显示
    var lastActiveSegment by remember { mutableStateOf<WearSegment?>(null) }
    LaunchedEffect(active) {
        if (active != null) lastActiveSegment = active
    }

    LaunchedEffect(active?.id) {
        while (active != null) {
            now = System.currentTimeMillis()
            // 对齐到整秒跳动，秒表走字更干脆
            delay(1_000 - (now % 1_000))
        }
    }

    LaunchedEffect(currentPerson?.id) {
        eventTypeToAdd = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Locker",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        PersonSelector(
            persons = persons,
            selectedPersonId = currentPerson?.id,
            onSelect = viewModel::selectPerson,
        )

        Spacer(Modifier.height(20.dp))

        if (currentPerson == null) {
            Card(
                colors = lockerCardColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "先新建一个人物，才能开始计时。",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        val isWearing = active != null
        val cardContainer by animateColorAsState(
            targetValue = if (isWearing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            animationSpec = tween(280),
            label = "status-card",
        )
        val statusColor by animateColorAsState(
            targetValue = if (isWearing) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            animationSpec = tween(280),
            label = "status-color",
        )

        AnimatedContent(
            targetState = currentPerson.id,
            transitionSpec = {
                val fromIndex = persons.indexOfFirst { it.id == initialState }
                val toIndex = persons.indexOfFirst { it.id == targetState }
                val forward = toIndex >= fromIndex
                (
                    slideInHorizontally(tween(260)) { width ->
                        if (forward) width / 8 else -width / 8
                    } + fadeIn(tween(260))
                ).togetherWith(
                    slideOutHorizontally(tween(200)) { width ->
                        if (forward) -width / 8 else width / 8
                    } + fadeOut(tween(160))
                )
            },
            label = "person-status",
        ) { personId ->
            val person = persons.firstOrNull { it.id == personId } ?: currentPerson
            Card(
                colors = CardDefaults.cardColors(containerColor = cardContainer),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "当前状态 · ${person.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(
                        visible = isWearing,
                        enter = expandHorizontally(tween(250)) + fadeIn(tween(250)),
                        exit = shrinkHorizontally(tween(200)) + fadeOut(tween(150)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulsingDot(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    AnimatedContent(
                        targetState = isWearing,
                        transitionSpec = {
                            fadeIn(tween(220)) togetherWith fadeOut(tween(120))
                        },
                        label = "status-text",
                    ) { wearing ->
                        Text(
                            text = if (wearing) "佩戴中" else "未佩戴",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = isWearing,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(250)),
                ) {
                    val segment = active ?: lastActiveSegment
                    if (segment != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = formatDurationWithSeconds(
                                    (now - segment.startAt).coerceAtLeast(0)
                                ),
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                fontWeight = FontWeight.SemiBold,
                                color = statusColor,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "本次已持续 · ${formatTime(segment.startAt)} 开始",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            }
        }

        Spacer(Modifier.height(20.dp))

        val buttonInteraction = remember { MutableInteractionSource() }
        val buttonPressed by buttonInteraction.collectIsPressedAsState()
        val buttonScale by animateFloatAsState(
            targetValue = if (buttonPressed) 0.96f else 1f,
            animationSpec = tween(120),
            label = "button-scale",
        )
        val buttonContainer by animateColorAsState(
            targetValue = if (isWearing) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.primary
            },
            animationSpec = tween(250),
            label = "button-container",
        )
        val buttonContent by animateColorAsState(
            targetValue = if (isWearing) {
                MaterialTheme.colorScheme.onSecondary
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
            animationSpec = tween(250),
            label = "button-content",
        )

        Button(
            onClick = {
                wearDialogOrigin = wearButtonCoordinates.value?.centerOnScreen(view)
                pendingWearAction = if (isWearing) WearAction.UNLOCK else WearAction.LOCK
            },
            interactionSource = buttonInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onPlaced { wearButtonCoordinates.value = it }
                .scale(buttonScale),
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonContainer,
                contentColor = buttonContent,
            ),
        ) {
            AnimatedContent(
                targetState = isWearing,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(120))
                },
                label = "button-label",
            ) { wearing ->
                Text(
                    text = if (wearing) "开锁" else "锁上",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("记录事件", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(
                text = "长按可快速记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (eventTypes.isEmpty()) {
            Text(
                text = "请先在设置中创建事件类型",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                eventTypes.forEach { type ->
                    Surface(
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(type.colorArgb).copy(alpha = eventBlockAlpha),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 16.dp)
                                .combinedClickable(
                                    onClick = { eventTypeToAdd = type },
                                    onLongClick = {
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.LongPress
                                        )
                                        viewModel.addEvent(
                                            type.typeKey,
                                            System.currentTimeMillis(),
                                            null,
                                        ) { eventId ->
                                            notifyEventRecorded(type.name, eventId)
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            EventColorDot(colorArgb = type.colorArgb, size = 10.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(type.name, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    pendingWearAction?.let { action ->
        val isLock = action == WearAction.LOCK
        val personName = currentPerson?.name ?: "当前人物"
        ConfirmDialog(
            title = if (isLock) "确认锁上？" else "确认开锁？",
            message = if (isLock) {
                "将为「$personName」开始计时。"
            } else {
                "将结束「$personName」的本次计时。"
            },
            confirmText = if (isLock) "锁上" else "开锁",
            icon = { LockConfirmIcon(locking = isLock) },
            tone = if (isLock) ConfirmTone.Primary else ConfirmTone.Neutral,
            originOnScreen = wearDialogOrigin,
            onConfirm = {
                if (isLock) viewModel.startWearing() else viewModel.stopWearing()
            },
            onDismiss = { pendingWearAction = null },
        )
    }

    eventTypeToAdd?.let { type ->
        EventEditDialog(
            initial = null,
            eventTypes = eventTypes,
            initialTypeKey = type.typeKey,
            onDismiss = { eventTypeToAdd = null },
            onSave = { typeKey, occurredAt, note ->
                val typeName = eventTypes.firstOrNull { it.typeKey == typeKey }?.name ?: "事件"
                viewModel.addEvent(typeKey, occurredAt, note) { eventId ->
                    notifyEventRecorded(typeName, eventId)
                }
                eventTypeToAdd = null
            },
        )
    }
}

@Composable
private fun PulsingDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    // 系统关闭动画（无障碍"移除动画"）时保持静态圆点
    val context = LocalContext.current
    val animationsEnabled = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
    val alpha = if (animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse-alpha",
        )
        animated
    } else {
        1f
    }
    Box(
        modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

private enum class WearAction { LOCK, UNLOCK }
