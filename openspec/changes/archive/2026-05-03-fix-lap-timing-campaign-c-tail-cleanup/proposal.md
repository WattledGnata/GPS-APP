# fix-lap-timing-campaign-c-tail-cleanup

战役 C 三期尾巴清理，闭环 attack-backlog **A36 + A43 + A44** 三条清理型残余。全部
属于"代码异味 / 命名纠偏 / 边界 case 未覆盖"级别，无生产 bug 在当前 TFIC 104°E
正常行驶场景暴露；本 change 是"把问题修在未来坐标扩张前"的工具链保底动作。

本 change 依赖：
- `fix-lap-timing-engine-entry-hardening` 已闭环（A19/A21/A34/A38）
- `fix-lap-timing-closure-and-precision-contract` 已闭环（A15/A20/A32/A33）
- `fix-gps-data-filter-signal-loss-and-anomaly-hygiene` 已闭环（A12/A13/A14）

改动涉及两个 capability：
- `lap-timing-engine`（R1 for A36）
- `gps-data-filter`（R2 for A43 + R3 for A44）

## Why

对抗 review `docs/superpowers/reviews/2026-04-22-lap-timing-and-gps-adversarial-review.md`
§ 1.12 / § 2.16 / § 2.17 揭示 engine 与 filter 三处残余清理项：

### A36 engine 两处重复 sector sort（§ 1.12）：单点真理缺失

`LapTimingEngine.kt` 有两处独立的 `track.sectorGates.sortedBy { it.sequenceIndex }`：

- **第 221 行**（`handleSectorCrossing`）：`val orderedSectorGates = track.sectorGates.sortedBy { it.sequenceIndex }` 每次样本调用都重算
- **第 293 行**（`expectedGate` 私有函数）：`track.sectorGates.sortedBy { it.sequenceIndex }.getOrNull(nextExpectedGateIndex - 1)` 每次 `processSample` 都重算

两处独立 sort 在 TFIC 2 个 sector 场景下性能可忽略，但**真正的风险**是**未来其中一处被
误改为 `sortedByDescending` / 不 sort / 改 comparator 时，另一处不会同步改动，测试
也不会立刻 fail**（handleSectorCrossing 内部消费顺序与 expectedGate 顺序出现分歧
才会触发异常）。单点真理（single source of truth）缺失。

### A43 `circularMedian` 命名与实现不符（§ 2.16）：API 语义误导

`GpsDataFilter.kt:285-301` 的函数**命名** `circularMedian`，但**实现**是：

```kotlin
private fun List<Double>.circularMedian(): Double {
    var sumSin = 0.0
    var sumCos = 0.0
    for (angle in this) {
        sumSin += Math.sin(Math.toRadians(angle))
        sumCos += Math.cos(Math.toRadians(angle))
    }
    val meanAngle = Math.toDegrees(Math.atan2(sumSin, sumCos))
    return if (meanAngle < 0) meanAngle + 360 else meanAngle
}
```

这是**单位向量求和后 atan2**，本质是**循环均值** `circularMean`（对对称分布准确，
对长尾会被拉向长尾，**不对离群鲁棒**）；"中位数"的语义承诺"对离群鲁棒"（经典
median 的核心属性）—— 当前实现不满足。

**后果**：未来维护者看到 `circularMedian` 会误以为"对 spike 鲁棒"，在需要鲁棒性
的场景（比如 bearing 偶尔受 GPS 噪声尖峰）错误沿用，结果被长尾拉偏。

### A44 `checkPositionVelocityConsistency` 跨经度不处理（§ 2.17）：边界 case 未覆盖

`GpsDataFilter.kt:219`：

```kotlin
val deltaLonM = abs(current.longitude - prevPos.second) * 111320.0 * Math.cos(latRad)
```

直接相减经度，不处理 ±180° 绕回。当车辆跨越 180° 经度线（antimeridian）时（示例
fixture `dt=40ms, speed=50km/h`，物理自洽位移 ~0.56m）：
- prev.lon = 179.9999975°，current.lon = -179.9999975°（实际经度差物理上 ~0.000005°）
- v1 `abs(-179.9999975 - 179.9999975)` = 359.999995°
- 投影到米：359.999995 × 111320 × cos(latRad) ≈ **40,075 km**（几何上几乎绕地球一周的距离）
- `vImpliedKmh` 被拉到 ~1e9 级 → `isPositionAnomaly = true` 误判 + `consistencyFactor` 被拉到 0.3
- 下游置信度崩盘

