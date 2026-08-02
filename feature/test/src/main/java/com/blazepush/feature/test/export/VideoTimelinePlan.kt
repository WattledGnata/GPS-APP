package com.blazepush.feature.test.export

import com.blazepush.core.domain.model.VideoSegment
import com.blazepush.feature.test.recording.VideoTelemetrySync

/**
 * 单圈视频的统一时间轴计划。
 *
 * 播放、导出和 UI 都消费同一份 wall-clock 覆盖事实，避免继续用“首段起点 + 单文件时长”
 * 推断多段录像。相邻段之间不超过 [SHORT_GAP_TOLERANCE_MS] 的录制轮换间隙视作无感技术
 * gap；不超过 [EXPORT_BRIDGE_GAP_TOLERANCE_MS] 的相邻段 gap 虽仍使 coverage 为 PARTIAL，
 * 但可在明确提示后由导出管线压缩。只有单侧画面或更长 gap 才阻止导出。
 */
internal data class VideoTimelinePlan(
    val windowStartWallClock: Long,
    val windowEndWallClock: Long,
    val lapStartWallClock: Long,
    val lapEndWallClock: Long,
    val slices: List<Slice>,
    val gaps: List<Gap>,
    val coverage: VideoExportClip.Coverage,
) {
    data class Slice(
        val segment: VideoSegment,
        val sourceStartMs: Long,
        val sourceEndMs: Long,
        val wallClockStart: Long,
        val wallClockEnd: Long,
        val outputStartMs: Long,
        val outputEndMs: Long,
    ) {
        val durationMs: Long get() = sourceEndMs - sourceStartMs
    }

    data class Gap(
        val wallClockStart: Long,
        val wallClockEnd: Long,
        val isShortTechnicalGap: Boolean,
        /** true 仅表示 gap 左右各有一段有效 slice；窗口头尾缺画面必须为 false。 */
        val isBetweenSegments: Boolean,
        /** 历史库无 rotation reason，只对有界的相邻段 gap 做可逆导出桥接。 */
        val isExportBridgeable: Boolean,
    ) {
        val durationMs: Long get() = wallClockEnd - wallClockStart
    }

    val outputDurationMs: Long get() = slices.lastOrNull()?.outputEndMs ?: 0L
    val isCrossSegment: Boolean get() = slices.map { it.segment.id }.distinct().size > 1
    val lapGaps: List<Gap>
        get() = gaps.filter { it.wallClockEnd > lapStartWallClock && it.wallClockStart < lapEndWallClock }
    val bridgeableLapGaps: List<Gap> get() = lapGaps.filter { it.isExportBridgeable }
    val blockingLapGaps: List<Gap> get() = lapGaps.filterNot { it.isExportBridgeable }
    val hasLapPicture: Boolean
        get() = slices.any { it.wallClockEnd > lapStartWallClock && it.wallClockStart < lapEndWallClock }

    /**
     * 导出能力与 coverage 分离：可桥接 chapter 仍是 PARTIAL，但已有管线可诚实压缩缺口。
     * 无画面、窗口头尾单侧缺失、或超过上限的圈内 gap 继续 fail closed。
     */
    val isExportable: Boolean get() = hasLapPicture && blockingLapGaps.isEmpty()

    fun sliceAtWallClock(wallClock: Long): Slice? =
        slices.firstOrNull { wallClock >= it.wallClockStart && wallClock < it.wallClockEnd }

    fun gapAtWallClock(wallClock: Long): Gap? =
        gaps.firstOrNull { wallClock >= it.wallClockStart && wallClock < it.wallClockEnd }

    /**
     * 将压缩后的导出 PTS 映射回原录像 wall-clock，供 overlay 继续对齐原始遥测。
     */
    fun wallClockForOutputPosition(outputPositionMs: Long): Long {
        val slice = slices.firstOrNull {
            outputPositionMs >= it.outputStartMs && outputPositionMs < it.outputEndMs
        } ?: slices.lastOrNull()
            ?: return windowStartWallClock
        val within = (outputPositionMs - slice.outputStartMs).coerceIn(0L, slice.durationMs)
        return slice.wallClockStart + within
    }

    companion object {
        /** CameraX 按圈轮换时允许被无感压缩的短暂切段间隙。 */
        const val SHORT_GAP_TOLERANCE_MS = 500L

        /** 历史自动轮换没有 reason 字段；仅桥接足够短且两侧都有相邻段的缺口。 */
        const val EXPORT_BRIDGE_GAP_TOLERANCE_MS = 5_000L

        fun build(
            lapStartWallClock: Long,
            lapEndWallClock: Long,
            segments: List<VideoSegment>,
            durationMsBySegmentId: Map<Long, Long> = emptyMap(),
            leadInMs: Long = VideoTelemetrySync.LAP_LEAD_IN_MS,
            leadOutMs: Long = VideoTelemetrySync.LAP_LEAD_OUT_MS,
            shortGapToleranceMs: Long = SHORT_GAP_TOLERANCE_MS,
            exportBridgeGapToleranceMs: Long = EXPORT_BRIDGE_GAP_TOLERANCE_MS,
        ): VideoTimelinePlan {
            val range = VideoTelemetrySync.lapPlayheadRange(
                lapStartWallClock = lapStartWallClock,
                lapEndWallClock = lapEndWallClock,
                leadInMs = leadInMs,
                leadOutMs = leadOutMs,
            )
            val windowStart = range.first
            val windowEnd = range.last

            data class RawSlice(
                val segment: VideoSegment,
                val durationMs: Long,
                val start: Long,
                val end: Long,
            )

            val raw = segments.mapNotNull { segment ->
                val duration = durationMsBySegmentId[segment.id]
                    ?: segment.durationMs
                    ?: segment.endWallClock?.minus(segment.startWallClock)
                    ?: 0L
                if (duration <= 0L) return@mapNotNull null
                val segmentEnd = segment.startWallClock + duration
                val start = maxOf(windowStart, segment.startWallClock)
                val end = minOf(windowEnd, segmentEnd)
                if (end <= start) null else RawSlice(segment, duration, start, end)
            }.sortedWith(compareBy<RawSlice> { it.start }.thenBy { it.segment.segmentIndex })

            // 同时处理异常重叠段：较早段优先，后一段从尚未覆盖的 wall-clock 开始。
            val normalized = mutableListOf<RawSlice>()
            var coveredUntil = windowStart
            raw.forEach { slice ->
                val start = maxOf(slice.start, coveredUntil)
                if (slice.end > start) {
                    normalized += slice.copy(start = start)
                    coveredUntil = slice.end
                }
            }

            val gaps = mutableListOf<Gap>()
            var cursor = windowStart
            normalized.forEachIndexed { index, slice ->
                if (slice.start > cursor) {
                    val duration = slice.start - cursor
                    val isBetweenSegments = index > 0
                    gaps += Gap(
                        wallClockStart = cursor,
                        wallClockEnd = slice.start,
                        isShortTechnicalGap = duration <= shortGapToleranceMs,
                        isBetweenSegments = isBetweenSegments,
                        isExportBridgeable = isBetweenSegments && duration <= exportBridgeGapToleranceMs,
                    )
                }
                cursor = maxOf(cursor, slice.end)
            }
            if (cursor < windowEnd) {
                val duration = windowEnd - cursor
                gaps += Gap(
                    wallClockStart = cursor,
                    wallClockEnd = windowEnd,
                    isShortTechnicalGap = duration <= shortGapToleranceMs,
                    isBetweenSegments = false,
                    isExportBridgeable = false,
                )
            }

            var outputCursor = 0L
            val slices = normalized.map { rawSlice ->
                val sourceStart = rawSlice.start - rawSlice.segment.startWallClock
                val sourceEnd = rawSlice.end - rawSlice.segment.startWallClock
                Slice(
                    segment = rawSlice.segment,
                    sourceStartMs = sourceStart.coerceIn(0L, rawSlice.durationMs),
                    sourceEndMs = sourceEnd.coerceIn(0L, rawSlice.durationMs),
                    wallClockStart = rawSlice.start,
                    wallClockEnd = rawSlice.end,
                    outputStartMs = outputCursor,
                    outputEndMs = outputCursor + (rawSlice.end - rawSlice.start),
                ).also { outputCursor = it.outputEndMs }
            }

            val lapSlices = slices.filter {
                it.wallClockEnd > lapStartWallClock && it.wallClockStart < lapEndWallClock
            }
            val lapGaps = gaps.filter {
                it.wallClockEnd > lapStartWallClock && it.wallClockStart < lapEndWallClock
            }
            val hasLapPicture = lapSlices.isNotEmpty()
            val leadingMissing = lapSlices.minOfOrNull { it.wallClockStart }?.let { it > lapStartWallClock } ?: true
            val trailingMissing = lapSlices.maxOfOrNull { it.wallClockEnd }?.let { it < lapEndWallClock } ?: true
            val hasLongInternalGap = lapGaps.any { !it.isShortTechnicalGap }
            val coverage = when {
                !hasLapPicture -> VideoExportClip.Coverage.NONE
                !leadingMissing && !trailingMissing && !hasLongInternalGap -> VideoExportClip.Coverage.FULL
                else -> VideoExportClip.Coverage.PARTIAL
            }

            return VideoTimelinePlan(
                windowStartWallClock = windowStart,
                windowEndWallClock = windowEnd,
                lapStartWallClock = lapStartWallClock,
                lapEndWallClock = lapEndWallClock,
                slices = slices,
                gaps = gaps,
                coverage = coverage,
            )
        }
    }
}
