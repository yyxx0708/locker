package com.yxsdc.locker.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val LockerBlue = Color(0xFF2A78D6)
val LockerBlueDark = Color(0xFF3987E5)
val LockerOrange = Color(0xFFEB6834)
val LockerOrangeDark = Color(0xFFD95926)

private val ChartSurfaceLight = Color(0xFFFCFCFB)
private val ChartSurfaceDark = Color(0xFF1A1A19)
private val ChartInkLight = Color(0xFF0B0B0B)
private val ChartInkDark = Color(0xFFF0EFEC)
private val ChartSecondaryLight = Color(0xFF52514E)
private val ChartSecondaryDark = Color(0xFFC3C2B7)
private val ChartMutedLight = Color(0xFF6E6C66)
private val ChartMutedDark = Color(0xFF898781)
private val ChartBaselineLight = Color(0xFFC3C2B7)
private val ChartBaselineDark = Color(0xFF383835)

val LocalDarkTheme = staticCompositionLocalOf { false }

// 卡片圆角比 Material 默认的 12dp 更圆润，与弹窗风格一致
private val LockerShapes = Shapes(
    medium = RoundedCornerShape(20.dp),
)

data class ChartPalette(
    val series1: Color,
    val series2: Color,
    val ink: Color,
    val secondary: Color,
    val muted: Color,
    val baseline: Color,
)

@Composable
fun chartPalette(): ChartPalette = if (LocalDarkTheme.current) {
    ChartPalette(
        series1 = LockerBlueDark,
        series2 = LockerOrangeDark,
        ink = ChartInkDark,
        secondary = ChartSecondaryDark,
        muted = ChartMutedDark,
        baseline = ChartBaselineDark,
    )
} else {
    ChartPalette(
        series1 = LockerBlue,
        series2 = LockerOrange,
        ink = ChartInkLight,
        secondary = ChartSecondaryLight,
        muted = ChartMutedLight,
        baseline = ChartBaselineLight,
    )
}

private val LightColors = lightColorScheme(
    primary = LockerBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E7FB),
    onPrimaryContainer = Color(0xFF0B2B4F),
    secondary = Color(0xFF52514E),
    secondaryContainer = Color(0xFFEAE8E1),
    onSecondaryContainer = Color(0xFF2B2A26),
    background = Color(0xFFF9F9F7),
    surface = ChartSurfaceLight,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = ChartSurfaceLight,
    surfaceContainer = Color(0xFFF4F3EF),
    surfaceContainerHigh = Color(0xFFEEEDE8),
    surfaceContainerHighest = Color(0xFFE7E6E0),
    onSurface = ChartInkLight,
    onSurfaceVariant = ChartSecondaryLight,
    outline = ChartBaselineLight,
    outlineVariant = Color(0xFFE4E3DD),
)

private val DarkColors = darkColorScheme(
    primary = LockerBlueDark,
    onPrimary = Color(0xFF06182E),
    primaryContainer = Color(0xFF123B66),
    onPrimaryContainer = Color(0xFFD7E7FB),
    secondary = Color(0xFFC3C2B7),
    onSecondary = Color(0xFF141412),
    secondaryContainer = Color(0xFF3A3934),
    onSecondaryContainer = Color(0xFFE7E5DE),
    background = Color(0xFF0D0D0D),
    surface = ChartSurfaceDark,
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = ChartSurfaceDark,
    surfaceContainer = Color(0xFF201F1E),
    surfaceContainerHigh = Color(0xFF262523),
    surfaceContainerHighest = Color(0xFF2D2C2A),
    onSurface = ChartInkDark,
    onSurfaceVariant = ChartSecondaryDark,
    outline = ChartBaselineDark,
    outlineVariant = Color(0xFF302F2C),
)

@Composable
fun lockerCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface,
)

@Composable
private fun animateThemeColor(target: Color, label: String): Color {
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 450),
        label = label,
    )
    return color
}

/**
 * 切换深浅主题时让常用颜色平滑过渡，而不是瞬间跳变。
 */
@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme = target.copy(
    primary = animateThemeColor(target.primary, "primary"),
    onPrimary = animateThemeColor(target.onPrimary, "onPrimary"),
    primaryContainer = animateThemeColor(target.primaryContainer, "primaryContainer"),
    onPrimaryContainer = animateThemeColor(target.onPrimaryContainer, "onPrimaryContainer"),
    secondary = animateThemeColor(target.secondary, "secondary"),
    onSecondary = animateThemeColor(target.onSecondary, "onSecondary"),
    secondaryContainer = animateThemeColor(
        target.secondaryContainer, "secondaryContainer"
    ),
    onSecondaryContainer = animateThemeColor(
        target.onSecondaryContainer, "onSecondaryContainer"
    ),
    background = animateThemeColor(target.background, "background"),
    onBackground = animateThemeColor(target.onBackground, "onBackground"),
    surface = animateThemeColor(target.surface, "surface"),
    onSurface = animateThemeColor(target.onSurface, "onSurface"),
    surfaceContainerLowest = animateThemeColor(
        target.surfaceContainerLowest, "surfaceContainerLowest"
    ),
    surfaceContainerLow = animateThemeColor(target.surfaceContainerLow, "surfaceContainerLow"),
    surfaceContainer = animateThemeColor(target.surfaceContainer, "surfaceContainer"),
    surfaceContainerHigh = animateThemeColor(target.surfaceContainerHigh, "surfaceContainerHigh"),
    surfaceContainerHighest = animateThemeColor(
        target.surfaceContainerHighest, "surfaceContainerHighest"
    ),
    onSurfaceVariant = animateThemeColor(target.onSurfaceVariant, "onSurfaceVariant"),
    outline = animateThemeColor(target.outline, "outline"),
    outlineVariant = animateThemeColor(target.outlineVariant, "outlineVariant"),
)

@Composable
fun LockerTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = animatedColorScheme(if (darkTheme) DarkColors else LightColors),
            shapes = LockerShapes,
            content = content,
        )
    }
}
