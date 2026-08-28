## ADDED Requirements

### Requirement: 多圈按距离重采样到 LapAlignmentResult

系统 SHALL 提供 `LapAlignment.alignByDistance(laps, referenceLapIndex, distanceStepMeters)` pure function，把多圈 telemetry 按累计距离重采样到由参考圈定义的距离等差网格上，输出 `LapAlignmentResult` data class，所有圈在该网格上的样本序列长度一致。

函数签名：
```
object LapAlignment {
    fun alignByDistance(
        laps: List<LapTelemetry>,
        referenceLapIndex: Int,
        distanceStepMeters: Double = 5.0,
    ): LapAlignmentResult
}

data class LapAlignmentResult(
    val samplesPerLap: List<List<LapTelemetrySample>>,
    val distanceStepMeters: Double,
    val refTotalDistMeters: Double,
    val gridSize: Int,
    val referenceLapIndex: Int,
)
```

输出契约：

- `samplesPerLap` 外层长度 == `laps.size`（每圈对应一个内部列表，顺序与 `laps` 输入顺序一致）；`result == LapAlignmentResult.EMPTY` 时 `samplesPerLap.isEmpty() == true`
- 每个内部列表长度 == `gridSize` == `floor(refTotalDistMeters / distanceStepMeters) + 1`，所有圈一致
- 内部第 `k` 个元素 == 该圈在累计距离 `k * distanceStepMeters` 处的重采样样本
- `referenceLapIndex` == 入参 `referenceLapIndex`（合法时）或 `-1`（边界 case 返回 EMPTY 时）

#### Scenario: 三圈不同 pace 正常重采样（正例）
- **WHEN** 输入 3 圈 telemetry，每圈轨迹相似（同一赛道）但耗时 60s/65s/62s（sample 数 1500/1625/1550 @ 25Hz），refIdx=0，step=5m，参考圈累计距离 3000m
- **THEN** 返回 `LapAlignmentResult(gridSize = floor(3000/5)+1 = 601, refTotalDistMeters ≈ 3000, distanceStepMeters = 5.0, referenceLapIndex = 0)`，`samplesPerLap.size == 3`，每个内部列表长度均为 601，且内部第 100 个元素位于参考圈累计距离 500m 处的重采样样本，比较圈在同一 grid 索引位置取自该圈累计距离 500m 处的样本

#### Scenario: 单圈输入返回单元素 samplesPerLap
- **WHEN** `laps.size == 1`，refIdx=0，step=5m，参考圈累计距离 2000m
- **THEN** 返回 `gridSize = floor(2000/5)+1 = 401`，`samplesPerLap.size == 1`，内层长度 401，内容为参考圈在距离网格上的重采样

#### Scenario: 距离步长过大返回单网格点
- **WHEN** 参考圈累计距离 1500m，`distanceStepMeters = 3000`
- **THEN** `gridSize = floor(1500/3000)+1 = 1`，每圈 `samplesPerLap[k]` 仅 1 元素列表（distance=0 处的样本）

#### Scenario: 两圈完全相同轨迹但 sample 时间戳不同（正例）
- **WHEN** 输入 2 圈 telemetry，lat/lon 序列严格相同（800 帧每帧位置一致），但 elapsedMsInLap 序列不同（lap0：[0, 40, 80, ..., 31960]，lap1：[0, 50, 100, ..., 39950]）
- **THEN** `samplesPerLap[0][k].lat == samplesPerLap[1][k].lat` for all `k in 0 until gridSize`（位置相同），但 `samplesPerLap[0][k].elapsedMsInLap < samplesPerLap[1][k].elapsedMsInLap` for `k > 0`（时间不同）

#### Scenario: 输入 laps 为空返回 EMPTY（反例）
- **WHEN** `laps.isEmpty()`
- **THEN** 返回 `LapAlignmentResult.EMPTY`（`gridSize == 0`，`samplesPerLap.isEmpty()`，`referenceLapIndex == -1`），**禁止**抛异常或返回包含 null 的列表

