package dev.gaimoy.betterspeedtest.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.lingqiqi5211.meowui.theme.MeowTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 滑动胶囊分段控件:配色取自 MeowTheme(选中胶囊用主题色,选中文字反白),
 * 点按弹性滑动、可整段拖动、跨段触觉轻响。
 */
@Composable
fun SlidingSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fontScale = LocalDensity.current.fontScale
    val colors = MeowTheme.colors
    // 直接用库的配色令牌:primary/onPrimary 在两种风格下都由 MeowUI 调好对比
    val track = trackColorOf()
    val accent = colors.primary
    val onAccent = colors.onPrimary
    // 未选中文字:用库的 onSurfaceVariant(次级前景色,天然与轨道对比良好)
    val unselectedText = colors.onSurfaceVariant
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val n = items.size
    val thumb = remember { Animatable(selectedIndex.toFloat()) }

    // 外部状态变化(点按、设置同步)时弹性滑过去
    LaunchedEffect(selectedIndex) {
        if (abs(thumb.value - selectedIndex) > 0.001f) {
            thumb.animateTo(selectedIndex.toFloat(), segmentSpring())
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height((46f * maxOf(1f, fontScale / 1.15f)).dp),
    ) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val segW = widthPx / n
        val gap = with(density) { 3.dp.toPx() }

        // 轨道
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(track),
        )

        // 滑块胶囊
        Box(
            Modifier
                .offset { IntOffset((thumb.value * segW + gap).roundToInt(), gap.roundToInt()) }
                .size(
                    with(density) { (segW - gap * 2).toDp() },
                    with(density) { (heightPx - gap * 2).toDp() },
                )
                .clip(CircleShape)
                .background(accent)
                // 拖动中轻微放大,提示"抓住了"
                .graphicsLayer {
                    scaleX = 1.02f
                    scaleY = 1.02f
                },
        )

        // 文字层:颜色按滑块与该段的重叠程度实时插值 —— 胶囊滑到哪,哪段文字渐变反白
        val pos = thumb.value
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, label ->
                val coverage = (1f - minOf(abs(pos - i), 1f))
                // 未选中用中性色保证对比度,选中用反白贴在滑块上(随滑块重叠度切换)
                val textColor = if (coverage > 0.5f) onAccent else unselectedText
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            role = Role.Tab
                            selected = selectedIndex == i
                            if (!enabled) disabled()
                            onClick { if (enabled) onSelected(i); enabled }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = label,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = MeowTheme.typography.value.fontSize),
                        style = MeowTheme.typography.value.copy(color = textColor),
                    )
                }
            }
        }

        // 手势层:点按选段(按下位置,库级 tap 判定容忍微漂)+ 横向拖动滑块;
        // 滚动接管(onDragCancel)只归位不选段
        var lastTickSeg = -1
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(n, selectedIndex, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { pos ->
                        val seg = (pos.x / segW).toInt().coerceIn(0, n - 1)
                        if (seg != selectedIndex) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelected(seg)
                        }
                    }
                }
                .pointerInput(n, selectedIndex, enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            lastTickSeg = (thumb.value + 0.5f).toInt()
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            scope.launch {
                                thumb.snapTo((thumb.value + amount / segW).coerceIn(0f, (n - 1).toFloat()))
                            }
                            val seg = (thumb.value + 0.5f).toInt().coerceIn(0, n - 1)
                            if (seg != lastTickSeg) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastTickSeg = seg
                            }
                        },
                        onDragEnd = {
                            val seg = (thumb.value + 0.5f).toInt().coerceIn(0, n - 1)
                            scope.launch { thumb.animateTo(seg.toFloat(), segmentSpring()) }
                            if (seg != selectedIndex) onSelected(seg)
                        },
                        onDragCancel = {
                            // 被外层滚动接管:滑块弹回当前选中段,不改选择
                            scope.launch { thumb.animateTo(selectedIndex.toFloat(), segmentSpring()) }
                        },
                    )
                },
        )
    }
}

private fun segmentSpring() = spring<Float>(
    dampingRatio = 0.78f,
    stiffness = Spring.StiffnessMediumLow,
)
