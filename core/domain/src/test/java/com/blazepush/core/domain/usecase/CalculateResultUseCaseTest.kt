package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.GpsDataPoint
import com.blazepush.core.domain.model.TestSession
import com.blazepush.core.domain.model.TestTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CalculateResultUseCase max* 字段拆分单测。
 *
 * 锁定 spec.md `Requirement: TestResult / TestRecordEntity 区分 maxAcceleration 与 maxDeceleration`：
 * - 0-100 加速：maxAcceleration > 0、maxDeceleration == 0.0
 * - 100-0 制动：maxAcceleration == 0.0、maxDeceleration > 0
 *
 * @author CC
 * @description max* 字段拆分单测
 * @date 2026-05-03
 */
class CalculateResultUseCaseTest {

    private val useCase = CalculateResultUseCase()

    private fun buildSession(
        template: TestTemplate,
        speedSeriesKmh: List<Double>,
        dtMs: Long = 40L,
    ): TestSession {
        val session = TestSession(
            id = "test-session",
            template = template,
            carModel = "TestCar",
            startTime = 0L,
        )
        speedSeriesKmh.forEachIndexed { i, speed ->
            session.dataPoints.add(
                GpsDataPoint(
                    elapsedTime = i * dtMs / 1000.0,
                    speed = speed,
                    latitude = 0.0,
                    longitude = 0.0,
                    altitude = 0.0,
                ),
            )
        }
        return session
    }

    @Test
    fun `0-100 加速 maxAcceleration 大于 0 maxDeceleration 等于 0`() {
        // 30 帧 25Hz 单调加速：0 → 116 km/h，约 7.5s（典型 0-100 测试时长）
        val series = (0..29).map { i -> i * 4.0 }
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "")

