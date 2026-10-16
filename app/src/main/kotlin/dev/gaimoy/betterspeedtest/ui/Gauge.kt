package dev.gaimoy.betterspeedtest.ui

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gaimoy.betterspeedtest.core.Sample
import dev.gaimoy.betterspeedtest.core.SpeedFormat
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

fun fmtMbps(v: Double): String = when {
    v >= 1000.0 -> "%.0f".format(v)
    v >= 100.0 -> "%.0f".format(v)
    v >= 10.0 -> "%.1f".format(v)
    else -> "%.2f".format(v)
}

private fun fmtScale(v: Double): String = when {
    v >= 1000.0 && v % 1000.0 == 0.0 -> "${v.toInt() / 1000}k"
    v >= 1000.0 -> "%.1fk".format(v / 1000)
    else -> "%.0f".format(v)
}

/** 上传曲线/表盘用色:与主题强调色区分的固定青色,深浅色下都可读。 */
val UploadChartColor = Color(0xFF00897B)

private fun gaugeMax(peak: Double, rangeMode: String = "auto", fixedMbps: Int = 1000): Double {
    if (rangeMode == "fixed" && fixedMbps > 0) return fixedMbps.toDouble()
    val target = maxOf(peak * 1.15, 5.0)
    val steps = doubleArrayOf(5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)
    for (s in steps) if (target <= s) return s
    return ceil(target / 1000.0) * 1000.0
}

/**
 * 速度表盘:270° 渐变进度弧 + 刻度盘 + 峰值标记,量程按峰值自适应。
 * 无指针设计 —— 弧的亮端就是当前速率,视觉焦点跟随渐变最亮处。
 */
