## Context

### A17 当前状态

`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt:88-95` 现状：

```kotlin
single<ReplayTrackSource> { AssetReplayTrackSource(androidContext()) }
// JVM 单测（如 `DomainModuleKoinTest`）无法提供 `androidContext()`，`get<ReplayTrackSource>()`
// 会抛 `MissingAndroidContextException`；此时降级到 `PresetTrackCatalog()`，让
// `TrackCatalog` 绑定在纯 JVM 环境下依然可解析，真机环境的 replay 对齐不受影响。
single<TrackCatalog> {
    runCatching { ReplayAlignedTrackCatalog(get(), PresetTrackCatalog()) }
        .getOrElse { PresetTrackCatalog() }
}
```

**关键问题**：
1. 注释里说"会抛 `MissingAndroidContextException`"，但**该类型不存在**于代码库（grep 全仓零命中除注释外）
2. 实际上 `androidContext()` 在缺 Context 时抛的是 Koin 内部异常（候选 `NoBeanDefFoundException` / `IllegalStateException` / `InstanceCreationException`，本 design 在 D2 决策固化）
3. `runCatching {...}.getOrElse {...}` 等价于 catch all `Throwable`：JVM 缺 Context 降级合理，但**真机** asset 损坏 / Gson parse 异常 / IOException 也被同一路径吞掉静默降级

### A30 当前状态

3 个 domain 类全部位于 `core/domain/src/main/java/com/blazepush/core/domain/usecase/`：

- `AnomalyDetector.kt` —— 提供异常类型分类（stale / jump / range / sat_low / zero_coord），grep 全仓**零消费方**
- `DataInterpolator.kt` —— 信号丢失时的插补算法，grep 全仓**零消费方**
- `DataSmoothing.kt` —— 数据平滑，仅 `GpsDataViewModel:171` 调 `reset()`，**未实际用于 smoothing**

DI 注册（`AppModule.kt:83-85`）：
```kotlin
factory { AnomalyDetector() }
factory { DataSmoothing() }
factory { DataInterpolator() }
```

`GpsDataViewModel.kt:24-29` 构造器（4 参数）：
```kotlin
class GpsDataViewModel(
    private val gpsDataRepository: GpsDataRepository,
    private val bleDeviceManager: BleDeviceManager,
    private val dataQualityEvaluator: DataQualityEvaluator,
    private val dataSmoothing: DataSmoothing,  // ← 删除目标
) : ViewModel() {
```

### 约束

- 不修改 RaceChrono BLE 协议 / GpsData 字段
- 不引入 A56 持久化模型
- `AssetReplayTrackSource(context: Context)` 构造签名 MUST 不变（专用异常仅在 DI 层产生）
- A30 删除 `DataSmoothing` 是 `GpsDataViewModel` 构造器 BREAKING，所有调用方同 commit 迁移

## Goals / Non-Goals

**Goals**：

- A17：DI fallback catch 范围**精确**只覆盖 "JVM 缺 Android Context" 一种合法场景；真机异常（IOException / Gson parse / asset 损坏）原样上抛 + 崩溃上报可见
- A17：注释与代码一致 —— `MissingAndroidContextException` 真实存在 + 真实被 catch
- A30：清除半接线孤岛代码 + DI 解绑 + ViewModel 解耦，全仓 grep 零命中
- A30：删除后下游零功能回归（DataSmoothing 本就只 reset 不 smooth）

**Non-Goals**：

- 不为 AnomalyDetector / DataInterpolator 寻找新归宿（方案 b 删除）
- 不引入 GpsDataFilter 层 fault classification（A30 方案 a 已被否决）
- 不修改 `AssetReplayTrackSource` 构造签名 / 不在 repository 层抛 DI 专用异常
- 不改 LapRecord / 协议字段

## Decisions

### D1 · 复用 Koin 自带 `org.koin.android.error.MissingAndroidContextException`（实施期决策：评审方放行 B 方案）

