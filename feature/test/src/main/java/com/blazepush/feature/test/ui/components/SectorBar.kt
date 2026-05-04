package com.blazepush.feature.test.ui.components

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import kotlin.math.max

internal data class SectorRect(val xStart: Float, val xEnd: Float)

internal fun computeSectorBounds(
    boundaries: List<Long>,
    lapStart: Long,
    lapEnd: Long,
    totalWidth: Float,
): List<SectorRect> {
    val lapDuration = max(lapEnd - lapStart, 1L)
    if (boundaries.isEmpty()) {
        return listOf(SectorRect(0f, totalWidth))
    }
    val allBounds = boundaries.toMutableList()
    if (allBounds.size < 2 || allBounds.last() < lapEnd) allBounds.add(lapEnd)
    return allBounds.windowed(2) { (start, end) ->
        val xStart = ((start - lapStart).toFloat() / lapDuration) * totalWidth
        val xEnd = ((end - lapStart).toFloat() / lapDuration) * totalWidth
        SectorRect(xStart.coerceIn(0f, totalWidth), xEnd.coerceIn(0f, totalWidth))
    }
}

@Composable
fun SectorBar(
    sectorBoundaries: List<Long>,
    lapStartWallClock: Long,
    lapEndWallClock: Long,
    cursorAbsoluteTs: Long?,
    modifier: Modifier = Modifier,
) {
    if (sectorBoundaries.isNotEmpty() && sectorBoundaries.first() != lapStartWallClock) {
        Log.w("SectorBar", "sectorBoundaries.first() (${sectorBoundaries.first()}) != lapStartWallClock ($lapStartWallClock)")
    }

    val lapDuration = max(lapEndWallClock - lapStartWallClock, 1L)
    val cursorFraction = if (cursorAbsoluteTs != null) {
        ((cursorAbsoluteTs - lapStartWallClock).toFloat() / lapDuration).coerceIn(0f, 1f)
    } else null

    val activeSectorIndex = if (cursorFraction != null && sectorBoundaries.isNotEmpty()) {
        val allBounds = sectorBoundaries.toMutableList()
        if (allBounds.last() < lapEndWallClock) allBounds.add(lapEndWallClock)
        val cursorTs = cursorAbsoluteTs!!
        allBounds.windowed(2).indexOfFirst { (start, end) -> cursorTs in start until end }
            .coerceAtLeast(0)
    } else if (cursorFraction != null) 0 else null

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
    ) {
        val sectors = computeSectorBounds(sectorBoundaries, lapStartWallClock, lapEndWallClock, size.width)
        sectors.forEachIndexed { index, sector ->
            val color = if (index == activeSectorIndex) TrackTechColors.Purple else TrackTechColors.SurfaceDark
            drawRoundRect(
                color = color,
                topLeft = Offset(sector.xStart, 0f),
                size = Size(sector.xEnd - sector.xStart, size.height),
                cornerRadius = CornerRadius(4f, 4f),
            )
        }

        if (cursorFraction != null) {
            val cursorX = cursorFraction * size.width
            drawLine(TrackTechColors.Purple, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 1f)
        }
    }
}
