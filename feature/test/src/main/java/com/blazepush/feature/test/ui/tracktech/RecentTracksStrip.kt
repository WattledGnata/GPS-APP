package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blazepush.feature.test.model.track.Track

/**
 * RECENT TRACKS 横滑卡片组件。change `replace-nearby-tracks-with-recent-strip` §3.1。
 *
 * stateless 组件：调用方传 recent ID 列表 + 全部 tracks 字典 + 当前选中 ID + 两个 callback。
 * 内部按 [recentTrackIds] 顺序解析为 Track（自动 filter 掉 stale ID）+ 末尾追加 VIEW ALL 卡片。
 */
@Composable
fun RecentTracksStrip(
    recentTrackIds: List<String>,
    availableTracks: List<Track>,
    currentTrackId: String?,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolved = recentTrackIds.mapNotNull { id ->
        availableTracks.firstOrNull { it.id == id }
    }
    // round 反馈修订：RECENT 空时回退显示所有 availableTracks，避免首次启动横滑区
    // 视觉空旷。RECENT 一旦有历史则按时间倒序显示。VIEW ALL 入口在 section header
    // 右侧 IconButton（不在 strip 内部）。
    val displayTracks = if (resolved.isEmpty()) availableTracks else resolved

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = displayTracks, key = { it.id }) { track ->
            RecentTrackCard(
                track = track,
                isCurrent = track.id == currentTrackId,
                onClick = { if (track.id != currentTrackId) onTrackClick(track) },
            )
        }
    }
}

@Composable
private fun RecentTrackCard(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val shape = CutCornerPanelShape(cutSize = 8.dp, cutCorners = cutCornersDiagonal)
    val borderColor = if (isCurrent) TrackTechColors.Purple else TrackTechColors.BorderAlpha60
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(shape)
            .background(TrackTechColors.Surface, shape)
            .border(1.dp, borderColor, shape)
            .clickable { onClick() }
            .padding(8.dp),
    ) {
        TrackThumbnail(
            assetPath = track.thumbnailAssetPath,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        )
        Spacer(Modifier.height(8.dp))
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
}

// ViewAllCard removed by round 反馈修订：VIEW ALL 改放 LapsHomeScreen
// section header 右侧 IconButton（GridView icon、cyan tint），不再做横滑序列末尾卡片。
