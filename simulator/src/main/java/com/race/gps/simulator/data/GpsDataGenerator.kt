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
    private var syncCounter = 0
    private var bearing = 0.0f
    private var altitude = 100.0f
    private var hdop = 1.0f
    private var vdop = 1.0f

    /**
     * 生成28字节GPS主数据（RaceChrono协议标准格式）
     * 格式: [ sync(1) | time(4) | fix+sats(1) | lat(4) | lon(4) | alt(4) | speed(4) | bearing(4) | hdop(1) | vdop(1) ]
     */
    fun generateGpsMainData(): ByteArray {
        val data = ByteArray(28)

        // Byte 0: 同步位 (低3位，0-7循环)
        data[0] = (syncCounter and 0x07).toByte()

        // Byte 1-4: 小时开始时间 (big endian, 毫秒)
        val timeMs = getTimeSinceHourStart()
        data[1] = ((timeMs shr 24) and 0xFF).toByte()
        data[2] = ((timeMs shr 16) and 0xFF).toByte()
        data[3] = ((timeMs shr 8) and 0xFF).toByte()
        data[4] = (timeMs and 0xFF).toByte()

        // Byte 5: 定位质量(高2位) + 卫星数(低6位)
        val fixQuality = 1 // GPS定位
        val fixAndSat = ((fixQuality shl 6) or (satellites and 0x3F))
        data[5] = fixAndSat.toByte()

        // Byte 6-9: 纬度 (big endian, 度 * 10,000,000)
        val latInt = (currentLatitude * 10000000.0).toInt()
        data[6] = ((latInt shr 24) and 0xFF).toByte()
        data[7] = ((latInt shr 16) and 0xFF).toByte()
        data[8] = ((latInt shr 8) and 0xFF).toByte()
        data[9] = (latInt and 0xFF).toByte()

        // Byte 10-13: 经度 (big endian, 度 * 10,000,000)
        val lonInt = (currentLongitude * 10000000.0).toInt()
        data[10] = ((lonInt shr 24) and 0xFF).toByte()
        data[11] = ((lonInt shr 16) and 0xFF).toByte()
        data[12] = ((lonInt shr 8) and 0xFF).toByte()
        data[13] = (lonInt and 0xFF).toByte()

        // Byte 14-17: 海拔 (big endian, 米 * 100)
        val altInt = (altitude * 100.0).toInt()
        data[14] = ((altInt shr 24) and 0xFF).toByte()
        data[15] = ((altInt shr 16) and 0xFF).toByte()
        data[16] = ((altInt shr 8) and 0xFF).toByte()
        data[17] = (altInt and 0xFF).toByte()

        // Byte 18-21: 速度 (big endian, km/h * 100)
        val speedInt = (currentSpeed * 100.0).toInt()
        data[18] = ((speedInt shr 24) and 0xFF).toByte()
        data[19] = ((speedInt shr 16) and 0xFF).toByte()
        data[20] = ((speedInt shr 8) and 0xFF).toByte()
        data[21] = (speedInt and 0xFF).toByte()

        // Byte 22-25: 方位角 (big endian, 度 * 100)
        val bearingInt = (bearing * 100.0).toInt()
        data[22] = ((bearingInt shr 24) and 0xFF).toByte()
        data[23] = ((bearingInt shr 16) and 0xFF).toByte()
        data[24] = ((bearingInt shr 8) and 0xFF).toByte()
        data[25] = (bearingInt and 0xFF).toByte()

        // Byte 26: HDOP (0.1单位)
        data[26] = ((hdop * 10.0).toInt().toByte())

        // Byte 27: VDOP (0.1单位)
        data[27] = ((vdop * 10.0).toInt().toByte())

        return data
    }

    /**
     * 获取从小时开始的毫秒数
     */
    private fun getTimeSinceHourStart(): Int {
        val now = System.currentTimeMillis()
        val hourStart = (now / 3600000L) * 3600000L
        return (now - hourStart).toInt()
    }

    /**
     * 增加同步计数器
     */
    private fun incrementSyncCounter() {
        syncCounter = (syncCounter + 1) and 0x07
    }

    /**
     * 生成3字节GPS时间数据
     * 格式: [ sync+date(1) | time(2) ]
     */
    fun generateGpsTimeData(): ByteArray {
        val data = ByteArray(3)

        val now = System.currentTimeMillis()
        val dateAndHour = ((now / 3600000L) % 2048).toInt() // 取日期和小时部分
        val milliseconds = (now % 60000L).toInt() // 取分钟内的毫秒数

        // Byte 0: 同步位(高3位) + 日期和小时(低13位)
        val syncAndDate = ((syncCounter shl 5) or (dateAndHour and 0x1FFF))
        data[0] = ((syncAndDate shr 8) and 0xFF).toByte()
        data[1] = (syncAndDate and 0xFF).toByte()

        // Byte 2: 毫秒低8位
        data[2] = (milliseconds and 0xFF).toByte()

        return data
    }

    /**
     * 日志记录传输的数据
     */
    private fun logTransmittedData(mainData: ByteArray, timeData: ByteArray) {
        val mainHex = mainData.joinToString("") { "%02X".format(it) }
        val timeHex = timeData.joinToString("") { "%02X".format(it) }
        android.util.Log.d("GpsDataGenerator", "Transmitting - Main: $mainHex, Time: $timeHex")

        // 解析关键字段用于日志
        val sync = mainData[0].toInt() and 0x07
        val fixAndSat = mainData[5].toInt() and 0xFF
        val fixQuality = (fixAndSat shr 6) and 0x03
        val sats = fixAndSat and 0x3F

        val latInt = ((mainData[6].toInt() and 0xFF) shl 24) or
                     ((mainData[7].toInt() and 0xFF) shl 16) or
                     ((mainData[8].toInt() and 0xFF) shl 8) or
                     (mainData[9].toInt() and 0xFF)
        val lat = latInt / 10000000.0

        val speedInt = ((mainData[18].toInt() and 0xFF) shl 24) or
                       ((mainData[19].toInt() and 0xFF) shl 16) or
                       ((mainData[20].toInt() and 0xFF) shl 8) or
                       (mainData[21].toInt() and 0xFF)
        val speed = speedInt / 100.0

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

    /**
     * 更新模拟状态
     */
    private fun updateSimulation() {
        incrementSyncCounter()

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
     * 重置模拟状态
     */
    fun reset() {
        currentSpeed = initialSpeed
        currentLatitude = 60.1725
        currentLongitude = 24.9375
    }
}
