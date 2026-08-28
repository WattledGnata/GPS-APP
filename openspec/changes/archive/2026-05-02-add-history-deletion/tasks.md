## 1. 协同看板登记 + worktree 准备

- [x] 1.1 阅读看板 §5/§6 核对：
  - `RecordsHomeScreen.kt` 当前主区无并行 round 在改 → 独占（F round 已归档；I round 已归档）
  - `TestSessionViewModel.kt` 仍跟 A round（fix-lap-binary-ts-hygiene）共享 — 函数级不重叠（A 改 `bridgeGpsToLapTiming` 内部，本 round 加 `deleteTestRecord` / `deleteLapSession`）
  - `TelemetryRepository.kt` 同 A round 共享 — 函数级不重叠（A 改 startSession/endSession 内部加 property，本 round 加新 deleteSession 方法）
  - `TrackTechRow.kt` 当前主区无并行 round 在改 → 独占
  - 新建文件 `DeleteHistoryDialog.kt` 独占
- [x] 1.2 看板 §5 登记本 round：`J. add-history-deletion`，状态"推进中"
- [x] 1.3 看板 §6 登记共享文件占用：`TestSessionViewModel.kt` / `TelemetryRepository.kt` / `TestResultRepository.kt`，标注"加新 delete 方法与 A round 函数级不重叠"
- [x] 1.4 创建 worktree：`git worktree add .worktrees/add-history-deletion -b feature/add-history-deletion feature/track-tech-v2`，cp keystore.properties 到 worktree

## 2. 数据层 — TelemetrySessionDao + CrossingEventDao 加 delete 方法

- [x] 2.1 编辑 `core/data/src/main/java/com/blazepush/core/data/local/dao/TelemetrySessionDao.kt`：加 `@Delete suspend fun deleteSession(entity: TelemetrySessionEntity)`
- [x] 2.2 编辑 `core/data/src/main/java/com/blazepush/core/data/local/dao/CrossingEventDao.kt`：加 `@Query("DELETE FROM crossing_events WHERE sessionId = :sessionId") suspend fun deleteCrossingsBySessionId(sessionId: String)`
- [x] 2.3 grep `class .* : TelemetrySessionDao` / `class .* : CrossingEventDao`（在 `:core:data:src/test` + `:feature:test:src/test`）找全部现役 fake 实现，同步加 override（轻量 mutate state，方便 §3 单测复用）：3 个 fake 都已加。`:feature:test:src/test` 无 Fake DAO 实现。
- [x] 2.4 `./gradlew :core:data:compileDebugKotlin :core:data:compileDebugUnitTestKotlin` 编译通过（fake 同步无遗漏）；完整 testDebugUnitTest 推迟到 §3 加完 cascade 单测后一起跑

## 3. 数据层 — TelemetryRepository.deleteSession cascade

> **注意（Codex review v1 修订）**：现有 `TelemetryRepository` 构造参数属性名是 **`sessionDao` / `crossingDao`**（不是 `telemetrySessionDao` / `crossingEventDao`）；`TelemetrySessionDao` 已有 **`queryBySessionId(sessionId)`**（不要新增 `getSessionById`，会跟既有方法重复）。

- [x] 3.1 编辑 `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`，加 `suspend fun deleteSession(sessionId: String)` cascade（已用 `import java.io.File`，文件顶部已存在）：query → 清 crossing → 删 entity → 白名单删 binary
- [x] 3.2 单测 `TelemetryRepositoryDeleteSessionTest.kt`（`core/data/src/test/...repository/`）：
  - Scenario 1 普通 cascade：startSession + 5 writeCrossing + endSession → deleteSession → entity null + crossing 0 行 + binary 文件不存在 ✅
  - Scenario 2 不存在 sessionId：插入占位 session → deleteSession("does-not-exist") → 占位 session 仍在 ✅
  - Scenario 3 binary 路径外白名单防御：手动构造 entity 指向 `<tempDir>/other/<id>.bin` → deleteSession → db 行清掉但文件**仍存在** ✅
  - 全 3 case 在 `:core:data:testDebugUnitTest` 全绿

## 4. 数据层 — TestResultRepository 加 by-id 删除 wrapper

> **注意（Codex review v1 修订）**：原方案让 ViewModel 直接调 `testRecordDao.getById` 不可行 — `TestSessionViewModel` 只注入 `TestResultRepository`，没 DAO 字段。把 by-id 查询封装进 repository，避免 DAO 边界泄漏到 feature 层。

- [x] 4.1 编辑 `core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt`：在现有 `deleteResult(entity)` 后加 `suspend fun deleteResultById(id: String)` wrapper，复用既有 `getTestRecordById(id)` + `deleteResult(entity)` cascade（已 verify `TestRecordDao.getTestRecordById` 在 line 21）。

## 5. ViewModel 层 — TestSessionViewModel 暴露 delete 方法

