package dev.gaimoy.betterspeedtest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.gaimoy.betterspeedtest.data.SharedPreferenceStore
import io.github.lingqiqi5211.meowui.core.preference.PreferenceWriteResult
import io.github.lingqiqi5211.meowui.preference.currentMeowPreferenceStore
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.gaimoy.betterspeedtest.data.Settings
import io.github.lingqiqi5211.meowui.component.*
import io.github.lingqiqi5211.meowui.theme.MeowTheme

/**
 * 设置主页:精简的分组入口,细节拆到二级页。
 * 分组:测速(时长/聚合) · 引擎(连接数/预热) · 显示(单位/量程) · 外观 · 关于
 */
@Composable
fun SettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenTestParams: () -> Unit,
    onOpenEngine: () -> Unit,
    onOpenDisplay: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val uriHandler = LocalUriHandler.current

    MeowPreferenceScreen(scaffoldPadding = contentPadding) {
        MeowPreferenceSection(title = "测速") {
            MeowActionPreference(
                title = "测速参数",
                summary = "单向时长、多源聚合",
                navigation = true,
                onClick = onOpenTestParams,
            )
            MeowActionPreference(
                title = "引擎(高级)",
                summary = "连接数、预热时长等底层参数",
                navigation = true,
                onClick = onOpenEngine,
            )
        }

        MeowPreferenceSection(title = "界面") {
            MeowActionPreference(
                title = "显示设置",
                summary = "测速单位、表盘量程、刻度",
                navigation = true,
                onClick = onOpenDisplay,
            )
            MeowActionPreference(
                title = "主题与外观",
                summary = "界面风格、深浅模式、主题色",
                navigation = true,
                onClick = onOpenAppearance,
            )
        }

        MeowPreferenceSection(title = "关于") {
            MeowActionPreference(
                title = "源码 (better-speedtest)",
                summary = "github.com/Gaimoydev/better-speedtest",
                onClick = { uriHandler.openUri("https://github.com/Gaimoydev/better-speedtest") },
            )
            MeowActionPreference(
                title = "UI 库 (MeowUI)",
                summary = "github.com/lingqiqi5211/MeowUI",
                onClick = { uriHandler.openUri("https://github.com/lingqiqi5211/MeowUI") },
            )
            MeowActionPreference(
                title = "版本",
                value = "1.0.0",
                summary = "Android 重制版:移除 AT 定位/MCC-MNC 等设备端逻辑",
                onClick = {},
            )
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                text = "仅用于测量你拥有或被授权测试的网络。请在流量充足时使用,移动数据下注意测速产生的流量消耗。",
                style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurfaceVariant),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 二级页:测速参数
// ──────────────────────────────────────────────────────────────

private val DURATIONS = listOf(5, 10, 15, 20, 30)
private val MULTIS = listOf(1, 2, 3, 4)

@Composable
fun TestParamsScreen(onBack: () -> Unit) {
    val duration by io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue(Settings.DurationSec)
    val store = currentMeowPreferenceStore() as SharedPreferenceStore
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    MeowPreferencePage(
        title = "测速参数",
        onBackClick = onBack,
    ) {
        MeowPreferenceSection(title = "时长") {
            MeowPopupPreference(
                title = "单向时长",
                summary = "下载、上传各自测试的秒数",
                value = duration,
                enabled = !saving,
                onValueChange = { selected ->
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                if (store.writeTestDuration(selected) != PreferenceWriteResult.Success)
                                    Toast.makeText(context, "时长未能保存，请重试", Toast.LENGTH_SHORT).show()
                            } finally { saving = false }
                        }
                    }
                },
                options = DURATIONS,
                optionLabel = { "$it 秒" },
            )
        }
        MeowPreferenceSection(title = "聚合") {
            MeowPopupPreference(
                title = "多源聚合",
                summary = "同时使用最快的 N 个源,吞吐求和,突破单源限速",
                key = Settings.Multi,
                options = MULTIS,
                optionLabel = { if (it == 1) "关闭(单源)" else "$it 源" },
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 二级页:引擎(高级)
// ──────────────────────────────────────────────────────────────

private val THREAD_COUNTS = listOf(1, 2, 4, 6, 8, 16, 32, 64)

@Composable
fun EngineSettingsScreen(onBack: () -> Unit) {
    val store = currentMeowPreferenceStore() as SharedPreferenceStore
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    val duration by io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue(Settings.DurationSec)
    val warmup by io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue(Settings.WarmupSec)
    MeowPreferencePage(
        title = "引擎(高级)",
        onBackClick = onBack,
    ) {
        MeowPreferenceSection(title = "下载连接") {
            MeowPopupPreference(
                title = "初始连接数",
                key = Settings.ThreadsDl,
                options = THREAD_COUNTS,
                optionLabel = { "$it" },
            )
            MeowPopupPreference(
                title = "连接上限",
                key = Settings.ThreadsDlMax,
                options = THREAD_COUNTS,
                optionLabel = { "$it" },
            )
        }
        MeowPreferenceSection(title = "上传连接") {
            MeowPopupPreference(
                title = "初始连接数",
                key = Settings.ThreadsUl,
                options = THREAD_COUNTS.filter { it <= 32 },
                optionLabel = { "$it" },
            )
            MeowPopupPreference(
                title = "连接上限",
                key = Settings.ThreadsUlMax,
                options = THREAD_COUNTS.filter { it <= 32 },
                optionLabel = { "$it" },
            )
        }
        MeowPreferenceSection(title = "统计") {
            MeowPopupPreference(
                title = "预热时长",
                value = Settings.effectiveWarmup(duration, warmup),
                enabled = !saving,
                onValueChange = { selected ->
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                if (store.writeTestWarmup(selected) != PreferenceWriteResult.Success)
                                    Toast.makeText(context, "预热时长未能保存，请重试", Toast.LENGTH_SHORT).show()
                            } finally { saving = false }
                        }
                    }
                },
                options = Settings.WarmupOptions.filter { it < duration },
                optionLabel = { "$it 秒" },
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 二级页:显示设置
// ──────────────────────────────────────────────────────────────

private val SPEED_UNITS = listOf("auto", "Mbps", "MB/s", "Gbps")
private val GAUGE_RANGE_MODES = listOf("auto", "fixed")
private val GAUGE_FIXED_MBPS = listOf(100, 200, 500, 1000, 2000, 5000, 10000)
private val GAUGE_DIGITS = listOf("big", "compact")

@Composable
fun DisplaySettingsScreen(onBack: () -> Unit) {
    // 量程模式控制"固定满量程"是否显示(自动模式下该项无意义)
    val rangeMode by io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue(Settings.GaugeRangeMode)
    val unit by io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue(Settings.SpeedUnit)

    MeowPreferencePage(
        title = "显示设置",
        onBackClick = onBack,
    ) {
        MeowPreferenceSection(title = "单位") {
            MeowPopupPreference(
                title = "测速单位",
                key = Settings.SpeedUnit,
                options = SPEED_UNITS,
                optionLabel = { if (it == "auto") "自动" else it },
            )
        }
        MeowPreferenceSection(title = "表盘") {
            MeowPopupPreference(
                title = "量程模式",
                key = Settings.GaugeRangeMode,
                options = GAUGE_RANGE_MODES,
                optionLabel = { if (it == "auto") "自动" else "固定" },
            )
            item(key = Settings.GaugeRangeFixed.name, visible = rangeMode == "fixed", container = false) {
                io.github.lingqiqi5211.meowui.component.MeowPopupPreference(
                    title = "固定满量程",
                    key = Settings.GaugeRangeFixed,
                    options = GAUGE_FIXED_MBPS,
                    optionLabel = { dev.gaimoy.betterspeedtest.core.SpeedFormat.format(it.toDouble(), unit) },
                )
            }
            MeowPopupPreference(
                title = "数显风格",
                key = Settings.GaugeDigits,
                options = GAUGE_DIGITS,
                optionLabel = { if (it == "big") "大数" else "紧凑" },
            )
            MeowSwitchPreference(
                title = "弧端进度点",
                key = Settings.GaugeShowDot,
            )
            MeowSwitchPreference(
                title = "量程刻度",
                key = Settings.GaugeShowScale,
            )
        }
    }
}
