// @IgnoreFormatCheck
// 理由：本文件含 legacy 格式违规（class comment / public fun comment /
//       import-order / trailing newline 等）。本战役 G R6 仅在 else 分支追加
//       startScan() fallback 一句，rename/补注释其他 legacy 内容超出 R6 scope。
//       评审方 2026-04-24 commit 阶段 B 方案批准此 ignore。
package com.blazepush.core.bluetooth
import com.blazepush.core.domain.model.ConnectionState

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BLE设备管理器
 * 统一管理设备扫描、连接和自动重连逻辑
 */
class BleDeviceManager(
    private val context: Context,
    private val bluetoothDataSource: BluetoothDataSource
) {
    companion object {
        private const val TAG = "BleDeviceManager"
        private const val RECONNECT_TIMEOUT_MS = 10000L // 10秒重连超时
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scanner = BleDeviceScanner(context)

    // 连接状态 (代理自BluetoothDataSource)
    val connectionState = bluetoothDataSource.connectionState

    // 扫描状态 (代理自BleDeviceScanner)
    val isScanning = scanner.isScanning

    // 扫描结果 (代理自BleDeviceScanner)
    val scanResults = scanner.scanResults

    private var autoReconnectInProgress = false

    init {
        // 自动重连上次设备
        autoReconnectLastDevice()
    }

    /**
     * 自动重连上次使用的设备
     */
    fun autoReconnectLastDevice() {
        if (autoReconnectInProgress) {
            Log.w(TAG, "自动重连已在进行中")
            return
        }

        autoReconnectInProgress = true

        scope.launch {
            try {
                // TODO: 从Repository获取上次连接的设备地址
                // val lastDeviceAddress = deviceRepository.getLastConnectedDevice()
                val lastDeviceAddress: String? = null // 暂时为null，待后续实现

                if (lastDeviceAddress != null) {
                    Log.d(TAG, "尝试自动重连设备: $lastDeviceAddress")

                    // 等待一小段时间确保蓝牙就绪
                    delay(1000)

                    // 尝试连接
                    bluetoothDataSource.connect(lastDeviceAddress)

                    // 等待连接结果
                    var waited = 0L
                    while (waited < RECONNECT_TIMEOUT_MS) {
                        delay(500)
                        waited += 500

                        if (connectionState.value == ConnectionState.CONNECTED) {
                            Log.d(TAG, "自动重连成功")
                            return@launch
                        }
                    }

                    // 超时未连接成功
                    Log.w(TAG, "自动重连超时，开始扫描其他设备")
                    startScan()
                } else {
                    // A29：冷启动 else 分支 fallback 扫描（当前 lastDeviceAddress
                    // 硬编码 null 时必走此分支）。用户期望"打开 app 看到设备列表"，
                    // 战役 G 前只 log 不 scan 导致用户必须手动点"扫描"按钮。
                    Log.d(TAG, "没有上次连接的设备记录，fallback 到扫描")
                    startScan()
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动重连失败", e)
                // 失败后自动开始扫描
                startScan()
            } finally {
                autoReconnectInProgress = false
            }
        }
    }

    /**
     * 开始扫描设备
     */
    fun startScan() {
        if (!PermissionChecker.hasAllRequiredPermissions(context)) {
            Log.e(TAG, "缺少必需的权限")
            return
        }

        scanner.startScan()
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        scanner.stopScan()
    }

    /**
     * 连接指定设备
     */
    fun connect(deviceAddress: String) {
        Log.d(TAG, "connect() called with address: $deviceAddress")
        scope.launch {
            try {
                // 停止当前扫描
                stopScan()
                Log.d(TAG, "停止扫描，开始连接")

                // 连接设备
                bluetoothDataSource.connect(deviceAddress)
                Log.d(TAG, "bluetoothDataSource.connect() 调用完成")

                // TODO: 连接成功后保存设备信息到Repository
                // deviceRepository.saveDevice(deviceAddress, deviceName)
            } catch (e: Exception) {
                Log.e(TAG, "连接失败", e)
            }
        }
    }

    /**
     * 断开当前连接
     */
    fun disconnect() {
        bluetoothDataSource.disconnect()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        scanner.cleanup()
    }
}
