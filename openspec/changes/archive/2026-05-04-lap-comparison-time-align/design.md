## Context

**Phase 1 整体编排**：本 round 是 Phase 1 第四个 round（W3 worktree 通道，与 W1/W2/W4 并行）。Phase 1 总目标是落地"单圈数据图表 + 多圈比较"两个 Tier 2 屏，本 round 承担多圈比较屏的核心算法——把多圈数据在 distance 维度重采样到统一网格，让游标拖到任一空间位置时，三圈在该位置的速度/时间能并列显示。

**为什么是 distance 而非 time 对齐**：

- 圈速的物理意义是 **"同一物理位置的速度差"**——例如 turn 1 入弯点 A 圈 80 km/h、B 圈 75 km/h、C 圈 78 km/h
- 时间对齐会让 A 圈在 turn 1 出现在 t=12s、B 圈在 t=13s、C 圈在 t=12.4s，无法直接位置比较
- 距离对齐让 turn 1 入弯点固定在所有圈的同一 distance bucket（如 distance=580m）→ cursor 拖到 580m 即可读三圈各自速度/时间

**当前 baseline**（Phase 0 已闭环）：

- `core/domain/.../model/TelemetryModels.kt` 现有 `TelemetrySample`（tsDeltaMs/lat/lon/speedKmh/bearingDeg/flags）+ `TelemetrySession`（含 sessionId/sessionType/startTs/endTs/binaryFilePath/lapCount/bestLapMs/topSpeedKmh/trackId/trackNameSnapshot）
- W1 round entry sketch §1 在此 baseline 之上引入 `LapTelemetry` + `LapTelemetrySample`（新增字段 `absoluteTsMs`、`elapsedMsInLap`、`accelerationG?`）—— accelerationG 标记为 "W1 不强制，W3 派生"（见 entry sketch line 43）
- 每圈 sample 数量不一致（同一赛道 60s 圈 vs 65s 圈 @ 25Hz 差 125 帧）→ 直接 index 对齐错位
- `lat / lon` 是 WGS-84 度数；distance 累计需要在每对相邻 sample 间用 haversine 或局部平面投影计算

**当前活跃 W1 worktree（lap-data-readers）依赖**：

- W3 消费 W1 在 entry sketch §1 已定稿的 `LapTelemetry` / `LapTelemetrySample` 类型
- 类型签名在 entry sketch 已锁；entry sketch 是 local-only md（看板 §1 排除规则），不进 git，因此 **MUST 把 entry sketch §1 字段集合在本 design D6 完整 inline 列出**，避免 W1 / W3 实施期漂移到不同签名

## Goals / Non-Goals

**Goals**：

- 提供 `LapAlignment.alignByDistance(laps, refIdx, step)` pure function，算法 **O(N + M log N)** 复杂度（N = 单圈 sample 数 ~1500，M = 网格点数 ~600；3 圈累计 ~24300 基础操作 + 1800 次插值 = sub-ms 主线程内完成），无 Android 依赖
- 输入多圈 `List<LapTelemetry>` 与参考圈 index 与距离步长 → 输出 `LapAlignmentResult` data class（含 `samplesPerLap` + `distanceStepMeters` + `refTotalDist` + `gridSize` + reverse-lookup helper `gridIndexFor(distance)`）
- 输出 `samplesPerLap` 长度 == `laps.size`，每个内部 list 长度 == 网格点数 == `floor(refLapDistance / step) + 1`
- 边界 case 完全确定性 + 由单测锁死返回行为（参考圈越界 / 距离步长 ≤ 0 / 单圈输入 / 圈内 sample 数 < 2 / 累计距离含重复值即车静止 / 参考圈累计距离 == 0）
- 6 cases 单测全绿（A 三圈正常 / B 单圈 / C 步长 / D refIdx 越界 / E 累计距离重复值 / F 比较圈样本退化 fallback），0 Android 依赖，可直接 `./gradlew :core:domain:testDebugUnitTest` 跑

**Non-Goals**：

