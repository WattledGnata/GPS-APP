package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.permission.PermissionRequestOutcome
import com.blazepush.core.domain.permission.RequiredCameraPermissions
import com.blazepush.feature.test.R
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.ui.tracktech.format.formatDate
import com.blazepush.feature.test.ui.tracktech.format.formatLapMs
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun LapsHomeScreen(
    navController: NavController,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testSessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val gpsViewModel = koinInject<GpsDataViewModel>()
    val gpsData by gpsViewModel.gpsData.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    val dataQuality by gpsViewModel.dataQuality.collectAsState()
    val lapGpsReadiness by testSessionViewModel.lapGpsReadiness.collectAsState()
    val availableTracks by testSessionViewModel.availableTracks.collectAsState()
    val currentTrack by testSessionViewModel.currentSelectedTrack.collectAsState()
    val recentSessions by testSessionViewModel.recentSessionsForCurrentTrack.collectAsState()
    var showSelectTrackSheet by remember { mutableStateOf(false) }
    // Session-scoped UI intent. It is consumed and reset before every successful START navigation.
    var recordThisSession by rememberSaveable { mutableStateOf(false) }

    val requestedCameraPermissions = remember {
        RequiredCameraPermissions.forSdk(Build.VERSION.SDK_INT)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        recordThisSession = recordingChoiceAfterPermissionResult(
            PermissionRequestOutcome.from(requestedCameraPermissions, result) == PermissionRequestOutcome.AllGranted,
        )
        if (!recordThisSession) {
            Toast.makeText(context, R.string.record_this_session_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
    val onRecordingChoiceChanged: (Boolean) -> Unit = { enabled ->
        when {
            !enabled -> recordThisSession = false
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> {
                recordThisSession = false
                Toast.makeText(context, R.string.record_this_session_camera_unavailable, Toast.LENGTH_LONG).show()
            }
            requestedCameraPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            } -> recordThisSession = true
            else -> cameraPermissionLauncher.launch(requestedCameraPermissions.toTypedArray())
        }
    }

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
    val lastSession = recentSessions.firstOrNull()
    val runAgainTrack = resolveRunAgainTrack(
        displayedSession = lastSession,
        currentTrack = currentTrack,
        availableTracks = availableTracks,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TrackTechColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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

        TrackTechStatusStrip(items = statusItems, onClick = { onTabSelected(TabIndex.Device) })
        CurrentTrackPanel(track = currentTrack, onClick = { showSelectTrackSheet = true })
        RecordingChoiceRow(selected = recordThisSession, onSelectedChange = onRecordingChoiceChanged)

        PrimaryActionPanel(
            title = stringResource(R.string.action_start_lap_session),
            subtitle = stringResource(R.string.action_begin_timing),
            leadingIcon = Icons.Filled.Flag,
            modifier = Modifier.padding(horizontal = 16.dp),
            onClick = {
                startLapSession(
                    track = testSessionViewModel.currentSelectedTrack.value,
                    recordThisSession = recordThisSession,
                    selectLapDebugMode = testSessionViewModel::selectLapDebugMode,
                    consumeRecordingChoice = { recordThisSession = false },
                    navigateToLapLive = { autoRecord ->
                        navController.navigate("lap_live?autoRecord=$autoRecord")
                    },
                )
            },
        )

        LastSessionPanel(
            session = lastSession,
            onRunAgain = if (runAgainTrack != null) {
                {
                    quickStartPreviousTrack(
                        track = runAgainTrack,
                        recordThisSession = recordThisSession,
                        selectTrack = testSessionViewModel::selectTrack,
                        selectLapDebugMode = testSessionViewModel::selectLapDebugMode,
                        consumeRecordingChoice = { recordThisSession = false },
                        navigateToLapLive = { autoRecord ->
                            navController.navigate("lap_live?autoRecord=$autoRecord")
                        },
                    )
                }
            } else {
                null
            },
        )

        if (isDebugCaptureAvailable()) {
            SecondaryActionPanel(
                title = stringResource(R.string.action_free_capture),
                subtitle = stringResource(R.string.action_free_capture_detail),
                leadingIcon = Icons.Filled.BugReport,
                accentColor = TrackTechColors.Purple,
                modifier = Modifier.padding(horizontal = 16.dp),
                onClick = {
                    recordThisSession = false
                    testSessionViewModel.startDebugCaptureMode()
                    navController.navigate("lap_live?autoRecord=false")
                },
            )
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showSelectTrackSheet) {
        SelectTrackBottomSheet(
            onDismiss = { showSelectTrackSheet = false },
            tracks = availableTracks,
            currentTrackId = currentTrack?.id,
            onTrackSelected = { testSessionViewModel.selectTrack(it) },
        )
    }
}

/** A selected track is the only START prerequisite; recording is a consumed, optional intent. */
internal fun startLapSession(
    track: Track?,
    recordThisSession: Boolean,
    selectLapDebugMode: (String) -> Unit,
    consumeRecordingChoice: () -> Unit,
    navigateToLapLive: (Boolean) -> Unit,
): Boolean {
    if (track == null) return false
    selectLapDebugMode(track.id)
    consumeRecordingChoice()
    navigateToLapLive(recordThisSession)
    return true
}

internal fun recordingChoiceAfterPermissionResult(allGranted: Boolean): Boolean = allGranted

internal fun resolveRunAgainTrack(
    displayedSession: TelemetrySession?,
    currentTrack: Track?,
    availableTracks: List<Track>,
): Track? {
    val sessionTrackId = displayedSession?.trackId ?: return null
    if (currentTrack?.id != sessionTrackId) return null
    return availableTracks.firstOrNull { it.id == sessionTrackId }
}

internal fun quickStartPreviousTrack(
    track: Track,
    recordThisSession: Boolean,
    selectTrack: (Track) -> Unit,
    selectLapDebugMode: (String) -> Unit,
    consumeRecordingChoice: () -> Unit,
    navigateToLapLive: (Boolean) -> Unit,
) {
    selectTrack(track)
    selectLapDebugMode(track.id)
    consumeRecordingChoice()
    navigateToLapLive(recordThisSession)
}

@Composable
private fun CurrentTrackPanel(track: Track?, onClick: () -> Unit) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onClick() },
        cutSize = 14.dp,
        cutCorners = cutCornersDiagonal,
        contentPadding = 14.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TrackThumbnail(
                assetPath = track?.thumbnailAssetPath,
                drawableResId = track?.thumbnailDrawableResId,
                points = track?.referencePath?.points,
                modifier = Modifier.width(112.dp).height(72.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.label_current_track),
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.Cyan,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = track?.name?.zh ?: stringResource(R.string.label_no_track_selected),
                    style = TrackTechTypography.RacingTitleMedium,
                    color = TrackTechColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = track?.let {
                        stringResource(R.string.track_summary, it.lengthKm, it.sectorGates.size + 1)
                    } ?: stringResource(R.string.track_select_prompt),
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.action_change_track),
                tint = TrackTechColors.Cyan,
            )
        }
    }
}

