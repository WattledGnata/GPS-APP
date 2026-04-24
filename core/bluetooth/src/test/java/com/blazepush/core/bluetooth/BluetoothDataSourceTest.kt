package com.blazepush.core.bluetooth

import android.content.Context
import android.util.Log
import com.blazepush.core.bluetooth.parser.RaceChronoParser
import com.blazepush.core.domain.model.GpsData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import java.io.File
import java.util.UUID

/**
 * @description 战役 G R4 + R5 回归测试：BluetoothDataSource isConnected 语义收敛
 *              + connect 清旧连接。覆盖 Spec ble-connection Requirement 4（A25）
 *              + Requirement 5（A27）。R4 runtime 测试通过 `internal fun
 *              handleIncomingData` 入口直接喂数据 + mock RaceChronoParser 控制
 *              parseResult，验证 `_dataFlow.value.isConnected` 语义收敛。R5 源码
 *              结构断言锁定 connect() 内"先 cancel collectJob → disconnect 旧
 *              bleConnection → CONNECTING" 的严格顺序（真 BleConnection 构造涉及
 *              Android BT API，JVM 下无法 runtime 跑）。
 * @author haozhang93
 * @date 2026-04-24
 */
class BluetoothDataSourceTest {

    private lateinit var mockContext: Context
    private lateinit var mockParser: RaceChronoParser
    private lateinit var source: BluetoothDataSource
    private lateinit var logMock: AutoCloseable

    private val gpsMainUuid = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
    private val gpsTimeUuid = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")
    private val unknownUuid = UUID.fromString("ffffffff-0000-1000-8000-00805f9b34fb")

    /**
     * 每个 @Test 前：mock Log 静态调用 + Context + RaceChronoParser，构造
     * BluetoothDataSource 实例（不调 connect 避免真 BLE 调用）。
     */
    @Before
    fun setup() {
        logMock = Mockito.mockStatic(Log::class.java)
        mockContext = Mockito.mock(Context::class.java)
        mockParser = Mockito.mock(RaceChronoParser::class.java)
        source = BluetoothDataSource(mockContext, mockParser)
    }

    /**
     * 释放 Mockito 静态 mock 资源，避免跨测试污染。
     */
    @After
    fun tearDown() {
        logMock.close()
    }

    /**
     * R4 Scenario 1：短包 parse 失败时 isConnected MUST 保持 false（v1 下会被强置 true）。
     */
    @Test
    fun onDataReceived_shortPacket_doesNotFlagIsConnectedTrue() {
        val shortPacketResult = GpsData.Empty.copy(errorMessage = "short-packet")
        Mockito.`when`(mockParser.parseGpsData(any(), any(), any())).thenReturn(shortPacketResult)

        assertFalse(source.dataFlow.value.isConnected)

        source.handleIncomingData(gpsMainUuid, ByteArray(10))

        assertFalse(
            "短包 parse 失败时 isConnected MUST 保持 false（v1 下会被强置为 true）",
            source.dataFlow.value.isConnected,
        )
        assertEquals("short-packet", source.dataFlow.value.errorMessage)
    }

    /**
     * R4 Scenario 2：parse 抛异常时 isConnected MUST 保持 false，errorMessage 含
     * "parse-error" 前缀上抛给下游。
     */
    @Test
    fun onDataReceived_parseException_doesNotFlagIsConnectedTrue() {
        val parseErrorResult = GpsData.Empty.copy(errorMessage = "parse-error: NumberFormatException")
        Mockito.`when`(mockParser.parseGpsData(any(), any(), any())).thenReturn(parseErrorResult)

        source.handleIncomingData(gpsMainUuid, ByteArray(20))

        assertFalse(
            "parse 异常时 isConnected MUST 保持 false",
            source.dataFlow.value.isConnected,
        )
        assertTrue(
            "errorMessage 含 parse-error 前缀",
            source.dataFlow.value.errorMessage?.startsWith("parse-error") == true,
        )
    }

    /**
     * R4 Scenario 3：parse 成功路径 MUST 置 isConnected=true 且显式清前帧短包残留
     * errorMessage（否则下游分流仍走失败分支）。
     */
    @Test
    fun onDataReceived_successfulParse_clearsErrorMessageAndFlagsIsConnectedTrue() {
        setDataFlow(GpsData.Empty.copy(errorMessage = "short-packet"))
        assertEquals("short-packet", source.dataFlow.value.errorMessage)

        val successResult = GpsData.Empty.copy(
            latitude = 30.0,
            longitude = 104.0,
            satelliteCount = 8,
            errorMessage = null,
        )
        Mockito.`when`(mockParser.parseGpsData(any(), any(), any())).thenReturn(successResult)

        source.handleIncomingData(gpsMainUuid, ByteArray(20))

        assertTrue("parse 成功 MUST 置 isConnected=true", source.dataFlow.value.isConnected)
        assertNull(
            "parse 成功 MUST 显式清 errorMessage（上次短包残留被清除）",
            source.dataFlow.value.errorMessage,
        )
    }

