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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 赛道缩略图渲染器（资产接入唯一入口，Laps tab 与 Records tab 共用）。
 *
 * 设计依据：change `enhance-track-presentation` design.md D6 + D7。
 * - 加载源：仅 asset 静态图（路径相对 `feature/test/src/main/assets/`）
 * - 加载方式：原生 [BitmapFactory.decodeStream]，不引入 Coil 等图片库
 * - 缺图 fallback：cyan 1dp 描边占位框 + 中央 `"NO PREVIEW"` 文字
 *
 * 三种状态：
 * 1. `assetPath == null` → fallback
 * 2. `assetPath` 非空但 asset 加载失败（IOException / 解码失败）→ fallback
 * 3. asset 加载成功 → 渲染图，`ContentScale.Fit`
 */
@Composable
fun TrackThumbnail(
    assetPath: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var imageBitmap by remember(assetPath) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(assetPath) { mutableStateOf(false) }

    LaunchedEffect(assetPath) {
        if (assetPath == null) {
            imageBitmap = null
            loadFailed = false
            return@LaunchedEffect
        }
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

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val loaded = imageBitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = "Track thumbnail",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
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
