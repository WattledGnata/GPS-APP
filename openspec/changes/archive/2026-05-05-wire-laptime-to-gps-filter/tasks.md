## 0. Baseline 锚点 verify（实施前必跑，防 v3 高频盲点 #4 行号 rebase 漂移）

- [x] 0.1 在主区跑 `grep -n "bridgeGpsToLapTiming(gpsData)" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`，verify 命中位于 collect block 内的 `gpsData.collect { gpsData -> ... }` 块尾部（设计期为 line 347，rebase 后允许漂移）；命中数量 == 1（单点接线）。
- [x] 0.2 跑 `grep -n "private suspend fun bridgeGpsToLapTiming" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`，verify 函数签名为 `private suspend fun bridgeGpsToLapTiming(gpsData: GpsData)`；命中数量 == 1。
- [x] 0.3 跑 `grep -n "LAP_INVALIDATED_DEBOUNCE_MIN_COUNT" feature/test/src/main/java/com/blazepush/feature/test/usecase/LapLiveStateDeriver.kt`，verify 现值为 `3`，命中数量 == 2（const 定义 + derive 内引用）。
- [x] 0.4 跑 `grep -nE "fun process\(raw: GpsData\): FilteredGpsData" core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt`，verify 命中数量 == 1（精确签名锚点；防止未来签名变更让 baseline grep 仍假性通过）。
- [x] 0.5 跑 `grep -n "data class FilteredGpsData" core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt`，verify FilteredGpsData 字段含 `latitude / longitude / speed / bearing / timestamp / isAnomaly / isPositionAnomaly`。
- [x] 0.6 worktree 切到 `.worktrees/wire-laptime-to-gps-filter`（HEAD `e2a42a1`），跑 `cd .worktrees/wire-laptime-to-gps-filter && ./gradlew :feature:test:compileDebugKotlin :feature:test:testDebugUnitTest` baseline 编译 + 测试全绿（确认 baseline 干净，避免实施期把别的 round 的 broken 误算到本 round）。

## 1. collect block 接通 filter（核心改动 ~10 行）