- 不做 UI 集成（属于 Tier 2 多圈比较屏 round 的工作）
- 不做实时算法（cursor 拖动时不要求 < 16ms 响应——网格预计算后 reverse-lookup helper O(1) 查表，远小于一帧；调用方 MUST 在 lap selection 改变时重算 1 次，cursor 移动时只查表，不重调 `alignByDistance`）
- 不消费 / 不依赖 Repository / Android Context / Room / DataStore
- 不修改 W1 round 的类型契约（如 W1 调整签名，本 round 同步跟进，但不主动驱动）
- 不做轨迹平滑 / 异常点剔除（属于 W1 / GpsDataFilter 的责任，本 round 假定输入已是干净轨迹；累计距离重复值即车静止反例由 D5 + spec scenario 锁死）
- 不实现 GUI / Compose preview / mock 数据生成器（属于 W2 round）
- 不派生 `accelerationG`（entry sketch §1 line 43 标记为 "W3 派生"，但实际派生由后续 round 做；本 round 的 W3 仅做 alignment，accelerationG 在重采样时按 nullable null fallback 处理；详见 D2 + Open Questions）

## Decisions

### D1：距离累计算法 → 局部平面投影 vs Haversine vs 已有 distance 字段

**选定方案**：局部平面投影（local equirectangular），相邻 sample 间用 `Δx = (lon2 - lon1) * cosLat0 * π/180 * R`，`Δy = (lat2 - lat1) * π/180 * R`，距离 `Δd = sqrt(Δx² + Δy²)`，`R = 6378137 m`，`cosLat0 = cos(参考圈起点纬度 * π/180)`。

**Alternatives 考虑**：

- **Haversine**（球面准确公式）：3 圈 × ~1500 帧累计距离 = ~4500 次 sin/cos 调用 ≈ 1.5 ms；精度严格准确无近似误差
- **已有 distance 字段**：`LapTelemetrySample` entry sketch §1 未定义 distance 累计字段；如要消费需 W1 加字段并 backfill 历史数据 → 增 W3 → W1 反向依赖，违反"W3 不依赖 W1 实施"原则
- **真大圆距离 + 椭球修正**（Vincenty 等）：精度过剩，赛道范围 < 5 km，平面近似误差 < 0.1 m

**Rationale（修订）**：

- Haversine 真实成本 ~1.5 ms（4500 次 sin/cos），不是 30ms（之前估算时混淆了 N·M 与 N，已修正）；局部平面投影 ~45 μs（4500 次基础算术 + sqrt）
- 真实精度差异：在赛道横跨纬度 < 0.05° 范围内，平面近似累积误差 < 0.5 m（远小于 5m 步长精度）；Haversine 严格球面准确
- 性能差异（1.5 ms vs 45 μs）在 sub-ms 范围内对调用方无感知（cursor 拖动每秒 ~25 fps × 16ms 帧时间预算）
- 但局部平面投影**对实施风险更小**：少 1 次 sin/cos / 帧、少 1 次 cosLat0 / 全圈、少跨 lat 跳变风险（cosLat0 == 0 即赤道纬度时不退化为 0/0）
- **保留局部平面投影选择**：rationale 不基于 CPU 差异（已不显著），而基于实施稳定性 + 主线程边界确定性
- `cos(lat0)` 用参考圈起点纬度提前算 1 次，所有 sample 共用——避免 per-sample 开销；如未来跨城市赛道 > 5km 触发误差超阈值，由 follow-up `lap-alignment-vincenty-fallback` deferred memo 决定升级（见 R2 + tasks §10）

**形式约束**：lat0 用参考圈第一个 sample 的纬度；如果参考圈 sample 数 < 2 进入 D5 边界路径，不进入投影；R 用 WGS-84 长半轴 6378137.0 m（国际通用）。

### D2：重采样插值 → 线性 vs 三次样条 vs 最近邻

