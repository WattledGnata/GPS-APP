// @IgnoreFormatCheck
// 理由：本文件含 legacy 格式违规（class comment / `reference!!` 非空断言 /
//       import-order / trailing newline）。`reference!!` 的非空断言是战役 A 时钟源
//       修复保留的契约——与 `syncedNow` 守卫同源；rename 掉会改判断语义。其他
//       legacy 违规 rename 扩散过大，超出战役 G R4 scope。评审方 2026-04-24
//       commit 阶段 B 方案批准此 ignore。
package com.blazepush.core.bluetooth.parser

import android.util.Log
import com.blazepush.core.domain.model.GpsData
import java.util.Collections
import java.util.ArrayList

/**
 * RaceChrono GPS Protocol Parser
 * Handles parsing of raw byte arrays into GpsData objects.
 * Maintains state for frequency calculation (A41 已清除内部 tracking 死状态 2026-04-24).
 *
 * Protocol: ESP32 20-byte GPS Main Data + 3-byte GPS Time Data
 * See: docs/RaceChrono_BLE_Protocol.md
 */
class RaceChronoParser {

    companion object {
        private const val TAG = "RaceChronoParser"
    }

    // GPS frequency calculation
    private val gpsDataTimestamps = Collections.synchronizedList(ArrayList<Long>())
    private val timeWindowMs = 1000 // 1 second time window
    private var lastFrequencyUpdateTime = 0L
    private val updateIntervalMs = 500 // Update frequency display every 500ms
    private var gpsFrequency = 0.0 // Current GPS frequency in Hz

    private var protocolTimeReference: ProtocolTimeReference? = null

    private data class ProtocolTimeReference(
        val syncBits: Int,
        val hourStartMillis: Long
    )

    /**
     * Resets all tracking state (distance, time, frequency)
     */
    fun reset() {
        gpsDataTimestamps.clear()
        gpsFrequency = 0.0
        protocolTimeReference = null
        // A8 / opsx code review C.4：`isTimeSynced` 不再作 parser 私有字段，
        // 而是从 `protocolTimeReference` 单源派生（reset 后 reference = null →
        // 下一次 parseGpsData 必判未同步 → 写 sentinel + isTimeSynced=false）。
    }

    /**
     * Parses GPS Time characteristic data (3 bytes)
     *
     * Format:
     *   Byte 0: syncBits[7:5] | dateAndHour[4:0]
     *   Byte 1: dateAndHour[15:8]
     *   Byte 2: dateAndHour[7:0]
     *
     *   dateAndHour = (year - 2000) * 8928 + (month - 1) * 744 + (day - 1) * 24 + hour
     */
    fun parseGpsTimeData(data: ByteArray, currentData: GpsData): GpsData {
        if (data.size < 3) {
            Log.e(TAG, "Invalid GPS time data size: ${data.size}, expected 3")
            // A25：时间包短包失败信号通过 errorMessage 字段上抛给 BluetoothDataSource，
            //      与主包路径对称 —— 让下游失败分支显式置 isConnected=false
            return currentData.copy(errorMessage = "short-packet")
        }

        return try {
            val syncBits = (data[0].toInt() shr 5) and 0x07
            val dateAndHour = ((data[0].toInt() and 0x1F) shl 16) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    (data[2].toInt() and 0xFF)

            val yearOffset = dateAndHour / 8928
            val remainder = dateAndHour % 8928
            val month = remainder / 744
            val remainder2 = remainder % 744
            val day = remainder2 / 24
            val hour = remainder2 % 24
            val year = 2000 + yearOffset

            val calendar = java.util.Calendar.getInstance().apply {
                clear()
                set(year, month, day + 1, hour, 0, 0)
            }
            protocolTimeReference = ProtocolTimeReference(
                syncBits = syncBits,
                hourStartMillis = calendar.timeInMillis
            )

//            Log.d(TAG, "GPS Time: $year-${month + 1}-${day + 1} $hour:xx (sync=$syncBits)")

            // A26（refactor-parser-internal-state-cleanup R1）：时间包 MUST NOT 写
            //       `isTestReady` —— 唯一写入源是主包 `parseGpsData` 的 `satellites >= 6
            //       && hdop < 2.0` 判定。`protocolTimeReference` 已在上方完成时间包
            //       真正职责；时间包到达本身不代表"定位质量满足测试就绪门槛"。
            // A25 契约闭合：成功路径仍显式清 errorMessage，避免前帧失败残留让下游
            //               把"本帧 parse 成功"误解释成"最近一次 parse 失败"
            currentData.copy(errorMessage = null)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GPS time data", e)
            // A25：时间包解析异常失败信号通过 errorMessage 上抛
            currentData.copy(errorMessage = "parse-error: ${e.message}")
        }
    }

