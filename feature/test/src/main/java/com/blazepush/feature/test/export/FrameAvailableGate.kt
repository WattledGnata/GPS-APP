// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import android.graphics.SurfaceTexture
import com.blazepush.feature.test.FileLogger

/**
 * SurfaceTexture 新帧到达同步门（round video-export-burned-overlay · Round B · Decision 3）。
 *
 * 单线程 drain loop 用：decoder 把帧 render 到 SurfaceTexture 后，drain 线程 [awaitNewImage] 阻塞等
 * `onFrameAvailable` 回调（GL 线程外，主线程 Looper 触发）→ notify → drain 线程继续 `updateTexImage`。
 * 按 Grafika `SurfaceTextureManager` 范式（条件变量 + 超时防卡死）。
 *
 * @author CC
 * @description SurfaceTexture onFrameAvailable sync gate for export drain loop
 * @date 2026-05-31
 */
class FrameAvailableGate(surfaceTexture: SurfaceTexture) {

    private val tag = "ExportPipe"
    private val lock = Object()
    private var frameAvailable = false

    init {
        // onFrameAvailable 回调在创建 SurfaceTexture 的线程 Looper（无则主线程 Looper）。
        // drain 线程阻塞 awaitNewImage，回调线程置标志 + notify。
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(lock) {
                if (frameAvailable) {
                    FileLogger.e(tag, "frameAvailable already set (frame dropped?)")
                }
                frameAvailable = true
                lock.notifyAll()
            }
        }
    }

    /** 阻塞等新帧到达（超时 [TIMEOUT_MS] 防永久卡死）。 */
    fun awaitNewImage() {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (!frameAvailable) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    FileLogger.e(tag, "awaitNewImage timeout ${TIMEOUT_MS}ms")
                    break
                }
                try {
                    lock.wait(remaining)
                } catch (e: InterruptedException) {
                    throw RuntimeException("awaitNewImage interrupted", e)
                }
            }
            frameAvailable = false
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}