**选定方案**：线性插值。给定累计距离序列 `d_0 ≤ d_1 ≤ ... ≤ d_{n-1}` 与对应 sample 序列，目标距离 `d*` 落在 `[d_k, d_{k+1}]` 之间时，插值系数 `α = (d* - d_k) / (d_{k+1} - d_k)`，结果 sample 各字段按 D2 字段表分级处理。

**字段插值表**：

> **B3 修订（phase1-hardening-w2-w3-w4-mimo-debt round 同步）**：本表用数学符号 `s_k / s_{k+1}` 描述 normative；**实际生产代码 `LapAlignment.kt:179-202 interpolate(s0, s1, ...)` 函数变量名是 `s0 / s1`**；spec ↔ code mapping：`s_k → s0`，`s_{k+1} → s1`，`α → alpha`。grep gate 字面量 MUST 用 `s0 / s1` 与生产代码一致。

| 字段 | 处理方式 | 理由 |
|---|---|---|
| `speedKmh` | 线性插值（α）| 连续标量，物理意义：圈内速度连续 |
| `lat`、`lon` | 线性插值（α）| 连续标量 |
| `elapsedMsInLap` | 线性插值（α）+ `kotlin.math.round` 转 Long | 浮点精度 → Long 时显式 round 而非截断（避免下游显示 4.999s/5.000s 跳变）|
| `bearingDeg` | 最近邻（α < 0.5 取 s_k 否则 s_{k+1}）| 角度型字段，跨 0°/360° 边界线性插值会出错（350° + 10° 中点不是 180°）|
| `accelerationG`（nullable）| (a) 两端都非 null 走线性插值（α 权重）；(b1) 近端非 null（α<0.5 时近端=`s_k` 否则 `s_{k+1}`）取近端值；(b2) 近端 null 远端非 null 退化到远端值；(c) 两端都 null 返回 null | nullable + 物理意义连续；entry sketch §1 line 43 标 "W1 不强制，W3 派生"，初期生产数据全 null，spec 锁死 (b1)/(b2) 拆分为后续派生预留契约 |
| **`flags`（B3 新增，phase1-hardening 修订）**| **最近邻**（α < 0.5 取 s_k.flags 否则 s_{k+1}.flags）| W1 commit 3c2f2d9 追加 `flags: Int = 0` 字段；W3 mimo 实施期未消费此字段 → 重采样默认 0 哨兵 → v3 高频盲点 #16 实战首例。最近邻策略与 bearingDeg 一致；语义保持原 sample 标记不丢；如未来 W1 改 flags 为 bitmask 用法 → 触发新 round 修订重采样策略。**v3 #16 已修复 by phase1-hardening-w2-w3-w4-mimo-debt round（B2 / B4）；W4 binary writer 仍永久默认 0 deferred to Phase 2** |
| `absoluteTsMs`（派生）| `lapStartWallClock + round(elapsedMsInLap)` | 与原 sample 同源派生，保持壁钟时间一致性 |

**Alternatives 考虑**：

- **三次样条 / 二次贝塞尔**：在 25Hz 采样 + 5m 步长（车速 100 km/h 时约 0.18s = 4-5 帧之间）的密度下，二阶以上导数贡献小；引入额外参数（端点处理 / boundary condition）增加 review 面积，无显著精度收益
- **最近邻**：cursor 在 distance bucket 边界附近抖动时数值跳变，影响 UI 平滑感
- **保持原 sample 不重采样、直接按 distance bisect**：每圈输出长度不一致 → 与 multi-lap UI 期望"每圈在第 k 个 grid bucket 取同一字段"的契约矛盾

**Rationale**：

- 线性插值 1 次乘加，~5 ns/sample；3 圈 × 600 grid = 1800 次插值 = 9 μs
- 边界处理简单（首尾 clamp 到 sample[0] / sample[-1]）
- bearingDeg 走最近邻是已知 trade-off：UI 调用方 SHALL NOT 把 bearingDeg 直接绑定到平滑插值 UI 元素（如旋转 indicator）—— spec "字段插值" requirement 显式声明此约束（见 R3 mitigation 升级）
- speedKmh / lat / lon / elapsedMsInLap 都是连续标量，线性插值物理意义合理

