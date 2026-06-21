// @IgnoreFormatCheck
package com.blazepush.feature.test.diagnostic

import com.blazepush.core.network.DiagnosticLogUploadApi
import com.blazepush.core.network.DiagnosticUploadMeta
import com.blazepush.core.network.DiagnosticUploadResult
import com.blazepush.feature.test.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 诊断上传 UI 订阅的状态（design Decision 8）。
 */
sealed interface DiagnosticUploadState {
    object Idle : DiagnosticUploadState
    object Packing : DiagnosticUploadState
    data class Uploading(val progress: Float) : DiagnosticUploadState
    data class Success(val logId: String) : DiagnosticUploadState
    data class Failed(val reason: String) : DiagnosticUploadState
}

/**
 * 诊断上传编排状态机（design Decision 8，复用 livetiming/LapUploadOrchestrator 风格）。
 *
 * 串 [DiagnosticPackager] 打包 → [DiagnosticLogUploadApi] 上传，状态经 [state] StateFlow 暴露。
 * 隐私确认由 UI 在调用 [start] 之前完成（本类不弹窗）。失败归类为可区分原因。
 * 上传无论成败都删临时 zip（design Decision 2）。
 */
class DiagnosticUploadOrchestrator(
    private val filesDir: File,
    private val databaseDir: File,
    private val cacheDir: File,
    private val uploader: DiagnosticLogUploadApi,
    private val metaProvider: (ticket: String?) -> DiagnosticUploadMeta,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow<DiagnosticUploadState>(DiagnosticUploadState.Idle)
    val state: StateFlow<DiagnosticUploadState> = _state.asStateFlow()

    suspend fun start(ticket: String?) {
        val zip = try {
            _state.value = DiagnosticUploadState.Packing
            DiagnosticPackager.pack(filesDir, databaseDir, cacheDir, nowMs())
        } catch (t: Throwable) {
            FileLogger.e("DiagUpload", "打包失败", t)
            _state.value = DiagnosticUploadState.Failed("打包失败：${t.message}")
            return
        }
        try {
            _state.value = DiagnosticUploadState.Uploading(0f)
            val meta = metaProvider(ticket)
            val result = uploader.upload(zip, meta) { p ->
                _state.value = DiagnosticUploadState.Uploading(p)
            }
            _state.value = when (result) {
                is DiagnosticUploadResult.Success -> {
                    FileLogger.d("DiagUpload", "上传成功 logId=${result.logId}")
                    DiagnosticUploadState.Success(result.logId)
                }
                is DiagnosticUploadResult.HttpError -> {
                    FileLogger.e("DiagUpload", "上传服务端拒绝 ${result.code} body=${result.body}")
                    DiagnosticUploadState.Failed("服务端拒绝（${result.code}）")
                }
                is DiagnosticUploadResult.NetworkError -> {
                    FileLogger.e("DiagUpload", "上传网络失败", result.cause)
                    DiagnosticUploadState.Failed("网络失败：${result.cause?.message ?: "未知"}")
                }
            }
        } finally {
            zip.delete() // 临时 zip 上传后清理（design Decision 2）
        }
    }
}