### Requirement: 参考圈索引越界 SHALL 返回 EMPTY

系统 SHALL 对参考圈索引越界的输入返回 `LapAlignmentResult.EMPTY`，不抛异常，不 clamp 到合法范围；调用方负责在 UI 层判空（通过 `result.gridSize == 0`）显示占位。

#### Scenario: refIdx 为负
- **WHEN** `referenceLapIndex < 0` 且 `laps.isNotEmpty()`
- **THEN** 返回 `LapAlignmentResult.EMPTY`

#### Scenario: refIdx 超过 laps 末尾
- **WHEN** `referenceLapIndex >= laps.size`
- **THEN** 返回 `LapAlignmentResult.EMPTY`

### Requirement: 距离步长非正 SHALL 返回 EMPTY

系统 SHALL 对 `distanceStepMeters <= 0` 返回 `LapAlignmentResult.EMPTY`，不抛异常，不取绝对值，不应用默认值替换。

#### Scenario: 步长为零（反例）
- **WHEN** `distanceStepMeters == 0.0`
- **THEN** 返回 `LapAlignmentResult.EMPTY`

#### Scenario: 步长为负（反例）
- **WHEN** `distanceStepMeters < 0`
- **THEN** 返回 `LapAlignmentResult.EMPTY`

### Requirement: 参考圈样本不足 SHALL 返回 EMPTY

系统 SHALL 在参考圈无法生成有效 grid 时（参考圈样本数 < 2，或参考圈累计距离 == 0.0）返回 `LapAlignmentResult.EMPTY`，**禁止**降级为单元素列表（避免外层长度 != laps.size 的契约破坏）。

#### Scenario: 参考圈样本数 < 2
- **WHEN** `laps[referenceLapIndex].samples.size < 2`（无法计算任何相邻距离增量）
- **THEN** 返回 `LapAlignmentResult.EMPTY`

#### Scenario: 参考圈累计距离 == 0（所有 sample 同位置，反例）
- **WHEN** 参考圈所有 sample 的 lat/lon 严格相同，累计距离总和 = 0.0
- **THEN** 返回 `LapAlignmentResult.EMPTY`，**禁止**返回非空 result（步长大于 0 时无法生成有效 grid）

### Requirement: 距离累计 SHALL 用局部平面投影

系统 SHALL 用局部 equirectangular 投影计算相邻 sample 间的距离增量：

- `R = 6378137.0` m（WGS-84 长半轴）
- `lat0 = laps[referenceLapIndex].samples[0].lat`（参考圈起点纬度）
- `cosLat0 = cos(lat0 * PI / 180)`（提前算 1 次，所有 sample 共用）
- 对相邻 sample `(s_i, s_{i+1})`：
  - `Δx = (s_{i+1}.lon - s_i.lon) * cosLat0 * PI / 180 * R`
  - `Δy = (s_{i+1}.lat - s_i.lat) * PI / 180 * R`
  - `Δd = sqrt(Δx² + Δy²)`

累计距离序列 `d_i = Σ_{j<i} Δd_j`，`d_0 = 0`。

#### Scenario: 局部平面投影累计距离精度
- **WHEN** 参考圈两 sample 经纬度差 `(Δlat=0.0001°, Δlon=0.0001°)`，`lat0=31.0°`
- **THEN** 累计距离增量 `Δd ≈ sqrt((0.0001*cos(31°)*111320)² + (0.0001*111320)²) ≈ 14.5m`，与 haversine 公式在赛道范围 < 5km 内偏差 < 0.5m

#### Scenario: 赛道范围超出 5km 适用边界（反例）
- **WHEN** 输入 telemetry 边界框对角线 > 5km（如跨城市赛道 + 极端纬度）
- **THEN** 算法仍执行（不抛异常），但累积误差可能 > 5m 步长精度；调用方 SHALL 在文档中标注此限制；follow-up `lap-alignment-vincenty-fallback` deferred memo 决定是否升级到 Vincenty 公式

