## 1. 协同看板登记 + worktree 准备

- [x] 1.1 阅读看板 §5/§6 核对：
  - `TestSessionViewModel.kt` 当前 A round（fix-lap-binary-ts-hygiene）实施中。本 round 加顶层 `_realtimeDeltaState` field 与 A 改 `bridgeGpsToLapTiming` 内部不重叠；rebase 应该 clean
  - `LapLiveStateDeriver.kt` / `LapLiveScreen.kt` / 新建文件 `ReferenceLapIndex.kt` / `RealtimeDeltaCalculator.kt` 当前无并行 round 占用
- [x] 1.2 看板 §5 登记本 round：`I. add-realtime-lap-delta`，状态"推进中"
- [x] 1.3 看板 §6 登记 `TestSessionViewModel.kt` 共享文件占用，标注"加顶层 field 与 A round 函数级不重叠"
- [x] 1.4 创建 worktree：`git worktree add .worktrees/add-realtime-lap-delta -b feature/add-realtime-lap-delta feature/track-tech-v2`

## 2. 米坐标投影 helper

- [x] 2.1 在 `feature/test/src/main/java/com/blazepush/feature/test/usecase/` 新建 `LocalPlaneProjection.kt`：
  ```kotlin
  internal object LocalPlaneProjection {
      private const val EARTH_R_M = 6_371_000.0
      // 经度 1 度 ≈ 111320 米 × cos(latitude)，纬度 1 度 ≈ 111320 米
      fun toMeters(refLat: Double, refLon: Double, lat: Double, lon: Double): Pair<Float, Float> {
          val cosLat = kotlin.math.cos(Math.toRadians(refLat))
          val dx = ((lon - refLon) * 111_320.0 * cosLat).toFloat()
          val dy = ((lat - refLat) * 111_320.0).toFloat()
          return dx to dy
      }
  }
  ```
- [x] 2.2 单测 `LocalPlaneProjectionTest.kt`：
  - refLat = 30.0, refLon = 120.0；同点 → (0, 0)
  - 经度 +0.001 → x ≈ 96.5m（cos(30°) × 111320 × 0.001）
  - 纬度 +0.001 → y ≈ 111.3m

## 3. ReferenceLapIndex 数据结构 + builder（Codex P1-1 + P1-2 已修订）

