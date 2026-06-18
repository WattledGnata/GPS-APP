package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.core.domain.model.TestResult
import com.blazepush.core.domain.model.TestState
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.feature.test.utils.VoiceAnnouncer
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import com.blazepush.feature.test.viewmodel.TestMode
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 加速测试起步阈值（km/h）：speed 低于此值时进度条进入 "WAITING FOR LAUNCH" 文案态。
 * 3.0 选值理由：高于 GPS 静止噪声（0-2 km/h）+ 低于真起步初速（车踩油门 200-300ms 内跨过）。
 * 调整：直接改本常量，无 settings UI / 兼容风险。详 design.md Decision 1。
 */
internal const val LAUNCH_SPEED_THRESHOLD_KMH = 3.0

internal data class ProgressState(val progress: Float, val waitingForLaunch: Boolean)

/**
 * 从 testState / currentMode / speed 派生 progress + waitingForLaunch。
 * 纯函数，无 Compose / ViewModel 依赖；JUnit4 单测覆盖关键边界见 TrackTechTestExecutionProgressTest。
 */
internal fun computeProgressState(
    testState: TestState,
    currentMode: TestMode,
    speed: Double,
    launchThresholdKmh: Double = LAUNCH_SPEED_THRESHOLD_KMH,
): ProgressState {
    val progress: Float = when (val s = testState) {
        is TestState.Running -> when (currentMode) {
            TestMode.Acceleration -> (speed / 100.0).toFloat().coerceIn(0f, 1f)
            TestMode.Braking -> {
                val start = s.session.dataPoints.firstOrNull()?.speed ?: 100.0
                ((start - speed) / start.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
            }
            else -> 0f
        }
        is TestState.Completed -> 1f
        else -> 0f
    }
    val waitingForLaunch = testState is TestState.Running &&
        currentMode == TestMode.Acceleration &&
        speed < launchThresholdKmh
    return ProgressState(progress, waitingForLaunch)
}

@Composable
fun TrackTechTestExecutionScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
    sessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    val gpsViewModel = koinInject<GpsDataViewModel>()

    // 路测问题 #1：0-100 测试中屏幕自动息屏 → 测试执行屏全程强制亮屏(同 LapLiveScreen 模式)
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // round perftest-result-detail-navigation-feedback：成绩闭合后 Toast "已保存到历史" + 按钮
    // 文案改"查看详情" + 点击 navigate 详情屏（消除"必须点 Done 才记录"的体感错觉）。
    val context = LocalContext.current

    val gpsData by gpsViewModel.gpsData.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    val dataQuality by gpsViewModel.dataQuality.collectAsState()
    val testState by sessionViewModel.testState.collectAsState()
    val currentMode by sessionViewModel.currentMode.collectAsState()
    val countdownSeconds by sessionViewModel.countdownSeconds.collectAsState()

    // 路测修复(2026-06-04 #2 播报无效真因)：V1→V2 改版时语音链路未迁移——init/announce
    // 只在旧 ui/screen/TestExecutionScreen 调用,V2 全程无人调 speak(两晚路测日志零 TTS
    // 痕迹的根因,与 TTS 引擎/AudioFocus/车机路由皆无关)。照搬 V1 接法(koin 单例)。
    val voiceAnnouncer: VoiceAnnouncer = koinInject()
    LaunchedEffect(Unit) { voiceAnnouncer.init() }
    // launch-arming-feedback Decision 2:静止武装就绪上升沿播报(VM 在 enterSmartLaunch/
    // cancelTest 复位 false → 重进页面不误播;LaunchedEffect 仅在值变化时重跑)
    val launchArmed by sessionViewModel.launchArmed.collectAsState()
    LaunchedEffect(launchArmed) {
        if (launchArmed) voiceAnnouncer.announceLaunchReady()
    }
    LaunchedEffect(testState) {
        val s = testState
        if (s is TestState.Completed) {
            // round perftest-result-detail-navigation-feedback：成绩落库后 Toast 反馈消除
            // "必须点 Done 才记录"体感错觉（实际 finishTest 已自动 saveResult）
            Toast.makeText(context, "已保存到历史", Toast.LENGTH_SHORT).show()
            // unify-speed-judgement-source Decision 3:DNF(totalTime<=0)播"测试未完成",
            // 不播"零点零零秒"
            if (s.result.totalTime <= 0.0) {
                voiceAnnouncer.announceTestNotCompleted()
            } else {
                when (s.result.template) {
                    is TestTemplate.Acceleration0To100 -> voiceAnnouncer.announceAccelerationResult(s.result.totalTime)
                    is TestTemplate.Braking100To0 -> voiceAnnouncer.announceBrakingResult(s.result.totalTime)
                }
            }
        }
    }

    // unify-speed-judgement-source Decision 2:仪表用滤波后速度(与判停/成绩同源);
    // raw 仅保留给状态条/卫星等非速度字段
    val speed by sessionViewModel.filteredSpeedKmh.collectAsState()
    // 实时尝试计时(2026-06-05):Running 期不再显示 session 总时长(蠕动回撤也一直涨到
    // 50 多秒,与成绩窗口语义脱节)——改 VM 的 liveAttemptElapsedSeconds:上穿 1.0 起步
    // 开始数,掉回静止清零重数;最后一把的实时值 ≈ 最终成绩
    val liveAttemptElapsed by sessionViewModel.liveAttemptElapsedSeconds.collectAsState()
    val elapsedSeconds: Double = when (val s = testState) {
        is TestState.Running -> liveAttemptElapsed
        is TestState.Completed -> s.result.totalTime
        else -> 0.0
    }
    val progressState = computeProgressState(testState, currentMode, speed)
    val progress = progressState.progress

    val signalGood = dataQuality.overall == QualityLevel.EXCELLENT || dataQuality.overall == QualityLevel.GOOD
    val qualityLabel = when (dataQuality.overall) {
        QualityLevel.EXCELLENT, QualityLevel.GOOD -> "Good"
        QualityLevel.FAIR -> "Fair"
        QualityLevel.POOR -> "Poor"
    }
    val isConnected = connectionState == ConnectionState.CONNECTED
    val frequencyHz = gpsData.frequency.toInt().coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TrackTechColors.Background),
    ) {
        Spacer(Modifier.height(12.dp))

        ExecutionStatusStrip(
            gpsReady = gpsData.isTestReady,
            isConnected = isConnected,
            frequencyHz = frequencyHz,
            qualityLabel = qualityLabel,
            signalGood = signalGood,
        )

        Spacer(Modifier.height(12.dp))

        TestTypeHeader(currentMode = currentMode)

        Spacer(Modifier.height(0.dp))

        PhaseBanner(
            testState = testState,
            currentMode = currentMode,
            countdownSeconds = countdownSeconds,
            launchArmed = launchArmed,
        )

        Spacer(Modifier.height(8.dp))

        // CURRENT SPEED
        Text(
            text = "CURRENT SPEED",
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.Purple,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        BigSpeedDisplay(speed = speed)

        Spacer(Modifier.height(8.dp))

        // ELAPSED TIME
        Text(
            text = "ELAPSED TIME",
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.Purple,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        ElapsedTimeDisplay(elapsedSeconds = elapsedSeconds)

        Spacer(Modifier.height(12.dp))

        ProgressPanel(
            progress = progress,
            currentMode = currentMode,
            displayPct = (progress * 100).toInt(),
            waitingForLaunch = progressState.waitingForLaunch,
        )

        Spacer(Modifier.height(8.dp))

        SignalFooter(
            satelliteCount = gpsData.satelliteCount,
            hdop = gpsData.hdop,
        )

        Spacer(Modifier.weight(1f))

        CancelOrDoneButton(
            testState = testState,
            onCancel = {
                sessionViewModel.cancelTest()
                navController.popBackStack()
            },
            // round perftest-result-detail-navigation-feedback：Completed → navigate 详情屏
            // （跟 RecordsHomeScreen 历史入口同源 performance_result/{testId}）；race fallback popBackStack
            onDone = {
                val s = testState
                if (s is TestState.Completed) {
                    navController.navigate("performance_result/${s.result.id}")
                } else {
                    navController.popBackStack()
                }
            },
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ExecutionStatusStrip(
    gpsReady: Boolean,
    isConnected: Boolean,
    frequencyHz: Int,
    qualityLabel: String,
    signalGood: Boolean,
) {
    val shape = CutCornerPanelShape(cutSize = 8.dp, cutCorners = cutCornersAll)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .border(1.dp, TrackTechColors.Border, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusCell(
            icon = { Icon(Icons.Filled.GpsFixed, null, tint = if (gpsReady) TrackTechColors.Green else TrackTechColors.Red, modifier = Modifier.size(12.dp)) },
            label = "GPS",
            status = if (gpsReady) "Ready" else "No Fix",
            statusColor = if (gpsReady) TrackTechColors.Green else TrackTechColors.Red,
        )
        StripDivider()
        StatusCell(
            icon = { Icon(Icons.Filled.Bluetooth, null, tint = if (isConnected) TrackTechColors.Cyan else TrackTechColors.TextMuted, modifier = Modifier.size(12.dp)) },
            label = "BLE",
            status = if (isConnected) "Connected" else "Disconnected",
            statusColor = if (isConnected) TrackTechColors.Cyan else TrackTechColors.TextMuted,
        )
        StripDivider()
        StatusCell(
            icon = { Icon(Icons.Filled.GraphicEq, null, tint = TrackTechColors.Cyan, modifier = Modifier.size(12.dp)) },
            label = "${frequencyHz}Hz",
            status = null,
            statusColor = TrackTechColors.Cyan,
        )
        StripDivider()
        StatusCell(
            icon = { Icon(Icons.Filled.SignalCellularAlt, null, tint = if (signalGood) TrackTechColors.Green else TrackTechColors.Red, modifier = Modifier.size(12.dp)) },
            label = "Quality",
            status = qualityLabel,
            statusColor = if (signalGood) TrackTechColors.Green else TrackTechColors.Red,
        )
    }
}

@Composable
private fun StatusCell(
    icon: @Composable () -> Unit,
    label: String,
    status: String?,
    statusColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(4.dp))
        Column {
            Text(text = label, style = TrackTechTypography.UiTextSmall, color = TrackTechColors.TextSecondary)
            if (status != null) {
                Text(text = status, style = TrackTechTypography.UiTextSmall, color = statusColor)
            }
        }
    }
}

@Composable
private fun StripDivider() {
    Box(
        modifier = Modifier
            .height(24.dp)
            .width(1.dp)
            .background(TrackTechColors.Border),
    )
}

@Composable
private fun TestTypeHeader(currentMode: TestMode) {
    val isAcceleration = currentMode == TestMode.Acceleration
    val speedLabel = if (isAcceleration) "0-100 km/h" else "100-0 km/h"
    val typeLabel = if (isAcceleration) "ACCELERATION TEST" else "BRAKING TEST"
    val icon = if (isAcceleration) Icons.Filled.Speed else Icons.Outlined.DoNotDisturbOn

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .border(1.dp, TrackTechColors.Purple.copy(alpha = 0.5f), CutCornerPanelShape(16.dp, cutCornersDiagonal)),
    ) {
        // Decorative rising/falling line graph on the right
        Canvas(modifier = Modifier.matchParentSize()) {
            val lineColor = TrackTechColors.Purple.copy(alpha = 0.25f)
            val step = 14.dp.toPx()
            var x = size.width * 0.55f
            // Acceleration → rising lines (bottom-left to top-right)
            // Braking → falling lines (top-left to bottom-right)
            while (x < size.width + size.height) {
                val (start, end) = if (isAcceleration) {
                    Offset(x, size.height) to Offset(x - size.height * 0.7f, 0f)
                } else {
                    Offset(x - size.height * 0.7f, 0f) to Offset(x, size.height)
                }
                drawLine(lineColor, start, end, strokeWidth = 1.2.dp.toPx())
                x += step
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(TrackTechColors.PurpleAlpha20)
                    .border(1.dp, TrackTechColors.Purple.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TrackTechColors.Purple,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = speedLabel,
                    style = TrackTechTypography.RacingTitleMedium,
                    color = TrackTechColors.Purple,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = typeLabel,
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PhaseBanner(
    testState: TestState,
    currentMode: TestMode,
    countdownSeconds: Int,
    launchArmed: Boolean = false,
) {
    val (phaseTag, phaseTitle, phaseSub) = when (testState) {
        is TestState.Idle -> Triple("STANDBY", "IDLE", "—")
        // launch-arming-feedback Decision 3:Preparing 三态——倒计时 / 引导停稳 / 武装就绪
        // (旧版只有 COUNTDOWN,用户行驶中进页面不知道"要先停稳",2.7km/h 缓动直接误触发)
        is TestState.Preparing -> when {
            countdownSeconds > 0 -> Triple(
                "PREPARING",
                "COUNTDOWN  $countdownSeconds",
                "Waiting for start conditions",
            )
            !launchArmed && currentMode == TestMode.Acceleration -> Triple(
                "STOP",
                "BRING CAR TO A STOP",
                "Hold still for 1 second to arm",
            )
            launchArmed && currentMode == TestMode.Acceleration -> Triple(
                "ARMED",
                "READY TO LAUNCH",
                "Floor it when ready",
            )
            else -> Triple(
                "PREPARING",
                "REACH SPEED",
                "Reach 95-105 km/h",
            )
        }
        is TestState.Ready -> Triple(
            "READY",
            if (currentMode == TestMode.Acceleration) "HOLD STILL" else "REACH SPEED",
            if (currentMode == TestMode.Acceleration) "Release brakes to start" else "Reach 95-105 km/h",
        )
        is TestState.Running -> Triple(
            "TESTING",
            if (currentMode == TestMode.Acceleration) "ACCELERATING" else "BRAKING",
            "Started automatically",
        )
        // unify-speed-judgement-source Decision 3:DNF(totalTime<=0)显式表达,
        // 不再显示 "DONE" 成功文案(2026-06-04 路测"done 却成绩 0"认知撕裂)
        is TestState.Completed -> if (testState.result.totalTime <= 0.0) {
            Triple("DNF", "NOT COMPLETED", "Target speed not reached")
        } else {
            Triple(
                "COMPLETE",
                if (currentMode == TestMode.Acceleration) "ACCELERATION DONE" else "BRAKING DONE",
                "Result recorded",
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        TrackTechColors.DeepPurple,
                        TrackTechColors.Purple.copy(alpha = 0.55f),
                    ),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val lineColor = TrackTechColors.Purple.copy(alpha = 0.25f)
            val step = 18.dp.toPx()
            var x = size.width * 0.45f
            while (x < size.width + size.height) {
                drawLine(
                    color = lineColor,
                    start = Offset(x, size.height),
                    end = Offset(x - size.height, 0f),
                    strokeWidth = 1.2.dp.toPx(),
                )
                x += step
            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .align(Alignment.CenterStart),
        ) {
            Text(
                text = phaseTag,
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Purple.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = phaseTitle,
                style = TrackTechTypography.RacingTitleLarge,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = phaseSub,
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BigSpeedDisplay(speed: Double) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                // 路测反馈#7：96sp 行车看不清 → MechanicalGiant 192sp；同时去小数
                // （GPS 速度小数位本是噪声,且 192sp 下 5 字符超 vivo 小屏宽度）
                Text(
                    text = "%.0f".format(speed),
                    style = TrackTechTypography.MechanicalGiant,
                    color = TrackTechColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "km/h",
                style = TrackTechTypography.UiTextBody,
                color = TrackTechColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ElapsedTimeDisplay(elapsedSeconds: Double) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "%.2f".format(elapsedSeconds),
                style = TrackTechTypography.ScoreMedium,
                color = TrackTechColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "s",
                style = TrackTechTypography.UiTextBody,
                color = TrackTechColors.TextMuted,
                modifier = Modifier.padding(bottom = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressPanel(
    progress: Float,
    currentMode: TestMode,
    displayPct: Int,
    waitingForLaunch: Boolean = false,
) {
    val targetLabel = if (currentMode == TestMode.Acceleration) "TO 100 km/h" else "TO 0 km/h"
    val fillFraction = if (waitingForLaunch) 0f else progress.coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(72.dp)
            .clip(CutCornerPanelShape(10.dp, cutCornersAll))
            .border(1.dp, TrackTechColors.Border, CutCornerPanelShape(10.dp, cutCornersAll)),
    ) {
        // Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TrackTechColors.SurfaceDark),
        )
        // Fill
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = fillFraction),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                TrackTechColors.DeepPurple,
                                TrackTechColors.Purple.copy(alpha = 0.75f),
                            ),
                        ),
                    ),
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineColor = TrackTechColors.Purple.copy(alpha = 0.20f)
                val step = 14.dp.toPx()
                var x = 0f
                while (x < size.width + size.height) {
                    drawLine(
                        color = lineColor,
                        start = Offset(x, size.height),
                        end = Offset(x - size.height, 0f),
                        strokeWidth = 1.2.dp.toPx(),
                    )
                    x += step
                }
            }
        }
        // Labels overlay
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "0",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Purple,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (waitingForLaunch) {
                    Text(
                        text = "WAITING FOR LAUNCH",
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.Cyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = "$displayPct%",
                        style = TrackTechTypography.RacingTitleMedium,
                        color = TrackTechColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = targetLabel,
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "100",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Purple,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SignalFooter(satelliteCount: Int, hdop: Double) {
    val satsOk = satelliteCount >= 6
    val hdopOk = hdop > 0.0 && hdop < 2.0
    val shape = CutCornerPanelShape(8.dp, cutCornersAll)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .border(1.dp, TrackTechColors.Border, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.GpsFixed,
            contentDescription = null,
            tint = if (satsOk) TrackTechColors.Cyan else TrackTechColors.TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = "SATELLITES", style = TrackTechTypography.UiTextLabel, color = TrackTechColors.TextMuted)
            Text(text = satelliteCount.toString(), style = TrackTechTypography.UiTextBody, color = TrackTechColors.TextPrimary)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(32.dp).background(TrackTechColors.Border))
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Filled.GpsFixed,
            contentDescription = null,
            tint = if (hdopOk) TrackTechColors.Cyan else TrackTechColors.TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = "HDOP", style = TrackTechTypography.UiTextLabel, color = TrackTechColors.TextMuted)
            Text(text = "%.1f".format(hdop), style = TrackTechTypography.UiTextBody, color = TrackTechColors.TextPrimary)
        }
    }
}

@Composable
private fun CancelOrDoneButton(
    testState: TestState,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val isComplete = testState is TestState.Completed
    val label = if (isComplete) "查看详情" else "CANCEL TEST"
    val color = if (isComplete) TrackTechColors.Green else TrackTechColors.Red
    val onClick = if (isComplete) onDone else onCancel
    val shape = CutCornerPanelShape(10.dp, cutCornersAll)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .border(1.dp, color, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TrackTechTypography.UiTextLabel,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}