        assertTrue("maxAcceleration 应 > 0：${result.maxAcceleration}", result.maxAcceleration > 0.1)
        assertEquals("纯加速段 maxDeceleration 应为 0.0", 0.0, result.maxDeceleration, 1e-6)
    }

    @Test
    fun `100-0 制动 maxDeceleration 大于 0 maxAcceleration 等于 0`() {
        // 25 帧 25Hz 单调制动：100 → 0 km/h，约 4s
        val series = (0..24).map { i -> 100.0 - i * 4.0 }
        val session = buildSession(TestTemplate.Braking100To0, series)

        val result = useCase(session, dataFilePath = "")

        assertEquals("纯制动段 maxAcceleration 应为 0.0", 0.0, result.maxAcceleration, 1e-6)
        assertTrue("maxDeceleration 应 > 0：${result.maxDeceleration}", result.maxDeceleration > 0.1)
    }

    @Test
    fun `空 dataPoints 返回 empty result max 全 0`() {
        val session = TestSession(
            id = "empty",
            template = TestTemplate.Acceleration0To100,
            carModel = "TestCar",
            startTime = 0L,
        )
        val result = useCase(session, dataFilePath = "")

        assertEquals(0.0, result.maxAcceleration, 1e-6)
        assertEquals(0.0, result.maxDeceleration, 1e-6)
        assertEquals(0.0, result.avgAcceleration, 1e-6)
    }

    // ===== fix-accel-last-crossing:perftest-timing-window spec 场景 =====
    // dt=40ms → 每帧 0.04s;插值期望值按线性插值手算,精确断言(delta 1e-9)

    @Test
    fun `R1S1 多次蠕动起步只算最后一轮冲刺`() {
        // 蠕动1(0,3,5,3,0) + 蠕动2(0,4,8,4,0) + 冲刺(0,10,...,110)
        val series = listOf(0.0, 3.0, 5.0, 3.0, 0.0) +
            listOf(0.0, 4.0, 8.0, 4.0, 0.0) +
            (0..11).map { it * 10.0 } // idx10 起:0,10,...,110;100 在 idx20
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "")

        // 起点:冲刺段 0→10 上穿 1.0(ratio 0.1)→ t=0.404s;终点:90→100(ratio 1.0)→ t=0.8s
        assertEquals("窗口=最后一轮冲刺 1.0→100", 0.396, result.totalTime, 1e-9)
        assertEquals("窗口末点应为终点线插值速度", 100.0, result.dataPoints.last().speed, 1e-9)
    }

    @Test
    fun `R1S2 单次干净起步窗口为运动阈值到首次过百`() {
        val series = (0..29).map { it * 4.0 } // 0,4,...,116
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "")

        // 1.0 上穿:0→4 ratio 0.25 → 0.01s;100 上穿:96→100 ratio 1.0 → 1.0s
        assertEquals(0.99, result.totalTime, 1e-9)
    }

    @Test
    fun `R1S3 回落再破百以首次触线停表`() {
        // 0→102 首次过百后回落 95 再冲 103(中途未掉回 1,0 以下)
        val series = listOf(0.0, 20.0, 40.0, 60.0, 80.0, 102.0, 95.0, 98.0, 103.0)
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "")

        // 起点:0→20 上穿 1.0(ratio 0.05)→ 0.002s;终点:80→102 首次过 100(ratio 20/22)→ 0.196363..s
        // 若实现错取第二次过线(98→103),totalTime 会是 ~0.31s——本断言锁死物理口径
        assertEquals(4.0 / 25.0 + (20.0 / 22.0) * 0.04 - 0.002, result.totalTime, 1e-9)
        assertEquals(100.0, result.dataPoints.last().speed, 1e-9)
    }

    @Test
    fun `R1S4 过线后停车挪车不丢成绩`() {
        val series = listOf(0.0, 30.0, 60.0, 90.0, 105.0) + // 冲刺(候选完整)
            listOf(60.0, 20.0, 0.0) + // 减速停车
            listOf(0.0, 4.0, 6.0, 3.0, 0.0) // 挪车:重开锚点但无过线
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "")

        assertTrue("挪车不得导致 DNF:totalTime=${result.totalTime}", result.totalTime > 0.1)
        assertEquals("成绩窗口仍为冲刺段", 100.0, result.dataPoints.last().speed, 1e-9)
    }

    @Test
    fun `R2S1 未破百不得产出正成绩 路测53s回归锁`() {
        // 2026-06-03 路测形态:蠕动+最高 99 未破百,旧版产出 session 全时长 53.32s 假成绩
        val series = listOf(0.0, 3.0, 0.0, 20.0, 50.0, 80.0, 99.0, 95.0, 90.0)
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "")

        assertEquals("未破百 MUST DNF,绝不产出正 totalTime", 0.0, result.totalTime, 1e-9)
        assertTrue("DNF segments 必须为空", result.segments.isEmpty())
        assertTrue("DNF dataPoints 必须为空", result.dataPoints.isEmpty())
    }

    @Test
    fun `R2S2 全程静止 DNF 不抛异常`() {
        val series = listOf(0.1, 0.3, 0.5, 0.2, 0.4, 0.1)
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "")

        assertEquals(0.0, result.totalTime, 1e-9)
    }

    @Test
    fun `R2S3 DNF 时 SG 统计量保留`() {
        // 未破百但有真实加减速:max 加速/减速在 raw 全程计算,不随 DNF 归零
        val series = listOf(0.0, 20.0, 50.0, 80.0, 99.0, 60.0, 30.0, 5.0)
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "")

        assertEquals(0.0, result.totalTime, 1e-9)
        assertTrue("DNF 仍应保留 maxAcceleration:${result.maxAcceleration}", result.maxAcceleration > 0.1)
        assertTrue("DNF 仍应保留 maxDeceleration:${result.maxDeceleration}", result.maxDeceleration > 0.1)
    }

    @Test
    fun `R3S1 刹停后挪车不延长刹车成绩`() {
        val series = listOf(95.0, 80.0, 60.0, 40.0, 20.0, 5.0, 0.0) + // 刹停
            listOf(0.0, 4.0, 6.0, 3.0, 0.0) // 挪车(第二次下行过 1.0 不得采用)
        val session = buildSession(TestTemplate.Braking100To0, series)

        val result = useCase(session, dataFilePath = "")

        // 起点=首帧 t=0;终点=首次下行过 1.0:5→0(ratio 0.8)→ t=5.8*0.04=0.232s
        assertEquals(0.232, result.totalTime, 1e-9)
    }

    @Test
    fun `R3S2 未刹停 DNF`() {
        val series = listOf(95.0, 60.0, 30.0, 8.0, 10.0, 12.0) // 最低 8,未停
        val session = buildSession(TestTemplate.Braking100To0, series)

        val result = useCase(session, dataFilePath = "")

        assertEquals("未刹停 MUST DNF(旧版返回全程假成绩)", 0.0, result.totalTime, 1e-9)
    }

    @Test
    fun `R4S1 平台速度段插值除零防御`() {
        val series = listOf(0.0, 50.0, 100.0, 100.0, 100.0, 90.0)
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "") // 100,100 同速对 MUST 跳过不崩

        assertTrue("存在合法过线对应产出窗口", result.totalTime > 0.0)
    }

    @Test
    fun `R4S2 单帧跨双阈值窗口仍正序`() {
        // 一对相邻帧同时跨 1.0 与 100(0→150):开/关段同对,end 插值晚于 start 插值,守卫不产负值
        val series = listOf(0.0, 150.0, 160.0)
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "")

        assertTrue("窗口必须正序:totalTime=${result.totalTime}", result.totalTime > 0.0)
    }
}