    /**
     * R4 Scenario（第五轮 review 补齐）：GPS_TIME 短包路径 MUST 让 isConnected
     * 翻转为 false 与 GPS_MAIN 对称。review 前 parseGpsTimeData 短包不写 errorMessage，
     * 下游误置 isConnected=true；修补后 parser 写 errorMessage，下游走失败分支翻转。
     */
    @Test
    fun onDataReceived_gpsTimeShortPacket_doesNotFlagIsConnectedTrue() {
        val shortPacketResult = GpsData.Empty.copy(
            isConnected = true,
            errorMessage = "short-packet",
        )
        Mockito.`when`(mockParser.parseGpsTimeData(any(), any())).thenReturn(shortPacketResult)
        setDataFlow(GpsData.Empty.copy(isConnected = true, errorMessage = null))

        source.handleIncomingData(gpsTimeUuid, ByteArray(2))

        assertFalse(
            "GPS_TIME 短包 MUST 让 isConnected 翻转为 false（与 GPS_MAIN 对称）",
            source.dataFlow.value.isConnected,
        )
        assertEquals("short-packet", source.dataFlow.value.errorMessage)
    }

    /**
     * R4 级联故障端到端验证（第五轮 review）：短包后紧跟一帧 parse 成功 MUST 让
     * isConnected 恢复 true。若 parser 成功路径不清 errorMessage，"短包之后第一帧
     * 成功"会被下游当失败 → isConnected 永远 false（级联）。parser 显式 errorMessage=null
     * 切断级联是本断言 pass 的前提。
     */
    @Test
    fun onDataReceived_shortPacketThenSuccess_recoversIsConnectedTrue() {
        val shortPacketResult = GpsData.Empty.copy(errorMessage = "short-packet")
        Mockito.`when`(mockParser.parseGpsData(any(), any(), any()))
            .thenReturn(shortPacketResult)
        source.handleIncomingData(gpsMainUuid, ByteArray(10))
        assertFalse(
            "前置：短包后 isConnected == false",
            source.dataFlow.value.isConnected,
        )
        assertEquals("short-packet", source.dataFlow.value.errorMessage)

        val successResult = GpsData.Empty.copy(
            latitude = 30.0,
            longitude = 104.0,
            satelliteCount = 8,
            errorMessage = null,
        )
        Mockito.`when`(mockParser.parseGpsData(any(), any(), any()))
            .thenReturn(successResult)
        source.handleIncomingData(gpsMainUuid, ByteArray(20))

        assertTrue(
            "短包后第一帧 parse 成功 MUST 让 isConnected 恢复 true —— parser 成功路径不清 " +
                "errorMessage 会让本断言 fail（级联故障）",
            source.dataFlow.value.isConnected,
        )
        assertNull(
            "短包残留的 errorMessage MUST 被成功路径清除",
            source.dataFlow.value.errorMessage,
        )
    }

    /**
     * R4 Scenario（第四轮 review）：成功后失败时 isConnected MUST 显式翻转回 false。
     * parser 的 copy 保留前帧 isConnected=true 字段，BluetoothDataSource 失败分支
     * 必须 `parseResult.copy(isConnected = false)` 显式翻转，否则输出
     * `isConnected=true + errorMessage != null` 状态自相矛盾。
     */
    @Test
    fun onDataReceived_successThenShortPacket_flipsIsConnectedBackToFalse() {
        setDataFlow(GpsData.Empty.copy(isConnected = true, errorMessage = null))
        assertTrue("前置：isConnected 初始为 true", source.dataFlow.value.isConnected)
        assertNull("前置：errorMessage 初始为 null", source.dataFlow.value.errorMessage)

        val shortPacketResult = source.dataFlow.value.copy(errorMessage = "short-packet")
        assertTrue(
            "前置：parser 的 copy 保留了前帧 isConnected=true（模拟 parser 实际行为）",
            shortPacketResult.isConnected,
        )
        Mockito.`when`(mockParser.parseGpsData(any(), any(), any())).thenReturn(shortPacketResult)

        source.handleIncomingData(gpsMainUuid, ByteArray(10))

        assertFalse(
            "成功后失败时 isConnected MUST 被 **显式** 翻转回 false —— 否则输出 " +
                "isConnected=true + errorMessage != null 状态自相矛盾，违反 " +
                "\"isConnected 充要条件是最近一次 parse 成功\" 契约",
            source.dataFlow.value.isConnected,
        )
        assertEquals("short-packet", source.dataFlow.value.errorMessage)
    }

