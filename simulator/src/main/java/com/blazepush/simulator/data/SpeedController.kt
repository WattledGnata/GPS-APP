package com.blazepush.simulator.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 速度控制器
 * 负责根据不同模式计算当前速度
 */
class SpeedController {

    var currentSpeed: Float = 0f
        private set

    var mode: SpeedMode = SpeedMode.STATIC
        private set

    var targetSpeed: Float = 60f
        private set

    var acceleration: Float = 2.0f // m/s²
        private set

    private var waveformPhase: Float = 0f
    private var lastUpdateTime: Long = 0

    /**
     * 设置速度模式
     */
    fun setMode(mode: SpeedMode) {
        this.mode = mode
        when (mode) {
            SpeedMode.STATIC -> {
                targetSpeed = 0f
            }
            SpeedMode.CONSTANT, SpeedMode.ACCELERATION, SpeedMode.DECELERATION -> {
                // 保持当前目标速度
            }
            SpeedMode.WAVEFORM, SpeedMode.REALISTIC -> {
                // 波形和真实模式使用目标速度作为基准
            }
            SpeedMode.CUSTOM -> {
                // 自定义模式
            }
        }
    }

    /**
     * 设置目标速度
     */
    fun setTargetSpeed(speed: Float) {
        this.targetSpeed = speed.coerceIn(0f, 300f)
    }

    /**
     * 设置加速度（m/s²）
     */
    fun setAcceleration(acc: Float) {
        this.acceleration = acc.coerceIn(0.1f, 10f)
    }

    /**
     * 更新速度
     * @param currentTimeMillis 当前时间（毫秒）
     * @return 更新后的速度
     */
    fun updateSpeed(currentTimeMillis: Long): Float {
        if (lastUpdateTime == 0L) {
            lastUpdateTime = currentTimeMillis
            return currentSpeed
        }

        val deltaTime = (currentTimeMillis - lastUpdateTime) / 1000f // 秒
        lastUpdateTime = currentTimeMillis

        currentSpeed = when (mode) {
            SpeedMode.STATIC -> {
                0f
            }

            SpeedMode.CONSTANT -> {
                targetSpeed
            }

            SpeedMode.ACCELERATION -> {
                val deltaV = acceleration * deltaTime * 3.6f // m/s转km/h
                val newSpeed = currentSpeed + deltaV
                min(newSpeed, targetSpeed)
            }

            SpeedMode.DECELERATION -> {
                val deltaV = acceleration * deltaTime * 3.6f
                val newSpeed = currentSpeed - deltaV
                max(newSpeed, targetSpeed)
            }

            SpeedMode.WAVEFORM -> {
                // 正弦波：目标速度 ± 30%
                waveformPhase += deltaTime * 2f // 波形速度
                val amplitude = targetSpeed * 0.3f
                targetSpeed + sin(waveformPhase) * amplitude
            }

            SpeedMode.REALISTIC -> {
                // 真实驾驶：随机加减速
                if (Random.nextFloat() < 0.1f) { // 10%概率改变速度
                    val change = Random.nextFloat() * 5f - 2.5f // -2.5到+2.5 km/h
                    val newSpeed = currentSpeed + change
                    newSpeed.coerceIn(targetSpeed * 0.7f, targetSpeed * 1.3f)
                } else {
                    currentSpeed
                }
            }

            SpeedMode.CUSTOM -> {
                // 自定义模式：暂时使用恒定速度
                targetSpeed
            }
        }

        // 确保速度在合理范围内
        currentSpeed = currentSpeed.coerceIn(0f, 300f)

        return currentSpeed
    }

    /**
     * 重置控制器
     */
    fun reset() {
        currentSpeed = 0f
        mode = SpeedMode.STATIC
        targetSpeed = 60f
        acceleration = 2.0f
        waveformPhase = 0f
        lastUpdateTime = 0
    }

    /**
     * 获取当前状态描述
     */
    fun getStatusDescription(): String {
        return when (mode) {
            SpeedMode.STATIC -> "静止 (0 km/h)"
            SpeedMode.CONSTANT -> "恒定 ${"%.1f".format(currentSpeed)} km/h"
            SpeedMode.ACCELERATION -> "加速到 ${"%.1f".format(targetSpeed)} km/h"
            SpeedMode.DECELERATION -> "减速到 ${"%.1f".format(targetSpeed)} km/h"
            SpeedMode.WAVEFORM -> "波形 (基准 ${"%.1f".format(targetSpeed)} km/h)"
            SpeedMode.REALISTIC -> "真实驾驶 (${"%.1f".format(currentSpeed)} km/h)"
            SpeedMode.CUSTOM -> "自定义"
        }
    }
}
