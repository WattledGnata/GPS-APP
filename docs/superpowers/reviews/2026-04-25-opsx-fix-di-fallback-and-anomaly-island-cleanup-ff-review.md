# fix-di-fallback-and-anomaly-island-cleanup ff artifacts review

## 0. TL;DR

不建议进入 `/opsx:apply`。`openspec validate --strict` 通过，但 A17 的核心异常边界方案与 Koin 3.5.3 的异常包装机制冲突：provider 内抛出的 `MissingAndroidContextException` 会被 Koin 包成 `InstanceCreationException`，外层 `single<TrackCatalog>` 直接 `catch (e: MissingAndroidContextException)` 捕不到。另有一个 Gradle 门槛写了不存在的 `:core:domain:compileDebugKotlin` task，实施方照 tasks 执行会失败。

## 1. Findings

### Finding 1 — [P1] A17 精确 catch 方案在 Koin 包装下不可达

- **位置**：
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/design.md` D3 / D4
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/specs/di-real-device-error-propagation/spec.md` Requirement 2 / 3
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/tasks.md` §3 / §4
- **问题**：方案要求 `single<ReplayTrackSource>` 在 `androidContext()` 失败时 `throw MissingAndroidContextException(cause = e)`，然后 `single<TrackCatalog>` 用 `catch (e: MissingAndroidContextException)` 降级。但 Koin 3.5.3 的 `InstanceFactory.create` 会 catch provider 内的 `Exception` 并重新抛 `InstanceCreationException(message, parent)`。也就是说，`get<ReplayTrackSource>()` 在 `single<TrackCatalog>` provider 内看到的不是裸 `MissingAndroidContextException`，而是 `InstanceCreationException`（cause 才是 Missing）。当前 spec 仍要求 catch 类型精确等于 `MissingAndroidContextException`，实施后 JVM fallback 很可能不再降级，或者实施方被迫扩大 catch 但违反 spec。
- **证据**：评审方反编译本仓 Koin 3.5.3 `org.koin.core.instance.InstanceFactory.create`，确认 provider exception 被包装：
  - catch `java.lang.Exception`
  - new `org.koin.core.error.InstanceCreationException(..., parent)`
- **要求**：重写 A17 契约，显式处理 Koin wrapper。可选方向：
  - 在 `single<TrackCatalog>` 捕获 `InstanceCreationException`，仅当 cause chain 含 `MissingAndroidContextException` 时降级，否则原样上抛。
  - 或把 context 获取与 `AssetReplayTrackSource` 构造移到同一个 `single<TrackCatalog>` try/catch 作用域内，避免跨 provider 抛 Missing 后被 Koin 包装；但这会改变 `single<ReplayTrackSource>` 的设计，需同步 spec/tasks。
  - 不可继续要求外层 provider 直接 `catch (e: MissingAndroidContextException)` 作为唯一合法实现。

### Finding 2 — [P2] core/domain 编译门槛引用不存在的 Android task

- **位置**：`openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/tasks.md:168-169`
- **问题**：tasks §9.1 要求 `./gradlew :core:domain:compileDebugKotlin`，但 `:core:domain` 是非 Android 模块，当前仓库实际 task 是 `:core:domain:compileKotlin` / `:core:domain:test`。评审方实跑 `./gradlew :core:domain:compileDebugKotlin`，Gradle 返回 `task 'compileDebugKotlin' not found in project ':core:domain'`。实施方照 tasks 跑会在门槛阶段失败。
- **要求**：把 §9.1 改为 `./gradlew :core:domain:compileKotlin`，或直接使用后续 §12.2 已有的 `./gradlew :core:domain:test` 作为 core/domain 编译+测试门槛。

## 2. Verified

- `openspec validate fix-di-fallback-and-anomaly-island-cleanup --strict` PASS。
- 当前 `AppModule.kt` 确有 A17 `runCatching` 宽 catch 和 A30 三个 factory 注册。
- 当前 `GpsDataViewModel(` 直接构造点只有 `AppModule.kt` 与 `GpsDataViewModelTest.kt` 两处，A30 迁移范围可控。

## 3. Verdict

暂不放行 `/opsx:apply`。请先修订 A17 的 Koin wrapper 契约与 tasks §9.1 Gradle 门槛；修完后重跑 `openspec validate --strict` 并重提 mini review。无需 patches 清单。

## 4. Round 2 mini review

### 4.1 Finding closure

- Finding 1 partially closed：design D2-D4 已承认 Koin 3.5.3 wrapper 事实，主方案改为 `catch (e: Throwable)` + `findInCauseChain<MissingAndroidContextException>()`，这是正确方向。
- Finding 2 closed：`:core:domain:compileDebugKotlin` 已改为 `:core:domain:compileKotlin`，`openspec validate --strict` 继续 PASS。

### 4.2 New findings

#### Finding 3 — [P2] 仍残留直接 catch MissingAndroidContextException 的旧契约

- **位置**：
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/proposal.md:14`
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/specs/di-real-device-error-propagation/spec.md:90-96`
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/tasks.md:299-319`
- **问题**：v2 design 已改成 cause-chain 方案，不再直接 `catch (e: MissingAndroidContextException)`；但 proposal 仍写 `try { ... } catch (e: MissingAndroidContextException) { ... }`，spec 的“注释提及的类型实际存在”Scenario 仍要求 `catch (e: MissingAndroidContextException)` 至少命中一次，tasks §12.5 / commit body 也仍写 “MissingAndroidContextException 既被 throw 也被 catch / catch 到 MissingAndroidContextException 精确类型”。实施方若按新 design 做，会违反 spec/tasks grep；若按 spec/tasks 做，又回到 v1 不可达方案。
- **要求**：统一改成 cause-chain 契约：
  - proposal What Changes 写 `catch (e: Throwable)` + cause chain 命中 Missing 时 fallback，不命中 throw。
  - spec Scenario 改为要求 `throw MissingAndroidContextException(` + `findInCauseChain<MissingAndroidContextException>()` / 等价 cause-chain 检查 + `throw e`，不再要求 `catch (e: MissingAndroidContextException)`。
  - tasks §12.5 / commit body 同步改为 “throw 标记类型 + cause-chain 检查命中 + 非命中上抛”。

#### Finding 4 — [P2] §2.1 临时实测命令引用不存在的测试名

- **位置**：`openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/tasks.md:52-56`
- **问题**：当前仓库现有测试名是 `DomainModuleKoinTest.domainModule_providesTrackCatalog`，不是 `providesTrackCatalog`。tasks §2.1 的命令 `--tests "*DomainModuleKoinTest.providesTrackCatalog"` 可能匹配不到任何测试，导致实施方无法按步骤观察 Koin 缺 Context 的实际异常类型。
- **要求**：改为现有测试名 `--tests "*DomainModuleKoinTest.domainModule_providesTrackCatalog"`，或更稳妥地用 `--tests "*DomainModuleKoinTest*"` 并在输出中筛异常链。

### 4.3 Verdict

Round 2 仍不放行 `/opsx:apply`。修完 Finding 3 / 4 后可重提 mini review；无需 patches 清单。

## 5. Round 3 mini review

### 5.1 Finding closure

- Finding 3 mostly closed：proposal / spec 主体 / tasks §12.5 已统一到 `catch (e: Throwable)` + `findInCauseChain<MissingAndroidContextException>()` 的 cause-chain 契约。
- Finding 4 closed：tasks §2.1 已改为 `--tests "*DomainModuleKoinTest*"`，不再硬编码不存在的 `providesTrackCatalog` 测试名。
- `openspec validate fix-di-fallback-and-anomaly-island-cleanup --strict` PASS。

### 5.2 Remaining finding

#### Finding 5 — [P2] commit body 和 spec 解释仍残留旧 catch 语义

- **位置**：
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/tasks.md:318`
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/specs/di-real-device-error-propagation/spec.md:86`
- **问题**：v3 已改为 cause-chain 方案，但 tasks commit body 仍要求写“`single<TrackCatalog>` 收窄 catch 到 `MissingAndroidContextException` 精确类型”；spec 的 IOException Scenario 仍解释为“因为 catch 类型仅限 `MissingAndroidContextException`”。这两处与新契约不一致。正确表述应是：`single<TrackCatalog>` 捕获 Koin 包装层 / `Throwable` 后只在 cause chain 命中 `MissingAndroidContextException` 时 fallback，非命中路径 `throw e` 上抛。否则实施方的 commit body 和 spec 解释会继续把读者带回 v1 不可达方案。
- **要求**：
  - tasks.md:318 commit body 改为“`single<TrackCatalog>` 使用 cause-chain 检查，命中 Missing 标记才 fallback，非命中异常原样上抛”。
  - spec.md:86 改为“不被 cause-chain fallback 吞掉，因为 cause chain 中没有 `MissingAndroidContextException` 标记”，不要再写“catch 类型仅限”。

### 5.3 Verdict

Round 3 仍不放行 `/opsx:apply`。只剩 2 处旧表述清理，修完后可重提 mini review；无需 patches 清单。

## 6. Round 4 mini review

### 6.1 Finding closure

- Finding 5 closed：`spec.md:86` 已改为 cause chain 中没有 `MissingAndroidContextException` 标记时走 `throw e`，不再写“catch 类型仅限”。
- Finding 5 closed：`tasks.md:318` commit body 模板已改为 `single<TrackCatalog>` 捕获 Koin 包装层后通过 `findInCauseChain<MissingAndroidContextException>()` 检查；命中 fallback，非命中原样上抛。
- `openspec validate fix-di-fallback-and-anomaly-island-cleanup --strict` PASS。

### 6.2 Non-blocking note

- `tasks.md` commit body 的合流门槛摘要中仍有一句 “A17 grep MissingAndroidContextException throw+catch 各命中”。由于 §12.5 已明确要求 “throw 路径 + cause chain 类型参数”，且 commit body 主段已正确描述 cause-chain 方案，此处按 P3 文案残留豁免，不阻塞 apply。实施 commit 时建议写成 “throw + findInCauseChain 各命中”。

### 6.3 Verdict

Round 4 通过。允许进入 `/opsx:apply`。代码落地后请提交 commit diff 给评审方按 A17 / A30 核销。

## 7. Implementation discovery decision

### 7.1 Discovery

实施期 §2 probe 发现 Koin Android 3.5.3 已自带 `org.koin.android.error.MissingAndroidContextException`。评审方本地核实 `koin-android-3.5.3.aar` 的 `classes.jar` 中确有 `org/koin/android/error/MissingAndroidContextException.class`。

### 7.2 Decision

批准选择 **B：复用 Koin 自带 `org.koin.android.error.MissingAndroidContextException`**。

允许实施方同步小范围修订 OpenSpec 工件：

- 删除自建 `com.blazepush.feature.test.di.MissingAndroidContextException` 类型契约与任务。
- `single<ReplayTrackSource>` 保持零改动，让 `androidContext()` 自然抛 Koin 自带 `MissingAndroidContextException`。
- `single<TrackCatalog>` 仍保留 v4 已批准的 cause-chain 检查，但标记类型改为 `org.koin.android.error.MissingAndroidContextException`。
- A17 测试继续锁两件事：JVM 环境 fallback 到 `PresetTrackCatalog`；fake `ReplayTrackSource.loadReplayJson()` 抛 `IOException` 时 `getAllTracks()` 原样上抛。

### 7.3 Rationale

复用 Koin 自带类型更简单，减少一个自建 wrapper 文件与一层异常转换；同时不削弱 v4 已批准的核心安全边界：只有 cause chain 命中 Koin 缺 Android Context 标记时 fallback，其他异常走 `throw e`。

### 7.4 Verdict

实施方可按方案 B 对齐工件后继续 `/opsx:apply`，无需重启完整 review。代码落地后仍需提交 commit diff 给评审方最终核销。
