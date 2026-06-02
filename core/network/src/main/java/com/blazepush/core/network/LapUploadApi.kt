// @IgnoreFormatCheck
package com.blazepush.core.network

/**
 * 上报门面契约（返回干净 [UploadResult]，不泄漏 retrofit 类型）。
 * 生产实现 [LivetimingUploader]；消费者（编排）依赖此接口，单测可 fake 返回 canned 结果
 * 而无需 retrofit/MockWebServer。
 */
interface LapUploadApi {
    suspend fun upload(dto: LapUploadDto): UploadResult
}
