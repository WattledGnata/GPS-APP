// @IgnoreFormatCheck
// 理由：本文件含 legacy 格式违规（class comment 缺 @author/@description/@date /
//       applyReplaySample 等 public fun 缺 multi-line comment block / trailing newline）。
//       本战役 D 尾巴 A16b R2 只改 altitude 编码 3 行，补齐全部 legacy 违规远超 R2 scope。
//       对齐 `core/bluetooth/.../parser/RaceChronoParser.kt` 先例（评审方 2026-04-24
//       commit 阶段 B 方案批准）。
package com.blazepush.simulator.data

import com.blazepush.simulator.data.replay.ReplaySample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * GPS数据生成器
 * 负责生成符合RaceChrono协议的GPS数据
 *
 * 时间源规范（fix-laptime-clock-source-integrity）：
 * - 非 replay 场景：使用构造时注入的 [clock] 会话相对单调时钟
 *   （生产默认 `SystemClock.elapsedRealtime()`，测试可注入 FakeClock）
 * - Replay 场景：严格使用 [replayTimestampMillis]，未调用
 *   [applyReplaySample] 时立即抛 [IllegalStateException]，**不允许** fallback
 *   到任何本地时钟
 * - 整个类 **不得** 调用 `System.currentTimeMillis()`
 */
