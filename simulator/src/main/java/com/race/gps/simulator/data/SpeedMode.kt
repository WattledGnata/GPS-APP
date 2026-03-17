package com.race.gps.simulator.data

/**
 * 速度模拟模式
 */
enum class SpeedMode {
    /** 静止模式：速度保持为0 */
    STATIC,

    /** 恒定模式：保持用户设定的恒定速度 */
    CONSTANT,

    /** 加速模式：从当前速度线性加速到目标速度 */
    ACCELERATION,

    /** 减速模式：从当前速度线性减速到目标速度 */
    DECELERATION,

    /** 波形模式：按正弦波变化速度 */
    WAVEFORM,

    /** 真实模式：模拟实际驾驶的速度波动 */
    REALISTIC,

    /** 自定义模式：用户定义的速度曲线 */
    CUSTOM
}

fun SpeedMode.getDisplayName(): String {
    return when (this) {
        SpeedMode.STATIC -> "静止"
        SpeedMode.CONSTANT -> "恒定"
        SpeedMode.ACCELERATION -> "加速"
        SpeedMode.DECELERATION -> "减��"
        SpeedMode.WAVEFORM -> "波形"
        SpeedMode.REALISTIC -> "真实驾驶"
        SpeedMode.CUSTOM -> "自定义"
    }
}

fun SpeedMode.getDescription(): String {
    return when (this) {
        SpeedMode.STATIC -> "速度保持为0 km/h"
        SpeedMode.CONSTANT -> "保持用户设定的恒定速度"
        SpeedMode.ACCELERATION -> "从当前速度匀加速到目标速度"
        SpeedMode.DECELERATION -> "从当前速度匀减速到目标速度"
        SpeedMode.WAVEFORM -> "速度按正弦波规律变化"
        SpeedMode.REALISTIC -> "模拟真实驾驶的速度波动"
        SpeedMode.CUSTOM -> "用户自定义速度曲线"
    }
}
