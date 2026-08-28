## 1. 前置：性能 baseline 实测（解 design OQ1）

- [ ] 1.1 在 `feature/test/src/androidTest/java/com/blazepush/feature/test/usecase/` 下新建临时 benchmark 文件 `RealtimeDeltaCalculatorBenchmark.kt`（标注 `@author CC` `@description perf baseline for projectDelta alternatives` `@date 2026-05-05`）；构造 reference.size = 1500 frames（等价 60s 一圈、25Hz）+ 3 个 alternative 实现版本（Alt A 现状 / Alt B stateless / 可选 Alt C KD-tree）
- [ ] 1.2 在 benchmark 中跑每个 alternative 1000 次 projectDelta 调用，使用 `System.nanoTime()` 计时；输出每个 alternative 的：单次平均耗时（µs）/ 99 分位耗时（µs）/ 1000 次总耗时（ms）
- [ ] 1.3 在华为 `8KE0219522008434` 真机跑 benchmark：`./gradlew :feature:test:connectedDebugAndroidTest --tests "*RealtimeDeltaCalculatorBenchmark*"`；输出落到 `~/Downloads/realtime-delta-perf-baseline.log`
- [ ] 1.4 把 benchmark 结果填入 design.md OQ1 + Decision 1 alternatives 比较表的"实测"列；user 拍板 Alt A / B / C
- [ ] 1.5 拍板后 strip benchmark 文件（不进 git）；如需保留转 `@Ignore` 标注 manual run

## 2. 工件期补丁（Alt 拍板后修订工件）

- [ ] 2.1 按 §1.4 拍板 Alt 修订 `design.md` Decision 1（确定单一 alternative，删除其他 2 个的 detail）
- [ ] 2.2 按拍板 Alt 修订 `specs/realtime-lap-delta/spec.md` MODIFIED Requirements："projectDelta 纯函数" 和 "TestSessionViewModel 拥有 projectDelta 算法 + 跨帧状态" 中的"如 Alt A/B"分支删除非选定的，保留选定的
- [ ] 2.3 决定 Decision 3 的 `LAP_DURATION_TYPICAL_MS_DEFAULT` 阈值（OQ2 解）：(a) 固定 90_000ms / (b) 动态 reference.lapDurationMs * 1.5；user 拍板后修订 design.md + spec.md
- [ ] 2.4 决定 Decision 2 的 OQ3 GPS 信号丢失"持续 ≥ N 帧"中 N 值（建议 5 帧）；如选 Alt B 可作废此 OQ
- [ ] 2.5 决定 OQ4 真机 verify gate 设备覆盖（仅华为 OR 华为 + vivo）；建议算法仅华为，UI 显示加 vivo

## 3. 实施代码：核心算法（按拍板 Alt 走 §3a 或 §3b）

### 3a. 如选 Alt B（stateless 全量 O(n)，推荐）

- [ ] 3a.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculator.kt:37-91` 函数签名：删除 `prevMatchedIdx: Int` 和 `forwardWindowFrames: Int = 200` 入参；删除 `val center` / `val lo` / `val hi` 局部变量（原 line 49-51）；将 `for (i in lo..hi)` 改为 `for (i in 0..size-2)`
- [ ] 3a.2 修改 `RealtimeDeltaCalculator.kt` 顶部 KDoc：删除 `prevMatchedIdx` 参数说明 + 描述 stateless 行为
- [ ] 3a.3 修改 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:RealtimeDeltaState`（line 100-107）数据类：删除 `prevMatchedIdx: Int = -1` 字段
- [ ] 3a.4 修改 `TestSessionViewModel.kt:updateRealtimeDelta`（line 422+）：调 projectDelta 时不传 prevMatchedIdx；成功分支不更新 prevMatchedIdx
- [ ] 3a.5 修改 `TestSessionViewModel.kt:maybeRebuildReference`（line 388-404）：删除 `prevMatchedIdx = -1` 字段写入（不再存在）
- [ ] 3a.6 grep verify：`grep -nE 'prevMatchedIdx' feature/test/src/main/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculator.kt feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt feature/test/src/main/java/com/blazepush/feature/test/usecase/ReferenceLapIndex.kt` 命中数 == 0（确认 prevMatchedIdx 完全消除）

### 3b. 如选 Alt A（保留 prevMatchedIdx + reset 触发契约）

- [ ] 3b.1 在 `RealtimeDeltaState`（line 100-107）新增字段：`lastSeenLapCount: Int = 0` / `lastSeenTrackId: String? = null` / `lastSatelliteLostFrameCount: Int = 0`
- [ ] 3b.2 在 `TestSessionViewModel` 加新 collect 协程（在 init block 内），监听 `_lapSession`、`_currentSelectedTrack`、`gpsDataViewModel.gpsData`，atomic update reset 6 触发源
- [ ] 3b.3 修改 `RealtimeDeltaCalculator.kt:projectDelta` 行为：保留 prevMatchedIdx 入参不变；行为契约新增"matchedIdx 在边界场景符合 graceful 输出"
- [ ] 3b.4 grep verify：`grep -nE 'lastSeenLapCount|lastSeenTrackId|lastSatelliteLostFrameCount' feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` 命中数 ≥ 3（确认新字段存在）

