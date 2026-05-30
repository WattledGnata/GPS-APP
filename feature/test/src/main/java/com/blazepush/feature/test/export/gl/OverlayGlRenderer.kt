// @IgnoreFormatCheck
package com.blazepush.feature.test.export.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.blazepush.feature.test.FileLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 离屏 GL 合成器（round video-export-burned-overlay · Round B）。
 *
 * 两层合成（按 Grafika `TextureRender` external OES 范式 + 加一层 2D overlay 纹理）：
 * 1. **视频帧层**：decoder 输出落 [GL_TEXTURE_EXTERNAL_OES] 纹理（[prepareSurfaceTexture] 创建的
 *    [SurfaceTexture]），用 `samplerExternalOES` shader + `SurfaceTexture.getTransformMatrix` 画满帧
 *    （零拷贝，不回读 CPU）。
 * 2. **overlay 层**：每帧把 [Bitmap]（CPU 用 `OverlayCanvasPainter` 画好）`glTexImage2D` 上传为普通 2D
 *    纹理，开 alpha blend 叠在视频帧上（overlay 透明背景，只露出仪表/面板图元）。
 *
 * EGL 上下文由 [OverlayEglCore] 提供；本类只负责"画"。GL error 经 [FileLogger] 记录。
 *
 * @author CC
 * @description offline GL compositor: video external texture + overlay 2D texture
 * @date 2026-05-31
 */
class OverlayGlRenderer {

    private val tag = "ExportGL"

    // 视频帧 external OES 纹理 + SurfaceTexture（给 decoder 输出）
    private var oesTextureId = 0
    private lateinit var surfaceTexture: SurfaceTexture

    // overlay 2D 纹理（每帧 glTexSubImage2D 复用同一 texture id）
    private var overlayTextureId = 0
    private var overlayAllocated = false

    // shader programs
    private var oesProgram = 0
    private var overlayProgram = 0

    // 全屏四边形顶点（NDC）+ 纹理坐标
    private val quadVertices: FloatBuffer = floatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        ),
    )
    // OES 纹理坐标（视频帧 transform matrix 会再变换它）
    private val oesTexCoords: FloatBuffer = floatBuffer(
        floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        ),
    )
    // overlay 2D 纹理坐标：bitmap 左上原点 → GL 纹理需上下翻转（v 取 1-v）
    private val overlayTexCoords: FloatBuffer = floatBuffer(
        floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        ),
    )

    private val stMatrix = FloatArray(16)

    /** 创建 GL 资源（在 [OverlayEglCore.makeCurrent] 之后调）。返回给 decoder 输出的 SurfaceTexture。 */
    fun prepareSurfaceTexture(): SurfaceTexture {
        // OES 视频帧 program 用带 transform 的 vertex shader；overlay 2D 用 passthrough（不乘 matrix）。
        oesProgram = buildProgram(OES_VERTEX_SHADER, OES_FRAGMENT_SHADER)
        overlayProgram = buildProgram(TWO_D_VERTEX_SHADER, TWO_D_FRAGMENT_SHADER)

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        oesTextureId = textures[0]
        overlayTextureId = textures[1]

        // external OES 纹理参数
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // overlay 2D 纹理参数
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        checkGlError("prepareSurfaceTexture textures")

        surfaceTexture = SurfaceTexture(oesTextureId)
        FileLogger.d(tag, "gl renderer prepared oesTex=$oesTextureId overlayTex=$overlayTextureId")
        return surfaceTexture
    }

    /** decoder 出新帧后调：把帧落到 OES 纹理 + 取 transform matrix。 */
    fun updateTexImage() {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
    }

    /**
     * 合成一帧到当前 EGLSurface（encoder input）：先画视频帧（OES），再叠 overlay Bitmap。
     *
     * @param viewportWidth  encoder 帧宽（px）
     * @param viewportHeight encoder 帧高（px）
     * @param overlayBitmap  本帧 overlay（透明背景 ARGB_8888，由 OverlayCanvasPainter 画好）
     */
    fun drawFrame(viewportWidth: Int, viewportHeight: Int, overlayBitmap: Bitmap) {
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // ── 1. 视频帧层（OES，套 SurfaceTexture transform matrix）──
        GLES20.glUseProgram(oesProgram)
        bindAttribs(oesProgram, quadVertices, oesTexCoords)
        val uStMatrix = GLES20.glGetUniformLocation(oesProgram, "uStMatrix")
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(oesProgram, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // ── 2. overlay 层（2D，alpha blend 叠上）──
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(overlayProgram)
        bindAttribs(overlayProgram, quadVertices, overlayTexCoords)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        uploadOverlay(overlayBitmap)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)

        checkGlError("drawFrame")
    }

    private fun uploadOverlay(bitmap: Bitmap) {
        // 首帧 texImage2D 分配，后续帧 texSubImage2D 复用（避免每帧重分配显存）
        if (!overlayAllocated) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            overlayAllocated = true
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
    }

    private fun bindAttribs(program: Int, vertices: FloatBuffer, texCoords: FloatBuffer) {
        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        vertices.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(aPosition)
        texCoords.position(0)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(aTexCoord)
    }

    fun release() {
        if (oesProgram != 0) GLES20.glDeleteProgram(oesProgram)
        if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram)
        val textures = intArrayOf(oesTextureId, overlayTextureId)
        GLES20.glDeleteTextures(2, textures, 0)
        if (this::surfaceTexture.isInitialized) surfaceTexture.release()
        FileLogger.d(tag, "gl renderer released")
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            FileLogger.e(tag, "program link failed: $log")
            GLES20.glDeleteProgram(program)
            throw RuntimeException("program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            FileLogger.e(tag, "shader compile failed (type=$type): $log")
            GLES20.glDeleteShader(shader)
            throw RuntimeException("shader compile failed: $log")
        }
        return shader
    }

    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            FileLogger.e(tag, "$op: glError 0x${Integer.toHexString(error)}")
        }
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(data)
        fb.position(0)
        return fb
    }

    companion object {
        // identity 矩阵备用（未用 transform 时）
        @Suppress("unused")
        private val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        // 视频帧 vertex shader：纹理坐标乘 SurfaceTexture transform matrix（处理裁剪/翻转/旋转）。
        private const val OES_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uStMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uStMatrix * aTexCoord).xy;
            }
        """

        // OES external 纹理（视频帧）
        private const val OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        // overlay vertex shader：passthrough（纹理坐标已含 bitmap 上下翻转，不乘任何 matrix）。
        private const val TWO_D_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """

        // 普通 2D 纹理（overlay，透明背景 alpha blend）
        private const val TWO_D_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
