// @IgnoreFormatCheck
package com.blazepush.feature.test.export.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import com.blazepush.feature.test.FileLogger

/**
 * EGL14 上下文 + window surface 管理（round video-export-burned-overlay · Round B）。
 *
 * 按 Grafika `EglCore` + `WindowSurface` 成熟范式抄写（业界验证），不从零造轮子。
 * 单线程独占：导出 drain loop 在一个后台线程内 [makeCurrent] 一次，全程不切换（Decision 3）。
 *
 * 用途：把 encoder 的 input [Surface]（`MediaCodec.createInputSurface()`）作为 GL 渲染目标——
 * GLES20 把"视频帧 external 纹理 + overlay Bitmap 纹理"合成画到此 EGLSurface，[swapBuffers] 推到
 * encoder Surface（带 PTS，[setPresentationTime]）。
 *
 * @author CC
 * @description EGL14 context + window surface for offline overlay burn-in
 * @date 2026-05-31
 */
class OverlayEglCore {

    private val tag = "ExportGL"
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw RuntimeException("eglInitialize failed")
        }
        FileLogger.d(tag, "egl initialize v=${version[0]}.${version[1]}")

        // 配置：RGBA8888 + EGL_RECORDABLE_ANDROID（可被 MediaCodec encoder Surface 录制，关键兼容点）
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw RuntimeException("eglChooseConfig failed")
        }
        if (numConfigs[0] <= 0 || configs[0] == null) {
            throw RuntimeException("no matching EGL config (recordable RGBA8888)")
        }
        eglConfig = configs[0]

        // GLES2 context
        val ctxAttrib = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE,
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrib, 0,
        )
        checkEglError("eglCreateContext")
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext returned EGL_NO_CONTEXT")
        }
        FileLogger.d(tag, "egl context created")
    }

    /**
     * 以 encoder input [surface] 为目标创建 window EGLSurface。
     * 一个 OverlayEglCore 只配一个 window surface（导出单目标）。
     */
    fun createWindowSurface(surface: Surface) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("window surface already created")
        }
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface, surfaceAttribs, 0,
        )
        checkEglError("eglCreateWindowSurface")
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface returned EGL_NO_SURFACE")
        }
        FileLogger.d(tag, "window surface created for encoder input")
    }

    /** 把当前线程 + window surface 绑成 GL 渲染上下文（单线程一次即可）。 */
    fun makeCurrent() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("makeCurrent before createWindowSurface")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    /** 把渲染好的帧推到 encoder input surface。 */
    fun swapBuffers(): Boolean {
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /** 设置当前帧的 presentation time（纳秒），encoder 据此给输出帧打 PTS。 */
    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    /** 释放所有 EGL 资源（drain loop finally 调）。 */
    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        eglConfig = null
        FileLogger.d(tag, "egl released")
    }

    private fun checkEglError(op: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            FileLogger.e(tag, "$op: EGL error 0x${Integer.toHexString(error)}")
            throw RuntimeException("$op: EGL error 0x${Integer.toHexString(error)}")
        }
    }

    companion object {
        // EGL_RECORDABLE_ANDROID 常量（android.opengl 未直接暴露，硬编码官方值 0x3142）
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