- [x] 5.1 编辑 `TestSessionViewModel.kt`：在 `endActiveLapSession` 之后加 `fun deleteTestRecord(recordId: String)` + `fun deleteLapSession(sessionId: String)`，均 `viewModelScope.launch(Dispatchers.IO)` 调对应 repository 方法。
- [x] 5.2 import 调整：加 `import kotlinx.coroutines.Dispatchers`；`:feature:test:compileDebugKotlin` 通过

## 6. UI 层 — TrackTechRow 加 onLongClick 参数

- [x] 6.1 编辑 `TrackTechRow.kt`：函数签名追加 `onLongClick: (() -> Unit)? = null`；`clickable` → `combinedClickable(onClick, onLongClick)`；加 `@OptIn(ExperimentalFoundationApi::class)` + import 调整
- [x] 6.2 grep 现有 5 处调用方（RecordsHomeScreen.kt × 2 + DeviceHomeScreen.kt × 3）都没传 `onLongClick` → 默认 null 兼容；`:feature:test:compileDebugKotlin` 通过

## 7. UI 层 — DeleteHistoryDialog Composable

- [x] 7.1 新建 `feature/test/.../ui/tracktech/components/DeleteHistoryDialog.kt`：含 `sealed interface DeleteCandidate` + `TestRecord(id, titleHint)` / `LapSession(id, titleHint)`，`@Composable fun DeleteHistoryDialog(candidate, onConfirm, onDismiss)`，title `"删除记录?"` / 副标 `candidate.titleHint`（`maxLines = 2 + Ellipsis`）；删除按钮 `TrackTechColors.Red` / 取消按钮 `TrackTechColors.TextSecondary`
- [x] 7.2 风格 verify 与 `LapLiveScreen.EndConfirmationDialog` 同款 Material3 `AlertDialog`：title `RacingTitleSmall`、text `UiTextBody/TextSecondary`、按钮 TextButton、按钮文字 `maxLines=1 + Ellipsis`

## 8. UI 层 — RecordsHomeScreen 接长按 + dialog

- [x] 8.1 编辑 `RecordsHomeScreen.kt` PerformanceView：顶部加 `var deleteCandidate by remember { mutableStateOf<DeleteCandidate?>(null) }`；RecentRuns `TrackTechRow` 加 `onLongClick` → 设 `DeleteCandidate.TestRecord(result.id, formatPerfDeleteHint(result))`
- [x] 8.2 LapsView 同模式：顶部加独立 `deleteCandidate` state；SESSION HISTORY `TrackTechRow` 加 `onLongClick` → 设 `DeleteCandidate.LapSession(session.sessionId, formatLapDeleteHint(session))`。两个 view 各自独立 state（切 SegmentedControl 时 dispose），更直接也更聚焦
- [x] 8.3 PerformanceView / LapsView 各自末尾（Composable scope，但在外层 Column 之后）加 `deleteCandidate?.let { DeleteHistoryDialog(...) }`，`onConfirm` 内部按 candidate 类型 narrow 后调对应 `viewModel.deleteTestRecord(id)` / `deleteLapSession(id)`，dialog dismiss
- [x] 8.4 实现 `formatPerfDeleteHint` / `formatLapDeleteHint` 派生函数（复用 `formatRunTimestamp` / `formatDate` / `formatLapMs`）；编译通过 `:feature:test:compileDebugKotlin`

## 9. 编译 + 单测

- [x] 9.1 worktree 内 `./gradlew :core:data:testDebugUnitTest` 全绿（含新 `TelemetryRepositoryDeleteSessionTest` 3 cases）
- [x] 9.2 worktree 内 `./gradlew :feature:test:testDebugUnitTest` 全绿（246 tests, 既有 + fake 同步零回归 + 新 `RecordsHomeScreenLongPressContractTest` 6 cases）。修复一处 baseline grep 测试与新 IO 路径冲突：`TestSessionViewModelTrackLoadingTest.testSessionViewModel_sourceDoesNotSpecifyDispatchersIOForTrackLoading` 收紧 grep 范围到 `_availableTracks.value =` 附近窗口（前 800/后 200 字符），保留 spec R4 内涵——track 加载不要走 Dispatchers.IO——同时不再误命中 deleteTestRecord/deleteLapSession 的合理 IO 路径
- [x] 9.3 `./gradlew :app:assembleDebug` 通过；APK 产出 `app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk`（62 MB）

## 10. 真机验证（华为 8KE0219522008434，需 user 授权）

