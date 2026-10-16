package dev.gaimoy.betterspeedtest.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaimoy.betterspeedtest.TrafficRuntime
import dev.gaimoy.betterspeedtest.core.*
import io.github.lingqiqi5211.meowui.component.*
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

@Composable
fun TrafficScreen(
    pickedNode: Node?,
    onNodeChange: (Node?) -> Unit,
    onPickNode: (TestSource) -> Unit,
    isSpeedtestRunning: () -> Boolean,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val runner = TrafficRuntime.runner
    var encoded by rememberSaveable { mutableStateOf(Json.encodeToString(TrafficRuntime.readOptions(context))) }
    val draft = remember(encoded) { Json.decodeFromString<TrafficOptions>(encoded) }
    fun change(next: TrafficOptions) { encoded = Json.encodeToString(next) }
    val busyFlow = remember { runner.state.map { it.busy }.distinctUntilChanged() }
    val busy by busyFlow.collectAsStateWithLifecycle(initialValue = runner.state.value.busy)
    var error by rememberSaveable { mutableStateOf("") }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "通知未开启，仍可回到打流页面停止任务", Toast.LENGTH_LONG).show()
    }
    MeowPreferenceScreen(scaffoldPadding = contentPadding) {
        TrafficStats()
        PrimaryButton(
            text = if (busy) "停止打流" else "开始打流",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (busy) runner.stop() else {
                    val options = draft.copy(node = pickedNode)
                    error = if (isSpeedtestRunning()) "请先停止正在进行的测速" else TrafficRuntime.start(context, options).orEmpty()
                    if (error.isEmpty() && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            },
        )
        if (error.isNotEmpty()) MeowTip(title = "未能开始", message = error, style = MeowTipStyle.Error)
        MeowPreferenceSection(title = "传输目标") {
            MeowPopupPreference(title = "模式", value = draft.mode, options = TrafficMode.entries.toList(),
                optionLabel = { it.label }, enabled = !busy, onValueChange = { change(draft.copy(mode = it)) })
            MeowPopupPreference(title = "方向", value = draft.direction, options = TrafficDirection.entries.toList(),
                optionLabel = { it.label }, enabled = !busy, onValueChange = { change(draft.copy(direction = it)) })
            if (draft.mode == TrafficMode.URL) {
                if (draft.direction.download) MeowTextInputPreference(title = "下载 URL", value = draft.downloadUrl,
                    summary = "通过 GET 循环读取响应正文", enabled = !busy, placeholder = "https://example.com/test.bin",
                    onValueChange = { change(draft.copy(downloadUrl = it.trim())) })
                if (draft.direction.upload) MeowTextInputPreference(title = "上传 URL", value = draft.uploadUrl,
                    summary = "接收 application/octet-stream POST 的接口", enabled = !busy, placeholder = "https://example.com/upload",
                    onValueChange = { change(draft.copy(uploadUrl = it.trim())) })
            } else {
                MeowPopupPreference(title = "测速源", value = draft.source, options = TestSource.entries.toList(),
                    optionLabel = { it.label }, enabled = !busy,
                    onValueChange = { onNodeChange(null); change(draft.copy(source = it, node = null)) })
                MeowActionPreference(title = "节点", value = pickedNode?.name ?: "自动选择",
                    summary = if (draft.source == TestSource.AUTO) "指定测速源后可手动选点" else "每轮重新连接，自动选择时重新检查候选节点",
                    navigation = true, enabled = !busy && draft.source != TestSource.AUTO,
                    onClick = { onPickNode(draft.source) })
                if (pickedNode != null) MeowActionPreference(title = "恢复自动选点", enabled = !busy, onClick = { onNodeChange(null) })
                MeowTextInputPreference(title = "每轮时长（秒）", value = draft.cycleSec.toString(), enabled = !busy,
                    validator = { if (it.toIntOrNull() in 5..120) null else "请输入 5–120 的整数" },
                    onValueChange = { change(draft.copy(cycleSec = it.toInt())) })
            }
        }
        MeowPreferenceSection(title = "限制与停止条件") {
            MeowPopupPreference(title = "每方向连接数", value = draft.connections, options = listOf(1, 2, 4, 8, 16),
                optionLabel = { "$it 条" }, enabled = !busy, onValueChange = { change(draft.copy(connections = it)) })
            MeowTextInputPreference(title = "总速率限制（Mbps）", value = draft.rateMbps.toString(),
                summary = "上下行合计；0 表示不限，实际速率受网络影响", enabled = !busy,
                validator = { value -> val number = value.toDoubleOrNull(); if (number != null && (number == 0.0 || number in 0.1..100000.0)) null else "请输入 0 或 0.1–100000" },
                onValueChange = { change(draft.copy(rateMbps = it.toDouble())) })
            MeowTextInputPreference(title = "持续时间（秒）", value = draft.durationSec.toString(), enabled = !busy,
                validator = { if (it.toIntOrNull() in 1..21600) null else "请输入 1–21600 的整数" },
                onValueChange = { change(draft.copy(durationSec = it.toInt())) })
            MeowTextInputPreference(title = "总流量上限（MiB）", value = draft.limitMiB.toString(),
                summary = "与时间限制中任意一项达到即停止", enabled = !busy,
                validator = { if (it.toIntOrNull() in 1..1_048_576) null else "请输入 1–1048576 的整数" },
                onValueChange = { change(draft.copy(limitMiB = it.toInt())) })
        }
        BasicText(
            "流量按应用读写的 HTTP 正文统计，不含协议开销、重传及网络缓冲，可能小于运营商计费流量。上传字节表示本机写出，成功响应次数表示服务器确认。节点循环结果单独显示，不写入普通测速历史。",
            style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurfaceVariant),
        )
    }
}

