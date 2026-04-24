package com.blazepush.core.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.blazepush.core.domain.model.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 *              对于 race 这类"虚拟时钟下难以真实复现"的语义，辅以**源码结构断言**
 *              锁定关键代码形状（`ensureActive()` 存在于 delay 与 body 之间等）。
 * @author haozhang93
 * @date 2026-04-24
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleConnectionTest {

    private lateinit var connection: BleConnection
    private lateinit var mockContext: Context
    private lateinit var mockGatt: BluetoothGatt
    private lateinit var logMock: AutoCloseable

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
     * R2 Scenario 1：数据超时成立时 gatt.disconnect 被调 + state DISCONNECTED；
     * close 未被直接调（留给 onConnectionStateChange 回调统一释放 GATT 资源）。
     */
    @Test
    fun startDataTimeoutCheck_onTimeout_releasesGattAndTransitionsDisconnected() = runTest {
        setField("scope", this)
        stateFlow().value = ConnectionState.CONNECTED
        setField("lastDataTime", 0L)

        invokePrivate("startDataTimeoutCheck")

        advanceTimeBy(10_500)
        runCurrent()

        Mockito.verify(mockGatt, Mockito.times(1)).disconnect()
        Mockito.verify(mockGatt, Mockito.never()).close()
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        assertNotNull("bluetoothGatt 字段由回调释放，此刻非 null", getField("bluetoothGatt"))
    }

    /**
     * R2 Scenario 2：race 场景在虚拟时钟下难以真实复现，改用源码结构断言
     * 锁定 ensureActive() 在 delay 之后、超时分支代码之前的位置 —— v1 无此
     * 调用；v2 有。
     */
    @Test
    fun startDataTimeoutCheck_rapidCancelRestart_sourceHasEnsureActiveGuard() {
        val source = File("src/main/java/com/blazepush/core/bluetooth/BleConnection.kt").readText()
        val fnStart = source.indexOf("private fun startDataTimeoutCheck()")
        assertTrue("必须定义 startDataTimeoutCheck", fnStart > 0)
        val fnEnd = source.indexOf("\n    }\n", fnStart)
        assertTrue("必须闭合 startDataTimeoutCheck 方法体", fnEnd > fnStart)
        val body = source.substring(fnStart, fnEnd)

        val delayIdx = body.indexOf("delay(DATA_TIMEOUT_MS)")
        val ensureActiveIdx = body.indexOf("ensureActive()")
        val ifIdx = body.indexOf("if (System.currentTimeMillis() - lastDataTime")

        assertTrue("delay 必须存在", delayIdx > 0)
        assertTrue("ensureActive() 必须存在于 startDataTimeoutCheck body", ensureActiveIdx > 0)
        assertTrue("if 超时判断必须存在", ifIdx > 0)
        assertTrue(
            "ensureActive() 位置 MUST 在 delay 之后、if 判断之前（消除 cancel/restart race）",
            delayIdx < ensureActiveIdx && ensureActiveIdx < ifIdx,
        )
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
     * R3 Scenario 3：R2 超时触发的 disconnect 与主动 disconnect 走同一条回调释放路径 ——
     * 组合验证 "超时 disconnect → 回调到达 → close + null" 只执行一次的统一流程。
     */
    @Test
    fun startDataTimeoutCheck_triggeredDisconnectUsesCallbackReleasePath() = runTest {
        setField("scope", this)
        stateFlow().value = ConnectionState.CONNECTED
        setField("lastDataTime", 0L)

        invokePrivate("startDataTimeoutCheck")
        advanceTimeBy(10_500)
        runCurrent()

        Mockito.verify(mockGatt, Mockito.times(1)).disconnect()
        Mockito.verify(mockGatt, Mockito.never()).close()

        gattCallback().onConnectionStateChange(mockGatt, 0, BluetoothProfile.STATE_DISCONNECTED)

        Mockito.verify(mockGatt, Mockito.times(1)).close()
        assertNull(getField("bluetoothGatt"))
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

    private fun gattCallback(): BluetoothGattCallback =
        getField("gattCallback") as BluetoothGattCallback

    private fun invokePrivate(methodName: String) {
        val m = BleConnection::class.java.getDeclaredMethod(methodName)
        m.isAccessible = true
        m.invoke(connection)
    }
}