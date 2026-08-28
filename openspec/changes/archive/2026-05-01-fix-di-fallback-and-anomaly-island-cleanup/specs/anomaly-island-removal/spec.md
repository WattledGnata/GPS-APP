## ADDED Requirements

### Requirement: `AnomalyDetector` / `DataInterpolator` / `DataSmoothing` 三个孤岛类整体删除

`core/domain/src/main/java/com/blazepush/core/domain/usecase/AnomalyDetector.kt`、`DataInterpolator.kt`、`DataSmoothing.kt` 三个文件 MUST 整体删除。这三个类在删除前为半接线状态（DI 注册但无消费方），其中 `DataSmoothing` 仅在 `GpsDataViewModel` 中被调 `reset()` 不被 smooth。本 round 选方案 b（删除）而非方案 a（接线），未来若有 anomaly classification 需求再独立 round 重新引入。

#### Scenario: 三个文件不存在

- **GIVEN** 实施后代码库
- **WHEN** `find core/domain/src/main/java/com/blazepush/core/domain/usecase/ -name "AnomalyDetector.kt" -o -name "DataInterpolator.kt" -o -name "DataSmoothing.kt"`
- **THEN** 零结果（三个文件全部删除）

#### Scenario: 全仓引用零命中

- **GIVEN** 实施后 `core/`、`feature/`、`app/`、`simulator/` 全部 `.kt` 源码
- **WHEN** grep `\bAnomalyDetector\b|\bDataInterpolator\b|\bDataSmoothing\b`
- **THEN** 零命中（包括 import 语句、类型引用、构造调用、DI factory）

### Requirement: `AppModule.kt` DI 节点解绑

`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` MUST 删除 3 条 import + 3 条 `factory { ... }` DI 注册，并把 `viewModel { GpsDataViewModel(get(), get(), get(), get()) }` 调整为 3 参数版本（去掉最后一个 `get()`，对应已删除的 `dataSmoothing`）。

#### Scenario: AppModule 不再含三个孤岛类的 import / factory

- **GIVEN** 实施后 `AppModule.kt` 源码
- **WHEN** grep `AnomalyDetector|DataInterpolator|DataSmoothing`
- **THEN** 零命中

#### Scenario: viewModel 节点 GpsDataViewModel 注入 3 参数

- **GIVEN** 实施后 `AppModule.kt` 源码
- **WHEN** 在 `viewModel { GpsDataViewModel(` 附近读 token
- **THEN** 命中 3 个 `get()` 实参（对应 `gpsDataRepository / bleDeviceManager / dataQualityEvaluator`），不再含第 4 个 `get()`（原 `dataSmoothing`）

### Requirement: `GpsDataViewModel` 解耦后构造器 3 参数 + reset 不再调用 dataSmoothing

`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt` MUST：(1) 删除 `import com.blazepush.core.domain.usecase.DataSmoothing`；(2) 构造器从 4 参数减为 3（删除 `dataSmoothing: DataSmoothing`）；(3) `resetStats()` 函数体内删除 `dataSmoothing.reset()` 调用。

#### Scenario: GpsDataViewModel 构造器 3 参数

- **GIVEN** 实施后 `GpsDataViewModel.kt` 源码
- **WHEN** 读 `class GpsDataViewModel(...)` 主构造器
- **THEN** 含 `gpsDataRepository: GpsDataRepository` + `bleDeviceManager: BleDeviceManager` + `dataQualityEvaluator: DataQualityEvaluator` 共 3 参数；**不**含 `dataSmoothing`

#### Scenario: GpsDataViewModel 不再 import DataSmoothing

- **GIVEN** 实施后 `GpsDataViewModel.kt` 源码
- **WHEN** grep `import com.blazepush.core.domain.usecase.DataSmoothing`
- **THEN** 零命中

#### Scenario: resetStats() 不再调 dataSmoothing.reset()

- **GIVEN** 实施后 `GpsDataViewModel.kt` 源码
- **WHEN** grep `dataSmoothing\.reset\(\)`
- **THEN** 零命中

### Requirement: 现有测试零回归

A30 是删除性变更（`DataSmoothing` 本就只 `reset()` 不 smooth），删除后所有现有测试 MUST 全绿。`GpsDataViewModelTest`（Round 2 新增）等所有创建 `GpsDataViewModel` 实例的测试需要同步迁移构造调用（去掉 `dataSmoothing = mock(...)` 实参）。

#### Scenario: feature:test 全测绿

- **GIVEN** 实施后整个代码库
- **WHEN** `./gradlew :feature:test:testDebugUnitTest`
- **THEN** BUILD SUCCESSFUL（所有 `GpsDataViewModelTest` / `TestSessionViewModelTrackLapTest` / `TestSessionViewModelTrackLoadingTest` 等测试零回归）

#### Scenario: 下游 :core:domain :app 编译零回归

- **GIVEN** 实施后整个代码库
- **WHEN** `./gradlew :core:domain:test :core:bluetooth:testDebugUnitTest :app:compileDebugKotlin`
- **THEN** 全部 BUILD SUCCESSFUL（A30 删除 + ViewModel 构造器 BREAKING 后无下游残留引用）

### Requirement: 不为孤岛类寻找新归宿

本 change MUST NOT 在删除三个孤岛类后引入等价新类型作为 "替代品"。具体禁止：

- 不在 `core/domain/usecase/` 新建任何含 `Anomaly` / `Interpolator` / `Smoothing` 字样的类型
- 不在 `GpsDataFilter` 内部直接 inline 三类的算法
- 未来若有 anomaly classification 需求，重新独立 round 引入（不在本 round scope）

#### Scenario: 不引入等价替代类型

- **GIVEN** 本 change 的 `git diff <baseline>..HEAD` 新增行
- **WHEN** grep `^\+` 行（排除 `^\+\+\+` 文件头）含 `class.*Anomaly\|class.*Interpolator\|class.*Smoothing`
- **THEN** 零命中（删除即删除，不替换）