@Composable
private fun TrafficStats() {
    val state by TrafficRuntime.runner.state.collectAsStateWithLifecycle()
    val textStyle = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface)
    MeowCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(state.message, style = MeowTheme.typography.title.copy(color = MeowTheme.colors.onSurface))
            BasicText(state.target.ifEmpty { "自定义 URL / 测速节点循环" }, style = textStyle)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TrafficSpeedCard("下载", state.downloadMbps, state.downloadBytes, Modifier.weight(1f))
        TrafficSpeedCard("上传", state.uploadMbps, state.uploadBytes, Modifier.weight(1f))
    }
    MeowCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("累计 %.2f MiB · 已运行 %.1f 秒".format(state.totalBytes / 1048576.0, state.elapsedSec), style = textStyle)
            state.options?.let { options ->
                BasicText("上限 ${options.limitMiB} MiB / ${options.durationSec} 秒", style = textStyle)
            }
            BasicText("第 ${state.cycle} 轮 · 失败 ${state.failures} 次 · 上传成功响应 ${state.uploadResponses} 次", style = textStyle)
            if (!state.busy && state.totalBytes > 0 && state.elapsedSec > 0) {
                BasicText("全程平均：↓ %.2f / ↑ %.2f Mbps".format(state.downloadBytes * 8 / state.elapsedSec / 1e6,
                    state.uploadBytes * 8 / state.elapsedSec / 1e6), style = textStyle)
            }
        }
    }
}

@Composable
private fun TrafficSpeedCard(title: String, mbps: Double, bytes: Long, modifier: Modifier) {
    MeowCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(title, style = MeowTheme.typography.title.copy(color = MeowTheme.colors.onSurface))
            BasicText("%.2f".format(mbps), modifier = Modifier.fillMaxWidth(), maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 32.sp),
                style = MeowTheme.typography.title.copy(color = MeowTheme.colors.primary))
            BasicText("Mbps", style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurfaceVariant))
            BasicText("%.2f MiB".format(bytes / 1048576.0),
                style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface))
        }
    }
}