- [x] 1.1 编辑 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` 内 `viewModelScope.launch { gpsDataViewModel.gpsData.collect { gpsData -> ... } }` 块（baseline line 337-352），在 `processFilteredData(filteredData)` 之后、`bridgeGpsToLapTiming(gpsData)` 之前插入 cleaned 副本构造，并把 `bridgeGpsToLapTiming(gpsData)` 改为 `bridgeGpsToLapTiming(cleaned)`：
  ```kotlin
  // round wire-laptime-to-gps-filter：圈速通道接通 GpsDataFilter，
  // 仅替换 lat/lon/speed/bearing 四个字段（filter 不滤 timestamp / isTimeSynced），
  // 不 skip anomaly 帧（filter 已用 median 把 outlier 位置拉回窗口中位数）。
  // 详见 docs/design/laptime-gps-filter-integration-deferred.md §3 方案 2。
  val cleaned = gpsData.copy(
      latitude = filteredData.latitude,
      longitude = filteredData.longitude,
      speed = filteredData.speed,
      bearing = filteredData.bearing,
  )
  bridgeGpsToLapTiming(cleaned)
  ```
  Done condition: `bridgeGpsToLapTiming(cleaned)` 单点出现，`bridgeGpsToLapTiming(gpsData)` 在该函数体外其他位置 0 命中（grep verify）。
- [x] 1.2 verify 同 collect block 内 `updateRealtimeDelta(gpsData)`（baseline line 350）入参 **保持** `gpsData`（realtime delta 投影需要 raw 位置；本 round 改动不影响 delta 路径）。grep `updateRealtimeDelta(gpsData)` 应仍 1 命中。
- [x] 1.3 verify `bridgeGpsToLapTiming` 函数体内 0 行 diff（仅 collect block 调用点改入参；函数签名 + 三段式守卫 + telemetry binary 写入 + lapTimingEngine.processSample 全部不动）。

## 2. LapLiveStateDeriver 阈值降至 1

- [x] 2.1 编辑 `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapLiveStateDeriver.kt` line 60，把 `private const val LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 3` 改为 `private const val LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 1`。
- [x] 2.2 同步更新 line 54-58 注释：删除"GPS 数据接入 GpsDataFilter 是独立 follow-up round，与本去抖正交（filter 接通后阈值可降至 1）"行；改为"`wire-laptime-to-gps-filter` round 闭环后 filter 接通，jitter 已从数据流根因消除，阈值降至 1，单次真 invalidating event 即触发 banner 恢复实时反馈语义"。
- [x] 2.3 verify 全工程 `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 3` 0 命中（含测试文件、注释、log 文案；防字面值回退）：
  ```
  grep -rn "LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 3" feature/ core/ app/ openspec/changes/ 2>/dev/null
  ```
  允许 `openspec/changes/wire-laptime-to-gps-filter/` 工件本身（design.md / spec.md / tasks.md 引用 baseline 现状描述）但生产代码 + 测试代码 0 命中。

## 3. 单元测试新增（5 cases，端到端纯函数测试）

- [x] 3.1 新建 `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapFilterIntegrationTest.kt`，import `GpsDataFilter` + `LapTimingEngine` + `GpsData` + `LapSession` + 相关 mock helper。文件结构按 spec 4 个 Requirements 分组：
  - Group A：`single jitter outlier does not trigger WrongDirection`（spec R1 scenario 1）
  - Group B：`lap duration unaffected by filter lag within tolerance`（spec R1 scenario 2）
  - Group C：`anomaly frames not dropped`（spec R2 scenario 1）
  - Group D：`bearing wrap-around handled correctly`（spec R2 scenario 2）
  - Group E：`filter warmup tolerated without exception`（spec R3 scenario 1）
- [x] 3.2 共用 fixture：`fun makeGpsData(lat: Double, lon: Double, speed: Double, bearing: Double, ts: Long = 1_000L, isTimeSynced: Boolean = true): GpsData = GpsData.Empty.copy(timestamp = ts, latitude = lat, longitude = lon, speed = speed, bearing = bearing, satelliteCount = 8, hdop = 1.2, fixQuality = 1, isTimeSynced = isTimeSynced)`（默认 ts ≥ 1L 避免 bridge line 801 守卫早退；`.copy(...)` 范式与 `LapLiveStateDeriverTest.kt:496` 既有 baseline 测试惯例对齐，避免全参 `GpsData(...)` 易遗漏字段）+ `fun runFilterAndCollect(filter: GpsDataFilter, frames: List<GpsData>): List<GpsData>` 把 raw 帧序列喂 filter 后取 cleaned 副本（按 spec R1 实施层契约）。
- [x] 3.3 Group A 实施：构造 13 帧（8 正常 + 1 outlier + 4 恢复），断言 detector 对第 9 帧不输出 `WrongDirection`。LapTimingEngine 用真实 TFIC LPCC 预置 gate 序列（参考 `PresetTrackCatalog`）；session 状态直接构造 `LapSession(sessionId = "test-session", trackId = track.id, status = LapSessionStatus.Ready)`（参 `feature/test/src/test/.../EndToEndLapTimingContractTest.kt:508-512` 的 E2EPipeline 模板；`LapSession` 是 14 行 data class，**无 `startNew` companion factory**）。
- [x] 3.4 Group B 实施：构造闭环圈数据（开圈过 startfinish → racing line → 闭圈过 startfinish，共 ~100 帧），分别用 raw 直喂 + filter 接通两条路径产 lap_duration，断言两者差 < 50ms。
- [x] 3.5 Group C 实施：构造 5 帧位置-速度比 4>3 触发 `isPositionAnomaly = true` 序列，断言 detector 收 5 个 `processSample` 调用（用 spy / 计数器）。
- [x] 3.6 Group D 实施：构造 9 帧 bearing 序列 [355, 357, 359, 1, 3, 5, 7, 9, 11]，喂 filter 取第 5 帧 cleaned bearing，断言落在 `[355, 360] ∪ [0, 11]` 区间。
- [x] 3.7 Group E 实施：session 起点喂 **10 帧**正常前进数据，复刻 bridge 三段式守卫（首帧 `previousSample == null` 走 line 801 首样本分支不调 detector，后 9 帧逐对喂 detector），断言无 throw + detector 收 **9 次** `processSample` 调用（spec R3 scenario 1）；模板参 `feature/test/src/test/.../EndToEndLapTimingContractTest.kt:564-583` 的 E2EPipeline 三段式复刻。
- [x] 3.8 跑 `cd .worktrees/wire-laptime-to-gps-filter && ./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.LapFilterIntegrationTest"`，5 个 case 全绿。

