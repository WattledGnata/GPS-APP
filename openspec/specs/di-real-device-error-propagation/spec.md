# di-real-device-error-propagation Specification

## Purpose
TBD - created by archiving change fix-di-fallback-and-anomaly-island-cleanup. Update Purpose after archive.
## Requirements
### Requirement: `single<TrackCatalog>` cause chain 检查 + 真机异常上抛

`AppModule.kt` 的 `single<TrackCatalog>` DI 节点 MUST 通过 **cause chain 检查** 决定是否降级，**不**用 `runCatching` / 直接精确类型 catch。

**关键事实**（Round 4 Review v1 P1 + 实施期 §2 实测）：
- **Koin Android 库自带** `org.koin.android.error.MissingAndroidContextException` 类型（位于 `koin-android-3.5.3.aar`）
- 当 `androidContext()` 在缺 Android Context 的 JVM 环境调用时，Koin 自然抛该自带类型
- 实施 MUST **复用** Koin 自带类型作为 cause chain 标记，**不**新建项目内同名 wrapper 类型
- Koin 把 provider 内部抛出的异常**包装**为 `InstanceCreationException(cause = ...)` 透传给 caller，所以 `single<TrackCatalog>` 内 `get<ReplayTrackSource>()` 拿到的不是直接的 `MissingAndroidContextException`，需要遍历 cause chain 才能找到标记类型
- 直接 `catch (e: org.koin.android.error.MissingAndroidContextException)` **永远不命中**

实施 MUST 采用：
- `single<ReplayTrackSource>` **保持零改动**（让 `androidContext()` 自然抛 Koin 自带 `MissingAndroidContextException`）
- `single<TrackCatalog>` 用 `try { ReplayAlignedTrackCatalog(...) } catch (e: Throwable) { ... }` 接收 Koin 包装层
- 在 catch block 内通过 cause chain 工具（如 `Throwable.findInCauseChain<MissingAndroidContextException>()`，类型参数指向 `org.koin.android.error.MissingAndroidContextException`）遍历 `e.cause` 链查找标记
- 命中：降级到 `PresetTrackCatalog()`
- 不命中：`throw e`（不吞噬，让真机异常原样上抛）
- DI 节点周围 MUST NOT 再含 `runCatching` 字面量

#### Scenario: AppModule.kt single<TrackCatalog> block 不再含 runCatching

- **GIVEN** 实施后 `AppModule.kt` 源码
- **WHEN** 在 `single<TrackCatalog>` block 内 grep `runCatching`
- **THEN** 零命中（已替换为 try/catch + cause chain）

#### Scenario: cause chain 检查方案使用 Koin 自带类型

- **GIVEN** 实施后 `AppModule.kt` 源码
- **WHEN** 在 `single<TrackCatalog>` block 内 grep
- **THEN** 命中 `findInCauseChain<MissingAndroidContextException>()` 或等价 cause chain 遍历调用，**且** import 行含 `import org.koin.android.error.MissingAndroidContextException`（不是项目内自建类型）
- **AND** catch 范围至少覆盖 `Throwable` / `InstanceCreationException`（不是直接精确 `catch (e: MissingAndroidContextException)`）
- **AND** catch block 含 `else` / `throw e` 路径（非命中时上抛，非吞噬）

#### Scenario: cause chain 不命中时异常上抛（非 catch all）

- **GIVEN** 实施后 `AppModule.kt` 源码
- **WHEN** 阅读 `single<TrackCatalog>` 的 catch block
- **THEN** 包含 `throw e`（或等价语句），即不命中 cause chain 的异常会重新抛出，不会静默降级到 `PresetTrackCatalog()`

#### Scenario: JVM 单测环境降级到 PresetTrackCatalog

- **GIVEN** Koin 启动 `domainModule`，JVM 环境无 Android Context
- **WHEN** `get<TrackCatalog>(TrackCatalog::class.java)`
- **THEN** 返回非 null，类型为 `PresetTrackCatalog`（即降级生效，未抛异常）

