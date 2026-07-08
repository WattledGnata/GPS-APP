// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.diagnostic

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.blazepush.core.network.DiagnosticLogUploader
import com.blazepush.feature.test.diagnostic.DiagnosticMetadataCollector
import com.blazepush.feature.test.diagnostic.DiagnosticUploadOrchestrator
import com.blazepush.feature.test.diagnostic.DiagnosticUploadState
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import com.blazepush.feature.test.ui.tracktech.TrackTechTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 诊断上传面板（add-diagnostic-log-upload, task 6.1）。
 *
 * 工单号输入 + 上传按钮 + 隐私确认弹窗 + 进度条 + 成功展示 logId + 失败展示原因。
 * [DiagnosticUploadOrchestrator] 在本 Composable 内创建（依赖 Context paths，非 Koin 注册），
 * 上传跑 `Dispatchers.IO`。隐私分支用 if/else 禁 early return（依 Compose 禁 early return 规则）。
 * 成功态展示 logId 并允许点按复制到剪贴板。
 */
@Composable
fun DiagnosticUploadSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val uploader = remember { DiagnosticLogUploader() }
    val orchestrator = remember {
        DiagnosticUploadOrchestrator(
            filesDir = ctx.filesDir,
            databaseDir = ctx.getDatabasePath("race_chrono_database").parentFile!!,
            cacheDir = ctx.cacheDir,
            uploader = uploader,
            metaProvider = { ticket -> DiagnosticMetadataCollector.collect(ctx, ticket, System.currentTimeMillis()) },
        )
    }
    val state by orchestrator.state.collectAsState()
    var ticket by remember { mutableStateOf("") }
    var showPrivacyDialog by remember { mutableStateOf(false) }

    // 隐私确认弹窗（spec『隐私确认』）
    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("上传诊断数据") },
            text = {
                Text(
                    "将上传你的行驶轨迹与诊断数据，用于排查问题，是否继续？\n\n" +
                        "上传内容：GPS 轨迹记录、圈速数据、应用诊断日志。\n不包含视频文件。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPrivacyDialog = false
                    scope.launch(Dispatchers.IO) { orchestrator.start(ticket.takeIf { it.isNotBlank() }) }
                }) { Text("同意并上传") }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("取消") }
            },
        )
    }

    // 主面板
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrackTechColors.Background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 标题行
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "‹ 关闭",
                    style = TrackTechTypography.RacingTitleSmall,
                    color = TrackTechColors.Cyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "诊断上传",
                style = TrackTechTypography.RacingTitleSmall,
                color = TrackTechColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        }

        HorizontalDivider(color = TrackTechColors.Cyan.copy(alpha = 0.2f))

        // 工单号（可选）
        OutlinedTextField(
            value = ticket,
            onValueChange = { ticket = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("工单号 / 备注（可选）") },
            enabled = state == DiagnosticUploadState.Idle || state is DiagnosticUploadState.Failed,
        )

        // action 区
        when (val s = state) {
            is DiagnosticUploadState.Idle -> {
                TextButton(
                    onClick = { showPrivacyDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "打包并上传诊断数据",
                        style = TrackTechTypography.RacingTitleSmall,
                        color = TrackTechColors.Cyan,
                    )
                }
            }
            is DiagnosticUploadState.Packing -> uploadProgressBar(0f, "打包中…")
            is DiagnosticUploadState.Uploading -> uploadProgressBar(s.progress, "上传中…")
            is DiagnosticUploadState.Success -> successPanel(s.logId, ctx)
            is DiagnosticUploadState.Failed -> {
                Text(
                    text = "上传失败：${s.reason}",
                    style = TrackTechTypography.RacingTitleSmall,
                    color = TrackTechColors.Red,
                )
                TextButton(onClick = { showPrivacyDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("重试", style = TrackTechTypography.RacingTitleSmall, color = TrackTechColors.Cyan)
                }
            }
        }
    }
}

@Composable
private fun uploadProgressBar(progress: Float, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label ${(progress * 100).toInt()}%",
            style = TrackTechTypography.RacingTitleSmall,
            color = TrackTechColors.Cyan,
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun successPanel(logId: String, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "上传成功 ✅",
            style = TrackTechTypography.RacingTitleSmall,
            color = TrackTechColors.Cyan,
        )
        Text(
            text = "请将以下 Log ID 报给开发：",
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.TextMuted,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(TrackTechColors.Cyan.copy(alpha = 0.1f))
                .padding(12.dp),
        ) {
            Text(
                text = logId,
                style = TrackTechTypography.RacingTitleSmall,
                color = TrackTechColors.Cyan,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("logId", logId))
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            },
        ) {
            Text("复制 Log ID", style = TrackTechTypography.RacingTitleSmall, color = TrackTechColors.TextSecondary)
        }
    }
}
