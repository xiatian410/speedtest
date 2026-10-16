@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.gaimoy.betterspeedtest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import android.widget.Toast
import dev.gaimoy.betterspeedtest.data.SharedPreferenceStore
import dev.gaimoy.betterspeedtest.core.SpeedFormat
import dev.gaimoy.betterspeedtest.core.UPLOAD_UNCONFIRMED
import io.github.lingqiqi5211.meowui.core.preference.PreferenceWriteResult
import io.github.lingqiqi5211.meowui.preference.currentMeowPreferenceStore
import io.github.lingqiqi5211.meowui.component.MeowTip
import io.github.lingqiqi5211.meowui.component.MeowTipStyle
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.gaimoy.betterspeedtest.core.Direction
import dev.gaimoy.betterspeedtest.core.DirectionState
import dev.gaimoy.betterspeedtest.core.IpLocation
import dev.gaimoy.betterspeedtest.core.Node
import dev.gaimoy.betterspeedtest.core.RunStatus
import dev.gaimoy.betterspeedtest.core.SpeedtestResult
import dev.gaimoy.betterspeedtest.core.SpeedtestRunner
import dev.gaimoy.betterspeedtest.core.TestUiState
import dev.gaimoy.betterspeedtest.data.Settings
import io.github.lingqiqi5211.meowui.component.MeowActionPreference
import io.github.lingqiqi5211.meowui.component.MeowBottomSheet
import io.github.lingqiqi5211.meowui.component.MeowCard
import io.github.lingqiqi5211.meowui.component.MeowPopupPreference
import io.github.lingqiqi5211.meowui.component.MeowPreferenceSection
import io.github.lingqiqi5211.meowui.component.meowScaffoldScroll
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceWriter
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val SOURCE_OPTIONS = listOf("auto", "cnspeed", "ookla")

private fun sourceLabel(s: String): String = when (s) {
    "cnspeed" -> "全球网测"
    "ookla" -> "Speedtest.net"
    else -> "自动"
}

private val MODE_TABS = listOf("双测", "仅下行", "仅上行")

/**
 * 只订阅状态的某个切片(distinct):重组被限制在真正用到它的叶子组件里,
 * 200ms 高频 tick 不会牵动整页 —— 这是主页性能的关键结构。
 */
@Composable
private fun <R : Any> selectState(runner: SpeedtestRunner, selector: (TestUiState) -> R): State<R> {
    val currentSelector by rememberUpdatedState(selector)
    val flow = remember(runner) { runner.state.map { currentSelector(it) }.distinctUntilChanged() }
    return flow.collectAsStateWithLifecycle(initialValue = selector(runner.state.value))
}

/** IPv4 掩码:保留前两段 → 1.2.*.*;空值显示 —。 */
private fun maskIp(ip: String): String {
    if (ip.isEmpty()) return "—"
    val parts = ip.split(".")
    if (parts.size == 4) return parts[0] + "." + parts[1] + ".*.*"
    return ip.take(2) + "…"
}

private fun locationText(loc: IpLocation?): String {
    if (loc == null) return "—"
    val place = loc.city.ifEmpty { loc.province }
    return listOfNotNull(loc.carrier.takeIf { it.isNotEmpty() }, place.takeIf { it.isNotEmpty() })
        .joinToString(" ").ifEmpty { "—" }
}