**形式约束**：插值字段集合显式列出（见上表）；跨 360° 字段（bearingDeg）走最近邻；这一约束在 spec scenario "字段插值 SHALL 区分连续标量与角度型" 锁死。

### D3：网格生成策略 → 固定步长 vs 自适应

**选定方案**：固定步长。`distanceStepMeters` 由调用方传入，默认 `5.0`。网格点 = `0, step, 2*step, ..., floor(refDist / step) * step`，最后一个网格点 ≤ refDist（不超出参考圈最长距离）。网格点总数 `M = floor(refDist / step) + 1`。

**Alternatives 考虑**：

- **自适应步长**（弯道密、直线疏）：需要先识别弯道（curvature 阈值），增加复杂度；UI 端 cursor 拖动期望"等间距 distance bucket"——自适应破坏这个心智模型
- **每圈各自网格 + cross-lap 插值**：让每圈用自己的距离总长生成网格，比较时再二次插值——多一层重采样误差累积

**Rationale**：

- 5m 步长对车速 100 km/h（27.7 m/s）= 180ms 时间分辨率，远低于人眼对 cursor 拖动的感知阈值（~50ms）
- 用参考圈作为 distance baseline 让"参考圈第 k 个 grid 点 == 该圈实际位置 k*step"，比较圈的同一 grid 点投到与参考圈空间同位置（如果两圈走同一轨迹）
- 网格上界 = floor(refDist / step) → 比较圈如果比参考圈长，超出部分被截断（视为 cursor 不可达区）；如果比参考圈短，到达终点后 clamp 到最后一个 sample（属边界 case，见 D5）
- 与 D7（return type LapAlignmentResult）协同：调用方 reverse-lookup `gridIndexFor(D) = floor(D / step).coerceIn(0, M-1)`，O(1) 复杂度

**形式约束**：步长 `step ≤ 0` 进入 D5 边界路径返回 `LapAlignmentResult.EMPTY`；步长大于参考圈总距离时，输出每圈仅 1 个 grid 点（distance=0 处的 sample）

### D4：参考圈 index 越界 → 抛 vs 返回空 vs clamp

**选定方案**：返回 `LapAlignmentResult.EMPTY`，不抛异常，不 clamp。

**Alternatives 考虑**：

- **抛 IndexOutOfBoundsException**：调用方（多圈比较屏）需要 try/catch，UI 层污染；调用方传错 index 是 bug 但不应 crash app
- **clamp 到 [0, laps.size-1]**：silently 改语义，调用方误传 `-1` 想表示"无参考"时被改成 `laps[0]`，bug 隐蔽

**Rationale**：

- pure function 边界路径返回空 `LapAlignmentResult`（`samplesPerLap.isEmpty() == true`）是 Kotlin / Java 主流惯例（`List.getOrNull` 同 pattern）
- 单测 case D 锁死"refIdx < 0 || refIdx >= laps.size → EMPTY"行为
- 调用方（Tier 2 屏）拿到 empty result 时显示"未选定参考圈"占位，与"laps 为空"路径合并 UI 处理；调用方判空通过 `result.gridSize == 0` 即可

**形式约束**：spec scenarios + 测试 case D 锁死。

### D5：边界 case 处理矩阵（统一）

**关键修订**：所有"无法生成有效 grid"的边界 case 统一返回 `LapAlignmentResult.EMPTY`（即 `samplesPerLap = emptyList()`，`gridSize = 0`）；其它降级路径（比较圈 sample 不足）走 fallback 而非 empty。

