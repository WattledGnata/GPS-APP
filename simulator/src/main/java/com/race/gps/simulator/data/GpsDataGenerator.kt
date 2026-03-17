package com.race.gps.simulator.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.max
import kotlin.math.min

/**
 * GPS数据生成器
 * 负责生成符合RaceChrono协议的GPS数据
 */
class GpsDataGenerator(
    private val scenario: TestScenario = TestScenario.STATIC,
    private var frequency: Int = 10,
    private var initialSpeed: Float = 0f,
    private var satellites: Int = 12
) {

    companion object {
        // RaceChrono GPS UUIDs
        const val SERVICE_UUID = "00001ff8-0000-1000-8000-00805f9b34fb"
        const val GPS_MAIN_DATA_UUID = "00000003-0000-1000-8000-00805f9b34fb"
        const val GPS_TIME_DATA_UUID = "00000004-0000-1000-8000-00805f9b34fb"
    }

    private var currentSpeed = initialSpeed
    private var currentLatitude = 60.1725
    private var currentLongitude = 24.9375

    /**
     * 生成20字节GPS主数据
     * 格式: [ satellites(1) | speed(2) | latitude(4) | longitude(4) | altitude(2) | distance(2) | acceleration(3) | unknown(2) ]
     */
    fun generateGpsMainData(): ByteArray {
        val data = ByteArray(20)

        // 卫星数量 (字节0)
        data[0] = satellites.toByte()

        // 速度 (字节1-2, 单位: 0.1 km/h, little-endian)
        val speedInt = (currentSpeed * 10).toInt()
        data[1] = (speedInt and 0xFF).toByte()
        data[2] = ((speedInt shr 8) and 0xFF).toByte()

        // 纬度 (字节3-6, 单位: 1e-7度, little-endian)
        val latInt = (currentLatitude * 1e7).toInt()
        data[3] = (latInt and 0xFF).toByte()
        data[4] = ((latInt shr 8) and 0xFF).toByte()
        data[5] = ((latInt shr 16) and 0xFF).toByte()
        data[6] = ((latInt shr 24) and 0xFF).toByte()

        // 经度 (字节7-10, 单位: 1e-7度, little-endian)
        val lonInt = (currentLongitude * 1e7).toInt()
        data[7] = (lonInt and 0xFF).toByte()
        data[8] = ((lonInt shr 8) and 0xFF).toByte()
        data[9] = ((lonInt shr 16) and 0xFF).toByte()
        data[10] = ((lonInt shr 24) and 0xFF).toByte()

        // 海拔 (字节11-12, 单位: 1m, little-endian)
        val altitude = 100 // 固定100米
        data[11] = (altitude and 0xFF).toByte()
        data[12] = ((altitude shr 8) and 0xFF).toByte()

        // 距离 (字节13-14, 单位: 1m, little-endian) - 简化为累加
        val distance = (currentSpeed * 0.27778).toInt() // km/h转m/s
        data[13] = (distance and 0xFF).toByte()
        data[14] = ((distance shr 8) and 0xFF).toByte()

        // 加速度 (字节15-17, 单位: 0.01g, little-endian)
        val acceleration = calculateAcceleration()
        data[15] = (acceleration and 0xFF).toByte()
        data[16] = ((acceleration shr 8) and 0xFF).toByte()
        data[17] = ((acceleration shr 16) and 0xFF).toByte()

        // 未知字段 (字节18-19)
        data[18] = 0x00
        data[19] = 0x00

        return data
    }

    /**
     * 生成3字节GPS时间数据
     * 格式: [ milliseconds(2) | seconds(1) ]
     */
    fun generateGpsTimeData(): ByteArray {
        val data = ByteArray(3)

        val now = System.currentTimeMillis()
        val seconds = (now / 1000 % 60).toInt()
        val milliseconds = (now % 1000).toInt()

        // 毫秒 (字节0-1, little-endian)
        data[0] = (milliseconds and 0xFF).toByte()
        data[1] = ((milliseconds shr 8) and 0xFF).toByte()

        // 秒 (字节2)
        data[2] = seconds.toByte()

        return data
    }

    /**
     * 计算加速度
     */
    private fun calculateAcceleration(): Int {
        return when (scenario) {
            TestScenario.STATIC -> 0
            TestScenario.ACCELERATION -> 98 // 0.98g
            TestScenario.BRAKING -> -196 // -1.96g
        }
    }

    /**
     * 更新模拟状态
     */
    private fun updateSimulation() {
        when (scenario) {
            TestScenario.STATIC -> {
                // 静态场景，保持当前速度
                currentSpeed = initialSpeed
            }
            TestScenario.ACCELERATION -> {
                // 加速场景，每秒增加10 km/h
                currentSpeed = min(currentSpeed + 10f, 100f)
            }
            TestScenario.BRAKING -> {
                // 刹车场景，每秒减少15 km/h
                currentSpeed = max(currentSpeed - 15f, 0f)
            }
        }

        // 更新位置 (简化：根据速度移动)
        val speedMs = currentSpeed / 3.6f // km/h转m/s
        currentLatitude += (speedMs / 111320.0) // 1度纬度约111.32km
        currentLongitude += (speedMs / (111320.0 * kotlin.math.cos(kotlin.math.PI * currentLatitude / 180.0)))
    }

    /**
     * 启动数据流
     */
    fun startGpsDataStream(): Flow<Pair<ByteArray, ByteArray>> = flow {
        while (true) {
            updateSimulation()
            val mainData = generateGpsMainData()
            val timeData = generateGpsTimeData()
            emit(Pair(mainData, timeData))
            delay((1000L / frequency).toLong())
        }
    }

    /**
     * 设置频率 (1-25 Hz)
     */
    fun setFrequency(hz: Int) {
        frequency = hz.coerceIn(1, 25)
    }

    /**
     * 设置卫星数量
     */
    fun setSatellites(count: Int) {
        satellites = count.coerceIn(4, 20)
    }

    /**
     * 重置模拟状态
     */
    fun reset() {
        currentSpeed = initialSpeed
        currentLatitude = 60.1725
        currentLongitude = 24.9375
    }
}