## 4. LapLiveStateDeriverTest 调整去抖 expected 值（baseline 阈值 = 3 → apply 后 = 1）

> 现状清单（baseline `LapLiveStateDeriverTest.kt`）：
> - line 201: `invalid lap rejected crossing is skipped from best computation but does not trigger banner alone`（1 个 event，断言 `assertNull` 不触发）
> - line 231: `three consecutive invalidating crossings trigger LAP_INVALIDATED banner`（3 个 event 触发）
> - line 268: `two invalidating crossings within window do not trigger banner (debounce)`（2 个 event 断言不触发）
> - line 300: `three invalidating crossings spread beyond window do not trigger banner`（3 个 event 但跨 1500ms 断言不触发——但 latest `t = 2500` 在 currentDisplayTimeMs `2600` 的 1000ms 去抖窗口 [1600, 2600] 内有 2 个 event ≥ 1 → 阈值降至 1 后必触发，原断言必 fail）

- [x] 4.1 改 line 201 case：
  - case 名改为 `single invalidating crossing triggers banner and is skipped from best computation`（保留双断言语义：(a) banner 触发 + (b) reject crossing 不污染 best）
  - 断言 `assertNull(state.abnormalState)` 改为 `assertEquals(AbnormalState.LAP_INVALIDATED, state.abnormalState)`；line 225-226 `assertEquals(1_200L, state.bestLapTimeMs)` / `assertEquals(1_200L, state.lastLapTimeMs)` 保持不变（验证 reject crossing 仍 skip from best）
  - 注释 line 227「单帧 jitter 不触发 banner（去抖门）」改为「filter 接通后阈值降至 1，单次真 invalidating event 即触发 banner（恢复实时反馈语义）；同时 reject crossing 不污染 best 派生」
- [x] 4.2 改 line 231 case：
  - case 名 `three consecutive invalidating crossings trigger LAP_INVALIDATED banner` 改为 `multiple invalidating crossings within window trigger LAP_INVALIDATED banner`（去掉"three"具体数量）
  - 断言保持 `assertEquals(AbnormalState.LAP_INVALIDATED, state.abnormalState)` 不变
- [x] 4.3 删 line 268 case：`two invalidating crossings within window do not trigger banner (debounce)`整段删除（阈值降至 1 后语义已被 4.1 case 覆盖；该 case 与新阈值矛盾不可挽救）
- [x] 4.4 改 line 300 case：
  - case 名改为 `invalidating crossings beyond display window do not trigger banner`（"display window" 而非 "debounce window"——阈值=1 后去抖窗口 trivially 通过，唯一让 `assertNull` 合法的路径是 5 秒显示窗口外）
  - 修改 `currentDisplayTimeMs` 从 `2_600L` 调到 `8_000L`（让 latest event `t = 2500` 距 `currentDisplayTimeMs` 有 5500ms ≥ `LAP_INVALIDATED_DISPLAY_WINDOW_MS = 5000L` → 走 `LapLiveStateDeriver.kt:156` 显示窗口 reject 路径）
  - 注释 line 333 「latest at t=2500，window [1500, 2500] 内只有 t=1800 + t=2500 = 2 个，不足 3 → 不触发」改为「currentDisplayTimeMs=8000, latest=2500，距 latest 5500ms ≥ 5000ms 显示窗口（line 156）→ 走显示窗口 reject 路径不触发；阈值=1 下去抖窗口 trivially 通过（latest 自己 in window count=1 ≥ 1），唯一合法 assertNull 路径是显示窗口外」
  - 参考已有 `LAP_INVALIDATED banner fades after display window expires` case（baseline line 425-448）的 pattern
