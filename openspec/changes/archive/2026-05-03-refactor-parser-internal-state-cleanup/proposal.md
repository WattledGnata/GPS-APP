# refactor-parser-internal-state-cleanup

战役 H parser 清理一期：把 `RaceChronoParser` 里两类内部状态污染同时收敛。闭环
attack-backlog **A26**（`parseGpsTimeData` 无条件写 `isTestReady = true` 与主包判定
冲突）+ **A41**（parser `totalDistance / hasStartedTracking` 死状态）。两条都来自
`2026-04-22-lap-timing-and-gps-adversarial-review.md § 2.11 / § 2.12`，作用域
都在 `core/bluetooth/.../parser/RaceChronoParser.kt` 及其 Test，scope 同类且互不
交叉，合一 change 承接。

依赖关系：
- 已闭环 `fix-parser-signed-int-decoding`（A16a）+ `fix-altitude-encoding-contract-alignment`
  （A16b）—— 前置 parser 契约对齐完成后的纯**状态清理**，不依赖任何未闭环攻击点
- 不影响 `fix-laptime-clock-source-integrity`（A8 单源 `protocolTimeReference`）的
  时间同步契约：本 change 保留 `parseGpsTimeData` 对 `protocolTimeReference` 的写入，
  只删 `isTestReady` 的副作用写入（与 `protocolTimeReference` 解耦）

**决策面**：两个 Requirement 的核销条件都在 backlog A26 / A41 条目内明确拍板，本
proposal 不开新决策，只把结论落到代码 + 测试。

## Why

对抗 review `docs/superpowers/reviews/2026-04-22-lap-timing-and-gps-adversarial-review.md`
§ 2.11 / § 2.12 揭示 parser 两类内部状态污染：

### 问题 1（A26）：`parseGpsTimeData` 写 `isTestReady = true` 与主包判定冲突

当前实现 `RaceChronoParser.kt:111-115`：

```kotlin
if (!currentData.isTestReady) {
    currentData.copy(isTestReady = true, errorMessage = null)
} else {
    currentData.copy(errorMessage = null)
}
```

- 时间包到达直接置 `isTestReady = true`，不看 satellites / hdop 质量信号
- 下一帧主包又按 `satellites >= 6 && hdop < 2.0`（`parseGpsData` L311）覆盖判定
- 冷启动锁星不稳时时间包与主包交替到达，UI 出现"就绪 ↔ 未就绪"闪烁
- `isTestReady` 的**唯一真相源应是主包的 satellites/hdop 组合**，时间包只负责
  更新 `protocolTimeReference` 用于下一帧时间同步判定

### 问题 2（A41）：parser 维护 `totalDistance / hasStartedTracking` 等 5 个字段全是死状态

当前实现 `RaceChronoParser.kt:37-41, 245-282`：

```kotlin
// 字段声明
private var startTime: Long = 0
private var totalDistance: Double = 0.0
private var lastLatitude: Double? = null
private var lastLongitude: Double? = null
private var hasStartedTracking = false

// parseGpsData 内的 Tracking Calculation (Non-Critical) 块
if (fixQuality > 0 && satellites >= 3) {
    if (!hasStartedTracking) {
        hasStartedTracking = true
        startTime = System.currentTimeMillis()
        lastLatitude = currentLatitude
        lastLongitude = currentLongitude
        totalDistance = 0.0
    } else {
        // Location.distanceBetween 累加 distanceStep / 1000.0 到 totalDistance
        ...
    }
}
```

审计结论（`rg 'totalDistance|hasStartedTracking' -g '*.kt'`）：
- `totalDistance` 在其他模块（`CalculateResultUseCase` / `TestResultRepository` /
  `TestModels` / `BrakeTest` 相关）命中的全是**刹车测试**的同名字段，与 parser
  内部字段无任何读写关系
- `hasStartedTracking / startTime / lastLatitude / lastLongitude` 在 parser 外**零命中**
- parser 内部累加完从**不写回 `GpsData`**、无 getter、无外部订阅 → 纯副产物

更糟的是判定阈值不一致：
- tracking 块用 `fixQuality > 0 && satellites >= 3`（L248）
- isTestReady 用 `satellites >= 6 && hdop < 2.0`（L311）
- 两处阈值不对齐本身没有明确语义，纯粹历史残留

死代码浪费：
- 每帧 25Hz 调用 `Location.distanceBetween`（JNI 调用）
- 每帧 `System.currentTimeMillis()`（与战役 A 时钟源单源化原则冲突 —— 虽然
  仅内部使用但容易让后续维护者误以为 parser 依赖系统时钟）
- 内部状态漂移无测试覆盖 → 悄悄烂掉

### 为什么合一 change

1. 同域文件：两者都在 `RaceChronoParser.kt` 及其 Test，合一 change 减少 proposal/
   spec/tasks 三件套开销
2. 同类问题：两者都是"parser 副作用式写/维护本不该在 parser 层的状态"
3. 同 review 文档来源：`§ 2.11` + `§ 2.12` 相邻段落
4. 互不交叉：R1 改 `parseGpsTimeData` 的写入行为，R2 删 `parseGpsData` 的 tracking
   块 + 5 个字段声明，两块代码物理分离，改动独立验证