## 4. 实施代码：跨帧 cache 异常兜底（共性，无论 Alt A/B）

- [ ] 4.1 修改 `TestSessionViewModel.kt:updateRealtimeDelta`（line 442-449 成功分支）：新增检查 `kotlin.math.abs(projection.deltaMs) > reference.lapDurationMs * 1.5`，如成立 → **拒绝写入 prevDeltaMs**（保留前值）+ staleFrameCount++ 视同失败
- [ ] 4.2 grep verify：`grep -nE 'lapDurationMs \* 1\.5|reference\.lapDurationMs.*1\.5' feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` 恰好 1 命中（确认兜底逻辑落地）
- [ ] 4.3 修改 `TestSessionViewModel.kt:maybeRebuildReference` 之外，新增 `maybeResetOnTrackChange()` private fun：监听 `_currentSelectedTrack.value.id` 变化，atomic update `_realtimeDeltaState.value = state.copy(reference = null, prevDeltaMs = null, ...)`；在 init block 加 collect

## 5. 实施代码：UI DELTA tile 占位显示

- [ ] 5.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt:200-209` `Lap2x2Dashboard` 内 `deltaText` 派生：新增分支 `state.deltaIsStale && kotlin.math.abs(state.deltaToBestMs ?: 0) > LAP_DURATION_TYPICAL_MS_DEFAULT * 1.5 -> "--"`（或动态 `reference.lapDurationMs * 1.5`，按 §2.3 拍板）
- [ ] 5.2 在 `LapLiveScreen.kt` 顶部添加 `private const val LAP_DURATION_TYPICAL_MS_DEFAULT = 90_000L`（如选固定值 OQ2 路径）；如选动态 reference.lapDurationMs * 1.5，需通过 LapLiveState 新增 `deltaSanityBoundMs: Long?` 字段从 ViewModel 传入
- [ ] 5.3 grep verify：`grep -n 'LAP_DURATION_TYPICAL_MS_DEFAULT\|deltaSanityBoundMs' feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt` 命中数 ≥ 2

## 6. 测试：4 边界场景反例 case

- [ ] 6.1 修改 `feature/test/src/test/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculatorTest.kt`（已存在，路径 verify）新增 4 个反例 case：
  - case `boundary_lap_switch_no_neg_lapDuration`：mock reference.lapDurationMs = 60_000 + 上一帧 prevMatchedIdx 设置在 reference 末段（仅 Alt A）OR 直接构造场景（Alt B）；新帧 GPS 物理位置在 reference idx 0 附近，currentLapElapsedMs = 100；断言 `result.matchedIdx <= 100` + `result.deltaMs in -100..200`（**反例**：违反此约束测试 fail）
  - case `boundary_gps_lost_recover_no_stuck_idx`：reference.size = 1500，prevMatchedIdx = 800（仅 Alt A，跨 5 帧不更新模拟 GPS lost），新帧 GPS 物理位置在 reference idx 200 附近；断言 result.matchedIdx in 100..300（Alt A 需先 reset prevMatchedIdx，Alt B 直接成立）
  - case `boundary_track_switch_returns_null_or_far_proj`：reference 是 trackA（origin lat=30, lon=120），新帧 GPS 物理位置在 trackB（lat=31, lon=121，距 trackA > 100km）；断言 `result == null`（projDistanceM > failoverDistanceM=50m 触发 null）
  - case `boundary_jitter_outlier_returns_null`：reference 任意，新帧 GPS 偏离 reference 100m；断言 result == null
- [ ] 6.2 在同测试文件新增 `cache_invalid_value_rejected` case：用 mock 框架（如 mockito-kotlin）让 projectDelta 返回 DeltaProjection(deltaMs = -125_000, matchedIdx = 1500, projDistanceM = 30f) + reference.lapDurationMs = 60_000；调用 ViewModel updateRealtimeDelta；断言 _realtimeDeltaState.value.prevDeltaMs == 上一帧值（不被写入 -125_000）+ staleFrameCount += 1
- [ ] 6.3 grep verify：`grep -cE 'boundary_lap_switch|boundary_gps_lost|boundary_track_switch|boundary_jitter_outlier|cache_invalid_value_rejected' feature/test/src/test/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculatorTest.kt` ≥ 5（5 个新 case）
- [ ] 6.4 跑 `./gradlew :feature:test:testDebugUnitTest --tests "*RealtimeDeltaCalculatorTest*"` 全绿（含原有 case + 5 新 case）

## 7. 测试：UI DELTA tile 占位 case

- [ ] 7.1 在 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreenTest.kt`（如不存在则新建）新增 case `delta_tile_shows_placeholder_on_stale_extreme_value`：mock LapLiveState(deltaToBestMs = -125_200, deltaIsStale = true)；断言 DELTA tile rendered value == "--"（**反例锁死 -125 灰色现象**）
- [ ] 7.2 新增 case `delta_tile_shows_value_on_stale_reasonable`：mock LapLiveState(deltaToBestMs = -2_500, deltaIsStale = true)；断言 value == "-2.50 s"（保留 baseline 灰色显示行为）
- [ ] 7.3 跑 UI 测试 `./gradlew :feature:test:testDebugUnitTest --tests "*LapLiveScreenTest*"` 全绿

