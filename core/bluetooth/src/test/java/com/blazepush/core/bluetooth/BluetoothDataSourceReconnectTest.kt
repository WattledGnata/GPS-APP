// @IgnoreFormatCheck
package com.blazepush.core.bluetooth

import android.content.Context
import com.blazepush.core.bluetooth.parser.RaceChronoParser
import com.blazepush.core.domain.model.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * @description fix-ble-auto-reconnect:意外断开自动重连单测(spec ble-auto-reconnect R1/R2/R3)。
 *              runtime 驱动方式:mockContext.getSystemService 返回 null →
 *              BleConnection.connect() 内 `as BluetoothManager` 抛 NPE →
 *              doConnect catch 分支 → DISCONNECTED + maybeScheduleReconnect ——
 *              每次尝试产生 CONNECTING→DISCONNECTED 一组转移,经注入的
 *              StandardTestDispatcher 用 advanceTimeBy 驱动退避虚拟时钟。
 * @author CC
 * @date 2026-06-04
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothDataSourceReconnectTest {

    @Test
    fun `P0-2 退避精确为 1 2 4 8 16 30 秒且之后恒 30 秒`() {
        val source = BluetoothDataSource(mockContext, mockParser)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            (0..7).map(source::reconnectDelayMs),
        )
    }

    @Test
    fun `P0-2 多个 immediate trigger 合并为单一 connect attempt`() = runTest {
        val attempts = mutableListOf<String>()
        val source = BluetoothDataSource(
            mockContext,
            mockParser,
            StandardTestDispatcher(testScheduler),
            onConnectAttempt = attempts::add,
        )

        source.connect("AA:BB:CC:DD:EE:10")
        source.requestImmediateReconnect("foreground")
        source.requestImmediateReconnect("lap session")
        source.requestImmediateReconnect("bluetooth enabled")
        runCurrent()

        assertEquals(listOf("AA:BB:CC:DD:EE:10"), attempts)
        source.disconnect(); runCurrent()
    }

    @Test
    fun `P0-2 immediate 安全抢占 30 秒退避且旧 job 不产生额外 attempt`() = runTest {
        val attempts = mutableListOf<String>()
        val source = BluetoothDataSource(
            mockContext,
            mockParser,
            StandardTestDispatcher(testScheduler),
            onConnectAttempt = attempts::add,
        )
        source.connect("AA:BB:CC:DD:EE:11")
        runCurrent()
        listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L).forEach {
            advanceTimeBy(it); runCurrent()
        }
        assertEquals(6, attempts.size)

        source.requestImmediateReconnect("foreground")
        runCurrent()
        assertEquals(7, attempts.size)
        advanceTimeBy(29_999); runCurrent()
        assertEquals("新 attempt 的 30s delay 前不得有旧 job 醒来", 7, attempts.size)
        advanceTimeBy(2); runCurrent()
        assertEquals("30s 时只能有新 attempt 自己的一个重试", 8, attempts.size)

        source.disconnect(); runCurrent()
    }

    @Test
    fun `P0-2 切设备和主动断开使旧目标及旧 delay 失效`() = runTest {
        val attempts = mutableListOf<String>()
        val source = BluetoothDataSource(
            mockContext,
            mockParser,
            StandardTestDispatcher(testScheduler),
            onConnectAttempt = attempts::add,
        )
        source.connect("AA:BB:CC:DD:EE:12"); runCurrent()
        source.connect("AA:BB:CC:DD:EE:13"); runCurrent()
        advanceTimeBy(1_001); runCurrent()

        assertEquals(listOf("AA:BB:CC:DD:EE:12", "AA:BB:CC:DD:EE:13", "AA:BB:CC:DD:EE:13"), attempts)
        source.disconnect(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, attempts.size)
    }

    private lateinit var mockContext: Context
    private lateinit var mockParser: RaceChronoParser

    // 注:不 mockStatic(Log)——本模块 isReturnDefaultValues=true 已让未 mock 的 Log 返回默认值;
    // mockStatic 的 inline agent 与重连协程链交互曾触发测试进程 OOM(2026-06-04 排查)。
    @Before
    fun setup() {
        mockContext = Mockito.mock(Context::class.java) // getSystemService 默认 null → connect 必抛
        mockParser = Mockito.mock(RaceChronoParser::class.java)
    }

    /** 统计 CONNECTING 出现次数 = 连接尝试次数(StateFlow 去重,交替序列每次都发)。 */
    private fun countAttempts(states: List<ConnectionState>): Int =
        states.count { it == ConnectionState.CONNECTING }

    @Test
    fun `R1S1 意外断开按指数退避自动重连`() = runTest {
        val source = BluetoothDataSource(mockContext, mockParser, StandardTestDispatcher(testScheduler))
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.connectionState.collect { states.add(it) }
        }

        source.connect("AA:BB:CC:DD:EE:01")
        runCurrent() // 第 1 次尝试同步失败(NPE)→ DISCONNECTED + 排队 1s 重连
        assertEquals(1, countAttempts(states))
        assertEquals(ConnectionState.DISCONNECTED, source.connectionState.value)

        advanceTimeBy(999); runCurrent() // 未到 1s 不得重试(退避下限)
        assertEquals(1, countAttempts(states))

        advanceTimeBy(2); runCurrent() // 过 1s → 第 2 次尝试
        assertEquals(2, countAttempts(states))

        advanceTimeBy(1500); runCurrent() // 第 2 次失败后退避 2s:1.5s 未到
        assertEquals(2, countAttempts(states))
        advanceTimeBy(600); runCurrent() // 累计 2.1s → 第 3 次
        assertEquals(3, countAttempts(states))

        advanceTimeBy(4100); runCurrent() // 退避 4s → 第 4 次
        assertEquals(4, countAttempts(states))

        source.disconnect(); runCurrent() // 收尾:取消挂起重连,否则 runTest 收尾 advanceUntilIdle
        // 会沿"无限重试"的虚拟时钟无限推进(无限 doConnect → OOM,2026-06-04 排查结论)
    }

    @Test
    fun `R1 长退避封顶 30s`() = runTest {
        val source = BluetoothDataSource(mockContext, mockParser, StandardTestDispatcher(testScheduler))
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.connectionState.collect { states.add(it) }
        }

        source.connect("AA:BB:CC:DD:EE:02")
        runCurrent()
        advanceTimeBy(10 * 60_000); runCurrent() // 10 分钟:1+2+4+8+16+30+30+... 全部释放

        val attempts = countAttempts(states)
        // 前 5 次退避和 31s,此后每 30s 一次:10min ≈ 5 + (600-31)/30 ≈ 23~24 次;
        // 封顶失效(恒 1s)会是 ~600 次,无限增长(无封顶)会 <10 次——区间断言锁两端
        assertTrue("封顶 30s 下 10min 尝试次数应在 15..40,实际 $attempts", attempts in 15..40)

        source.disconnect(); runCurrent() // 收尾防 runTest advanceUntilIdle 无限重试循环
    }

    @Test
    fun `R2S1 主动断开后不再自动重连(反例)`() = runTest {
        val source = BluetoothDataSource(mockContext, mockParser, StandardTestDispatcher(testScheduler))
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.connectionState.collect { states.add(it) }
        }

        source.connect("AA:BB:CC:DD:EE:03")
        runCurrent()
        val attemptsBefore = countAttempts(states)

        source.disconnect()
        runCurrent()
        advanceTimeBy(5 * 60_000); runCurrent() // 5 分钟内不得有任何新尝试

        assertEquals("主动断开后 MUST NOT 自动重连", attemptsBefore, countAttempts(states))
    }

    @Test
    fun `R2S2 切设备后旧重连作废且退避复位`() = runTest {
        val source = BluetoothDataSource(mockContext, mockParser, StandardTestDispatcher(testScheduler))
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.connectionState.collect { states.add(it) }
        }

        source.connect("AA:BB:CC:DD:EE:04")
        runCurrent()
        advanceTimeBy(1100); runCurrent() // A 的第 2 次尝试已发生,退避升至 2s
        val attemptsA = countAttempts(states)

        source.connect("AA:BB:CC:DD:EE:05") // 切设备:公开入口立即尝试 + 复位退避
        runCurrent()
        assertEquals("切设备立即尝试一次", attemptsA + 1, countAttempts(states))

        advanceTimeBy(1100); runCurrent() // 复位后退避从 1s 起(若未复位需 2s 才有下次)
        assertEquals("退避已复位为 1s 起步", attemptsA + 2, countAttempts(states))

        source.disconnect(); runCurrent() // 收尾防 runTest advanceUntilIdle 无限重试循环
    }

    @Test
    fun `R2S3 无连接历史不重连`() = runTest {
        val source = BluetoothDataSource(mockContext, mockParser, StandardTestDispatcher(testScheduler))
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.connectionState.collect { states.add(it) }
        }

        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals("从未 connect 不得有任何尝试", 0, countAttempts(states))
    }

    @Test
    fun `R3 状态转移序列每次尝试可见`() = runTest {
        val source = BluetoothDataSource(mockContext, mockParser, StandardTestDispatcher(testScheduler))
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.connectionState.collect { states.add(it) }
        }

        source.connect("AA:BB:CC:DD:EE:06")
        runCurrent()
        advanceTimeBy(1100); runCurrent()
        advanceTimeBy(2100); runCurrent() // 3 次尝试

        // 序列(忽略 replay 初值)应为 CONNECTING,DISCONNECTED 交替,无静默重试
        val meaningful = states.dropWhile { it == ConnectionState.DISCONNECTED }
        assertTrue("序列至少 3 组交替:$meaningful", meaningful.size >= 6)
        meaningful.chunked(2).take(3).forEach { pair ->
            assertEquals(ConnectionState.CONNECTING, pair[0])
            assertEquals(ConnectionState.DISCONNECTED, pair[1])
        }

        source.disconnect(); runCurrent() // 收尾防 runTest advanceUntilIdle 无限重试循环
    }
}