TFIC 104°E 不触发，但未来跨经度带赛道（例如太平洋岛屿赛道、国际竞技比赛）会在
antimeridian 附近产生"假位移 = 绕地球"的假阳性，让 filter 无法正常工作。

### 三条联动的共同根因

三条都属于**工具链 / 规范级的"正确性预防"**，不影响 TFIC 当前场景但在边界扩展
或未来维护时会引爆：

- A36 是"单点真理"工程规范
- A43 是"API 名称不骗人"命名规范
- A44 是"经纬度几何边界"数学规范

合并一个 change 做的理由：
1. 三条都是**纯清理 / 命名纠偏 / 边界 case 修补**，无业务逻辑新增
2. 每条改动面都很小（A36 2-3 处引用 + Track 加字段；A43 rename；A44 +1 utility + 测试）
3. review 成本：单 change 一次性审完 < 3 个独立 change 分别审（后者总成本非线性）
4. 都是"防御未来"性质，心智归一（v.s. 当前 bug 修复）

## What

### R1 `Track.orderedSectorGates` 单点真理（A36）

`Track` 数据类新增派生字段 `orderedSectorGates: List<TimingGate>`：

```kotlin
data class Track(
    val id: String,
    ...
    val sectorGates: List<TimingGate> = emptyList()
) {
    /**
     * `sectorGates` 按 `sequenceIndex` 升序排列的**单点真理**派生字段。
     *
     * 引入理由（A36）：engine 内消费 sector 顺序的两处（`handleSectorCrossing` /
     * `expectedGate`）原本各自 `sortedBy { sequenceIndex }`，单点真理缺失。本字段
     * 把排序语义统一到 Track 模型上，engine 只读字段不再重复 sort。
     *
     * 计算方式：Kotlin `by lazy`，首次访问时计算一次缓存（`Track` 是 data class
     * `equals` / `hashCode` 仍基于声明字段，不受 lazy 字段影响）。
     */
    val orderedSectorGates: List<TimingGate> by lazy { sectorGates.sortedBy { it.sequenceIndex } }
}
```

engine 两处改为读 `track.orderedSectorGates`：

```kotlin
// handleSectorCrossing (LapTimingEngine.kt:221)
BEFORE: val orderedSectorGates = track.sectorGates.sortedBy { it.sequenceIndex }
AFTER:  val orderedSectorGates = track.orderedSectorGates

// expectedGate (LapTimingEngine.kt:292-293)
BEFORE: track.sectorGates.sortedBy { it.sequenceIndex }.getOrNull(nextExpectedGateIndex - 1)
AFTER:  track.orderedSectorGates.getOrNull(nextExpectedGateIndex - 1)
```

**为什么用 `by lazy` 而非 `init` 预计算**：
- `init` 预计算会在 data class 主构造函数结束时执行，对"Track 只用 startFinishGate
  不消费 sectorGates"的场景（JSON 解析构造中间态、UI 只展示 track 信息）仍要付
  排序开销，虽然小但无意义
- `by lazy` 首次访问才计算，与 `sectorGates` 为空的合法场景兼容

**Non-goal for A36**：不引入 `Track.isValidated: Boolean` 或运行时 `sequenceIndex`
唯一性校验 —— 那属于更大范围的"Track 健康检查"战役，本 change 只做排序单源。

### R2 `circularMedian` → `circularMean` 命名纠偏（A43）

`GpsDataFilter.kt` 的扩展函数重命名 + 函数注释更新：

