# 实施任务（依赖顺序）

本 change 包含 2 个 capability，跨 di + domain + viewmodel + test 四层。

- **§1-§4 A17 段**（独立 OK，不依赖 A30）：复用 Koin 自带 `org.koin.android.error.MissingAndroidContextException` 作 cause chain 标记 + AppModule `single<TrackCatalog>` cause chain 改造（B 方案 scope：DI provider 创建期）
- **§5-§9 A30 段**（BREAKING 连锁）：删除 3 文件 + AppModule + GpsDataViewModel + 调用方迁移
- **§10-§11 测试段**：A17 追加 2 测试 + 全测回归
- **§12 合流门槛 + §13 commit**

参考 `proposal.md` / `design.md` / `specs/di-real-device-error-propagation/spec.md` / `specs/anomaly-island-removal/spec.md`。

---

## 0. grep 预检（已核实，作为实施依据存档）

- [x] 0.1 **`MissingAndroidContextException` 真相**：grep 全仓 `class MissingAndroidContextException` 在项目源码内仅命中 `AppModule.kt:90` 注释字面量；实施期 §2 实测发现 Koin Android 库 koin-android-3.5.3 自带 `org.koin.android.error.MissingAndroidContextException`，B 方案复用之，**不**新建项目内 wrapper
- [x] 0.2 **A17 修补点**：`AppModule.kt:88` `single<ReplayTrackSource>` + `:92-95` `single<TrackCatalog> { runCatching {...}.getOrElse {...} }`
- [x] 0.3 **A30 三文件路径**：`core/domain/src/main/java/com/blazepush/core/domain/usecase/{AnomalyDetector,DataInterpolator,DataSmoothing}.kt` 全部存在
- [x] 0.4 **A30 DI 注册点**：`AppModule.kt:13/15/17` 三 import + `:83/84/85` 三 factory + `viewModel { GpsDataViewModel(get(), get(), get(), get()) }` 第 4 个 get 是 dataSmoothing
- [x] 0.5 **A30 ViewModel 消费**：`GpsDataViewModel.kt:14` import + `:28` 构造参数 + `:171` `dataSmoothing.reset()` 共 3 处
- [x] 0.6 **A30 现有 ViewModel 测试构造点**：`GpsDataViewModelTest.kt`（Round 2 新增）含 `mock(DataSmoothing::class.java)` 实参，需同步迁移
- [x] 0.7 **`DomainModuleKoinTest.kt` 已存在**：`feature/test/src/test/.../di/`，追加测试不新建文件

---

## 1. A17 · 复用 Koin 自带 `org.koin.android.error.MissingAndroidContextException`（D1，实施期决策 B）

- [x] 1.1 **不新建**项目内 `MissingAndroidContextException` 类型。实施期 §2 实测发现 Koin Android 库（koin-android-3.5.3）已自带 `org.koin.android.error.MissingAndroidContextException`，复用之作为 cause chain 标记类型。v2 design 计划新建的 `feature/test/src/main/java/com/blazepush/feature/test/di/MissingAndroidContextException.kt` MUST 不存在（实施期 §1 创建后由 D1 简化删除）。
- [x] 1.2 **§1 简化无独立编译门槛**：直接进 §3 改造时一并验证编译。

---

## 2. A17 · 实测 Koin 异常类型（D2）

- [x] 2.1 **临时跑现有 `DomainModuleKoinTest`** 在 JVM 环境观察 Koin `androidContext()` 缺 Context 时实际抛的异常类型（**Review v2 P2 修补**：现有测试名是 `domainModule_providesTrackCatalog`，不是 `providesTrackCatalog`；用 wildcard 覆盖整个 class 避免名字硬编码漂移）：

  ```bash
  ./gradlew :feature:test:testDebugUnitTest --tests "*DomainModuleKoinTest*" --info 2>&1 | grep -iE "exception|caused by|noBeanDef|instanceCreation|illegalState" | head -10
  ```

  预期候选：`NoBeanDefFoundException` / `InstanceCreationException` / `IllegalStateException`，但实测一次锁死。

  注：当前 `DomainModuleKoinTest.kt` 已含 `domainModule_providesGpsDataFilter` + `domainModule_providesTrackCatalog` 两条测试，后者会触发 `androidContext()` 调用 → 真实抛出的 Koin 类型在 stderr / `--info` 日志可见。
