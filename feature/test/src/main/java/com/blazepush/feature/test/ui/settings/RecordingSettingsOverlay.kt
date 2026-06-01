// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.datastore.RecordingPreferencesRepository
import com.blazepush.feature.test.recording.CameraFacing
import com.blazepush.feature.test.recording.FocusMode
import com.blazepush.feature.test.recording.RecordingCapabilities
import com.blazepush.feature.test.recording.RecordingCapabilityDetector
import com.blazepush.feature.test.recording.RecordingConfig
import com.blazepush.feature.test.recording.RecordingResolution
import com.blazepush.feature.test.recording.resolveEffectiveResolution
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import com.blazepush.feature.test.ui.tracktech.TrackTechTypography
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.koin.compose.koinInject

/** 曝光滑块实用上限：±5 EV 档（与设备实际范围取交集）。 */
private const val PRACTICAL_EV_LIMIT = 5f

/** 曝光滑块 UI 步进：0.2 EV 一跳（落库时映射到设备最近的原生 index）。 */
private const val EV_UI_STEP = 0.2f

/**
 * 录制参数配置浮层（recording-params-config-screen round · 实施期修订：route 屏 → overlay）。
 *
 * 浮在相机预览页之上（不跳路由 / 不开 activity）：左侧留出实时预览（改参数立刻能看到效果），
 * 右侧轻量面板。优点（user 路测反馈）：① 横屏保持（LapLiveScreen 不离开 composition）；
 * ② 相机不解绑、改参数经 rebind 即时反映到预览；③ 轻量弹窗。
 *
 * 仅在 Idle 态由齿轮打开（调用方 gate）。读写经 [RecordingPreferencesRepository]，进屏按 facing 探测能力。
 * 本 round 不暴露 60fps（spec "不暴露 60fps" Requirement）。
 *
 * @param onDismiss 关闭浮层回调
 */