**决策**：实施期 §2 实测发现 Koin Android 库（koin-android-3.5.3）已自带 `org.koin.android.error.MissingAndroidContextException` 类型，`androidContext()` 在缺 Context 时直接抛该类型。**复用** Koin 自带类型作为 cause chain 标记类型，**不**新建项目内 wrapper：

- 删除 v2 计划新建的 `feature/test/src/main/java/com/blazepush/feature/test/di/MissingAndroidContextException.kt`
- `single<ReplayTrackSource>` 保持零改动（让 `androidContext()` 自然抛 Koin 自带类型）
- `single<TrackCatalog>` cause chain 检查类型为 `org.koin.android.error.MissingAndroidContextException`

**Rationale**：

- 评审方实施期反馈：`koin-android-3.5.3.aar` 自带该类型，重复造 wrapper 无独立价值
- Koin Android 包名（`org.koin.android.error.*`）是公开 API，稳定性可信
- 简化层次：`single<ReplayTrackSource>` 0 改动，`AppModule.kt` 改动量减半

**Alternatives considered**：

- (a) 保持 v2 自建 wrapper：拒收 —— 多一层 wrapper 解释成本，无独立价值；评审方实施期反馈拍板 (B) 简化方案
- (b) 不依赖 Koin Android 类型，自建独立标记：拒收 —— 见 (a)

### D2 · Koin 包装 + cause chain 检查方案

**关键事实**（Round 4 Review v1 P1 + 实施期 §2 实测）：Koin 3.5.3 把 provider 内部抛出的任何异常**包装**为 `InstanceCreationException` 透传给 caller。`single<TrackCatalog>` 内 `get<ReplayTrackSource>()` 在 JVM 缺 Context 时拿到的是 `InstanceCreationException(cause = ... -> MissingAndroidContextException)`，直接 `catch (e: MissingAndroidContextException)` **永远不命中**。

**决策**：cause chain 检查方案（标记类型 = Koin 自带 `org.koin.android.error.MissingAndroidContextException`）：

```kotlin
// AppModule.kt
import org.koin.android.error.MissingAndroidContextException

single<TrackCatalog> {
    try {
        ReplayAlignedTrackCatalog(get(), PresetTrackCatalog())
    } catch (e: Throwable) {
        // Koin 把 androidContext() 失败包装为 InstanceCreationException —— 遍历 cause chain
        // 找 Koin 自带 MissingAndroidContextException 标记。命中则降级（JVM 缺 Context 合法场景），
        // 否则 throw 让真机异常可见
        if (e.findInCauseChain<MissingAndroidContextException>() != null) {
            PresetTrackCatalog()
        } else {
            throw e
        }
    }
}

// AppModule.kt 文件末尾或独立 di/CauseChainExt.kt
private inline fun <reified T : Throwable> Throwable.findInCauseChain(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current as T
        current = current.cause
    }
    return null
}
```

**Rationale**：

- cause chain 不被 Koin 包装影响：`MissingAndroidContextException` 在 chain 里某层一定能找到
- 类型仍精确：只对该标记类型降级，其他异常（`IOException` / `JsonSyntaxException`）cause chain 不含 → 走 else 分支 throw e
- catch `Throwable` + cause chain 检查 = 逻辑收窄（不命中即 throw e，非 catch all）

### D3 · `single<ReplayTrackSource>` 保持零改动（实施期 D1 简化的直接结果）

**决策**：`single<ReplayTrackSource> { AssetReplayTrackSource(androidContext()) }` 保持原状，不再加 try/catch wrapper —— `androidContext()` 自然抛 Koin 自带 `MissingAndroidContextException`，被 Koin 包装为 `InstanceCreationException(cause = MissingAndroidContextException)` 透传给 `single<TrackCatalog>` 的 caller，由 D2 cause chain 处理。

**Rationale**：

- D1 复用 Koin 自带类型后，无需在 `single<ReplayTrackSource>` 内做类型转换
- 减少 AppModule 改动面，对齐"最小改动"原则
- `AssetReplayTrackSource(context: Context)` 构造签名零改动

