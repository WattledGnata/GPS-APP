# track-presentation Specification

## Purpose
TBD - created by archiving change enhance-track-presentation. Update Purpose after archive.
## Requirements
### Requirement: TrackName 值对象承载多种写法

The system SHALL define a `TrackName` value class with three fields: `zh: String`, `en: String`, `abbr: String?`. UI 渲染默认显示 `zh`（中文全称）；`abbr` 与 `en` 在小空间或调试场景按需使用。`abbr` MUST 允许为 `null`（卡丁车与小赛道无官方缩写）。

`Track` 数据类的 `name` 字段 MUST 为 `TrackName` 类型。系统 MUST NOT 同时保留 `String` 类型的 `name` 字段或与新结构混用。

#### Scenario: TFIC 预置赛道的三种写法

- **WHEN** `PresetTrackCatalog.getTrack("preset-tfic-lpcc")` 被调用
- **THEN** 返回的 `Track.name` MUST 满足：`zh == "成都天府国际赛道"`、`en == "Chengdu Tianfu International Circuit"`、`abbr == "TFIC"`

#### Scenario: 卡丁车赛道无缩写

- **WHEN** 一条 preset 赛道在数据中无官方缩写
- **THEN** `Track.name.abbr` MUST 为 `null`，UI 在选用 `abbr` 的位置 MUST fallback 到 `en` 或 `zh`，禁止显示空字符串或 `"null"`

#### Scenario: UI 默认显示中文全称

- **WHEN** `LapsHomeScreen.CurrentTrackPanel` 或 `SelectTrackBottomSheet` 列表项渲染赛道名
- **THEN** 渲染文本 MUST 等于 `track.name.zh`

### Requirement: Track 删除 layoutName 字段

The system SHALL 从 `Track` 数据类移除 `layoutName: String?` 字段。来源语义 MUST 由 `source: TrackSource` 完全承载。`ReplayAlignedTrackCatalog` 拟合产物 MUST 通过 `source = TrackSource.Generated` 标识，禁止用任何字符串字段（如 `"REAL_TRACK_REPLAY"`）复刻旧 `layoutName` 用法。

下游消费方（日志、UI 条件判断、测试断言）原本读取 `layoutName` 的位置 MUST 改为读取 `source`。

#### Scenario: ReplayAlignedTrackCatalog 拟合产物的来源标识

- **WHEN** `ReplayAlignedTrackCatalog.getAllTracks()` 在 replay 资源加载成功后返回 TFIC 赛道
- **THEN** 该 `Track.source` MUST 为 `TrackSource.Generated`，且 `Track` 数据类 MUST NOT 存在任何形如 `layoutName` 的字符串字段

#### Scenario: PresetTrackCatalog 直返赛道的来源标识

- **WHEN** `PresetTrackCatalog.getAllTracks()` 返回 TFIC 赛道
- **THEN** 该 `Track.source` MUST 为 `TrackSource.Preset`

### Requirement: Track 提供 thumbnailAssetPath 字段与缺图 fallback

The system SHALL 在 `Track` 数据类提供 `thumbnailAssetPath: String?` 字段。一期 MUST 仅支持 asset 静态图：路径为相对 `feature/test/src/main/assets/` 的字符串（例如 `"track_thumbnails/chengdu_tianfu.png"`）。

UI 渲染缩略图时 MUST 处理三种情况：

1. `thumbnailAssetPath == null`
2. `thumbnailAssetPath` 非空但 asset 加载失败（文件不存在或解码失败）
3. asset 加载成功

情况 1 与 2 时 UI MUST 渲染 fallback（占位框 + `"NO PREVIEW"` 文字），禁止页面空白或抛异常。情况 3 时 UI MUST 渲染加载到的图。

#### Scenario: TFIC 缩略图正常加载

- **WHEN** `Track` 的 `thumbnailAssetPath = "track_thumbnails/chengdu_tianfu.png"` 且 asset 文件存在
- **THEN** `TrackThumbnail` Composable MUST 渲染该图，且渲染区高度 MUST 与占位 fallback 高度一致（120dp）

#### Scenario: 缩略图字段为 null

- **WHEN** `Track.thumbnailAssetPath == null`
- **THEN** `TrackThumbnail` Composable MUST 渲染 fallback 占位（cyan 描边 + `"NO PREVIEW"` 文字），不抛异常、不渲染空白

#### Scenario: 缩略图 asset 文件不存在

