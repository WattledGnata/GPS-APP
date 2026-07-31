package com.blazepush

import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.overlay.OverlayCanvasPainter
import com.blazepush.feature.test.overlay.OverlayHudFrame
import com.blazepush.feature.test.overlay.VideoOverlayStyle
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import com.blazepush.feature.test.ui.tracktech.TrackTechTheme
import com.blazepush.feature.test.ui.tracktech.TrackTechTypography

/**
 * Debug-only HUD visual QA host. Start with:
 * adb shell am start -n com.blazepush.debug/.OverlayStylePreviewActivity --es style FLAT
 */
class OverlayStylePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val style = VideoOverlayStyle.fromStored(intent.getStringExtra("style"))
        setContent {
            TrackTechTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF10151A)),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color(0xFF182027))
                        val road = Path().apply {
                            moveTo(-size.width * 0.10f, size.height * 0.88f)
                            cubicTo(
                                size.width * 0.20f,
                                size.height * 0.38f,
                                size.width * 0.72f,
                                size.height * 0.72f,
                                size.width * 1.10f,
                                size.height * 0.18f,
                            )
                        }
                        drawPath(
                            path = road,
                            color = Color(0xFF343B40),
                            style = Stroke(width = size.height * 0.42f),
                        )
                        drawPath(
                            path = road,
                            color = Color(0xFFE8E0B0).copy(alpha = 0.55f),
                            style = Stroke(width = size.height * 0.012f),
                        )
                        for (i in 0..8) {
                            val x = size.width * i / 8f
                            drawLine(
                                color = Color.White.copy(alpha = 0.025f),
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1f,
                            )
                        }
                        drawIntoCanvas { composeCanvas ->
                            composeCanvas.nativeCanvas.drawRect(
                                0f,
                                0f,
                                size.width,
                                size.height,
                                Paint().apply { color = 0x22000000 },
                            )
                            OverlayCanvasPainter.drawHud(
                                canvas = composeCanvas.nativeCanvas,
                                width = size.width,
                                height = size.height,
                                style = style,
                                frame = previewFrame,
                            )
                        }
                    }
                    Text(
                        text = style.displayName,
                        style = TrackTechTypography.RacingTitleSmall,
                        color = TrackTechColors.TextMuted,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp),
                    )
                }
            }
        }
    }

    companion object {
        private val previewTrack = listOf(
            GeoPoint(31.2920, 121.1890),
            GeoPoint(31.2928, 121.1912),
            GeoPoint(31.2945, 121.1926),
            GeoPoint(31.2962, 121.1918),
            GeoPoint(31.2967, 121.1898),
            GeoPoint(31.2955, 121.1879),
            GeoPoint(31.2934, 121.1875),
            GeoPoint(31.2920, 121.1890),
        )

        private val previewFrame = OverlayHudFrame(
            speedKmh = 126.0,
            latG = 0.82,
            lonG = -0.31,
            lapNumber = 3,
            elapsedMs = 62_438L,
            deltaMs = -270L,
            trackPoints = previewTrack,
            currentLat = 31.2955,
            currentLon = 121.1879,
            maxSpeedKmh = 180.0,
        )
    }
}
