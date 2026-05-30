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
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import com.blazepush.feature.test.recording.CameraRecordingEngine
import com.blazepush.feature.test.recording.RecordingState
import com.blazepush.feature.test.usecase.AbnormalState
import com.blazepush.feature.test.usecase.LapLiveState
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LapLiveScreen(
    navController: NavController,
    sessionViewModel: TestSessionViewModel = koinViewModel(),
    recordingEngine: CameraRecordingEngine = koinInject(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    val lapLiveState by sessionViewModel.lapLiveState.collectAsState()
    val track by sessionViewModel.currentSelectedTrack.collectAsState()

    var showEndConfirmation by remember { mutableStateOf(false) }
    val trackName = track?.name?.zh ?: "—"

    // camera-preview-in-laplivescreen round v2（横滑独立预览页返工）：
    // 页 0 = 纯 HUD（驾驶页，绝不开相机），页 1 = 相机取景页（仅在该页 current 时才绑相机 = 省电省热）。
    // hasCamera 降级 gate 查一次；cameraPermissionGranted 由懒请求 launcher 回填。
    var hasCamera by remember { mutableStateOf(false) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            RequiredCameraPermissions.forSdk(Build.VERSION.SDK_INT).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val pagerState = rememberPagerState(pageCount = { 2 })

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
                cameraPermissionGranted = true
                FileLogger.d("CamPreview", "permission AllGranted → cameraPermissionGranted=true")
            }
            is PermissionRequestOutcome.MissingPermissions -> {
                cameraPermissionGranted = false
                FileLogger.d("CamPreview", "permission MissingPermissions → cameraPermissionGranted=false")
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

    // 显式请求一次（页 1 "授权"按钮 + 进预览页懒请求复用）：无相机不响应；已授权 no-op。
    val requestCameraPermission: () -> Unit = {
        if (!hasCamera) {
            FileLogger.d("CamPreview", "permission request ignored: hasCamera=false")
        } else if (cameraPermissionGranted) {
            FileLogger.d("CamPreview", "permission request skipped: already granted")
        } else {
            FileLogger.d("CamPreview", "launch camera permission request")
            cameraPermissionLauncher.launch(requestedCameraPermissions.toTypedArray())
        }
    }

    // 横滑到预览页（settledPage==1）且有相机但未授权 → 懒请求一次（spec MUST 进预览页懒请求）。
    LaunchedEffect(pagerState.settledPage, hasCamera, cameraPermissionGranted) {
        FileLogger.d("CamPreview", "settledPage=${pagerState.settledPage} hasCamera=$hasCamera granted=$cameraPermissionGranted")
        if (pagerState.settledPage == 1 && hasCamera && !cameraPermissionGranted) {
            FileLogger.d("CamPreview", "entered preview page without permission → lazy request")
            cameraPermissionLauncher.launch(requestedCameraPermissions.toTypedArray())
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

    // camera-preview-in-laplivescreen round v2（横滑独立预览页返工 · spec MUST 4 重写）：
    // HorizontalPager 2 页：页 0 = 纯 HUD（驾驶页），页 1 = 相机取景页。
    // beyondBoundsPageCount 不设（默认 0）→ 页 1 不会在停留页 0 时被预组合 → 页 0 时相机绝不绑定（省电省热）。
    // M2 重组陷阱：所有页/权限/相机显隐分支 MUST 用 if/else，绝不 return@HorizontalPager。
    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .background(TrackTechColors.Background),
    ) { page ->
        if (page == 0) {
            LapHudPage(
                trackName = trackName,
                lapLiveState = lapLiveState,
                onConfirmEnd = onConfirmEnd,
                hasCamera = hasCamera,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CameraPreviewPage(
                isCurrent = pagerState.settledPage == 1,
                hasCamera = hasCamera,
                permissionGranted = cameraPermissionGranted,
                onRequestPermission = requestCameraPermission,
                recordingEngine = recordingEngine,
                sessionViewModel = sessionViewModel,
                modifier = Modifier.fillMaxSize(),
            )
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

/**
 * 页 0 = 纯 HUD 驾驶页（top strip / abnormal banner 或 Lap2x2Dashboard / HOLD TO END）。
 * 绝不含任何相机预览（驾驶时纯 HUD = 省电省热）。右缘加不显眼的横滑提示（chevron + 2 dot）。
 */
@Composable
private fun LapHudPage(
    trackName: String,
    lapLiveState: LapLiveState,
    onConfirmEnd: () -> Unit,
    hasCamera: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(TrackTechColors.Background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
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

        // 横滑提示（spec MUST 5）：右缘小 chevron + 2 个 dot 页指示器，不喧宾夺主。
        // 仅在有相机时提示（无相机机型预览页只显示降级文案，仍可横滑但不强调）。
        if (hasCamera) {
            SwipeToCameraHint(
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * 右缘横滑提示：小 chevron + 2 个 page dot（当前 HUD 页高亮第 0 个）。V2 单行不喧宾夺主。
 */
@Composable
private fun SwipeToCameraHint(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(TrackTechColors.Cyan),
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(TrackTechColors.TextMuted),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = "Swipe to camera",
            tint = TrackTechColors.TextMuted,
        )
    }
}

/**
 * 页 1 = 相机取景页（camera-recording-and-gps-sync round 改造）。
 *
 * 三态分流（全用 if/else，绝不 early-return — M2 崩溃教训）：
 *  - 无相机机型（hasCamera=false）→ 显示"无可用相机"。
 *  - 有相机但未授权 → 显示"需要相机/麦克风权限" + "授权"按钮。
 *  - 有相机且已授权 → 仅当 isCurrent（settledPage==1）时渲染 RecordableCameraPreview + start/stop 按钮。
 *
 * isCurrent gate：回到页 0 → RecordableCameraPreview 不在 composition → onDispose unbindAll（释放相机）。
 * 录制不随 isCurrent 中断（引擎内部处理）；unbindAll 时引擎会 stop 进行中的录制。
 */
@Composable
private fun CameraPreviewPage(
    isCurrent: Boolean,
    hasCamera: Boolean,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    recordingEngine: CameraRecordingEngine,
    sessionViewModel: TestSessionViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recordingState by recordingEngine.recordingState.collectAsState()

    Box(
        modifier = modifier
            .background(TrackTechColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        if (!hasCamera) {
            Text(
                text = "无可用相机",
                style = TrackTechTypography.UiTextBody,
                color = TrackTechColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (!permissionGranted) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "需要相机/麦克风权限",
                    style = TrackTechTypography.UiTextBody,
                    color = TrackTechColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRequestPermission) {
                    Text(
                        text = "授权",
                        style = TrackTechTypography.RacingTitleSmall,
                        color = TrackTechColors.Cyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else if (isCurrent) {
            // 仅当本页为当前停留页时才绑相机（省电核心）。离页 → 不在 composition → onDispose unbindAll。
            // round 3 改：使用 RecordableCameraPreview（Preview + VideoCapture 双 use-case），替代原 CameraPreview。
            RecordableCameraPreview(
                engine = recordingEngine,
                modifier = Modifier.fillMaxSize(),
            )

            // 角落标题（左上角）
            Text(
                text = "CAMERA · 取景",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )

            // 录制状态文字（右上角）—— round 5 再做精致 REC 红点，此处最小可用
            val recStateText = when (recordingState) {
                is RecordingState.Recording -> "REC"
                is RecordingState.Stopping -> "停止中..."
                is RecordingState.Error -> "录制错误"
                else -> ""
            }
            if (recStateText.isNotEmpty()) {
                Text(
                    text = recStateText,
                    style = TrackTechTypography.UiTextLabel,
                    color = when (recordingState) {
                        is RecordingState.Recording -> TrackTechColors.Red
                        is RecordingState.Error -> TrackTechColors.Red
                        else -> TrackTechColors.TextMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                )
            }

            // Start/Stop 录制按钮（右下角 · 最小可用 · 精致版留 round 5）
            val recBtnText = when (recordingState) {
                is RecordingState.Recording -> "STOP"
                is RecordingState.Stopping -> "停止中..."
                is RecordingState.Error -> "重试"
                else -> "REC"
            }
            val recBtnColor = when (recordingState) {
                is RecordingState.Recording -> TrackTechColors.Red
                is RecordingState.Error -> TrackTechColors.Red
                else -> TrackTechColors.Cyan
            }
            TextButton(
                onClick = {
                    if (recordingState is RecordingState.Recording) {
                        recordingEngine.stopRecording()
                    } else if (recordingState is RecordingState.Idle || recordingState is RecordingState.Error) {
                        if (recordingState is RecordingState.Error) recordingEngine.resetError()
                        recordingEngine.startRecording(
                            context = context,
                            activeSessionId = sessionViewModel.getActiveLapSessionId(),
                        )
                    }
                    // Stopping 状态下按钮点击忽略
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Text(
                    text = recBtnText,
                    style = TrackTechTypography.RacingTitleSmall,
                    color = recBtnColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // 已授权但当前不在本页（settledPage!=1）→ 占位，不绑相机（防 beyondBounds 预组合时误绑）。
            Text(
                text = "横滑查看相机",
                style = TrackTechTypography.UiTextBody,
                color = TrackTechColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
