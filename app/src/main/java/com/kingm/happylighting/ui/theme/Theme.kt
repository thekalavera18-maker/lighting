package com.kingm.happylighting.ui.theme

import android.graphics.Color.HSVToColor
import android.graphics.Color.colorToHSV
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kingm.happylighting.model.AppStyleState
import kotlin.math.max
import kotlin.math.min

@Composable
fun HappyLightingTheme(
    appStyle: AppStyleState,
    content: @Composable () -> Unit,
) {
    val scheme = remember(appStyle) { buildScheme(appStyle) }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}

private fun buildScheme(appStyle: AppStyleState) = darkColorScheme(
    primary = accentFromStyle(appStyle),
    secondary = shiftHue(accentFromStyle(appStyle), 96f, 0.78f),
    tertiary = shiftHue(accentFromStyle(appStyle), 208f, 0.86f),
    background = backgroundColor(appStyle),
    surface = surfaceColor(appStyle),
    surfaceVariant = surfaceVariantColor(appStyle),
    onPrimary = if (accentFromStyle(appStyle).luminance() > 0.58f) Color(0xFF101217) else Color(0xFFF5F8FC),
    onBackground = contrastText(appStyle),
    onSurface = contrastText(appStyle),
)

private fun accentFromStyle(appStyle: AppStyleState): Color {
    val hsv = FloatArray(3)
    colorToHSV(
        android.graphics.Color.rgb(
            appStyle.accentColor.first,
            appStyle.accentColor.second,
            appStyle.accentColor.third,
        ),
        hsv,
    )
    hsv[1] = (hsv[1] * (appStyle.saturation / 100f)).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * (0.92f + ((appStyle.contrast - 100) / 300f))).coerceIn(0.25f, 1f)
    val colorInt = HSVToColor(hsv)
    return Color(
        red = android.graphics.Color.red(colorInt),
        green = android.graphics.Color.green(colorInt),
        blue = android.graphics.Color.blue(colorInt),
    )
}

private fun shiftHue(source: Color, degrees: Float, valueScale: Float): Color {
    val hsv = FloatArray(3)
    colorToHSV(
        android.graphics.Color.rgb(
            (source.red * 255).toInt(),
            (source.green * 255).toInt(),
            (source.blue * 255).toInt(),
        ),
        hsv,
    )
    hsv[0] = (hsv[0] + degrees) % 360f
    hsv[1] = (hsv[1] * 0.92f).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * valueScale).coerceIn(0f, 1f)
    val colorInt = HSVToColor(hsv)
    return Color(
        red = android.graphics.Color.red(colorInt),
        green = android.graphics.Color.green(colorInt),
        blue = android.graphics.Color.blue(colorInt),
    )
}

private fun blend(base: Color, accent: Color, amount: Float): Color = Color(
    red = lerp(base.red, accent.red, amount),
    green = lerp(base.green, accent.green, amount),
    blue = lerp(base.blue, accent.blue, amount),
)

private fun backgroundColor(appStyle: AppStyleState): Color =
    if (appStyle.amoledMode) {
        blend(Color(0xFF000000), accentFromStyle(appStyle), 0.02f)
    } else {
        blend(Color(0xFF091019), accentFromStyle(appStyle), 0.08f)
    }

private fun surfaceColor(appStyle: AppStyleState): Color =
    if (appStyle.amoledMode) {
        blend(Color(0xFF050505), accentFromStyle(appStyle), 0.05f)
    } else {
        blend(Color(0xFF121923), accentFromStyle(appStyle), 0.14f)
    }

private fun surfaceVariantColor(appStyle: AppStyleState): Color =
    if (appStyle.amoledMode) {
        blend(Color(0xFF0A0A0A), accentFromStyle(appStyle), 0.08f)
    } else {
        blend(Color(0xFF18212C), accentFromStyle(appStyle), 0.20f)
    }

private fun contrastText(appStyle: AppStyleState): Color {
    val normalized = ((appStyle.contrast - 60) / 100f).coerceIn(0f, 1f)
    val channel = lerp(0.78f, 0.96f, normalized)
    return Color(channel, channel + 0.015f, min(channel + 0.05f, 1f))
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    max(0f, min(1f, start + ((end - start) * fraction)))