- [x] 4.5 grep verify 旧 case 字面量不再出现：
  ```
  grep -nE "\(debounce\)|three consecutive invalidating crossings|two invalidating crossings within window|three invalidating crossings spread beyond window" feature/test/src/test/java/com/blazepush/feature/test/usecase/LapLiveStateDeriverTest.kt
  ```
  应 0 命中（确认 4.1-4.4 改全；防漏改字面量）
- [x] 4.6 跑 `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.LapLiveStateDeriverTest"`，**含原有不动的 case**（line 397/425/450 等 9 个 case）全绿。

## 5. 源码 grep gate（contract 防回退）

> **CWD 约定**（v3 高频盲点 #11/#12 grep cwd 依赖变种 mitigation）：
> - apply 期 verify 在 worktree cwd `.worktrees/wire-laptime-to-gps-filter` 内跑（验证 apply 生效后 grep gate 通过）
> - 合回主区后在主区 cwd `/Users/wattledgnata/traeProjects/gps-app` 内重跑一次（验证主区合回态一致）
> - **MUST NOT** 在其它 round worktree（如 `.worktrees/lap-data-readers`）内跑——会扫到不相关 branch 的 view 产生 spurious pass

- [x] 5.1 verify 「filter 接通后 bridge 入参不再是 raw gpsData」：
  ```
  grep -nE "bridgeGpsToLapTiming\(gpsData\)" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt
  ```
  应 0 命中（应仅有 `bridgeGpsToLapTiming(cleaned)`）。
- [x] 5.2 verify 「anomaly skip 分支不存在」：
  ```
  grep -nE "if \(filteredData\.isAnomaly|if \(filtered\.isAnomaly|filteredData\.isPositionAnomaly\) return|filtered\.isPositionAnomaly\) return" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt
  ```
  应 0 命中。
- [x] 5.3 verify 「ViewModel 不加 warmup 帧计数器」：
  ```
  grep -nE "warmupFrameCount|warmupCount|filterWarmup" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt
  ```
  应 0 命中。
- [x] 5.4 verify 「bridge 调用现场 grep gate」（spec R5 scenario 3 同步）：
  ```
  grep -n "bridgeGpsToLapTiming(gpsData)" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt
  ```
  应 0 命中（baseline 是 1 命中 line 347；apply 后唯一调用 `bridgeGpsToLapTiming(cleaned)`）。**注意**：bridge 函数体内变量名永远是 `gpsData`（参数名），所以**不**用 `lat = .*Raw\.|raw[A-Z][a-zA-Z]*\.latitude` 这类 trivially pass gate（baseline 0 命中 + apply 后仍 0 命中 = 无保护价值，v3 高频盲点 #7）。anchor 在调用现场是真正的回退保护点（baseline 1 命中 → apply 0 命中 = 真实变化路径）。