- **WHEN** `Track.thumbnailAssetPath = "track_thumbnails/missing.png"` 但 asset 不存在
- **THEN** `TrackThumbnail` Composable MUST 捕获 `IOException` 并渲染 fallback 占位，禁止崩溃

### Requirement: Track 提供 lengthKm 字段且不参与运行时计算

The system SHALL 在 `Track` 数据类提供 `lengthKm: Double` 字段（必填，不可空）。该值 MUST 由 preset 数据硬编码（来自国际汽联认定的赛道官方长度）。

`ReplayAlignedTrackCatalog` 在拟合时 MUST NOT 重新计算长度；`buildReplayAlignedTrack` 产物的 `lengthKm` MUST 等于其 fallback preset 的 `lengthKm`。

#### Scenario: TFIC 的官方长度

- **WHEN** `PresetTrackCatalog.getTrack("preset-tfic-lpcc")` 被调用
- **THEN** 返回的 `Track.lengthKm` MUST 等于 `3.260`

#### Scenario: replay 拟合后长度不变

- **WHEN** `ReplayAlignedTrackCatalog` 完成 replay 资源加载并返回 TFIC 赛道
- **THEN** 返回的 `Track.lengthKm` MUST 等于 preset fallback 的 `3.260`，禁止从 replay 样本重新累加计算

### Requirement: TFIC 预置数据契约

The system SHALL 在 `PresetTracks.kt` 提供单条 TFIC 预置赛道，字段值 MUST 严格匹配下表：

| 字段 | 值 |
|------|---|
| `id` | `"preset-tfic-lpcc"` |
| `name.zh` | `"成都天府国际赛道"` |
| `name.en` | `"Chengdu Tianfu International Circuit"` |
| `name.abbr` | `"TFIC"` |
| `lengthKm` | `3.260` |
| `thumbnailAssetPath` | `"track_thumbnails/chengdu_tianfu.png"` |
| `source` | `TrackSource.Preset` |

`referencePath`、`startFinishGate`、`sectorGates` 几何坐标 MUST NOT 在本 change 中变更（保持 `TrackCatalogTest` 已锁定的现有契约）。

#### Scenario: TFIC 预置数据完整

- **WHEN** 测试通过 `PresetTrackCatalog().getTrack("preset-tfic-lpcc")` 取出赛道
- **THEN** 字段值 MUST 全部匹配上表，且 `name` 类型 MUST 为 `TrackName`，`thumbnailAssetPath` MUST 为非空字符串

#### Scenario: TFIC 缩略图资产存在

- **WHEN** 构建后的 APK 被安装运行
- **THEN** asset 路径 `feature/test/src/main/assets/track_thumbnails/chengdu_tianfu.png` MUST 实际存在并可被 `AssetManager.open()` 成功加载

### Requirement: CurrentTrackPanel 渲染真实赛道信息

The system SHALL 升级 `LapsHomeScreen.CurrentTrackPanel` Composable，使其消费 `Track?` 参数（而非 `String` 名字）并渲染：

1. 标签 `"CURRENT TRACK"`（cyan 颜色，UiTextLabel 字号）
2. 赛道名称 = `track.name.zh`（TextPrimary 颜色，RacingTitleMedium 字号）
3. 长度 = `"%.3f km".format(track.lengthKm)`（位置在名称下方或缩略图旁，由实施时按效果图布局）
4. 缩略图区（120dp 高）= `TrackThumbnail(track.thumbnailAssetPath)`

`CurrentTrackPanel` MUST NOT 渲染 ★ 收藏图标（一期 Non-goal）。

`track == null` 时 MUST 渲染整体占位（标签 + `"NO TRACK SELECTED"` 文本 + 缩略图 fallback），禁止崩溃。

#### Scenario: 选中 TFIC 时 CurrentTrackPanel 渲染

- **WHEN** `currentSelectedTrack.value` 为 TFIC 赛道
- **THEN** `CurrentTrackPanel` MUST 渲染 `"CURRENT TRACK"` 标签 + `"成都天府国际赛道"` 文本 + `"3.260 km"` 距离 + TFIC 缩略图

#### Scenario: 当前未选中赛道

- **WHEN** `currentSelectedTrack.value` 为 `null`
- **THEN** `CurrentTrackPanel` MUST 渲染占位文本 `"NO TRACK SELECTED"` 与 fallback 缩略图占位框，禁止抛异常

#### Scenario: CurrentTrackPanel 不画收藏图标

