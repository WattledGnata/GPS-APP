package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.feature.test.R
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.ui.tracktech.format.formatDate
import com.blazepush.feature.test.ui.tracktech.format.formatLapMs
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

@Composable
fun LapsHomeScreen(
    navController: NavController,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testSessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    val gpsViewModel = koinInject<GpsDataViewModel>()
    val gpsData by gpsViewModel.gpsData.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    val dataQuality by gpsViewModel.dataQuality.collectAsState()
    val lapGpsReadiness by testSessionViewModel.lapGpsReadiness.collectAsState()
    val availableTracks by testSessionViewModel.availableTracks.collectAsState()
    val currentTrack by testSessionViewModel.currentSelectedTrack.collectAsState()
    val recentTrackIds by testSessionViewModel.recentTrackIds.collectAsState()
    val bestLapForCurrent by testSessionViewModel.bestLapForCurrentTrack.collectAsState()
    var showSelectTrackSheet by remember { mutableStateOf(false) }

    val signalGood = dataQuality.overall == QualityLevel.EXCELLENT ||
        dataQuality.overall == QualityLevel.GOOD
    val signalLabel = when (dataQuality.overall) {
        QualityLevel.EXCELLENT, QualityLevel.GOOD -> stringResource(R.string.quality_good_signal)
        QualityLevel.FAIR -> stringResource(R.string.quality_weak_signal)
        QualityLevel.POOR -> stringResource(R.string.quality_no_signal)
    }
    val gpsPresentation = remember(lapGpsReadiness, connectionState) {
        GpsReadinessPresentationMapper.present(lapGpsReadiness, connectionState)
    }
    val statusItems = statusItemsFromGpsState(
        gpsStatusLabel = stringResource(gpsPresentation.shortLabelRes),
        gpsStatusTone = gpsPresentation.tone,
        frequencyHz = gpsData.frequency.toInt(),
        signalLabel = signalLabel,
        signalIsGood = signalGood,
    )

    val currentTrackLabel = currentTrack?.name?.zh ?: "—"
    val lastTrack = recentTrackIds.firstOrNull()
        ?.let { id -> availableTracks.firstOrNull { it.id == id } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TrackTechColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.screen_laps),
                style = TrackTechTypography.RacingTitleLarge,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = stringResource(R.string.action_help),
                tint = TrackTechColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }

        TrackTechStatusStrip(
            items = statusItems,
            onClick = { onTabSelected(TabIndex.Device) },
        )

        CurrentTrackPanel(
            track = currentTrack,
            onClick = { showSelectTrackSheet = true },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryActionPanel(
                title = stringResource(R.string.action_start_lap_session),
                subtitle = stringResource(R.string.action_begin_timing),
                leadingIcon = Icons.Filled.Flag,
                onClick = {
                    startLapSession(
                        track = testSessionViewModel.currentSelectedTrack.value,
                        selectLapDebugMode = testSessionViewModel::selectLapDebugMode,
                        navigateToLapLive = { navController.navigate("lap_live") },
                    )
                },
            )
            if (lastTrack != null) {
                SecondaryActionPanel(
                    title = stringResource(R.string.action_quick_start, lastTrack.name.zh),
                    subtitle = stringResource(R.string.action_quick_start_detail),
                    leadingIcon = Icons.Filled.Flag,
                    accentColor = TrackTechColors.Purple,
                    onClick = {
                        quickStartPreviousTrack(
                            track = lastTrack,
                            selectTrack = testSessionViewModel::selectTrack,
                            selectLapDebugMode = testSessionViewModel::selectLapDebugMode,
                            navigateToLapLive = { navController.navigate("lap_live") },
                        )
                    },
                )
            }
            SecondaryActionPanel(
                title = stringResource(R.string.action_change_track),
                subtitle = stringResource(R.string.action_change_track_detail),
                leadingIcon = Icons.Filled.SwapHoriz,
                accentColor = TrackTechColors.Cyan,
                onClick = { showSelectTrackSheet = true },
            )
            if (isDebugCaptureAvailable()) {
                SecondaryActionPanel(
                    title = stringResource(R.string.action_free_capture),
                    subtitle = stringResource(R.string.action_free_capture_detail),
                    leadingIcon = Icons.Filled.BugReport,
                    accentColor = TrackTechColors.Purple,
                    onClick = {
                        testSessionViewModel.startDebugCaptureMode()
                        navController.navigate("lap_live")
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "RECENT BEST",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetricTile(
                label = currentTrackLabel.uppercase(),
                value = bestLapForCurrent?.bestLapMs?.let { formatLapMs(it) } ?: "--",
                status = bestLapForCurrent?.let { "Personal Best · ${formatDate(it.startTs)}" } ?: "暂无成绩",
                accentColor = TrackTechColors.Purple,
                valueSize = MetricSize.Medium,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "RECENT TRACKS",
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.Cyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "VIEW ALL",
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.Cyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { showSelectTrackSheet = true },
                )
            }
            RecentTracksStrip(
                recentTrackIds = recentTrackIds,
                availableTracks = availableTracks,
                currentTrackId = currentTrack?.id,
                onTrackClick = { testSessionViewModel.selectTrack(it) },
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showSelectTrackSheet) {
        SelectTrackBottomSheet(
            onDismiss = { showSelectTrackSheet = false },
            tracks = availableTracks,
            currentTrackId = currentTrack?.id,
            onTrackSelected = { testSessionViewModel.selectTrack(it) },
            // title 走默认值「设置计时赛道」—— 钉死 Laps "动作"语义
        )
    }
}

/**
 * Laps START policy: a selected track is the only entry prerequisite.
 * GPS readiness remains observable on the home/live UI and gates timing samples, not session
 * creation or navigation.
 */
internal fun startLapSession(
    track: Track?,
    selectLapDebugMode: (String) -> Unit,
    navigateToLapLive: () -> Unit,
): Boolean {
    if (track == null) return false
    selectLapDebugMode(track.id)
    navigateToLapLive()
    return true
}

internal fun quickStartPreviousTrack(
    track: Track,
    selectTrack: (Track) -> Unit,
    selectLapDebugMode: (String) -> Unit,
    navigateToLapLive: () -> Unit,
) {
    selectTrack(track)
    selectLapDebugMode(track.id)
    navigateToLapLive()
}

@Composable
private fun CurrentTrackPanel(track: Track?, onClick: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onClick() },
        cutSize = 16.dp,
        cutCorners = cutCornersDiagonal,
        contentPadding = 20.dp,
    ) {
        Column {
            Text(
                text = "CURRENT TRACK",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = track?.name?.zh ?: "NO TRACK SELECTED",
                style = TrackTechTypography.RacingTitleMedium,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = track?.let { "%.3f km".format(it.lengthKm) } ?: "",
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            TrackThumbnail(
                assetPath = track?.thumbnailAssetPath,
                drawableResId = track?.thumbnailDrawableResId,
                points = track?.referencePath?.points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
        }
    }
}