/** 主页:模式切换 + 单表盘 + 延迟/抖动/IP/位置 + 双曲线 + 按钮 + 快捷选项。 */
@Composable
fun MainScreen(
    runner: SpeedtestRunner,
    pickedNode: Node?,
    onPickNode: () -> Unit,
    onClearPickedNode: () -> Unit,
    onStart: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val source by rememberMeowPreferenceValue(Settings.Source)
    val duration by rememberMeowPreferenceValue(Settings.DurationSec)
    val doDownload by rememberMeowPreferenceValue(Settings.DoDownload)
    val doUpload by rememberMeowPreferenceValue(Settings.DoUpload)
    val speedUnit by rememberMeowPreferenceValue(Settings.SpeedUnit)
    val gaugeRangeMode by rememberMeowPreferenceValue(Settings.GaugeRangeMode)
    val gaugeRangeFixed by rememberMeowPreferenceValue(Settings.GaugeRangeFixed)
    val gaugeShowScale by rememberMeowPreferenceValue(Settings.GaugeShowScale)
    val gaugeShowDot by rememberMeowPreferenceValue(Settings.GaugeShowDot)
    val gaugeDigits by rememberMeowPreferenceValue(Settings.GaugeDigits)
    val session by selectState(runner) { it.status to it.activeOptions }
    val running = session.first == RunStatus.RUNNING
    val displayDownload = session.second?.doDownload ?: doDownload
    val displayUpload = session.second?.doUpload ?: doUpload
    val displayDuration = session.second?.durationSec ?: duration
    val store = currentMeowPreferenceStore() as SharedPreferenceStore
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var savingMode by remember { mutableStateOf(false) }
    val layoutDirection = LocalLayoutDirection.current

    val modeDownload = if (running) displayDownload else doDownload
    val modeUpload = if (running) displayUpload else doUpload
    val modeIndex = when {
        modeDownload && modeUpload -> 0
        modeDownload -> 1
        modeUpload -> 2
        else -> 0
    }

    // 进入主页立即预取 IP/位置(测速前的状态卡也能显示;无网保持 —)
    androidx.compose.runtime.LaunchedEffect(runner) { runner.ensureIpPrefetch() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .meowScaffoldScroll()
            .verticalScroll(rememberScrollState())
            .padding(start = contentPadding.calculateStartPadding(layoutDirection) + 16.dp,
                end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Spacer(Modifier.height(contentPadding.calculateTopPadding() + 12.dp))

        // 模式切换(静态,不随 tick 重组)
        SlidingSegmentedControl(
            items = MODE_TABS,
            selectedIndex = modeIndex,
            onSelected = { i ->
                if (!running && !savingMode) {
                    savingMode = true
                    scope.launch {
                        try {
                            if (store.writeTestMode(i != 2, i != 1) != PreferenceWriteResult.Success) {
                                Toast.makeText(context, "模式未能保存，请重试", Toast.LENGTH_SHORT).show()
                            }
                        } finally { savingMode = false }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running && !savingMode,
        )

        // 表盘(高频叶子:只有它跟 tick 重组)
        GaugeSection(
            runner = runner,
            duration = displayDuration,
            doDownload = displayDownload,
            doUpload = displayUpload,
            unit = speedUnit,
            rangeMode = gaugeRangeMode,
            rangeFixedMbps = gaugeRangeFixed,
            showScale = gaugeShowScale,
            showDot = gaugeShowDot,
            digits = gaugeDigits,
        )

        // 延迟/抖动/IP/位置 胶囊(低频叶子)
        StatChipsSection(runner)

        // 曲线卡(高频叶子)
        ChartSection(
            runner = runner,
            duration = displayDuration,
            doDownload = displayDownload,
            doUpload = displayUpload,
            unit = speedUnit,
        )

        // 按钮(低频叶子:仅状态切换时重组)
        ButtonsSection(runner = runner, onStart = onStart)

        // 测速源(静态)
        MeowPreferenceSection(title = "测速源") {
            MeowPopupPreference(
                title = "源",
                key = Settings.Source,
                options = SOURCE_OPTIONS,
                enabled = !running,
                optionLabel = { sourceLabel(it) },
                onValueChange = { onClearPickedNode() },
            )
            if (source != "auto") {
                MeowActionPreference(
                    title = "节点",
                    summary = pickedNode?.name ?: "自动(就近/择优)",
                    navigation = true,
                    enabled = !running,
                    onClick = onPickNode,
                )
                if (pickedNode != null) {
                    MeowActionPreference(
                        title = "清除指定节点",
                        summary = "恢复自动选择",
                        enabled = !running,
                        onClick = onClearPickedNode,
                    )
                }
            }
        }

        // 日志(低频叶子)
        LogSection(runner)

        Spacer(Modifier.height(contentPadding.calculateBottomPadding() + 12.dp))
    }
}

@Composable
private fun GaugeSection(
    runner: SpeedtestRunner,
    duration: Int,
    doDownload: Boolean,
    doUpload: Boolean,
    unit: String,
    rangeMode: String,
    rangeFixedMbps: Int,
    showScale: Boolean,
    showDot: Boolean,
    digits: String,
) {
    val state by runner.state.collectAsStateWithLifecycle()
    val running = state.status == RunStatus.RUNNING
    val live = if (state.direction == Direction.DOWNLOAD) state.dl else state.ul
    val gaugeMbps = when {
        running -> live.mbps
        state.status == RunStatus.DONE ->
            if (doDownload) (state.result?.dlAvg ?: 0.0) else (state.result?.ulAvg ?: 0.0)
        else -> 0.0
    }
    val gaugePeak = when {
        running -> live.peakMbps
        state.status == RunStatus.DONE -> if (doDownload) state.result?.dlPeak ?: 0.0 else state.result?.ulPeak ?: 0.0
        else -> 0.0
    }
    val gaugeColor = when {
        running && state.direction == Direction.UPLOAD -> UploadChartColor
        running -> null
        state.status == RunStatus.DONE && !doDownload -> UploadChartColor
        else -> null
    }
    val gaugeTitle = when {
        running && state.direction == Direction.DOWNLOAD ->
            "下载 " + "%.0f/%ds".format(state.dl.progressSec, duration)
        running ->
            "上传 " + "%.0f/%ds".format(state.ul.progressSec, duration)
        state.status == RunStatus.DONE && !doDownload && state.result?.ulMsg == UPLOAD_UNCONFIRMED -> "未确认"
        state.status == RunStatus.DONE -> "完成"
        state.status == RunStatus.FAILED -> "失败"
        state.status == RunStatus.CANCELLED -> "已停止"
        else -> ""
    }
    // GaugeSection 已不在 Column 作用域内,不能用 align;用等宽 Box 承载实现水平居中
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        SpeedGauge(
            mbps = gaugeMbps,
            peakMbps = maxOf(gaugePeak, gaugeMbps),
            title = gaugeTitle,
            progressColor = gaugeColor,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f),
            unit = unit,
            rangeMode = rangeMode,
            rangeFixedMbps = rangeFixedMbps,
            showScale = showScale,
            showDot = showDot,
            digits = digits,
        )
    }
}

@Composable
private fun StatChipsSection(runner: SpeedtestRunner) {
    // 只取需要的字段:延迟/抖动(实时优先)与 IP 信息,变化频率低
    data class ChipData(
        val livePing: Double,
        val liveJitter: Double,
        val resultPing: Double,
        val resultJitter: Double,
        val ip: IpLocation?,
    )

    val data by selectState(runner) {
        ChipData(it.livePingMs, it.liveJitter, it.result?.pingMs ?: 0.0, it.result?.jitter ?: 0.0, it.ipInfo)
    }
    var ipRevealed by rememberSaveable { mutableStateOf(false) }

    val pingShow = when {
        data.livePing > 0 -> "%.0f ms".format(data.livePing)
        data.resultPing > 0 -> "%.0f ms".format(data.resultPing)
        else -> "—"
    }
    val jitShow = when {
        data.livePing > 0 -> "%.1f ms".format(data.liveJitter)
        data.resultPing > 0 -> "%.1f ms".format(data.resultJitter)
        else -> "—"
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            StatChip("延迟", pingShow)
            StatChip("抖动", jitShow)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            StatChip(
                label = "IP",
                value = maskIp(data.ip?.ip ?: "").let { if (!ipRevealed) it else (data.ip?.ip ?: "—") },
                onClick = { if ((data.ip?.ip ?: "").isNotEmpty()) ipRevealed = !ipRevealed },
            )
            StatChip("位置", locationText(data.ip))
        }
    }
}

@Composable
private fun ChartSection(
    runner: SpeedtestRunner,
    duration: Int,
    doDownload: Boolean,
    doUpload: Boolean,
    unit: String,
) {
    val state by runner.state.collectAsStateWithLifecycle()
    val running = state.status == RunStatus.RUNNING
    val result = state.result
    val downloadAccent = MeowTheme.colors.primary
    BoxWithConstraints {
        val download: @Composable (Modifier) -> Unit = { modifier ->
            DirectionChartCard("下载", downloadAccent, running && state.direction == Direction.DOWNLOAD,
                state.dl, result?.dlAvg, result?.dlPeak, duration, unit, state.status, modifier = modifier)
        }
        val upload: @Composable (Modifier) -> Unit = { modifier ->
            DirectionChartCard("上传", UploadChartColor, running && state.direction == Direction.UPLOAD,
                state.ul, result?.ulAvg, result?.ulPeak, duration, unit, state.status,
                resultMessage = result?.ulMsg.orEmpty(), modifier = modifier)
        }
        if (doDownload && doUpload && maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.3f) {
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                download(Modifier.weight(1f)); upload(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                if (doDownload) download(Modifier.fillMaxWidth())
                if (doUpload) upload(Modifier.fillMaxWidth())
            }
        }
    }
    if (result != null && result.ulMsg.isNotEmpty()) {
        MeowCard {
            BasicText(
                text = "上传: ${result.ulMsg}",
                style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface),
            )
        }
    }
}

