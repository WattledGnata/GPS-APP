## Why

性能测试结束后的 G 值曲线肉眼可见高频尖刺（实测 0.3~0.5G 量级伪冲击），根因是 `GpsDataFilter` / `CalculateResultUseCase` / `GForceChart` 三处加速度都用相邻两帧一阶差分且无任何平滑；叠加 A56 binary 持久化把速度量化到 0.1 km/h（量化下限 ≈ 0.07G/帧），令 maxAcceleration 统计与曲线展示均不可信。

## What Changes

- 新增 `AccelerationSmoother`（5 点 Savitzky-Golay 中心差分 + forward/backward 边界系数）作为单一加速度计算入口，替代当前散落在三处的相邻一阶差分
- `CalculateResultUseCase.calculateAccelerations` 与 `feature/test` 的 `GForceChart` 改为调用 `AccelerationSmoother`，相同输入得到相同曲线（消除 UI 与离线统计算法漂移）
- `GpsDataFilter.calculateAcceleration` 把 `current.speed` / `previousRaw.speed` 改用 median 9 点平滑后的速度（与 `outputSpeed` 同源），消除"输出 speed 是平滑过的、内部 acceleration 用 raw"的内部不一致
- **BREAKING** `TestResult` 与 `TestRecordEntity` 拆分加速度统计字段：`maxAcceleration` 语义收紧为"正向最大加速 G"，新增 `maxDeceleration`（负向最大制动 G，绝对值），移除 `CalculateResultUseCase` 内对 `dv/dt` 的 `Math.abs()` 污染
- `GForceChart` 把 `abs(gForce) >= 3.0 → return null` 改为 `clip 到 ±3G`，避免折线在尖刺处出现 V 字断点
- design.md §2 数据证据章：从默认真机 `8KE0219522008434` 拉取存量 0-100 测试 binary（含 10.2s / 7.2s 两条用户实跑记录），离线运行旧/新算法生成对比图作为 round 启动 baseline
- 单元测试：合成"匀加速 + 高频小噪声"信号断言 SG 后加速度 RMSE 降幅 ≥ 70%；合成"单点速度跳变"断言伪冲击被 SG + 既有 9 点 median 级联抑制
- 真机验证：装机后跑两次 0-100 测试，确认 G 值曲线肉眼无高频尖刺，maxAcceleration / maxDeceleration 数值与体感一致

## Capabilities

### New Capabilities
- `perftest-acceleration-smoothing`: 性能测试加速度 / G 值的信号处理、统计与 UI 展示契约（平滑差分算法、G 值统计方向区分、UI 边界 clip）

### Modified Capabilities
- 无（既有 specs 不涉及性能测试加速度统计）

## Impact

**受影响模块**：
- `core/domain/src/main/.../usecase/GpsDataFilter.kt` — `calculateAcceleration` 速度源切换
- `core/domain/src/main/.../usecase/CalculateResultUseCase.kt` — 离线 G 计算改走 `AccelerationSmoother`，统计字段拆分
- `core/domain/src/main/.../model/TestModels.kt` — `TestResult.maxAcceleration` 语义收紧 + 新增 `maxDeceleration`
- `core/data/src/main/.../entity/TestRecordEntity.kt` — Room schema migration 增 `maxDeceleration` 列
- `core/data/src/main/.../repository/TestResultRepository.kt` — `saveResult` 写入新字段
- `feature/test/src/main/.../ui/components/SpeedChart.kt` — `GForceChart` 调用统一 smoother + clip 改造
- `feature/test/src/main/.../ui/tracktech/PerformanceResultScreen.kt` — 若 UI 卡片展示 maxDeceleration 则同步追加
- 新增：`core/domain/src/main/.../usecase/AccelerationSmoother.kt`
- 新增：`core/domain/src/test/.../usecase/AccelerationSmootherTest.kt`

**协议兼容性**：
- 不涉及 RaceChrono BLE 协议改动
- 不涉及 binary telemetry 文件格式改动（17-byte sample schema 保持，本 round 仅消费现有文件）
- Room schema 向前兼容：迁移脚本新增 `maxDeceleration: Double DEFAULT 0.0`；存量记录该字段为 0.0，UI 渲染需对 0.0 做"未填充"展示降级

**双端协同**：
- 仅接收端 gps-app 改动，simulator / RaceChrono 协议链路无关

**与平行 round 的边界**：
- 不动 `dataPoints` 持久化方案（属于 `speed-curve-real-data-persistence-deferred.md` 主题）
- 不动 lap timing 通道滤波接入（属于 `laptime-gps-filter-integration-deferred.md` 主题）
- 不动 `GpsDataFilter` 的 9 点 median windowSize / 物理约束阈值（baseline 不动）
- 不引入 IMU / 手机加速度计 / Kalman / Butterworth（探索阶段已显式拒绝，留给未来 sensor fusion round）
