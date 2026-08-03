// @IgnoreFormatCheck
package com.blazepush.feature.test.livetiming

import com.blazepush.core.data.local.dao.PendingLapUploadDao
import com.blazepush.core.data.local.entity.PendingLapUploadEntity
import com.blazepush.core.network.LapUploadApi
import com.blazepush.core.network.LapUploadDto
import com.blazepush.core.network.UploadResult
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.datastore.UserProfileRepository
import com.blazepush.feature.test.model.laptiming.LapRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * livetiming 上报编排（livetiming-lap-upload round）。
 *
 * 出圈 → 前置校验 → 实时上报；失败落 Room 队列；flush 用同一 clientLapId 幂等补传。
 * 依赖 [LivetimingUploader] 的 [UploadResult]（不碰 retrofit 类型，模块隔离）。
 * 上报是**旁路副作用**：异常 MUST NOT 影响本地圈速记录/UI（uploader 已吞异常为 NetworkError）。
 *
 * clientLapId 命门：[LapUploadMapper.clientLapId] 派生 / 队列持久化列复用，**绝不**请求处 new。
 */
class LapUploadOrchestrator(
    private val uploader: LapUploadApi,
    private val dao: PendingLapUploadDao,
    private val userProfile: UserProfileRepository,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : LapUploadTrigger {
    private val _needDriverNameHint = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val flushMutex = Mutex()

    /** 开关开但无车手名时发一次,UI 收到弹"请先设车手名"一次性提示（spec R1）。 */
    val needDriverNameHint: Flow<Unit> = _needDriverNameHint.asSharedFlow()

    /**
     * 出圈触发。前置不满足 → 跳过且不入队（开关关 / 无车手名 / 无 trackId）。
     * 满足 → 上报；Success 完成,失败入队。
     */
    override suspend fun onLapCompleted(record: LapRecord) {
        val quality = record.qualityDecision
        if (!quality.eligibility.upload) {
            FileLogger.d(
                "Livetiming",
                "质量策略跳过上报 lap=${record.lapIndex} confidence=${quality.confidence} provenance=${quality.provenance}",
            )
            return
        }
        // 路测修复（2026-06-04）：三个前置 gate 原先静默跳过 → 路测"上报失败"无法从日志区分
        // 是 gate 拦截还是网络/服务端失败；全部补可观测日志。
        if (!userProfile.livetimingEnabled.first()) {
            FileLogger.d("Livetiming", "开关关闭,跳过上报 lap=${record.lapIndex}")
            return // 开关关:完全不上报,也不入队（spec R1）
        }
        val driver = userProfile.driverName.first()
        if (driver.isBlank()) {
            FileLogger.d("Livetiming", "车手名未设置,跳过上报 lap=${record.lapIndex}（已发 UI 提示）")
            _needDriverNameHint.tryEmit(Unit) // 无车手名:跳过 + 一次性提示
            return
        }
        if (record.trackId.isBlank()) {
            FileLogger.d("Livetiming", "无 trackId（自由跑/未匹配赛道）,跳过上报 lap=${record.lapIndex}")
            return // 自由跑无赛道:跳过
        }
        val dto = LapUploadMapper.buildDto(record, driver)
        when (val r = uploader.upload(dto)) {
            is UploadResult.Success -> FileLogger.d("Livetiming", "上报成功 201 ${dto.clientLapId}")
            is UploadResult.HttpError -> if (r.code == 400) {
                // 400 必须落服务端拒绝原因——2026-06-03 路测 3 圈全 400,无 body 日志只能 curl 复现定位
                FileLogger.e("Livetiming", "上报 400 不可上报,丢弃 ${dto.clientLapId} server=「${r.body}」") // 永久失败:不入队
            } else {
                FileLogger.d("Livetiming", "上报 HTTP ${r.code} ${dto.clientLapId},入队补传 server=「${r.body}」") // 401/429/5xx
                enqueue(dto)
            }
            is UploadResult.NetworkError -> {
                FileLogger.e("Livetiming", "上报网络失败 ${dto.clientLapId},入队", r.cause)
                enqueue(dto)
            }
        }
    }

    /** 队列补传:出圈后 + app 启动调。串行重试,复用各条持久化的 clientLapId。 */
    override suspend fun flush() = flushMutex.withLock {
        val pending = dao.all()
        for (p in pending) {
            val dto = p.toDto() // 复用持久化 clientLapId,不 new
            if (dto.quality == null || dto.evidenceVersion == null) {
                FileLogger.d("Livetiming", "legacy unknown pending 保留且不自动上传 ${dto.clientLapId}")
                continue
            }
            when (val r = uploader.upload(dto)) {
                is UploadResult.Success -> dao.deleteByClientLapId(p.clientLapId) // 201（含幂等重复）出队
                is UploadResult.HttpError -> when (r.code) {
                    400 -> {
                        FileLogger.e("Livetiming", "flush 400 不可上报,丢弃 ${dto.clientLapId} server=「${r.body}」")
                        dao.deleteByClientLapId(p.clientLapId) // 永久失败丢弃,不死循环（spec R4）
                    }
                    429 -> {
                        FileLogger.d("Livetiming", "flush 429 限流,本轮停止")
                        return // 限流:停止本轮,保留队列,按 Retry-After 下次再来
                    }
                    else -> {
                        // 路测修复（2026-06-04）：原先无日志,401/5xx 重试在日志里不可见易误判为圈丢失
                        FileLogger.d("Livetiming", "flush HTTP ${r.code} ${dto.clientLapId},保留重试")
                        dao.incrementRetry(p.clientLapId) // 401/5xx:保留重试
                    }
                }
                is UploadResult.NetworkError -> {
                    FileLogger.e("Livetiming", "flush 网络失败 ${dto.clientLapId},保留重试", r.cause)
                    dao.incrementRetry(p.clientLapId)
                    return // 网络不通,停止本轮 flush,下次再来
                }
            }
        }
    }

    private suspend fun enqueue(dto: LapUploadDto) {
        dao.enqueue(
            PendingLapUploadEntity(
                clientLapId = dto.clientLapId, // 入队即生成一次,后续 flush 复用此列（命门）
                trackId = dto.trackId,
                driver = dto.driver,
                lapNo = dto.lapNo,
                lapTimeMs = dto.lapTimeMs,
                sectorsMsCsv = dto.sectorsMs?.joinToString(","),
                lappedAtRfc3339 = dto.lappedAt,
                createdAtMs = nowMs(),
                quality = dto.quality,
                qualityFlagsCsv = dto.qualityFlags?.joinToString(","),
                evidenceVersion = dto.evidenceVersion,
            ),
        )
    }

    private fun PendingLapUploadEntity.toDto(): LapUploadDto = LapUploadDto(
        trackId = trackId,
        driver = driver,
        carModel = null,
        lapNo = lapNo,
        lapTimeMs = lapTimeMs,
        sectorsMs = sectorsMsCsv?.takeIf { it.isNotBlank() }?.split(",")?.map { it.toLong() },
        clientLapId = clientLapId, // 复用持久化键,不 new
        lappedAt = lappedAtRfc3339,
        quality = quality,
        qualityFlags = qualityFlagsCsv?.takeIf { it.isNotBlank() }?.split(","),
        evidenceVersion = evidenceVersion,
    )
}