- [x] 2.2 **决策记录**：在 commit body 或 design.md D2 段补充实测结果，明示选定的 Koin 异常类型（写入 D3 改造代码）

---

## 3. A17 · `single<ReplayTrackSource>` 保持零改动（D3，实施期 D1 简化的直接结果）

- [x] 3.1 **`AppModule.kt:88` `single<ReplayTrackSource> { AssetReplayTrackSource(androidContext()) }` 保持原状不改**。实施期 §2 实测发现 `androidContext()` 在 JVM 缺 Context 时自然抛 Koin 自带 `org.koin.android.error.MissingAndroidContextException`，被 Koin 包装为 `InstanceCreationException(cause = MissingAndroidContextException)` 透传给 `single<TrackCatalog>` 的 caller，由 §4 cause chain 处理。零项目内类型转换层。

---

## 4. A17 · 收窄 `single<TrackCatalog>` catch + 删误导注释（D4）

- [x] 4.1 **`AppModule.kt:92-95` 改造** `single<TrackCatalog>`（**Review v1 P1 修补**：Koin 3.5.3 包装为 InstanceCreationException，直接 catch MissingAndroidContextException 不命中；改为 cause chain 检查）：

  ```kotlin
  // 改前
  single<TrackCatalog> {
      runCatching { ReplayAlignedTrackCatalog(get(), PresetTrackCatalog()) }
          .getOrElse { PresetTrackCatalog() }
  }

  // 改后（cause chain 方案，实施期 D1 简化：复用 Koin 自带类型作标记）
  // import org.koin.android.error.MissingAndroidContextException  ← Koin Android 自带
  single<TrackCatalog> {
      try {
          ReplayAlignedTrackCatalog(get(), PresetTrackCatalog())
      } catch (e: Throwable) {
          // Koin 把 androidContext() 失败包成 InstanceCreationException —— 遍历 cause chain
          // 找 Koin 自带 MissingAndroidContextException 标记
          if (e.findInCauseChain<MissingAndroidContextException>() != null) {
              // JVM 单测环境：缺 Android Context 合法场景，降级到 preset
              PresetTrackCatalog()
          } else {
              // 其他异常（IOException / JsonSyntaxException / asset 损坏 / 磁盘读错等）
              // 原样上抛，让真机用户 / 崩溃上报可见
              throw e
          }
      }
  }

  // 加 file-private 工具到 AppModule.kt 同 file 末尾
  private inline fun <reified T : Throwable> Throwable.findInCauseChain(): T? {
      var current: Throwable? = this
      while (current != null) {
          if (current is T) return current as T
          current = current.cause
      }
      return null
  }
  ```

  注意：
  - import `org.koin.android.error.MissingAndroidContextException`（Koin Android 库自带，**不**自建项目内同名 wrapper）
  - `findInCauseChain` 是 `inline fun` + `reified T`，必须在 file-level 定义不能放 class 内
  - 放 `AppModule.kt` 同 file 末尾（scope 最小）
- [x] 4.2 **删除误导注释**（`AppModule.kt:89-91` 原 v1 注释）：

  原注释提"会抛 `MissingAndroidContextException`"但当时类型不存在。改为新注释明确"§3 把 Koin 内部异常转换为本类型"+ "§4 catch 范围精确仅限本类型"。
