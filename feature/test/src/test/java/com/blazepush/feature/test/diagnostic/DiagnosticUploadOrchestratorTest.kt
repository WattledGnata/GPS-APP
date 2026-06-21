// @IgnoreFormatCheck
package com.blazepush.feature.test.diagnostic

import com.blazepush.core.network.DiagnosticLogUploadApi
import com.blazepush.core.network.DiagnosticUploadMeta
import com.blazepush.core.network.DiagnosticUploadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * spec『上传状态与结果反馈』：成功→Success(logId)、HTTP/网络失败→Failed、临时 zip 上传后删除。
 * fake uploader + 临时目录；stdlib startCoroutine 跑 suspend（fake 无真实挂起点）。
 */
class DiagnosticUploadOrchestratorTest {

    private fun <T> runSuspendBlocking(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return (outcome ?: error("suspend 未同步完成")).getOrThrow()
    }

    private fun dirs(): Triple<File, File, File> {
        val files = createTempDir(prefix = "diagFiles")
        val db = createTempDir(prefix = "diagDb")
        val cache = createTempDir(prefix = "diagCache")
        File(files, "debug_log.txt").writeText("log")
        return Triple(files, db, cache)
    }

    private val meta = DiagnosticUploadMeta("m", "a", "1.0", "1", 0L, null)

    private fun orch(files: File, db: File, cache: File, up: DiagnosticLogUploadApi) =
        DiagnosticUploadOrchestrator(files, db, cache, up, { meta }, nowMs = { 7L })

    @Test fun start_success_endsAtSuccessWithLogId() {
        val (files, db, cache) = dirs()
        val up = object : DiagnosticLogUploadApi {
            override suspend fun upload(zip: File, meta: DiagnosticUploadMeta, onProgress: (Float) -> Unit): DiagnosticUploadResult {
                onProgress(0.5f); onProgress(1f)
                return DiagnosticUploadResult.Success("LOG-9")
            }
        }
        val o = orch(files, db, cache, up)
        runSuspendBlocking { o.start(null) }
        assertTrue(o.state.value is DiagnosticUploadState.Success)
        assertEquals("LOG-9", (o.state.value as DiagnosticUploadState.Success).logId)
    }

    @Test fun start_httpError_endsAtFailed() {
        val (files, db, cache) = dirs()
        val up = object : DiagnosticLogUploadApi {
            override suspend fun upload(zip: File, meta: DiagnosticUploadMeta, onProgress: (Float) -> Unit) =
                DiagnosticUploadResult.HttpError(500, "err")
        }
        val o = orch(files, db, cache, up)
        runSuspendBlocking { o.start(null) }
        assertTrue(o.state.value is DiagnosticUploadState.Failed)
    }

    @Test fun start_networkError_endsAtFailed() {
        val (files, db, cache) = dirs()
        val up = object : DiagnosticLogUploadApi {
            override suspend fun upload(zip: File, meta: DiagnosticUploadMeta, onProgress: (Float) -> Unit) =
                DiagnosticUploadResult.NetworkError(java.io.IOException("x"))
        }
        val o = orch(files, db, cache, up)
        runSuspendBlocking { o.start(null) }
        assertTrue(o.state.value is DiagnosticUploadState.Failed)
    }

    @Test fun start_deletesTempZipAfterUpload() {
        val (files, db, cache) = dirs()
        var captured: File? = null
        val up = object : DiagnosticLogUploadApi {
            override suspend fun upload(zip: File, meta: DiagnosticUploadMeta, onProgress: (Float) -> Unit): DiagnosticUploadResult {
                captured = zip
                return DiagnosticUploadResult.Success("X")
            }
        }
        val o = orch(files, db, cache, up)
        runSuspendBlocking { o.start(null) }
        assertNotNull(captured)
        assertFalse("上传后临时 zip 应删除", captured!!.exists())
    }
}
