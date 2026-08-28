# Track Presentation Capability — RECENT TRACKS Strip Round

## REMOVED Requirements

### Requirement: NEARBY TRACKS 区块保留不动（Non-goal 边界）

**Reason**: 上一 round `enhance-track-presentation` 通过逐字符冻结 NEARBY TRACKS 区块（`LapsHomeScreen.kt:174-195`）避免范围漂移；该区块本身是 `add-track-tech-app-shell` round 留下的硬编码占位（`["Shanghai Tianma", "TFIC LPCC", "Coming soon"]` + 死路 toast `"Track detail placeholder"`），无产品价值。本 round 是该区块的正式替代实施 —— 解除冻结、用 RECENT TRACKS 横滑卡片彻底替换。

**Migration**: `LapsHomeScreen.kt` 中原 NEARBY TRACKS 区块的 section header `"NEARBY TRACKS"`、占位列表 `["Shanghai Tianma", "TFIC LPCC", "Coming soon"]`、`forEach { TrackTechRow(...) }` 列表项渲染、占位 toast `"Track detail placeholder"` 全部移除。替代品由本 capability 新增的 `Requirement: LapsHomeScreen 用 RECENT TRACKS 横滑替代 NEARBY 区块` 与 `Requirement: RecentTracksStrip 横滑卡片组件` 共同覆盖。

## ADDED Requirements

### Requirement: RecentTracksStore 持久化最近选过的赛道

The system SHALL 在 `feature/test/src/main/java/com/blazepush/feature/test/datastore/RecentTracksStore.kt` 提供 `RecentTracksStore` 类型，使用 `androidx.datastore:datastore-preferences` 持久化"用户最近选过的 trackId 列表"。

**测试友好的接口 + 双入口构造**（必须）：

- **抽象接口** `RecentTracksStoreApi`（位于同文件）：
  ```kotlin
  interface RecentTracksStoreApi {
      val recentIds: Flow<List<String>>
      suspend fun add(trackId: String)
  }
  ```
  ViewModel 构造参数类型 MUST 为 `RecentTracksStoreApi`（不绑定具体实现），便于测试注入 fake
- **生产实现**：`class RecentTracksStore : RecentTracksStoreApi`，提供两个构造：
  - **主构造**：`internal constructor(private val dataStore: DataStore<Preferences>)` —— 接收已构造好的 DataStore，便于真实行为测试注入 `PreferenceDataStoreFactory.create(scope, produceFile = { tmpFolder.newFile("recent_tracks.preferences_pb") })`
  - **生产入口**：`constructor(context: Context) : this(context.recentTracksDataStore)` —— 通过顶层 delegate `private val Context.recentTracksDataStore by preferencesDataStore(name = "recent_tracks")` 拿到 DataStore；DI 在 `AppModule.kt` 调此构造
- **测试 fake** `FakeRecentTracksStore` 位于 `feature/test/src/test/java/com/blazepush/feature/test/datastore/`（不进生产代码）：
  - 实现 `RecentTracksStoreApi` 接口
  - 内部用 `MutableStateFlow<List<String>>`，逻辑与生产 `RecentTracksStore` 一致（头插 + 去重 + 5 条滚动覆盖）
  - 提供给所有 `TestSessionViewModel` 直接构造的 test helper 用，避免引入真实 DataStore + tmpFile boilerplate
- **禁止**只暴露 Context 单入口（JVM 单测拿不到真实 Android Context、顶层 delegate 也不可替换）；**禁止** ViewModel 构造参数直接绑 `RecentTracksStore` 具体类（应绑接口）

存储契约：

- 单个 `Preferences.Key<String>("recent_track_ids")`，值为 trackId 列表的 `","` 拼接字符串（trackId 为 kebab-case ASCII，禁止包含 `","`）
- 上限：最多 5 条（常量 `MAX_RECENT_COUNT = 5`，写死）
- 顺序：时间倒序，最近调用 `add()` 的 trackId 在头部
- 去重：`add(trackId)` MUST 移除列表中已有的同 ID 项，再头部插入；同一 trackId 在列表中**最多出现 1 次**
- 滚动覆盖：`add()` 后若超出 5 条，MUST 从尾部丢弃多余项
- 操作：`suspend fun add(trackId: String)`（事务性 `dataStore.edit { }`）+ `val recentIds: Flow<List<String>>`（DataStore 自动 flow，跨进程恢复）

