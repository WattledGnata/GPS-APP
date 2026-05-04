package com.blazepush.feature.test.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import com.blazepush.feature.test.ui.tracktech.TrackTechSemantic
import com.blazepush.feature.test.ui.tracktech.TrackTechTypography
import kotlin.math.max
import kotlin.math.min

internal data class MapBoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val centerLat: Double,
    val centerLon: Double,
)

internal fun computeMapBoundingBox(samples: List<LapTelemetrySample>): MapBoundingBox {
    val minLat = samples.minOf { it.lat }
    val maxLat = samples.maxOf { it.lat }
    val minLon = samples.minOf { it.lon }
    val maxLon = samples.maxOf { it.lon }
    return MapBoundingBox(
        minLat = minLat,
        maxLat = maxLat,
        minLon = minLon,
        maxLon = maxLon,
        centerLat = (minLat + maxLat) / 2.0,
        centerLon = (minLon + maxLon) / 2.0,
    )
}

internal fun mapLatLonToCanvas(
    lat: Double,
    lon: Double,
    bbox: MapBoundingBox,
    canvasSize: Size,
): Offset {
    val latRange = max(bbox.maxLat - bbox.minLat, 0.0001)
    val lonRange = max(bbox.maxLon - bbox.minLon, 0.0001)
    val scale = min(canvasSize.width / lonRange, canvasSize.height / latRange).toFloat()
    val offsetX = (canvasSize.width - lonRange.toFloat() * scale) / 2f
    val offsetY = (canvasSize.height - latRange.toFloat() * scale) / 2f
    val x = offsetX + ((lon - bbox.minLon) * scale).toFloat()
    val y = offsetY + ((bbox.maxLat - lat) * scale).toFloat()
    return Offset(x, y)
}

@Composable
fun TrackPolylineMap(
    samples: List<LapTelemetrySample>,
    cursorAbsoluteTs: Long?,
    modifier: Modifier = Modifier,
) {
    if (samples.isEmpty()) {
        Box(modifier) {
            Text(
                "NO TRACK DATA",
                style = TrackTechTypography.ScoreSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = TrackTechColors.TextMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    val bbox = remember(samples) { computeMapBoundingBox(samples) }
    val cursorIdx = remember(cursorAbsoluteTs, samples) {
        if (cursorAbsoluteTs != null) samples.indexOfFirst { it.absoluteTsMs == cursorAbsoluteTs } else -1
    }

    Canvas(modifier = modifier) {
        drawRect(TrackTechColors.Background)

        val coords = samples.map { mapLatLonToCanvas(it.lat, it.lon, bbox, size) }

        if (coords.size >= 2) {
            val path = Path().apply {
                moveTo(coords[0].x, coords[0].y)
                for (i in 1 until coords.size) {
                    lineTo(coords[i].x, coords[i].y)
                }
            }
            drawPath(path, TrackTechSemantic.TelemetryLine, style = Stroke(width = 2f))
        }

        if (cursorIdx >= 0 && cursorIdx < coords.size) {
            val pos = coords[cursorIdx]
            drawCircle(TrackTechColors.Purple, radius = 8f, center = pos, style = Stroke(width = 2f))
            drawCircle(TrackTechColors.Purple, radius = 4f, center = pos)
        }
    }
}
