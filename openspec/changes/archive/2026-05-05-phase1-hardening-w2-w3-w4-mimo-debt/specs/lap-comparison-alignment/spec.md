## MODIFIED Requirements

> **L1 R2 P1-R2-1 全局 caveat（适用本 spec 所有字段）**：本 spec 的 normative 描述（字段插值表 + scenario 主体）用数学符号 `s_k / s_{k+1}` 描述策略；**实际生产代码 `core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt:179-202` `interpolate(s0, s1, alpha, lapStartWallClock)` 函数变量名是 `s0 / s1`**。所有反例 scenario 中的 **grep gate 字面量 MUST 统一用 `s0 / s1`**（与生产代码一致）。本 spec 的 spec ↔ code mapping：`s_k → s0`，`s_{k+1} → s1`，`α → alpha`。该 caveat 同时适用于 W3 archive design.md Decision 2 + 字段插值表段。

### Requirement: 字段插值 SHALL 区分连续标量、角度型、nullable

系统 SHALL 对网格点 `d* ∈ [d_k, d_{k+1}]` 用以下规则计算重采样 sample 各字段（见上方 global caveat：normative 用数学符号，grep gate 字面量用 s0/s1）：

| 字段 | 插值规则 |
|---|---|
| `speedKmh` / `lat` / `lon` | 线性插值，`α = (d* - d_k) / (d_{k+1} - d_k)`，结果 = `s_k.f * (1-α) + s_{k+1}.f * α` |
| `elapsedMsInLap` | 线性插值后用 `kotlin.math.round` 转 Long（**禁止**截断或 floor，避免 4.999s/5.000s 显示跳变）|
| `bearingDeg` | 最近邻：`if (alpha < 0.5) s_k.bearingDeg else s_{k+1}.bearingDeg`（跨 0°/360° 边界线性插值会出错）|
| `accelerationG` | (a) 两端都非 null → 线性插值（α 权重）；(b1) 近端非 null（`α<0.5` 时近端 = `s_k`，否则近端 = `s_{k+1}`）→ 取近端值；(b2) 近端 null + 远端非 null → 退化到远端非 null 值；(c) 两端都 null → null |
| **`flags`（B2/B3 新增）** | **最近邻**：`if (alpha < 0.5) s_k.flags else s_{k+1}.flags`（与 bearingDeg 最近邻策略一致；`flags: Int = 0` 由 W1 round 落地 commit `3c2f2d9` 追加，本 round 修复 v3 高频盲点 #16 实战首例） |
| `absoluteTsMs`（派生）| `lapStartWallClock + round(elapsedMsInLap)`（与原 sample 同源，保持壁钟时间一致性）|

**flags 字段重采样 rationale**：W1 当前 `flags: Int = 0` 仅作 placeholder（默认 0 表示"无标记"），还没有 bitmask 语义。最近邻策略与 R6 现有 bearingDeg 最近邻策略一致；语义保持原 sample 标记不丢；如果将来 W1 改 flags 为 bitmask 用法 → 触发新 round 修订重采样策略（届时再走 OpenSpec 流程）。

**禁止**默认 0 哨兵值（v3 高频盲点 #6 + #16）：`SampleClass(flags = 0)` 在 UI 层若用 `flags != 0` 做"是否手动标记"判断会全部错认 — 必须按最近邻取自源 sample。

边界处理（**L1 R1 P1-1 加严**：当前 LapAlignment.kt:155-156 / 160-164 实际是 `return samples[0]` / `return samples[n - 1]` / `return samples[kMin]` 直接复用对象引用 → 天然保留 flags；本 round 加 grep gate 反例锁防止未来重构破坏）：

- `d* < d_0`：直接返回 `samples[0]`（**对象引用**，不是 .copy()；clamp 到首样本，所有字段保持原值，含 flags）
- `d* > d_{n-1}`：直接返回 `samples[n - 1]`（**对象引用**，不是 .copy()；clamp 到末样本，含 flags）
- `d* == d_k`（精确命中）：直接返回 `samples[k]`（**对象引用**，不是 .copy()；含 flags）
- 未来重构若把 `return samples[i]` 改为 `samples[i].copy(...)` 形式 → 任何字段重写若漏掉 `flags = it.flags` MUST 触发 spec 反例 scenario fail（本 round 加 grep gate 锁两条 clamp 边界 + 精确命中分支）

调用方约束（不变）：