```kotlin
BEFORE:
/**
 * 扩展函数：计算循环中位数（用于航向角）
 * 将角度转换为单位向量后求平均角...
 */
private fun List<Double>.circularMedian(): Double { ... }

AFTER:
/**
 * 扩展函数：计算循环均值（单位向量求和后 atan2，用于航向角滤波）。
 *
 * **不是中位数**（v1 命名错误已由 A43 修正为 `circularMean`）：
 * - 实现是"单位向量相加 + atan2"，对**对称分布**的 bearing 样本准确收敛
 * - 对**长尾分布**（偶尔 spike 的 bearing 噪声）会被拉向长尾，**不对离群鲁棒**
 * - 想要鲁棒性的场景（bearing 偶尔 GPS 尖峰）应先用 [isPositionAnomaly] 或外层
 *   anomaly 过滤把离群样本排除，再喂进本函数；不要误以为本函数自带鲁棒性
 *
 * 场景：bearing 跨 0°/360° 边界（355° → 5°）时，直接 median 会给出 `180°`
 * 的错误答案；本函数用单位向量旋转几何确保跨边界正确性。
 */
private fun List<Double>.circularMean(): Double { ... }
```

调用点同步改名（`GpsDataFilter.kt:102`）：

```kotlin
BEFORE: val outputBearing = if (bearingWindow.size >= 3) bearingWindow.circularMedian() else raw.bearing
AFTER:  val outputBearing = if (bearingWindow.size >= 3) bearingWindow.circularMean() else raw.bearing
```

**Non-goal for A43**：不引入**真正的**循环中位数（真正的循环中位数需要"把样本排到
圆周上找 N/2 分位点"，算法复杂度 O(N²) 或 O(N log N)，当前 `bearing` 25Hz 数据
性能要求不值得实现）。如果未来某场景真的需要鲁棒性，应用外层 anomaly 过滤
（`isPositionAnomaly` / `isAnomaly`）而非换算法。

### R3 `wrappedDeltaLon` 跨经度 180° 边界处理（A44）

`GpsDataFilter.checkPositionVelocityConsistency` 新增内部 helper：

```kotlin
/**
 * 经度差带 ±180° 绕回处理（antimeridian wrap）。
 *
 * 引入理由（A44）：当车辆跨越 180° 经度线时，`abs(current.lon - prev.lon)` 直接
 * 相减会得到 ~360° 的假差（物理位移几米，几何差 ~40000 km），让
 * `checkPositionVelocityConsistency` 误判 `isPositionAnomaly = true`。
 *
 * 算法：
 * - 先算原始差 `delta = current.lon - prev.lon`
 * - 若 `delta > 180`，减 360（车辆从 179°E 跨到 -179°E，原始差 +358°，修正为 -2°）
 * - 若 `delta < -180`，加 360（反向跨越）
 * - 否则返回原始差（非跨边界场景，语义等价）
 *
 * 返回值：**带符号**的 Δlon（度），下游取 `abs` 后投影到米。
 */
private fun wrappedDeltaLon(currentLon: Double, prevLon: Double): Double {
    val raw = currentLon - prevLon
    return when {
        raw > 180.0 -> raw - 360.0
        raw < -180.0 -> raw + 360.0
        else -> raw
    }
}
```

调用点（`GpsDataFilter.kt:219`）：

```kotlin
BEFORE: val deltaLonM = abs(current.longitude - prevPos.second) * 111320.0 * Math.cos(latRad)
AFTER:  val deltaLonM = abs(wrappedDeltaLon(current.longitude, prevPos.second)) * 111320.0 * Math.cos(latRad)
```

**Non-goal for A44**：不处理极地附近的 `cos(latRad) → 0` 退化（极地赛道不在需求范围，
属 `fix-gps-position-denoise` 未来战役的扩展）；不引入 WGS84 椭球大地测量
（`A10 / backlog` 明确：当前 `METERS_PER_DEGREE_LAT = 111320.0` 常量在 TFIC 场景够用，
升级椭球是 <0.1% 精度战役）。

## Impact

### 数据模型变更

| 字段 | v1 | v2 | 破坏性 |
|---|---|---|---|
| `Track.orderedSectorGates` | — | 新增 `by lazy` 派生字段 `List<TimingGate>`（A36） | ✅ 非破坏（新增字段，不影响现有构造/equals/hashCode） |
| `GpsDataFilter.circularMedian` | private extension fun | **重命名为** `circularMean`（A43） | ⚠️ private 函数 rename，不影响外部 API |

`Track` 的新字段是 `by lazy` —— data class 的 `equals` / `hashCode` / `copy` 仍按
声明字段算，lazy 属性是"推导结果"不影响模型身份。现有构造调用 `Track(id=..., sectorGates=[...])`
完全不变。

