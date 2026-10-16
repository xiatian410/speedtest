package dev.gaimoy.betterspeedtest.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import io.github.lingqiqi5211.meowui.core.MeowUiStyle
import kotlin.math.abs

/**
 * 中性底(轨道/胶囊底)兜底:优先用库的 surfaceVariant —— MeowUI 已在两种风格下
 * 各自调好对比;仅当它与背景完全同色(动态取色极端情况)时做最小混合。
 * 强调色不做任何"修正":MeowColorScheme 的 primary/onPrimary 就是
 * 各风格设计体系内的正确配色(Miuix 原生灰即 Miuix 的设计语言)。
 */
@Composable
fun trackColorOf(): Color {
    val colors = MeowTheme.colors
    // 0.1.5 的 Scaffold 底色并非 background：M3 用 surfaceContainer，Miuix 用 surface。
    // M3 的 surfaceVariant 也映射 surfaceContainer，必须比较实际页面才能识别同色轨道。
    val pageColor = if (MeowTheme.style == MeowUiStyle.MaterialExpressive) {
        colors.surfaceVariant
    } else {
        colors.surface
    }
    return if (abs(colors.surfaceVariant.luminance() - pageColor.luminance()) < 0.02f) {
        lerp(pageColor, colors.onSurface, 0.10f)
    } else {
        colors.surfaceVariant
    }
}