class GpsDataGenerator(
    private val scenario: TestScenario = TestScenario.STATIC,
    private var frequency: Int = 10,
    private var initialSpeed: Float = 0f,
    private var satellites: Int = 12,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
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
    private var syncCounter = 0
    private var bearing = 0.0f
    private var altitude = 100.0f
    private var hdop = 1.0f
    private var vdop = 1.0f
    private var replayTimestampMillis: Long? = null

    /**
     * 会话启动时刻（来自注入 [clock]）——非 replay 场景下，[currentTimestampMillis]
     * 以此为原点计算"会话相对时钟"。
     */
    private var sessionStartRealtimeMillis: Long = clock()

    /**
     * 生成20字节GPS主数据（ESP32协议格式）
     * 格式: [ sync(3bit)|timeHigh(5bit) | timeMid(8bit) | timeLow(8bit) | fixQuality(2bit)|satellites(6bit) | lat(4) | lon(4) | alt(2) | speed(2) | bearing(2) | hdop(1) | vdop(1) ]
     */
    fun generateGpsMainData(): ByteArray {
        val data = ByteArray(20)

        // Byte 0: syncBits(高3位) | time高5位
        val timeMs = ((currentTimestampMillis() % 3_600_000L).toInt()) / 2
        val timeHigh = (timeMs shr 16)
        data[0] = (((syncCounter and 0x07) shl 5) or (timeHigh and 0x1F)).toByte()

        // Byte 1-2: time 中低16位
        data[1] = ((timeMs shr 8) and 0xFF).toByte()
        data[2] = (timeMs and 0xFF).toByte()

        // Byte 3: fixQuality(高2位) | satellites(低6位)
        val fixQuality = 1 // GPS定位
        data[3] = (((fixQuality shl 6) or (satellites and 0x3F)) and 0xFF).toByte()

        // Byte 4-7: latitude (big endian, 度 * 10,000,000)
        val latInt = (currentLatitude * 10000000.0).toInt()
        data[4] = ((latInt shr 24) and 0xFF).toByte()
        data[5] = ((latInt shr 16) and 0xFF).toByte()
        data[6] = ((latInt shr 8) and 0xFF).toByte()
        data[7] = (latInt and 0xFF).toByte()

        // Byte 8-11: longitude (big endian, 度 * 10,000,000)
        val lonInt = (currentLongitude * 10000000.0).toInt()
        data[8] = ((lonInt shr 24) and 0xFF).toByte()
        data[9] = ((lonInt shr 16) and 0xFF).toByte()
        data[10] = ((lonInt shr 8) and 0xFF).toByte()
        data[11] = (lonInt and 0xFF).toByte()

        // Byte 12-13: altitude (A16b R2: 按 ino 真实编码对齐，与 R1 parser 对称)
        //   ino `RaceChrono_ESP32_M9N.ino:294-298`:
        //     if (alt < 6053.5): raw = ((int)((alt+500)*10)) & 0x7FFF  (bit15=0，精度 0.1m)
        //     else:              raw = (((int)(alt+500)) & 0x7FFF) | 0x8000  (bit15=1，不乘 10，精度 1m)
        //   v1 错误：判定条件用 raw<=32767（等价于 alt<2776.7m），bit15=1 公式仍乘 10，与 ino 不对齐
        //   [2776.7m, 6053.5m] 区间 ino 自身 & 0x7FFF 截断不可逆（A16b R5 Non-goal 契约，
        //   simulator 与 ino 行为一致，parser 解截断后的错值不报错）
        val altMeters = altitude.toDouble()
        val altEncoded = if (altMeters < 6053.5) {
            (((altMeters + 500.0) * 10.0).toInt()) and 0x7FFF
        } else {
            ((((altMeters + 500.0).toInt()) and 0x7FFF)) or 0x8000
        }
        data[12] = ((altEncoded shr 8) and 0xFF).toByte()
        data[13] = (altEncoded and 0xFF).toByte()

        // Byte 14-15: speed (special encoding: km/h<655.35用*100，>=用*10，高位置1)
        val speedKmh = currentSpeed.toDouble()
        val speedEncoded = if (speedKmh < 655.35) {
            ((speedKmh * 100.0).toInt() and 0x7FFF)
        } else {
            (((speedKmh * 10.0).toInt() and 0x7FFF) or 0x8000)
        }
        data[14] = ((speedEncoded shr 8) and 0xFF).toByte()
        data[15] = (speedEncoded and 0xFF).toByte()

        // Byte 16-17: bearing (big endian uint16, 度 * 100)
        val bearingInt = (bearing * 100.0).toInt()
        data[16] = ((bearingInt shr 8) and 0xFF).toByte()
        data[17] = (bearingInt and 0xFF).toByte()

        // Byte 18: HDOP (0.1单位)
        data[18] = ((hdop * 10.0).toInt() and 0xFF).toByte()

        // Byte 19: VDOP (0.1单位)
        data[19] = ((vdop * 10.0).toInt() and 0xFF).toByte()

        return data
    }

    /**
     * 协议编码用的时间源。
     *
     * - [TestScenario.REAL_TRACK_REPLAY]：`replayTimestampMillis`，缺失立即抛异常（不得 fallback）
     * - 其他场景：`clock() - sessionStartRealtimeMillis`（会话相对单调毫秒）
     *
     * 永远 **不得** 返回 `System.currentTimeMillis()`。
     */
    private fun currentTimestampMillis(): Long {
        return if (scenario == TestScenario.REAL_TRACK_REPLAY) {
            replayTimestampMillis
                ?: throw IllegalStateException(
                    "Replay sample missing timestamp - did you forget to call applyReplaySample()?"
                )
        } else {
            clock() - sessionStartRealtimeMillis
        }
    }

    /**
     * 同步计数器维护(2026-06-05 协议语义修正)。
     *
     * syncBits 是**时间基准版本号**:时间包声明"syncBits=N 对应小时起点 H",主包凭相同
     * syncBits 与 reference 配对——它 MUST 仅在小时翻转(dateAndHour 变化)时递增,
     * **不是帧计数器**。旧实现每帧 ++:接收端 reference 一旦滞后,主包 syncBits 在
     * 3bit 空间环回,每 8 帧才撞上一次 → 接收端 ~12% 帧 isTimeSynced(5Hz 下每 1.7s
     * 一帧),触发链/滤波窗/preTriggerBuffer 全被未同步守卫拒收(2026-06-05 凌晨
     * speedPipeline 逐帧日志实锤)。真机 blazepush 固件 syncBits 小时级恒定,从不暴露。
     */
    private var lastDateAndHour = -1

    private fun updateSyncCounterOnHourChange() {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = currentTimestampMillis()
        }
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val dateAndHour = (month - 1) * 744 + (day - 1) * 24 + hour
        if (lastDateAndHour != -1 && dateAndHour != lastDateAndHour) {
            syncCounter = (syncCounter + 1) and 0x07
        }
        lastDateAndHour = dateAndHour
    }

    /**
     * 生成3字节GPS时间数据（ESP32协议格式）
     * 格式: [ sync(3bit)|dateHigh(5bit) | dateMid(8bit) | dateLow(8bit) ]
     * dateAndHour = yearOffset*8928 + (month-1)*744 + (day-1)*24 + hour
     *
     * 注：simulator 以 `yearOffset = 0` 编码（虚拟 2000-01-01 起点），**不反映真实日历**，
     * 接收端 parser 不得据此做真实日历业务判断。
     */
    fun generateGpsTimeData(): ByteArray {
        val data = ByteArray(3)

        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = currentTimestampMillis()
        }
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)

        // 固定 yearOffset = 0（相对 2000-01-01 起点，不反映真实日历）
        val yearOffset = 0
        val dateAndHour = yearOffset * 8928 + (month - 1) * 744 + (day - 1) * 24 + hour

        data[0] = (((syncCounter and 0x07) shl 5) or (dateAndHour shr 16) and 0x1F).toByte()
        data[1] = ((dateAndHour shr 8) and 0xFF).toByte()
        data[2] = (dateAndHour and 0xFF).toByte()

        return data
    }

    /**
     * 日志记录传输的数据
     */
    private fun logTransmittedData(mainData: ByteArray, timeData: ByteArray) {
        val mainHex = mainData.joinToString("") { "%02X".format(it) }
        val timeHex = timeData.joinToString("") { "%02X".format(it) }
        android.util.Log.d("GpsDataGenerator", "Transmitting - Main: $mainHex, Time: $timeHex")

        // 解析关键字段用于日志（20字节格式）
        val sync = (mainData[0].toInt() shr 5) and 0x07
        val fixAndSat = mainData[3].toInt() and 0xFF
        val fixQuality = (fixAndSat shr 6) and 0x03
        val sats = fixAndSat and 0x3F

        val latInt = ((mainData[4].toInt() and 0xFF) shl 24) or
                     ((mainData[5].toInt() and 0xFF) shl 16) or
                     ((mainData[6].toInt() and 0xFF) shl 8) or
                     (mainData[7].toInt() and 0xFF)
        val lat = latInt / 10000000.0

        // speed 解码（特殊编码）
        val speedEncoded = ((mainData[14].toInt() and 0xFF) shl 8) or (mainData[15].toInt() and 0xFF)
        val speed = if (speedEncoded and 0x8000 != 0) {
            (speedEncoded and 0x7FFF) / 10.0
        } else {
            (speedEncoded and 0x7FFF) / 100.0
        }

        android.util.Log.d("GpsDataGenerator", "Fields - Sync=$sync, Fix=$fixQuality, Sats=$sats, Lat=$lat, Speed=$speed km/h, Freq=${frequency}Hz")
    }

    /**
     * 设置当前速度（由ViewModel调用）
     */
    fun setCurrentSpeed(speed: Float) {
        currentSpeed = speed
    }

    /**
     * 设置当前位置（由ViewModel调用）
     */
    fun setCurrentPosition(lat: Double, lon: Double) {
        currentLatitude = lat
        currentLongitude = lon
    }

    fun applyReplaySample(sample: ReplaySample) {
        currentLatitude = sample.latitude
        currentLongitude = sample.longitude
        currentSpeed = sample.speedKmh.toFloat()
        bearing = sample.bearingDegrees.toFloat()
        satellites = sample.satellites
        altitude = sample.altitudeMeters.toFloat()
        hdop = sample.hdop.toFloat()
        vdop = sample.altitudePrecisionMeters.toFloat().coerceAtLeast(0f)
        replayTimestampMillis = sample.timestampMillis
    }

    /**
     * 更新模拟状态
     */
    private fun updateSimulation() {
        updateSyncCounterOnHourChange()

        // 简化：使用外部设置的速度，不在这里计算
        // 速度由SpeedController控制，通过setCurrentSpeed设置

        // 更新位置 (简化：根据速度移动)
        val speedMs = currentSpeed / 3.6f // km/h转m/s
        currentLatitude += (speedMs / 111320.0) // 1度纬度约111.32km
        currentLongitude += (speedMs / (111320.0 * kotlin.math.cos(kotlin.math.PI * currentLatitude / 180.0)))

        // 更新方位角 (简化：向东北方向移动)
        bearing = 45.0f
    }

    /**
     * 启动数据流
     */
    fun startGpsDataStream(): Flow<Pair<ByteArray, ByteArray>> = flow {
        while (true) {
            updateSimulation()
            val mainData = generateGpsMainData()
            val timeData = generateGpsTimeData()

            // 添加详细日志
            logTransmittedData(mainData, timeData)

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
     * 重置模拟状态。
     *
     * 同时重置会话相对时钟起点（非 replay 场景下新一轮 `timeSinceHourStart` 从 0 开始）
     * 并清零 `replayTimestampMillis`（replay 场景下下次 generate 前必须重新 [applyReplaySample]，
     * 否则抛 [IllegalStateException]）。
     */
    fun reset() {
        currentSpeed = initialSpeed
        currentLatitude = 60.1725
        currentLongitude = 24.9375
        replayTimestampMillis = null
        sessionStartRealtimeMillis = clock()
    }
}