### 受影响模块

| 模块 | 文件 | 动作 |
|---|---|---|
| model | `feature/test/src/main/java/com/blazepush/feature/test/model/track/Track.kt` | 新增 `val orderedSectorGates by lazy { sectorGates.sortedBy { sequenceIndex } }` |
| engine | `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt` | 第 221 行 + 第 292-293 行：`track.sectorGates.sortedBy { ... }` → `track.orderedSectorGates` |
| filter | `core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt` | 第 285-301 行：`circularMedian` → `circularMean`（函数名 + 注释 + 调用点 102 行同步）；第 219 行：`abs(lon - lon)` → `abs(wrappedDeltaLon(...))` |
| 测试 | `feature/test/src/test/java/com/blazepush/feature/test/model/track/TrackTest.kt`（新增文件或已有） | 新增 `orderedSectorGates_sortedBySequenceIndex` / `_stableAcrossCalls` 契约断言 |
| 测试 | `core/domain/src/test/java/com/blazepush/core/domain/usecase/GpsDataFilterTest.kt` | 新增 A44 `checkConsistency_crossingAntimeridian_doesNotProduceFakeDistance`；核对 `circularMean` rename 后 bearing 窗口测试仍通过 |

### 行为变更

| 场景 | Before | After |
|---|---|---|
| engine 两次 sort（TFIC 单 sample） | 两次 O(N log N)（N=2 实际 O(1)） | 首次访问 `orderedSectorGates` 一次 O(N log N)，之后 O(1) 读 |
| engine 两处 sort 不一致风险 | 各自独立，无契约 | 强制单点，改一处自动影响另一处（逻辑上防御） |
| `bearingWindow.circularMedian()` → `.circularMean()` | 函数名误导 | 名实相符 + 注释解释"不是鲁棒性" |
| `bearing = [355, 0, 5]` 跨边界输出 | `~3°`（向量均值算法） | `~3°`（同算法，只改名称） |
| 跨 antimeridian 位置差（物理自洽 `dt=40ms, speed=50km/h`, `prev.lon=179.9999975, current.lon=-179.9999975`） | deltaLonM ≈ 40,075 km → isPositionAnomaly 误判 + consistencyFactor 0.3 | `wrappedDeltaLon` 返回 0.000005°，deltaLonM ≈ 0.56m，vImpliedKmh ≈ 50.1，语义正确 |
| 非跨边界（典型场景） | 语义等价 | 语义等价（wrappedDeltaLon 透传原始差） |
| TFIC 104°E 正常行驶 | 正确 | **完全相同**（不触发 antimeridian 分支） |

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| `Track.orderedSectorGates` 是 `by lazy`，对`equals` / `hashCode` 是否影响？ | `by lazy` 属性是 data class body 内的成员属性，**不在 primary constructor 中**，因此不参与 Kotlin data class 自动生成的 `equals` / `hashCode` / `copy`；测试新增 `TrackTest.equalsIgnoresOrderedSectorGatesLazyField` 锁定契约 |
| 调用 `orderedSectorGates` 线程安全 | `by lazy` 默认 SYNCHRONIZED，线程安全；engine 25Hz 单线程消费，实际不需要同步但 lazy 默认安全不损失 |
| `circularMedian` rename 漏掉某处调用点 | grep 确认只有 filter 内部 1 处调用 + 1 处定义；测试层无引用（filter bearing 测试走 filter 公共 API 间接测） |
| `wrappedDeltaLon` 引入 branch，热路径性能 | 3 条件 branch，25Hz × 2 次判断（gt / lt）可忽略；benchmark 若需要可加，本 change 不强制 |
| antimeridian 测试 fixture 经纬度数值 | 物理自洽约束：`dt=40ms + speed=50km/h` 对应位移 ~0.56m → 经度差 ~0.000005°。选 `prev.lon=179.9999975, current.lon=-179.9999975, lat=0`（赤道）做测试；非极地、非跨纬度，几何最清晰。避免 `dt>0.2s` 早退分支 + 避免物理上不现实的高速场景（0.002° / 40ms 对应 20,000 km/h 会让 v2 也判异常） |

### 回归保护清单

每 Requirement 对应至少 1 条硬区分测试：