#### Scenario: DI provider 创建期非 Missing 异常原样上抛不被吞

**Round 4 review v2 修补（B 方案）**：原 v1 Scenario 假设 `getAllTracks()` 内 `loadReplayJson()` 抛 `IOException` 会传播到 `single<TrackCatalog>` 的 catch。但 A37（`fix-gps-stats-and-lazy-catalog-hot-start`，已核销）的 spec 明确要求 `ReplayAlignedTrackCatalog.ensureReplayTrackLoaded()` 用 `runCatching {}.getOrNull()` 在 asset 解析失败时降级 fallbackCatalog **不抛异常**。两条契约直接冲突：A17 本 round **不修订** A37 已核销容错契约，scope 显式降级为 "DI provider 创建期" 的异常处理。

- **GIVEN** Koin 启动 `domainModule` + fake `single<ReplayTrackSource>` provider **直接抛** `IOException`（模拟 DI bootstrapping 期罕见异常 / 资源加载失败）
- **WHEN** `get<TrackCatalog>(TrackCatalog::class.java)`
- **THEN** 抛出异常（不被 `single<TrackCatalog>` 的 catch 静默降级到 `PresetTrackCatalog()`），cause chain 含原始 `IOException`：catch block 通过 `findInCauseChain<MissingAndroidContextException>()` 检查，因为 cause chain 中没有 Koin 自带 `MissingAndroidContextException` 标记，走 `throw e` 路径
- **作用域**：本 Scenario 锁住 `single<TrackCatalog>` **provider scope 内**的异常处理 —— provider 内任何非 `MissingAndroidContextException` cause chain 异常都会上抛

### Requirement: A17 与 A37 catalog 内部容错契约的协调（scope 边界）

`ReplayAlignedTrackCatalog.ensureReplayTrackLoaded()`（A37 design D5 + spec "asset 解析失败降级 fallbackCatalog"，`fix-gps-stats-and-lazy-catalog-hot-start` 已核销）MUST 使用 `runCatching { ... }.getOrNull()` 在 `loadReplayJson()` / `loadTrackVbo()` 抛任何异常（含 `IOException`）时降级到 `fallbackCatalog`，**不抛异常**。本 A17 round MUST NOT 修订该容错契约。

意味着：

- `runBlocking { trackCatalog.getAllTracks() }` 触发 asset 读时若 `loadReplayJson()` 抛 `IOException`，**不会**传播到 `single<TrackCatalog>` 的 catch（被 `ReplayAlignedTrackCatalog` 自身 `runCatching` 吞）
- A17 scope **限定为 DI provider 创建期**：保证 `single<TrackCatalog>` provider 内 catch 范围精确（仅 cause chain 命中 `MissingAndroidContextException` 标记才降级，其他异常上抛）
- 若未来需要"真机 asset read 失败也上抛崩溃上报"以提高可观测性，需要**独立 round** 修订 A37 的 `ensureReplayTrackLoaded` `runCatching` 容错策略，**不在本 round scope**

