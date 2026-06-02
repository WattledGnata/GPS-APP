// @IgnoreFormatCheck
package com.blazepush.core.network

/**
 * core/network 对外门面：把 Retrofit 调用收敛成 [UploadResult]，消费者（feature/test 编排）
 * 无需依赖 retrofit2 类型（模块隔离）。异常统一收敛为 [UploadResult.NetworkError]。
 */
class LivetimingUploader(private val api: LivetimingApi) : LapUploadApi {
    override suspend fun upload(dto: LapUploadDto): UploadResult =
        try {
            val resp = api.postLap(dto)
            if (resp.isSuccessful) {
                UploadResult.Success
            } else {
                UploadResult.HttpError(resp.code())
            }
        } catch (e: Exception) {
            UploadResult.NetworkError(e)
        }

    companion object {
        /** 生产构造：内置 token/baseUrl 走 BuildConfig。 */
        fun create(): LivetimingUploader = LivetimingUploader(LivetimingClient.create())
    }
}