    /**
     * R4 Scenario 4：未知 UUID MUST NOT 翻转 isConnected。硬断言 GIVEN false →
     * THEN 仍 false。v1 未知 UUID 走 `copy(isConnected = true)` 强置；v2 通过
     * parseResult == null 早退整块跳过。
     */
    @Test
    fun onDataReceived_unknownUuid_doesNotFlipIsConnected() {
        assertFalse(source.dataFlow.value.isConnected)
        assertNull(source.dataFlow.value.errorMessage)

        source.handleIncomingData(unknownUuid, ByteArray(20))

        assertFalse(
            "未知 UUID MUST NOT 翻转 isConnected 为 true（v1 会走 copy(isConnected = true)）",
            source.dataFlow.value.isConnected,
        )
        assertNull(
            "未知 UUID MUST NOT 污染 errorMessage",
            source.dataFlow.value.errorMessage,
        )
        Mockito.verify(mockParser, Mockito.never()).parseGpsData(any(), any(), any())
        Mockito.verify(mockParser, Mockito.never()).parseGpsTimeData(any(), any())
    }

    /**
     * R5 源码锚点断言：connect() 内清旧连接顺序 MUST 严格为
     * "先 cancel collectJob → nullify → disconnect 旧 bleConnection → nullify →
     * CONNECTING → 新建 BleConnection"。JVM 单测无法真跑 connect()，此结构断言
     * 防止未来打乱顺序。
     */
    @Test
    fun connect_sourceHasStrictOrderingOfCleanupThenReset() {
        val src = File(
            "src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt"
        ).readText()

        val connectStart = src.indexOf("fun connect(deviceAddress: String)")
        assertTrue("connect(String) 必须定义", connectStart > 0)

        val cancelIdx = src.indexOf("connectionCollectJob?.cancel()", connectStart)
        val nullifyJobIdx = src.indexOf("connectionCollectJob = null", connectStart)
        val disconnectIdx = src.indexOf("bleConnection?.disconnect()", connectStart)
        val nullifyBleIdx = src.indexOf("bleConnection = null", connectStart)
        val connectingIdx = src.indexOf("_connectionState.value = ConnectionState.CONNECTING", connectStart)
        val newBleIdx = src.indexOf("bleConnection = BleConnection(context, deviceAddress)", connectStart)

        assertTrue("cancel collectJob 必须存在", cancelIdx > 0)
        assertTrue("nullify collectJob 必须存在", nullifyJobIdx > cancelIdx)
        assertTrue("disconnect 旧 bleConnection 必须存在", disconnectIdx > nullifyJobIdx)
        assertTrue("nullify 旧 bleConnection 必须存在", nullifyBleIdx > disconnectIdx)
        assertTrue("CONNECTING 状态重置必须存在", connectingIdx > nullifyBleIdx)
        assertTrue("新建 BleConnection 必须在 CONNECTING 之后", newBleIdx > connectingIdx)
    }

    /**
     * R5 反向锚点断言：旧有的 `connectionState?.let` 块内不应再有第二次
     * connectionCollectJob?.cancel() —— cancel 已原子化在 try 块开头。
     */
    @Test
    fun connect_sourceHasNoSecondCancelCollectJobInsideLet() {
        val src = File(
            "src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt"
        ).readText()
        val letStart = src.indexOf("bleConnection?.connectionState?.let { stateFlow ->")
        assertTrue("connectionState?.let 块必须存在", letStart > 0)
        val letEnd = src.indexOf("\n                }\n", letStart)
        val letBody = src.substring(letStart, letEnd)
        assertFalse(
            "connectionState?.let 块内不应再有 connectionCollectJob?.cancel() —— " +
                "cancel 已原子化在 try 开头",
            letBody.contains("connectionCollectJob?.cancel()"),
        )
    }

    /**
     * 锁定 handleIncomingData 可见性为 internal —— 把 onDataReceived lambda
     * 提取为独立方法是 R4 runtime 测试前提。Kotlin `internal` 在字节码中
     * name mangling 为 `handleIncomingData$core_bluetooth_debug`，用 startsWith
     * 绕开 suffix 变化。
     */
    @Test
    fun handleIncomingData_isInternalForTestability() {
        val method = BluetoothDataSource::class.java
            .declaredMethods
            .firstOrNull { it.name.startsWith("handleIncomingData") }
        assertTrue(
            "BluetoothDataSource.handleIncomingData 方法必须存在（internal 可见于同 module 测试）",
            method != null,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun setDataFlow(data: GpsData) {
        val f = BluetoothDataSource::class.java.getDeclaredField("_dataFlow")
        f.isAccessible = true
        val flow = f.get(source) as kotlinx.coroutines.flow.MutableStateFlow<GpsData>
        flow.value = data
    }
}