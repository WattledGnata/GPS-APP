package com.race.gps.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.race.gps.data.local.entity.TestRecordEntity
import com.race.gps.viewmodel.TestHistoryViewModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录页面
 */
@Composable
fun TestHistoryScreen(
    onRecordClick: (String) -> Unit,
    testHistoryViewModel: TestHistoryViewModel = koinViewModel()
) {
    val testRecords by testHistoryViewModel.testRecords.collectAsState()
    var recordToDelete by remember { mutableStateOf<TestRecordEntity?>(null) }

    // 删除确认对话框
    recordToDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { recordToDelete = null },
            title = { Text("删除记录") },
            text = { Text("确定要删除这条测试记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    testHistoryViewModel.deleteRecord(record)
                    recordToDelete = null
                }) { Text("删除", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { recordToDelete = null }) { Text("取消") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("测试历史", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        if (testRecords.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无测试记录", fontSize = 18.sp, color = Color.Gray)
                    Text("完成一次测试后记录将显示在这里", fontSize = 14.sp, color = Color.Gray)
                }
            }
        } else {
            // 统计摘要
            StatsSummaryCard(testRecords)
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(testRecords, key = { it.id }) { record ->
                    TestRecordItem(
                        record = record,
                        onClick = { onRecordClick(record.id) },
                        onLongClick = { recordToDelete = record }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsSummaryCard(records: List<TestRecordEntity>) {
    val accRecords = records.filter { it.testTemplateId == "acc_0_100" }
    val brakeRecords = records.filter { it.testTemplateId == "brake_100_0" }
    val bestAcc = accRecords.minByOrNull { it.totalTime }
    val bestBrake = brakeRecords.minByOrNull { it.totalDistance }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("总测试", fontSize = 12.sp, color = Color.Gray)
                Text("${records.size}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("最佳加速", fontSize = 12.sp, color = Color.Gray)
                Text(
                    bestAcc?.let { String.format("%.2fs", it.totalTime) } ?: "-",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("最佳刹车", fontSize = 12.sp, color = Color.Gray)
                Text(
                    bestBrake?.let { String.format("%.1fm", it.totalDistance) } ?: "-",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TestRecordItem(
    record: TestRecordEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(record.testType, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(record.carModel, fontSize = 14.sp, color = Color.Gray)
                Text(dateFormat.format(Date(record.timestamp)), fontSize = 12.sp, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.End) {
                val mainResult = when (record.testTemplateId) {
                    "acc_0_100" -> String.format("%.2f s", record.totalTime)
                    "brake_100_0" -> String.format("%.1f m", record.totalDistance)
                    else -> record.result
                }
                Text(mainResult, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
