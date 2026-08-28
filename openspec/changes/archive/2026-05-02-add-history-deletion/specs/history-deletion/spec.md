## ADDED Requirements

### Requirement: TelemetrySessionDao 必须暴露 @Delete 删除方法

`core/data/src/main/java/com/blazepush/core/data/local/dao/TelemetrySessionDao.kt` MUST 新增：

```kotlin
@Delete
suspend fun deleteSession(entity: TelemetrySessionEntity)
```

#### Scenario: 删除 session 实体

- **WHEN** 调用 `dao.deleteSession(entity)` 传入存在的 entity
- **THEN** 该 row 从 `telemetry_sessions` 表中删除
- **AND** 后续 `getSession(sessionId)` 返回 null

### Requirement: CrossingEventDao 必须暴露按 sessionId 删除关联 crossing 的方法

`core/data/src/main/java/com/blazepush/core/data/local/dao/CrossingEventDao.kt` MUST 新增：

```kotlin
@Query("DELETE FROM crossing_events WHERE sessionId = :sessionId")
suspend fun deleteCrossingsBySessionId(sessionId: String)
```

#### Scenario: 按 sessionId 清除关联 crossings

- **WHEN** session A 有 5 条 crossing，session B 有 3 条 crossing；调用 `deleteCrossingsBySessionId(A.id)`
- **THEN** session A 的 5 条 crossing 全部删除
- **AND** session B 的 3 条不受影响

### Requirement: TelemetryRepository.deleteSession 必须 cascade 清理 crossing + binary

`TelemetryRepository` MUST 新增：

```kotlin
suspend fun deleteSession(sessionId: String)
```

行为约束：

1. 先 query entity 拿 `binaryFilePath`（如果 entity 不存在则 no-op 返回）
2. `crossingDao.deleteCrossingsBySessionId(sessionId)`（先关联表，`TelemetryRepository` 内 `CrossingEventDao` 字段实际命名为 `crossingDao`）
3. `sessionDao.deleteSession(entity)`（后主表，`TelemetryRepository` 内 `TelemetrySessionDao` 字段实际命名为 `sessionDao`）
4. 如果 `binaryFilePath` 非空 → `File.delete()`，**MUST 用 `canonicalPath.contains("/telemetry/")` 白名单防路径穿越**（与 `TestResultRepository.deleteResult` 同款安全策略）

#### Scenario: 普通 cascade 删除

- **WHEN** sessionId 存在 + crossing_events 有 5 行 + binary 文件存在于 `/telemetry/<sessionId>.bin`
- **THEN** 5 行 crossing 删除
- **AND** session entity 删除
- **AND** binary 文件 delete 调用

#### Scenario: 不存在的 sessionId

- **WHEN** 调用 `deleteSession("non-existent")`
- **THEN** 不抛异常
- **AND** 数据库其它 row 不受影响

#### Scenario: binary 路径不在 /telemetry/ 内不删

- **WHEN** entity.binaryFilePath = "/data/user/0/com.blazepush/files/other/x.bin"（路径不含 /telemetry/）
- **THEN** db 行删除
- **AND** binary 文件**不**被删除（safety boundary）

### Requirement: TestResultRepository 必须暴露 by-id 删除入口给 ViewModel

为避免把 DAO 边界泄漏到 feature 层（ViewModel 当前不持有 `TestRecordDao`），`TestResultRepository` MUST 新增：

```kotlin
suspend fun deleteResultById(id: String)
```

行为：内部调既有 `testRecordDao.getTestRecordById(id)` 拿到 entity 后，**复用既有 `deleteResult(entity)` cascade**（含 binary 文件 `/telemetry/` 路径白名单 + db 行删除）。entity 不存在时 no-op 返回。**MUST NOT** 重写已经过 review 的 PERFORMANCE 删除 cascade 逻辑。

#### Scenario: by-id 删除存在的记录

- **WHEN** 调用 `deleteResultById(existingId)`
- **THEN** entity 被加载并交给 `deleteResult(entity)` 处理
- **AND** db 行删除 + binary 文件 cascade（沿用现有 `/telemetry/` 白名单防御）

#### Scenario: by-id 删除不存在的记录

- **WHEN** 调用 `deleteResultById("non-existent")`
- **THEN** 不抛异常
- **AND** db 其它行不受影响

### Requirement: FakeTelemetrySessionDao / FakeCrossingEventDao 必须同步新增 override

