package com.yxsdc.locker.ui.components

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 布局中心点的屏幕像素坐标，用作 [PopDialog] 的弹出原点。
 * [view] 必须是该布局所在的 Compose 根视图（LocalView）。
 */
fun LayoutCoordinates.centerOnScreen(view: View): Offset? {
    if (!isAttached) return null
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().center + Offset(location[0].toFloat(), location[1].toFloat())
}

/**
 * 居中卡片弹窗：卡片从 [originOnScreen]（缺省为卡片中心）弹出。
 * 通过 content 拿到的 dismiss、点击卡片外或按返回键关闭时，卡片先沿原路收回，
 * 动画结束后才回调 [onDismissRequest]，调用方应在回调里移除弹窗。
 * dismiss 返回 false 表示弹窗已在收回途中，本次调用被忽略。
 * 系统窗口动画已关闭：调用方不经 dismiss 直接把弹窗移出组合时，卡片会瞬间消失。
 */
@Composable
fun PopDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    originOnScreen: Offset? = null,
    startScale: Float = 0.86f,
    maxWidth: Dp = 360.dp,
    content: @Composable (dismiss: () -> Boolean) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    var closing by remember { mutableStateOf(false) }
    val dialogWindow = remember { Ref<Window>() }

    val dismiss: () -> Boolean = {
        if (closing) {
            false
        } else {
            closing = true
            scope.launch {
                val window = dialogWindow.value
                // 遮罩交给系统淡出，与卡片收回同时进行
                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                progress.animateTo(
                    0f,
                    tween(160, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)),
                )
                currentOnDismiss()
                // 调用方没有移除弹窗时恢复原状，避免留下看不见却拦截触摸的窗口
                repeat(2) { withFrameNanos {} }
                window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                progress.snapTo(1f)
                closing = false
            }
            true
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            dialogWindow.value = window
            // 进出场都由卡片自身的动画负责，关闭系统窗口动画以免两者叠加
            window?.setWindowAnimations(0)
            onDispose { dialogWindow.value = null }
        }
        LaunchedEffect(Unit) {
            progress.animateTo(1f, spring(dampingRatio = 0.74f, stiffness = 420f))
        }
        var cardOnScreen by remember { mutableStateOf<Offset?>(null) }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { dismiss() } }
            )
            Surface(
                modifier = modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        val location = IntArray(2)
                        dialogView.getLocationOnScreen(location)
                        cardOnScreen = coordinates.positionInRoot() +
                            Offset(location[0].toFloat(), location[1].toFloat())
                    }
                    .graphicsLayer {
                        val p = progress.value
                        val topLeft = cardOnScreen
                        if (
                            originOnScreen != null && topLeft != null &&
                            size.width > 0f && size.height > 0f
                        ) {
                            transformOrigin = TransformOrigin(
                                (originOnScreen.x - topLeft.x) / size.width,
                                (originOnScreen.y - topLeft.y) / size.height,
                            )
                        }
                        val scale = startScale + (1f - startScale) * p
                        scaleX = scale
                        scaleY = scale
                        alpha = (p * 2f).coerceIn(0f, 1f)
                    },
                shape = RoundedCornerShape(28.dp),
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                content(dismiss)
            }
            if (closing) {
                Box(Modifier.matchParentSize().pointerInput(Unit) {})
            }
        }
    }
}

enum class ConfirmTone { Primary, Neutral, Danger }

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    icon: ImageVector,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tone: ConfirmTone = ConfirmTone.Primary,
    dismissText: String = "取消",
    originOnScreen: Offset? = null,
) {
    ConfirmDialog(
        title = title,
        message = message,
        confirmText = confirmText,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        tone = tone,
        dismissText = dismissText,
        originOnScreen = originOnScreen,
    )
}

