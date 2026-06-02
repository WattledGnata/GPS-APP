// @IgnoreFormatCheck
package com.blazepush.core.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * livetiming 服务端写接口（首个增量只做上报）。
 *
 * 返回 `Response<Unit>` 而非直接 body：调用方需读 `code()`（201/400/401/429）+
 * `headers()`（429 的 Retry-After）做错误分流（spec R4），不能只看成功值。
 */
interface LivetimingApi {
    @POST("api/v1/laps")
    suspend fun postLap(@Body dto: LapUploadDto): Response<Unit>
}