- 调用方 SHALL NOT 把 `bearingDeg` 直接绑定到平滑插值 UI 元素（如旋转 indicator 动画）；如需 cursor 拖动平滑显示 bearing，UI 层 MUST 显式处理跨 0°/360° 拼接。

#### Scenario: 连续标量线性插值
- **WHEN** `d_k=100m`，`d_{k+1}=110m`，`s_k.speedKmh=80`，`s_{k+1}.speedKmh=90`，目标 `d*=105m`
- **THEN** 重采样 `speedKmh = 85.0`（α=0.5 的线性插值结果）

#### Scenario: elapsedMsInLap 浮点 round
- **WHEN** `s_k.elapsedMsInLap=1000`，`s_{k+1}.elapsedMsInLap=1001`，α=0.6
- **THEN** 重采样 `elapsedMsInLap = round(1000 + 0.6) = 1001L`（**禁止** `1000L` 截断）

#### Scenario: 角度型字段最近邻 fallback
- **WHEN** `s_k.bearingDeg=350`，`s_{k+1}.bearingDeg=10`，α=0.4
- **THEN** 重采样 `bearingDeg = 350`（α < 0.5 取左侧最近邻），**禁止**返回 180.0（线性插值错误结果）

#### Scenario: 角度型字段跨 360° 边界 (D2 加严)
- **WHEN** `s_k.bearingDeg=359f`（北向 359 度），`s_{k+1}.bearingDeg=1f`（北向 1 度，跨过 0/360 边界），α=0.7（近端 = `s_{k+1}`）
- **THEN** 重采样 `bearingDeg == 1f`（α >= 0.5 取右侧最近邻 = 1.0），**禁止**返回 180f（如果代码用 `(s_k.bearingDeg + s_{k+1}.bearingDeg) / 2` = `(359 + 1) / 2 = 180` 错误平均到反向）；防 W3 review-l2-opus-a.md P1-2 反例强度不足

#### Scenario: nullable 字段近端 null 退化到远端
- **WHEN** `s_k.accelerationG = 0.5`，`s_{k+1}.accelerationG = null`，α=0.7（近端 = `s_{k+1}` null，远端 = `s_k` 非 null）
- **THEN** 重采样 `accelerationG = 0.5`（近端 null → 退化到远端非 null）

#### Scenario: nullable 字段近端非 null 取近端
- **WHEN** `s_k.accelerationG = 0.3`，`s_{k+1}.accelerationG = 0.8`，α=0.4（近端 = `s_k`，两端都非 null）
- **THEN** 重采样 `accelerationG = 0.3 * 0.6 + 0.8 * 0.4 = 0.5`（两端都非 null 走线性插值，不走 fallback 路径）

#### Scenario: nullable 字段两端都 null
- **WHEN** `s_k.accelerationG = null`，`s_{k+1}.accelerationG = null`，α=0.4
- **THEN** 重采样 `accelerationG = null`（W1 重建生产数据当前全 null，此 scenario 锁死该路径不抛 NPE）

#### Scenario: gridIndexFor 浮点边界 deterministic (D3 加严，apply 期 spec drift 修订)
- **WHEN** grid step 5m，调用 `gridIndexFor` 在 step 整数倍边界附近的浮点输入（100.0000001m vs 100.0m vs 99.9999999m）
- **THEN** 行为分两类：
  - (a) **同侧 deterministic**：100.0 与 100.0+1e-9 落同 step bucket [100, 105)，`gridIndexFor` 返回都是 20（同输入 → 同输出）
  - (b) **跨 step 边界 truncation expected**：99.999... 落 bucket [95, 100) → 返回 19；100.0+1e-9 落 bucket [100, 105) → 返回 20。生产代码 `(distanceMeters / distanceStepMeters).toInt()` 用 truncation（**math.floor 而非 round**），跨整数倍边界 ±1e-9 误差**预期返回不同 bucket** — 这是 deterministic 行为（不是 nondeterministic 摇摆），未来重构改 round / ceil 会破坏此行为
  - (c) 同输入幂等 deterministic：`gridIndexFor(105.0)` 多次调用返回值相同（pure function 语义）

  **注**：D3 原 spec 描述"边界 ±1e-9 三种情况 deterministic 一致"是错（apply 期 caseI 测试 fail 暴露），实际 truncation 跨边界**预期不一致**。本修订以"同侧 deterministic + 跨边界 expected"两个分组锁实际行为，避免未来重构破坏。

