## Why

Round 4 合并两条同属 "DI / 测试边界卫生" 主题的攻击点，scope 正交、约 50 行净 diff、半天闭环：

- **A17（D 战役尾巴）**：`AppModule.kt:90-94` `single<TrackCatalog> { runCatching { ReplayAlignedTrackCatalog(get(), PresetTrackCatalog()) }.getOrElse { PresetTrackCatalog() } }` 的 `runCatching` catch 范围**过宽**——JVM 单测无 Android Context 降级到 `PresetTrackCatalog` 是合理意图，但**真机 DI provider 创建期**任何罕见异常（如资源加载失败）也被同 `runCatching` 吞掉静默降级。Round 4 review v2 B 方案 scope 限定：A17 只覆盖 `single<TrackCatalog>` provider 创建期异常处理；`ReplayAlignedTrackCatalog.getAllTracks()` 内部 asset read fallback 是 A37（`fix-gps-stats-and-lazy-catalog-hot-start`，已核销）的 `runCatching {}.getOrNull()` 容错契约，本 round 不修订。同时 `MissingAndroidContextException` 在 AppModule 注释中提及但实际**项目中无此类型定义**，注释误导。
- **A30（H 清理）**：`AnomalyDetector` / `DataInterpolator` / `DataSmoothing` 三个类在 `core/domain/usecase/` 半接线状态：`AppModule.kt:83-85` DI 注册，但 grep 全仓无消费方（`DataSmoothing` 在 `GpsDataViewModel:171` 仅调 `reset()` 不 smooth）。后来者易误以为"已启用"，维护心智负担。

## What Changes

### A17 · DI fallback 真机异常传播修复

- **复用 Koin 自带** `org.koin.android.error.MissingAndroidContextException` 作为 cause chain 标记类型（实施期 §2 实测发现 `koin-android-3.5.3` 已自带，评审方放行 B 方案）；**不**新建项目内自建 wrapper 类型
- **保持** `AppModule.kt` `single<ReplayTrackSource> { AssetReplayTrackSource(androidContext()) }` **零改动** —— 让 `androidContext()` 在缺 Context 时自然抛 Koin 自带 `MissingAndroidContextException`，被 Koin 包装为 `InstanceCreationException(cause = MissingAndroidContextException)` 透传给 `single<TrackCatalog>` 的 caller
- `AppModule.kt` `single<TrackCatalog>` 收窄 catch：`runCatching {...}.getOrElse {...}` → `try { ... } catch (e: Throwable) { if (e.findInCauseChain<MissingAndroidContextException>() != null) PresetTrackCatalog() else throw e }`，**cause chain 检查**穿透 Koin `InstanceCreationException` 包装层；只对 cause chain 命中 Koin 自带 `MissingAndroidContextException` 标记的情况降级，其他 DI provider 创建期异常**原样上抛**让真机崩溃上报可见
- **保持** `AssetReplayTrackSource(context: Context)` 构造签名不变 + `ReplayAlignedTrackCatalog.ensureReplayTrackLoaded` 内 `runCatching {}.getOrNull()` 容错契约**保留不修订**（A37 已核销 Round 2 契约）
- **scope 边界（B 方案）**：A17 限定为 `single<TrackCatalog>` provider 创建期异常；`getAllTracks()` 内 asset read 失败由 A37 容错降级 `fallbackCatalog` 不传播到 DI 层
- **测试**（追加到现有 `DomainModuleKoinTest.kt`）：
  - 强化：`providesTrackCatalog_jvmEnvironment_fallsBackToPresetGracefully`（明确化现有隐式契约：cause chain 命中标记 → 降级 PresetTrackCatalog）
  - 新增：`providesTrackCatalog_realDeviceAssetFailure_propagatesNotSilenced` —— fake `single<ReplayTrackSource>` provider **直接抛** `IOException`（在 provider 工厂内，不在 `loadReplayJson()` 实现内），断言 `get<TrackCatalog>()` 抛包装异常 cause chain 含原始 IOException、未降级到 preset

### A30 · AnomalyDetector / DataInterpolator / DataSmoothing 整体删除（方案 b）

- **删除** `core/domain/src/main/java/com/blazepush/core/domain/usecase/AnomalyDetector.kt` 整文件
- **删除** `core/domain/src/main/java/com/blazepush/core/domain/usecase/DataInterpolator.kt` 整文件
- **删除** `core/domain/src/main/java/com/blazepush/core/domain/usecase/DataSmoothing.kt` 整文件
- **删除** `AppModule.kt` line 13/15/17 三条 import + line 83/84/85 三条 `factory { ... }` DI 注册
- **BREAKING** 修改 `GpsDataViewModel`：
  - 删除 `:14` import `com.blazepush.core.domain.usecase.DataSmoothing`
  - 删除 `:28` 构造参数 `dataSmoothing: DataSmoothing`
  - 删除 `:171` `dataSmoothing.reset()` 调用