@Composable
private fun RecordingChoiceRow(selected: Boolean, onSelectedChange: (Boolean) -> Unit) {
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .toggleable(value = selected, role = Role.Switch, onValueChange = onSelectedChange),
        cutSize = 10.dp,
        cutCorners = cutCornersAntiDiagonal,
        fillColor = if (selected) TrackTechColors.PurpleAlpha20 else TrackTechColors.SurfaceDark,
        borderColor = if (selected) TrackTechColors.Purple else TrackTechColors.Border,
        contentPadding = 12.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = if (selected) TrackTechColors.Purple else TrackTechColors.TextMuted,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.record_this_session_title),
                    style = TrackTechTypography.UiTextBody,
                    color = TrackTechColors.TextPrimary,
                )
                Text(
                    text = stringResource(
                        if (selected) R.string.record_this_session_on_detail
                        else R.string.record_this_session_off_detail,
                    ),
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextMuted,
                )
            }
            Box(
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = if (selected) TrackTechColors.Purple else TrackTechColors.Border,
                        shape = RoundedCornerShape(2.dp),
                    )
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(
                    text = stringResource(if (selected) R.string.toggle_on else R.string.toggle_off),
                    style = TrackTechTypography.UiTextLabel,
                    color = if (selected) TrackTechColors.Purple else TrackTechColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun LastSessionPanel(session: TelemetrySession?, onRunAgain: (() -> Unit)?) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        cutSize = 10.dp,
        cutCorners = cutCornersTopRight,
        fillColor = TrackTechColors.SurfaceDark,
        borderColor = TrackTechColors.BorderAlpha60,
        contentPadding = 12.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.last_session_title),
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.Cyan,
                )
                Spacer(Modifier.weight(1f))
                if (onRunAgain != null) {
                    Text(
                        text = stringResource(R.string.action_run_again),
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.Purple,
                        modifier = Modifier.clickable(onClick = onRunAgain),
                    )
                }
            }
            if (session == null) {
                Text(
                    text = stringResource(R.string.last_session_empty),
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextMuted,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.last_session_summary,
                        session.lapCount,
                        session.bestLapMs?.let(::formatLapMs) ?: "--",
                    ),
                    style = TrackTechTypography.UiTextBody,
                    color = TrackTechColors.TextPrimary,
                )
                Text(
                    text = formatDate(session.startTs),
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextMuted,
                )
            }
        }
    }
}
