// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.export.gl.OverlayEglCore
import com.blazepush.feature.test.export.gl.OverlayGlRenderer
import com.blazepush.feature.test.overlay.VideoOverlayStyle
import com.blazepush.feature.test.recording.VideoTelemetrySync
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离屏烧录导出管线（round video-export-burned-overlay · Round B · Decision 2/3/5/6）。
 *
 * 单线程同步 drain loop（Grafika `DecodeEditEncode` 范式）：
 * ```
 * MediaExtractor(源视频轨) → MediaCodec decoder → SurfaceTexture(GL external) →
 *   GLES20 离屏(画视频帧 + 叠 overlay Bitmap) → encoder input Surface → MediaCodec encoder →
 *   MediaMuxer(视频重编码轨 + 音轨直通 copy)
 * ```
 *
 * 按圈裁剪（[VideoExportClip]）：seekTo(起点, PREVIOUS_SYNC) + 丢弃早于起点的帧；PTS > 终点即 EOS。
 * 音轨直通：另一 MediaExtractor 读音频 sample 直接 writeSampleData（不解不编），PTS 减裁剪起点对齐。
 *
 * **不创建 MediaMuxer 目标**：muxer 由调用方（[VideoExportMediaStoreWriter] 经 MediaStore fd）构造并传入，
 * pipeline 只负责 addTrack/start/writeSampleData/stop（封装层与 MediaStore 解耦）。
 *
 * 所有阶段 [FileLogger] 埋点 + `runCatching` 由调用方包裹（GL/codec 失败删半成品不崩 app）。
 *
 * @author CC
 * @description offline GL burn-in export drain loop (decoder→GL→encoder→muxer)
 * @date 2026-05-31
 */