为避免 `:core:data:testDebugUnitTest` 编译失败，所有现役 `class : TelemetrySessionDao` / `class : CrossingEventDao` 实现 MUST 同步 override 新方法。轻量返回（fake 类无需真删）：

```kotlin
override suspend fun deleteSession(entity: TelemetrySessionEntity) { /* no-op */ }
override suspend fun deleteCrossingsBySessionId(sessionId: String) { /* no-op */ }
```

#### Scenario: 既有单测编译通过

- **WHEN** 跑 `:core:data:testDebugUnitTest`
- **THEN** 全部既有测试编译 + 通过

### Requirement: TestSessionViewModel 必须暴露删除方法供 UI 调用

`TestSessionViewModel` MUST 新增：

```kotlin
fun deleteTestRecord(recordId: String)
fun deleteLapSession(sessionId: String)
```

行为：内部 `viewModelScope.launch(Dispatchers.IO)` 调对应 Repository 删除方法（PERFORMANCE 走 `testResultRepository.deleteResultById(id)` by-id wrapper，LAPS 走 `telemetryRepository.deleteSession(sessionId)`）；ViewModel MUST NOT 直接持有或调用 DAO（DAO 边界封装在 Repository 层）。删除完 Room Flow 自动 emit 新 list（reactive），UI 列表无需手动刷新。

#### Scenario: 删除 PERFORMANCE 测试记录

- **WHEN** 调用 `deleteTestRecord(id)`
- **THEN** 内部协程调 `testResultRepository.deleteResultById(id)`
- **AND** `recentRuns: StateFlow` 在下一个 emit 时不再含该 id

#### Scenario: 删除 LAPS 圈速 session

- **WHEN** 调用 `deleteLapSession(sessionId)`
- **THEN** 内部协程调 `telemetryRepository.deleteSession(sessionId)`
- **AND** `recentSessionsForCurrentTrack: StateFlow` 在下一个 emit 时不再含该 sessionId

### Requirement: TrackTechRow 必须支持可选的 onLongClick 参数

`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechRow.kt` MUST 新增可选参数：

```kotlin
onLongClick: (() -> Unit)? = null
```

实现 MUST 用 `combinedClickable(onClick = onClick, onLongClick = onLongClick)`（需要 `@OptIn(ExperimentalFoundationApi::class)`）；当 `onLongClick = null` 时行为等价于普通 `clickable(onClick)` —— **现有调用方零改动**。

#### Scenario: onLongClick = null 时不破坏现有 onClick 行为

- **WHEN** 调用方传 `onLongClick = null`（默认）+ `onClick = { ... }`
- **THEN** 点击仍触发 onClick
- **AND** 长按不触发任何回调

#### Scenario: onLongClick 非 null 时长按触发

- **WHEN** 调用方传 `onLongClick = { showDialog() }`
- **THEN** 长按 ≥500ms 后触发 onLongClick

### Requirement: RecordsHomeScreen 列表行长按必须弹删除确认 AlertDialog

`RecordsHomeScreen.kt` MUST 在 PERFORMANCE 子页 RecentRuns 列表 + LAPS 子页 SESSION HISTORY 列表 各自每行的 `TrackTechRow` 设置 `onLongClick`，触发显示删除确认 AlertDialog（统一 `DeleteHistoryDialog` Composable）。

确认逻辑：

- 用户点 `删除` 红色按钮 → 调对应 ViewModel 删除方法 → dismiss dialog
- 用户点 `取消` 灰色按钮 → 仅 dismiss dialog，**不**调删除方法
- dialog 状态用普通 `remember<MutableState<DeleteCandidate?>>` 管理（本 round 不上 `rememberSaveable`，配置变化丢 state 是已知 trade-off，见 design Decision 6 + follow-up backlog §12.4）

#### Scenario: PERFORMANCE row 长按弹 dialog 取消

- **WHEN** 用户长按 RecentRuns 第一行 + 点 `取消`
- **THEN** dialog 消失
- **AND** 该 row 仍在列表

#### Scenario: PERFORMANCE row 长按弹 dialog 确认删除

- **WHEN** 用户长按 RecentRuns 第一行 + 点 `删除`
- **THEN** 调 `viewModel.deleteTestRecord(id)`
- **AND** dialog 消失
- **AND** 列表下次 emit 该行已消失

#### Scenario: LAPS row 长按弹 dialog 同流程