| 输入条件 | 返回 | 单测 / spec 锁死位置 |
|---|---|---|
| `laps.isEmpty()` | `LapAlignmentResult.EMPTY` | spec scenario "输入 laps 为空"（反例）|
| `refIdx !in laps.indices`（含 < 0 / >= size）| `LapAlignmentResult.EMPTY` | case D / spec scenarios "参考圈索引越界" |
| `distanceStepMeters <= 0` | `LapAlignmentResult.EMPTY` | spec scenarios "距离步长非正"（反例）|
| `laps[refIdx].samples.size < 2`（无法累计任何距离）| `LapAlignmentResult.EMPTY` | spec scenario "参考圈样本数 < 2 退化"（**修订**：直接 empty 不再尝试单元素列表降级，避免外层长度与 lap.size 不一致风险） |
| 参考圈累计距离 == 0.0（所有 sample 同位置）| `LapAlignmentResult.EMPTY` | spec scenario "参考圈累计距离 == 0"（反例）|
| `laps.size == 1`（单圈输入）| `LapAlignmentResult` 含 1 元素 `samplesPerLap`，长度 = M | case B |
| 比较圈 `samples.size == 1`（仅 1 帧）| 该圈所有 grid 点输出 `samples[0]`（外层长度仍 == laps.size）| spec scenario "比较圈仅 1 个样本" |
| 比较圈 `samples.isEmpty()`（无样本）| 该圈所有 grid 点的 sample lat/lon/speedKmh/elapsedMsInLap 取自参考圈对应 grid 点（保留 grid 索引一致性）；**MUST 重新派生 `absoluteTsMs = laps[k].lapStartWallClock + 该 grid 点 elapsedMsInLap`**（防止跨时钟域污染，不是 copy 参考圈 absoluteTsMs）；`accelerationG` 强制 null（标记 fallback）；调用方 UI 层 SHALL 染色提示该圈 fallback | spec scenario "比较圈样本为空 fallback" |
| 累计距离含重复值（车静止 / Δd = 0）| 二分查找在重复区间返回最小 index 的 sample（最早 elapsedMsInLap）；α = 0 防 NaN（`d_k == d_{k+1}` 时直接取 s_k，不做除法）| case E / spec scenario "累计距离含重复值" |

**形式约束**：D5 决定每个边界 case 的返回；测试 case A/B/C/D/E 各覆盖一组；spec scenarios 全部锁死 normative。

### D6：依赖契约 → 占位 LapTelemetry vs 等 W1 合回（重写）

**选定方案**：本 round 在 `core/domain/.../model/LapTelemetry.kt` 新建占位类型（与 entry sketch §1 签名完全一致）。Rebase 期处理 conflict 是预期流程：后合回方在 worktree rebase 时 `git rm` 占位 LapTelemetry.kt + `git rebase --continue` + 重测。Push 顺序由 user 拍板。

**关键 caveat（修订）**：

- Kotlin 编译器**不允许**同 package 同名 data class 出现两次（"Conflicting declarations" 错误）——这是 P0 修订前 design rationale 中错误描述（"允许...则报错"自相矛盾）
- 因此 W1 / W3 worktree 分支各自 commit 占位 / 正式 LapTelemetry.kt 后，**git rebase 时必然在该文件 conflict**——这不是 bug，是预期信号
- "ff-only merge" 仅在 worktree → 主区方向（worktree 完成 rebase 后主区无新 commit），`git merge feature/lap-comparison-time-align --ff-only` 才能成立；**worktree → 主区 rebase 必然非 ff-only**

**Rebase 流程（详细）**：

```
# 在 worktree 内
git fetch origin
git rebase feature/track-tech-v2

# 若 W1 已合回主区 → 在 LapTelemetry.kt 上 conflict
# 选择保留主区版本（W1 正式版）：
git rm core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt
git rebase --continue

# 验证编译 + 测试
./gradlew :core:domain:compileDebugKotlin :core:domain:testDebugUnitTest

# 切回主区 ff-only merge（此时 worktree 已 rebase 至主区 head 之上）
git checkout feature/track-tech-v2
git merge feature/lap-comparison-time-align --ff-only
```

**Alternatives 考虑**：