- [x] 3.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/usecase/ReferenceLapIndex.kt`：
  ```kotlin
  internal data class ReferenceLapIndex(
      val refLat: Double,            // 投影原点（trajectory.first() lat）
      val refLon: Double,            // 投影原点（trajectory.first() lon）
      val xs: FloatArray,
      val ys: FloatArray,
      val cumDistanceM: FloatArray,
      val elapsedMs: LongArray,      // 相对 bestLap.startedAtMillis
      val lapStartTsMs: Long,        // bestLap.startedAtMillis
      val lapDurationMs: Long,
  ) {
      fun toLocalMeters(lat: Double, lon: Double): Pair<Float, Float> =
          LocalPlaneProjection.toMeters(refLat, refLon, lat, lon)
  }
  ```
- [x] 3.2 builder `internal fun buildReferenceLapIndex(bestLap: LapRecord): ReferenceLapIndex?` 实现：
  - trajectory.size < 2 → return null
  - refLat / refLon = trajectory.first() lat/lon（米坐标参考点）
  - **lapStartTsMs = bestLap.startedAtMillis**（**不**用 trajectory.first().ts —— 那会引入一个采样间隔的固定偏移）
  - lapDurationMs = bestLap.durationMillis
  - 遍历 trajectory：
    - xs[i] / ys[i] = LocalPlaneProjection.toMeters(refLat, refLon, traj[i].lat, traj[i].lon)
    - **elapsedMs[i] = traj[i].timestampMillis - bestLap.startedAtMillis**（首点 ≥ 0，反映 trajectory 首点相对 crossing 的真实滞后；**不**强制 0 起点）
    - cumDistanceM[i] = cumDistanceM[i-1] + sqrt((xs[i]-xs[i-1])² + (ys[i]-ys[i-1])²)；i=0 时为 0
- [x] 3.3 单测 `ReferenceLapIndexTest.kt`：
  - 单点 trajectory → null
  - 100 点 trajectory + bestLap.startedAtMillis = 1000 + trajectory.first().ts = 1020 →
    - elapsedMs[0] == 20（**不是** 0）
    - lapStartTsMs == 1000
    - 所有 size 字段全 100
    - cumDistanceM[0] = 0
    - cumDistanceM / elapsedMs 单调非降
  - toLocalMeters(refLat, refLon) → (0f, 0f)
  - 直线轨迹（每点 lat 等距） → cumDistanceM 等差数列

## 4. projectDelta pure function + 单测

- [x] 4.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculator.kt`，包含 `internal data class DeltaProjection` + `internal fun projectDelta(...)`
- [x] 4.2 实现：
  ```kotlin
  internal fun projectDelta(
      reference: ReferenceLapIndex,
      currentLapElapsedMs: Long,
      currentX: Float,
      currentY: Float,
      prevMatchedIdx: Int,
      forwardWindowFrames: Int = 200,
      failoverDistanceM: Float = 50f,
  ): DeltaProjection? {
      val size = reference.xs.size
      if (size < 2) return null
      
      val center = if (prevMatchedIdx < 0) 0 else prevMatchedIdx
      val lo = (center - forwardWindowFrames).coerceAtLeast(0)
      val hi = (center + forwardWindowFrames).coerceAtMost(size - 2)  // segment [i, i+1] 上界
      
      var bestSegIdx = -1
      var bestT = 0f
      var bestDistSq = Float.MAX_VALUE
      
      for (i in lo..hi) {
          val segDx = reference.xs[i+1] - reference.xs[i]
          val segDy = reference.ys[i+1] - reference.ys[i]
          val segLenSq = segDx * segDx + segDy * segDy
          val t = if (segLenSq < 1e-6f) 0f else {
              ((currentX - reference.xs[i]) * segDx + (currentY - reference.ys[i]) * segDy) / segLenSq
          }.coerceIn(0f, 1f)
          val projX = reference.xs[i] + t * segDx
          val projY = reference.ys[i] + t * segDy
          val distSq = (currentX - projX).let { it * it } + (currentY - projY).let { it * it }
          if (distSq < bestDistSq) {
              bestDistSq = distSq
              bestSegIdx = i
              bestT = t
          }
      }
      if (bestSegIdx < 0) return null
      
      val projDistanceM = kotlin.math.sqrt(bestDistSq)
      if (projDistanceM > failoverDistanceM) return null
      
      val bestElapsed = reference.elapsedMs[bestSegIdx] +
          (bestT * (reference.elapsedMs[bestSegIdx + 1] - reference.elapsedMs[bestSegIdx])).toLong()
      val deltaMs = currentLapElapsedMs - bestElapsed
      return DeltaProjection(deltaMs = deltaMs, matchedIdx = bestSegIdx, projDistanceM = projDistanceM)
  }
  ```
- [x] 4.3 单测 `RealtimeDeltaCalculatorTest.kt`：
  - case 1：同轨迹 0 → abs(deltaMs) ≤ 5
  - case 2：当前慢 1s → abs(deltaMs - 1000) ≤ 5
  - case 3：当前快 1s → abs(deltaMs + 1000) ≤ 5
  - case 4：GPS 跳变 100m → return null
  - case 5：prevMatchedIdx = -1 + size = 1000 → 不抛异常，搜 [0, 200]
  - case 6：极端边界 prevMatchedIdx 接近末尾 → 不抛 IOOB
  - case 7：单点 reference (size = 1) → return null
  - case 8：当前点正好在 segment 中点（t = 0.5）→ bestElapsed 是两端 elapsedMs 的中值

## 5. LapLiveStateDeriver 重做（Codex P1-4 + P2 已修订）

> Deriver **不**调 projectDelta、**不**读跨帧状态。算法责任全在 ViewModel。Deriver 仅消费 ViewModel 算好的 deltaToBestMs / deltaIsStale 两个标量。CURRENT tile 时间继续用 ticker 外推，不切到 GPS sample 域。

- [x] 5.1 修改 `LapLiveState`：增加 `deltaIsStale: Boolean` 字段
- [x] 5.2 修改 `LapLiveStateDeriver.derive` 入参签名：
  - 保留 `currentTimeMs: Long`（语义上重命名为 `currentDisplayTimeMs: Long`，由 ViewModel ticker / elapsedRealtime 推动；保留 baseline 行为，CURRENT tile 平滑显示）
  - 新增 `deltaToBestMs: Long?`（ViewModel 已算好的 delta；null = 无 best 或从未成功过）
  - 新增 `deltaIsStale: Boolean`（ViewModel 已算好的 stale 标志）
  - **不**新增 reference / prevMatchedIdx / staleFrameCount 入参（这些是 ViewModel 内部跨帧状态）