### Requirement: 字段插值 SHALL 区分连续标量、角度型、nullable

> **B3 修订（phase1-hardening-w2-w3-w4-mimo-debt round 同步）**：本 spec 的 normative 描述（字段插值表 + scenario）用数学符号 `s_k / s_{k+1}`；**实际生产代码 `LapAlignment.kt:179-202 interpolate(s0, s1, ...)` 函数变量名是 `s0 / s1`**；spec ↔ code mapping：`s_k → s0`，`s_{k+1} → s1`，`α → alpha`。所有反例 scenario 中的 grep gate 字面量 MUST 用 `s0 / s1`。同步本 caveat 适用于 design.md D2 字段插值表。

系统 SHALL 对网格点 `d* ∈ [d_k, d_{k+1}]` 用以下规则计算重采样 sample 各字段：

| 字段 | 插值规则 |
|---|---|
| `speedKmh` / `lat` / `lon` | 线性插值，`α = (d* - d_k) / (d_{k+1} - d_k)`，结果 = `s_k.f * (1-α) + s_{k+1}.f * α` |
| `elapsedMsInLap` | 线性插值后用 `kotlin.math.round` 转 Long（**禁止**截断或 floor，避免 4.999s/5.000s 显示跳变）|
| `bearingDeg` | 最近邻：`if (alpha < 0.5) s_k.bearingDeg else s_{k+1}.bearingDeg`（跨 0°/360° 边界线性插值会出错）|
| `accelerationG` | (a) 两端都非 null → 线性插值（α 权重）；(b1) 近端非 null（`α<0.5` 时近端 = `s_k`，否则近端 = `s_{k+1}`）→ 取近端值；(b2) 近端 null + 远端非 null → 退化到远端非 null 值；(c) 两端都 null → null |
| **`flags`（B3 新增，phase1-hardening 修订）**| **最近邻**：`if (alpha < 0.5) s_k.flags else s_{k+1}.flags`（与 bearingDeg 最近邻策略一致；W1 commit 3c2f2d9 追加 `flags: Int = 0` 字段；mimo 实施期未消费 → v3 高频盲点 #16 实战首例。**已修复 by phase1-hardening round B2/B4**；W4 binary writer 永久默认 0 deferred to Phase 2）|
| `absoluteTsMs`（派生）| `lapStartWallClock + round(elapsedMsInLap)`（与原 sample 同源，保持壁钟时间一致性）|

边界处理：

- `d* < d_0`：返回 `samples[0]` 的复制（clamp 到首样本，所有字段保持原值）
- `d* > d_{n-1}`：返回 `samples[n-1]` 的复制（clamp 到末样本）

调用方约束：

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

#### Scenario: nullable 字段近端 null 退化到远端
- **WHEN** `s_k.accelerationG = 0.5`，`s_{k+1}.accelerationG = null`，α=0.7（近端 = `s_{k+1}` null，远端 = `s_k` 非 null）
- **THEN** 重采样 `accelerationG = 0.5`（近端 null → 退化到远端非 null）

#### Scenario: nullable 字段近端非 null 取近端
- **WHEN** `s_k.accelerationG = 0.3`，`s_{k+1}.accelerationG = 0.8`，α=0.4（近端 = `s_k`，两端都非 null）
- **THEN** 重采样 `accelerationG = 0.3 * 0.6 + 0.8 * 0.4 = 0.5`（两端都非 null 走线性插值，不走 fallback 路径）

#### Scenario: nullable 字段两端都 null
- **WHEN** `s_k.accelerationG = null`，`s_{k+1}.accelerationG = null`，α=0.4
- **THEN** 重采样 `accelerationG = null`（W1 重建生产数据当前全 null，此 scenario 锁死该路径不抛 NPE）

### Requirement: 累计距离含重复值 SHALL 不返回 NaN

系统 SHALL 在累计距离序列含重复值（车静止 / 同一物理位置多帧）时仍返回确定性结果：