- **WHEN** 用户长按 SESSION HISTORY 第一行 + 点 `删除`
- **THEN** 调 `viewModel.deleteLapSession(sessionId)`
- **AND** crossing_events 关联行 + binary 文件均被 cascade 清理
- **AND** 列表下次 emit 该行已消失

### Requirement: DeleteHistoryDialog Composable 必须统一 PERFORMANCE / LAPS 两边的删除确认 UI

新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/components/DeleteHistoryDialog.kt`，本 Composable MUST：

- 使用 baseline Material3 `AlertDialog`（参考 `LapLiveScreen.EndConfirmationDialog` 风格）
- title 文案 MUST 为 `"删除记录?"`（或类似简短中文确认问句）
- 副标 MUST 显示要删的记录摘要（如 `"0-100 km/h, 4.21 s, Today 10:35"` 或 `"5/2 23:48 · 4 圈 · 最佳 1:32.457"`）
- `删除` 按钮文字 MUST 用红色（`TrackTechColors.Red` 或等价红色）；`取消` 按钮文字 MUST 用 muted / 灰色
- 函数签名 MUST 接受参数：`candidate: DeleteCandidate, onConfirm: () -> Unit, onDismiss: () -> Unit`

#### Scenario: dialog 文案展示候选记录摘要

- **WHEN** dialog 打开传入 PERFORMANCE 候选
- **THEN** 副标显示该 record 的简短描述（type / value / time）

#### Scenario: 红色删除按钮 + 灰色取消按钮

- **WHEN** dialog 显示
- **THEN** 删除按钮文字使用 `TrackTechColors.Red` 或等价红色
- **AND** 取消按钮使用 muted 字色

### Requirement: 删除路径必须保证 binary 文件 /telemetry/ 路径白名单

`TelemetryRepository.deleteSession` MUST 在 binary 文件删除前同时满足"非空"+"路径白名单"两个守卫，缺一不可。

LAPS session 的 `binaryFilePath` MAY 是空字符串（首次 startSession 还没写入则不可能有 entity，但理论上要防御）或 `/data/user/0/com.blazepush/files/telemetry/<sessionId>.bin`。

`TelemetryRepository.deleteSession` 在 binary 文件删除前 MUST 满足下面两个守卫，缺一不可：

1. `binaryFilePath.isNotEmpty()` 检查 SHALL 通过
2. `File(binaryFilePath).canonicalPath.contains("/telemetry/")` 白名单 SHALL 通过 — 防路径穿越（避免 evil path 删 app 目录外文件）

PERFORMANCE 侧 `TestResultRepository.deleteResult` 已有此防御（line 107），本 round MUST NOT 修改 PERFORMANCE 侧实现。

#### Scenario: binaryFilePath 空不抛

- **WHEN** entity.binaryFilePath = ""
- **THEN** 跳过文件删除步骤，db 行仍删除

#### Scenario: 路径穿越防御

- **WHEN** entity.binaryFilePath = "/etc/passwd"（白名单不含 /telemetry/）
- **THEN** **不**调用 `File.delete()`
- **AND** db 行仍删除

### Requirement: 删除后列表必须自动刷新（依赖 Room Flow 反应性）

删除完成后，UI 列表的下一个 emit MUST 不再含已删除项。本 round **不**显式触发 refresh / invalidate / refetch —— 依赖 Room `Flow<List<...>>` 的天然反应性（既有 query 已是 Flow，删除后自动 re-emit）。

#### Scenario: PERFORMANCE 删除后 recentRuns Flow 自动减少一项

- **WHEN** `viewModel.deleteTestRecord(id)` 完成
- **THEN** `recentRuns: StateFlow<List<TestResultSummary>>` 在 ≤500ms 内 emit 不含 id 的新 list

#### Scenario: LAPS 删除后 recentSessionsForCurrentTrack Flow 自动减少一项

- **WHEN** `viewModel.deleteLapSession(sessionId)` 完成
- **THEN** `recentSessionsForCurrentTrack: StateFlow<List<TelemetrySession>>` 在 ≤500ms 内 emit 不含该 sessionId 的新 list

### Requirement: 详情屏（PerformanceResultScreen / LapSessionDetailScreen）不在本 round 改动

为保持本 round 最小 scope，详情屏 MUST NOT 加删除按钮 / 菜单。任何删除入口仅在列表行长按。

#### Scenario: 详情屏 diff 为空

- **WHEN** 本 round 全部 commit 完成后
- **THEN** `PerformanceResultScreen.kt` / `LapSessionDetailScreen.kt` 的 git diff 为空
