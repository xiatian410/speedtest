@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.gaimoy.betterspeedtest.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.gaimoy.betterspeedtest.core.SpeedFormat
import dev.gaimoy.betterspeedtest.data.Settings
import io.github.lingqiqi5211.meowui.preference.rememberMeowPreferenceValue
import io.github.lingqiqi5211.meowui.component.MeowTip
import io.github.lingqiqi5211.meowui.component.MeowTipStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gaimoy.betterspeedtest.core.SpeedtestResult
import dev.gaimoy.betterspeedtest.core.fmtBytes
import io.github.lingqiqi5211.meowui.component.MeowCard
import io.github.lingqiqi5211.meowui.component.meowScaffoldScroll
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("MM-dd", Locale.getDefault())
private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * 历史页:按日期倒序的结果卡列表,点按卡片复制结果文本。
 * 卡片布局:头部(标签+时间)→ 速率大数(左右分色)→ 指标行(延迟/抖动/流量)→ 尾行(节点·IP)。
 */
@Composable
fun HistoryScreen(
    history: List<SpeedtestResult>,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val unit by rememberMeowPreferenceValue(Settings.SpeedUnit)
    val layoutDirection = LocalLayoutDirection.current
    val keyedHistory = remember(history) {
        val occurrences = mutableMapOf<String, Int>()
        history.map { r ->
            val id = "${r.time}|${r.source}|${r.node}"
            val count = occurrences.getOrDefault(id, 0)
            occurrences[id] = count + 1
            "$id|$count" to r
        }
    }
    if (history.isEmpty() && (loading || error != null)) {
        Box(Modifier.fillMaxSize().padding(contentPadding).padding(16.dp), contentAlignment = Alignment.Center) {
            MeowTip(title = if (loading) "正在读取历史记录" else "历史记录暂不可用",
                message = error ?: "请稍候。", style = if (error == null) MeowTipStyle.Info else MeowTipStyle.Error,
                actionText = if (error != null && onRetry != null) "重试" else null,
                onAction = if (error != null) onRetry else null)
        }
        return
    }
    if (history.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    text = "暂无历史记录",
                    style = MeowTheme.typography.title.copy(color = MeowTheme.colors.onSurface),
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = "完成一次测速后会保存在本地",
                    style = MeowTheme.typography.summary.copy(color = MeowTheme.colors.onSurface),
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .meowScaffoldScroll(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + 16.dp,
            end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (error != null) item("history-error") {
            MeowTip(title = "历史记录保存失败", message = error, style = MeowTipStyle.Error,
                actionText = if (onRetry != null) "重试" else null, onAction = onRetry)
        }
        items(keyedHistory, key = { it.first }) { (_, r) ->
            HistoryCard(r = r, unit = unit) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("BetterSpeedtest", formatResultShare(r, unit)))
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun HistoryCard(r: SpeedtestResult, unit: String, onClick: () -> Unit) {
    val colors = MeowTheme.colors
    MeowCard(onClick = onClick) {
        // ── 头部:模式徽章 + 源徽章 · 右侧日期时间 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeBadge(modeTag(r))
            Spacer(Modifier.width(6.dp))
            if (r.network.isNotEmpty()) {
                SourceBadge(r.network)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                BasicText(
                    text = dayFmt.format(Date(r.time)),
                    style = MeowTheme.typography.value.copy(
                        color = colors.onSurfaceVariant,
                        fontSize = 13.sp,
                    ),
                )
                BasicText(
                    text = timeFmt.format(Date(r.time)),
                    style = MeowTheme.typography.summary.copy(
                        color = colors.onSurfaceVariant,
                        fontSize = 13.sp,
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 速率区:下载/上传左右分块,大数字 + 峰值小字 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (r.dlAvg > 0 || r.dlPeak > 0) {
                RateBlock(
                    label = "↓",
                    avg = r.dlAvg,
                    peak = r.dlPeak,
                    unit = unit,
                    color = colors.primary,
                    modifier = Modifier.weight(1f),
                )
            }
            if (r.ulAvg > 0 || r.ulPeak > 0) {
                RateBlock(
                    label = "↑",
                    avg = r.ulAvg,
                    peak = r.ulPeak,
                    unit = unit,
                    color = UploadChartColor,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 保持常规宽度的三项排列；大字体时整项换行，不把数值挤成逐字竖排。
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceVariant.copy(alpha = 0.4f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricCell("延迟", if (r.pingMs > 0) "%.0f ms".format(r.pingMs) else "—")
            MetricCell("抖动", if (r.pingMs > 0) "%.1f ms".format(r.jitter) else "—")
            MetricCell("流量", fmtBytes(r.dlBytes + r.ulBytes + r.probeBytes))
        }

        if (r.ulMsg.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            BasicText("上传: ${r.ulMsg}", style = MeowTheme.typography.summary.copy(color = colors.onSurfaceVariant))
        }

        // ── 尾行:节点 · 位置 · IP ──
        val sub = buildList {
            r.node.takeIf { it.isNotEmpty() }?.let { add(it) }
            buildList {
                r.carrier.takeIf { it.isNotEmpty() }?.let { add(it) }
                r.city.takeIf { it.isNotEmpty() }?.let { add(it) }
            }.joinToString(" ").takeIf { it.isNotEmpty() }?.let { add(it) }
            r.ip.takeIf { it.isNotEmpty() }?.let { add(it) }
        }.joinToString(" · ")
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = sub,
                style = MeowTheme.typography.summary.copy(
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp,
                ),
            )
        }
    }
}

/** 模式徽章:小圆角胶囊,primary 色。 */
@Composable
private fun ModeBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MeowTheme.colors.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        BasicText(
            text = text,
            style = MeowTheme.typography.summary.copy(
                color = MeowTheme.colors.primary,
                fontSize = 12.sp,
            ),
        )
    }
}

/** 源徽章:小圆角胶囊,次级色。 */
@Composable
private fun SourceBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MeowTheme.colors.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        BasicText(
            text = text,
            style = MeowTheme.typography.summary.copy(
                color = MeowTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
            ),
        )
    }
}

/** 速率块:箭头+大数字(彩色)+峰值小字。 */
@Composable
private fun RateBlock(
    label: String,
    avg: Double,
    peak: Double,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val displayUnit = SpeedFormat.unitLabel(unit, maxOf(avg, peak))
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(
                text = label,
                style = MeowTheme.typography.title.copy(
                    color = color,
                    fontSize = 22.sp,
                ),
                modifier = Modifier.padding(end = 4.dp),
            )
            BasicText(
                text = SpeedFormat.split(avg, displayUnit).first,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 32.sp),
                style = MeowTheme.typography.pageTitle.copy(
                    color = color,
                    fontSize = 32.sp,
                ),
            )
        }
        BasicText(
            text = "峰值 " + SpeedFormat.format(peak, displayUnit),
            style = MeowTheme.typography.summary.copy(
                color = MeowTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
            ),
            modifier = Modifier.padding(start = 26.dp),
        )
    }
}

/** 指标单元:标签(小灰) + 值(正常)。 */
@Composable
private fun MetricCell(label: String, value: String) {
    Row(modifier = Modifier.width(IntrinsicSize.Max), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = label,
            maxLines = 1,
            style = MeowTheme.typography.summary.copy(
                color = MeowTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
            ),
            modifier = Modifier.padding(end = 4.dp),
        )
        BasicText(
            text = value,
            maxLines = 1,
            style = MeowTheme.typography.value.copy(
                color = MeowTheme.colors.onSurface,
                fontSize = 14.sp,
            ),
        )
    }
}

/** 从结果数据推断模式标签。 */
private fun modeTag(r: SpeedtestResult): String {
    if (r.mode.isNotEmpty()) return r.mode
    val hasDl = r.dlAvg > 0 || r.dlPeak > 0
    val hasUl = r.ulAvg > 0 || r.ulPeak > 0
    return when {
        hasDl && hasUl -> "双向"
        hasDl -> "仅下载"
        hasUl -> "仅上传"
        else -> "—"
    }
}