- **A：等 W1 合回再开 W3**：违反"4 worktree 真正并行"目标；W1 估时与 W3 不同步，强串行损失并行收益
- **B：W3 内不引入 LapTelemetry，把签名改为 `List<List<LapTelemetrySample>>` 输入**：丢失 lap 维度的 metadata（trackId / sectorBoundaries），后续 Tier 2 屏需要重新组装；与 entry sketch §4 签名不一致；本 round 仍需 LapTelemetrySample 占位，节省有限
- **C（新增评估）：interface + typealias 兼容**：W3 在 usecase 内定义 `internal interface LapTelemetryAccess`，W1 实际类型实现该 interface——避免 conflict；但 W1 / W3 双方都要改，违反"W3 不主动驱动 W1"原则；且 entry sketch §1 已锁定 data class 形态而非 interface，改 interface 引入跨 round 协调成本

**Rationale**：

- 选 A 损失并行；选 B 改本 round 签名违反 entry sketch；选 C 引入跨 round 协调
- 选当前方案：占位类型 + rebase conflict 显式处理路径，配合 P0 修订后明确 caveat，是并行收益最大化与协调成本最小化的折中
- 字段集合 inline 列在 design 内部（见 entry sketch §1 引用）确保 W1 / W3 不漂移；如 W1 实施期改字段需 user 同步通知本 round session，触发本 round design 修订 + tasks §1 字段对照 STOP gate

**entry sketch §1 字段集合 inline（防漂移）**：

```kotlin
data class LapTelemetry(
    val sessionId: String,
    val lapIndex: Int,                    // 0-based
    val lapStartWallClock: Long,           // 真壁钟 ms
    val lapEndWallClock: Long,             // 真壁钟 ms
    val lapDurationMs: Long,               // == lapEnd - lapStart
    val samples: List<LapTelemetrySample>, // 按 absoluteTs 升序
    val sectorBoundaries: List<Long>,      // 各 sector 起点 absoluteTs
    val trackId: String?,
    val trackNameSnapshot: String?,
)

data class LapTelemetrySample(
    val absoluteTsMs: Long,
    val elapsedMsInLap: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val bearingDeg: Double?,               // nullable，跨 0°/360° 走最近邻
    val accelerationG: Double?,            // nullable，entry sketch 标 "W1 不强制，W3 派生"；初期生产数据全 null，nullable fallback 语义为后续派生预留
)
```

**形式约束**：tasks §1.1 起步先 inline 对照本 D6 字段集合 + entry sketch §1 原文 + W1 worktree 当前实施现状（若可见）；任意字段不一致 STOP 与 user 同步。tasks §7.3 显式列 rebase conflict 处理流程。

### D7：return type → 裸 List vs LapAlignmentResult data class（新增）

**选定方案**：返回 `LapAlignmentResult` data class，包含：

```kotlin
data class LapAlignmentResult(
    val samplesPerLap: List<List<LapTelemetrySample>>, // 外层长度 == 输入 laps.size，内层长度 == gridSize
    val distanceStepMeters: Double,                     // 网格步长（EMPTY 时 == 0.0）
    val refTotalDistMeters: Double,                     // 参考圈累计总距离（EMPTY 时 == 0.0）
    val gridSize: Int,                                  // == samplesPerLap[k].size for any k；EMPTY 时 == 0
    val referenceLapIndex: Int,                         // 用过的参考圈 index（边界 case 时 == -1）
) {
    init {
        // gridSize == 0 即 EMPTY 形态；非 EMPTY 时 step 必须 > 0
        require(gridSize == 0 || distanceStepMeters > 0.0) {
            "Non-EMPTY LapAlignmentResult requires distanceStepMeters > 0"
        }
        // 非 EMPTY 时 samplesPerLap 必须非空
        require(gridSize == 0 || samplesPerLap.isNotEmpty()) {
            "Non-EMPTY LapAlignmentResult requires non-empty samplesPerLap"
        }
    }

    /** 反查：cursor 物理 distance D 对应的 grid 索引（O(1) clamp 到 [0, gridSize-1]）；EMPTY 返回 -1 */
    fun gridIndexFor(distanceMeters: Double): Int = when {
        gridSize == 0 -> -1
        else -> (distanceMeters / distanceStepMeters).toInt().coerceIn(0, gridSize - 1)
    }

    /** grid 索引对应的 distance（== gridIndex * distanceStepMeters）；EMPTY 时 step==0 返回 0.0 */
    fun distanceAtGridIndex(gridIndex: Int): Double = gridIndex * distanceStepMeters

    companion object {
        val EMPTY = LapAlignmentResult(
            samplesPerLap = emptyList(),
            distanceStepMeters = 0.0,
            refTotalDistMeters = 0.0,
            gridSize = 0,
            referenceLapIndex = -1,
        )
    }
}
```