internal class VideoExportPipeline(
    private val sourceVideoPath: String,
    private val ctx: LapPlaybackLoader.LapPlaybackContext,
    private val clip: VideoExportClip.ClipRange,
    private val overlayStyle: VideoOverlayStyle,
) {
    private val tag = "ExportPipe"

    /** 取消标志（Service 取消时置 true，drain loop 检查后中断）。 */
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        FileLogger.d(tag, "cancel requested")
    }

    /**
     * 执行导出 drain loop，把视频轨重编码（烧 overlay）+ 音轨直通写入 [muxer]。
     *
     * @param muxer       已用 MediaStore fd 构造好的 MediaMuxer（pipeline 负责 addTrack/start/stop）
     * @param onProgress  进度回调（已处理帧数, 估算总帧数）
     * @return 是否完成（false = 被取消）
     */
    fun run(
        muxer: MediaMuxer,
        onProgress: (processedFrames: Int, totalFrames: Int) -> Unit,
    ): Boolean {
        val startUs = clip.startPositionMs * 1000L
        val endUs = clip.endPositionMs * 1000L

        // ── 源视频轨 ──
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(sourceVideoPath)
        val videoTrackIndex = selectTrack(videoExtractor, "video/")
        require(videoTrackIndex >= 0) { "no video track in $sourceVideoPath" }
        videoExtractor.selectTrack(videoTrackIndex)
        val srcFormat = videoExtractor.getTrackFormat(videoTrackIndex)

        val srcWidth = srcFormat.getInteger(MediaFormat.KEY_WIDTH)
        val srcHeight = srcFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = if (srcFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            srcFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        } else {
            VideoExportConfig.DEFAULT_FRAME_RATE
        }
        val sourceRotation = if (srcFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            srcFormat.getInteger(MediaFormat.KEY_ROTATION)
        } else {
            0
        }
        val estTotalFrames = ((clip.durationMs / 1000.0) * frameRate).toInt().coerceAtLeast(1)
        FileLogger.d(
            tag,
            "src ${srcWidth}x$srcHeight fps=$frameRate rotation=$sourceRotation " +
                "clip=[${clip.startPositionMs},${clip.endPositionMs}]ms estFrames=$estTotalFrames " +
                "videoStart=${ctx.videoStartedAtWallClock}",
        )

        // ── encoder（COLOR_FormatSurface + createInputSurface）──
        // 旋转处理（4.3）：透传 source rotation 给 muxer（setOrientationHint），编码帧不做几何旋正
        //   → GL 用 SurfaceTexture transform matrix 已处理解码侧翻转；播放器据 rotation hint 旋正显示。
        val encoderFormat = MediaFormat.createVideoFormat(VideoExportConfig.VIDEO_MIME, srcWidth, srcHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VideoExportConfig.computeBitrate(srcWidth, srcHeight, frameRate))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoExportConfig.I_FRAME_INTERVAL_SEC)
        }
        val encoder = MediaCodec.createEncoderByType(VideoExportConfig.VIDEO_MIME)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface: Surface = encoder.createInputSurface()
        FileLogger.d(tag, "encoder ${encoder.name} configured (COLOR_FormatSurface)")

        // ── EGL + GL 合成器 ──
        val eglCore = OverlayEglCore()
        eglCore.createWindowSurface(encoderInputSurface)
        eglCore.makeCurrent()
        val glRenderer = OverlayGlRenderer()
        val decoderSurfaceTexture = glRenderer.prepareSurfaceTexture()
        val frameAvailable = FrameAvailableGate(decoderSurfaceTexture)
        val decoderOutputSurface = Surface(decoderSurfaceTexture)

        // ── decoder（输出到 GL external SurfaceTexture）──
        val decoder = MediaCodec.createDecoderByType(srcFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(srcFormat, decoderOutputSurface, null, 0)
        FileLogger.d(tag, "decoder ${decoder.name} configured")

        val overlayRenderer = ExportOverlayRenderer(ctx, srcWidth, srcHeight, overlayStyle)

        encoder.start()
        decoder.start()

        // seek 到裁剪起点前最近关键帧（丢弃早于起点的帧）
        videoExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        FileLogger.d(tag, "seekTo $startUs us (PREVIOUS_SYNC) actual=${videoExtractor.sampleTime}")

        var muxerVideoTrack = -1
        var muxerStarted = false
        var processedFrames = 0
        var droppedEarlyFrames = 0
        var completed = false

        try {
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (cancelled.get()) {
                    FileLogger.d(tag, "drain loop interrupted by cancel at frame=$processedFrames")
                    break
                }

                // 1) 喂 decoder 输入（源视频 sample）
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = videoExtractor.readSampleData(inBuf, 0)
                        val sampleTime = videoExtractor.sampleTime
                        if (sampleSize < 0 || sampleTime > endUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            FileLogger.d(tag, "decoder input EOS (sampleSize=$sampleSize sampleTime=$sampleTime endUs=$endUs)")
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            videoExtractor.advance()
                        }
                    }
                }

                // 2) drain decoder → GL render → encoder Surface
                if (!decoderDone) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* no output yet */ }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            FileLogger.d(tag, "decoder output format changed: ${decoder.outputFormat}")
                        }
                        outIndex >= 0 -> {
                            val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            val ptsUs = bufferInfo.presentationTimeUs
                            // 丢弃早于裁剪起点的帧（seek 落在前一个关键帧，需丢到 startUs）
                            val render = !eos && bufferInfo.size > 0 && ptsUs >= startUs && ptsUs <= endUs
                            decoder.releaseOutputBuffer(outIndex, render)
                            if (render) {
                                frameAvailable.awaitNewImage()
                                glRenderer.updateTexImage()
                                // 帧 PTS（相对录制开始 ms）→ frameWallClock → overlay bitmap
                                val framePtsMs = ptsUs / 1000L
                                val frameWallClock = VideoTelemetrySync.frameWallClock(
                                    ctx.videoStartedAtWallClock, framePtsMs,
                                )
                                val overlayBitmap = overlayRenderer.renderFrame(frameWallClock)
                                glRenderer.drawFrame(srcWidth, srcHeight, overlayBitmap)
                                // encoder 帧 PTS：源 PTS 减裁剪起点对齐到 0（与音轨同起点平移）
                                val outPtsUs = ptsUs - startUs
                                eglCore.setPresentationTime(outPtsUs * 1000L)
                                eglCore.swapBuffers()
                                processedFrames++
                                if (processedFrames % 30 == 0) {
                                    val idx = VideoTelemetrySync.findNearestSampleIndex(frameWallClock, ctx.sampleWallClocks)
                                    val f = ctx.frames.getOrNull(idx)
                                    FileLogger.d(
                                        tag,
                                        "frame=$processedFrames ptsUs=$ptsUs outPtsUs=$outPtsUs " +
                                            "wall=$frameWallClock idx=$idx spd=${f?.speedKmh?.let { "%.1f".format(it) }} " +
                                            "lonG=${f?.lonG?.let { "%.2f".format(it) }} latG=${f?.latG?.let { "%.2f".format(it) }}",
                                    )
                                    onProgress(processedFrames, estTotalFrames)
                                }
                            } else if (!eos && ptsUs < startUs) {
                                droppedEarlyFrames++
                            }
                            if (eos || ptsUs > endUs) {
                                decoderDone = true
                                encoder.signalEndOfInputStream()
                                FileLogger.d(tag, "decoder EOS/endReached -> encoder signalEndOfInputStream (dropped=$droppedEarlyFrames)")
                            }
                        }
                    }
                }

                // 3) drain encoder → muxer
                val encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    encOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* no output */ }
                    encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        require(!muxerStarted) { "encoder format changed twice" }
                        muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (sourceRotation != 0) muxer.setOrientationHint(sourceRotation)
                        // 音轨直通：在 muxer.start() 前 addTrack（见 writeAudioPassthrough 先 addTrack）
                        audioTrackInfo = addAudioTrack(muxer)
                        muxer.start()
                        muxerStarted = true
                        FileLogger.d(tag, "muxer started videoTrack=$muxerVideoTrack audioTrack=${audioTrackInfo?.muxerTrackIndex}")
                        // 音轨 sample 一次性写入（直通）
                        audioTrackInfo?.let { writeAudioPassthrough(muxer, it, startUs, endUs) }
                    }
                    encOutIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(encOutIndex)!!
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (isConfig) {
                            bufferInfo.size = 0 // codec config 不写（addTrack 时 format 已含 csd）
                        }
                        if (bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo)
                        }
                        val encEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(encOutIndex, false)
                        if (encEos) {
                            encoderDone = true
                            FileLogger.d(tag, "encoder EOS -> drain loop done frames=$processedFrames")
                        }
                    }
                }
            }

            completed = !cancelled.get() && encoderDone
            onProgress(processedFrames, estTotalFrames)
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { glRenderer.release() }
            runCatching { eglCore.release() }
            runCatching { decoderOutputSurface.release() }
            runCatching { encoderInputSurface.release() }
            runCatching { overlayRenderer.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioTrackInfo?.extractor?.release() }
            FileLogger.d(tag, "pipeline cleanup done completed=$completed frames=$processedFrames")
        }

        return completed
    }

    // 音轨直通信息（addTrack 后保存，muxer.start 后写 sample）
    private var audioTrackInfo: AudioTrackInfo? = null

    private data class AudioTrackInfo(
        val extractor: MediaExtractor,
        val sourceTrackIndex: Int,
        val muxerTrackIndex: Int,
    )

    /** 音轨 addTrack（源无音轨 → 返回 null，纯视频导出，spec 反例）。 */
    private fun addAudioTrack(muxer: MediaMuxer): AudioTrackInfo? {
        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(sourceVideoPath)
        val audioTrack = selectTrack(audioExtractor, "audio/")
        if (audioTrack < 0) {
            audioExtractor.release()
            FileLogger.d(tag, "source has no audio track -> video-only export")
            return null
        }
        audioExtractor.selectTrack(audioTrack)
        val audioFormat = audioExtractor.getTrackFormat(audioTrack)
        val muxerAudioTrack = muxer.addTrack(audioFormat)
        return AudioTrackInfo(audioExtractor, audioTrack, muxerAudioTrack)
    }

    /** 音轨直通 copy：读 sample 直接 writeSampleData，仅写 PTS ∈ [startUs, endUs]，PTS 减 startUs 对齐。 */
    private fun writeAudioPassthrough(
        muxer: MediaMuxer,
        info: AudioTrackInfo,
        startUs: Long,
        endUs: Long,
    ) {
        val extractor = info.extractor
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val maxBufSize = info.run {
            val fmt = extractor.getTrackFormat(sourceTrackIndex)
            if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                256 * 1024
            }
        }
        val buffer = ByteBuffer.allocate(maxBufSize)
        val bufferInfo = MediaCodec.BufferInfo()
        var audioSamples = 0
        var firstPts = -1L
        var lastPts = -1L
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val sampleTime = extractor.sampleTime
            if (sampleTime > endUs) break
            if (sampleTime >= startUs) {
                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.presentationTimeUs = sampleTime - startUs // 对齐视频起点
                bufferInfo.flags = sampleFlagsToBufferFlags(extractor.sampleFlags)
                muxer.writeSampleData(info.muxerTrackIndex, buffer, bufferInfo)
                if (firstPts < 0) firstPts = bufferInfo.presentationTimeUs
                lastPts = bufferInfo.presentationTimeUs
                audioSamples++
            }
            extractor.advance()
        }
        FileLogger.d(tag, "audio passthrough samples=$audioSamples firstPts=$firstPts lastPts=$lastPts (aligned to video start)")
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun sampleFlagsToBufferFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
