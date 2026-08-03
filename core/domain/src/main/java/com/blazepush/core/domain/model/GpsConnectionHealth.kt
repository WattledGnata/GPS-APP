package com.blazepush.core.domain.model

/** 当前 GATT 代次的串行订阅进度。 */
enum class BleHandshakeStage {
    PENDING_MAIN,
    PENDING_TIME,
    PROBING_BATTERY,
    COMPLETE,
    FAILED,
}

enum class GpsChannelSubscriptionState {
    PENDING,
    SUBSCRIBED,
    FAILED,
}

data class BleHandshakeState(
    val connectionGeneration: Long = 0L,
    val stage: BleHandshakeStage = BleHandshakeStage.PENDING_MAIN,
    val main: GpsChannelSubscriptionState = GpsChannelSubscriptionState.PENDING,
    val time: GpsChannelSubscriptionState = GpsChannelSubscriptionState.PENDING,
)

/**
 * Battery 是与计时完全正交的互斥能力状态。Pending 只表示当前代次仍在探测，不能解释为不支持。
 */
sealed interface BatteryCapabilityState {
    data object Pending : BatteryCapabilityState

    data class Available(val percent: Int) : BatteryCapabilityState {
        init {
            require(percent in 0..100) { "Battery percent must be in 0..100" }
        }
    }

    data object Unsupported : BatteryCapabilityState
    data object Failed : BatteryCapabilityState
}

/** 当前连接代次从链路存活到可计时所需的协议证据。 */
enum class TimingHandshakeState {
    WAITING_MAIN,
    WAITING_TIME,
    WAITING_SYNCHRONIZED_MAIN,
    SYNCHRONIZED,
}
