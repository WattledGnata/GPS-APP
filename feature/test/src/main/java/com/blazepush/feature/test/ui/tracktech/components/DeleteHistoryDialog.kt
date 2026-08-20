package com.blazepush.feature.test.ui.tracktech.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import com.blazepush.feature.test.ui.tracktech.TrackTechTypography
import com.blazepush.feature.test.R

/**
 * Records 列表行长按删除候选；统一 PERFORMANCE 测试记录与 LAPS 圈速 session 两侧入口
 * （add-history-deletion round）。
 *
 * - [titleHint] 用于 [DeleteHistoryDialog] 副标显示该记录的简短描述
 *   （PERFORMANCE：`type · value · time`；LAPS：`date · 圈数 · 最佳`）
 * - [TestRecord.id] 走 [com.blazepush.feature.test.viewmodel.TestSessionViewModel.deleteTestRecord]
 * - [LapSession.id] 走 [com.blazepush.feature.test.viewmodel.TestSessionViewModel.deleteLapSession]
 *
 * @author CC
 * @description sealed candidate for delete confirmation dialog
 * @date 2026-05-02
 */
sealed interface DeleteCandidate {
    val titleHint: String

    data class TestRecord(val id: String, override val titleHint: String) : DeleteCandidate

    data class LapSession(val id: String, override val titleHint: String) : DeleteCandidate
}

/**
 * 删除确认 AlertDialog（add-history-deletion round）：
 *
 * - 风格：baseline Material3 [AlertDialog]，参考 `LapLiveScreen.EndConfirmationDialog`，
 *   不引入 V2 cut-corner 自定义组件
 * - 文案：title `"删除记录?"`，副标显示 [DeleteCandidate.titleHint]
 * - 按钮：`删除`使用 [TrackTechColors.Red]（与 EndConfirmationDialog 红色按钮同款），
 *   `取消`使用 [TrackTechColors.TextSecondary] 灰色字
 *
 * 调用方（[com.blazepush.feature.test.ui.tracktech.RecordsHomeScreen]）通过
 * `remember { mutableStateOf<DeleteCandidate?>(null) }` 控制是否显示。
 *
 * @author CC
 * @description Material3 alert dialog for confirming deletion of records / sessions
 * @date 2026-05-02
 */
@Composable
fun DeleteHistoryDialog(
    candidate: DeleteCandidate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.confirm_delete_record),
                style = TrackTechTypography.RacingTitleSmall,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Text(
                text = candidate.titleHint,
                style = TrackTechTypography.UiTextBody,
                color = TrackTechColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = TrackTechColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = TrackTechColors.Red,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}