- **BREAKING** 调用方迁移：
  - 生产：`AppModule.kt` 的 `viewModel { GpsDataViewModel(get(), get(), get(), get()) }` 去掉最后一个 `get()`（4 → 3 参数）
  - 测试：Round 2 新增的 `GpsDataViewModelTest.kt` 构造 ViewModel 处去掉 `dataSmoothing = mock(...)` 实参
  - 其他 `mock(GpsDataViewModel::class.java)` 不受影响（只 mock 实例不构造）

## Capabilities

### New Capabilities

- `di-real-device-error-propagation`（A17）：定义 `single<TrackCatalog>` DI **provider 创建期** 异常处理的精确边界 —— cause chain 命中 Koin 自带 `MissingAndroidContextException` 标记则降级 fallback，其他异常 MUST 上抛；scope 不覆盖 `getAllTracks()` 内 asset read 失败（由 A37 既定容错契约 `runCatching {}.getOrNull()` 降级 `fallbackCatalog`）
- `anomaly-island-removal`（A30）：定义 `AnomalyDetector` / `DataInterpolator` / `DataSmoothing` 三个孤岛类的删除契约 + DI 节点解绑 + ViewModel 解耦后零功能回归

### Modified Capabilities

无。`openspec/specs/` 当前为空，全部走 New Capabilities。

## Impact

### 受影响模块路径

**A17**：
- `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`（`single<ReplayTrackSource>` **零改动**让 Koin 自带 `androidContext()` 自然抛；`single<TrackCatalog>` 改 cause chain 检查 + import `org.koin.android.error.MissingAndroidContextException` + file-private `findInCauseChain<T>` reified inline 工具放文件末尾 + 删除注释中"会抛 `MissingAndroidContextException`"的误导句）
- **不**新建项目内 `MissingAndroidContextException.kt`（B 方案：复用 Koin 自带类型）
- `feature/test/src/test/java/com/blazepush/feature/test/di/DomainModuleKoinTest.kt`（追加 2 条测试）

**A30**：
- 删除 `core/domain/src/main/java/com/blazepush/core/domain/usecase/AnomalyDetector.kt`
- 删除 `core/domain/src/main/java/com/blazepush/core/domain/usecase/DataInterpolator.kt`
- 删除 `core/domain/src/main/java/com/blazepush/core/domain/usecase/DataSmoothing.kt`
- `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`（删 3 import + 3 factory + ViewModel 注入参数）
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt`（删 import + 构造参数 + reset 调用）
- `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/GpsDataViewModelTest.kt`（构造 ViewModel 处去 `dataSmoothing` 实参）

### 不受影响的边界

- `core/bluetooth`：零改动
- `core/data`：零改动
- `app/`：可能含 `viewModel { GpsDataViewModel(...) }` 注入，需检查（多半在 `feature/test` 模块内）
- `simulator`：零改动
- `LapRecord` / `GpsData` / 协议字段：零改动

### 协议兼容性

**N/A** —— 不涉及 RaceChrono BLE / GpsData 字段。

### 双端任务范围

**仅接收端（gps-app）** —— 不涉及发射端 simulator 改动。

## Non-goals（scope 硬边界）

- **不碰 A22**（已 ✅）/ **A35**（Round 5）
- **不修改 RaceChrono BLE 协议 / GpsData 字段**
- **不引入 A56 持久化模型**
- **A17 catch 范围收窄是行为修复**，不是接口变更（不算 BREAKING）
- **A30 删除 `DataSmoothing` 是 `GpsDataViewModel` 构造器 BREAKING**，所有调用方同 commit 迁移
- 不为 `AnomalyDetector` / `DataInterpolator` 寻找新归宿（用户已拍板方案 b 删除，未来若有新需求再独立 round 引入）
- 不在本 round 引入 GpsData filter 层 fault classification（与 A30 接线方案 (a) 的取舍点）

## 验收门槛（进入 `/opsx:apply` 前）

- `openspec validate fix-di-fallback-and-anomaly-island-cleanup --strict` 通过
- `:core:domain:compileKotlin` + `:feature:test:compileDebugKotlin` BUILD SUCCESSFUL（A30 删除 + A17 类型新增后编译闭环）
- `:feature:test:testDebugUnitTest` + `:core:bluetooth:testDebugUnitTest` + `:core:domain:test` + `:app:compileDebugKotlin` 全绿（下游零回归）
- E2E 契约 `*EndToEndLapTimingContractTest*` 全绿
- **A30 grep 自检**：`AnomalyDetector|DataInterpolator|DataSmoothing` 在 `core/`、`feature/`、`app/`、`simulator/` 零命中
- **A17 grep 自检**：(1) `runCatching` 在 `AppModule.kt` 的 `single<TrackCatalog>` 周围零命中（已替换为 try/catch + cause chain）；(2) `findInCauseChain<MissingAndroidContextException>()` 在 `AppModule.kt` 命中 + `import org.koin.android.error.MissingAndroidContextException` 命中（B 方案 cause chain 标记类型）；(3) 项目内 `class MissingAndroidContextException` 自建类型在 `core/`、`feature/`、`app/`、`simulator/` **零命中**（仅复用 Koin 自带）
- backlog A17 + A30 各自迁 🟢 `pending_review` + 附录表格状态列同步

## 基线

commit `a8e2377`（Round 3 P1 修补）。
