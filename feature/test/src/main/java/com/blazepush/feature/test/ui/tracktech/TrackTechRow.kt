package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Device Home 的 GPS Details / Diagnostics / Settings 入口行 + BLE Scan device 行复用。
 *
 * round add-history-deletion：追加可选 [onLongClick]（默认 null 行为等价旧 clickable，零改动调用方）。
 * 长按≥500ms 触发 [onLongClick]，用于 Records → PERFORMANCE / LAPS 列表行长按删除入口。
 *
 * @author CC
 * @description 通用入口行（icon + title + subtitle + chevron），支持可选长按
 * @date 2026-05-01
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackTechRow(
    leadingIcon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TrackTechColors.Surface, shape)
            .border(1.dp, TrackTechColors.BorderAlpha60, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = TrackTechColors.Purple,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = title,
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = subtitle,
                            style = TrackTechTypography.UiTextSmall,
                            color = TrackTechColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TrackTechColors.TextSecondary,
            )
        }
    }
}
