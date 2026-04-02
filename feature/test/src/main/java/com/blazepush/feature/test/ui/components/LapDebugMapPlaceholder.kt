package com.blazepush.feature.test.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.track.Track

@Composable
fun LapDebugMapPlaceholder(
    track: Track,
    trajectory: List<GpsSample>,
    latestCrossing: CrossingEvent?,
    title: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(text = "参考线路点数: ${track.referencePath.points.size}")
            Text(text = "计时门数量: 起终点 1 / 分段门 ${track.sectorGates.size}")
            Text(text = "轨迹点数: ${trajectory.size}")
            Text(
                text = latestCrossing?.let {
                    "最近 crossing: accepted=${it.accepted}, reason=${it.reason.name}"
                } ?: "最近 crossing: 暂无"
            )
        }
    }
}
