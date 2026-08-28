# refactor-parser-internal-state-cleanup · code review

- **日期**：2026-04-24
- **评审对象**：
  - `9335ce0` R1 / A26
  - `3051253` R2 / A41
- **覆盖攻击点**：A26 / A41
- **评审方**：codex session
- **实施方**：session 1
- **轮次**：代码落地后最终核销 review
- **结论**：✅ 准予核销 A26 / A41

## 0. 结论摘要

R1 / R2 两个 commit 与 v2 tasks 的边界一致。`parseGpsTimeData` 已不再写 `isTestReady`，保留了 `protocolTimeReference` 写入与 A25 `errorMessage` 清理；parser tracking 死状态 5 字段、reset 对应清理、tracking 计算块和孤儿 `Location` import 都已删除，frequency / 时间同步 / A16b altitude 代码未被误碰。

实施期额外修订 `RP34_parseGpsTimeData_validData` 是必要修补：它属于同类 v1 残留，不修会让 `RaceChronoParserTest` 合流门槛失败。删除孤儿 `Location` import 也属于 R2 自然 cleanup。

本轮无新增 P0/P1/P2。

## 1. 🔴 P0 / P1

暂无。

## 2. 🟡 P2

暂无。

## 3. 已核销项

- R1：`parseGpsTimeData` 成功路径合并为 `currentData.copy(errorMessage = null)`，无 `isTestReady = true` / `if (!currentData.isTestReady)` v1 残留。
- R1：新增 `RaceChronoParserTestReadyStateTest` 4 条 Scenario，覆盖 false 保持、true 保持、冷启动 no-flicker、短包不动 ready。
- R1：既有 `RaceChronoParserTest` 源码断言和 RP34 已同步到 A26 契约。
- R2：5 个死字段 `startTime / totalDistance / lastLatitude / lastLongitude / hasStartedTracking` 从 production parser 类体删除。
- R2：`Tracking Calculation (Non-Critical)` 块整块删除，`Location.distanceBetween` 生产路径消失。
- R2：`reset()` 保留 `gpsDataTimestamps.clear()`、`gpsFrequency = 0.0`、`protocolTimeReference = null`，未顺手修 `lastFrequencyUpdateTime`，符合 scope。
- R2：新增 `RaceChronoParserInternalStateTest` 3 条 Scenario。

## 4. 本轮执行的复核命令

```bash
openspec validate refactor-parser-internal-state-cleanup --strict
rg -n "totalDistance|hasStartedTracking|startTime|lastLatitude|lastLongitude" \
  core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt
rg -n "isTestReady\s*=\s*true" \
  core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt
rg -n "@IgnoreFormatCheck" \
  core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTestReadyStateTest.kt \
  core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserInternalStateTest.kt
./gradlew :core:bluetooth:testDebugUnitTest \
  --tests "*RaceChronoParserTest*" \
  --tests "*RaceChronoParserTestReadyStateTest*" \
  --tests "*RaceChronoParserInternalStateTest*" \
  --tests "*RaceChronoParserProtocolTimeTest*"
```

结果：

- `openspec validate refactor-parser-internal-state-cleanup --strict`：PASS
- tracking 字段 grep：零命中
- `isTestReady\s*=\s*true` grep：零命中
- 新测试 `@IgnoreFormatCheck` grep：零命中
- parser 相关 Gradle 测试组合：BUILD SUCCESSFUL

## 5. 给实施方的回复模板

战役 H 一期 R1/R2 code review 最终核销通过 ✅。可将 A26 / A41 从 🔴 pending 迁入 ✅ resolved，并同步状态总表。后续可继续推进 A28 / A37，但起跑前请确认所有 session 的 HEAD 均包含 `9335ce0` 与 `3051253`。