- [x] 4.3 **编译门槛**：`./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL

---

## 5. A30 · 删除 3 个 domain 文件（D6）

> **注意**：§5-§8 是 BREAKING 连锁，必须**一气做完**到 §9 编译门槛才能通过。中间步骤会因 `GpsDataViewModel` 构造器变更暂时编译失败，**预期行为**。

- [x] 5.1 **删除** `core/domain/src/main/java/com/blazepush/core/domain/usecase/AnomalyDetector.kt` 整文件（`git rm` 或 IDE 删除）
- [x] 5.2 **删除** `core/domain/src/main/java/com/blazepush/core/domain/usecase/DataInterpolator.kt` 整文件
- [x] 5.3 **删除** `core/domain/src/main/java/com/blazepush/core/domain/usecase/DataSmoothing.kt` 整文件

---

## 6. A30 · 改造 `AppModule.kt` 删除孤岛 DI 注册

- [x] 6.1 **删除 import**（`AppModule.kt:13/15/17`）：
  - `import com.blazepush.core.domain.usecase.AnomalyDetector`
  - `import com.blazepush.core.domain.usecase.DataInterpolator`
  - `import com.blazepush.core.domain.usecase.DataSmoothing`
- [x] 6.2 **删除 factory 注册**（`AppModule.kt:83-85`）：
  - `factory { AnomalyDetector() }`
  - `factory { DataSmoothing() }`
  - `factory { DataInterpolator() }`
- [x] 6.3 **改造 viewModel 注入**：找到 `viewModel { GpsDataViewModel(get(), get(), get(), get()) }`（apply 阶段 grep 精确定位行号），去掉最后一个 `get()`：

  ```kotlin
  // 改前
  viewModel { GpsDataViewModel(get(), get(), get(), get()) }

  // 改后
  viewModel { GpsDataViewModel(get(), get(), get()) }
  ```

---

## 7. A30 · 改造 `GpsDataViewModel.kt`

- [x] 7.1 **删除 import**（`GpsDataViewModel.kt:14`）：`import com.blazepush.core.domain.usecase.DataSmoothing`
- [x] 7.2 **删除构造参数**（`GpsDataViewModel.kt:28`）：`private val dataSmoothing: DataSmoothing,`
- [x] 7.3 **删除 reset 调用**（`GpsDataViewModel.kt:171`）：`dataSmoothing.reset()` 整行删除
- [x] 7.4 **更新 KDoc**（如有提及 dataSmoothing）：`resetStats()` KDoc 中 "+ dataSmoothing.reset()" 字面量删除（保持文档与代码一致）

---

## 8. A30 · 测试调用方迁移

- [x] 8.1 **`GpsDataViewModelTest.kt`**（Round 2 新增）：构造 `GpsDataViewModel(...)` 处去掉 `dataSmoothing = mock(DataSmoothing::class.java)` 实参，删除该 import：

  ```kotlin
  // 改前
  viewModel = GpsDataViewModel(
      gpsDataRepository = repo,
      bleDeviceManager = mock(BleDeviceManager::class.java),
      dataQualityEvaluator = DataQualityEvaluator(),
      dataSmoothing = mock(DataSmoothing::class.java),
  )

  // 改后
  viewModel = GpsDataViewModel(
      gpsDataRepository = repo,
      bleDeviceManager = mock(BleDeviceManager::class.java),
      dataQualityEvaluator = DataQualityEvaluator(),
  )
  ```

  同时删除 `import com.blazepush.core.domain.usecase.DataSmoothing` 若孤立。
- [x] 8.2 **全仓 grep 其他 `GpsDataViewModel(` 调用方**：

  ```bash
  grep -rnE "GpsDataViewModel\(" feature/ core/ app/ --include="*.kt" | grep -v "mock(GpsDataViewModel"
  ```

  对每个命中点检查参数数（应改为 3 参数）；若实测 grep 还命中其他文件，逐一迁移。

---

## 9. A30 · 编译门槛（BREAKING 连锁结束）

- [x] 9.1 **`./gradlew :core:domain:compileKotlin`** BUILD SUCCESSFUL（3 文件删后无引用残留）
- [x] 9.2 **`./gradlew :feature:test:compileDebugKotlin`** BUILD SUCCESSFUL（GpsDataViewModel 构造器 BREAKING 后所有调用方迁移完成）
- [x] 9.3 **`./gradlew :app:compileDebugKotlin`** BUILD SUCCESSFUL

---

## 10. A17 · 测试段（D5）

- [x] 10.1 **追加测试 1：`providesTrackCatalog_jvmEnvironment_fallsBackToPresetGracefully`**（强化现有隐式契约）：

  ```kotlin
  @Test
  fun providesTrackCatalog_jvmEnvironment_fallsBackToPresetGracefully() {
      stopKoin()
      startKoin { modules(domainModule) }
      try {
          val trackCatalog = get<TrackCatalog>(TrackCatalog::class.java)
          assertTrue(
              "JVM 环境应降级到 PresetTrackCatalog；实际 ${trackCatalog::class.simpleName}",
              trackCatalog is PresetTrackCatalog,
          )
      } finally {
          stopKoin()
      }
  }
  ```

  补 import：`import com.blazepush.feature.test.repository.PresetTrackCatalog` + `import org.junit.Assert.assertTrue`（若未含）。
- [x] 10.2 **追加测试 2：`providesTrackCatalog_realDeviceAssetFailure_propagatesNotSilenced`**（关键 P1 契约，**Round 4 review v3 修补 B 方案 scope**：测 DI provider 创建期异常上抛，不走 `getAllTracks()` 路径，因 A37 `ensureReplayTrackLoaded` 内 `runCatching` 自吞 IOException 降级 fallbackCatalog 不抛 —— 已核销契约不修订）：

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
          // cause chain 应含原始 IOException（被 Koin 包装但 cause 链可达）
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

  补 import：
  - `import com.blazepush.feature.test.repository.ReplayTrackSource`
  - `import java.io.IOException`
  - `import kotlinx.coroutines.runBlocking`
  - `import org.junit.Assert.assertEquals` + `assertThrows`
  - `import org.koin.dsl.module`
- [x] 10.3 **测试门槛**：`./gradlew :feature:test:testDebugUnitTest --tests "*DomainModuleKoinTest*"` 全绿

---

## 11. 全测回归

- [x] 11.1 **`./gradlew :feature:test:testDebugUnitTest`** BUILD SUCCESSFUL（A30 删除后 `GpsDataViewModelTest` / `TestSessionViewModelTrackLapTest` / 其他所有创建 ViewModel 的 test 全绿）

---

## 12. 合流门槛（non-negotiable）

- [x] 12.1 **Spec 验证**：`openspec validate fix-di-fallback-and-anomaly-island-cleanup --strict` 返回 `Change ... is valid`
- [x] 12.2 **下游零回归**：
  - `./gradlew :core:bluetooth:testDebugUnitTest`（不涉及）
  - `./gradlew :core:domain:test`（A30 删除后零残留引用）
  - `./gradlew :app:compileDebugKotlin`
- [x] 12.3 **E2E 契约**：`./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"` 全绿
- [x] 12.4 **A30 grep 自检**：

  ```bash
  grep -rnE "\bAnomalyDetector\b|\bDataInterpolator\b|\bDataSmoothing\b" core/ feature/ app/ simulator/ --include="*.kt"
  ```

  预期**零命中**（已全部删除 + 调用方迁移完成）
- [x] 12.5 **A17 grep 自检**：
  - `AppModule.kt` 内 `single<TrackCatalog>` 周围 `runCatching` 零命中（已替换为精确 try/catch）
  - `MissingAndroidContextException` 在 `AppModule.kt` 通过 `findInCauseChain<MissingAndroidContextException>()` 引用 + `import org.koin.android.error.MissingAndroidContextException`（B 方案：复用 Koin 自带类型，**不**自建 wrapper；throw 由 Koin 自带库 `androidContext()` 内部做，不在 AppModule.kt 源文件 grep 可见）
  - 项目内 `class MissingAndroidContextException` 自建类型在 `core/`、`feature/`、`app/`、`simulator/` **零命中**
- [x] 12.6 **A17 迁档**：`docs/superpowers/reviews/attack-backlog.md` 一节 `🔴 pending` 删除 A17 条目，三节 `🟢 pending_review` 新增 A17 条目（覆盖核销条件 1-2）+ 附录表格状态列同步
- [x] 12.7 **A30 迁档**：同上 A30 条目（覆盖方案 b 删除清单 + 零回归门槛）
- [x] 12.8 **backlog 迁档 grep 自检**：`grep -nE "^### A17\b|^### A30\b|\| A17 \||\| A30 \|"` 应只命中 🟢 节 + 附录共 4 条，🔴 节零命中

---

## 13. Commit 策略

本 change scope 中等（A17 行为修复 + A30 删除性变更，2 capability 正交但 A30 是 BREAKING），**1 个代码 commit**：

- [x] 13.1 **commit**：`fix(di): 战役 D 尾巴 A17 + 战役 H 一期 A30 DI fallback 真机异常传播 + 删除 anomaly 孤岛`

  body 要点：
  - **A17（di-real-device-error-propagation，B 方案 scope）**：复用 Koin Android 自带 `org.koin.android.error.MissingAndroidContextException` 作为 cause chain 标记（实施期 §2 实测发现，评审方放行 B 方案，**不**新建项目内自建 wrapper）；`AppModule single<ReplayTrackSource>` 保持零改动（让 `androidContext()` 自然抛 Koin 自带类型，被 Koin 包装为 `InstanceCreationException(cause = MissingAndroidContextException)` 透传）；`single<TrackCatalog>` 捕获 Koin 包装层后通过 `findInCauseChain<MissingAndroidContextException>()` 检查 cause chain 标记，**命中 fallback 到 PresetTrackCatalog，非命中原样上抛 `throw e`**；scope 限定为 **DI provider 创建期**（A37 `ensureReplayTrackLoaded` 内 `runCatching {}.getOrNull()` 容错降级 fallbackCatalog 是已核销契约，本 round 不修订）；删除 v1 误导注释；新增 `DomainModuleKoinTest.providesTrackCatalog_jvmEnvironment_fallsBackToPresetGracefully`（cause chain 命中 → preset）+ `_realDeviceAssetFailure_propagatesNotSilenced`（fake provider 直接抛 IOException → cause chain 不命中 → throw e 上抛）2 测试
  - **A30（anomaly-island-removal）方案 b 删除**：删除 `core/domain/usecase/AnomalyDetector.kt` + `DataInterpolator.kt` + `DataSmoothing.kt` 三文件；`AppModule.kt` 删 3 import + 3 factory + viewModel 注入参数从 4 降为 3；`GpsDataViewModel.kt` BREAKING 删 import + 构造参数 + reset 调用；`GpsDataViewModelTest` 等所有调用方同步迁移
  - 实测 Koin 异常类型：${apply 阶段 §2.2 实测结果}
  - 合流门槛：openspec validate --strict ✅ / :feature:test :core:bluetooth :core:domain :app 全绿 ✅ / E2E 契约 ✅ / A30 grep 全仓零命中 ✅ / A17 grep `findInCauseChain<MissingAndroidContextException>` + `import org.koin.android.error.MissingAndroidContextException` 各命中 ✅ / 项目内自建 `class MissingAndroidContextException` 零命中 ✅

  格式约束：
  - Conventional Commits
  - body 含 "A17" + "A30" 便于 grep
  - Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
- [x] 13.2 **commit 后回填 backlog 附录表格 commit 号**：A17 + A30 行 `{pending commit}` 替换为实际 hash
