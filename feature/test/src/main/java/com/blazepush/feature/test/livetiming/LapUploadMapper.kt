// @IgnoreFormatCheck
package com.blazepush.feature.test.livetiming

import com.blazepush.core.network.LapUploadDto
import com.blazepush.feature.test.model.laptiming.LapRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `LapRecord`(in-memory 出圈结果)→ `LapUploadDto`(网络 DTO)纯映射。
 *
 * 三类不混并（盲点 #5）：domain/in-memory `LapRecord` ↔ 网络 `LapUploadDto` ↔ Room
 * `PendingLapUploadEntity` 各自独立，本类只做 record→dto 一个方向。
 *
 * **clientLapId 命门（Decision 2）**：[clientLapId] 按圈稳定派生（`sessionId:lapIndex`），
 * 同圈首传与所有重试**复用同一个**；上报/重试路径 MUST NOT 在请求构造处 `UUID.randomUUID()`。
 */
object LapUploadMapper {
    /** 幂等键：按圈稳定，生成一次复用。禁请求处随机生成。 */
    fun clientLapId(sessionId: String, lapIndex: Int): String = "$sessionId:$lapIndex"

    /** epoch ms → RFC3339（带本地时区偏移，如 +08:00），doc §3.2 lappedAt 格式。 */
    fun toRfc3339(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(epochMs))

    /**
     * 组装上报体。`lapNo`=lapIndex+1（0-based→1-based）；`sectorsMs` 取 `LapRecord.sectorTimes`
     * （已是各段时长，无需算 boundary 差），空则不传；`carModel` 不传（App 无车型概念）。
     */
    fun buildDto(record: LapRecord, driver: String): LapUploadDto = LapUploadDto(
        trackId = record.trackId,
        driver = driver,
        carModel = null,
        lapNo = record.lapIndex + 1,
        lapTimeMs = record.durationMillis,
        sectorsMs = record.sectorTimes.takeIf { it.isNotEmpty() },
        clientLapId = clientLapId(record.sessionId, record.lapIndex),
        lappedAt = toRfc3339(record.finishedAtMillis),
    )
}
