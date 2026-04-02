package com.blazepush.feature.test.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.ui.components.LapDebugMapPlaceholder

@Composable
fun LapDebugExecutionScreen(
    track: Track,
    lapRunConfig: LapRunConfig?,
    lapSession: LapSession?,
    latestCrossing: CrossingEvent?,
    onStop: () -> Unit
) {
    val orderedGates = listOf(track.startFinishGate) + track.sectorGates.sortedBy { it.sequenceIndex }
    val nextGate = lapSession?.nextExpectedGateIndex?.let { index -> orderedGates.getOrNull(index) }
    val currentLap = (lapSession?.currentLapIndex ?: 0) + 1
    val trajectory = lapSession?.samples ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "圈速调试执行中",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Track: ${track.name}", fontWeight = FontWeight.Bold)
                Text(text = "当前圈: Lap $currentLap")
                Text(text = "下一 Gate: ${nextGate?.name ?: "无"}")
                Text(text = "会话状态: ${lapSession?.status?.name ?: "Idle"}")
                lapRunConfig?.let {
                    Text(text = "配置 Track ID: ${it.trackId}")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "最近 crossing 摘要", fontWeight = FontWeight.Bold)
                if (latestCrossing == null) {
                    Text(text = "暂无 crossing 事件")
                } else {
                    Text(text = "Gate ID: ${latestCrossing.gateId}")
                    Text(text = "Accepted: ${latestCrossing.accepted}")
                    Text(text = "Reason: ${latestCrossing.reason.name}")
                    Text(text = "Timestamp: ${latestCrossing.timestampMillis}")
                }
            }
        }

        LapDebugMapPlaceholder(
            track = track,
            trajectory = trajectory,
            latestCrossing = latestCrossing,
            title = "Track / Gate / Crossing 概览"
        )

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("停止圈速记录")
        }
    }
}