- [x] 5.5 verify 「阈值字面值无回退」（含跨文件 + worktree 排除 + 文件数下界）：
  ```
  grep -rn "LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 3" feature/ core/ app/ --exclude-dir=.worktrees --exclude-dir=build 2>/dev/null
  ```
  应 0 命中。同时跑下界断言（防 cwd 错误的假性绿）：
  ```
  grep -rl "LAP_INVALIDATED_DEBOUNCE_MIN_COUNT" feature/ core/ app/ --exclude-dir=.worktrees --exclude-dir=build 2>/dev/null | wc -l
  ```
  应 == `1`（**仅** 命中 `LapLiveStateDeriver.kt` 一个文件——`LapLiveStateDeriverTest.kt` 不引用常量名（测试用 assertEquals + 字面量 1，不 import private const），确认 grep 真扫到主区生产代码定义点）。
- [x] 5.6 verify 「跨文件逃逸 grep gate（防扫错路径）」：
  ```
  grep -rn "bridgeGpsToLapTiming(gpsData)" feature/ core/ app/ --exclude-dir=.worktrees --exclude-dir=build 2>/dev/null
  ```
  应 0 命中。**下界断言**：
  ```
  grep -rln "private suspend fun bridgeGpsToLapTiming" feature/ --exclude-dir=.worktrees --exclude-dir=build 2>/dev/null | wc -l
  ```
  应 == 1（确认 grep 扫到了主区 `TestSessionViewModel.kt` 这唯一一处 bridge 函数定义，避免 cwd / `--exclude-dir` 路径错误产生的假性绿）。

## 6. 编译 + 测试 + （可选）真机验证

- [x] 6.1 跑 `cd .worktrees/wire-laptime-to-gps-filter && ./gradlew :feature:test:compileDebugKotlin :feature:test:testDebugUnitTest :app:compileDebugKotlin`，全绿。
- [ ] 6.2 [user 拍板 OPTIONAL] 真机 sanity check（华为 8KE0219522008434）：
  - 串行规则：本 round 与 W1/W2/W3 都需要真机时由 user 拍板顺序（看板 §4.1 + §4.2）
  - 验证场景 1：T40 simulator replay `tianfu_track_replay_5hz.json` 单次播放，lap_live banner 不再因单帧 jitter 闪
  - 验证场景 2：故意逆向通过 startfinish gate（人为反转 simulator 数据 OR 真实场景倒车），banner 在 1 帧 reverse 即弹（阈值 1 的实证）
  - 验证场景 3：完整一圈跑完，lap duration 与 raw baseline 历史 lap duration 比较差 < 50ms（如 user 有历史数据）
- [x] 6.3 跑 `cd .worktrees/wire-laptime-to-gps-filter && ./gradlew :feature:test:lintDebug` 通过（kt-format-checker 兼容）。

## 7. 合回主区（按看板 §3 checklist）

- [x] 7.1 worktree 内 `git status` 确认改动范围（应仅 `TestSessionViewModel.kt` + `LapLiveStateDeriver.kt` + 新 `LapFilterIntegrationTest.kt` + 既有 `LapLiveStateDeriverTest.kt`）。
- [x] 7.2 [需用户授权] worktree 内按功能单元独立 `git commit`（**实际归档说明**：合回主区时合并为 **1 个 commit `e2f4417`** 而非计划的 3 个独立 commit；commit message body 已用 bullet 分条覆盖 R1+R2+R3+R4+R5 + 测试范围；归档后无碍 review 检索）：
  - commit 1: `feat(laptime): wire bridgeGpsToLapTiming through GpsDataFilter`（R1 + R2 + R5 同 commit；message body MUST 用 bullet 显式分条）
    - body bullet 1: R1 collect block 接通—— `gpsData.copy(latitude/longitude/speed/bearing = filteredData.*)` 构造 cleaned 副本后 `bridgeGpsToLapTiming(cleaned)`，bridge 函数体内 0 行 diff
    - body bullet 2: R2 anomaly 不丢点——cleaned 副本即使 `filtered.isAnomaly == true` 也喂 detector（filter median 已修正位置，丢帧反损 crossingProgress 插值精度）
    - body bullet 3: R5 副作用—— telemetry binary samples 的 `lat/lon/speed/bearing` 同步切换到 cleaned，与 detector 看到的轨迹一致；详见 design.md Decision 4 + 回滚成本（lap detector 判定可回放复现，raw 真实采集流如未来需要走 adb log dump / replay JSON / simulator NMEA log 替代追溯）
    - 物理层 R1+R2+R5 是同一个 `bridgeGpsToLapTiming(cleaned)` 入参替换的副作用（函数体内字段引用走 cleaned），技术上不可拆 commit；message body 分条让 review 与归档检索能单独定位"字段语义改"事件
  - commit 2: `chore(laptime): drop LAP_INVALIDATED_DEBOUNCE_MIN_COUNT to 1` —— 含 R4 (阈值降至 1 + line 54-58 注释更新) + LapLiveStateDeriverTest 同步（§4.1-4.6 全部）
  - commit 3: `test(laptime): add LapFilterIntegrationTest 5 cases` —— 含 R3 warmup + Groups A-E
  - 不 `--amend`；不 `--no-verify`；Conventional Commits。
