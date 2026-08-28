# 实施任务（依赖顺序）

本 change 合并 A36 + A43 + A44 三条清理型残余，按 3 个 Requirement 组织。
三条互不依赖，可独立或并行做；施工顺序不强制，但建议：

1. **R1 A36** 先做（touches Track 模型，engine 两处引用同步）
2. **R2 A43** 纯 rename，改动面最小
3. **R3 A44** 加 helper + 1 测试，独立于 R1/R2

合流门槛集中在第 4 节。

---

## 1. R1 A36 · Track.orderedSectorGates 单点真理

- [x] 1.1 **代码改动**：`feature/test/src/main/java/com/blazepush/feature/test/model/track/Track.kt` 加 `by lazy` 派生字段：
    ```kotlin
    val orderedSectorGates: List<TimingGate> by lazy { sectorGates.sortedBy { it.sequenceIndex } }
    ```
    加 KDoc 说明"单点真理 + `by lazy` 首访缓存 + data class equals/hashCode 不受影响"。
- [x] 1.2 **代码改动**：`feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`：
    - 第 221 行 `handleSectorCrossing` 内：`val orderedSectorGates = track.sectorGates.sortedBy { it.sequenceIndex }` → `val orderedSectorGates = track.orderedSectorGates`
    - 第 292-293 行 `expectedGate` 函数：`track.sectorGates.sortedBy { it.sequenceIndex }.getOrNull(...)` → `track.orderedSectorGates.getOrNull(...)`
- [x] 1.3 **新增测试** `TrackTest.orderedSectorGates_sortedBySequenceIndex_regardlessOfInputOrder`：
    - 构造 `Track(sectorGates = [S3(sequenceIndex=2), S2(sequenceIndex=1), S1(sequenceIndex=0)])`
    - 断言 `track.orderedSectorGates.map { it.id } == listOf("S1", "S2", "S3")`
- [x] 1.4 **新增测试** `TrackTest.orderedSectorGates_stableAcrossCalls`：
    - 连续访问 `track.orderedSectorGates` 两次
    - 断言 `first === second`（同一引用，证明 `by lazy` 缓存）
- [x] 1.5 **新增测试** `TrackTest.equalsIgnoresOrderedSectorGatesLazyField`：
    - 构造两个 Track 声明字段全等、但其中一个已访问过 `orderedSectorGates`（触发 lazy）
    - 断言 `trackA == trackB` 为 true（`by lazy` 不参与 data class equals）
- [x] 1.6 **grep 零残留验证**：
    ```bash
    grep -nE "sectorGates\.sortedBy" feature/test/src/main
    ```
    期望输出只在 `Track.kt` 的 `by lazy` 定义处命中，engine 两处应已改完。

## 2. R2 A43 · circularMedian → circularMean 命名纠偏

- [x] 2.1 **代码改动**：`core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt`：
    - 第 285-301 行函数定义：`private fun List<Double>.circularMedian(): Double` → `private fun List<Double>.circularMean(): Double`
    - 函数上方 KDoc 更新为 "循环均值（单位向量求和 + atan2） + 不对离群鲁棒"语义说明
    - 第 102 行调用点：`bearingWindow.circularMedian()` → `bearingWindow.circularMean()`
- [x] 2.2 **全仓 grep 零残留验证**：
    ```bash
    grep -rn "circularMedian" core/domain/src feature/test/src core/bluetooth/src
    ```
    期望输出：全为空（无 `circularMedian` 残留）。
- [x] 2.3 **现有 filter bearing 测试 rename + 回归**（**必做**，不再可选，否则 §4.8 零残留门槛必失败）：
    - `core/domain/src/test/java/com/blazepush/core/domain/usecase/GpsDataFilterTest.kt` 当前命中残留：
      - 第 391 行：`fun GF09_bearingCrossZero_circularMedian()` → rename 为 `fun GF09_bearingCrossZero_circularMean()`
      - 第 926 行：注释 "circularMedian() 应正确处理循环" → "circularMean() 应正确处理循环"
      - 第 928 行：注释 "预期：circularMedian() ≈ 5°-8°（正确）..." → "预期：circularMean() ≈ 5°-8°（正确）..."
    - 数值断言 v1/v2 完全等价（rename 不改算法），现有测试数据值保持不变
    - 跑 `GpsDataFilterTest` 全绿，特别是 bearing 跨 0°/360° 边界的用例

## 3. R3 A44 · wrappedDeltaLon 跨经度 180° 处理

