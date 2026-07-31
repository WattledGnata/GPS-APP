// @IgnoreFormatCheck
package com.blazepush.core.network

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 诊断上传元数据（add-diagnostic-log-upload, design Decision 7）。纯数据，跨模块传递。
 */
data class DiagnosticUploadMeta(
    val deviceModel: String,
    val androidId: String,
    val versionName: String,
    val versionCode: String,
    val capturedAtMs: Long,
    val ticket: String?,
)

/**
 * 诊断上传结果门面（返回干净结果，不泄漏 OkHttp 类型给上层；对齐 [UploadResult] 风格）。
 */
sealed interface DiagnosticUploadResult {
    data class Success(val logId: String) : DiagnosticUploadResult
    data class HttpError(val code: Int, val body: String?) : DiagnosticUploadResult
    data class NetworkError(val cause: Throwable?) : DiagnosticUploadResult
}

/** 上传门面接口（编排层依赖此接口，单测可 fake）。 */
interface DiagnosticLogUploadApi {
    suspend fun upload(zip: File, meta: DiagnosticUploadMeta, onProgress: (Float) -> Unit): DiagnosticUploadResult
}

/**
 * 诊断 zip 上传（design Decision 5/9）：OkHttp multipart `POST <baseUrl>/api/v1/logs`，
 * 复用 livetiming baseUrl/token；`file`(zip) + meta 表单字段；带写入进度回调；
 * 成功解析响应 JSON `logId`。LOGS_PATH 用代码常量（端点固定，无需 BuildConfig 注入）。
 */
class DiagnosticLogUploader internal constructor(
    private val baseUrl: String = BuildConfig.LIVETIMING_BASE_URL,
    token: String = BuildConfig.LIVETIMING_TOKEN,
    private val client: OkHttpClient = defaultClient(token),
) : DiagnosticLogUploadApi {

    override suspend fun upload(
        zip: File,
        meta: DiagnosticUploadMeta,
        onProgress: (Float) -> Unit,
    ): DiagnosticUploadResult {
        return try {
            val fileBody = ProgressRequestBody(
                zip.asRequestBody("application/zip".toMediaType()),
                onProgress,
            )
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", zip.name, fileBody)
                .addFormDataPart("deviceModel", meta.deviceModel)
                .addFormDataPart("androidId", meta.androidId)
                .addFormDataPart("versionName", meta.versionName)
                .addFormDataPart("versionCode", meta.versionCode)
                .addFormDataPart("capturedAt", meta.capturedAtMs.toString())
                .apply {
                    meta.ticket?.takeIf { it.isNotBlank() }?.let { addFormDataPart("ticket", it) }
                }
                .build()
            val url = baseUrl.trimEnd('/') + LOGS_PATH
            val req = Request.Builder().url(url).post(multipart).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) {
                    return DiagnosticUploadResult.HttpError(resp.code, body)
                }
                val logId = parseLogId(body)
                if (logId != null) {
                    DiagnosticUploadResult.Success(logId)
                } else {
                    DiagnosticUploadResult.HttpError(resp.code, body)
                }
            }
        } catch (t: IOException) {
            DiagnosticUploadResult.NetworkError(t)
        }
    }

    private fun parseLogId(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            JsonParser.parseString(json).asJsonObject.get("logId")?.asString
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val LOGS_PATH = "/api/v1/logs"

        /** 生产构造不向消费者模块泄漏 OkHttp 类型。 */
        fun create(): DiagnosticLogUploadApi = DiagnosticLogUploader()

        internal fun defaultClient(token: String): OkHttpClient =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val authed = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    chain.proceed(authed)
                }
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS) // design：大 zip 弱网放宽
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}

/** 包装 RequestBody 报告写入进度（0..1）。 */
private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        val counting = object : ForwardingSink(sink) {
            private var written = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}
