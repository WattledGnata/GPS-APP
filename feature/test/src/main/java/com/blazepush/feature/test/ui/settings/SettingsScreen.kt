// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.R
import com.blazepush.feature.test.datastore.UserProfileRepository
import com.blazepush.feature.test.diagnostic.VersionTapCounter
import com.blazepush.feature.test.ui.diagnostic.DiagnosticUploadSheet
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import com.blazepush.feature.test.ui.tracktech.TrackTechTypography
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 应用设置页（driver-display-name round · 填空 DeviceHomeScreen 的 SETTINGS 占位）。
 *
 * 第一个功能：**车手显示名**（livetiming lap-upload `driver` 必填项的本地前置）。
 * 用户填一次、跨会话保留。后续可在此页扩展 单位 / 语音 / 自动重连 等（DeviceHomeScreen
 * SETTINGS 副标题 "Units · Voice · Auto reconnect" 的预期）。
 */
@Composable
fun SettingsScreen(
    navController: NavController,
    userProfileRepository: UserProfileRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val savedName by userProfileRepository.driverName.collectAsState(initial = "")
    // 本地 draft 驱动 TextField，避免每次 Flow 重发导致光标跳；持久化值加载后同步一次。
    var draft by remember { mutableStateOf("") }
    var showDriverNameRequired by remember { mutableStateOf(false) }
    LaunchedEffect(savedName) {
        if (draft.isEmpty() && savedName.isNotEmpty()) draft = savedName
    }

    // 暗门连点计数器（design D1）：3 秒窗口 7 次点版本号触发诊断面板
    val tapCounter = remember { VersionTapCounter() }
    var showDiagnostic by remember { mutableStateOf(false) }

    // 版本号（取 PackageInfo，feature:test 库模块无独立 BuildConfig versionName）
    val pkg = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
    }
    val versionText = remember(pkg) {
        val vName = pkg?.versionName ?: "?"
        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg?.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg?.versionCode?.toLong()
        } ?: 0
        "$vName ($vCode)"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrackTechColors.Background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 顶部返回 + 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { navController.popBackStack() }) {
                Text(
                    text = stringResource(R.string.action_back),
                    style = TrackTechTypography.RacingTitleSmall,
                    color = TrackTechColors.Cyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.screen_settings),
                style = TrackTechTypography.RacingTitleSmall,
                color = TrackTechColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 车手显示名
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.label_driver),
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    scope.launch {
                        userProfileRepository.setDriverName(it.trim())
                        FileLogger.d("UserProfile", "driverName set len=${it.trim().length}")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.label_driver_name_hint)) },
            )
            Text(
                text = stringResource(R.string.label_driver_name_detail),
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 上报是唯一需要车手名的真实动作；缺名时不开启，也不影响本地记录。
        val livetimingEnabled by userProfileRepository.livetimingEnabled.collectAsState(initial = false)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = stringResource(R.string.label_livetiming_upload),
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.Cyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.label_livetiming_upload_detail),
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = livetimingEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && draft.trim().isEmpty()) {
                        showDriverNameRequired = true
                    } else {
                        scope.launch {
                            userProfileRepository.setLivetimingEnabled(enabled)
                            FileLogger.d("UserProfile", "livetimingEnabled set $enabled")
                        }
                    }
                },
            )
        }

        // 版本号（暗门入口：连点 7 次弹出诊断上传面板，design D1）
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = versionText,
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.TextMuted,
                modifier = Modifier.clickable {
                    val now = System.currentTimeMillis()
                    if (tapCounter.tap(now)) {
                        showDiagnostic = true
                    }
                }
            )
        }
    }

    if (showDriverNameRequired) {
        AlertDialog(
            onDismissRequest = { showDriverNameRequired = false },
            title = { Text(stringResource(R.string.driver_name_required_title)) },
            text = { Text(stringResource(R.string.driver_name_required_message)) },
            confirmButton = {
                TextButton(onClick = { showDriverNameRequired = false }) {
                    Text(stringResource(R.string.action_enter_name))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDriverNameRequired = false }) {
                    Text(stringResource(R.string.action_later))
                }
            },
        )
    }

    // 诊断上传面板 overlay（暗门触发后用全屏 Box 遮盖，非独立路由）
    if (showDiagnostic) {
        DiagnosticUploadSheet(onDismiss = { showDiagnostic = false })
    }
}
