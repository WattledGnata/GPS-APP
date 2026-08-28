# fix-lap-timing-campaign-c-tail-cleanup spec/tasks review

- **日期**：2026-04-24
- **评审方**：codex
- **评审对象**：
  - `openspec/changes/fix-lap-timing-campaign-c-tail-cleanup/proposal.md`
  - `openspec/changes/fix-lap-timing-campaign-c-tail-cleanup/tasks.md`
  - `openspec/changes/fix-lap-timing-campaign-c-tail-cleanup/specs/lap-timing-engine/spec.md`
  - `openspec/changes/fix-lap-timing-campaign-c-tail-cleanup/specs/gps-data-filter/spec.md`
- **结论**：🔴 暂不放行 `/opsx:apply`，需修 1 个 P1 + 1 个 P2 后重提 mini review

## 0. TL;DR

A36 / A43 / A44 合并为 C 三期尾巴清理的方向可以接受，`openspec validate
fix-lap-timing-campaign-c-tail-cleanup --strict` 已通过。阻塞点在两处执行契约：

1. A44 antimeridian 测试 fixture 物理量级不成立。当前 `0.002° / 40ms` 对应约
   `20,000 km/h`，v2 也会判 `isPositionAnomaly=true`，无法按 spec/tasks 通过。
2. A43 把 `circularMedian` 零残留作为合流门槛，但 tasks 又把测试方法 / 注释 rename
   写成可选；现有测试源码内确实有 `circularMedian` 残留，会让 4.8 失败。

## 1. Findings

### P1-1 · A44 antimeridian fixture 会让 v2 也判异常

- **位置**：
  - `openspec/changes/fix-lap-timing-campaign-c-tail-cleanup/tasks.md:75-79`
  - `openspec/changes/fix-lap-timing-campaign-c-tail-cleanup/specs/gps-data-filter/spec.md:77-85`
  - `openspec/changes/fix-lap-timing-campaign-c-tail-cleanup/proposal.md:250,274`
- **问题**：
  - tasks 要求第 1 帧 `lon=179.999`，第 2 帧 `lon=-179.999`，`ts=prev+40ms`，
    并断言 `isPositionAnomaly == false`、`consistencyFactor ≈ 1.0`。
  - `wrappedDeltaLon` 后真实经度差是 `0.002°`，赤道距离约 `222.64m`。在 `40ms`
    内对应 `vImpliedKmh ≈ 20,037 km/h`。
  - 当前 `GpsDataFilter.getConsistencyTolerance(speed=50)` 容差是 `5 km/h`，所以
    `ratio` 远大于 3，v2 也会返回 `isPositionAnomaly=true`、`consistencyFactor=0.3`。
  - spec 里还同时写了两套互相矛盾的坐标：先给 `prev=179.99/current=-179.99`，
    又"修正"成 `current=179.999, prev=-179.999`，但 THEN 计算用的是
    `-179.999 - 179.999`。这会误导实施方构造测试。
- **建议修订**：
  - 使用物理速度自洽的跨线小位移。例如 `dt=40ms, speed=50km/h` 时，距离应约
    `0.56m`，经度差应约 `0.000005°`：
    - `prev.lon = 179.9999975`
    - `current.lon = -179.9999975`
    - `wrappedDeltaLon = 0.000005°`
    - `distanceM ≈ 0.5566m`
    - `vImpliedKmh ≈ 50.1km/h`
  - 或保留 `0.002°`，但把 `dt/speed` 改到物理自洽；注意 `dt > 0.2s` 会触发当前
    filter 的早退，所以更推荐缩小经度差。
  - spec/proposal/tasks 三处坐标方向统一，只保留一组 `prev -> current`，并把 v1/v2
    预期数值写清楚。

### P2-1 · circularMedian 零残留门槛与"测试 rename 可选"冲突

- **位置**：`openspec/changes/fix-lap-timing-campaign-c-tail-cleanup/tasks.md:45-53,97-101`
- **问题**：
  - tasks §4.8 要求 `grep -rn "circularMedian" core/domain/src feature/test/src core/bluetooth/src`
    期望空输出。
  - 但 §2.3 又把测试方法名同步 rename 写成"可选"。
  - 当前仓库实测 `circularMedian` 不只在 main 里，还在
    `GpsDataFilterTest.kt` 的测试方法名 / 注释中出现：
    - `GF09_bearingCrossZero_circularMedian`
    - `GF20b_bearingCircularMedian_crossesZero` 附近注释
  - 如果实施方只按 §2.1 改 main 函数和调用点，不改测试名/注释，§4.8 必失败。