- [x] 3.1 **代码改动**：`core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt` 新增 private helper：
    ```kotlin
    /**
     * 经度差带 ±180° 绕回处理（antimeridian wrap）。A44 修订。
     * 算法：原始差 > 180 → -360；< -180 → +360；否则透传。返回带符号 Δlon 度。
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
- [x] 3.2 **代码改动**：`checkPositionVelocityConsistency` 第 219 行：
    - `val deltaLonM = abs(current.longitude - prevPos.second) * 111320.0 * Math.cos(latRad)`
    - → `val deltaLonM = abs(wrappedDeltaLon(current.longitude, prevPos.second)) * 111320.0 * Math.cos(latRad)`
- [x] 3.3 **新增测试** `GpsDataFilterTest.checkConsistency_crossingAntimeridian_doesNotProduceFakeDistance`：
    - fixture 物理自洽约束：40ms / 50km/h → 位移 ~0.56m → 经度差 ~0.000005°
    - 喂第 1 帧 `(lat=0.0, lon=179.9999975, speed=50.0, ts=t0)`，跑 filter 建立 previousPosition/previousRaw
    - 喂第 2 帧 `(lat=0.0, lon=-179.9999975, speed=50.0, ts=t0+40ms)`（跨 antimeridian，v2 `wrappedDeltaLon` 修正后真实差 ~0.000005°）
    - 断言 `result.isPositionAnomaly == false`（v2 ratio ≈ 0.02 << 3；v1 不处理 wrap 会误判 true）
    - 断言 `result.consistencyFactor` 接近 1.0（v2 speedDiff ≈ 0.1，低于 5km/h 容差；v1 被拉到 0.3）
    - **注**：经度差不能用 `0.002°` 搭配 `40ms`（物理上 20,000 km/h 不现实，`vImpliedKmh` 爆表让 v2 也判异常）；
      也不能让 `dt > 0.2s`（filter 内 `dt > 0.2` 早退，不走一致性检查分支）
- [x] 3.4 **新增测试** `GpsDataFilterTest.checkConsistency_nonAntimeridianNormalCase_unchanged`：
    - 非跨边界场景（TFIC 104°E 前后 0.01° 经度差）
    - 断言 v2 输出与 v1 等价（`wrappedDeltaLon` 透传原始差不改变 filter 行为）

## 4. 合流门槛 + 自洽审计

- [x] 4.1 `openspec validate fix-lap-timing-campaign-c-tail-cleanup --strict` 通过。
- [x] 4.2 `./gradlew :feature:test:testDebugUnitTest --tests "*LapTimingEngineTest*"` 全绿（R1 改造后 engine 测试零回归）。
- [x] 4.3 `./gradlew :feature:test:testDebugUnitTest --tests "*TrackTest*"` 全绿（R1 新增 3 条测试通过）。
- [x] 4.4 `./gradlew :core:domain:test --tests "*GpsDataFilterTest*"` 全绿（R2 rename + R3 antimeridian 新 2 条测试通过 + 现有测试零回归）。
- [x] 4.5 `./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"` 全绿（R1 track 模型改动不破坏 E2E 契约）。
- [x] 4.6 `./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModelTrackLapTest*"` 全绿（bridge 零回归）。
- [x] 4.7 **R1 grep 零残留**（所有消费方应读 `Track.orderedSectorGates`）：
    ```bash
    # 期望：Track.kt 内的 `by lazy` 定义行是唯一合法残留（单点真理实现本身）
    grep -rnE "sectorGates\.sortedBy" feature/test/src | \
        grep -vF "Track.kt"
    ```
    期望空输出（`Track.kt` 定义行属单点真理实现，按文件排除；其他所有
    `sortedBy { sequenceIndex }` MUST 已收敛到 `Track.orderedSectorGates`）。
    当前已知消费方：
    - `LapTimingEngine.handleSectorCrossing` 第 221 行（已改）
    - `LapTimingEngine.expectedGate` 第 293 行（已改）
    - `LapDebugExecutionScreen.kt:42` UI（已改）
- [x] 4.8 **R2 grep 零残留**（全仓不应再有 `circularMedian`/`CircularMedian`/"循环中位数"；
    code review 升级为大小写不敏感 + 中文语义联合审计）：
    ```bash
    rg -n -i "circularmedian|循环中位数" core/domain/src feature/test/src core/bluetooth/src
    ```
    期望空输出。保留"普通中位数"对照说明不算残留（普通中位数是 v1 错误算法的对照描述，
    不是 A43 要消除的目标命名）。
- [x] 4.9 **回执更新** `docs/superpowers/reviews/attack-backlog.md`：A36 / A43 / A44 三条状态迁 🟢 `pending_review`，附本 change 的 commit hash。

## 5. Commit 策略

本轮三条清理型改动同质度高，建议合并 **1 个 commit**（复用 OpenSpec 工作流
`/opsx:apply` 的标准粒度）：

- **commit 1 — A36 + A43 + A44 清理**
  - Track 加 `orderedSectorGates by lazy` + engine 两处引用同步
  - filter `circularMedian` → `circularMean` rename + KDoc 澄清
  - filter `wrappedDeltaLon` helper + antimeridian 调用点修订
  - 新增 5 条测试（TrackTest ×3 + GpsDataFilterTest ×2）
  - 建议消息：`fix(laptiming): 战役 C 三期尾巴清理（A36/A43/A44）Track 单点真理 + filter 命名纠偏 + antimeridian`

若评审方倾向拆分，可按 R 分 3 个 commit（R1 Track + R2 rename + R3 antimeridian），
但三条 scope 都很小，合一个 commit review 成本更低。