@Composable
private fun ButtonsSection(runner: SpeedtestRunner, onStart: () -> Unit) {
    data class ButtonData(val status: RunStatus)

    val data by selectState(runner) { ButtonData(it.status) }
    when {
        data.status == RunStatus.RUNNING -> PrimaryButton(
            text = "停止测速",
            onClick = { runner.stop() },
            modifier = Modifier.fillMaxWidth(),
        )
        else -> PrimaryButton(
            text = if (data.status == RunStatus.IDLE) "开始测速" else "再次测速",
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LogSection(runner: SpeedtestRunner) {
    val log by selectState(runner) { it.log }
    val error by selectState(runner) { it.error.orEmpty() }
    if (error.isNotEmpty()) MeowTip(title = "测速失败", message = error, style = MeowTipStyle.Error)
    if (log.isEmpty()) return
    MeowCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            log.takeLast(8).reversed().forEach { line ->
                BasicText(
                    text = line,
                    style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface),
                )
            }
        }
    }
}

@Composable
private fun DirectionChartCard(
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    active: Boolean,
    state: DirectionState,
    resultAvg: Double?,
    resultPeak: Double?,
    durationSec: Int,
    unit: String,
    runStatus: RunStatus,
    resultMessage: String = "",
    modifier: Modifier = Modifier,
) {
    val colors = MeowTheme.colors
    val measured = if (resultAvg != null) resultAvg.isFinite() && resultAvg > 0.0 else active || state.avgMbps > 0.05
    val avg = resultAvg ?: state.avgMbps
    val peak = resultPeak ?: state.peakMbps
    val chartUnit = SpeedFormat.unitLabel(unit, maxOf(avg, peak))
    val outcome = directionOutcome(runStatus, active, resultAvg, state.samples.isNotEmpty(), resultMessage)
    val statusText = if (active) "%.0f/%ds".format(state.progressSec, durationSec) else outcome
    MeowCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = title,
                style = MeowTheme.typography.title.copy(
                    color = if (measured || active) accent else colors.onSurface,
                ),
            )
            BasicText(
                text = statusText,
                style = MeowTheme.typography.value.copy(
                    color = if (active) accent else colors.onSurface,
                ),
            )
        }
        Box(Modifier.fillMaxWidth().height(96.dp)) {
            DirectionLineChart(state.samples, accent, Modifier.fillMaxSize())
            BasicText(chartUnit, modifier = Modifier.align(Alignment.TopEnd),
                style = MeowTheme.typography.summary.copy(color = colors.onSurfaceVariant))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniStat(if (outcome == "未确认") "发送均值" else "均值", if (measured) SpeedFormat.split(avg, chartUnit).first else "—")
            MiniStat(if (outcome == "未确认") "发送峰值" else "峰值", if (measured) SpeedFormat.split(peak, chartUnit).first else "—")
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = "$label ",
            style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface),
        )
        BasicText(
            text = value,
            style = MeowTheme.typography.value.copy(color = MeowTheme.colors.onSurface),
        )
    }
}