/**
 * 二次确认弹窗：圆形图标徽章 + 标题 + 说明 + 取消 / 确认两个等宽按钮。
 * 点击确认时立即回调 [onConfirm]（每次弹出至多一次），随后弹窗自行收回，
 * 动画结束后回调 [onDismiss]；调用方只应在 [onDismiss] 里移除弹窗。
 * 如果确认动作会连同弹窗的宿主一起移除，应改在 [onDismiss] 里执行，否则收回动画会被截断。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    icon: @Composable () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tone: ConfirmTone = ConfirmTone.Primary,
    dismissText: String = "取消",
    originOnScreen: Offset? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val badgeColor: Color
    val iconColor: Color
    val buttonColor: Color
    val onButtonColor: Color
    when (tone) {
        ConfirmTone.Primary -> {
            badgeColor = scheme.primaryContainer
            iconColor = scheme.primary
            buttonColor = scheme.primary
            onButtonColor = scheme.onPrimary
        }

        ConfirmTone.Neutral -> {
            badgeColor = scheme.onSurface.copy(alpha = 0.08f)
            iconColor = scheme.onSurface
            buttonColor = scheme.secondary
            onButtonColor = scheme.onSecondary
        }

        ConfirmTone.Danger -> {
            badgeColor = scheme.errorContainer
            iconColor = scheme.error
            buttonColor = scheme.error
            onButtonColor = scheme.onError
        }
    }

    PopDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { paneTitle = title },
        originOnScreen = originOnScreen,
    ) { dismiss ->
        Column(
            modifier = Modifier.padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(container = badgeColor, tint = iconColor, icon = icon)
            Spacer(Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { dismiss() },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = scheme.onSurface,
                    ),
                ) {
                    Text(dismissText, maxLines = 1)
                }
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (dismiss()) onConfirm()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        contentColor = onButtonColor,
                    ),
                ) {
                    Text(confirmText, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 表单弹窗：左对齐标题 + 可滚动内容 + 底部"取消 / 保存"按钮。
 * 保存时先收起弹窗，收回动画结束后再执行 [onConfirm]（调用方在 onConfirm 里移除弹窗）。
 */
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String = "保存",
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var confirmRequested by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    PopDialog(
        onDismissRequest = {
            val confirmed = confirmRequested
            confirmRequested = false
            if (confirmed) onConfirm() else onDismiss()
        },
        modifier = Modifier.semantics { paneTitle = title },
    ) { dismiss ->
        Column(
            modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                headerAction?.invoke()
            }
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { dismiss() },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("取消", maxLines = 1)
                }
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        confirmRequested = true
                        dismiss()
                    },
                    enabled = confirmEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Text(confirmText, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun IconBadge(
    container: Color,
    tint: Color,
    icon: @Composable () -> Unit,
) {
    val pop = remember { Animatable(0.7f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 380f))
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint, content = icon)
    }
}

/**
 * 锁上 / 开锁确认弹窗里的挂锁：弹窗出现后锁扣扣上（[locking]）或弹开。
 */
@Composable
fun LockConfirmIcon(
    locking: Boolean,
    modifier: Modifier = Modifier,
) {
    val open = remember { Animatable(if (locking) 1f else 0f) }
    LaunchedEffect(locking) {
        delay(240)
        open.animateTo(
            targetValue = if (locking) 0f else 1f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 450f),
        )
    }
    LockGlyph(openFraction = { open.value }, modifier = modifier.size(30.dp))
}

/**
 * 圆润的挂锁图标，按 24 格网格绘制。[openFraction] 为 0 时锁扣闭合，为 1 时抬起并向外甩开；
 * 弹簧动画的轻微越界（约 -0.15 到 1.15）不会画出边界。
 */
@Composable
fun LockGlyph(
    openFraction: () -> Float,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Canvas(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val u = size.minDimension / 24f
        val open = openFraction()
        val bodyTop = 10.75f * u
        val shackleLeft = 8.3f * u
        val shackleRight = 15.7f * u
        val radius = (shackleRight - shackleLeft) / 2f
        val arcCenterY = (8.4f - 2f * open) * u

        val shackle = Path().apply {
            moveTo(shackleLeft, 11.7f * u)
            lineTo(shackleLeft, arcCenterY)
            arcTo(
                rect = Rect(
                    left = shackleLeft,
                    top = arcCenterY - radius,
                    right = shackleRight,
                    bottom = arcCenterY + radius,
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(shackleRight, (11.7f - 2.9f * open) * u)
        }
        rotate(degrees = -10f * open, pivot = Offset(shackleLeft, bodyTop)) {
            drawPath(
                path = shackle,
                color = tint,
                style = Stroke(
                    width = 2.3f * u,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
        drawRoundRect(
            color = tint,
            topLeft = Offset(4.75f * u, bodyTop),
            size = Size(14.5f * u, 10.25f * u),
            cornerRadius = CornerRadius(3.25f * u),
        )
        drawCircle(
            color = Color.Black,
            radius = 1.45f * u,
            center = Offset(12f * u, 15.9f * u),
            blendMode = BlendMode.Clear,
        )
    }
}
