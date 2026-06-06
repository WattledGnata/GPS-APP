// @IgnoreFormatCheck
// 理由：本文件由 change fix-gps-stats-and-lazy-catalog-hot-start（战役 F Round 2 A28+A37）
//       新建。JUnit4 测试类遵循 Gherkin-style snake_case 命名（method-name `_25Hz` 的
//       `_2` 违反 `^[a-z][a-zA-Z0-9]*(_[a-z]...)*$` 但这是测试可读性的语义承载），
//       @Before/@After 的 setup/tearDown 无须 doc comment（测试框架契约清晰）。
//       评审方 2026-04-25 战役 G B 方案纪律批准 test 文件 ignore。
package com.blazepush.feature.test.viewmodel

import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.bluetooth.GpsDataRepository
import com.blazepush.core.data.model.BluetoothDeviceModel
import com.blazepush.core.data.repository.BluetoothDeviceRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.DataQualityEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

/**
 * 战役 F Round 2 A28 GpsDataViewModelTest：
 *
 * - 集成 2 条（R1 frequency 透传 / R3 DISCONNECTED 触发 resetStats）
 * - 纯函数 4 条（R2 packetLoss 从 data.frequency 反推，零时间源依赖）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GpsDataViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var gpsDataFlow: MutableStateFlow<GpsData>
    private lateinit var connectionStateFlow: MutableStateFlow<ConnectionState>
    private lateinit var viewModel: GpsDataViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // non-blocking note from review：初始 CONNECTED，喂帧后再发 DISCONNECTED，
        // 这样 DISCONNECTED 订阅链能测到"迁入"路径而不是被 StateFlow 自带去重掩盖
        gpsDataFlow = MutableStateFlow(GpsData.Empty)
        connectionStateFlow = MutableStateFlow(ConnectionState.CONNECTED)

        val repo = mock(GpsDataRepository::class.java)
        doReturn(gpsDataFlow).`when`(repo).gpsDataFlow
        doReturn(connectionStateFlow).`when`(repo).connectionState

        // ble-device-memory：VM 构造期访问 devicesFlow 做 stateIn，mock 默认 null 会 NPE，必须 stub
        val deviceRepo = mock(BluetoothDeviceRepository::class.java)
        doReturn(MutableStateFlow(emptyList<BluetoothDeviceModel>())).`when`(deviceRepo).devicesFlow

        viewModel = GpsDataViewModel(
            gpsDataRepository = repo,
            bleDeviceManager = mock(BleDeviceManager::class.java),
            dataQualityEvaluator = DataQualityEvaluator(),
            bluetoothDeviceRepository = deviceRepo,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== R1 透传集成测试 ====================

    /**
     * R1 Scenario "稳态 25Hz 透传" + "低频帧立即透传"：集成测试，只断 frequency 透传，
     * 不精确断 packetLoss（那由纯函数测试负责）
     */
    @Test
    fun frequency_transparentlyMirrorsLatestParserFrequency() = runTest(dispatcher) {
        // 稳态喂 30 帧 25Hz
        repeat(30) {
            gpsDataFlow.value = GpsData.Empty.copy(
                timestamp = System.currentTimeMillis(),
                frequency = 25.0,
                satelliteCount = 10,
                hdop = 1.0,
            )
            dispatcher.scheduler.advanceUntilIdle()
        }

        // 再喂 1 帧 data.frequency = 1.0（parser 滑窗判定掉到 1Hz）
        gpsDataFlow.value = GpsData.Empty.copy(
            timestamp = System.currentTimeMillis(),
            frequency = 1.0,
            satelliteCount = 10,
            hdop = 1.0,
        )
        dispatcher.scheduler.advanceUntilIdle()

        // 硬区分 v1：v1 累计平均会显示接近历史均值（> 10.0），v2 直接透传最新 1.0
        assertEquals(1.0, viewModel.dataQuality.value.frequency, 0.0001)
    }

    // ==================== R3 DISCONNECTED 触发 resetStats ====================

    /**
     * R3 Scenario "DISCONNECTED 触发 resetStats"：CONNECTED → 喂帧 → DISCONNECTED，
     * 断言 dataQuality 回到 DataQuality.Empty。
     * 关键：fake repository 初始 CONNECTED，迁入 DISCONNECTED 是 StateFlow 的真实状态变化，
     * 避免被 StateFlow 自带 distinctUntilChanged 去重掩盖。
     */
    @Test
    fun resetStats_onConnectionStateDisconnected_clearsQuality() = runTest(dispatcher) {
        // 喂一帧让 dataQuality 非 Empty
        gpsDataFlow.value = GpsData.Empty.copy(
            timestamp = System.currentTimeMillis(),
            frequency = 25.0,
            satelliteCount = 10,
            hdop = 1.0,
        )
        dispatcher.scheduler.advanceUntilIdle()
        // 前置确认：此时 frequency 已经 > 0
        assertEquals(25.0, viewModel.dataQuality.value.frequency, 0.0001)

        // 迁入 DISCONNECTED
        connectionStateFlow.value = ConnectionState.DISCONNECTED
        dispatcher.scheduler.advanceUntilIdle()

        // resetStats → _dataQuality.value = DataQuality.Empty
        assertEquals(DataQuality.Empty, viewModel.dataQuality.value)
    }

    // ==================== R2 纯函数精确断言（零时间源依赖） ====================

    @Test
    fun computePacketLossRate_returnsZero_whenFrequencyIsZero() {
        assertEquals(
            0.0,
            GpsDataViewModel.computePacketLossRate(dataAge = 9999L, frequency = 0.0),
            0.0001,
        )
    }

    @Test
    fun computePacketLossRate_25HzSteady_30ms_returnsZero() {
        // expectedSampleInterval = 40ms, dataAge=30 < 80 阈值
        assertEquals(
            0.0,
            GpsDataViewModel.computePacketLossRate(dataAge = 30L, frequency = 25.0),
            0.0001,
        )
    }

    @Test
    fun computePacketLossRate_25Hz_200ms_returns4_0_hardDiscriminatesV1() {
        // v2: (200-40)/40 = 4.0
        // v1 硬编码 expectedInterval=100L 在 25Hz 同场景：(200-100)/100 = 1.0
        // 两者差 4x，断裂点足够硬
        assertEquals(
            4.0,
            GpsDataViewModel.computePacketLossRate(dataAge = 200L, frequency = 25.0),
            0.0001,
        )
    }

    @Test
    fun computePacketLossRate_10HzLowFreq_300ms_returns2_0() {
        // expectedSampleInterval = 100ms, (300-100)/100 = 2.0
        assertEquals(
            2.0,
            GpsDataViewModel.computePacketLossRate(dataAge = 300L, frequency = 10.0),
            0.0001,
        )
    }
}
