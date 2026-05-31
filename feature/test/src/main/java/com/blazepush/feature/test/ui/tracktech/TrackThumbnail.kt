package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.overlay.OverlayCanvasPainter

/**
 * 赛道缩略图渲染器（资产接入唯一入口，Laps tab 与 Records tab 共用）。
 *
 * 设计依据：change `enhance-track-presentation` design.md D6 + D7；
 * 动态轮廓 fallback 由 round `add-track-preview-dynamic-outline` 落地；
 * 静态矢量图优先级由 round `track-preview-static-vector` 落地（boyu 改静态 VectorDrawable）。
 * - 加载源（优先级）：
 *   静态 VectorDrawable 资源（[drawableResId]）> 静态 asset PNG（[assetPath]）
 *   > 赛道轨迹动态俯视轮廓（[points]）> NO PREVIEW 占位
 * - 静态矢量图：[painterResource]，运行时不投影不绘制（一次性离线生成的 drawable，省性能）。
 * - 静态 PNG：原生 [BitmapFactory.decodeStream]，不引入 Coil 等图片库。
 * - 动态轮廓：复用 [TrackMiniMap]（[TrackMiniMapProjection] 等距矩形投影），
 *   `currentLat/Lon = null` 仅画整条赛道轮廓 polyline、不画当前位置点；
 *   用于有 `referencePath` 但无矢量图 / 无美术图的预置赛道（boyu 已改走 [drawableResId]，
 *   不再触发此动态分支；此分支保留给其他无资源赛道兜底）。
 *
 * 五种状态（按优先级）：
 * 1. [drawableResId] 非空 → 渲染静态矢量图，`ContentScale.Fit`（boyu 等已预生成矢量图赛道）
 * 2. [assetPath] 非空且 asset 加载成功 → 渲染静态 PNG，`ContentScale.Fit`（天府等已配图赛道）
 * 3. [assetPath] 非空但加载失败（IOException / 解码失败） → 若有 [points] 退到动态轮廓，否则 fallback
 * 4. 无矢量图 / 无 PNG 但 [points] ≥ 2 点 → 动态画赛道俯视轮廓
 * 5. 既无任何静态资源又无足量轨迹点 → cyan 1dp 描边占位框 + 中央 `"NO PREVIEW"`
 *
 * @param drawableResId 静态预览 VectorDrawable 资源 id（[com.blazepush.feature.test.model.track.Track.thumbnailDrawableResId]）；
 *                      非空时最高优先级，运行时零绘制。null 退到 [assetPath]。
 */
@Composable
fun TrackThumbnail(
    assetPath: String?,
    modifier: Modifier = Modifier,
    drawableResId: Int? = null,
    points: List<GeoPoint>? = null,
) {
    val context = LocalContext.current
    var imageBitmap by remember(assetPath) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(assetPath) { mutableStateOf(false) }

    LaunchedEffect(assetPath, drawableResId) {
        if (assetPath == null || drawableResId != null) {
            // 无 PNG，或已有更高优先级的矢量图 → 不解码 PNG（drawable 分支直接 painterResource）。
            imageBitmap = null
            loadFailed = false
        } else {
            runCatching {
                context.assets.open(assetPath).use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                        ?: error("Bitmap decode returned null for $assetPath")
                }
            }.onSuccess { bitmap ->
                imageBitmap = bitmap
                loadFailed = false
            }.onFailure {
                imageBitmap = null
                loadFailed = true
            }
        }
    }

    // 是否有足量轨迹点可画动态轮廓（投影下限 2 点，与 TrackMiniMapProjection.project 一致）。
    val hasOutline = (points?.size ?: 0) >= 2

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val loaded = imageBitmap
        if (drawableResId != null) {
            // 状态 1：静态预览 VectorDrawable（boyu 等已预生成矢量图赛道）。
            // 运行时零投影零 drawPath —— 一次性离线生成的矢量资源，直接显示。
            Image(
                painter = painterResource(id = drawableResId),
                contentDescription = "Track thumbnail",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (loaded != null) {
            // 状态 2：静态美术图（天府等已配图赛道，视觉不变）
            Image(
                bitmap = loaded,
                contentDescription = "Track thumbnail",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (hasOutline) {
            // 状态 2/3：无静态图（或加载失败）但有轨迹 → 动态画俯视轮廓，不画当前位置点。
            // thumbnail 场景：加粗线宽 + 标起点（points.first() 投影后即 polyline[0]）。
            TrackMiniMap(
                points = points!!,
                currentLat = null,
                currentLon = null,
                modifier = Modifier.fillMaxSize(),
                strokeWidthDp = OverlayCanvasPainter.MINIMAP_STROKE_THUMBNAIL,
                startPoint = points.first(),
            )
        } else {
            // 状态 4：既无图又无足量轨迹点 → NO PREVIEW 占位（与原 design.md D7 一致）
            FallbackPlaceholder(modifier = Modifier.fillMaxSize())
            // loadFailed 与 null 路径都进入 fallback —— design.md D7 要求两种情况
            // 视觉一致，无需为 loadFailed 单独分支。
            @Suppress("UNUSED_EXPRESSION")
            loadFailed
        }
    }
}

@Composable
private fun FallbackPlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier
            .background(TrackTechColors.SurfaceDark)
            .border(1.dp, TrackTechColors.Cyan),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "NO PREVIEW",
            style = TrackTechTypography.UiTextSmall,
            color = TrackTechColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