### D4 · `single<TrackCatalog>` cause chain catch + 真机异常上抛行为契约

**决策**（D2 已固化代码片段，本节锁住行为契约表，**Round 4 review v2 修补 B 方案**：A17 scope 显式限定为 DI provider 创建期，不修订 A37 容错契约）：

| 场景 | 触发 catch？ | catch (e: Throwable) 拿到的 e | findInCauseChain<MissingAndroidContextException>() | 行为 |
|---|---|---|---|---|
| JVM 单测无 Android Context | ✓ | `InstanceCreationException(cause = MissingAndroidContextException(cause = ...))` | 命中 | 降级到 `PresetTrackCatalog()` |
| 真机正常运行 | ✗（无异常） | N/A | N/A | 返回 ReplayAlignedTrackCatalog |
| Fake module 让 ReplayTrackSource provider **直接** 抛 IOException（DI 创建期） | ✓ | `InstanceCreationException(cause = IOException)` | 不命中 | throw e 上抛 |
| `getAllTracks()` 内 `loadReplayJson()` 抛 IOException（user 调用时，**已超出 DI provider scope**） | ✗（A17 catch 不参与） | N/A | N/A | A37 `ensureReplayTrackLoaded` 内 `runCatching {}.getOrNull()` 吞掉，降级到 `fallbackCatalog` 返回 PresetTrackCatalog 赛道（**不抛**，A37 已核销容错契约） |

**关键**：

1. `ReplayAlignedTrackCatalog` 构造器本身不读 asset（A37 已去除 `by lazy` 提前触发）—— DI 实例化阶段不会触发 IOException
2. **A17 catch 范围限于 `single<TrackCatalog>` provider scope**：catch 只看 provider 实例化时（`get<ReplayTrackSource>()` + `ReplayAlignedTrackCatalog(...)` 构造）的异常
3. user 调用 `getAllTracks()` 时若 asset 读失败，**不**会传到 A17 的 catch —— `ReplayAlignedTrackCatalog.ensureReplayTrackLoaded()` 内 `runCatching {}.getOrNull()` 自吞 IOException 降级 fallbackCatalog（A37 已核销）
4. **A17 与 A37 catalog 内部容错契约协调**：本 round 不修订 A37 容错；若未来需要"真机 asset read 失败也上抛崩溃上报"以提高可观测性，需要独立 round 修订 A37 的 `runCatching` 容错策略（spec 同步声明该豁免）

D5 Test 2 用 fake `ReplayTrackSource` provider **直接抛** IOException（在 provider 工厂内，不是在 `loadReplayJson()` 实现内）—— 验证 DI provider 创建期 IOException 上抛 + cause chain 不命中标记。

### D5 · A17 测试覆盖

**决策**：追加 2 条测试到 `DomainModuleKoinTest.kt`：

#### Test 1：JVM fallback 明确化

```kotlin
@Test
fun providesTrackCatalog_jvmEnvironment_fallsBackToPresetGracefully() {
    stopKoin()
    startKoin { modules(domainModule) }
    try {
        val trackCatalog = get<TrackCatalog>(TrackCatalog::class.java)
        // JVM 环境无 Android Context → MissingAndroidContextException 被 catch → 降级到 PresetTrackCatalog
        assertTrue(
            "JVM 环境应降级到 PresetTrackCatalog（不是 ReplayAlignedTrackCatalog）",
            trackCatalog is PresetTrackCatalog,
        )
    } finally {
        stopKoin()
    }
}
```

#### Test 2：DI provider 创建期非 Missing 异常上抛（关键 P1 契约，B 方案 scope 限定）

**Round 4 review v2 修补**：原 v1 测试经 `getAllTracks()` 路径触发 IOException 与 A37 已核销容错契约冲突（A37 `ensureReplayTrackLoaded` `runCatching` 自吞 IOException 降级 fallbackCatalog 不抛）。改为 fake provider **直接抛** IOException（DI 创建期），测 single<TrackCatalog> 的 cause-chain catch scope 行为：

