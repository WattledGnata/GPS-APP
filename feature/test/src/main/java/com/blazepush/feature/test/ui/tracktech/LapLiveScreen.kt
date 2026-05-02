package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
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
import androidx.navigation.NavController
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
    val deltaText = formatDelta(state.deltaToBestMs)
    val deltaAccent = when {
        state.deltaToBestMs == null -> TrackTechColors.TextMuted
        // round add-realtime-lap-delta：stale 时（连续失效 ≥ 5 帧 / 1 秒）字色降级，
        // 数字仍显示上一帧 prevDeltaMs，但用户立刻看到"灰了 → 不可信"。
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
