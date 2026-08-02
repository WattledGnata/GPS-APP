package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.GPS_FIX_RECOVERY_MAIN_FRAMES

/** 单帧具备可靠定位证据，但尚不包含恢复连续帧门槛。 */
fun GpsData.hasReliableFixEvidence(): Boolean =
    isConnected &&
        hasMainFrame &&
        !isStale &&
        fixQuality > 0 &&
        satelliteCount >= 6 &&
        hdop > 0.0 && hdop < 2.0 &&
        isTimeSynced &&
        timestamp != Long.MIN_VALUE

/**
 * 计时/轨迹消费者的统一 GPS 帧白名单。BLE 连接、收到字节、定位有效不是同一件事。
 */
fun GpsData.isUsableForTiming(): Boolean =
    hasReliableFixEvidence() &&
        consecutiveReliableMainFrames >= GPS_FIX_RECOVERY_MAIN_FRAMES