- **建议修订**：
  - 将 §2.3 的测试方法名 / 注释 rename 改为必做，并列出当前已知残留点。
  - 或把 §4.8 grep 收窄到 `core/domain/src/main`，但这会削弱 A43 "命名不再误导"
    的目标；更推荐全仓测试名和注释也同步改为 `circularMean`。

## 2. P3 / 文案建议

- `lap-timing-engine/spec.md:44` 写"sectorGates 为反序与正序的输入，但 sectorGates
  本身 List 相等"语义矛盾。建议改成"两个 Track 的声明字段完全相同；其中一个先访问
  `orderedSectorGates`，另一个不访问"。
- `proposal.md:258` 说 `by lazy` "生成的是 getter（不是声明属性）"不准确。它是
  data class body 内的成员属性，只是不在 primary constructor 中，因此不参与
  data class 自动 `equals/hashCode/copy`。
- `proposal.md:250` 写 `prev.lon=179.99,current.lon=-179.99` 时 `wrappedDeltaLon`
  返回 `-0.02°` 但 `deltaLonM≈2m`，实际 `0.02°` 在赤道约 `2226m`。需随 P1 一起改。

## 3. 复核记录

- `openspec validate fix-lap-timing-campaign-c-tail-cleanup --strict`：PASS
- `rg -n "circularMedian" core/domain/src feature/test/src core/bluetooth/src`：
  当前命中 `GpsDataFilter.kt` 定义/调用与 `GpsDataFilterTest.kt` 测试名/注释
- 现有 `GpsDataFilter` 一致性判定：
  - `dt > 0.2s` 早退
  - `speed=50km/h` 容差 `5km/h`
  - `ratio > 3` 判 `isPositionAnomaly=true`

## 4. 核销建议

暂不进入 `/opsx:apply`。请先修：

1. A44 antimeridian fixture 的经度差 / dt / speed，使 v2 真的能 `isPositionAnomaly=false`
   且 v1 仍 hard-fail。
2. A43 测试方法名和注释 rename 改为必做，确保 §4.8 零残留可执行。

修订项共 2 条，未达到 5 条，不单独产出 `review-vN-patches.md`。

---

## 5. mini review：第二轮修订复核

- **日期**：2026-04-24
- **评审对象**：第二轮修订后的 C 三期三件套
- **结论**：✅ 通过，可进入 `/opsx:apply`

### 5.1 上轮 finding 关闭情况

| Finding | 复核结论 |
|---|---|
| P1-1 A44 antimeridian fixture 物理量级不成立 | 已修。fixture 改为 `prev.lon=179.9999975` / `current.lon=-179.9999975` / `dt=40ms` / `speed=50km/h`，v2 位移约 `0.5566m`、`vImpliedKmh≈50.1`，可判非异常；v1 仍算约 `40,075km` 假位移并 hard-fail |
| P2-1 circularMedian 零残留与可选 rename 冲突 | 已修。tasks §2.3 将 `GpsDataFilterTest.kt` 方法名和注释 rename 改为必做，并列出当前残留点；§4.8 零残留门槛可执行 |

### 5.2 复核记录

- `openspec validate fix-lap-timing-campaign-c-tail-cleanup --strict`：PASS
- 旧错误数值 grep：`179.99` / `2226` / `40_000 km` / `2m` / `0.002` 均无残留
- A44 数值链复核：
  - `wrappedDeltaLon(-179.9999975, 179.9999975) = 0.000005°`
  - `deltaLonM ≈ 0.000005 × 111320 = 0.5566m`
  - `vImpliedKmh ≈ 0.5566 / 0.04 × 3.6 = 50.1km/h`
  - 对 `speed=50km/h`、容差 `5km/h`，`ratio≈0.02`，不会触发 `isPositionAnomaly`
- R2 执行门槛：tasks 已明确测试名 / 注释 rename 必做，`grep -rn "circularMedian" core/domain/src feature/test/src core/bluetooth/src` 具备可达成路径

### 5.3 非阻塞提醒

- `proposal.md` 回归保护清单仍以 `GF09_bearingCrossZero_circularMedian` 作为"现有测试"示例出现一次，并说明 rename 为 `_circularMean_`。这是说明性旧名引用，不影响 tasks 的零残留代码门槛；实施时以 tasks §2.3 / §4.8 为准。

### 5.4 放行结论

`fix-lap-timing-campaign-c-tail-cleanup` 当前可放行实施。实施阶段按 tasks 执行 A36 / A43 / A44，完成后再提交 code review。
