# 战役 C 二期 `fix-lap-timing-closure-and-precision-contract` · code review

- **日期**：2026-04-24
- **评审对象**：
  - commit `715a268` R1 detector crossingProgress
  - commit `ddfc42a` R2/R3 engine 插值时刻 + trajectory 时间窗口
  - commit `1f2e3c3` R4/R5/R7 多门遍历 + filter 严格 `>` + A33 断言
  - commit `b059335` R6 E2E ±5ms 合成契约收紧
- **覆盖攻击点**：A15 / A20 / A32 / A33
- **评审方**：Codex
- **结论**：🔴 **暂不核销**（1 个 P1 + 3 个 P2）

---

## 0. 结论摘要

代码主体方向正确，`openspec validate fix-lap-timing-closure-and-precision-contract --strict`
通过，目标测试与 `:core:domain:test` 也能跑绿。但本轮发现 1 个实现级契约漏洞：
R1 要求微越界 `t` 通过 clamp 收敛到 `[0, 1]`，当前实现会在 clamp 前把微越界 `t`
直接判成 `null`，导致本应 accepted 的边界穿线变成 `NoIntersection`。

另有 3 个测试强度问题：R4 多门同帧场景没有真正构造多门 accepted，R4 反序数据源
排序测试没有构造 `[S3, S2, S1]`，R3 empty trajectory 场景没有断言 `trajectory.isEmpty()`。
这些不一定说明生产代码当前错，
但违反 tasks / spec 的硬区分测试承诺，不能盖 ✅。

---

## 1. 🔴 P1（阻塞核销）

### P1-1 · R1 clamp 写在不可达位置，微越界 t 会被提前判成 NoIntersection

- **位置**：
  - `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt:139-145`
  - `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt:180-184`
- **问题**：
  - spec R1 明确要求 accepted 分支的 `crossingProgress` 必须经 `coerceIn(0.0, 1.0)`
    clamp，防 `segmentsIntersectMeters` 内部计算出 `-1e-16` 或 `1.0000001` 这类
    浮点边界微越界。
  - 但当前 `segmentsIntersectMeters` 先执行：
    ```kotlin
    return if (t in 0.0..1.0 && u in 0.0..1.0) t else null
    ```
    这会把 `t = -1e-16` / `1.0000001` 直接返回 `null`。
  - 因此 `detect` 里的 `intersectionT.coerceIn(0.0, 1.0)` 只会处理已经落在范围内的
    `t`，对 spec 里点名的微越界场景不可达。
- **后果**：边界穿线可能从 accepted 降级成 `NoIntersection`，R1 的 clamp 契约没有真正实现。
- **建议修订**：
  - 引入一个极小 epsilon，仅用于判定 `t/u` 是否为边界微越界；返回原始 `t` 交给
    `detect` 的 `coerceIn` 做最终 clamp。
  - 测试不要只断言 `Double.coerceIn` 纯函数；需要构造或直接注入 `segmentsIntersectMeters`
    可返回微越界 `t` 的路径，硬断言 `detect` 结果仍 accepted 且 `crossingProgress`
    clamp 到 `0.0` / `1.0`。

---

## 2. 🟡 P2（阻塞核销，测试强度不足）

### P2-1 · R4 多门同帧测试未真正覆盖“期待门 + 2 非期待门”

- **位置**：`feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt:1081-1125`
- **问题**：
  - spec R4 Scenario 要求 `(prev, current)` 同时过期待门 + 2 个非期待门，断言
    `crossingEvents.size == 原值 + 3`，硬区分 v1 只记 1 条。
  - 当前测试方法名是 `handleSectorCrossing_multiGateAcceptedInSingleStep_recordsAllWithOrdering`，
    但注释承认 TFIC fixture 实际“仅单门过线”，断言也只是
    `result.crossingEvents.size >= initialCrossingSize + 1`。
  - 这不会区分“遍历所有门”与“只处理期待门”的退化实现。
- **建议修订**：构造一个测试专用 track，至少 3 个 sector gate，且同一条
  `(prev, current)` 几何上能同时 accepted 期待门 + 两个非期待门；断言新增事件精确
  `+3`，顺序与 reason / accepted 标志逐项匹配。

### P2-2 · R4 反序数据源排序测试未覆盖 `[S3, S2, S1]` 与多个非期待门 accepted

- **位置**：`feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt:1166-1197`
- **问题**：
  - spec R4 Scenario 5 要求即使 `track.sectorGates` 数据顺序为 `[S3, S2, S1]`，
    engine 内部 `sortedBy { sequenceIndex }` 后非期待门输出仍为 `[S2, S3]`。
  - 当前测试仍使用 TFIC preset 的两个 sector gates，且只产生 `[S1 rejected, S2 unexpected]`。
    它没有构造反序数据源，也没有两个非期待门 accepted。