@Composable
fun RecordingSettingsOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    prefsRepository: RecordingPreferencesRepository = koinInject(),
    capabilityDetector: RecordingCapabilityDetector = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by prefsRepository.configFlow.collectAsState(initial = RecordingConfig.DEFAULT)

    fun save(newConfig: RecordingConfig) {
        scope.launch { prefsRepository.update(newConfig) }
    }

    // 按当前 facing 探测能力（前后摄能力不同 → facing 变化重新探测）
    var caps by remember { mutableStateOf(RecordingCapabilities.FALLBACK) }
    LaunchedEffect(config.cameraFacing) {
        val detected = capabilityDetector.detect(context, config.cameraFacing)
        caps = detected
        // 切摄像头后若已选分辨率不被新设备支持（典型：前置不支持 4K）→ 自动降级到生效档（1080p），
        // 让 UI 选中态与实际录制一致，不留"选中但灰显"的 4K。
        if (detected.supportedResolutions.isNotEmpty() && config.resolution !in detected.supportedResolutions) {
            val eff = resolveEffectiveResolution(config.resolution, detected.supportedResolutions)
            if (eff != config.resolution) {
                FileLogger.d("RecSettings", "facing=${config.cameraFacing} 不支持 ${config.resolution} → 自动切 $eff")
                save(config.copy(resolution = eff))
            }
        }
        FileLogger.d("RecSettings", "overlay 探测 facing=${config.cameraFacing} caps=$detected")
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 左侧透明 dismiss 区：露出实时预览 + 点击关闭（无 ripple）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        )

        // 右侧面板（半透明背景，可滚动）
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(360.dp)
                .background(TrackTechColors.Background.copy(alpha = 0.94f))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // 标题 + 完成
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "录制设置",
                    style = TrackTechTypography.RacingTitleSmall,
                    color = TrackTechColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "完成",
                        style = TrackTechTypography.RacingTitleSmall,
                        color = TrackTechColors.Cyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ① 清晰度（4K 不支持灰显）
            SettingSection(title = "清晰度") {
                OptionChips(
                    options = listOf(
                        RecordingResolution.UHD_4K to "4K",
                        RecordingResolution.FHD_1080P to "1080p",
                        RecordingResolution.HD_720P to "720p",
                    ),
                    selected = config.resolution,
                    enabledOf = { it in caps.supportedResolutions },
                    onSelect = { save(config.copy(resolution = it)) },
                )
                if (RecordingResolution.UHD_4K !in caps.supportedResolutions) {
                    Hint("当前摄像头不支持 4K（已灰显）")
                }
            }

            // ② 麦克风
            SettingSection(title = "麦克风") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (config.audioEnabled) "录音频：开" else "录音频：关",
                        style = TrackTechTypography.UiTextBody,
                        color = TrackTechColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = config.audioEnabled,
                        onCheckedChange = { save(config.copy(audioEnabled = it)) },
                    )
                }
            }

            // ③ 摄像头前后置
            SettingSection(title = "摄像头") {
                OptionChips(
                    options = listOf(
                        CameraFacing.BACK to "后置",
                        CameraFacing.FRONT to "前置",
                    ),
                    selected = config.cameraFacing,
                    enabledOf = { true },
                    onSelect = { save(config.copy(cameraFacing = it)) },
                )
            }

            // ④ 对焦（连续自动 / 锁定无限远）
            SettingSection(title = "对焦") {
                OptionChips(
                    options = listOf(
                        FocusMode.CONTINUOUS_AUTO to "连续自动",
                        FocusMode.LOCKED_INFINITY to "锁定无限远",
                    ),
                    selected = config.focusMode,
                    enabledOf = { true },
                    onSelect = { save(config.copy(focusMode = it)) },
                )
                if (config.focusMode == FocusMode.LOCKED_INFINITY) {
                    Hint("赛道远景拍摄推荐，避免来回拉焦")
                }
            }

            // ⑤ 曝光 EV：设备 index 范围可能很宽（如 ±24，1/6 EV/步）→ 限到实用 ±2 EV 并以 EV 档显示（直观）。
            //    拖动只动 draft、松手才落库 → 不触发 rebind 风暴。
            SettingSection(title = "曝光补偿") {
                val evSupported = caps.evRange != 0..0 && caps.evStep > 0f
                if (!evSupported) {
                    Hint("当前摄像头不支持曝光补偿")
                } else {
                    // 设备 EV 档范围 ∩ 实用 ±5 EV，对齐 0.2 网格（往内取，不越设备边界）；滑块在 EV 域、0.2 一跳。
                    val deviceMinEv = caps.evRange.first * caps.evStep
                    val deviceMaxEv = caps.evRange.last * caps.evStep
                    val loEv = ceil(max(-PRACTICAL_EV_LIMIT, deviceMinEv) / EV_UI_STEP) * EV_UI_STEP
                    val hiEv = floor(min(PRACTICAL_EV_LIMIT, deviceMaxEv) / EV_UI_STEP) * EV_UI_STEP
                    val intervals = ((hiEv - loEv) / EV_UI_STEP).roundToInt().coerceAtLeast(1)
                    var evDraft by remember(config.exposureCompensationEv, loEv, hiEv) {
                        mutableStateOf((config.exposureCompensationEv * caps.evStep).coerceIn(loEv, hiEv))
                    }
                    Text(
                        text = "EV %+.1f（%+.1f ~ %+.1f）".format(evDraft, loEv, hiEv),
                        style = TrackTechTypography.UiTextBody,
                        color = TrackTechColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Slider(
                        value = evDraft,
                        onValueChange = { evDraft = it },
                        onValueChangeFinished = {
                            // EV → 设备最近原生 index，clamp 到设备 index 范围后落库
                            val idx = (evDraft / caps.evStep).roundToInt().coerceIn(caps.evRange.first, caps.evRange.last)
                            save(config.copy(exposureCompensationEv = idx))
                        },
                        valueRange = loEv..hiEv,
                        steps = (intervals - 1).coerceAtLeast(0),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.Cyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        content()
    }
}

/** 单行 hint 文字（设备能力提示）。 */
@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = TrackTechTypography.UiTextLabel,
        color = TrackTechColors.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 互斥选项 chips（TextButton 行，避免引 material-icons-extended）。不支持项灰显且不可点。 */
@Composable
private fun <T> OptionChips(
    options: List<Pair<T, String>>,
    selected: T,
    enabledOf: (T) -> Boolean,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val enabled = enabledOf(value)
            val isSelected = value == selected
            TextButton(
                onClick = { if (enabled) onSelect(value) },
                enabled = enabled,
            ) {
                Text(
                    text = if (isSelected) "● $label" else label,
                    style = TrackTechTypography.UiTextBody,
                    color = when {
                        !enabled -> TrackTechColors.TextMuted
                        isSelected -> TrackTechColors.Cyan
                        else -> TrackTechColors.TextSecondary
                    },
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
