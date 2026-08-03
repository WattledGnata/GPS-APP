package com.blazepush.core.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.BleHandshakeState
import com.blazepush.core.domain.model.GpsChannelSubscriptionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.util.UUID

/**
 * @description 战役 G R2/R3 回归测试：BleConnection 数据超时释放 + disconnect close 时机。
 *              覆盖 Spec ble-connection Requirement 2（A24 race + 释放 GATT）+
 *              Requirement 3（A40 close 统一走回调路径）。
 *              测试策略：反射注入 mock `BluetoothGatt` 避开 Android runtime 依赖；
 *              反射替换 `scope` 字段为 `TestScope`，由 `runTest` 控制虚拟时钟；
 *              数据静默由连接期单一 watchdog 监控，25Hz 主帧只更新时间戳。
 * @author haozhang93
 * @date 2026-04-24
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleConnectionTest {

    private lateinit var connection: BleConnection
    private lateinit var mockContext: Context
    private lateinit var mockGatt: BluetoothGatt
    private lateinit var logMock: AutoCloseable
    private var elapsedRealtime = 0L

    /**
     * 每个 @Test 前的初始化：mock Log 静态调用，mock Context + BluetoothGatt，
     * 构造 BleConnection 并反射注入 mock gatt（绕过真实 BLE 调用）。
     */
    @Before
    fun setup() {
        logMock = Mockito.mockStatic(Log::class.java)
        mockContext = Mockito.mock(Context::class.java)
        mockGatt = Mockito.mock(BluetoothGatt::class.java)
        connection = BleConnection(
            context = mockContext,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            onDataReceived = { _: UUID, _: ByteArray -> },
            elapsedRealtimeMs = { elapsedRealtime },
        )
        setField("bluetoothGatt", mockGatt)
    }

    /**
     * 释放 Mockito 静态 mock 资源，避免跨测试污染。
     */
    @After
    fun tearDown() {
        logMock.close()
    }

    /**
     * ble-connection-liveness spec R1 场景一：数据静默超时 MUST NOT 拆链。
     * 数据超时成立时 gatt.disconnect / close 均**不**被调，connectionState 维持 CONNECTED，
     * 只置 dataStale=true。（本测试由 ble-no-fix-keep-link round 从原"超时即 disconnect"
     * 行为反转而来——真机固件无卫星不推帧，丢星 = 静默，曾被误判死链拆链。）
     */
    @Test
    fun startDataWatchdog_onTimeout_marksStaleAndKeepsConnected() = runTest {
        setField("scope", this)
        stateFlow().value = ConnectionState.CONNECTED
        setField("lastDataTime", 0L)

        invokePrivate("startDataWatchdog")

        advanceTimeBy(1_000)
        elapsedRealtime = 1_000L
        runCurrent()

        Mockito.verify(mockGatt, Mockito.never()).disconnect()
        Mockito.verify(mockGatt, Mockito.never()).close()
        assertEquals(
            "数据静默 MUST 维持 CONNECTED（不拆链）",
            ConnectionState.CONNECTED,
            connection.connectionState.value,
        )
        assertTrue("数据静默超时 MUST 置 dataStale=true", connection.dataStale.value)
        invokePrivate("cleanup")
    }

    /**
     * 25Hz 主帧只更新时间戳，不得为每帧取消/创建一个超时协程。一个连接期只运行
     * 一个 watchdog；连续 25 帧后仍是同一个 Job，并保持数据新鲜。
     */
    @Test
    fun mainFramesAt25Hz_reuseSingleWatchdogAndUseAdaptiveDeadline() = runTest {
        setField("scope", this)
        stateFlow().value = ConnectionState.CONNECTED
        invokePrivate("startDataWatchdog")
        val watchdog = getField("dataWatchdogJob")
        val mockChar = Mockito.mock(BluetoothGattCharacteristic::class.java)
        Mockito.`when`(mockChar.uuid)
            .thenReturn(UUID.fromString("00000003-0000-1000-8000-00805f9b34fb"))
        handshakeFlow().value = handshakeFlow().value.copy(
            main = GpsChannelSubscriptionState.SUBSCRIBED,
        )

        repeat(25) { index ->
            elapsedRealtime = (index + 1) * 40L
            gattCallback().onCharacteristicChanged(mockGatt, mockChar, byteArrayOf(1, 2, 3))
            advanceTimeBy(40L)
            runCurrent()
        }

        assertSame("25Hz 主帧不得重建 watchdog Job", watchdog, getField("dataWatchdogJob"))
        assertFalse("持续收到 25Hz 主帧时数据必须保持新鲜", connection.dataStale.value)

        elapsedRealtime = 1_400L
        advanceTimeBy(400L)
        runCurrent()
        assertTrue("25Hz 节拍建立后静默 400ms 应判为不可靠", connection.dataStale.value)
        invokePrivate("cleanup")
    }

    /**
     * R3 Scenario 1：主动 disconnect() 调用后 gatt.close() 未被调用，字段仍非 null ——
     * close 推迟到 onConnectionStateChange(STATE_DISCONNECTED) 回调统一处理。
     */
    @Test
    fun disconnect_doesNotCloseGattBeforeStateDisconnectedCallback() {
        connection.disconnect()

        Mockito.verify(mockGatt, Mockito.times(1)).disconnect()
        Mockito.verify(mockGatt, Mockito.never()).close()
        assertNotNull("disconnect() 同步阶段 bluetoothGatt 必须非 null", getField("bluetoothGatt"))
    }

    /**
     * R3 Scenario 2：onConnectionStateChange(STATE_DISCONNECTED) 回调触发
     * close + 字段 null + state 变 DISCONNECTED + cleanup 执行（所有释放动作集中在此处）。
     */
    @Test
    fun onConnectionStateChange_stateDisconnected_closesGattAndNullsReference() {
        val callback = gattCallback()
        callback.onConnectionStateChange(mockGatt, 0, BluetoothProfile.STATE_DISCONNECTED)

        Mockito.verify(mockGatt, Mockito.times(1)).close()
        assertNull("回调后 bluetoothGatt 必须置 null", getField("bluetoothGatt"))
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
    }

    /**
     * ble-connection-liveness spec R1 场景二（恢复）：静默置 dataStale=true 后收到任意帧，
     * dataStale MUST 清回 false 且链路全程保持 CONNECTED、disconnect 从未被调。
     * （本测试由 ble-no-fix-keep-link round 从原"超时 disconnect 走回调释放"反转为恢复语义。）
     */
    @Test
    fun onCharacteristicChanged_afterStale_clearsDataStaleAndKeepsConnected() {
        // 模拟先经历静默：dataStale=true、链路 CONNECTED
        staleFlow().value = true
        stateFlow().value = ConnectionState.CONNECTED

        val mockChar = Mockito.mock(BluetoothGattCharacteristic::class.java)
        Mockito.`when`(mockChar.uuid)
            .thenReturn(UUID.fromString("00000003-0000-1000-8000-00805f9b34fb"))
        handshakeFlow().value = handshakeFlow().value.copy(
            main = GpsChannelSubscriptionState.SUBSCRIBED,
        )

        // 卫星恢复推帧（走 onCharacteristicChanged → handleCharacteristicChange）
        gattCallback().onCharacteristicChanged(mockGatt, mockChar, byteArrayOf(1, 2, 3))

        assertFalse("收到帧后 dataStale MUST 清 false（数据续上）", connection.dataStale.value)
        assertEquals(
            "收到帧链路保持 CONNECTED",
            ConnectionState.CONNECTED,
            connection.connectionState.value,
        )
        Mockito.verify(mockGatt, Mockito.never()).disconnect()
    }

    @Test
    fun onGpsTimeCharacteristicChanged_afterStale_doesNotMaskMainFrameSilence() {
        staleFlow().value = true
        stateFlow().value = ConnectionState.CONNECTED
        val mockChar = Mockito.mock(BluetoothGattCharacteristic::class.java)
        Mockito.`when`(mockChar.uuid)
            .thenReturn(UUID.fromString("00000004-0000-1000-8000-00805f9b34fb"))
        handshakeFlow().value = handshakeFlow().value.copy(
            time = GpsChannelSubscriptionState.SUBSCRIBED,
        )

        gattCallback().onCharacteristicChanged(mockGatt, mockChar, byteArrayOf(1, 2, 3))

        assertTrue("GPS Time 包不得清除主定位帧静默状态", connection.dataStale.value)
    }

    /**
     * ble-connection-liveness spec R1 反例锁（源码结构断言）：startDataWatchdog 超时分支
     * MUST NOT 含拆链动作，且 MUST 改为置软陈旧状态（防"空实现什么都不做"trivially pass）。
     */
    @Test
    fun startDataWatchdog_timeoutBranch_marksStaleNotDisconnect() {
        val source = File("src/main/java/com/blazepush/core/bluetooth/BleConnection.kt").readText()
        val fnStart = source.indexOf("private fun startDataWatchdog()")
        assertTrue("必须定义 startDataWatchdog", fnStart > 0)
        val fnEnd = source.indexOf("\n    }\n", fnStart)
        assertTrue("必须闭合 startDataWatchdog 方法体", fnEnd > fnStart)
        val body = source.substring(fnStart, fnEnd)

        assertFalse(
            "超时分支 MUST NOT 调 bluetoothGatt?.disconnect()（丢星不拆链）",
            body.contains("bluetoothGatt?.disconnect()"),
        )
        assertFalse(
            "超时分支 MUST NOT 置 _connectionState.value = ConnectionState.DISCONNECTED",
            body.contains("_connectionState.value = ConnectionState.DISCONNECTED"),
        )
        assertTrue(
            "超时分支 MUST 置 _dataStale.value = true（证明改成软状态而非单纯删动作）",
            body.contains("_dataStale.value = true"),
        )
    }

    private fun setField(name: String, value: Any?) {
        val f = BleConnection::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(connection, value)
    }

    private fun getField(name: String): Any? {
        val f = BleConnection::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(connection)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateFlow(): MutableStateFlow<ConnectionState> =
        getField("_connectionState") as MutableStateFlow<ConnectionState>

    @Suppress("UNCHECKED_CAST")
    private fun staleFlow(): MutableStateFlow<Boolean> =
        getField("_dataStale") as MutableStateFlow<Boolean>

    @Suppress("UNCHECKED_CAST")
    private fun handshakeFlow(): MutableStateFlow<BleHandshakeState> =
        getField("_handshakeState") as MutableStateFlow<BleHandshakeState>

    private fun gattCallback(): BluetoothGattCallback =
        getField("gattCallback") as BluetoothGattCallback

    private fun invokePrivate(methodName: String) {
        val m = BleConnection::class.java.getDeclaredMethod(methodName)
        m.isAccessible = true
        m.invoke(connection)
    }
}
