package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.blazepush.core.camera.CameraAvailability
import com.blazepush.core.domain.permission.PermissionRequestOutcome
import com.blazepush.core.domain.permission.RequiredCameraPermissions
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.usecase.AbnormalState
import com.blazepush.feature.test.usecase.LapLiveState
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs

private const val HOLD_DURATION_MS = 1500L
private const val HOLD_TICK_MS = 16L

/**
 * 圈速 Live Session 主屏：强制横屏 + 屏幕常亮 + 拦截返回手势 + 2x2 dashboard + HOLD TO END。
 *
 * @author CC
 * @description landscape lap live session screen
 * @date 2026-05-01
 */
@Composable
fun LapLiveScreen(
    navController: NavController,
    sessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    val lapLiveState by sessionViewModel.lapLiveState.collectAsState()
    val track by sessionViewModel.currentSelectedTrack.collectAsState()

    var showEndConfirmation by remember { mutableStateOf(false) }
    val trackName = track?.name?.zh ?: "—"

    // camera-preview-in-laplivescreen round（Decision 4/5 + spec MUST 1/7）：
    // 相机预览默认关（opt-in），hasCamera 降级 gate 查一次。
    var cameraEnabled by remember { mutableStateOf(false) }
    var hasCamera by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CameraAvailability.hasCamera(context) { available ->
            hasCamera = available
            FileLogger.d("CamPreview", "hasCamera=$available")
        }
    }

    // 懒请求权限 launcher（spec MUST 2/3）：复用 RequiredCameraPermissions + PermissionRequestOutcome.from。
    val requestedCameraPermissions = remember {
        RequiredCameraPermissions.forSdk(Build.VERSION.SDK_INT)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        when (PermissionRequestOutcome.from(requestedCameraPermissions, result)) {
            PermissionRequestOutcome.AllGranted -> {
                cameraEnabled = true
                FileLogger.d("CamPreview", "permission AllGranted → cameraEnabled=true")
            }
            is PermissionRequestOutcome.MissingPermissions -> {
                cameraEnabled = false
                FileLogger.d("CamPreview", "permission MissingPermissions → cameraEnabled=false")
                val activity = context.findActivity()
                // 永久拒绝（!shouldShowRequestPermissionRationale）→ 引导跳 app 设置页（spec MUST 3）。
                val permanentlyDenied = activity != null && requestedCameraPermissions.any {
                    !activity.shouldShowRequestPermissionRationale(it)
                }
                if (permanentlyDenied) {
                    Toast.makeText(
                        context,
                        "相机/麦克风权限被永久拒绝，请到系统设置开启",
                        Toast.LENGTH_LONG,
                    ).show()
                    runCatching {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                } else {
                    Toast.makeText(context, "需要相机和麦克风权限才能预览", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // toggle 点击处理（spec MUST 1/2/7）：无相机不响应；已授权直接翻；未授权懒请求。
    val onToggleCamera: () -> Unit = {
        if (!hasCamera) {
            FileLogger.d("CamPreview", "toggle ignored: hasCamera=false")
        } else if (cameraEnabled) {
            cameraEnabled = false
            FileLogger.d("CamPreview", "toggle off → cameraEnabled=false")
        } else {
            val alreadyGranted = requestedCameraPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (alreadyGranted) {
                cameraEnabled = true
                FileLogger.d("CamPreview", "toggle on (already granted) → cameraEnabled=true")
            } else {
                FileLogger.d("CamPreview", "toggle on → launch permission request")
                cameraPermissionLauncher.launch(requestedCameraPermissions.toTypedArray())
            }
        }
    }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        view.keepScreenOn = true
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            view.keepScreenOn = false
        }
    }

    BackHandler { showEndConfirmation = true }

    val onConfirmEnd: () -> Unit = {
        coroutineScope.launch {
            val result = sessionViewModel.finishActiveLapSession()
            if (result != null) {
                LapSessionSaveBus.emit(result)
            }
            navController.popBackStack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TrackTechColors.Background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // camera-preview-in-laplivescreen round（Decision 1 + spec MUST 4）：
        // 预览层是 root Box 第一个子元素（最底），HUD Column 保持其后绘制 → Compose 后绘者在上，
        // HUD 浮在预览之上（屏上合成，本 round 不录不烧录）。
        // M2 重组陷阱：用 if（无 else 分支也不 early-return）控制显隐，绝不 return@Box。
        if (cameraEnabled && hasCamera) {
            CameraPreview(modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LapLiveTopStrip(
                trackName = trackName,
                lapNumber = lapLiveState.currentLapNumber,
                isReady = lapLiveState.abnormalState == null,
            )

            if (lapLiveState.abnormalState != null) {
                AbnormalBanner(
                    state = lapLiveState.abnormalState!!,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                Lap2x2Dashboard(
                    state = lapLiveState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }

            HoldToEndButton(
                onEndCompleted = onConfirmEnd,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // camera-preview-in-laplivescreen round（Decision 4/5 + spec MUST 7）：
        // 相机 toggle 角落浮层（top-end），hasCamera 降级时隐藏（无相机不显示 / 不响应）。
        // 浮在 HUD 之上但不破坏现有 Column 布局（绝对定位在 Box 角落）。
        if (hasCamera) {
            IconButton(
                onClick = onToggleCamera,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = if (cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    contentDescription = if (cameraEnabled) "Camera on" else "Camera off",
                    tint = if (cameraEnabled) TrackTechColors.Cyan else TrackTechColors.TextMuted,
                )
            }
        }
    }

    if (showEndConfirmation) {
        EndConfirmationDialog(
            onContinue = { showEndConfirmation = false },
            onEnd = {
                showEndConfirmation = false
                onConfirmEnd()
            },
        )
    }
}

@Composable
private fun LapLiveTopStrip(
    trackName: String,
    lapNumber: Int,
    isReady: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LAPS",
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.Cyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = trackName,
            style = TrackTechTypography.RacingTitleSmall,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "LAP $lapNumber",
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.Purple,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (isReady) "Ready" else "—",
            style = TrackTechTypography.UiTextSmall,
            color = if (isReady) TrackTechColors.Green else TrackTechColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Lap2x2Dashboard(
    state: LapLiveState,
    modifier: Modifier = Modifier,
) {
    // round redesign-realtime-delta-projection-search：stale 时显示 `--` 占位，不显示不可信数值。
    // Alt B 全量扫描成功路径永远可信；stale 只发生在真正脱离 reference（>50m）时，此时维持旧值会误导。
    val deltaText = if (state.deltaIsStale || state.deltaToBestMs == null) "--" else formatDelta(state.deltaToBestMs)
    val deltaAccent = when {
        state.deltaToBestMs == null -> TrackTechColors.TextMuted
        state.deltaIsStale -> TrackTechColors.TextMuted
        state.deltaToBestMs < 0 -> TrackTechColors.Green
        state.deltaToBestMs > 0 -> TrackTechColors.Red
        else -> TrackTechColors.TextPrimary
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricTile(
                label = "DELTA",
                value = deltaText,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                accentColor = deltaAccent,
                // round add-realtime-lap-delta：Hero 字号下 `+1:23.456` 8 字符在 weight=1f 等分宽度的 tile 容易截断 →
                // 降到 Large（视觉强度仍突出但更紧凑）。同时 valueColor 染数字本身（baseline 仅染 label）。
                valueSize = MetricSize.Large,
                valueKind = MetricKind.Score,
                valueColor = deltaAccent,
            )
            MetricTile(
                label = "CURRENT",
                value = formatLapTime(state.currentLapTimerMs),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                accentColor = TrackTechColors.Cyan,
                valueSize = MetricSize.Large,
                valueKind = MetricKind.Score,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricTile(
                label = "LAST",
                value = formatLapTime(state.lastLapTimeMs),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                accentColor = TrackTechColors.TextSecondary,
                valueSize = MetricSize.Large,
                valueKind = MetricKind.Score,
            )
            MetricTile(
                label = "BEST",
                value = formatLapTime(state.bestLapTimeMs),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                accentColor = TrackTechColors.Purple,
                valueSize = MetricSize.Large,
                valueKind = MetricKind.Score,
            )
        }
    }
}

@Composable
private fun AbnormalBanner(
    state: AbnormalState,
    modifier: Modifier = Modifier,
) {
    val text = when (state) {
        AbnormalState.GPS_SIGNAL_LOST -> "GPS SIGNAL LOST"
        AbnormalState.WAITING_FOR_GPS_LOCK -> "WAITING FOR GPS LOCK"
        AbnormalState.BLE_DISCONNECTED -> "BLE DISCONNECTED"
        AbnormalState.LAP_INVALIDATED -> "LAP INVALIDATED"
    }
    val accent = when (state) {
        AbnormalState.LAP_INVALIDATED, AbnormalState.BLE_DISCONNECTED -> TrackTechColors.Red
        AbnormalState.GPS_SIGNAL_LOST, AbnormalState.WAITING_FOR_GPS_LOCK -> TrackTechColors.Cyan
    }
    CutCornerPanel(
        modifier = modifier,
        cutSize = 12.dp,
        cutCorners = cutCornersAll,
        contentPadding = 24.dp,
        borderColor = accent,
        borderWidth = 2.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = TrackTechTypography.RacingTitleLarge,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HoldToEndButton(
    onEndCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            val startMs = System.currentTimeMillis()
            while (isPressed) {
                val elapsed = System.currentTimeMillis() - startMs
                progress = (elapsed.toFloat() / HOLD_DURATION_MS).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    isPressed = false
                    onEndCompleted()
                    break
                }
                delay(HOLD_TICK_MS)
            }
        } else {
            progress = 0f
        }
    }

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(CutCornerPanelShape(cutSize = 12.dp, cutCorners = cutCornersAll))
            .background(TrackTechColors.SurfaceDark)
            .border(
                width = 2.dp,
                color = TrackTechColors.Red,
                shape = CutCornerPanelShape(cutSize = 12.dp, cutCorners = cutCornersAll),
            )
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    tryAwaitRelease()
                    isPressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(TrackTechColors.Red.copy(alpha = 0.25f)),
        )
        Text(
            text = "HOLD TO END",
            style = TrackTechTypography.RacingTitleSmall,
            color = TrackTechColors.Red,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EndConfirmationDialog(
    onContinue: () -> Unit,
    onEnd: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = {
            Text(
                text = "End Lap Session?",
                style = TrackTechTypography.RacingTitleSmall,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Text(
                text = "Stop recording and save the current session.",
                style = TrackTechTypography.UiTextBody,
                color = TrackTechColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        dismissButton = {
            TextButton(onClick = onContinue) {
                Text(
                    text = "Continue",
                    color = TrackTechColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onEnd) {
                Text(
                    text = "End Session",
                    color = TrackTechColors.Red,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        containerColor = TrackTechColors.Surface,
    )
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun formatLapTime(ms: Long?): String {
    if (ms == null || ms < 0) return "--:--.---"
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    val millis = ms % 1000
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}

private fun formatDelta(ms: Long?): String {
    if (ms == null) return "--"
    val sign = if (ms >= 0) "+" else "-"
    val abs = abs(ms)
    return "%s%.2f s".format(sign, abs / 1000.0)
}
