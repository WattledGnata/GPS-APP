// @IgnoreFormatCheck
package com.blazepush.core.network

/**
 * 上报结果（core/network 对外的干净契约，**不泄漏** retrofit2.Response 给消费者模块）。
 *
 * - [Success]：201（含幂等重复，服务端已去重）
 * - [HttpError]：非 2xx（400 校验失败 / 401 token / 429 限流 / 5xx）；调用方按 code 分流
 * - [NetworkError]：IO 异常 / 无网 / 超时等（可重试）
 */
sealed interface UploadResult {
    object Success : UploadResult
    data class HttpError(val code: Int) : UploadResult
    data class NetworkError(val cause: Throwable?) : UploadResult
}
