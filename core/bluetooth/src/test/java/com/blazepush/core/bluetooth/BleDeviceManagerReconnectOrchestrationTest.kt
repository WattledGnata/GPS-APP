package com.blazepush.core.bluetooth

import android.content.Context
import com.blazepush.core.domain.model.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class BleDeviceManagerReconnectOrchestrationTest {

    @Test
    fun `CONNECTING 和 CONNECTED 时不得启动扫描`() = runTest {
        val fixture = fixture(ConnectionState.CONNECTING)
        fixture.manager.startScan()
        verify(fixture.scanner, never()).startScan()

        fixture.states.value = ConnectionState.CONNECTED
        fixture.manager.startScan()
        verify(fixture.scanner, never()).startScan()
    }

    @Test
    fun `连接前同步停扫且重复连接由 data source 合并`() = runTest {
        val fixture = fixture(ConnectionState.DISCONNECTED)
        fixture.manager.connect("AA:01")
        runCurrent()

        inOrder(fixture.scanner, fixture.source).apply {
            verify(fixture.scanner).stopScan()
            verify(fixture.source).connect("AA:01")
        }
    }

    @Test
    fun `扫描命中目标后立即停扫再触发连接`() = runTest {
        val fixture = fixture(ConnectionState.DISCONNECTED)
        fixture.manager.connect("AA:02")
        runCurrent()
        fixture.scanResults.value = listOf(ScannedDevice("GPS", "AA:02", -40, 1L))
        runCurrent()

        verify(fixture.scanner, org.mockito.Mockito.atLeast(2)).stopScan()
        verify(fixture.source).requestImmediateReconnect("scan target discovered")
    }

    @Test
    fun `前台 lap session 蓝牙重开信号都汇入 immediate trigger`() = runTest {
        val fixture = fixture(ConnectionState.DISCONNECTED)
        whenever(fixture.source.requestImmediateReconnect(any())).thenReturn(true)

        fixture.manager.requestImmediateReconnect("app foreground")
        fixture.manager.requestImmediateReconnect("lap session entered")
        fixture.manager.requestImmediateReconnect("bluetooth enabled")

        verify(fixture.source).requestImmediateReconnect("app foreground")
        verify(fixture.source).requestImmediateReconnect("lap session entered")
        verify(fixture.source).requestImmediateReconnect("bluetooth enabled")
    }

    @Test
    fun `主动断开后生命周期触发无效且忘记当前目标立即失效`() = runTest {
        val fixture = fixture(ConnectionState.DISCONNECTED)
        fixture.manager.connect("AA:03")
        runCurrent()
        fixture.manager.forget("AA:03")
        fixture.manager.requestImmediateReconnect("app foreground")
        runCurrent()

        verify(fixture.source).disconnect()
        verify(fixture.source, never()).requestImmediateReconnect("app foreground")
        assertEquals(emptyList<ScannedDevice>(), fixture.scanResults.value)
    }

    private fun TestScopeFixture.managerState(state: ConnectionState) {
        states.value = state
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        state: ConnectionState,
    ): TestScopeFixture {
        val context = mock(Context::class.java)
        val source = mock(BluetoothDataSource::class.java)
        val scanner = mock(BleScanner::class.java)
        val states = MutableStateFlow(state)
        val scans = MutableStateFlow<List<ScannedDevice>>(emptyList())
        val scanning = MutableStateFlow(false)
        whenever(source.connectionState).thenReturn(states)
        whenever(scanner.scanResults).thenReturn(scans)
        whenever(scanner.isScanning).thenReturn(scanning)
        return TestScopeFixture(
            source = source,
            scanner = scanner,
            states = states,
            scanResults = scans,
            manager = BleDeviceManager(
                context = context,
                bluetoothDataSource = source,
                scanner = scanner,
                dispatcher = StandardTestDispatcher(testScheduler),
                autoReconnectOnInit = false,
            ),
        )
    }

    private data class TestScopeFixture(
        val source: BluetoothDataSource,
        val scanner: BleScanner,
        val states: MutableStateFlow<ConnectionState>,
        val scanResults: MutableStateFlow<List<ScannedDevice>>,
        val manager: BleDeviceManager,
    )
}