`add()` MUST NOT 阻塞 Main 线程（DataStore 内部 dispatch 到 IO 池，调用方在 `viewModelScope.launch` 内即可）。

#### Scenario: 首次启动 store 为空

- **WHEN** 应用首次安装启动、`RecentTracksStore.recentIds.first()` 被调用
- **THEN** 返回 `emptyList<String>()`，不抛异常

#### Scenario: add 头部插入新 trackId

- **WHEN** 当前 `recentIds = ["a", "b"]`，调用 `add("c")`
- **THEN** `recentIds.first()` MUST 等于 `["c", "a", "b"]`

#### Scenario: add 已存在的 trackId 自动去重 + 提到头部

- **WHEN** 当前 `recentIds = ["a", "b", "c"]`，调用 `add("b")`
- **THEN** `recentIds.first()` MUST 等于 `["b", "a", "c"]`（"b" 不重复出现，从中段提到头部）

#### Scenario: add 触发 5 条上限滚动覆盖

- **WHEN** 当前 `recentIds = ["a", "b", "c", "d", "e"]`（5 条已满），调用 `add("f")`
- **THEN** `recentIds.first()` MUST 等于 `["f", "a", "b", "c", "d"]`（"e" 从尾部丢弃）

#### Scenario: 跨进程重启恢复

- **WHEN** 进程内调 `add("x")` 并完成持久化，应用进程被杀重启
- **THEN** 重启后 `RecentTracksStore.recentIds.first()` 第一个值 MUST 包含 `"x"` 在头部

### Requirement: TestSessionViewModel 暴露 recentTrackIds 与 selectTrack 触发持久化

The system SHALL 在 `TestSessionViewModel` 增加：

```kotlin
val recentTrackIds: StateFlow<List<String>>
```

由 `init` block 内 `viewModelScope.launch { recentTracksStore.recentIds.collect { _recentTrackIds.value = it } }` 推送。

`TestSessionViewModel` 构造函数 MUST 新增 `recentTracksStore: RecentTracksStore` 参数（DI 注入）。

`fun selectTrack(track: Track)` 实现 MUST 在切换 `_currentSelectedTrack.value` 之外、追加 `viewModelScope.launch { recentTracksStore.add(track.id) }` 调用。即**每次切换赛道都触发 RECENT 列表持久化更新**。

`availableTracks` 加载完成时设置初始 `currentSelectedTrack` 的逻辑 MUST NOT 同时调 `selectTrack` 或直接写 RecentTracksStore —— 初始化只设 `_currentSelectedTrack.value`，避免在 user 没主动切换的情况下污染 RECENT 列表。

#### Scenario: ViewModel 启动后 collect store

- **WHEN** ViewModel 实例化、`init` block 完成
- **THEN** `recentTrackIds.value` MUST 等于 `RecentTracksStore.recentIds.first()` 的返回值（首次推送）

#### Scenario: selectTrack 触发持久化写

- **WHEN** 调用 `viewModel.selectTrack(trackX)`
- **THEN** `_currentSelectedTrack.value` MUST 更新为 `trackX`，且 `RecentTracksStore.add(trackX.id)` MUST 被调用一次

#### Scenario: store 异步推送后 recentTrackIds 更新

- **WHEN** `RecentTracksStore.recentIds` flow 推送新值（例如另一处调用 `add` 后）
- **THEN** `recentTrackIds.value` MUST 在下次 collect 后更新为推送的新列表

#### Scenario: 初始化不污染 RECENT

- **WHEN** ViewModel 启动 + `availableTracks` 加载完成 + `_currentSelectedTrack.value` 自动 fallback 到 `availableTracks.firstOrNull()`
- **THEN** 此过程 MUST NOT 触发 `RecentTracksStore.add` —— RECENT 列表 MUST 仍为空（首次启动场景）或保持原状

### Requirement: RecentTracksStrip 横滑卡片组件