```kotlin
@Test
fun providesTrackCatalog_realDeviceAssetFailure_propagatesNotSilenced() {
    stopKoin()
    // fake provider 直接抛 IOException（在 single<ReplayTrackSource> 工厂内，
    // 不在 loadReplayJson() 实现内）—— 模拟 DI bootstrapping 期罕见异常
    val fakeIoFailureModule = module {
        single<ReplayTrackSource> { throw IOException("simulated DI-layer asset failure") }
    }
    startKoin { modules(domainModule, fakeIoFailureModule) }
    try {
        // get<TrackCatalog> 触发 single<TrackCatalog> provider 实例化
        // → 内部 get<ReplayTrackSource>() 触发 fake provider 抛 IOException
        // → Koin 包装为 InstanceCreationException(cause = IOException)
        // → single<TrackCatalog> catch 拿到包装异常
        // → cause chain 不含 MissingAndroidContextException → throw e 上抛
        val thrown = assertThrows(Throwable::class.java) {
            get<TrackCatalog>(TrackCatalog::class.java)
        }
        // 验证 cause chain 含原始 IOException（被 Koin 包装但 cause 链可达）
        var current: Throwable? = thrown
        var foundIo = false
        while (current != null) {
            if (current is IOException &&
                current.message == "simulated DI-layer asset failure") {
                foundIo = true; break
            }
            current = current.cause
        }
        assertTrue("DI 层异常应原样上抛", foundIo)
    } finally {
        stopKoin()
    }
}
```

**Rationale**：

- Test 1 锁住 v1 契约：JVM fallback 不变（现有隐式契约明确化）
- Test 2 锁住 v2 契约：真机异常上抛 + 不再被 catch all 吞掉
- 用 fake `ReplayTrackSource` 模拟 asset 失败 —— 不依赖真实 asset / Android Context
- 调用 `getAllTracks()` 触发 IO 是因为 A37 已让 ctor 不读 asset；DI 实例化期不再有现成的 IOException 触发点。Test 2 通过 `getAllTracks()` 验证 `ReplayAlignedTrackCatalog` 这一层的真机异常会自然上抛（DI 层 catch 不覆盖 IOException）

### D6 · A30 删除清单 + 影响范围

**决策**：删除目标 + 调用方迁移：

| 文件 | 操作 |
|---|---|
| `core/domain/.../usecase/AnomalyDetector.kt` | 整文件删除 |
| `core/domain/.../usecase/DataInterpolator.kt` | 整文件删除 |
| `core/domain/.../usecase/DataSmoothing.kt` | 整文件删除 |
| `feature/test/.../di/AppModule.kt:13` | 删除 `import com.blazepush.core.domain.usecase.AnomalyDetector` |
| `feature/test/.../di/AppModule.kt:15` | 删除 `import com.blazepush.core.domain.usecase.DataInterpolator` |
| `feature/test/.../di/AppModule.kt:17` | 删除 `import com.blazepush.core.domain.usecase.DataSmoothing` |
| `feature/test/.../di/AppModule.kt:83-85` | 删除 3 条 `factory { ... }` |
| `feature/test/.../di/AppModule.kt` | `viewModel { GpsDataViewModel(get(), get(), get(), get()) }` 去掉最后一个 `get()`（4 → 3 参数） |
| `feature/test/.../viewmodel/GpsDataViewModel.kt:14` | 删除 `import com.blazepush.core.domain.usecase.DataSmoothing` |
| `feature/test/.../viewmodel/GpsDataViewModel.kt:28` | 删除构造参数 `private val dataSmoothing: DataSmoothing` |
| `feature/test/.../viewmodel/GpsDataViewModel.kt:171` | 删除 `dataSmoothing.reset()` 调用 |
| `feature/test/.../GpsDataViewModelTest.kt` | 构造 `GpsDataViewModel(...)` 处去掉 `dataSmoothing = mock(DataSmoothing::class.java)` 实参 |

### D7 · A30 测试与回归