- **WHEN** `CurrentTrackPanel` 渲染任意状态
- **THEN** 渲染树 MUST NOT 包含 `Icons.Filled.Star` / `Icons.Filled.StarBorder` 或任何收藏 ★ 视觉元素

### Requirement: SelectTrackBottomSheet 赛道选择 UI

The system SHALL 新建 `SelectTrackBottomSheet` Composable（位于 `feature/test/.../ui/tracktech/`），形态 MUST 满足：

1. 用 Material3 `ModalBottomSheet` 实现
2. 顶栏：标题 `"SELECT TRACK"` + 装饰条纹 + 关闭按钮（`Icons.Filled.Close`）
3. 列表：每项展示缩略图（左侧）+ 赛道名 `track.name.zh`（中部，RacingTitleMedium）+ 长度 `"%.3f km"`（中部下方，UiTextSmall）
4. 当前选中项 MUST 显示绿色 `"Current"` 标记（右侧）
5. 当前选中项卡片 MUST 用紫色描边高亮（`TrackTechColors.Purple`）
6. 列表数据来源：`testSessionViewModel.availableTracks.collectAsState()`
7. 当前选中识别：`testSessionViewModel.currentSelectedTrack.collectAsState()` 与列表项 `track.id` 比对

点击非当前项时 MUST 调用 `testSessionViewModel.selectTrack(track)` 并自动 `onDismiss` 关闭弹窗。

#### Scenario: 弹窗展开时显示所有可用赛道

- **WHEN** `SelectTrackBottomSheet` 被展开
- **THEN** 列表 MUST 渲染 `availableTracks` 的全部条目，每条按 (1)-(5) 形态显示

#### Scenario: 当前选中项视觉高亮

- **WHEN** `currentSelectedTrack.value` 为 TFIC 且 TFIC 在列表中
- **THEN** TFIC 列表项 MUST 显示紫色描边 + 绿色 `"Current"` 文本，其他项 MUST 不显示这两个高亮元素

#### Scenario: 选择新赛道触发切换

- **WHEN** 用户点击非当前选中项 `trackX`
- **THEN** `testSessionViewModel.selectTrack(trackX)` MUST 被调用，弹窗 MUST 关闭，`LapsHomeScreen.CurrentTrackPanel` MUST 在下一帧 recompose 显示 `trackX` 的信息

### Requirement: CHANGE TRACK 按钮接通真实切换

The system SHALL 修改 `LapsHomeScreen` 中 `CHANGE TRACK` 按钮（当前 L139-151）的点击行为：

- 移除 `Toast.makeText(context, "Track selection — placeholder for future round", ...)` 占位
- 改为弹出 `SelectTrackBottomSheet`

**CHANGE TRACK 按钮**的 `onClick` lambda MUST NOT 保留 `"Track selection — placeholder for future round"` 这条占位 toast，也 MUST NOT 在按钮相关代码块中遗留 `TODO` 注释表明"未来实现"。注意：本约束**仅针对 CHANGE TRACK 按钮**；同文件 `START LAP SESSION` 按钮的占位 toast `"Lap session entry — placeholder for future round"` 不在本 change 范围、MUST 保留不动。

#### Scenario: 点击 CHANGE TRACK 弹出选择器

- **WHEN** 用户点击 `CHANGE TRACK` 按钮
- **THEN** `SelectTrackBottomSheet` MUST 被展开为底部弹窗，禁止显示占位 toast

### Requirement: TestSessionViewModel 持有当前选中赛道状态

The system SHALL 在 `TestSessionViewModel` 增加：

```kotlin
val currentSelectedTrack: StateFlow<Track?>
fun selectTrack(track: Track)
```

`currentSelectedTrack` 初始值 MUST 为 `availableTracks` 加载完成后的第一条（与现有 `LapsHomeScreen.kt:72` fallback 行为对齐：`availableTracks.firstOrNull()`）。当 `availableTracks` 为空时初始值 MUST 为 `null`。

`selectTrack(track)` 调用后，`currentSelectedTrack.value` MUST 立即更新为参数 `track`，并通过 StateFlow 推送给所有订阅者。

新建独立 ViewModel 持有此状态 MUST NOT 被采用——状态归属与 `availableTracks`、`lapRunConfig` 同一 VM。

#### Scenario: ViewModel 启动后默认选中第一条

- **WHEN** `TestSessionViewModel` 完成初始化、`availableTracks` 已加载至少一条
- **THEN** `currentSelectedTrack.value` MUST 等于 `availableTracks.value.first()`

