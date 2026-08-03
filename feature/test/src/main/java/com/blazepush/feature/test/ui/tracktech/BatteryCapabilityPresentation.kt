package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.BatteryCapabilityState

internal fun BatteryCapabilityState.displayLabel(): String = when (this) {
    BatteryCapabilityState.Pending -> "检测中"
    is BatteryCapabilityState.Available -> "$percent%"
    BatteryCapabilityState.Unsupported -> "不支持"
    BatteryCapabilityState.Failed -> "读取失败"
}