**决策**：删除性变更，重点是回归保护：

- `:core:domain:compileKotlin` BUILD SUCCESSFUL（3 文件删后无引用残留）
- `:feature:test:compileDebugKotlin` BUILD SUCCESSFUL（GpsDataViewModel 构造器签名 BREAKING 后所有调用方迁移完成）
- 全仓 grep 自检：`AnomalyDetector|DataInterpolator|DataSmoothing` 在 `core/`、`feature/`、`app/`、`simulator/` **零命中**
- 现有测试零回归：`GpsDataViewModelTest`（Round 2 新增）、`TestSessionViewModelTrackLapTest` 等所有创建 ViewModel 的 test 全绿

**Rationale**：A30 是结构清理，本身不引入新行为契约 —— 验证主要靠"零回归"。

### D8 · Capability 拆分

- `di-real-device-error-propagation`（A17）：行为契约 capability，定义 `single<TrackCatalog>` DI 节点的精确异常传播边界
- `anomaly-island-removal`（A30）：结构契约 capability，定义 3 类删除 + DI 节点解绑 + ViewModel 解耦的零功能回归边界

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Koin 异常类型在 design 阶段未固化，apply 阶段实测可能发现意外类型（如多种异常都属"context missing"） | apply §1 实测一次锁死；若多种类型，catch 父类（如 `KoinException`）但仍排除 `IOException`/`JsonSyntaxException`；不在 design 阶段 over-engineer |
| `MissingAndroidContextException` 未来被其他模块误用（domain / data 层抛该类型） | scope 限制：类型放 `feature/test/.../di/`，import 该类型即跨包，未来 review 自然挡 |
| A30 删除后某 future round 又需要 anomaly classification | 接受 —— 用户已拍板方案 b；future round 重新引入时可直接重写而非维护半接线代码 |
| `GpsDataViewModel` 构造器 BREAKING 影响超出预检的隐藏调用方（如 Compose preview / instrumented test） | apply §6 全仓 grep `GpsDataViewModel(` 详尽核实；compile 失败即调用方未迁移信号 |

## Migration Plan

### 实施顺序

1. **A17 段**（独立 OK，不影响 A30）：
   - §1 新建 `MissingAndroidContextException.kt`
   - §2 实测确定 Koin 异常类型（apply 阶段一次性 grep + try/error 探测）
   - §3 改造 `AppModule.kt` 的 `single<ReplayTrackSource>` + `single<TrackCatalog>`
   - §4 删除 AppModule 注释中的误导句
2. **A30 段**（BREAKING 连锁）：
   - §5 删除 3 个 domain 文件
   - §6 改造 `AppModule.kt`（删 3 import + 3 factory + ViewModel 注入参数）
   - §7 改造 `GpsDataViewModel.kt`（删 import + 构造参数 + reset 调用）
   - §8 迁移所有 `GpsDataViewModel(...)` 调用方（test + 生产 DI）
   - §9 编译门槛：`:core:domain:compileKotlin` + `:feature:test:compileDebugKotlin`
3. **测试段**：
   - §10 追加 A17 2 条 `DomainModuleKoinTest`
   - §11 测试门槛：`:feature:test:testDebugUnitTest` 全绿
4. **合流段**：
   - §12 `openspec validate --strict` + 下游零回归 + E2E + grep 自检 + backlog 迁档

### Rollback 策略

单 commit 实施 → rollback = `git revert`。两个 capability 彼此正交，单独回滚一个不影响另一个，但 BREAKING 改动（A30）使 partial revert 编译失败，建议整体 revert。

## Open Questions

无未决。`apply` 阶段需要 grep 确认的待核实项（不是设计决策）：

- Koin 在 JVM 缺 Android Context 时实际抛的具体异常类型（D2 实测）
- `app/` 模块是否含隐藏的 `GpsDataViewModel(` 调用（D6 全仓 grep）
- `IOException` import 是否需要在 `DomainModuleKoinTest.kt` 新加（D5 测试编写时确认）