/** 紧凑指标胶囊;带 onClick 时可点(如 IP 掩码/完整切换)。 */
@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(trackColorOf())
            .then(if (onClick != null) Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "$label ",
            style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface),
        )
        BasicText(
            text = value,
            style = MeowTheme.typography.value.copy(color = MeowTheme.colors.onSurface),
        )
    }
}

/** 主题色填充大按钮(自动对比度:M3E 深色下 primary 可能与背景同色)。 */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MeowTheme.colors.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = MeowTheme.typography.button.copy(color = MeowTheme.colors.onPrimary),
        )
    }
}

/** 次要按钮:与 [PrimaryButton] 同尺寸,中性底。 */
@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(trackColorOf())
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = MeowTheme.typography.button.copy(color = MeowTheme.colors.onSurface),
        )
    }
}

/** 整体完成不代表上传被服务器确认。 */
internal fun directionOutcome(status: RunStatus, active: Boolean, resultAvg: Double?, hasSamples: Boolean, message: String): String = when {
    active -> "进行中"
    resultAvg != null && message == UPLOAD_UNCONFIRMED -> "未确认"
    resultAvg != null && (message.isNotBlank() || !resultAvg.isFinite() || resultAvg <= 0.0) -> "未完成"
    resultAvg != null -> "完成"
    status == RunStatus.CANCELLED -> "已停止"
    status == RunStatus.FAILED -> "未完成"
    hasSamples -> "已采样"
    else -> "待测"
}