- 二分查找在重复值区间命中时 SHALL 返回**最小 index 的 sample**（最早 elapsedMsInLap 的帧），而非 unstable index
- 当 `d_k == d_{k+1}` 时（区间长度 0）α 取 0（取 `s_k`）；**禁止**做除法 `(d* - d_k) / (d_{k+1} - d_k)` 触发 NaN
- 若 grid 点 `d*` 落入重复值区间内部（不在端点）→ 返回 `s_k`（最小 index）

#### Scenario: 累计距离含重复值（车静止反例）
- **WHEN** 参考圈 sample 序列含 100 帧静止 sample（lat/lon 严格相同，索引 [100, 199]，elapsedMsInLap 单调递增 [4000, 4040, 4080, ..., 7960]，对应累计距离 `d_100 == d_101 == ... == d_199`），grid 点 `d*` 落入该重复值区间
- **THEN** 重采样 sample 的 `elapsedMsInLap == 4000L`（取重复区间最小 index 的 sample），**禁止**返回 NaN 或随机帧 elapsedMsInLap（如 4040L、5520L、7960L 中任一非 4000L 值）

### Requirement: 比较圈样本数过少 SHALL 退化处理

系统 SHALL 在比较圈（非参考圈）`samples.size < 2` 时降级处理：

- **如果 `samples.size == 1`**：该圈所有 grid 点输出 `samples[0]`（直接复制单帧 sample，所有字段含 `absoluteTsMs` / `accelerationG` 保留原值）
- **如果 `samples.isEmpty()`**：该圈所有 grid 点的 sample lat/lon/speedKmh/elapsedMsInLap 取自参考圈对应 grid 点（保留 grid 索引一致性），但 SHALL：
  - **重新派生 `absoluteTsMs = laps[k].lapStartWallClock + 该 grid 点 elapsedMsInLap`**（防止跨时钟域污染——若直接 copy 参考圈 absoluteTsMs，UI 层取 `samplesPerLap[k][gridIdx].absoluteTsMs - laps[k].lapStartWallClock` 派生显示用 elapsedMsInLap 时算出错误值）
  - **`accelerationG` 强制 null**（与 W1 生产数据 accelerationG 全 null 一致，标记 fallback；UI 层 SHALL 染色或标注此圈数据 fallback）

降级处理保证 `samplesPerLap.size == laps.size`，调用方 UI 层不需特判空圈。

#### Scenario: 比较圈仅 1 个样本
- **WHEN** `laps[1].samples.size == 1`，参考圈正常重采样输出 100 个 grid 点
- **THEN** `samplesPerLap[1].size == 100`，每个元素 == `laps[1].samples[0]`（直接复制，包括 `absoluteTsMs` 和 `accelerationG` 都保留原值）

#### Scenario: 比较圈样本为空走参考圈 fallback 重新派生 absoluteTsMs
- **WHEN** `laps[2].samples.isEmpty()`，`laps[2].lapStartWallClock == 1000000L`，参考圈 grid 点 100 个，参考圈 grid k=10 处 sample 的 `elapsedMsInLap == 500L`，参考圈 grid k=10 处 sample 的 `absoluteTsMs == 2000500L`（参考圈 lapStartWallClock 是 2000000L）
- **THEN**：
  - `samplesPerLap[2].size == 100`（保证非空 list）
  - `samplesPerLap[2][10].lat == 参考圈 grid 10 处的 lat`（同位置）
  - `samplesPerLap[2][10].lon == 参考圈 grid 10 处的 lon`
  - `samplesPerLap[2][10].speedKmh == 参考圈 grid 10 处的 speedKmh`
  - `samplesPerLap[2][10].elapsedMsInLap == 500L`（保留参考圈 elapsedMsInLap）
  - `samplesPerLap[2][10].absoluteTsMs == 1000500L`（**MUST** 等于 `laps[2].lapStartWallClock + 500L = 1000500L`，不是参考圈的 2000500L）
  - `samplesPerLap[2][10].accelerationG == null`（强制 null 标记 fallback）