The system SHALL 在 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecentTracksStrip.kt` 新建 `RecentTracksStrip` Composable，签名：

```kotlin
@Composable
fun RecentTracksStrip(
    recentTrackIds: List<String>,
    availableTracks: List<Track>,
    currentTrackId: String?,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier,
)
```

**round 反馈修订**（实施期 user 回归）：

1. **签名删 `onViewAllClick`** —— VIEW ALL 入口移到 `LapsHomeScreen` section header 右侧文字按钮（不在 strip 内部），strip 只负责赛道卡片渲染
2. **空 RECENT fallback 显示所有 availableTracks** —— 首次启动 / RECENT 历史为空时，横滑直接显示 `availableTracks` 全部赛道（避免初始视觉空旷）；RECENT 一旦有历史则按时间倒序显示

行为契约：

- 内部 `recentTrackIds.mapNotNull { id -> availableTracks.firstOrNull { it.id == id } }` 解析为 Track 列表，自动 filter 掉在 `availableTracks` 中找不到的 stale trackId
- **若解析后列表为空 → fallback 显示 `availableTracks`**（修订 2）；列表非空 → 按解析顺序渲染
- 渲染用 `LazyRow`，item 顺序：解析后的 RECENT 卡片或 fallback 的 availableTracks 卡片（按 `recentTrackIds` 顺序或 availableTracks 自然顺序），**不**追加 VIEW ALL 卡片（修订 1）
- **每张赛道卡片**：
  - 缩略图（约 96×64dp，复用 `TrackThumbnail` 组件、消费 `track.thumbnailAssetPath`）
  - `track.name.zh`（`TrackTechTypography.RacingTitleMedium`、`maxLines = 1`、`overflow = TextOverflow.Ellipsis`）
  - `"%.3f km".format(track.lengthKm)`（`TrackTechTypography.UiTextSmall` + `TrackTechColors.TextMuted`、`maxLines = 1`、`overflow = TextOverflow.Ellipsis`、不走 Mechanical 七段字体）
  - 当前选中识别（`track.id == currentTrackId`）：cut-corner 形状 + 紫色 1dp 描边（`TrackTechColors.Purple`）
  - 非当前：cut-corner 形状 + 灰色 1dp 描边（`TrackTechColors.BorderAlpha60`）
  - **MUST NOT** 渲染 `Icons.Filled.Star` 或任何 ★ 收藏视觉
- **MUST NOT** 渲染 "Custom" 占位卡片（一期不做自定义赛道）
- **MUST NOT** 渲染 VIEW ALL 卡片（修订 1：VIEW ALL 移到 LapsHomeScreen section header）
- 卡片点击行为：
  - 当前选中卡片 → onClick no-op（**不**调 `onTrackClick`）
  - 非当前卡片 → 调 `onTrackClick(track)`

#### Scenario: 渲染顺序（RECENT 非空）

- **WHEN** `recentTrackIds = ["a", "b", "c"]`、`availableTracks` 包含全部 3 条
- **THEN** `LazyRow` MUST 渲染 3 张赛道卡片（按 ["a", "b", "c"] 顺序），共 3 个 item，**不**追加 VIEW ALL 卡片

#### Scenario: 当前选中卡片紫框高亮

- **WHEN** `currentTrackId = "b"` 且 "b" 在 RECENT 列表中
- **THEN** "b" 卡片 MUST 显示紫色 1dp 描边、其他赛道卡片 MUST 显示灰色描边

#### Scenario: 点击非当前卡片触发 onTrackClick

- **WHEN** `currentTrackId = "a"`，user 点击 "b" 卡片
- **THEN** `onTrackClick(track_b)` MUST 被调用一次

#### Scenario: 点击当前选中卡片 no-op

- **WHEN** `currentTrackId = "a"`，user 点击 "a" 卡片
- **THEN** `onTrackClick` MUST NOT 被调用（不重复触发切换）

#### Scenario: stale trackId 自动 filter

- **WHEN** `recentTrackIds = ["preset-tfic-lpcc", "stale-deleted-track"]`、`availableTracks` 只含 `"preset-tfic-lpcc"`
- **THEN** `LazyRow` MUST 只渲染 1 张赛道卡片（TFIC），不抛异常、不渲染空白卡片

#### Scenario: 空 RECENT fallback 显示 availableTracks（修订 2）

- **WHEN** `recentTrackIds.mapNotNull(...)` 解析后为 `emptyList()`（首次启动 / 全部 stale ID 被 filter / RECENT 历史为空）
- **THEN** `LazyRow` MUST 渲染 `availableTracks` 全部赛道（按 availableTracks 自然顺序），避免视觉空旷；当前选中识别仍按 `currentTrackId` 紫框高亮

#### Scenario: 不渲染 ★ 收藏图标

- **WHEN** `RecentTracksStrip` 渲染任意状态
- **THEN** 渲染树 MUST NOT 包含 `Icons.Filled.Star` / `Icons.Filled.StarBorder` 或任何收藏 ★ 视觉

#### Scenario: 不渲染 Custom 占位卡片

- **WHEN** `RecentTracksStrip` 渲染任意状态（含空 RECENT）
- **THEN** 渲染树 MUST NOT 包含任何 "Custom" 文本或带虚线描边的占位卡片

### Requirement: LapsHomeScreen 用 RECENT TRACKS 横滑替代 NEARBY 区块

The system SHALL 在 `LapsHomeScreen.kt` 中替换原 NEARBY TRACKS 区块（被 REMOVED Requirement "NEARBY TRACKS 区块保留不动" 覆盖的占位代码）为 RECENT TRACKS 区块：

- 区块外层 Column MUST 用 `Modifier.fillMaxWidth().padding(horizontal = 16.dp)`（与 RECENT BEST / CURRENT TRACK 等其他 section 容器结构一致）
- 区块内 section header 用一个 `Row(modifier = Modifier.fillMaxWidth())`，包含：
  - 左侧：`Text("RECENT TRACKS")`（`TrackTechTypography.UiTextLabel` + cyan + maxLines=1 + Ellipsis）—— **不**加 `weight` 修饰，wrap content 即可
  - 中间：`Spacer(Modifier.weight(1f))` —— 拿全部剩余空间，把右侧元素推到 Row 末尾
  - 右侧：`Text("VIEW ALL")`（同 cyan UiTextLabel + `Modifier.clickable { showSelectTrackSheet = true }`）—— **MUST 真正贴 Row 右沿（即父 Column 右内沿 = 屏幕右 - 16dp）与 RECENT BEST 卡片右沿对齐**
- 区块下方调用 `RecentTracksStrip(recentTrackIds, availableTracks, currentTrack?.id, onTrackClick = { testSessionViewModel.selectTrack(it) })`（**不**传 `onViewAllClick`，VIEW ALL 已上移至 section header）
- VIEW ALL 文字按钮点击触发的 `SelectTrackBottomSheet` MUST 与 `CHANGE TRACK` 按钮 / `CurrentTrackPanel` 卡片点击触发的弹窗**同一实例**（`showSelectTrackSheet` 同一 state 控制）
- 原占位 toast `"Track detail placeholder"` MUST 完全移除，不在生产代码中保留任何形式
- 主 Composable 内新增 `val recentTrackIds by testSessionViewModel.recentTrackIds.collectAsState()`

#### Scenario: section header 改名

- **WHEN** Laps tab 渲染
- **THEN** 原 `"NEARBY TRACKS"` 文本 MUST NOT 出现；`"RECENT TRACKS"` 文本 MUST 出现

#### Scenario: 占位 toast 移除

- **WHEN** 本 round 全部 task 完成
- **THEN** `LapsHomeScreen.kt` 中 MUST NOT 包含字符串 `"Track detail placeholder"`

#### Scenario: VIEW ALL 复用 SelectTrackBottomSheet

- **WHEN** user 点击 section header 右侧 `VIEW ALL` 文字按钮
- **THEN** `showSelectTrackSheet` MUST 设为 `true`、`SelectTrackBottomSheet` 弹出，行为与点击 `CHANGE TRACK` 按钮 / `CurrentTrackPanel` 卡片完全一致

#### Scenario: VIEW ALL 与 RECENT BEST 容器右沿对齐

- **WHEN** Laps tab 渲染、screen 宽度任意
- **THEN** `VIEW ALL` 文字右沿 MUST 与 `RECENT BEST` 卡片右沿、`CHANGE TRACK` 按钮右沿、`CurrentTrackPanel` 卡片右沿在同一 x 坐标（即父 Column 16dp horizontal padding 决定的 `屏幕宽 - 16dp` 处）—— 不允许 VIEW ALL 文字偏左于该 x 坐标

#### Scenario: RECENT 卡片切换赛道

- **WHEN** user 点击 RECENT 横滑中某条非当前赛道卡片
- **THEN** `testSessionViewModel.selectTrack(track)` MUST 被调用、`currentSelectedTrack` 更新、`CurrentTrackPanel` 在下一帧 recompose 显示新赛道