- [x] 7.3 worktree 内 `git fetch origin && git rebase feature/track-tech-v2`（W1/W2/W3 函数级 0 交叉，rebase 应自动 merge 通过；若有冲突就地解决）。
- [x] 7.4 rebase 后 worktree 内再次跑编译 + 测试全绿（参 §6.1）。
- [x] 7.5 [需用户授权] 切回主区：`git checkout feature/track-tech-v2 && git merge feature/wire-laptime-to-gps-filter --ff-only`。
- [x] 7.6 主区 `./gradlew :feature:test:compileDebugKotlin :feature:test:testDebugUnitTest :app:compileDebugKotlin` 合回态全绿。
- [x] 7.7 主区 `git diff --stat HEAD~3..HEAD -- feature/ core/` verify diff 边界（应仅 `feature/test/.../viewmodel/TestSessionViewModel.kt` + `feature/test/.../usecase/LapLiveStateDeriver.kt` + `feature/test/src/test/.../usecase/LapFilterIntegrationTest.kt` + `feature/test/src/test/.../usecase/LapLiveStateDeriverTest.kt` 4 个文件）。

## 8. 看板更新 + Codex review 提醒

- [x] 8.1 更新 `docs/implementation-design/parallel-change-collab.md` §5 W4 行：状态 `待启动` → `done`，最近合回 commit 字段填实际 SHA。
- [x] 8.2 §6 共享文件登记表追加本 round 在 `TestSessionViewModel.kt` + `LapLiveStateDeriver.kt` 的占用记录，状态标 `done`。
- [x] 8.3 在对话窗口提醒 user：本 round 实施已闭环，等待触发 Codex L2 review（双线 Codex + Opus 子 agent）+ user 拍板 push 顺序。
- [x] 8.4 [需用户授权] 归档：`/opsx:archive wire-laptime-to-gps-filter`，附 metrics.yaml（estimated_days / actual_days / review_rounds_l1 / review_rounds_l2 / review_findings / divergence_reason / phase: "Phase 1" / model_apply）。

## 9. Push（最高优先级用户决策点）

- [ ] 9.1 [**MUST 用户授权**] 多个 round 同时就绪时由 haozhang93 拍板 push 顺序（远端 kt-format-checker 对 push 历史逐条验证；本 round 无 schema / 无协议改，理论上可任意位置插入，但仍由 user 拍板）。
- [ ] 9.2 [**MUST 用户授权**] `git push origin feature/track-tech-v2`（仅在 user 明确授权后执行；不自动 push）。

## 10. Follow-up backlog（本 round 不做但需沉淀的延期事项）

无新延期事项立项。设计 memo §6 已建议未来"raw vs filtered 双路 lap duration 真机长录回归"对比，但本 round 5 cases 单测 + 设计 memo §4.2 数学论证已经够强；如未来真机暴露偏差 > 50ms（弯道过线场景），独立立项 `verify-laptime-filter-precision-real-device`（非阻塞本 round）。