    /**
     * Parses GPS Main characteristic data (20 bytes - ESP32 protocol)
     *
     * Format:
     *   Byte 0:   syncBits[7:5] | timeSinceHourStart[4:0]
     *   Byte 1:   timeSinceHourStart[15:8]
     *   Byte 2:   timeSinceHourStart[7:0]
     *   Byte 3:   fixQuality[7:6] | satellites[5:0]
     *   Byte 4-7: latitude (big endian int32, degrees * 10,000,000)
     *   Byte 8-11: longitude (big endian int32, degrees * 10,000,000)
     *   Byte 12-13: altitude (big endian uint16, special encoding)
     *   Byte 14-15: speed (big endian uint16, special encoding)
     *   Byte 16-17: bearing (big endian uint16, degrees * 100)
     *   Byte 18: HDOP (raw value * 0.1)
     *   Byte 19: VDOP (raw value * 0.1)
     *
     * Altitude encoding (A16b 对齐 ino `RaceChrono_ESP32_M9N.ino:294-298`)：
     *   - Bit 15 = 0（低海拔，精度 0.1m）：alt = (raw & 0x7FFF) / 10.0 - 500.0
     *   - Bit 15 = 1（高海拔，精度 1m，发送端 alt >= 6053.5m 进入此分支且不乘 10）：
     *     alt = (raw & 0x7FFF).toDouble() - 500.0
     *
     * Speed encoding:
     *   - Bit 15 = 0: speed = raw / 100.0
     *   - Bit 15 = 1: speed = ((raw & 0x7FFF) * 10) / 100.0
     */
    fun parseGpsData(data: ByteArray, inputData: GpsData, shouldLog: Boolean = false): GpsData {
        var currentData = inputData

        if (data.size < 20) {
            // A25：短包失败信号通过 errorMessage 字段上抛给 BluetoothDataSource，
            //      让其不把 isConnected 强置为 true（语义收敛到 "GATT 连上 + parse 成功"）
            return currentData.copy(errorMessage = "short-packet")
        }

        try {
            if (shouldLog) {
                val hexDump = data.joinToString("") { "%02X".format(it) }
                Log.d(TAG, "Raw GPS Data (20 bytes): $hexDump")
            }

            // Byte 0: sync + time high
            val syncBits = (data[0].toInt() shr 5) and 0x07
            val timeHigh = data[0].toInt() and 0x1F
            val timeMid = data[1].toInt() and 0xFF
            val timeLow = data[2].toInt() and 0xFF
            val timeSinceHourStart = ((timeHigh shl 16) or (timeMid shl 8) or timeLow) * 2  // 每个单位=2ms

            // Byte 3: fix + satellites
            val fixQuality = (data[3].toInt() shr 6) and 0x03
            val satellites = data[3].toInt() and 0x3F

            // Byte 4-7: latitude (big endian int32, 度 * 10,000,000)
            val latInt = ((data[4].toInt() and 0xFF) shl 24) or
                    ((data[5].toInt() and 0xFF) shl 16) or
                    ((data[6].toInt() and 0xFF) shl 8) or
                    (data[7].toInt() and 0xFF)
            // A16a: 协议 / ESP32 ino 明确 lat 是 signed int32。`.toLong() and 0xFFFFFFFFL`
            //       会把 signed 抹成 unsigned，所有南纬解成 +[180°, 400°] 大数。Kotlin
            //       Int / Double 除法按 IEEE 754 保留符号位扩展为 Double，结果 signed。
            val currentLatitude = latInt / 10_000_000.0

            // Byte 8-11: longitude (big endian int32, 度 * 10,000,000)
            val lonInt = ((data[8].toInt() and 0xFF) shl 24) or
                    ((data[9].toInt() and 0xFF) shl 16) or
                    ((data[10].toInt() and 0xFF) shl 8) or
                    (data[11].toInt() and 0xFF)
            // A16a: 协议 / ESP32 ino 明确 lon 是 signed int32。参考 latitude 同源修复。
            val currentLongitude = lonInt / 10_000_000.0

            // Byte 12-13: altitude special encoding (big endian uint16)
            // A16b：两分支公式对称于 ESP32 ino `RaceChrono_ESP32_M9N.ino:294-298` 真实编码。
            //       v1 公式 `raw/100 - 500` / `raw*10/100 - 500` 与 ino 不对称，生产中所有
            //       altitude 解成 -500m 附近负值（audit § 6 / § 10 / 2026-04-24）。
            val altRaw = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)
            val altitudeMeters = if ((altRaw and 0x8000) == 0) {
                // bit15=0 (低海拔)：ino 编码 raw = ((alt+500)*10) & 0x7FFF，逆运算 alt = raw/10 - 500
                // 精度 0.1m，范围 -500m ~ 2776.7m；
                // [2776.7m, 6053.5m] 区间 ino 自身 & 0x7FFF 截断不可逆（A16b R1 Scenario 5
                // Non-goal 契约），parser 单边无法恢复，解得截断值不报错。
                (altRaw and 0x7FFF) / 10.0 - 500.0
            } else {
                // bit15=1 (高海拔)：ino 编码 raw = (alt+500) & 0x7FFF | 0x8000（不乘 10）
                // 逆运算 alt = (raw & 0x7FFF) - 500。发送端 `alt >= 6053.5m` 触发 bit15=1；
                // 解码值精度 1m，最小可回读整数 6053m（量化回读）。
                (altRaw and 0x7FFF).toDouble() - 500.0
            }

            // Byte 14-15: speed special encoding (big endian uint16)
            val speedRaw = ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)
            val speedKmh = if ((speedRaw and 0x8000) == 0) {
                (speedRaw and 0x7FFF) / 100.0
            } else {
                ((speedRaw and 0x7FFF) * 10) / 100.0
            }

