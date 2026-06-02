// @IgnoreFormatCheck
package com.blazepush.core.network

import com.google.gson.annotations.SerializedName

/**
 * `POST /api/v1/laps` 请求体（livetiming-server/docs/api/client-integration.md §3.2）。
 *
 * 字段名与服务端契约**完全一致**（Gson SerializedName 锁死，避免混淆/重命名漂移）。
 * 这是**网络 DTO**，独立于 domain 的 `LapTelemetry`（in-memory）与 Room 的
 * `PendingLapUploadEntity`（持久化）——三类不混并（design Decision / 盲点 #5）。
 *
 * `clientLapId` 是幂等键：调用方 MUST 传按圈稳定、重试复用的值（见 LapUploadMapper）。
 */
data class LapUploadDto(
    @SerializedName("trackId") val trackId: String,
    @SerializedName("driver") val driver: String,
    @SerializedName("carModel") val carModel: String? = null,
    @SerializedName("lapNo") val lapNo: Int,
    @SerializedName("lapTimeMs") val lapTimeMs: Long,
    @SerializedName("sectorsMs") val sectorsMs: List<Long>? = null,
    @SerializedName("clientLapId") val clientLapId: String,
    @SerializedName("lappedAt") val lappedAt: String? = null,
)
