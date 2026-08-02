package com.blazepush.core.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程级未闭环圈速恢复入口。
 *
 * Application 启动与 Records/LAPS 页面复用同一个启动 cutoff；互斥锁避免两个入口并发扫描、写回。
 */
class IncompleteLapSessionRecoveryCoordinator(
    private val telemetryRepository: TelemetryRepository,
    private val processStartedAtMs: Long,
) {
    private val recoveryMutex = Mutex()

    suspend fun recover(
        recoveryNowMs: Long = System.currentTimeMillis(),
    ): TelemetryRepository.LapSessionRecoveryReport = recoveryMutex.withLock {
        telemetryRepository.recoverIncompleteLapSessions(
            processStartedAtMs = processStartedAtMs,
            recoveryNowMs = recoveryNowMs,
        )
    }
}