@Composable
fun SpeedGauge(
    mbps: Double,
    peakMbps: Double,
    title: String,
    modifier: Modifier = Modifier,
    progressColor: Color? = null,
    trackColor: Color? = null,
    valueFontSize: TextUnit = 44.sp,
    showScale: Boolean = true,
    showDot: Boolean = true,
    unit: String = "auto",
    rangeMode: String = "auto",
    rangeFixedMbps: Int = 1000,
    digits: String = "big",
) {
    val colors = MeowTheme.colors
    val track = trackColor ?: trackColorOf()
    val accent = progressColor ?: colors.primary
    // 量程取 max(peak, mbps):预热段 peak 尚未更新(统计剔预热)但 mbps 已是实际速率,
    // 若只用 peak 算量程会得到最小量程(5),当前值直接打满弧——"先拉满再回落"的根因
    val maxV = gaugeMax(maxOf(peakMbps, mbps), rangeMode, rangeFixedMbps)
    val active = mbps > 0.05 || peakMbps > 0.05
    // 200ms 一个采样点,180ms 线性补间让弧端连续滑动。
    // 只驱动弧/指针;数字文本用原始采样值(5Hz 快照)——逐帧文本排版在弱机上很贵
    val animatedMbps = animateFloatAsState(
        targetValue = mbps.toFloat(),
        animationSpec = tween(durationMillis = 180),
        label = "gauge-value",
    )
    val peakFrac = (peakMbps / maxV).toFloat().coerceIn(0f, 1f)
    val density = LocalDensity.current
    val labelColor = colors.onSurfaceVariant.toArgb()
    val labelPaint = remember(labelColor) {
        Paint().apply {
            color = labelColor
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    // 渐变画刷缓存:逐帧重建 Brush 是无谓的分配压力
    val progressGradient = remember(accent) {
        Brush.sweepGradient(
            0f to accent.copy(alpha = 0.45f),
            0.75f to accent,
            0.7501f to accent.copy(alpha = 0.45f),
            1f to accent.copy(alpha = 0.45f),
        )
    }
    // 未达刻度:同样用确定性中性混合,不依赖 outline(动态取色下可能不可见)
    val inactiveTick = colors.onSurfaceVariant.copy(alpha = 0.5f)

    Box(modifier = modifier.clearAndSetSemantics {
        contentDescription = "$title，${SpeedFormat.format(mbps, unit)}，峰值 ${SpeedFormat.format(peakMbps, unit)}"
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val frac = (animatedMbps.value / maxV).toFloat().coerceIn(0f, 1f)
            val stroke = size.minDimension * 0.068f
            val inset = stroke * 1.45f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val startAngle = 135f
            val sweep = 270f
            val cx = size.width / 2
            val cy = size.height / 2
            val arcR = arcSize.minDimension / 2

            // 1. 底轨
            drawArc(
                color = track,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )

            // 2. 渐变进度弧:起点暗、末端亮,沿方向渐亮,视线落在移动的亮端
            if (frac > 0.004f) {
                drawContext.canvas.save()
                drawContext.canvas.translate(cx, cy)
                drawContext.canvas.rotate(startAngle)
                drawContext.canvas.translate(-cx, -cy)
                drawArc(
                    brush = progressGradient,
                    startAngle = 0f,
                    sweepAngle = sweep * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawContext.canvas.restore()
            }

            // 2.5 弧端进度点:联动进度弧顶端,比弧稍大的亮点标记当前位置
            if (showDot && frac > 0.004f) {
                val da = Math.toRadians((startAngle + sweep * frac).toDouble())
                val dx = cx + arcR * cos(da).toFloat()
                val dy = cy + arcR * sin(da).toFloat()
                drawCircle(color = track, radius = stroke * 0.52f, center = Offset(dx, dy))
                drawCircle(color = accent, radius = stroke * 0.36f, center = Offset(dx, dy))
            }

            // 3. 中心一层极淡的强调色,给数字一个"盘面"
            drawCircle(
                color = if (active) accent.copy(alpha = 0.05f) else track.copy(alpha = 0.35f),
                radius = arcR - stroke * 2.2f,
                center = Offset(cx, cy),
            )

            // 4. 刻度盘:12 格细刻度沿弧内侧,已达到的格子上强调色(真机减负:24→12)
            val tickCount = 12
            val tickOuter = arcR - stroke * 0.85f
            for (i in 0..tickCount) {
                val f = i.toFloat() / tickCount
                val a = Math.toRadians((startAngle + sweep * f).toDouble())
                val len = stroke * (if (i % 3 == 0) 1.15f else 0.55f)
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                val tickColor = when {
                    frac > 0.004f && f <= frac + 0.004f -> accent.copy(alpha = 0.85f)
                    else -> inactiveTick
                }
                drawLine(
                    color = tickColor,
                    start = Offset(cx + tickOuter * ca, cy + tickOuter * sa),
                    end = Offset(cx + (tickOuter - len) * ca, cy + (tickOuter - len) * sa),
                    strokeWidth = stroke * 0.07f,
                    cap = StrokeCap.Round,
                )
            }

            // 弧上只留进度点(峰值用下方文字展示,弧上双点会重叠干扰)

            // 6. 量程:只在弧的两端标 0 和满量程,不挤中间
            if (showScale) {
                labelPaint.textSize = with(density) { 11.sp.toPx() }
                val fm = labelPaint.fontMetrics
                val labelR = arcR + stroke * 1.05f
                val displayUnit = SpeedFormat.unitLabel(unit, mbps)
                val maxLabel = if (displayUnit == "Mbps") fmtScale(maxV) else SpeedFormat.split(maxV, displayUnit).first
                for (label in listOf(0f to "0", 1f to maxLabel)) {
                    val (f, text) = label
                    val a = Math.toRadians((startAngle + sweep * f).toDouble())
                    val tx = cx + labelR * cos(a).toFloat()
                    val ty = cy + labelR * sin(a).toFloat() - (fm.ascent + fm.descent) / 2f
                    drawContext.canvas.nativeCanvas.drawText(text, tx, ty, labelPaint)
                }
            }
        }
        Column(Modifier.fillMaxWidth(0.68f), horizontalAlignment = Alignment.CenterHorizontally) {
            // 数字用原始采样值(5Hz):避免补间动画导致逐帧文本测量/排版(弱机卡顿源)
            val (valueText, unitText) = SpeedFormat.split(mbps, unit)
            BasicText(
                text = valueText,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = if (digits == "compact") 32.sp else valueFontSize),
                style = MeowTheme.typography.pageTitle.copy(
                    color = if (active) colors.onSurface else colors.onSurfaceVariant,
                    fontSize = valueFontSize,
                    textAlign = TextAlign.Center,
                ),
            )
            BasicText(
                text = unitText,
                style = MeowTheme.typography.summary.copy(color = colors.onSurface),
            )
            if (digits == "compact") BasicText("峰值 ${SpeedFormat.split(peakMbps, unitText).first}",
                style = MeowTheme.typography.summary.copy(color = colors.onSurfaceVariant))
            if (title.isNotEmpty()) {
                BasicText(
                    text = title,
                    style = MeowTheme.typography.value.copy(
                        color = if (active) accent else colors.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

/**
 * 单方向实时速率曲线:纵轴按本方向峰值自适应,横轴为本方向自己的时间跨度。
 */
@Composable
fun DirectionLineChart(
    samples: List<Sample>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = MeowTheme.colors
    val total = maxOf(samples.maxOfOrNull { it.t } ?: 0.0, 1.0)
    val peakMbps = maxOf(samples.maxOfOrNull { it.bps / 1e6 } ?: 0.0, 1.0)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(
                color = colors.outline.copy(alpha = 0.25f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
            )
        }
        if (samples.size < 2) return@Canvas
        val path = Path()
        samples.forEachIndexed { i, s ->
            val x = ((s.t / total).coerceIn(0.0, 1.0)).toFloat() * w
            val y = h - ((s.bps / 1e6 / peakMbps).coerceIn(0.0, 1.0)).toFloat() * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(4f, cap = StrokeCap.Round))
    }
}