#### Scenario: A37 ReplayAlignedTrackCatalog 容错契约不变

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt` 源码
- **WHEN** 在 `ensureReplayTrackLoaded` 函数体内 grep `runCatching`
- **THEN** 命中（A37 容错契约保留）；本 A17 round 不删除 / 不替换该 `runCatching`

#### Scenario: getAllTracks 内 IOException 不传播到 DI 层

- **GIVEN** Koin 启动 `domainModule` + fake `single<ReplayTrackSource>` 让 `loadReplayJson()` 抛 `IOException`（注意：fake 在 `loadReplayJson()` 实现内抛，**不**在 provider 工厂内抛）
- **AND** `get<TrackCatalog>(TrackCatalog::class.java)` 返回 `ReplayAlignedTrackCatalog` 实例（DI 实例化期不读 asset，A37 已固化 ctor 不触 IO）
- **WHEN** 调用 `runBlocking { trackCatalog.getAllTracks() }` 触发首次 asset 读
- **THEN** 不抛异常，返回 `fallbackCatalog`（即 `PresetTrackCatalog`）的赛道集合（即 `ReplayAlignedTrackCatalog.ensureReplayTrackLoaded` 的 `runCatching` 已吞掉 IOException 降级），与 A37 容错契约一致
- **AND** A17 `single<TrackCatalog>` 的 catch 完全**不参与**此路径，因为 IOException 在 `ReplayAlignedTrackCatalog` 内部就被 catch 掉，永远不传播到 DI 层

### Requirement: `single<ReplayTrackSource>` 保持零改动 + 不引入项目内自建 MissingAndroidContextException

`AppModule.kt` 的 `single<ReplayTrackSource> { AssetReplayTrackSource(androidContext()) }` MUST 保持零改动 —— 让 `androidContext()` 在缺 Context 时自然抛 Koin 自带 `org.koin.android.error.MissingAndroidContextException`，不再用项目内 wrapper 类型转换（实施期 §2 实测发现 Koin Android 库已自带该类型，新建项目内同名 wrapper 无收益）。

- `feature/test/src/main/java/com/blazepush/feature/test/di/MissingAndroidContextException.kt` MUST **不存在**（v2 design 曾计划新建，实施期实测后删除）
- `AssetReplayTrackSource(context: Context)` 构造签名 MUST 不变

#### Scenario: 项目内不存在自建 MissingAndroidContextException

- **GIVEN** 实施后代码库
- **WHEN** grep `class MissingAndroidContextException` 在 `feature/`、`core/`、`app/`、`simulator/`
- **THEN** 零命中（项目内无同名自建类型；标记类型唯一来自 `koin-android` library 的 `org.koin.android.error.MissingAndroidContextException`）

#### Scenario: AppModule single<ReplayTrackSource> 保持原结构

- **GIVEN** 实施后 `AppModule.kt` 源码
- **WHEN** 查看 `single<ReplayTrackSource> { ... }` block
- **THEN** body 形如 `AssetReplayTrackSource(androidContext())` 单行表达式（**不**含 try/catch / `runCatching` / `throw MissingAndroidContextException`）
- **AND** 让 `androidContext()` 在 JVM 环境自然抛 Koin 自带类型（被 Koin 包装为 `InstanceCreationException` 透传给 single<TrackCatalog> 的 caller）

### Requirement: 注释与代码一致

`AppModule.kt` 中提及 `MissingAndroidContextException` 的注释 MUST 与代码一致：注释提及的 Koin 自带 `MissingAndroidContextException` 类型 MUST 真实在代码中使用（`findInCauseChain<MissingAndroidContextException>()`）；删除原 v1 误导性注释（说"会抛 `MissingAndroidContextException`"但当时 v1 既未真实 catch、也无项目内同名类型的句子）。

注：D1 简化方案下，`MissingAndroidContextException` 的**抛出来源**是 Koin Android 库内部（`androidContext()` 在缺 Context 时自然抛），不在 `AppModule.kt` 源文件内 grep 可见 —— 所以本契约只要求 import + cause chain 类型参数引用，不要求 `throw MissingAndroidContextException(` 字面量出现。

#### Scenario: 注释提及的类型实际在代码中使用

- **GIVEN** 实施后 `AppModule.kt` 源码
- **WHEN** 注释中出现 `MissingAndroidContextException` 字面量
- **THEN** 同文件代码内至少一处实代码引用 `findInCauseChain<MissingAndroidContextException>()` 或等价 cause chain 类型参数引用，**且** import 行含 `import org.koin.android.error.MissingAndroidContextException`
- **AND** 不再含直接 `catch (e: MissingAndroidContextException)` 形式（cause chain 方案下，Koin 包装层精确 catch 不可达）

