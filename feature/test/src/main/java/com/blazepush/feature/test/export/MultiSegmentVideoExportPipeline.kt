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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 跨段圈导出管线。
 *
 * 每个 [VideoTimelinePlan.Slice] 独立 seek/decode，只把目标局部帧送入同一个 GL overlay 和
 * H.264 encoder。这样无需先生成整段 concat 临时文件，也不会要求分段裁剪点恰好是关键帧。
 * 输出 PTS 使用 timeline 的压缩时间轴；overlay wall-clock 始终使用各源段原始时间。
 */
internal class MultiSegmentVideoExportPipeline(
    private val ctx: LapPlaybackLoader.LapPlaybackContext,
) {
    private val tag = "MultiExportPipe"
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
        FileLogger.d(tag, "cancel requested")
    }

    fun run(
        muxer: MediaMuxer,
        onProgress: (processedFrames: Int, totalFrames: Int) -> Unit,
    ): Boolean {
        val slices = ctx.timelinePlan.slices
        require(slices.isNotEmpty()) { "timeline has no export slices" }

        val firstExtractor = MediaExtractor()
        firstExtractor.setDataSource(slices.first().segment.filePath)
        val firstVideoTrack = selectTrack(firstExtractor, "video/")
        require(firstVideoTrack >= 0) { "no video track in ${slices.first().segment.filePath}" }
        val firstFormat = firstExtractor.getTrackFormat(firstVideoTrack)
        val width = firstFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = firstFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = if (firstFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            firstFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        } else {
            VideoExportConfig.DEFAULT_FRAME_RATE
        }
        val rotation = if (firstFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            firstFormat.getInteger(MediaFormat.KEY_ROTATION)
        } else {
            0
        }
        firstExtractor.release()

        val estimatedFrames = ((ctx.timelinePlan.outputDurationMs / 1000.0) * frameRate)
            .toInt()
            .coerceAtLeast(1)
        FileLogger.d(
            tag,
            "start slices=${slices.size} ${width}x$height fps=$frameRate " +
                "duration=${ctx.timelinePlan.outputDurationMs}ms gaps=${ctx.timelinePlan.gaps.map { it.durationMs }}",
        )

        val encoderFormat = MediaFormat.createVideoFormat(VideoExportConfig.VIDEO_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VideoExportConfig.computeBitrate(width, height, frameRate))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoExportConfig.I_FRAME_INTERVAL_SEC)
        }
        val encoder = MediaCodec.createEncoderByType(VideoExportConfig.VIDEO_MIME)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface: Surface = encoder.createInputSurface()

        val eglCore = OverlayEglCore()
        eglCore.createWindowSurface(encoderInputSurface)
        eglCore.makeCurrent()
        val glRenderer = OverlayGlRenderer()
        val decoderSurfaceTexture = glRenderer.prepareSurfaceTexture()
        val frameAvailable = FrameAvailableGate(decoderSurfaceTexture)
        val decoderOutputSurface = Surface(decoderSurfaceTexture)
        val overlayRenderer = ExportOverlayRenderer(ctx, width, height)

        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false
        var processedFrames = 0
        var encoderDone = false
        var completed = false
        val encoderInfo = MediaCodec.BufferInfo()

        fun startMuxerIfNeeded() {
            if (muxerStarted) return
            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
            if (rotation != 0) muxer.setOrientationHint(rotation)
            val audioFormat = firstAudioFormat(slices)
            if (audioFormat != null) muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true
            if (muxerAudioTrack >= 0) {
                writeAllAudio(slices, muxer, muxerAudioTrack)
            }
            FileLogger.d(tag, "muxer started video=$muxerVideoTrack audio=$muxerAudioTrack")
        }

        fun drainEncoder(): Boolean {
            val index = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    require(!muxerStarted) { "encoder format changed twice" }
                    startMuxerIfNeeded()
                }
                index >= 0 -> {
                    val data = encoder.getOutputBuffer(index)!!
                    if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoderInfo.size = 0
                    }
                    if (encoderInfo.size > 0 && muxerStarted) {
                        data.position(encoderInfo.offset)
                        data.limit(encoderInfo.offset + encoderInfo.size)
                        muxer.writeSampleData(muxerVideoTrack, data, encoderInfo)
                    }
                    val eos = (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (eos) encoderDone = true
                }
            }
            return encoderDone
        }

        encoder.start()
        try {
            slices.forEachIndexed { sliceIndex, slice ->
                if (cancelled.get()) return@forEachIndexed
                val extractor = MediaExtractor()
                var decoder: MediaCodec? = null
                try {
                    extractor.setDataSource(slice.segment.filePath)
                    val track = selectTrack(extractor, "video/")
                    require(track >= 0) { "no video track in ${slice.segment.filePath}" }
                    extractor.selectTrack(track)
                    val format = extractor.getTrackFormat(track)
                    require(format.getInteger(MediaFormat.KEY_WIDTH) == width &&
                        format.getInteger(MediaFormat.KEY_HEIGHT) == height
                    ) {
                        "跨段分辨率不一致，暂无法合并"
                    }
                    val mime = format.getString(MediaFormat.KEY_MIME)
                        ?: error("video mime missing")
                    decoder = MediaCodec.createDecoderByType(mime)
                    decoder.configure(format, decoderOutputSurface, null, 0)
                    decoder.start()

                    val startUs = slice.sourceStartMs * 1000L
                    val endUs = slice.sourceEndMs * 1000L
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val decoderInfo = MediaCodec.BufferInfo()
                    var inputDone = false
                    var decoderDone = false

                    while (!decoderDone && !cancelled.get()) {
                        if (!inputDone) {
                            val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                            if (inputIndex >= 0) {
                                val input = decoder.getInputBuffer(inputIndex)!!
                                val size = extractor.readSampleData(input, 0)
                                val sampleTime = extractor.sampleTime
                                if (size < 0 || sampleTime > endUs) {
                                    decoder.queueInputBuffer(
                                        inputIndex, 0, 0, 0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US)
                        when {
                            outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                            outputIndex >= 0 -> {
                                val eos = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                val ptsUs = decoderInfo.presentationTimeUs
                                val render = !eos && decoderInfo.size > 0 &&
                                    ptsUs >= startUs && ptsUs <= endUs
                                decoder.releaseOutputBuffer(outputIndex, render)
                                if (render) {
                                    frameAvailable.awaitNewImage()
                                    glRenderer.updateTexImage()
                                    val wallClock = slice.segment.startWallClock + ptsUs / 1000L
                                    val bitmap = overlayRenderer.renderFrame(wallClock)
                                    glRenderer.drawFrame(width, height, bitmap)
                                    val outputPtsUs = slice.outputStartMs * 1000L + (ptsUs - startUs)
                                    eglCore.setPresentationTime(outputPtsUs * 1000L)
                                    eglCore.swapBuffers()
                                    processedFrames++
                                    if (processedFrames % 15 == 0) {
                                        onProgress(processedFrames, estimatedFrames)
                                    }
                                }
                                if (eos || ptsUs > endUs) decoderDone = true
                            }
                        }
                        // 每轮及时 drain，避免 encoder 输出队列反压 GL input surface。
                        drainEncoder()
                    }
                    FileLogger.d(
                        tag,
                        "slice ${sliceIndex + 1}/${slices.size} done seg=${slice.segment.segmentIndex} " +
                            "source=[${slice.sourceStartMs},${slice.sourceEndMs}] " +
                            "output=[${slice.outputStartMs},${slice.outputEndMs}]",
                    )
                } finally {
                    runCatching { decoder?.stop() }
                    runCatching { decoder?.release() }
                    runCatching { extractor.release() }
                }
            }

            if (!cancelled.get()) {
                encoder.signalEndOfInputStream()
                while (!encoderDone) {
                    drainEncoder()
                }
            }
            completed = !cancelled.get() && encoderDone
            onProgress(processedFrames, estimatedFrames)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { glRenderer.release() }
            runCatching { eglCore.release() }
            runCatching { decoderOutputSurface.release() }
            runCatching { encoderInputSurface.release() }
            runCatching { overlayRenderer.release() }
            FileLogger.d(tag, "cleanup completed=$completed frames=$processedFrames")
        }
        return completed
    }

    private fun firstAudioFormat(slices: List<VideoTimelinePlan.Slice>): MediaFormat? {
        slices.forEach { slice ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(slice.segment.filePath)
                val track = selectTrack(extractor, "audio/")
                if (track >= 0) return extractor.getTrackFormat(track)
            } finally {
                extractor.release()
            }
        }
        return null
    }

    private fun writeAllAudio(
        slices: List<VideoTimelinePlan.Slice>,
        muxer: MediaMuxer,
        muxerTrack: Int,
    ) {
        slices.forEach { slice ->
            if (cancelled.get()) return
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(slice.segment.filePath)
                val track = selectTrack(extractor, "audio/")
                if (track < 0) return@forEach
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    256 * 1024
                }
                val buffer = ByteBuffer.allocate(maxSize)
                val info = MediaCodec.BufferInfo()
                val startUs = slice.sourceStartMs * 1000L
                val endUs = slice.sourceEndMs * 1000L
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (!cancelled.get()) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val ptsUs = extractor.sampleTime
                    if (ptsUs > endUs) break
                    if (ptsUs >= startUs) {
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = slice.outputStartMs * 1000L + (ptsUs - startUs)
                        info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                        muxer.writeSampleData(muxerTrack, buffer, info)
                    }
                    extractor.advance()
                }
            } finally {
                extractor.release()
            }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return index
        }
        return -1
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
