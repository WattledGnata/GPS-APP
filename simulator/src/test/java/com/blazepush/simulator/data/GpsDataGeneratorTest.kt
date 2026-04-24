// @IgnoreFormatCheck
// 理由：本文件含 legacy 格式违规（class comment 缺 @author/@description/@date /
//       trailing newline）以及本轮 R2 新增的 generatesBytes_altitudeWithInoCompatibleEncoding
//       使用 `// ====` 段注释而非 `/** */` multi-line block。该段注释承载 Case 1/2/3
//       的 v1/v2 硬区分字节推导（含十六进制对照），改为 block 会破坏可读性。
//       对齐 `core/bluetooth/.../parser/RaceChronoParserTest.kt` 先例（评审方
//       2026-04-24 commit 阶段 B 方案批准）。
package com.blazepush.simulator.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GpsDataGenerator 时间源单源化测试（fix-laptime-clock-source-integrity 战役 C）。
 *
 * 锁定 spec `specs/laptime-clock-source/spec.md` 的 Requirement：
 * 发射端时间戳必须来自会话相对单调时钟或 replay 样本时间戳，严禁 fallback 到系统时钟。
 */
class GpsDataGeneratorTest {

    /**
     * Spec Scenario：非 replay 模式按会话相对单调时钟编码 timeSinceHourStart。
     *
     * 注入 fake clock：构造时返回 500，随后递增至 2500；构造后首次 generate 应
     * 解出 `timeSinceHourStart == 2000ms`（会话启动至今 2000 毫秒）。
     *
     * 协议编码：`timeMs = (timestamp % 3_600_000) / 2`，故 byte 里原始值 == 1000；
     * 解码回读时 × 2 还原 2000ms。
     */
    @Test
    fun generateGpsMainData_staticMode_timeSinceHourStartFromInjectedClock() {
        var fakeNow = 500L
        val generator = GpsDataGenerator(
            scenario = TestScenario.STATIC,
            clock = { fakeNow }
        )
        // 构造时 sessionStartRealtimeMillis = 500
        fakeNow = 2500L // 会话已持续 2000ms

        val data = generator.generateGpsMainData()

        // 解析 byte[0..2] 的 timeMs（协议编码是 /2）
        val encodedHalfMillis =
            ((data[0].toInt() and 0x1F) shl 16) or
                ((data[1].toInt() and 0xFF) shl 8) or
                (data[2].toInt() and 0xFF)
        val decodedMillis = encodedHalfMillis * 2

        assertEquals(2000, decodedMillis)
    }

    /**
     * Spec Scenario：Replay 模式缺样本时立即抛异常而不是 fallback。
     *
     * 注：测试注入 fake clock 以避免构造时默认调用 `SystemClock.elapsedRealtime()`（JVM 单测下未 mock 会抛 RuntimeException）。
     * 构造时 clock 仅用于 `sessionStartRealtimeMillis` 初始化，与 replay 路径无关。
     */
    @Test
    fun generateGpsMainData_replayMode_withoutApplyReplaySample_throws() {
        val generator = GpsDataGenerator(
            scenario = TestScenario.REAL_TRACK_REPLAY,
            clock = { 0L }
        )

        try {
            generator.generateGpsMainData()
            fail("Expected IllegalStateException when replay sample is missing")
        } catch (e: IllegalStateException) {
            assertTrue(
                "exception message should contain 'Replay sample missing timestamp', but was: ${e.message}",
                e.message?.contains("Replay sample missing timestamp") == true
            )
        }
    }

    /**
     * Spec Scenario：端到端链路内不得访问 `System.currentTimeMillis`。
     *
     * Mockito 5 禁止 mock `java.lang.System` 的静态方法（会引发类加载死循环），
     * 因此改用**字节码扫描**反证：读取编译后的 `GpsDataGenerator.class`，
     * 断言其常量池**不引用** `java/lang/System.currentTimeMillis`。
     * 等价于"该类任何代码路径都无法调用 `System.currentTimeMillis()`"。
     *
     * 端到端链路全量 0 次调用的强保证留给战役 H 的 EndToEndLapTimingContractTest
     * （在 feature:test 模块用 Byte Buddy agent 拦截）。
     */
    @Test
    fun generateGpsData_neverCallsSystemCurrentTimeMillis() {
        val classBytes = GpsDataGenerator::class.java
            .getResourceAsStream("/${GpsDataGenerator::class.java.name.replace('.', '/')}.class")
            ?.use { it.readBytes() }
            ?: fail("Could not locate GpsDataGenerator.class in test classpath").let { ByteArray(0) }

        val constantPoolText = String(classBytes, Charsets.ISO_8859_1)

        // 反证：字节码常量池不得含 "java/lang/System" 或 "currentTimeMillis" 成对出现
        val referencesSystemClass = constantPoolText.contains("java/lang/System")
        val referencesCurrentTimeMillis = constantPoolText.contains("currentTimeMillis")

        // 允许 System 类引用（例如 System.arraycopy 其他合法调用，当前代码里没有但为防误伤）
        // 但严禁出现 currentTimeMillis 常量
        assertFalse(
            "GpsDataGenerator.class should NOT reference 'currentTimeMillis' in its constant pool " +
                "(spec: 发射端 MUST NOT 调用 System.currentTimeMillis())",
            referencesCurrentTimeMillis
        )

        // 同时快速自检：generate 路径本身能正常产出字节，不抛异常（非功能断言，仅保证测试不空转）
        var fakeNow = 1000L
        val generator = GpsDataGenerator(
            scenario = TestScenario.STATIC,
            clock = { fakeNow }
        )
        fakeNow = 5000L
        assertEquals(20, generator.generateGpsMainData().size)
        assertEquals(3, generator.generateGpsTimeData().size)

        // 抑制未使用告警
        @Suppress("UNUSED_VARIABLE")
        val mention = referencesSystemClass
    }