## What Changes

### R1（A26）：`parseGpsTimeData` 不再写 `isTestReady`

- 删除 `parseGpsTimeData` 成功分支 L111-115 的 `if (!currentData.isTestReady)` 判断
  + `copy(isTestReady = true, ...)` 分支
- 保留 `protocolTimeReference = ProtocolTimeReference(syncBits, hourStartMillis)`
  （L102-105）—— 时间包的**真正职责**
- 保留 `errorMessage = null` 清理（A25 契约闭合的必要部分，不在本 change 动）
- 新增独立文件 `RaceChronoParserTestReadyStateTest.kt` / 类 `RaceChronoParserTestReadyStateTest`
  承载本 change 契约（**不复用** 已存在的 `RaceChronoParserProtocolTimeTest.kt`
  ——后者承载 A8 时间同步契约 scope 不同）
- 新增断言：输入 `GpsData(isTestReady = false)` + 时间包 → 输出 `isTestReady == false`
  （硬区分 v1：v1 会输出 `true`）
- 同步修订既有 `RaceChronoParserTest.parseGpsTimeData_successPathExplicitlyClearsErrorMessage_sourceAssertion`
  源码断言（从"≥ 2 次 `errorMessage = null`"改为"≥ 1 次 + NOT 含 `isTestReady =
  true` / `if (!currentData.isTestReady)` 任一 v1 残留"），否则合流 `RaceChronoParserTest`
  全量会失败

### R2（A41）：删除 parser 内部死状态字段 + tracking 计算块

- 删字段：`startTime / totalDistance / lastLatitude / lastLongitude / hasStartedTracking`
  （L37-41）
- 删 `reset()` 内对应重置行（L53-57 五行）
- 删 `parseGpsData` 内 "Tracking Calculation (Non-Critical)" 整块（L245-282）
- 删 class comment 的 `Maintains state for frequency calculation and tracking
  (distance/time)` 里的 `and tracking (distance/time)`（L18），改为
  `Maintains state for frequency calculation`
- 新增测试 `RaceChronoParserInternalStateTest` / `parseGpsData_doesNotMaintainTotalDistance`
  用反射断言 `RaceChronoParser` 实例上不存在 `totalDistance / hasStartedTracking /
  startTime / lastLatitude / lastLongitude` 任何一个字段（若未来有人回改引入，
  测试立即失败）

### 文档

- class KDoc L15-22 的 "Maintains state for frequency calculation and tracking
  (distance/time)" 更新
- 不涉及 `docs/RaceChrono_BLE_Protocol.md`（协议字段不变）

## Non-goals

- **不动** parser frequency 计算块（L228-243）：frequency 有外部消费者
  （`GpsDataViewModel` 读 `gpsFrequency`），属活状态，A28 另有独立清理计划（滑窗化）
- **不动** `protocolTimeReference` 的写入（`parseGpsTimeData` 成功分支 L102-105）：
  时间包的真正职责，A8 战役已经固化为单源
- **不动** 主包 `isTestReady = satellites >= 6 && hdop < 2.0`（`parseGpsData` L311）：
  本 change 锁定**时间包不写 isTestReady**，主包仍是 isTestReady 的唯一写入源
- **不改** `reset()` 的其它清理（protocolTimeReference、frequency 相关）
- **不引入** 新的 distance 计算 domain usecase：若未来需要实时里程再起新 change
  （backlog A41 核销条件 (3) 明确）

## 验收（合流门槛）

1. `openspec validate refactor-parser-internal-state-cleanup --strict` PASS
2. `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserTest*"` BUILD SUCCESSFUL（含修订后的源码断言）
3. `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserTestReadyStateTest*"` BUILD SUCCESSFUL（R1 新增）
4. `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserInternalStateTest*"` BUILD SUCCESSFUL（R2 新增）
5. A8 既有契约零回归：`./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserProtocolTimeTest*"` BUILD SUCCESSFUL（本 change MUST NOT 修改或覆盖 A8 既有 `RaceChronoParserProtocolTimeTest.kt`）
6. 下游端到端测试零回归：`./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*" --tests "*LapTimingEngineTest*" --tests "*TestSessionViewModelTrackLapTest*"` BUILD SUCCESSFUL
7. `rg -n "totalDistance|hasStartedTracking|startTime|lastLatitude|lastLongitude" core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt` 零命中
8. `rg -n "isTestReady\s*=\s*true" core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt` 零命中（主包 L311 的 `isTestReady = satellites >= 6 && hdop < 2.0` 不是字面量赋值 true，不会命中）
9. R1 / R2 两个 Requirement 各出一个独立 commit，commit message 引用对应 backlog
   条目（A26 / A41）

## Rollback Plan

- 若 R1 落地后发现下游有代码依赖时间包写 `isTestReady=true`（审计漏查），回退
  commit 并把 R1 挂 🔴 pending，保留 R2 独立推进
- 若 R2 落地后 parser 外下游有隐式依赖（当前 grep 已确认无，但留后手），回退
  commit；R2 回退不影响 R1 行为
