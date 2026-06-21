// @IgnoreFormatCheck
package com.blazepush.core.network

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * spec『服务端 API 契约』客户端侧 + 『元数据随包上送』+ 『上传状态』失败/进度路径。
 *
 * 用 OkHttp Interceptor 短路返回 mock 响应（离线无 mockwebserver；com.sun HttpServer 在
 * android module test classpath 不可见），并在拦截器内驱动 request body 写入以捕获 multipart
 * + 触发进度回调。用 stdlib startCoroutine 跑 suspend（upload 内 OkHttp execute 同步阻塞、
 * 无真实挂起点，故无需 kotlinx-coroutines-test）。
 */
class DiagnosticLogUploaderTest {

    private fun <T> runSuspendBlocking(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return (outcome ?: error("suspend 未同步完成（出现真实挂起点）")).getOrThrow()
    }

    private class Cap {
        var path: String? = null
        var body: String = ""
    }

    /** mock client：拦截器内写出 request body（驱动 progress + 捕获 multipart）后短路返回响应。 */
    private fun client(code: Int, respBody: String, cap: Cap, throwIo: Boolean = false): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            if (throwIo) throw IOException("boom")
            cap.path = req.url.encodedPath
            val buf = Buffer()
            req.body?.writeTo(buf)
            cap.body = buf.readString(Charsets.ISO_8859_1)
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("mock")
                .body(respBody.toResponseBody(null))
                .build()
        }.build()

    private fun tmpZip(): File {
        val f = File.createTempFile("diag", ".zip")
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        return f
    }

    private fun meta(ticket: String? = null) = DiagnosticUploadMeta(
        deviceModel = "vivo V2405A",
        androidId = "abc123",
        versionName = "1.0.1",
        versionCode = "2",
        capturedAtMs = 123L,
        ticket = ticket,
    )

    private fun uploader(client: OkHttpClient) =
        DiagnosticLogUploader(baseUrl = "http://example/", token = "test-token", client = client)

    @Test fun upload_success_parsesLogIdAndSendsMultipart() {
        val cap = Cap()
        val r = runSuspendBlocking { uploader(client(200, """{"logId":"LOG-42"}""", cap)).upload(tmpZip(), meta()) {} }
        assertTrue(r is DiagnosticUploadResult.Success)
        assertEquals("LOG-42", (r as DiagnosticUploadResult.Success).logId)
        assertEquals("/api/v1/logs", cap.path)
        assertTrue(cap.body.contains("name=\"file\""))
        assertTrue(cap.body.contains("name=\"deviceModel\""))
        assertTrue(cap.body.contains("vivo V2405A"))
        assertTrue(cap.body.contains("name=\"androidId\""))
        assertTrue(cap.body.contains("name=\"versionName\""))
        assertTrue(cap.body.contains("name=\"versionCode\""))
        assertTrue(cap.body.contains("name=\"capturedAt\""))
    }

    @Test fun upload_emptyTicket_omitsTicketField() {
        val cap = Cap()
        runSuspendBlocking { uploader(client(200, """{"logId":"X"}""", cap)).upload(tmpZip(), meta(ticket = null)) {} }
        assertFalse("空工单号不应出现 ticket 字段", cap.body.contains("name=\"ticket\""))
    }

    @Test fun upload_withTicket_includesTicketField() {
        val cap = Cap()
        runSuspendBlocking { uploader(client(200, """{"logId":"X"}""", cap)).upload(tmpZip(), meta(ticket = "BUG-123")) {} }
        assertTrue(cap.body.contains("name=\"ticket\""))
        assertTrue(cap.body.contains("BUG-123"))
    }

    @Test fun upload_http400_returnsHttpError() {
        val cap = Cap()
        val r = runSuspendBlocking { uploader(client(400, "bad request", cap)).upload(tmpZip(), meta()) {} }
        assertTrue(r is DiagnosticUploadResult.HttpError)
        assertEquals(400, (r as DiagnosticUploadResult.HttpError).code)
    }

    @Test fun upload_progressReachesOne() {
        val cap = Cap()
        var last = 0f
        runSuspendBlocking { uploader(client(200, """{"logId":"X"}""", cap)).upload(tmpZip(), meta()) { last = it } }
        assertEquals(1f, last, 0.001f)
    }

    @Test fun upload_networkError_onIoException() {
        val cap = Cap()
        val r = runSuspendBlocking { uploader(client(200, "", cap, throwIo = true)).upload(tmpZip(), meta()) {} }
        assertTrue(r is DiagnosticUploadResult.NetworkError)
    }
}