- **建议修订**：同样使用测试专用 3-sector track，输入顺序显式为 `[S3, S2, S1]`，
  期待门为 S1，同帧 accepted S2/S3，断言非期待门顺序严格 `[S2, S3]`。

### P2-3 · R3 empty trajectory 场景没有断言 empty

- **位置**：`feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt:1299-1333`
- **问题**：
  - tasks §3.6 / spec R3 Scenario 5 要求构造“开圈后立即闭圈无推进帧”，断言
    `trajectory.isEmpty()`。
  - 当前测试注释承认 fixture 实际得到非空 trajectory，最终只断言“不含闭圈帧”。
  - 这已经由 R3 Scenario 1 覆盖，不能替代 empty boundary。
- **建议修订**：调整 fixture，使 `activeLap.sampleStartIndex` 对应的开圈 current 帧也不落入
  `[startedAtMillis, finishedAtMillis)`，并直接断言 `lap.trajectory.isEmpty()` 与
  `durationMillis == 20L`。

---

## 3. 验证记录

- `openspec validate fix-lap-timing-closure-and-precision-contract --strict`：PASS
- `./gradlew :feature:test:testDebugUnitTest --tests "*GateCrossingDetectorTest*" --tests "*LapTimingEngineTest*" --tests "*EndToEndLapTimingContractTest*" --tests "*TestSessionViewModelTrackLapTest*"`：BUILD SUCCESSFUL
- `./gradlew :core:domain:test`：BUILD SUCCESSFUL
- 注：首次 feature:test 在沙盒内因 `~/.gradle` wrapper lock 权限失败，已用授权后的同一命令复跑通过。

---

## 4. 核销建议

暂不把 A15 / A20 / A32 / A33 迁 ✅。请实施方先修 P1-1，并补齐 P2-1 / P2-2 / P2-3
的硬区分测试；修完后重新提交 mini review。修订项共 4 条，未达到 5 条，不单独产出
`review-vN-patches.md`。

---

## 5. mini review：commit `79c4323` 复核

- **日期**：2026-04-24
- **评审对象**：commit `79c4323`（code review 修订 1 P1 + 3 P2）
- **结论**：✅ **通过，可核销 A15 / A20 / A32 / A33**

### 5.1 上轮 finding 关闭情况

| Finding | 复核结论 |
|---|---|
| P1-1 R1 clamp 可达性 | 已修。`segmentsIntersectMeters` 增加 `FLOAT_BOUNDARY_TOLERANCE = 1e-9` 与 `tolerance` 参数，容差内返回原始 `t`，`detect` 再 `coerceIn(0.0, 1.0)` |
| P2-1 多门同帧测试 | 已修。新增 3-sector 测试 track，同一帧跨 S1/S2/S3，硬断言 `crossingEvents +3` 与 `[S1, S2, S3]` 顺序 |
| P2-2 反序数据源排序 | 已修。`track.sectorGates=[S3,S2,S1]` 下仍输出 `[S1,S2,S3]`，覆盖数据源顺序与 `sequenceIndex` 排序解耦 |
| P2-3 empty trajectory | 已修。手动构造窗口 `[500,520)` 内无样本，硬断言 `trajectory.isEmpty()` 与 `durationMillis == 20L` |

### 5.2 验证记录

- `openspec validate fix-lap-timing-closure-and-precision-contract --strict`：PASS
- `./gradlew :feature:test:testDebugUnitTest --tests "*GateCrossingDetectorTest*" --tests "*LapTimingEngineTest*" --tests "*EndToEndLapTimingContractTest*" --tests "*TestSessionViewModelTrackLapTest*"`：BUILD SUCCESSFUL
- `./gradlew :core:domain:test`：BUILD SUCCESSFUL
- tasks §8.7 关键词 grep 审计：PASS（6 项均 `(无残留)`）
- tasks §8.8 圈时字段 grep：PASS（无输出）
- tasks §8.11 speed 字段 grep：PASS（无输出）

### 5.3 剩余风险

- `tasks.md` checkbox 仍为未勾选，`openspec status` 因此显示 `0/59 tasks`。这是实施方文档回填问题，不是代码行为阻塞；归档前应由实施方按实际完成状态回填。
- `:core:bluetooth:testDebugUnitTest` 在本 change 中按 tasks §8.6b 是战役 G 并行期软检；战役 G 已另行核销。
