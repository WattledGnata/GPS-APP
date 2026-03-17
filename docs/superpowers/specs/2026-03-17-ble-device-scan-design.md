# BLE设备扫描和连接功能设计文档

**日期**: 2026-03-17
**版本**: 1.0
**作者**: Claude Sonnet 4.6

## 1. 功能��述

### 1.1 背景
当前应用缺少BLE设备扫描功能，用户无法发现和连接附近的RaceChrono GPS设备。需要实现完整的设备扫描、连接和管理功能。

### 1.2 目标
- 实现BLE设备扫描功能，发现附近的RaceChrono GPS设备
- 支持自动重连上次使用的设备
- 提供友好的设备扫描和连接UI
- 确保功能扩展性，便于未来支持其他设备

### 1.3 核心需求
1. **设备扫描**: 扫描附近的BLE设备，根据SERVICE_UUID过滤RaceChrono设备
2. **自动重连**: 应用启动后自动尝试连接上次使用的设备
3. **用户可控**: 提供"停止扫描"按钮，用户可随时停止或重新扫描
4. **智能失败处理**: 重连失败后自动开始扫描其他设备
5. **设备记忆**: 持久化存储上次连接的设备地址

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────┐
│              DeviceConnectionScreen                  │
│  ┌───────────────────────────────────────────────┐  │
│  │  连接状态卡片 + GPS信号卡片                     │  │
│  │  [如果未连接，显示"扫描设备"按钮]               │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────┐
│            BleDeviceManager (新增)                   │
│  - 自动重连上次设备                                  │
│  - 管理扫描状态                                      │
│  - 持久化设备地址                                    │
└─────────────────────────────────────────────────────┘
                        │
                        ���─────────────────┐
                        ▼                 ▼
┌──────────────────────────┐  ┌──────────────────────────┐
│   BleDeviceScanner       │  │   BleConnection (现有)    │
│  - 扫描BLE设备           │  │  - GATT连接              │
│  - 过滤RaceChrono设备    │  │  - 服务发现              │
│  - 返回设备列表          │  │  - 数据通知              │
└──────────────────────────┘  └──────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────┐
│         DeviceScanDialog (新增UI)                    │
│  - 扫描进度显示                                      │
│  - 设备列表（名称+信号强度）                         │
│  - 停止扫描按钮                                      │
└─────────────────────────────────────────────────────┘
```

### 2.2 分层设计

**UI层**
- `DeviceConnectionScreen`: 修改现有屏幕，添加扫描按钮和对话框
- `DeviceScanDialog`: 新增设备扫描对话框

**ViewModel层**
- `BleDeviceManager`: 新增设备管理器，统一管理扫描和连接状态

**数据层**
- `BleDeviceScanner`: 新增设备扫描器
- `BleConnection`: 现有连接管理器
- `BluetoothDeviceRepository`: 现有设备持久化Repository

### 2.3 单一数据源原则

遵循现有的单一数据源设计原则：
- `BleDeviceManager` 作为唯一的扫描和连接状态发射点
- `BluetoothDataSource` 保持作为唯一的GPS数据发射点
- 避免多个数据通道导致的状态不一致

## 3. 核心组件设计

### 3.1 BleDeviceScanner（设备扫描器）

**职责**: 扫描BLE设备并过滤RaceChrono设备

**核心功能**:
```kotlin
class BleDeviceScanner(
    private val context: Context
) {
    // 扫描结果流
    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    // 扫描状态
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // 开始扫描
    fun startScan()

    // 停止扫描
    fun stopScan()
}

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,  // 信号强度
    val lastSeen: Long
)
```

**扫描逻辑**:
1. 使用 `BluetoothLeScanner.startScan()` 开始扫描
2. 通过 `ScanCallback` 接收扫描结果
3. 根据SERVICE_UUID (00001ff8-0000-1000-8000-00805f9b34fb) 过滤设备
4. 实时更新扫描结果列表
5. 支持用户手动停止扫描

### 3.2 BleDeviceManager（设备管理器）

**职责**: 统一管理设备扫描、连接和重连逻辑

**核心功能**:
```kotlin
class BleDeviceManager(
    private val context: Context,
    private val scanner: BleDeviceScanner,
    private val bluetoothDataSource: BluetoothDataSource,
    private val deviceRepository: BluetoothDeviceRepository
) {
    // 连接状态流
    val connectionState: StateFlow<ConnectionState>

    // 扫描状态流
    val isScanning: StateFlow<Boolean>

    // 扫描结果流
    val scanResults: StateFlow<List<ScannedDevice>>

    // 自动重连上次设备
    fun autoReconnectLastDevice()

    // 开始扫描
    fun startScan()

    // 停止扫描
    fun stopScan()

    // 连接设备
    fun connect(deviceAddress: String)

    // 断开连接
    fun disconnect()
}
```

**状态管理**:
- **启动时**: 自动调用 `autoReconnectLastDevice()`
- **重连成功**: 更新连接状态，进入就绪状态
- **重连失败**: 自动调用 `startScan()`
- **用户扫描**: 显示扫描对话框，用户选择设备后连接

### 3.3 DeviceScanDialog（设备扫描对话框）

**职责**: 显示设备扫描进度和结果列表

**UI设计**:
```kotlin
@Composable
fun DeviceScanDialog(
    isScanning: Boolean,
    devices: List<ScannedDevice>,
    onStopScan: () -> Unit,
    onDeviceClick: (String) -> Unit,
    onDismiss: () -> Unit
)
```

**UI元素**:
1. **扫描进度**:
   - 扫描中: 显示"正在扫描设备..." + 进度指示器
   - 扫描停止: 显示"扫描停止"

2. **设备列表**:
   - 设备名称（如 "RaceChrono GPS"）
   - 信号强度（RSSI值，用进度条显示）
   - 最后发现时间

3. **操作按钮**:
   - "停止扫描"按钮（仅在扫描时显示）
   - 点击设备项进行连接

4. **空状态提示**:
   - 未找到设备时显示"未找到RaceChrono设备"

## 4. 数据流设计

### 4.1 应用启动流程

```
应用启动
   │
   ▼