#### Scenario: flags 最近邻 — α<0.5 取左侧 (B4 case G)
- **WHEN** `s_k.flags = 1`，`s_{k+1}.flags = 0`，α=0.3（近端 = `s_k`）
- **THEN** 重采样 `flags = 1`（最近邻取左侧），**MUST NOT** 返回 0（默认值哨兵）

#### Scenario: flags 最近邻 — α≥0.5 取右侧 (B4 case G)
- **WHEN** `s_k.flags = 1`，`s_{k+1}.flags = 0`，α=0.6（近端 = `s_{k+1}`）
- **THEN** 重采样 `flags = 0`（最近邻取右侧 sample 实际值）；**MUST NOT** 因为 0 看似默认值就错误回 fallback 到 s_k.flags

#### Scenario: flags 边界 — d* 落入重复距离区间取最小 index (B4 case G)
- **WHEN** 参考圈含 100 帧静止 sample（accumulated distance 重复，索引 [100, 199]），sample[100].flags = 0，sample[150].flags = 1（中段被 manual mark）；grid 点 `d*` 落入该重复值区间内部
- **THEN** 重采样 `flags == sample[100].flags == 0`（取重复区间最小 index 的 sample 的 flags，与 R7"累计距离含重复值 SHALL 不返回 NaN"约束一致），**MUST NOT** 取 sample[150].flags 或随机帧

#### Scenario: 反例 — flags 默认 0 哨兵必须 fail (B2/B4 防回退，L1 R1 P0-3 锚点修订)
- **WHEN** 实施时 `LapAlignment.interpolate` 内重采样 sample 的 flags 字段直接写 `flags = 0` 或 `flags = LapTelemetrySample.DEFAULT_FLAGS` 等默认值（未通过最近邻取自源 sample）
- **THEN** 测试 case G 断言「mock laps with flags=1 on certain samples，重采样后 grid sample.flags == 1（最近邻取自源）」MUST fail；同时 grep gate 扫 `core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt` MUST 双锚点验证：
  - **锚点 1**：Regex `flags\s*=\s*if\s*\(\s*alpha\s*<\s*0\.5\s*\)\s*s0\.flags\s*else\s*s1\.flags` 命中数 = 1（与生产代码变量名 `s0/s1` 一致 — 参 LapAlignment.kt:179-202 interpolate 函数签名 `s0: LapTelemetrySample, s1: LapTelemetrySample`；命中 0 次 = 实施未落地 fail）
  - **锚点 2**：Regex `flags\s*=\s*0` 命中数 = 1（仅 `resampleByGridFallback` 内 empty 圈 fallback 路径；命中 ≥ 2 次 = interpolate 路径误用默认 0 fail）
  - 锁双锚点而非"等价三元表达式 + flags"宽松规则（防止 `flags = 0 + if (...)` 等回退路径开后门）

#### Scenario: 反例 — clamp 路径 flags 保留 (L1 R1 P1-1 修订，B2 加严)
- **WHEN** 测试 grid 点 `d* < d_0` 触发 clamp 到 samples[0]，源 sample `samples[0].flags == 1`
- **THEN** 重采样 sample.flags == 1（直接 return samples[0] 含原 flags；当前 LapAlignment.kt:155-156 实际是 `return samples[0]` 直接复用对象引用 → 天然保留 flags）；**反例**：未来重构若把 `return samples[0]` 改为 `return samples[0].copy(flags = 0)` 或 `return samples[0].copy(absoluteTsMs = ...)` 等可能丢 flags 的形态 → 测试断言 fail；同时 grep gate 扫 LapAlignment.kt findSampleAtDistance MUST 在 clamp 边界两处出现 `return samples[0]` / `return samples[n - 1]` 字面量（恰好 2 次；命中 `samples[0].copy(flags = 0)` → fail）

#### Scenario: 反例 — 精确命中重复距离区间起点 flags 保留 (L1 R1 P1-1 修订，B2 加严)
- **WHEN** 测试 grid 点 `d*` 精确命中累计距离重复值区间起点（如索引 [100..199] 累计距离全相同，`samples[100].flags == 1`），二分查找返回 idx==100
- **THEN** 重采样 sample == samples[100]（直接 return 原 sample 引用；含 flags=1）；**反例**：未来重构若把精确命中分支改为 `return samples[idx].copy(flags = 0)` → 测试 fail；同时 LapAlignment.kt 精确命中分支（line 160-164 附近 `if (idx >= 0) return samples[kMin]`）MUST 不出现 `samples[kMin].copy(flags = 0)` 字面量

---

