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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.model.track.Track

/**
 * 赛道选择底部弹窗（无状态：调用方负责 state 与 callback）。
 *
 * change `enhance-track-presentation` §8 / Req 8。
 *
 * **职责边界**：本面板是纯展示组件，不持有任何 ViewModel 状态。Laps tab
 * 与未来 Records tab filter 各自传入自己的 [tracks] / [currentTrackId] /
 * [onTrackSelected]，**禁止**复用同一个底层 state，避免一个 tab 切换污染
 * 另一个 tab。
 *
 * 当前 Laps 调用语义已锁死：标题"设置计时赛道"+ 紫色 accent + onTrackSelected
 * 调 [TestSessionViewModel.selectTrack]。Records filter 弹窗将来用相同
 * Composable 传不同标题（"按赛道查看历史"等）+ 不同 callback。
 *
 * - 当前项点击 no-op（不重复触发切换）
 * - 选中非当前项 → 调 [onTrackSelected] 并自动 dismiss
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectTrackBottomSheet(
    onDismiss: () -> Unit,
    tracks: List<Track>,
    currentTrackId: String?,
    onTrackSelected: (Track) -> Unit,
    title: String = "设置计时赛道",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TrackTechColors.Background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderRow(title = title, onClose = onDismiss)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = tracks, key = { it.id }) { track ->
                    TrackSelectionRow(
                        track = track,
                        isCurrent = track.id == currentTrackId,
                        onClick = {
                            if (track.id != currentTrackId) {
                                onTrackSelected(track)
                                onDismiss()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TrackTechTypography.RacingTitleLarge,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        CloseButton(onClose = onClose)
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    val shape = CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersDiagonal)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(TrackTechColors.SurfaceDark, shape)
            .border(1.dp, TrackTechColors.Cyan, shape)
            .clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Close",
            tint = TrackTechColors.Cyan,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun TrackSelectionRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val itemShape = CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersDiagonal)
    val borderColor = if (isCurrent) TrackTechColors.Purple else TrackTechColors.BorderAlpha60

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(itemShape)
            .border(if (isCurrent) 1.dp else 1.dp, borderColor, itemShape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackThumbnail(
            assetPath = track.thumbnailAssetPath,
            modifier = Modifier
                .size(width = 96.dp, height = 64.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = track.name.zh,
                style = TrackTechTypography.RacingTitleMedium,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "%.3f km".format(track.lengthKm),
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (isCurrent) {
            Text(
                text = "当前",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Green,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