检查上次连接设备
   │
   ├─ 有记录 → 自动重连
   │             │
   │             ├─ 成功 → 进入就绪状态
   │             │
   │             └─ 失败 → 自动开始扫描
   │
   └─ 无记录 → 显示"扫描设备"按钮
```

### 4.2 设备扫描流程

```
用户点击"扫描设备"
   │
   ▼
开始扫描
   │
   ├─ 扫描中 → 实时更新设备列表
   │             │
   │             ├─ 用户点击"停止" → 停止扫描
   │             │
   │             └─ 用户点击设备 → 停止扫描并连接
   │
   └─ 扫描完成 → 显示结果列表
```

### 4.3 设备连接流程

```
用户选择设备
   │
   ▼
停止扫描
   │
   ▼
调用BluetoothDataSource.connect()
   │
   ├─ 连接成功 → 进入就绪状态
   │
   └─ 连接失败 → 显示错误提示
```

## 5. UI设计

### 5.1 DeviceConnectionScreen 修改

**新增元素**:

1. **"扫描设备"按钮**:
   - 位置: 在连接状态卡片下方
   - 显示条件: 未连接且不在扫描中
   - 样式: 主要按钮，全宽

2. **扫描中状态**:
   - 在连接状态卡片中显示"扫描中..."状态
   - 显示已发现的设备数量

### 5.2 DeviceScanDialog 设计

**布局**:
```
┌─────────────────────────────────────┐
│  扫描设备                  [✕]      │
├─────────────────────────────────────┤
│  正在扫描设备...                   │
│  ◌◌◌◌◌ (进度指示器)                │
├─────────────────────────────────────┤
│  [停止扫描]                        │
├─────────────────────────────────────┤
│  发现的设备:                        │
│  ┌───────────────────────────────┐ │
│  │ RaceChrono GPS         [▂▃▅] │ │
│  │ 信号: 强                    │ │
│  └───────────────────────────────┘ │
│  ┌───────────────────────────────┐ │
│  │ RC GPS Device          [▂▃_] │ │
│  │ 信号: 中                    │ │
│  └─���─────────────────────────────┘ │
└─────────────────────────────────────┘
```

**交互**:
- 点击设备项: 停止扫描并尝试连接
- 点击停止按钮: 停止扫描，显示当前结果
- 点击关闭或外部: 关闭对话框

## 6. 错误处理

### 6.1 扫描失败

**场景**: 用户未授权蓝牙权限或蓝牙未开启

**处理**:
1. 捕获异常并显示错误提示
2. 引导用户开启蓝牙或授权权限
3. 提供"重试"按钮

### 6.2 连接失败

**场景**: 设备不在范围内、设备电量耗尽、连接超时

**处理**:
1. 显示错误提示："无法连接到设备"
2. 提供"重新扫描"按钮
3. 记录错误日志

### 6.3 自动重连失败

**场景**: 上次设备不在范围内

**处理**:
1. 静默失败，不显示错误对话框
2. 自动开始扫描其他设备
3. 在扫描列表中优先显示上次设备（如果被发现）

## 7. 权限需求

### 7.1 Android 12及以上（API 31+）

需要在 `AndroidManifest.xml` 中声明:
```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