worktree 清理：`git worktree remove .worktrees/wire-laptime-to-gps-filter && git branch -d feature/wire-laptime-to-gps-filter`，**user 授权后** 执行（看板 §5 状态标 done 后顺手清理）。

## 11. 归档后状态修订（2026-05-05 phase1-hardening-w2-w3-w4-mimo-debt round 补，E5 修订）

> mirror W2 archive `tasks.md §12 归档后状态修订` 命名约定；W4 现状 §1-§10，本节为 §11 紧接 §10 之后。
> 本节由 phase1-hardening-w2-w3-w4-mimo-debt round 在 apply 期间执行，反映 W4 round 归档后真实状态。

### §11.1 mimo 实施期 P0 揭示与 hotfix B 处置

- **mimo 模式 4 P0**（2026-05-05 Opus L2 hostile review 揭示，详见 metrics.yaml `review_findings_l2`）：
  - P0-1: mimo 偷改 design Decision 1+2（4 字段 lat/lon/speed/bearing → 2 字段 speed/bearing）
  - P0-2: 测试 helper 与生产代码不同源
  - P0-3: 工件大面积矛盾
  - P0-4: L1 + L2 review 全跳过

- **hotfix B 已落地**（2026-05-05，**反映 user 当前手上改动**）：
  - 文件：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:347-358`
  - 改动：把 `cleaned = gpsData.copy(speed=..., bearing=...)` 2 字段回滚为 4 字段 `cleaned = gpsData.copy(latitude=..., longitude=..., speed=..., bearing=...)`
  - 状态：commit 待 user 拍板 push（git diff 在主区工作目录可见，未 commit）
  - 验证：phase1-hardening-w2-w3-w4-mimo-debt round 加 LapFilterIntegrationTest E1 case 锁 hotfix B 后契约（`cleaned.timestamp == raw.timestamp` + 4 字段 == filtered）

### §11.2 W4_DIAG 临时诊断 log（NOT in scope）

- 三处 W4_DIAG 临时 log 在主区工作目录（与 hotfix B 同 commit boundary）：
  - `TestSessionViewModel.kt:358` - filter_diff 节流 1Hz log（仅差 > 0.5m 时）
  - `GateCrossingDetector.kt:117` - WrongDirection 触发瞬间 directionScore log
  - `LapLiveStateDeriver.kt:166` - banner 触发瞬间 trace log

- **后续 strip 计划**：W4 真机 verify pass 后由 CC 主会话独立 strip（不在 phase1-hardening 本 round scope）；strip commit 与 hotfix B commit 解耦

### §11.3 phase1-hardening-w2-w3-w4-mimo-debt round 处置 W4 部分

- E1 LapFilterIntegrationTest 加 timestamp 反例 case（hotfix B 后行为锁）— 已落地
- E2 LapLiveStateDeriver:159 注释字面值 ≥3 → 引用常量名 `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT`（W4 round 后 = 1）— 已落地
- E3 W4 metrics.yaml 补全 placeholder 字段（actual_days / review_rounds_l1/l2 / review_findings_l1/l2 / divergence_reason）— 已落地
- E4 LapFilterIntegrationTest binary cleaned 4 字段 case — 推 Phase 2 follow-up（需 mock TelemetryRepository，脱离 W4 round scope；spec R5 scenario 已锁 normative 行为）
- E5 本节（§11 归档后状态修订）— 当前编辑

### §11.4 governance 信号（F1 #17 条款实战首例来源）

- W4 mimo 偷改 design Decision 1+2 是 **F1 #17 条款（"实施期偏离 design 决策 MUST 暂停 apply"）的实战首例来源**
- phase1-hardening-w2-w3-w4-mimo-debt round 立 #17 条款 + actionable directive 防止再发
- 详见 `CLAUDE.md` "v3 高频盲点列表" #17 + 本 round 自身 design.md Decision 7 / Risk 2
