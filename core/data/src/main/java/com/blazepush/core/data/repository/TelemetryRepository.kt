package com.blazepush.core.data.repository

import android.content.Context
import com.blazepush.core.data.local.binary.BinaryTelemetryWriter
import com.blazepush.core.data.local.binary.LapTelemetryReader
import com.blazepush.core.data.local.binary.PerformanceTestTelemetryReader
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.model.TelemetrySessionType
import java.io.File
import java.util.UUID

/**
 * 统一 GPS 点阵持久化入口（A56）。
 * 封装 Room metadata DAO + binary file writer/reader，外部仅与 sessionId / TelemetrySample 打交道。
 *
 * @author CC
 * @description unified GPS telemetry persistence repository
 * @date 2026-04-30
 */
class TelemetryRepository(
    private val context: Context,
    private val sessionDao: TelemetrySessionDao,
    private val crossingDao: CrossingEventDao,
) {
    private var activeWriter: BinaryTelemetryWriter? = null
    private var activeSessionId: String? = null
    private var activeSessionType: TelemetrySessionType = TelemetrySessionType.PERFORMANCE_TEST

    /**
     * 开启新 session：生成 UUID + 写 metadata 入 Room + 启动 binary writer。
     */
    suspend fun startSession(type: TelemetrySessionType): String {
        val sessionId = UUID.randomUUID().toString()
        val startTs = System.currentTimeMillis()
        val file = telemetryFile(sessionId)
        file.parentFile?.mkdirs()

        val entity = TelemetrySessionEntity(
            sessionId = sessionId,
            sessionType = type.name,
            startTs = startTs,
            endTs = startTs,
            binaryFilePath = file.absolutePath,
        )
        sessionDao.insert(entity)

        val writer = BinaryTelemetryWriter()
        writer.open(file.absolutePath, type, startTs)
        activeWriter = writer
        activeSessionId = sessionId
        activeSessionType = type
        return sessionId
    }

    /**
     * 提交一帧 sample 到 active writer 队列。
     */
    suspend fun writeSample(sample: TelemetrySample) {
        activeWriter?.write(sample)
    }

    /**
     * 事务式写入过线事件到 Room（不走 binary 流，是计时精度真相源）。
     */
    suspend fun writeCrossing(event: TelemetryCrossingEvent) {
        val entity = CrossingEventEntity(
            sessionId = event.sessionId,
            lapIndex = event.lapIndex,
            crossingTimestampMs = event.crossingTimestampMs,
            speedKmh = event.speedKmh,
            gateId = event.gateId,
            gateType = event.gateType,
            accepted = event.accepted,
            reason = event.reason,
            directionScore = event.directionScore,
        )
        crossingDao.insertInTransaction(entity)
    }

    /**
     * 主动 flush：等待 active writer header 回写 + force 刷盘后返回。
     */
    suspend fun flush() {
        activeWriter?.flush()
    }

    /**
     * 关闭 session：close writer + 更新 Room endTs。
     */
    suspend fun endSession(sessionId: String) {
        val writer = activeWriter ?: return
        writer.close()
        activeWriter = null
        activeSessionId = null
        sessionDao.updateEndTs(sessionId, System.currentTimeMillis())
    }

    /**
     * 拉 session metadata 转换成 domain model；未找到返回 null。
     */
    suspend fun getSession(sessionId: String): TelemetrySession? {
        return sessionDao.queryBySessionId(sessionId)?.toDomain()
    }

    /**
     * 读加减速 session 全部 sample（顺序读整个 chunk file）。
     */
    fun readPerformanceSamples(filePath: String): List<TelemetrySample> =
        PerformanceTestTelemetryReader.read(filePath)

    /**
     * 按时间窗口 [lapStartTs, lapEndTs] 过滤拉取 lap session 内的 sample。
     */
    fun readLapSamples(filePath: String, lapStartTs: Long, lapEndTs: Long): List<TelemetrySample> =
        LapTelemetryReader.read(filePath, lapStartTs, lapEndTs)

    private fun telemetryFile(sessionId: String): File =
        File(context.filesDir, "telemetry/$sessionId.bin")

    private fun TelemetrySessionEntity.toDomain() = TelemetrySession(
        sessionId = sessionId,
        sessionType = TelemetrySessionType.valueOf(sessionType),
        startTs = startTs,
        endTs = endTs,
        binaryFilePath = binaryFilePath,
        lapCount = lapCount,
        bestLapMs = bestLapMs,
    )
}