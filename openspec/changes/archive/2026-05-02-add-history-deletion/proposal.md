## Why

V2 Records tab 已经把 PERFORMANCE 测试记录 + LAPS 圈速 session 接成真实数据列表（F round 落地），但**没有删除入口**。用户跑了几次测试后想清理失败 / 重复 / 测试用 session，目前必须直接清 app data → 全删，丢失所有历史。

加一个最小可用的"长按列表行 → AlertDialog 确认 → 删除"功能，让用户能选择性清单条记录。本 round 不引入批量删除 / 撤销 / 详情屏菜单等高级 UX，留给后续 round。

## What Changes

### 数据层（删除 API + cascade 清理）

- **PERFORMANCE 测试记录删除**：`TestResultRepository.deleteResult(entity)` 已存在（含 binary 文件 cascade，`/telemetry/` 路径白名单防穿越），cascade 主体复用；但 UI 层只持有 `recordId` 字符串、ViewModel 没注入 DAO，所以本 round MUST 在 `TestResultRepository` **新增一个 by-id 入口** `suspend fun deleteResultById(id: String)` 调既有 `getTestRecordById` + 复用 `deleteResult` 的 cascade 实现 — 避免把 DAO 边界泄漏到 feature 层
- **LAPS 圈速 session 删除**：新增 cascade 删除链路：
  - `TelemetrySessionDao` 新增 `@Delete suspend fun deleteSession(entity: TelemetrySessionEntity)`
  - `CrossingEventDao` 新增 `@Query("DELETE FROM crossing_events WHERE sessionId = :sessionId") suspend fun deleteCrossingsBySessionId(sessionId: String)`（cascade 关联 crossing 行）
  - `TelemetryRepository` 新增 `suspend fun deleteSession(sessionId: String)`（用现有 DAO 字段名 `sessionDao` / `crossingDao`）：
    1. 先读 entity：`sessionDao.queryBySessionId(sessionId) ?: return`
    2. 调 `crossingDao.deleteCrossingsBySessionId(sessionId)`
    3. 调 `sessionDao.deleteSession(entity)`
    4. 删 binary 文件（同样 `/telemetry/` 路径白名单）
  - `FakeTelemetrySessionDao` / `FakeCrossingEventDao` 同步新增 override（避免单测编译失败）

### UI 层（长按入口 + AlertDialog）

- **新建** `feature/test/.../ui/tracktech/components/DeleteHistoryDialog.kt`：复用 baseline Material3 `AlertDialog` 风格（参考 `LapLiveScreen.EndConfirmationDialog`），含 title / 副标 / `删除` 红色按钮 + `取消` 灰色按钮；统一供 PERFORMANCE row + LAPS row 复用
- **修改** `RecordsHomeScreen.kt`：
  - PERFORMANCE 子页 RecentRuns 列表：每行 `TrackTechRow` 加 `onLongClick` 触发 `DeleteHistoryDialog(target = result)`
  - LAPS 子页 SESSION HISTORY 列表：每行 `TrackTechRow` 同样加 `onLongClick` 触发 dialog
  - dialog 状态用普通 `remember<MutableState<DeleteCandidate?>>` 管理（本 round 不上 `rememberSaveable`，配置变化丢 state 是已知 trade-off — 见 design Decision 6 + tasks §12.4 follow-up backlog）；拍板"删除"后调 ViewModel + dialog dismiss；"取消"仅 dismiss
- **修改** `TrackTechRow`：添加可选 `onLongClick: (() -> Unit)? = null` 参数，使用 `combinedClickable`（默认 null 不影响现有调用方）

### ViewModel 层

- `TestSessionViewModel` 新增：
  - `fun deleteTestRecord(recordId: String)`：内部 `viewModelScope.launch(Dispatchers.IO) { testResultRepository.deleteResultById(recordId) }`（DAO 边界封装在 repository 层，ViewModel 不直接接 DAO）
  - `fun deleteLapSession(sessionId: String)`：内部 `viewModelScope.launch(Dispatchers.IO) { telemetryRepository.deleteSession(sessionId) }`
  - 删除后 Repository 的 Flow query 自动 emit 新 list（Room Flow 已是 reactive），UI 列表无需手动刷新

### 测试

- 新增 `TelemetryRepositoryDeleteSessionTest.kt`：3 cases —— 普通 cascade（session + crossings + binary 都清）/ 不存在 sessionId（不抛 / no-op）/ binary 路径外（不删错文件）
- 新增 `RecordsHomeScreenLongPressContractTest.kt`：grep RecordsHomeScreen.kt 字面量验证 `onLongClick` 出现在 RecentRuns / SESSION HISTORY 渲染处

不做的事（明确 out-of-scope）：