## 8. Grep gates 防回退

- [ ] 8.1 `grep -cE 'reference\.lapDurationMs \* 1\.5' feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` 恰好 1 命中（cache 兜底）
- [ ] 8.2 如选 Alt B：`grep -nE 'forwardWindowFrames|prevMatchedIdx' feature/test/src/main/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculator.kt feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` 命中数 == 0
- [ ] 8.3 `grep -cE 'state\.deltaIsStale' feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt` ≥ 2（stale 判分支保留）
- [ ] 8.4 跨文件逃逸 grep gate：`grep -rnE 'prevMatchedIdx' feature/test/src/main` （如选 Alt B）命中数 == 0；防止 prevMatchedIdx 残留漏在某个未注意文件

## 9. 编译 + 测试全绿验证

- [ ] 9.1 `./gradlew :feature:test:compileDebugKotlin` 通过 0 warning
- [ ] 9.2 `./gradlew :feature:test:testDebugUnitTest` 全绿（含本 round 新 case）
- [ ] 9.3 `./gradlew :app:assembleDebug` 通过 0 error

## 10. 真机 verify（华为 8KE0219522008434）

- [ ] 10.1 build APK：`./gradlew :app:assembleDebug` 输出 `app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk`
- [ ] 10.2 装机：`adb -s 8KE0219522008434 install -r app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk`（**MUST** user 显式确认 install 时机；其他 round 真机 verify 等本 round 完）
- [ ] 10.3 回放泊寓数据 10 分钟（与 W4 hotfix B verify 相同 dataset）；观察 DELTA tile：
  - `-125.20 s` 灰色现象 MUST NOT 复现
  - 中段秒差 `10s → 5s` 跳变现象 MUST NOT 复现（注：如 Bug Y `fix-gps-data-filter-mid-stream-outlier` round 未修复，跳变可能仍存在但应是 graceful "--" 占位而不是数字跳）
- [ ] 10.4 真机签收：user 拍板"PASS / FAIL"

## 11. Codex L2 review + push（user 拍板顺序）

- [ ] 11.1 完成本 round 全部 §1-§10 后通知 user 触发 Codex review；review 范围 = 本 round commit 全部
- [ ] 11.2 Codex review 结果消化：(a) 局部 P2 修复直接补丁；(b) 设计级 P0/P1 → 暂停 apply 起新 round
- [ ] 11.3 push 顺序由 user 拍板（与 hardening round / Bug X / Bug Y / banner-flash follow-up 互不阻塞，但 user 决定推送时序）—— **MUST** 显式等 user 授权再 `git push`

## 12. 归档

- [ ] 12.1 `openspec archive --change "redesign-realtime-delta-projection-search"`
- [ ] 12.2 写 `metrics.yaml`：含 estimated_days / actual_days / review_rounds_l1 / review_rounds_l2 / review_findings / divergence_reason / phase / model_apply
- [ ] 12.3 更新协同看板 `docs/implementation-design/parallel-change-collab.md` §5 + §6
- [ ] 12.4 更新 Phase 1 治理表：把本 round 加到 W1-W4 之后的"DELTA 重设计"行

## 13. Follow-up backlog（CLAUDE.md 延期立项 memo 规矩）

- [ ] 13.1 如选 Alt A 但未来发现仍有边界 case 未覆盖 → 起新 round `realtime-delta-additional-reset-trigger`，标 follow-up
- [ ] 13.2 如选 Alt B 性能在 reference > 5000 frames 长赛道下退化 → 起新 round `realtime-delta-kdtree-optimization`（design 期已列 Alt C，可升级到此 round）
- [ ] 13.3 与 Bug Y `fix-gps-data-filter-mid-stream-outlier` 协同：本 round 闭环后如 user 真机仍偶见 "10→5 跳"占位（"--"），意味着 Bug Y 未修；状态写入看板 §5 关联说明
- [ ] 13.4 与 banner 闪 follow-up `investigate-banner-flash-on-replay` 协同：本 round 闭环不阻塞 banner 闪诊断
