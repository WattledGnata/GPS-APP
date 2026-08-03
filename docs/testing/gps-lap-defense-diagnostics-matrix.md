# GPS 圈速防御：诊断与测试矩阵

## 证据边界

- `DiagnosticEvidence` 每秒写入现有可打包的轮转日志；只包含 App/Bluetooth/BLE、代次与序号、Main/Time 握手与恢复 gate、Main stale/RX age、Camera 状态、Battery 能力状态。
- 卫星数、fix、HDOP 只出现在 `main(...)` 内。项目没有、也不声明独立“卫星通道”。
- 日志不写坐标、设备地址、Session ID、用户视频实体或密钥；现有诊断 ZIP 仍明确排除视频实体。
- JVM/模拟序列只证明状态机与策略。当前没有指定 BLE 硬件在手，所有硬件项均为 **未验收**。

## 自动化与验收矩阵

| 场景 | JVM / 无硬件自动化 | Android 模拟器 | 真机 + 指定 BLE GPS | 当前结论 |
|---|---|---|---|---|
| 冷启动等待 2–3 分钟 | `DiagnosticRecoveryMatrixTest.coldStart_twoToThreeMinutePolicy_usesVirtualClockWithoutWaiting` 使用虚拟 elapsed time 验证 120s/180s 阶段，不真实等待 | 可观察 App 生命周期和日志打包，不能证明 BLE/GPS | 冷启动后连续观察至少 3 分钟，核对 adapter、BLE、gen/seq、Main/Time gate、RX age | JVM 策略可验；硬件 **未验收** |
| 超距后返回 | `DiagnosticRecoveryMatrixTest.outOfRangeReturn_andPhoneBluetoothCycle_preserveGenerationEvidence` + `BleDeviceManagerReconnectOrchestrationTest` 验证重连意图/扫描发现目标 | 无真实射频距离 | 携设备离开射频范围至断开，再返回；核对新 generation、seq 从新代次恢复、旧回调未污染 | JVM 模拟可验；硬件 **未验收** |
| GPS 关机再开 | `gpsPowerCycle_bleCanRemainConnectedWhileMainBecomesStaleThenRecovers` 验证 BLE 仍连但 Main stale，以及同/新代次 Main 恢复证据 | 无真实 GPS 电源 | 分别覆盖“BLE 未断但 Main 静默”和“整机断链”；核对 Main/Time gate 重新满足 | JVM 状态序列可验；硬件 **未验收** |
| 手机蓝牙关开 | 上述 adapter OFF→ON + `BleReconnectTriggerWiringTest` 验证 STATE_ON 触发立即重连 | 可用系统设置做 UI 冒烟，但不能等价真实硬件 | 关闭手机蓝牙至 DISCONNECTED，再开启；核对 adapter、重连、generation、Battery 重新探测 | JVM/接线可验；硬件 **未验收** |
| App 强杀后恢复 | `forceKillBoundary_startsNewProcessEvidenceWithoutInventingRecovery` + `IncompleteLapSessionRecoveryTest` 验证新进程边界和遗留 Session 恢复策略 | 可强停/重启验证进程日志与 Room 恢复 | 录制/计时中强杀后重启；核对旧日志、恢复报告、Camera 不虚假续录、BLE 新恢复链 | JVM 边界可验；模拟器未运行；硬件 **未验收** |
| 相机伴随 Main gap | `cameraStateRemainsVisibleAlongsideMainGap` 验证同一快照同时保留 Camera 与 Main stale/RX age | 可用模拟相机 + 模拟数据做 UI 冒烟 | 真机录制中制造 Main 静默，核对视频状态与 gap 证据独立、恢复后 gate | JVM 快照可验；模拟器未运行；硬件 **未验收** |
| 断联跨起终点 | `LapTimingEngineTest` 的 `recordMainGap` 跨起终点用例验证 gap 标记受影响 gate | 回放可做补充，不能替代射频断联 | 驶近起终点制造断联并恢复；核对该圈证据/置信度，禁止当无条件最佳圈 | JVM 圈速引擎可验；硬件 **未验收** |
| 断联不跨起终点 | `LapTimingEngineTest` 的 `recordMainGap` 不跨 gate 用例验证 gap 不虚构 gate | 回放可做补充 | 远离 gate 制造同等时长断联；核对 gap 存在但 affected gate 为空 | JVM 圈速引擎可验；硬件 **未验收** |
| Battery 四态 | `handshakeMainTimeRecoveryGate_andAllBatteryStatesAreExportable` 覆盖 Pending/Available(含 0)/Unsupported/Failed | 只能做 UI/日志状态注入 | 分别使用支持 Battery Service、无该服务、读失败的指定硬件验证 | JVM 格式可验；硬件 **未验收** |
| Room v9→v10 | `LapEvidenceMigration9To10SqliteTest` 是 Android instrumentation 的真实 SQLite fixture；不是 JVM/SQL-only 证明，也不是完整 Room runtime `MigrationTestHelper` | 在可用 emulator 上运行 `:core:data:connectedDebugAndroidTest` | 与 BLE 无关 | androidTest 编译为最低门槛；运行状态单独报告 |

## 真机采集检查点

每个硬件场景都应导出诊断包并按时间线检查：`PROCESS_CREATED/FOREGROUND/BACKGROUND`、Bluetooth adapter、BLE state、`gen/seq`、Main/Time handshake、`timingGate`、`main(stale,rxAgeMs,deadlineMs,recovery,gate)`、Camera、Battery。只有 Main 块可用于卫星/fix/HDOP 结论。