### Requirement: LapAlignmentResult SHALL 暴露 distance / grid 双向映射

系统 SHALL 在 `LapAlignmentResult` 提供 `gridIndexFor(distanceMeters)` 与 `distanceAtGridIndex(gridIndex)` 双向 helper，让调用方在 cursor 拖动时直接从物理 distance 反查 grid index 不需复刻算法。

helper 行为：

```
fun gridIndexFor(distanceMeters: Double): Int = when {
    gridSize == 0 -> -1
    else -> (distanceMeters / distanceStepMeters).toInt().coerceIn(0, gridSize - 1)
}

fun distanceAtGridIndex(gridIndex: Int): Double = gridIndex * distanceStepMeters
```

**实现不变量**：`distanceStepMeters > 0.0` 由 LapAlignmentResult 主构造函数 `init { require(...) }` 锁死（非 EMPTY 时 step 必正）；EMPTY 时 `gridSize == 0` 命中第一分支返回 -1，不进入除法。因此 helper 不需要 `|| distanceStepMeters <= 0.0` 兜底分支。

#### Scenario: gridIndexFor 正例
- **WHEN** result `gridSize = 601`，`distanceStepMeters = 5.0`，调用 `gridIndexFor(580.0)`
- **THEN** 返回 `116`（floor(580/5) = 116，落在 [0, 600] 范围内）

#### Scenario: gridIndexFor 超出 refTotalDist 时 clamp
- **WHEN** result `gridSize = 601`，`distanceStepMeters = 5.0`（refTotalDist ≈ 3000m），调用 `gridIndexFor(5000.0)`
- **THEN** 返回 `600`（clamp 到 `gridSize - 1`，**禁止**返回 1000）

#### Scenario: gridIndexFor 在 EMPTY 上返回 -1
- **WHEN** `result == LapAlignmentResult.EMPTY`，调用 `gridIndexFor(100.0)`
- **THEN** 返回 `-1`，调用方在 UI 层判 `< 0` 显示占位

#### Scenario: distanceAtGridIndex 正例
- **WHEN** `distanceStepMeters = 5.0`，`gridIndex = 100`
- **THEN** `distanceAtGridIndex(100)` 返回 `500.0`

### Requirement: 算法 SHALL 是 pure function 且无 Android 依赖

系统 SHALL 实现 `LapAlignment` 为 `object`，所有方法为 pure function：

- 无任何 `android.*` import
- 无任何 `androidx.*` import
- 无 `Context` / `Application` / `SharedPreferences` 参数
- 无 Repository / DAO / Room / DataStore 调用
- 不修改输入参数（输入 list / sample 引用透明）
- 给定相同输入 SHALL 返回相同输出（确定性）

调用方约束：

- 调用方（Tier 2 多圈比较屏 ViewModel）SHALL 在 lap 选择改变时调用一次 `alignByDistance`，结果存 StateFlow / remember；cursor 拖动期 SHALL NOT 重调，仅用 `LapAlignmentResult.gridIndexFor` 查表（避免 GC 压力，O(N + M log N) 算法每帧重算 ~25 fps × 185 KB = 4.6 MB/s 持续分配）

#### Scenario: pure function 确定性
- **WHEN** 同一输入 `(laps, refIdx, step)` 连续调用 2 次 `alignByDistance`
- **THEN** 两次返回的 `LapAlignmentResult` 内容完全相等（深度比较，data class equals）

#### Scenario: 无 Android 依赖（grep gate）
- **WHEN** `core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt` 被 grep `import android\.|import androidx\.`
- **THEN** 命中数为 0（**反例**：若实施期意外引入 Android 依赖，此 grep gate MUST fail，确保 review 期能发现）

#### Scenario: 单测可在 JVM unit test 跑通（不需要 Robolectric）
- **WHEN** `./gradlew :core:domain:test --tests "*LapAlignmentTest*"`
- **THEN** 测试在标准 JVM 下运行，6 个 testcase 全部通过，**不依赖** Robolectric / `@RunWith(AndroidJUnit4::class)`