### 7.2 Android 12以下（API 30及以下）

需要:
```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

### 7.3 运行时权限请求

在首次扫描前检查并请求权限:
- BLUETOOTH_SCAN / BLUETOOTH_CONNECT
- ACCESS_FINE_LOCATION

## 8. 数据持久化

### 8.1 设备地址存储

使用现有的 `BluetoothDeviceRepository` 存储上次连接的设备地址:

```kotlin
@Entity(tableName = "bluetooth_devices")
data class BluetoothDeviceEntity(
    @PrimaryKey val deviceAddress: String,
    val deviceName: String?,
    val lastConnected: Long,
    val isFavorite: Boolean = false
)
```

### 8.2 存储策略

- 连接成功后更新 `lastConnected` 时间戳
- 应用启动时查询最新的设备记录
- 如果多条记录，选择 `lastConnected` 最新的

## 9. 性能优化

### 9.1 扫描优化

- **扫描时长**: 不设置固定超时，由用户控制
- **扫描间隔**: 使用 `ScanSettings.SCAN_MODE_LOW_LATENCY` 快速扫描
- **过滤策略**: 在扫描时使用 `ScanFilter` 过滤SERVICE_UUID，减少无关设备

### 9.2 内存优化

- 扫描结果列表限制最多显示20个设备
- 去重: 同一地址的设备只保留RSSI最强的记录
- 定期清理超过5分钟未更新的设备

### 9.3 电量优化

- 仅在需要时扫描
- 连接成功后立即停止扫描
- 提供明显的"停止扫描"按钮

## 10. 测试策略

### 10.1 单元测试

- `BleDeviceScanner`: 测试扫描逻辑、过滤逻辑
- `BleDeviceManager`: 测试状态管理、自动重连逻辑
- 设备过滤: 测试SERVICE_UUID匹配

### 10.2 集成测试

- 扫描 → 连接 → 数据接收 完整流程
- 自动重连失败 → 扫描流程
- 权限请求流程

### 10.3 UI测试

- 设备扫描对话框显示和交互
- 扫描按钮状态变化
- 错误提示显示

## 11. 未来扩展

### 11.1 支持其他设备

当前设计已考虑扩展性:
- `BleDeviceScanner` 支持配置多个SERVICE_UUID
- `BleDeviceManager` 可以管理不同类型的设备
- UI可以显示设备类型标识

### 11.2 设备管理功能

未来可以添加:
- 设备重命名
- 多设备收藏
- 设备优先级排序
- 手动添加设备（通过地址）

## 12. 实现优先级

### Phase 1 - 核心功能（本次实现）
1. 实现 `BleDeviceScanner`
2. 实现 `BleDeviceManager`
3. 实现 `DeviceScanDialog`
4. 修改 `DeviceConnectionScreen`
5. 实现自动重连逻辑

### Phase 2 - 优化（后续）
1. 设备信号强度图标优化
2. 扫描结果动画效果
3. 错误处理完善
4. 性能优化

## 13. 风险和挑战

### 13.1 Android版本兼容性

**风险**: Android 12权限模型变化

**解决方案**:
- 使用 `Build.VERSION.SDK_INT` 判断
- 分别处理不同版本的权限请求

### 13.2 设备发现延迟

**风险**: 某些设备可能需要较长时间才能被发现

**解决方案**:
- 不设置扫描超时，由用户控制
- 提供明显的扫描进度反馈

### 13.3 假阳性设备

**风险**: 其他设备可能使用相同的SERVICE_UUID

**解决方案**:
- 在连接时验证服务的完整性和特征值
- 连接失败时提示用户

## 14. 总结

本设计文档详细描述了BLE设备扫描和连接功能的完整设计方案。核心设计原则包括:

1. **单一数据源**: 遵循现有架构，避免状态不一致
2. **用户可控**: 提供明确的扫描控制按钮
3. **智能重连**: 启动时自动重连，失败后自动扫描
4. **精准过滤**: 只显示RaceChrono设备
5. **扩展性**: 易于支持其他设备类型

该设计在用户体验、技术实现和未来扩展性之间取得了良好平衡。