            // Byte 16-17: bearing (big endian uint16, 度 * 100)
            val bearingInt = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
            val bearingDegrees = bearingInt / 100.0

            // Byte 18: HDOP (0.1单位)
            val hdop = (data[18].toInt() and 0xFF) / 10.0

            // Byte 19: VDOP (0.1单位)
            val vdop = (data[19].toInt() and 0xFF) / 10.0

            // Frequency Calculation (Non-Critical)
            try {
                val currentTime = System.currentTimeMillis()
                synchronized(gpsDataTimestamps) {
                    gpsDataTimestamps.add(currentTime)
                    val cutoffTime = currentTime - timeWindowMs
                    gpsDataTimestamps.removeAll { it < cutoffTime }

                    if (currentTime - lastFrequencyUpdateTime >= updateIntervalMs) {
                        gpsFrequency = gpsDataTimestamps.size.toDouble()
                        lastFrequencyUpdateTime = currentTime
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating frequency", e)
            }

            // 协议时间对齐判定（A8 / opsx code review C.4：从单源 `protocolTimeReference` 派生）：
            //   - protocolTimeReference == null：从未收到过时间包 → 未同步
            //   - syncBits 失配：当前帧与最近一次时间包不对齐 → 未同步
            // 未同步时写入 sentinel (Long.MIN_VALUE)，严禁 fallback 到本地系统时钟。
            val reference = protocolTimeReference
            val syncedNow = reference != null && reference.syncBits == syncBits
            val protocolTimestamp = if (syncedNow) {
                reference!!.hourStartMillis + timeSinceHourStart
            } else {
                Long.MIN_VALUE
            }

            // Update Current Data
            // A25 契约闭合：主包成功路径显式清 errorMessage，避免前帧失败残留（例如
            //               上一帧短包设的 "short-packet"）被带过来让下游把本帧
            //               parse 成功误走失败分支置 isConnected=false。
            currentData = currentData.copy(
                timestamp = protocolTimestamp,
                speed = speedKmh,
                latitude = currentLatitude,
                longitude = currentLongitude,
                altitude = altitudeMeters,
                bearing = bearingDegrees,
                satelliteCount = satellites,
                hdop = hdop,
                vdop = vdop,
                frequency = gpsFrequency,
                isTestReady = satellites >= 6 && hdop < 2.0,
                fixQuality = fixQuality,
                isTimeSynced = syncedNow,
                errorMessage = null
            )

            if (shouldLog) {
                Log.d(TAG, "Parsed: Sync=$syncBits, Time=$timeSinceHourStart, " +
                        "Fix=$fixQuality, Sats=$satellites, " +
                        "Lat=${"%.7f".format(currentLatitude)}, " +
                        "Lon=${"%.7f".format(currentLongitude)}, " +
                        "Alt=${"%.1f".format(altitudeMeters)}m, " +
                        "Speed=${"%.1f".format(speedKmh)}km/h, " +
                        "Bearing=${"%.1f".format(bearingDegrees)}, " +
                        "HDOP=$hdop, VDOP=$vdop")
            }

            return currentData

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GPS data", e)
            // A25：解析异常失败信号通过 errorMessage 字段上抛给 BluetoothDataSource
            return currentData.copy(errorMessage = "parse-error: ${e.message}")
        }
    }
}