### Requirement: 比较圈样本数过少 SHALL 退化处理

系统 SHALL 在比较圈（非参考圈）`samples.size < 2` 时降级处理：

- **如果 `samples.size == 1`**：该圈所有 grid 点输出 `samples[0]`（直接复制单帧 sample，所有字段含 `absoluteTsMs` / `accelerationG` / **`flags`** 保留原值）
- **如果 `samples.isEmpty()`**：该圈所有 grid 点的 sample lat/lon/speedKmh/elapsedMsInLap 取自参考圈对应 grid 点（保留 grid 索引一致性），但 SHALL：
  - **重新派生 `absoluteTsMs = laps[k].lapStartWallClock + 该 grid 点 elapsedMsInLap`**（防止跨时钟域污染——若直接 copy 参考圈 absoluteTsMs，UI 层取 `samplesPerLap[k][gridIdx].absoluteTsMs - laps[k].lapStartWallClock` 派生显示用 elapsedMsInLap 时算出错误值）
  - **`accelerationG` 强制 null**（与 W1 生产数据 accelerationG 全 null 一致，标记 fallback；UI 层 SHALL 染色或标注此圈数据 fallback）
  - **`flags` 强制 0**（empty 圈 fallback 路径无源 sample 可参照；与 W1 默认值一致；调用方 SHALL 通过 `accelerationG == null` 判断 fallback 状态，**MUST NOT** 用 flags 区分 fallback 圈）

**死参数清理（D1 修订）**：`LapAlignment` 内 `private fun resampleByGridFallback` 的 `fallbackRefSamples: List<LapTelemetrySample>` 参数为死参数（实际未使用），MUST 删除该参数 + 删除函数注解 `@Suppress("UNUSED_PARAMETER")`（v3 高频盲点 #15 + 卸责借口反对款）。

降级处理保证 `samplesPerLap.size == laps.size`，调用方 UI 层不需特判空圈。

#### Scenario: 比较圈仅 1 个样本（含 flags 保留）
- **WHEN** `laps[1].samples.size == 1`，`laps[1].samples[0].flags == 1`，参考圈正常重采样输出 100 个 grid 点
- **THEN** `samplesPerLap[1].size == 100`，每个元素 == `laps[1].samples[0]`（直接复制，包括 `absoluteTsMs`、`accelerationG`、**`flags == 1`** 都保留原值）

#### Scenario: 比较圈样本为空走参考圈 fallback 重新派生 absoluteTsMs（B 类 flags 强制 0）
- **WHEN** `laps[2].samples.isEmpty()`，`laps[2].lapStartWallClock == 1000000L`，参考圈 grid 点 100 个，参考圈 grid k=10 处 sample 的 `elapsedMsInLap == 500L`，参考圈 grid k=10 处 sample 的 `absoluteTsMs == 2000500L`（参考圈 lapStartWallClock 是 2000000L），参考圈 grid k=10 处 sample 的 `flags == 1`
- **THEN**：
  - `samplesPerLap[2].size == 100`（保证非空 list）
  - `samplesPerLap[2][10].lat == 参考圈 grid 10 处的 lat`（同位置）
  - `samplesPerLap[2][10].lon == 参考圈 grid 10 处的 lon`
  - `samplesPerLap[2][10].speedKmh == 参考圈 grid 10 处的 speedKmh`
  - `samplesPerLap[2][10].elapsedMsInLap == 500L`（保留参考圈 elapsedMsInLap）
  - `samplesPerLap[2][10].absoluteTsMs == 1000500L`（**MUST** 等于 `laps[2].lapStartWallClock + 500L = 1000500L`，不是参考圈的 2000500L）
  - `samplesPerLap[2][10].accelerationG == null`（强制 null 标记 fallback）
  - **`samplesPerLap[2][10].flags == 0`**（empty 圈 fallback 强制 0；MUST NOT copy 参考圈 flags=1）

#### Scenario: 反例 — 死参数 fallbackRefSamples 未删除必须 fail（D1 防回退）
- **WHEN** 实施后 `LapAlignment.kt` 仍保留 `private fun resampleByGridFallback(... fallbackRefSamples: List<LapTelemetrySample>, ...)` 参数 + `@Suppress("UNUSED_PARAMETER")` 注解
- **THEN** GrepGateTest 扫 `core/domain/.../usecase/LapAlignment.kt` MUST 0 命中 `fallbackRefSamples` 字面量 + 0 命中 `@Suppress("UNUSED_PARAMETER")` 字面量；命中 → fail
