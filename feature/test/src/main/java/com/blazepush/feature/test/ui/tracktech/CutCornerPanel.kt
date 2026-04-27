package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Track Tech V2 切角面板的角位置。
 */
enum class CutCorner { TopLeft, TopRight, BottomLeft, BottomRight }

/**
 * 切角面板 Shape：通过 GenericShape + Path 自定义 8 段轨迹（任意角组合）。
 *
 * 大部分卡片 / 按钮 / bottom nav selected indicator / sheet 容器都用此 shape。
 */
class CutCornerPanelShape(
    private val cutSize: Dp,
    private val cutCorners: Set<CutCorner>,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val c = with(density) { cutSize.toPx() }
        val w = size.width
        val h = size.height
        val path = Path().apply {
            // Top-Left 起点
            if (CutCorner.TopLeft in cutCorners) moveTo(c, 0f) else moveTo(0f, 0f)
            // 顶边 → TopRight
            if (CutCorner.TopRight in cutCorners) {
                lineTo(w - c, 0f); lineTo(w, c)
            } else {
                lineTo(w, 0f)
            }
            // 右边 → BottomRight
            if (CutCorner.BottomRight in cutCorners) {
                lineTo(w, h - c); lineTo(w - c, h)
            } else {
                lineTo(w, h)
            }
            // 底边 → BottomLeft
            if (CutCorner.BottomLeft in cutCorners) {
                lineTo(c, h); lineTo(0f, h - c)
            } else {
                lineTo(0f, h)
            }
            // 左边 → TopLeft 起点闭合
            if (CutCorner.TopLeft in cutCorners) {
                lineTo(0f, c); lineTo(c, 0f)
            } else {
                lineTo(0f, 0f)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

// 常用 corner preset
val cutCornersDiagonal = setOf(CutCorner.TopLeft, CutCorner.BottomRight)
val cutCornersAntiDiagonal = setOf(CutCorner.TopRight, CutCorner.BottomLeft)
val cutCornersAll = setOf(CutCorner.TopLeft, CutCorner.TopRight, CutCorner.BottomLeft, CutCorner.BottomRight)
val cutCornersTop = setOf(CutCorner.TopLeft, CutCorner.TopRight)
val cutCornersBottom = setOf(CutCorner.BottomLeft, CutCorner.BottomRight)
val cutCornersTopRight = setOf(CutCorner.TopRight)
val cutCornersBottomLeft = setOf(CutCorner.BottomLeft)
val cutCornersNone = emptySet<CutCorner>()

@Composable
fun CutCornerPanel(
    modifier: Modifier = Modifier,
    cutSize: Dp = 12.dp,
    cutCorners: Set<CutCorner> = cutCornersDiagonal,
    fillColor: Color = TrackTechColors.Surface,
    borderColor: Color = TrackTechColors.Border,
    borderWidth: Dp = 1.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val shape = CutCornerPanelShape(cutSize, cutCorners)
    Box(
        modifier = modifier
            .clip(shape)
            .background(fillColor, shape)
            .border(borderWidth, borderColor, shape)
            .padding(contentPadding),
    ) {
        content()
    }
}
