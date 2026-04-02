package com.blazepush.feature.test.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.LapRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LapDebugResultScreen(
    latestLapRecords: List<LapRecord>,
    latestCrossing: CrossingEvent?,
    onBackToSelection: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("圈速调试结果") }
            )
        }
    ) { innerPadding ->
        if (latestLapRecords.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "暂无 LapRecord", fontWeight = FontWeight.Bold)
                        Text(text = "本次圈速调试还没有生成有效圈记录。")
                    }
                }
                CrossingSummaryCard(latestCrossing = latestCrossing)
                Button(
                    onClick = onBackToSelection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("返回选择页")
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CrossingSummaryCard(latestCrossing = latestCrossing)
            }
            items(latestLapRecords, key = { it.recordId }) { lapRecord ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Lap ${lapRecord.lapIndex}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(text = "durationMillis: ${lapRecord.durationMillis}")
                        Text(
                            text = lapRecord.sectorTimes.joinToString(
                                prefix = "sectorTimes: [",
                                postfix = "]"
                            )
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onBackToSelection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("返回选择页")
                }
            }
        }
    }
}

@Composable
private fun CrossingSummaryCard(
    latestCrossing: CrossingEvent?
) {
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
}
