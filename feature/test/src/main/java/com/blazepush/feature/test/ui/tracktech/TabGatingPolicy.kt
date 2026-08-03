package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.GpsData

/**
 * 首页 tab 入口 readiness condition enum。
 *
 * 与 SmartTestLauncher 内 device condition 阈值对齐（BLE / data fresh / fix / time / sats / hdop），
 * 但显式不含 speed_at_start / template / 起点条件 —— 见 [TabGatingPolicy] KDoc 边界声明。
 *
 * @author Track Tech V2
 * @description Tab gating readiness condition enum
 * @date 2026-04-28
 */
enum class TabReadinessCondition {
    BLE_CONNECTED,
    DATA_FRESH,
    SATELLITES_SUFFICIENT,
    HDOP_GOOD,
    GPS_FIX_VALID,
    TIME_SYNCED,
    GPS_SIGNAL_STABLE,
}

/**
 * Tab gating readiness 计算结果。
 *
 * @property canEnterTestFlow 所有 device/data 条件全满足才为 true
 * @property unmetConditions 当前未满足的条件子集
 *
 * @author Track Tech V2
 * @description Tab gating readiness 结果数据载体
 * @date 2026-04-28
 */
data class TabReadiness(
    val canEnterTestFlow: Boolean,
    val unmetConditions: List<TabReadinessCondition>,
)

/**
 * 首页 tab 入口 gating policy。
 *
 * **MUST 仅检查** device/data 基础条件：
 * - BLE_CONNECTED:        connectionState == CONNECTED
 * - DATA_FRESH:           dataQuality.dataAge < 1000ms
 * - SATELLITES_SUFFICIENT: gpsData.satelliteCount >= 6
 * - HDOP_GOOD:            gpsData.hdop > 0 && gpsData.hdop < 2.0
 * - GPS_FIX_VALID:        gpsData.fixQuality > 0
 * - TIME_SYNCED:          gpsData.isTimeSynced && timestamp 非 sentinel
 *
 * **MUST NOT 检查**：
 * - speed range（任何速度区间）
 * - test template（0-100 / 100-0 / lap 区分）
 * - acceleration/braking 起点条件
 *
 * Rationale：首页入口语义是 "用户能否进入测试流程"，不是 "用户能否立即开始测试"。
 * 速度门槛 / template 准备 / 起点条件应在 TestExecutionScreen 等执行前
 * 准备屏由 [com.blazepush.core.domain.usecase.SmartTestLauncher.checkLaunchConditions] 处理。
 *
 * 阈值与 SmartTestLauncher 内对应 4 项 device condition 阈值对齐保证语义一致。
 *
 * @author Track Tech V2
 * @description 首页 tab 入口 gating policy 单例
 * @date 2026-04-28
 */
object TabGatingPolicy {
    /**
     * 计算当前 tab readiness 状态。
     *
     * 仅基于 device/data 基础条件派生 [TabReadiness]，**不**检查 speed range /
     * test template / 起点条件 —— 见类 KDoc 的 MUST NOT 边界。
     *
     * @param connectionState BLE 连接状态
     * @param gpsData 当前 GPS 数据帧
     * @param dataQuality 当前数据质量评估
     * @return [TabReadiness]：全部基础条件满足则 canEnterTestFlow = true
     */
    fun computeTabReadiness(
        connectionState: ConnectionState,
        gpsData: GpsData,
        dataQuality: DataQuality,
    ): TabReadiness {
        val unmet = mutableListOf<TabReadinessCondition>()
        if (connectionState != ConnectionState.CONNECTED) {
            unmet += TabReadinessCondition.BLE_CONNECTED
        }
        if (!gpsData.hasMainFrame || gpsData.isStale ||
            dataQuality.dataAge >= gpsData.mainFrameSilenceTimeoutMs
        ) {
            unmet += TabReadinessCondition.DATA_FRESH
        }
        if (gpsData.satelliteCount < 6) {
            unmet += TabReadinessCondition.SATELLITES_SUFFICIENT
        }
        if (gpsData.hdop <= 0.0 || gpsData.hdop >= 2.0) {
            unmet += TabReadinessCondition.HDOP_GOOD
        }
        if (gpsData.fixQuality <= 0) {
            unmet += TabReadinessCondition.GPS_FIX_VALID
        }
        if (!gpsData.isTimeSynced || gpsData.timestamp == Long.MIN_VALUE) {
            unmet += TabReadinessCondition.TIME_SYNCED
        }
        if (!gpsData.isRecoveryStable ||
            gpsData.consecutiveReliableMainFrames < gpsData.requiredReliableMainFrames
        ) {
            unmet += TabReadinessCondition.GPS_SIGNAL_STABLE
        }
        return TabReadiness(
            canEnterTestFlow = unmet.isEmpty(),
            unmetConditions = unmet,
        )
    }
}