- [x] 5.3 删除 baseline `currentLapTimerMs - bestLapTimeMs` 错位减法（line 100-104）
- [x] 5.4 实现派生：
  - `currentLapTimerMs = currentDisplayTimeMs - lastAcceptedCrossing.timestampMillis`（**保留** baseline 行为）
  - LapLiveState.deltaToBestMs = 入参 deltaToBestMs（直传）
  - LapLiveState.deltaIsStale = 入参 deltaIsStale（直传）
  - 其它字段（lastLapTimeMs / bestLapTimeMs / currentLapNumber / abnormalState）派生逻辑保持 baseline
- [x] 5.5 验证：grep `LapLiveStateDeriver.kt` 中**不**出现 `projectDelta(` 调用；**不** import RealtimeDeltaCalculator
- [x] 5.6 现有 `LapLiveStateDeriverTest.kt` 全套同步更新（入参签名 + 新字段）；新增 case：
  - 入参 deltaToBestMs = -500 + deltaIsStale = true → LapLiveState.deltaIsStale = true（直传）
  - 入参 deltaToBestMs = null + deltaIsStale = false → LapLiveState.deltaToBestMs = null
  - currentLapTimerMs 仍用 currentDisplayTimeMs - crossingTs（baseline 公式不变）

## 6. TestSessionViewModel 算法 + 跨帧状态（Codex P1-3 + P1-4 已修订：首圈完成立即建 reference + ViewModel 是 projectDelta 唯一调用方）

- [x] 6.1 在 `TestSessionViewModel.kt` 顶层加：
  ```kotlin
  internal data class RealtimeDeltaState(
      val reference: ReferenceLapIndex?,
      val prevMatchedIdx: Int = -1,
      val prevDeltaMs: Long? = null,
      val staleFrameCount: Int = 0,
  )
  
  private val _realtimeDeltaState = MutableStateFlow(RealtimeDeltaState(reference = null))
  ```
- [x] 6.2 在 lapSession StateFlow collect 路径里检测 reference 应否重建/首建：
  - **首圈完成立即建**：`completedLaps` 由空变 size = 1 → `buildReferenceLapIndex(completedLaps[0])` + atomic update `reference = newRef, prevMatchedIdx = -1, staleFrameCount = 0`（**prevDeltaMs 保留**）
  - **PB 刷新重建**：当某新完成圈 `durationMillis < reference.lapDurationMs` → 重建 reference + 同样 reset
  - 新完成圈不是 PB（更慢） → reference 保持，**不**强制 reset prev/stale
- [x] 6.3 active lap 重置（用户 abort / session 重启）→ atomic update `prevMatchedIdx = -1, staleFrameCount = 0`，prevDeltaMs 保留
- [x] 6.4 每帧 GPS data 来到时（GpsData StateFlow collect 路径）：
  - 当前 reference 为 null 或 lastAcceptedCrossing 为 null → outDelta = null, outIsStale = false
  - 否则：
    1. `(curX, curY) = reference.toLocalMeters(gpsData.latitude, gpsData.longitude)`
    2. `currentLapElapsedMs = gpsData.timestamp - lastAcceptedCrossing.timestampMillis`（GPS sample ts 域）
    3. `projection = projectDelta(reference, currentLapElapsedMs, curX, curY, prevMatchedIdx, ...)`
    4. atomic update `_realtimeDeltaState`：
       - 成功 → prevMatchedIdx = projection.matchedIdx, prevDeltaMs = projection.deltaMs, staleFrameCount = 0
       - 失败 → staleFrameCount += 1（其它不变）
    5. 派生本帧 outputs（喂给 deriver）：
       - 成功 → outDelta = projection.deltaMs, outIsStale = false
       - 失败 + prevDeltaMs != null + staleFrameCount < 5 → outDelta = prevDeltaMs, outIsStale = false
       - 失败 + staleFrameCount >= 5 → outDelta = prevDeltaMs, outIsStale = true
       - 失败 + prevDeltaMs == null → outDelta = null, outIsStale = false
- [x] 6.5 LapLiveScreen 消费的 LapLiveState 派生 flow：把 outDelta / outIsStale 作为 derive 入参传入（不传 reference / prev state）
- [x] 6.6 grep 验证：`projectDelta(` 在 ViewModel 内调用次数恰好 1 处；Deriver 内 0 处
- [x] 6.7 现有 ViewModel test 同步（如 `TestSessionViewModelTrackLapTest`）：测试 reference 生命周期 + 单帧调用次数 + first-lap 立即建

## 7. LapLiveScreen DELTA tile 渲染