- 不做批量删除 / 多选 mode
- 不做撤销 / undo snackbar
- 不做 V2 cut-corner 自定义 dialog（baseline Material3 AlertDialog 够用）
- 不在 `PerformanceResultScreen` / `LapSessionDetailScreen` 详情屏加删除按钮（user 拍板"暂时只长按"）
- 不做导出后再删（清理就是清理）
- 不做"30 天回收站"机制
- 不改 Room schema（只加 DAO 方法）
- 不动 PERFORMANCE 测试记录的删除 repo（`deleteResult` 已存在）

## Capabilities

### New Capabilities

- `history-deletion`: V2 Records tab 列表行长按删除契约 —— PERFORMANCE 测试记录 + LAPS 圈速 session 的 cascade 删除（含 binary 文件 + crossing_events 行）+ AlertDialog 确认 UX

### Modified Capabilities

无（本 round 仅新增能力，不修改现有 spec）。

## Impact

### 受影响代码

- **修改**：
  - `core/data/src/main/java/com/blazepush/core/data/local/dao/TelemetrySessionDao.kt`（加 `@Delete`）
  - `core/data/src/main/java/com/blazepush/core/data/local/dao/CrossingEventDao.kt`（加 `@Query DELETE WHERE sessionId`）
  - `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`（加 `deleteSession(sessionId)` cascade，使用既有 `sessionDao.queryBySessionId` + `crossingDao.deleteCrossingsBySessionId`，DAO 字段名是 `sessionDao` / `crossingDao`，不要写成 `telemetrySessionDao` / `crossingEventDao`）
  - `core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt`（加 `deleteResultById(id)` wrapper，调既有 `testRecordDao.getTestRecordById` + 复用 `deleteResult` cascade，让 ViewModel 不直接接 DAO）
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt`（PERFORMANCE / LAPS 两处 row 加 `onLongClick` + dialog state）
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechRow.kt`（加 `onLongClick: (() -> Unit)? = null` 参数 + `combinedClickable`）
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`（加 `deleteTestRecord(recordId)` / `deleteLapSession(sessionId)` 方法，分别调 `testResultRepository.deleteResultById` / `telemetryRepository.deleteSession`）
  - 现有 `FakeTelemetrySessionDao` / `FakeCrossingEventDao` 同步 override（不重叠 ViewModel test 主路径）
- **新建**：
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/components/DeleteHistoryDialog.kt`（统一 dialog Composable）
  - `core/data/src/test/java/com/blazepush/core/data/repository/TelemetryRepositoryDeleteSessionTest.kt`（cascade 单测）
  - `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreenLongPressContractTest.kt`（grep contract）

### 不受影响

- `app/*`、其它 home screen
- `LapTimingEngine` / `GateCrossingDetector` / GPS / BLE 数据链路
- `PerformanceResultScreen` / `LapSessionDetailScreen` 详情屏
- Room schema（只加方法，不改 entity 字段）
- BLE / GPS / RaceChrono 协议
- 录制流程（写入侧 startSession / writeSample / endSession 不动）

### 协议兼容性

无协议改动。

### 双端

仅接收端（gps-app）改动；发射端（simulator）不动。

### 多 change 并行协同

`RecordsHomeScreen.kt` / `TestSessionViewModel.kt` / `TelemetryRepository.kt` 当前看板 §6 状态：

- A round（fix-lap-binary-ts-hygiene）仍 ongoing：改 `TelemetryRepository` 的 `activeSessionStartTs` property + `TestSessionViewModel.bridgeGpsToLapTiming` 内部 — **跟本 round 函数级不重叠**（A 改 startSession/endSession 内部；本 round 加新 deleteSession 方法）
- I round（add-realtime-lap-delta）已合回归档：本 round rebase 后干净拿到 `_realtimeDeltaState` 等改动
- `RecordsHomeScreen.kt` / `TestSessionViewModel.kt` 当前主区无并行 round 在改 → 独占

启动前看板 §5/§6 登记 + rebase A round 同源。

### 测试影响

- 新增 ~30 行 cascade 单测（依赖现有 in-memory Fake DAO，不引入 Robolectric）
- 现有 `:feature:test:testDebugUnitTest` + `:core:data:testDebugUnitTest` 全套 MUST 零回归
- 真机验证：华为 `8KE0219522008434`：
  1. Records → PERFORMANCE → 长按 RecentRun 行 → AlertDialog 弹出 → 取消 → 列表保持
  2. 长按再次弹 dialog → 确认删 → 列表实时刷新少了一行 → 切到 detail 验证 binary 文件已删
  3. Records → LAPS → 长按 SESSION HISTORY 行 → 同流程
  4. 验证删除 lap session 后该 session crossing_events 也被清（如能 inspect 数据库）