**Alternatives 考虑**：

- **裸 `List<List<LapTelemetrySample>>`**：调用方需要复刻 `gridIndex = floor(D / step)` 算法 + 步长 / 总距离的传递机制；未来步长策略变化时调用方失同步；典型 "scope 收紧不够"（见 P1-2 review）
- **三个独立返回值（List + step + totalDist）`**：Kotlin 函数无多返回值；用 Triple 失语义；data class 是 idiomatic 选择
- **Map<distanceBucket, sample>**：内存膨胀且有序性丢失，Tier 2 屏直接 indexed access 不便

**Rationale**：

- API 签名形态稳定 + reverse-lookup helper 内置，避免调用方复刻
- `LapAlignmentResult.EMPTY` 让边界 case 返回路径一致
- data class 自动 `equals` / `hashCode` 让单测 fixture 比较简单
- 调用方（Tier 2 多圈比较屏 round）直接：
  ```kotlin
  val result = LapAlignment.alignByDistance(laps, refIdx = 0, step = 5.0)
  // cursor 拖到物理 D = 580 m
  val gridIdx = result.gridIndexFor(580.0)
  // 三圈在该位置：
  val laneA = result.samplesPerLap[0][gridIdx]
  val laneB = result.samplesPerLap[1][gridIdx]
  ```

**形式约束**：spec ADDED Requirement "LapAlignmentResult SHALL 暴露 distance/grid 双向映射" 锁死 helper 行为；tasks §3.1 + §4.x 适配 data class return type。

## Risks / Trade-offs

- **[R1] 占位类型与 W1 实际类型签名漂移** → Mitigation：tasks §1.1 起步先 grep entry sketch §1 全文 + 字段逐项对照 + 与 D6 inline 字段集合对照三方一致；W1 session 如改签名 user 同步通知；rebase 期 conflict 是预期信号（删占位文件 + continue），不强行 force
- **[R2] 局部平面投影在大跨度赛道（> 5 km 边界框对角线）误差超 5m 步长** → Mitigation：spec scenario 锁死"赛道边界框对角线 < 5 km 内算法适用"；超出则 risks 透明声明，留 follow-up `lap-alignment-vincenty-fallback` deferred memo（Phase 1 收尾决定是否做；见 tasks §10）
- **[R3] 跨 360° bearing 字段最近邻 fallback 让 cursor 拖动时 bearing 数字跳变** → Mitigation：spec "字段插值" requirement 显式声明 "调用方 SHALL NOT 把 `bearingDeg` 直接绑定到平滑插值 UI 元素（如旋转 indicator）"；UI 端如果显示 bearing 在 multi-lap 比较语义上意义低，Tier 2 屏可不显示 bearing；如需显示，UI 层显式 wrap 到 max nearest neighbor 显示模式
- **[R4] 比较圈轨迹偏离参考圈轨迹（如 turn 切线不同）时，同一 distance bucket 不在同一物理位置** → Mitigation：本 round 不解决（属算法**固有限制**，与轨迹相似性绑定）；spec 段 risks 显式声明；Tier 2 多圈比较屏 round 会引入"轨迹偏差报警"（cursor 处比较圈与参考圈位置 > 阈值时染色提示，由 follow-up `lap-alignment-trajectory-divergence-warning` round 单独评估，不在本 round scope）
- **[R5] sample 距离累计因 GPS 微抖动产生伪累加（人眼感知不动但 GPS 漂移 0.5m）** → Mitigation：本 round 假定输入已干净；与 GpsDataFilter 输出一致即可；在原始 raw GPS 上跑会有累积误差（属 GpsDataFilter 责任范畴）；累计距离极端反例（车完全静止 N 帧 → Δd = 0）由 R7 + spec scenario "累计距离含重复值" 锁死语义
- **[R6] 调用方误用按帧重调 alignByDistance 导致 GC 压力** → Mitigation：proposal §性能 + KDoc 显式禁止；Tier 2 屏 round 实施期 grep 验证 ViewModel 仅在 lap selection 改变时 collect 一次；如未来发现 GC 压力，可加 result cache 或 incremental update（推到 follow-up）
- **[R7] 累计距离含重复值（车静止）二分查找返回 unstable + α = 0/0 = NaN** → Mitigation：tasks §3.5 实施 + spec scenario "累计距离含重复值" 锁死：(a) 二分查找返回区间内**最小 index 的 sample**（最早 elapsedMsInLap，避免帧选择不稳定）(b) `d_k == d_{k+1}` 时 α 直接 0（取 s_k，不做除法）；case E 单测覆盖

## Migration Plan

无 schema / 数据迁移。本 round 只新建文件。

**Worktree 流程**（按看板 §3，修订 D6 后）：

1. worktree `.worktrees/lap-comparison-time-align` 内开发
2. `./gradlew :core:domain:compileDebugKotlin :core:domain:testDebugUnitTest` 通过
3. 用户授权后 `git commit`（Conventional Commits）
4. `git fetch origin && git rebase feature/track-tech-v2`：**若 W1 已合回**则 LapTelemetry.kt 必然 conflict → `git rm` 占位 + `git rebase --continue`；**若 W1 未合回**则 rebase 干净
5. rebase 后再次跑测试
6. 切回主区 `git checkout feature/track-tech-v2 && git merge feature/lap-comparison-time-align --ff-only`（此步是 worktree → 主区 ff-only，与 D6 描述一致）
7. 主区编译确认
8. 更新看板 §5 W3 行状态 + 最近合回 commit
9. 提醒 user 触发 Codex review；review 通过后 user 拍板 push 顺序

**Rollback 策略**：

- 算法层面：本 round 是新增 capability，无现有 capability 被改；rollback = `git revert <commit>` 即可，不影响其他 round
- 占位类型层面：W1 后合回时如发现占位与正式签名不一致，rebase conflict 暴露 → user 拍板 fix（patch 占位 OR patch 正式），不强行 force-push

## Open Questions

### OQ1：accelerationG 字段在占位类型保留 vs 删除？

**当前 disposition**：保留（D6 字段集合已 inline 列出）。

**Rationale**：

- entry sketch §1 line 43 标 "W1 不强制，W3 派生"——意指 W1 实施时 accelerationG 全填 null，W3（本 round）或后续 round 派生
- 本 round 不派生加速度（仅做 alignment），但保留字段使签名与 entry sketch 一致，避免 W1 / W3 漂移
- spec "字段插值" requirement 锁死 nullable null fallback 语义为后续派生预留契约

**潜在风险**：W1 实施 review 期可能基于 "本 round 不派生即不需此字段" 的反方意见删除字段——此情景下本 round 占位与 W1 正式版字段集合不一致，rebase conflict 暴露后 user 拍板。

**触发 user 同步条件**：tasks §1.1 字段对照 STOP gate 发现 W1 entry sketch 与本 D6 字段集合不一致，或 W1 worktree commit 已删除 accelerationG。

### OQ2：调用方"按帧重调 vs 一次预算"模式约束放在 KDoc 还是 spec？

**当前 disposition**：spec 内 "Pure function" requirement 强约束 + KDoc inline 警告。

**Rationale**：spec 是 normative source；KDoc 是开发者第一接触面；双层约束。Tier 2 屏 round 实施期 review grep 验证 ViewModel 仅在 lap selection 改变时调用一次。

（无其它未决问题；所有 D1-D7 决策已闭环；R1-R7 已 mitigate；边界 case D5 已锁死。）
