package com.hydro.link.server.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 为支持 [ScrollState] 的可滚动内容绘制垂直滚动条指示器。
 *
 * 特性：
 * 1. 内容不需要滚动时（maxValue <= 0）静默，不绘制多余装饰；
 * 2. 需要滚动时，在右侧清晰显示半透明轨道与圆角胶囊滑块（thumb）；
 * 3. 滑块高度根据当前可视高度与总内容高度比例动态自适应（设定最小高度保证可见性）；
 * 4. 滑块位置跟随当前 scrollState.value 实时联动；
 * 5. 用户一眼即可识别出该区域是可向下滚动的长文本，消除“以为不能滚动”的疑虑。
 */
fun Modifier.drawVerticalScrollbar(
    state: ScrollState,
    thumbColor: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    width: Dp = 4.dp,
    paddingEnd: Dp = 2.dp,
    minThumbHeight: Dp = 32.dp,
): Modifier = this.drawWithContent {
    drawContent()
    val maxScroll = state.maxValue
    if (maxScroll <= 0) return@drawWithContent

    val viewHeight = size.height
    val totalHeight = maxScroll + viewHeight
    if (totalHeight <= 0f) return@drawWithContent

    val effectiveTrackColor = if (trackColor != Color.Unspecified) trackColor else Color(0x1F888888)
    val effectiveThumbColor = if (thumbColor != Color.Unspecified) thumbColor else Color(0x66888888)

    val trackWidthPx = width.toPx()
    val rightEdge = size.width - paddingEnd.toPx()
    val leftEdge = (rightEdge - trackWidthPx).coerceAtLeast(0f)
    val radiusPx = trackWidthPx / 2f

    // 绘制滚动条微淡轨道，提示可滚动的整体范围
    drawRoundRect(
        color = effectiveTrackColor,
        topLeft = Offset(leftEdge, 0f),
        size = Size(trackWidthPx, viewHeight),
        cornerRadius = CornerRadius(radiusPx, radiusPx)
    )

    // 计算滑块尺寸与位移
    val thumbHeightPx = (viewHeight * (viewHeight / totalHeight))
        .coerceIn(minThumbHeight.toPx(), viewHeight)
    val scrollProgress = (state.value.toFloat() / maxScroll).coerceIn(0f, 1f)
    val thumbOffsetY = (viewHeight - thumbHeightPx) * scrollProgress

    // 绘制滑块
    drawRoundRect(
        color = effectiveThumbColor,
        topLeft = Offset(leftEdge, thumbOffsetY),
        size = Size(trackWidthPx, thumbHeightPx),
        cornerRadius = CornerRadius(radiusPx, radiusPx)
    )
}