- [x] 10.1 与 user 确认装机时间，等授权 → 2026-05-02 user 授权（"装到设备上 我正好有两条记录要删"）
- [x] 10.2 `adb -s 8KE0219522008434 install -r app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk` → Success（Performing Streamed Install）
- [x] 10.3 PERFORMANCE 删除路径：user 删 2 条 PERFORMANCE（5/1 18:47 + 5/1 22:55）。db 对账 ✅：`test_records` 5→3 行 / `telemetry/` 11→9 个 .bin / `binaryFilePath` 命中 `/telemetry/` 白名单。**残留 baseline 不一致**：`telemetry_sessions` PERFORMANCE_TEST 行未清 → 沉淀延期 memo `docs/design/perftest-cascade-orphan-cleanup-deferred.md`，§12.5 backlog
- [x] 10.4 LAPS 删除路径：user 删 1 条天府 lap session（`06738aa7-...` 5/1 16:13）。db 对账 ✅ cascade 三件套全清：
  - `telemetry_sessions` LAP_SESSION 6→5 行（天府 2→1，泊寓 4→4 没误伤）
  - `crossing_events` 6→5 个 sessionId 桶（`06738aa7` 的 1505 行 crossing 整批清零）
  - `telemetry/06738aa7-...bin` fs 文件已删（命中 `/telemetry/` 白名单）
- [x] 10.5 长按误触防御：`combinedClickable` 长按阈值 ≥500ms，user 验证流程顺畅未报误触

## 11. commit + ff-only 合回 + push

- [x] 11.1 worktree 内独立 4 commit：
  - `e765299` feat(data): TelemetrySessionDao + CrossingEventDao 加 delete 方法 + Fake 同步
  - `7c7954e` feat(data): TelemetryRepository.deleteSession cascade + TestResultRepository.deleteResultById wrapper + 单测
  - `c95cee7` feat(viewmodel): TestSessionViewModel 暴露 deleteTestRecord / deleteLapSession
  - `dc69a7f` feat(ui): RecordsHomeScreen 列表行长按 → DeleteHistoryDialog 删除 + TrackTechRow onLongClick 参数
- [x] 11.2 worktree 内 `git fetch origin && git rebase feature/track-tech-v2` → up-to-date（worktree 期间主区无并行合回）
- [x] 11.3 rebase 后跑 `:core:data:testDebugUnitTest :feature:test:testDebugUnitTest :app:assembleDebug` 全绿
- [x] 11.4 主区 `git merge --ff-only feature/add-history-deletion` 完成（领先 origin 4 commit）
- [x] 11.5 主区编译确认 `:core:data:compileDebugKotlin :feature:test:compileDebugKotlin :app:compileDebugKotlin` 通过
- [x] 11.6 **需 user 显式确认才能 push**：`git push origin feature/track-tech-v2`（user 2026-05-02 拍板"可以 push"）
- [x] 11.7 看板 §5 状态改 done（`dc69a7f` 合回 commit）；§6 J round 3 行占用全部标 done
- [x] 11.8 清理 worktree：`git worktree remove .worktrees/add-history-deletion` + 删本地分支 `feature/add-history-deletion` 完成
- [x] 11.9 归档 round：`openspec archive add-history-deletion` 完成

## 12. follow-up backlog（不在本 round 实现）

- [ ] 12.1 `add-history-deletion-detail-screen-entry` — 在 `PerformanceResultScreen` / `LapSessionDetailScreen` 顶部 ⋮ 菜单加删除入口（user 拍板暂时不做）。**触发条件**：用户反馈"详情屏看了想删要 back 出去太麻烦"
- [ ] 12.2 `add-history-deletion-undo-snackbar` — 删除后 5-10 秒 Snackbar 显示 `已删除 · 撤销` 按钮，点撤销 → 恢复（需要 db transaction + binary 文件软删机制）。**触发条件**：用户误删反馈
- [ ] 12.3 `add-history-deletion-multi-select` — 列表头加多选 toolbar，批量删除。**触发条件**：用户记录数 > 50 条 + 反馈"一条一条删麻烦"
- [ ] 12.4 `align-record-deletion-rememberSaveable-state` — `deleteCandidate` 用 `rememberSaveable` 替换 `remember`，旋屏 / 配置变化保留 dialog state。**触发条件**：用户报旋屏 dialog 消失
- [ ] 12.5 `fix-perftest-cascade-double-write` / `cleanup-perftest-telemetry-session-orphan` — `TestResultRepository.deleteResult` cascade 不彻底：PERFORMANCE 测试记录在 `test_records` + `telemetry_sessions(sessionType=PERFORMANCE_TEST)` 双写，但 deleteResult 只清前者，留下 telemetry_sessions 孤儿行。**触发场景**：J round 真机验证 2026-05-02 db 对账发现。**完整设计 memo**：[`docs/design/perftest-cascade-orphan-cleanup-deferred.md`](../../../docs/design/perftest-cascade-orphan-cleanup-deferred.md)（9 章 + 推荐方案 B + migration sweep 设计 + 实施约束 6 条）。**立项触发条件**：用户主动开 round / Codex review 提到 / "PERFORMANCE 删了 LAPS 统计还在算它"反馈 / D round 顺手做