- [x] 7.1 修改 `Lap2x2Dashboard` 的 deltaAccent 派生：
  ```kotlin
  val deltaAccent = when {
      state.deltaToBestMs == null -> TrackTechColors.TextMuted
      state.deltaIsStale -> TrackTechColors.TextMuted
      state.deltaToBestMs < 0 -> TrackTechColors.Green
      state.deltaToBestMs > 0 -> TrackTechColors.Red
      else -> TrackTechColors.TextPrimary
  }
  ```
- [x] 7.2 验证 V1 dead code 文件 diff 为空（git diff feature/test/src/main/java/com/blazepush/feature/test/ui/screen/）

## 8. 编译 + 单测

- [x] 8.1 worktree 内 `./gradlew :feature:test:assembleDebug` 通过
- [x] 8.2 worktree 内 `./gradlew :feature:test:testDebugUnitTest` 通过（含新增 4 个测试文件）
- [x] 8.3 `./gradlew :app:assembleDebug` 通过

## 9. 真机验证（按看板 §4.2 串行规则，需 user 授权）

- [x] 9.1 与 user 确认装机时间（默认华为 8KE0219522008434）
- [x] 9.2 等 user 授权后 `adb -s 8KE0219522008434 install -r`
- [x] 9.3 验证场景：
  - 第 1 圈进行中 → DELTA tile 显示 `--:--.---` muted
  - 第 1 圈完成瞬间 → reference 立即建立；第 2 圈第一个 GPS 帧到达后 DELTA 即应显示相对首圈的实时秒差（不应整个第 2 圈持续 muted）。允许冲线后 1-2 帧 GPS 延迟期间短暂 muted
  - 第 2 圈进行中 → DELTA 数字平滑变化，绿色（如这圈快）/ 红色（如这圈慢）
  - 故意外内线变线 → DELTA 数字不大跳，可能短暂跳几十 ms 后稳定
  - 故意 GPS 信号丢失（盖着手机）→ DELTA 字色变 muted（5 帧后），数字保留上一帧
  - 完成第 2 圈成为新 PB → reference 切换，第 3 圈 delta 是相对新 best
  - active lap abort → DELTA muted

## 10. commit + 合回 + push

- [x] 10.1 worktree 内独立 commit（建议拆 4 commit）：
  - commit 1: `feat(usecase): LocalPlaneProjection + ReferenceLapIndex builder + 单测`
  - commit 2: `feat(usecase): RealtimeDeltaCalculator polyline 投影 + 单测`
  - commit 3: `feat(usecase): LapLiveStateDeriver 重做 deltaToBestMs 派生 · GPS sample ts 域纯净化`
  - commit 4: `feat(viewmodel,ui): TestSessionViewModel 跨帧状态 + LapLiveScreen DELTA stale 字色降级`
- [x] 10.2 worktree 内 `git fetch origin && git rebase feature/track-tech-v2`（拉 A round 等并行合回的 commit）
- [x] 10.3 rebase 后再次跑编译 + 测试
- [x] 10.4 切回主区 `git merge --ff-only feature/add-realtime-lap-delta`
- [x] 10.5 主区编译确认
- [ ] 10.6 **需用户显式确认才能 push**：`git push origin feature/track-tech-v2`
- [x] 10.7 看板 §5 状态改 done；§6 占用全部 done
- [x] 10.8 清理 worktree：`git worktree remove .worktrees/add-realtime-lap-delta`
- [ ] 10.9 归档 round：`openspec archive add-realtime-lap-delta`

## 11. follow-up backlog（不在本 round 实现）

- [ ] 11.1 `add-realtime-delta-ema-smoothing` — 如真机验证发现 delta 数字小幅抖（GPS 噪声 ±20-50ms），加 250-500ms EMA 平滑。**触发条件**：本 round 上线后 user 反馈"数字闪得头晕"
- [ ] 11.2 `sector-based-delta-analysis` — 引入 sector 概念，对每个 sector 单独计算秒差 + 显示（赛道理论分析 + 教练复盘场景）。需要先做 sector gate 数据契约扩展。**触发条件**：用户对"整圈 delta 不够细"反馈，或专业用户要分段教学
- [ ] 11.3 `track-progress-thumbnail` — 复用 ReferenceLapIndex 的 cumDistanceM 数组做赛道进度缩略图（实时屏 / 详情屏可视化"当前圈走到哪里"）。**触发条件**：UI 要做赛道 minimap
- [ ] 11.4 `cleanup-v1-test-execution-screens` — 整组删 V1 dead code 屏（`TestExecutionScreen.kt` / `LapDebugExecutionScreen.kt` / 等）。**触发条件**：所有 V1 入口确认无引用后 1-2 周