- **R1 × 2**：
  - `TrackTest.orderedSectorGates_sortedBySequenceIndex_regardlessOfInputOrder`（构造 `sectorGates = [S3, S2, S1]`，断言 `orderedSectorGates = [S1, S2, S3]`）
  - `TrackTest.orderedSectorGates_stableAcrossCalls`（连续两次 getter 返回同一 List 引用，证明 `by lazy` 缓存）
- **R2 × 1**：
  - `GpsDataFilterTest` 现有 bearing 测试（如 `GF09_bearingCrossZero_circularMedian`）rename 为 `_circularMean_` 或更新注释；断言输出不变（rename 是纯语义不改算法）
- **R3 × 2**：
  - `GpsDataFilterTest.checkConsistency_crossingAntimeridian_doesNotProduceFakeDistance`（物理自洽 fixture `dt=40ms / speed=50km/h / prev.lon=179.9999975 / current.lon=-179.9999975 / lat=0` → v2 `isPositionAnomaly=false + consistencyFactor≈1.0`；硬区分 v1 `isPositionAnomaly=true + consistencyFactor=0.3`）
  - `GpsDataFilterTest.checkConsistency_nonAntimeridianNormalCase_unchanged`（v1/v2 对非跨边界场景输出等价回归保护）

## Alternatives

### 方案 A：三条拆三个独立 change

**拒绝理由**：每条 scope < 100 LOC（含测试），独立 change 的 proposal/spec/tasks
写作成本与 review 成本占比过高；合并单 change 成本更低。三条都是清理型同质，
风险隔离需求弱。

### 方案 B：`Track.orderedSectorGates` 用 `init` 预计算而非 `by lazy`

**拒绝理由**：
- `sectorGates` 允许为 `emptyList()`（起终点赛道、仅绕圈赛道）—— init 对空 list 排序
  无意义但仍要执行
- `by lazy` 首访计算 + 缓存，对"不消费 sectorGates"的场景（JSON 解析中间态、UI
  展示 track 名不访问 gates）零开销
- 线程安全差异不相关（data class 本质不可变，lazy default SYNC 也无额外损耗）

### 方案 C：`circularMedian` 保留名称 + 注释警告"不是真 median"

**拒绝理由**：注释警告是防御性文档，不治本；命名是 API 的一部分，下游见到
`circularMedian` 几乎必然假设"鲁棒性"（median 的核心属性），注释只能拦住认真读
注释的维护者，拦不住 autocomplete / IDE 提示级的误用。rename 成本低，一劳永逸。

### 方案 D：`wrappedDeltaLon` 独立成为 `GeoMath` utility module 供多处复用

**拒绝理由**：目前仅 filter `checkPositionVelocityConsistency` 一处需要（detector
的 `segmentsIntersectMeters` 走米空间投影，投影原点在 gate 中点，天然避开 antimeridian
问题）。提前建 utility module 引入过度抽象；未来若 engine / bridge / UI 层也需要
跨经度处理，届时再抽。YAGNI。

### 方案 E：把 A36 的 `Track.orderedSectorGates` 改成 `init` + 运行时 `sequenceIndex` 唯一性校验

**拒绝理由**：`sequenceIndex` 唯一性校验属于更大范围的"Track 模型健康检查"（包含：
sectorGates 非空 / startFinish passDirection 非零 / referencePath 连续等），本 change
scope 太小不适合扩。留给未来 `fix-track-model-validation` 战役。

## Non-goals

- **不改** `Track` 模型的 `equals` / `hashCode` / `copy` 语义（`by lazy` 字段不在 data class 自动生成范围内，无破坏）
- **不引入** 真正的"对离群鲁棒"循环中位数算法（算法复杂度 vs 收益不划算；靠外层 anomaly 过滤）
- **不做** A35（UI `currentLap +1` / I 战役）/ A36 运行时校验 / A39 日志隐私 / A22 UI haversine
- **不做** WGS84 椭球升级（`METERS_PER_DEGREE_LAT` 常量在 TFIC 场景够用，A10 契约保留）
- **不做** 极地 / 高纬度 `cos(lat) → 0` 退化处理（跨极赛道不在需求范围）
- **不做** 任何生产代码的行为变更（除 antimeridian 边界 case 外，所有场景输出保持等价）