    /**
     * Spec 额外锁定：`reset()` 重置会话相对时钟起点；
     * reset 后再 generate，`timeSinceHourStart` 从 0 重新递增。
     */
    @Test
    fun reset_resetsSessionStartRealtime() {
        var fakeNow = 100L
        val generator = GpsDataGenerator(
            scenario = TestScenario.STATIC,
            clock = { fakeNow }
        )
        // 让第一段会话推进到 1 秒
        fakeNow = 1_100L
        val beforeReset = decodeTimeMillisFromMainPacket(generator.generateGpsMainData())
        assertEquals(1_000, beforeReset)

        // reset 后 sessionStartRealtimeMillis 应更新为当前 fakeNow
        generator.reset()
        // 会话相对时钟归零——立即 generate 应得 0
        val immediatelyAfterReset = decodeTimeMillisFromMainPacket(generator.generateGpsMainData())
        assertEquals(0, immediatelyAfterReset)

        // 再推进 500ms，应得 500
        fakeNow = 1_600L
        val laterAfterReset = decodeTimeMillisFromMainPacket(generator.generateGpsMainData())
        assertEquals(500, laterAfterReset)
    }

    private fun decodeTimeMillisFromMainPacket(data: ByteArray): Int {
        val encodedHalfMillis =
            ((data[0].toInt() and 0x1F) shl 16) or
                ((data[1].toInt() and 0xFF) shl 8) or
                (data[2].toInt() and 0xFF)
        return encodedHalfMillis * 2
    }

    // ==================== A16b R2 altitude 编码字节级测试（P2-2 必做门槛）====================
    //
    // Change: openspec/changes/fix-altitude-encoding-contract-alignment
    //   Requirement 2: simulator 发送端 altitude 编码与 ino 对齐
    // 断言 simulator 生成的 byte 12-13 与 ino `RaceChrono_ESP32_M9N.ino:294-298` 真实编码一致

    @Test
    fun generatesBytes_altitudeWithInoCompatibleEncoding() {
        // 覆盖 3 组 alt → byte[12], byte[13] 映射：
        //   bit15=0 典型（100m）/ bit15=1 典型高海拔（10000m）/ Non-goal 截断区间锁定（4000m）

        // Case 1: bit15=0 典型 alt=100m
        //   ino 编码：raw = ((100+500)*10) & 0x7FFF = 6000 = 0x1770，字节 0x17 0x70
        val generator100 = GpsDataGenerator(scenario = TestScenario.STATIC)
        setAltitudeViaReflection(generator100, 100f)
        val data100 = generator100.generateGpsMainData()
        assertEquals(
            "alt=100m bit15=0：data[12] 应为 0x17（raw=6000 高字节）",
            0x17,
            data100[12].toInt() and 0xFF
        )
        assertEquals(
            "alt=100m bit15=0：data[13] 应为 0x70（raw=6000 低字节）",
            0x70,
            data100[13].toInt() and 0xFF
        )

        // Case 2: bit15=1 典型高海拔 alt=10000m
        //   ino 编码：raw = (10000+500) | 0x8000 = 10500 | 0x8000 = 0xA904，字节 0xA9 0x04
        //   硬区分 v1：v1 公式 ((10500*10) & 0x7FFF) | 0x8000 = (105000 & 0x7FFF) | 0x8000
        //              = 39464 | 0x8000 = 0x9A28，字节 0x9A 0x28 —— 与 v2 完全不同
        val generator10000 = GpsDataGenerator(scenario = TestScenario.STATIC)
        setAltitudeViaReflection(generator10000, 10000f)
        val data10000 = generator10000.generateGpsMainData()
        assertEquals(
            "alt=10000m bit15=1：data[12] 应为 0xA9（ino raw=0xA904 高字节；v1 错为 0x9A）",
            0xA9,
            data10000[12].toInt() and 0xFF
        )
        assertEquals(
            "alt=10000m bit15=1：data[13] 应为 0x04（ino raw=0xA904 低字节；v1 错为 0x28）",
            0x04,
            data10000[13].toInt() and 0xFF
        )

        // Case 3: Non-goal 截断区间 alt=4000m（[2776.7m, 6053.5m]）
        //   ino 判定 4000 < 6053.5 → bit15=0 → raw = ((4000+500)*10) & 0x7FFF
        //   = 45000 & 0x7FFF = 45000 - 32768 = 12232 = 0x2FC8，字节 0x2F 0xC8
        //   simulator MUST 与 ino 行为一致（都截断，字节一致），不在 simulator 单边补救
        val generator4000 = GpsDataGenerator(scenario = TestScenario.STATIC)
        setAltitudeViaReflection(generator4000, 4000f)
        val data4000 = generator4000.generateGpsMainData()
        assertEquals(
            "alt=4000m 截断区间：data[12] 应为 0x2F（与 ino 截断字节一致）",
            0x2F,
            data4000[12].toInt() and 0xFF
        )
        assertEquals(
            "alt=4000m 截断区间：data[13] 应为 0xC8（与 ino 截断字节一致，Non-goal 契约）",
            0xC8,
            data4000[13].toInt() and 0xFF
        )
    }

    /** 通过反射设置 [GpsDataGenerator.altitude] private 字段供字节级测试使用。 */
    private fun setAltitudeViaReflection(generator: GpsDataGenerator, altMeters: Float) {
        val field = GpsDataGenerator::class.java.getDeclaredField("altitude")
        field.isAccessible = true
        field.setFloat(generator, altMeters)
    }
}
