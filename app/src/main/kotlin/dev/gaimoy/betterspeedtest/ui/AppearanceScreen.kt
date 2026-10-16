package dev.gaimoy.betterspeedtest.ui

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lingqiqi5211.meowui.component.MeowAppearancePreview
import io.github.lingqiqi5211.meowui.component.MeowColorPicker
import io.github.lingqiqi5211.meowui.component.MeowPopupPreference
import io.github.lingqiqi5211.meowui.component.MeowPreferencePage
import io.github.lingqiqi5211.meowui.component.MeowPreferenceSection
import io.github.lingqiqi5211.meowui.component.MeowSliderPreference
import io.github.lingqiqi5211.meowui.component.MeowSwitchPreference
import io.github.lingqiqi5211.meowui.core.MeowUiStyle
import io.github.lingqiqi5211.meowui.theme.MeowAppearance
import io.github.lingqiqi5211.meowui.theme.MeowColorSpec
import io.github.lingqiqi5211.meowui.theme.MeowPaletteStyle
import io.github.lingqiqi5211.meowui.theme.MeowThemeMode

private val THEME_MODE_LABELS = listOf("跟随系统", "浅色", "深色")

/**
 * 自建外观页(库文档认可的方式:同一批积木自己拼)。
 * 与内置 MeowAppearancePage 的差别:深浅模式用滑动胶囊分段控件替代 TabRow。
 */
@Composable
fun AppearanceScreen(
    appearance: MeowAppearance,
    onChange: (MeowAppearance) -> Unit,
    onBack: () -> Unit,
) {
    val modes = MeowThemeMode.entries
    MeowPreferencePage(title = "外观", onBackClick = onBack) {
        MeowAppearancePreview()

        SlidingSegmentedControl(
            items = THEME_MODE_LABELS,
            selectedIndex = modes.indexOf(appearance.themeMode),
            onSelected = { onChange(appearance.copy(themeMode = modes[it])) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )

        val colorCustomizable = appearance.style != MeowUiStyle.Miuix || appearance.miuixMonetEnabled
        if (colorCustomizable) MeowColorPicker(
            dynamicColor = appearance.dynamicColor,
            seedColor = appearance.seedColor,
            paletteStyle = appearance.paletteStyle,
            colorSpec = appearance.colorSpec,
            onDynamicColorChange = { onChange(appearance.copy(dynamicColor = it)) },
            onSeedColorChange = { onChange(appearance.copy(dynamicColor = false, seedColor = it)) },
            customColorTitle = "自定义颜色",
            confirmText = "完成",
            cancelText = "取消",
            modifier = Modifier.padding(top = 12.dp),
        )

        MeowPreferenceSection(title = "颜色") {
            if (appearance.style == MeowUiStyle.Miuix) {
                MeowSwitchPreference(
                    title = "Monet 取色",
                    summary = "关闭后使用 Miuix 原生配色",
                    checked = appearance.miuixMonetEnabled,
                    onCheckedChange = { onChange(appearance.copy(miuixMonetEnabled = it)) },
                )
            }
            if (appearance.style == MeowUiStyle.MaterialExpressive) {
                MeowSwitchPreference(
                    title = "AMOLED 纯黑",
                    summary = "深色模式下使用纯黑背景",
                    checked = appearance.amoledDarkEnabled,
                    onCheckedChange = { onChange(appearance.copy(amoledDarkEnabled = it)) },
                )
            }
            if (colorCustomizable) {
                MeowPopupPreference(
                    title = "调色板风格",
                    value = appearance.paletteStyle,
                    options = MeowPaletteStyle.entries,
                    onValueChange = { onChange(appearance.copy(paletteStyle = it)) },
                )
                MeowPopupPreference(
                    title = "色彩标准",
                    value = appearance.colorSpec,
                    options = MeowColorSpec.entries,
                    onValueChange = { onChange(appearance.copy(colorSpec = it)) },
                )
            }
        }

        MeowPreferenceSection(title = "界面") {
            MeowPopupPreference(
                title = "界面风格",
                value = appearance.style,
                options = listOf(MeowUiStyle.MaterialExpressive, MeowUiStyle.Miuix),
                optionLabel = { if (it == MeowUiStyle.Miuix) "Miuix" else "Material 3 Expressive" },
                onValueChange = { onChange(appearance.copy(style = it)) },
            )
            MeowSwitchPreference(
                title = "背景模糊",
                summary = "顶栏磨砂,系统不支持时自动回退",
                checked = appearance.blurEnabled,
                onCheckedChange = { onChange(appearance.copy(blurEnabled = it)) },
            )
            if (Build.VERSION.SDK_INT >= 34) {
                MeowSwitchPreference(
                    title = "预测式返回",
                    summary = "返回时预览目标页面",
                    checked = appearance.predictiveBackEnabled,
                    onCheckedChange = { onChange(appearance.copy(predictiveBackEnabled = it)) },
                )
            }
            var scaleDraft by remember(appearance.interfaceScale) {
                mutableFloatStateOf(appearance.interfaceScale)
            }
            MeowSliderPreference(
                title = "界面缩放",
                summary = "调整整个界面的大小",
                value = scaleDraft,
                onValueChange = { scaleDraft = it },
                onValueChangeFinished = { onChange(appearance.copy(interfaceScale = scaleDraft)) },
                valueRange = 0.8f..1.1f,
                valueText = { "${(it * 100).toInt()}%" },
            )
        }
    }
}