#### Scenario: selectTrack 更新状态

- **WHEN** 调用 `selectTrack(trackX)`，其中 `trackX` 在 `availableTracks` 中
- **THEN** 下一次读取 `currentSelectedTrack.value` MUST 等于 `trackX`，且 StateFlow MUST 推送新值给已收集的 collectors

### Requirement: Records tab LAPS segment 消费真实赛道数据

The system SHALL 在 `RecordsHomeScreen.kt` 的 `LapsView`（`selectedSegment == "LAPS"` 分支）中替换 `placeholderTrackRecord` 的 `trackName` 与 `length` 字段来源：

- `trackName` MUST 派生自 `currentSelectedTrack.value?.name?.zh`（`null` 时 fallback 为 `"—"`）
- `length` MUST 派生自 `currentSelectedTrack.value?.let { "%.3f km".format(it.lengthKm) }`（`null` 时 fallback 为 `"—"`）
- 范围外的 mock 字段（`bestLapTime` / `bestLapDate` / `direction` / `sessions` / `totalLaps`）MUST 保持不动

`LapsView` 中两个消费点 MUST 同步更新：

1. `CurrentTrackRecordCard(track = ..., record = ...)` 调用
2. `TrackTechRow(title = ..., subtitle = "${length} · ${direction}")` 调用

#### Scenario: 选中 TFIC 时 Records tab LAPS segment 显示真实数据

- **WHEN** `currentSelectedTrack.value` 为 TFIC 赛道、用户切换到 Records tab 并选中 LAPS segment
- **THEN** `CurrentTrackRecordCard` 与 `TrackTechRow` MUST 显示 `"成都天府国际赛道"` + `"3.260 km"`，而非 `"Shanghai Tianma"` / `"3.063 km"`

#### Scenario: 切换赛道后 Records tab 同步更新

- **WHEN** 用户在 Laps tab `SelectTrackBottomSheet` 中调用 `selectTrack(trackX)` 切换赛道，随后切到 Records tab LAPS segment
- **THEN** `CurrentTrackRecordCard` 与 `TrackTechRow` MUST 显示 `trackX.name.zh` 与 `trackX.lengthKm`，无需用户额外操作

### Requirement: Records tab CurrentTrackRecordCard 接入统一 TrackThumbnail

The system SHALL 在 `CurrentTrackRecordCard` 中替换原 `TrackPreviewStub(...)` 调用为 `TrackThumbnail(assetPath = track?.thumbnailAssetPath, ...)`，与 Laps tab `CurrentTrackPanel` 使用**同一**`TrackThumbnail` Composable。

`CurrentTrackRecordCard` 签名 MUST 扩展为接受 `Track?` 参数（用于 `TrackThumbnail`）+ `CurrentTrackRecord` 参数（保留 record 数据），即 `(track: Track?, record: CurrentTrackRecord)`。

替换后 `TrackPreviewStub` Composable（原定义于 `RecordsHomeScreen.kt:548-584`）MUST 在工程内无任何调用方，因此 MUST 被删除（避免死代码）。

`CurrentTrackRecordCard` 已存在的 ★ 收藏图标（原 `RecordsHomeScreen.kt:529-536`）MUST 保留不动 —— 与本 change 的收藏 Non-goal 一致：本 change 不做收藏功能化，也不做 opportunistic 删除。

#### Scenario: TFIC 缩略图在 Records tab 正常加载

- **WHEN** `currentSelectedTrack.value` 为 TFIC 且 `chengdu_tianfu.png` asset 存在
- **THEN** `CurrentTrackRecordCard` 右侧 MUST 渲染 `TrackThumbnail` 加载到的 TFIC 图，与 Laps tab `CurrentTrackPanel` 使用同一张图

#### Scenario: 缩略图缺失时 Records tab fallback

- **WHEN** `currentSelectedTrack.value?.thumbnailAssetPath` 对应的 asset 不存在（或 `null`）
- **THEN** `CurrentTrackRecordCard` 右侧 `TrackThumbnail` MUST 渲染 `"NO PREVIEW"` fallback（行为与 `CurrentTrackPanel` fallback 一致），禁止崩溃或空白

#### Scenario: TrackPreviewStub 已删除

- **WHEN** 本 change 全部 task 完成
- **THEN** 工程内 MUST NOT 存在 `TrackPreviewStub` 这个 Composable 定义或任何对它的调用

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

