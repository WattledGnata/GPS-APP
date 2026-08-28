## Why

`enhance-track-presentation` round 落地后，`LapsHomeScreen.kt:NEARBY TRACKS` 区块仍是硬编码占位列表 `["Shanghai Tianma", "TFIC LPCC", "Coming soon"]` + `TrackTechRow` 列表项 + 死路 toast `"Track detail placeholder"`，§10 baseline 边界守护明确"本 round 不动"。一期不做"附近赛道"功能（无定位 / 无地理排序 / 无服务端下发），该区块的产品价值为零、视觉上也是 round 后视觉密度被削弱（CURRENT TRACK 卡片下方接连两段 placeholder：RECENT BEST + NEARBY TRACKS）。

设计稿（用户提供）已确认替代方案：把 NEARBY TRACKS 区块改成 **RECENT TRACKS 横滑缩略图卡片**——展示用户最近选过的赛道（DataStore 持久化、时间倒序、自动去重、滚动覆盖最多 5 条），每张卡片含缩略图 + 中文名 + 距离，当前选中项紫色描边高亮，末尾 `VIEW ALL` 复用现有 `SelectTrackBottomSheet`。**视觉上更接近赛车 app 习惯（"我刚跑过哪几条"），交互上提供切换赛道的快捷入口（不必每次开 SELECT TRACK 弹窗），数据上消化"用户切换历史"这个本来就该追踪的事实**。

## What Changes

### Capability 1：`track-presentation`（modified）

#### 数据层

- **新增** `RecentTracksStoreApi` 接口 + `RecentTracksStore` 生产实现（`feature/test/.../datastore/RecentTracksStore.kt`）：DataStore Preferences 持久化"最近选过的 trackId 列表"
  - **接口**：`interface RecentTracksStoreApi { val recentIds: Flow<List<String>>; suspend fun add(trackId: String) }` —— ViewModel 与下游调用方 MUST 绑接口、不绑具体类，便于注入 Fake 测试
  - **生产实现**：`class RecentTracksStore : RecentTracksStoreApi`，双入口构造（`internal constructor(DataStore<Preferences>)` 主构造 + `constructor(Context)` 生产入口）
  - 存储：`Preferences.Key<String>("recent_track_ids")`，序列化为 `","` 拼接的 trackId 列表
  - 写：`add(trackId)` —— 头部插入、自动去重（移除既有相同 ID）、滚动覆盖最多 5 条（超出尾部丢弃）
  - 读：`recentIds: Flow<List<String>>` —— 时间倒序（最近选的在前）
- **新增** `FakeRecentTracksStore`（`feature/test/src/test/java/com/blazepush/feature/test/datastore/FakeRecentTracksStore.kt`）实现 `RecentTracksStoreApi`，in-memory MutableStateFlow 模拟生产语义，供所有 ViewModel test helper 注入
- **新增** `feature/test/build.gradle.kts` 依赖：`androidx.datastore:datastore-preferences`

#### ViewModel 层

- **`TestSessionViewModel` 修改**：
  - 构造函数加 `recentTracksStore: RecentTracksStoreApi` 参数（**MUST 绑接口、不绑具体类**；DI 注入）
  - 顶层加 `_recentTrackIds: StateFlow<List<String>>` —— 由 `init` block collect `recentTracksStore.recentIds` 推送
  - `selectTrack(track)` 内部追加 `viewModelScope.launch { recentTracksStore.add(track.id) }` —— **每次切换都触发持久化写**
  - `availableTracks` 加载完成时不再自动追加初始赛道到 RECENT（避免假"已选过"假象）
- **`AppModule.kt` 修改**：
  - 注册 `single<RecentTracksStoreApi> { RecentTracksStore(androidContext()) }`（接口为 key、生产实现为 value）
  - `TestSessionViewModel` 构造参数注入 `recentTracksStore = get()`（resolve 接口）

#### UI 层

- **新建** `RecentTracksStrip`（`feature/test/.../ui/tracktech/RecentTracksStrip.kt`）：
  - 横滑 `LazyRow`，最多 5 张赛道卡片 + 末尾 1 个 `VIEW ALL` 卡片
  - 单卡片：缩略图（约 96×64dp，复用 `TrackThumbnail`）+ `track.name.zh`（RacingTitleMedium / maxLines=1 + Ellipsis）+ `"%.3f km"` 长度（UiTextSmall TextMuted / maxLines=1 + Ellipsis）
  - 当前选中项卡片（`track.id == currentTrack?.id`）：紫色 1dp 描边高亮（`TrackTechColors.Purple`）
  - **不画 ★**（与 `enhance-track-presentation` Non-goal 一致：收藏一期不做）
  - **不要 Custom 卡片**（一期不做自定义赛道入口）
  - `VIEW ALL` 卡片：cyan 描边、内容 "VIEW ALL"，点击触发 `SelectTrackBottomSheet`（与 CHANGE TRACK 按钮 / CurrentTrackPanel 卡片复用同一弹窗）
  - 卡片点击：调用 `testSessionViewModel.selectTrack(track)` 切换当前赛道（与 SELECT TRACK 弹窗内的 `onTrackSelected` 行为一致）
- **`LapsHomeScreen.kt` 修改**：
  - 删除 `NEARBY TRACKS` 区块（L185-202 当前 `Text("NEARBY TRACKS") + listOf(...).forEach { TrackTechRow(...) }`）
  - 在原位置插入 `Text("RECENT TRACKS") + RecentTracksStrip(...)` 调用
  - section header `"NEARBY TRACKS"` → `"RECENT TRACKS"`
  - 主 Composable 顶部新增 `val recentTrackIds by testSessionViewModel.recentTrackIds.collectAsState()`
  - `RecentTracksStrip` 收 `recentTrackIds` + `availableTracks` + `currentTrack` + 两个 callback（onTrackClick / onViewAll）

### Non-goals（明确划出本 change 之外）

- **不做赛道收藏**：与 `enhance-track-presentation` 一致，无 ★ 收藏图标
- **不做附近赛道**：本 change 不引入定位、地理排序、距离用户位置计算等任何"附近"语义
- **不做自定义赛道**：无 Custom 卡片入口；自定义 / 服务端下发赛道留给后续 change
- **不做 RECENT 上限可配置**：上限固定 5 条，写死，未来要改再开 change
- **不做 RECENT 列表手动管理**（删除某条 / 置顶 / 重排）：DataStore 自动滚动覆盖即可
- **不动 Records tab**：Records LAPS segment 的 CURRENT TRACK RECORD 仍然 follow `currentSelectedTrack`（数据真实化已在上 round 完成）
- **不动 SelectTrackBottomSheet 现有 API**：本 change 仅复用，不改其参数 / 行为

## Capabilities

### New Capabilities

（无 —— 复用 `track-presentation`）

### Modified Capabilities

- `track-presentation`：替换 NEARBY TRACKS 占位为 RECENT TRACKS 横滑卡片；新增 DataStore 持久化用户最近选过的赛道；新增 RecentTracksStrip 组件契约。`enhance-track-presentation` 中 Requirement "NEARBY TRACKS 区块保留不动（Non-goal 边界）" 在本 round REMOVED（解除边界守护）

## Impact

### 受影响模块路径

- `feature/test/src/main/java/com/blazepush/feature/test/datastore/RecentTracksStore.kt`（新建）
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecentTracksStrip.kt`（新建）
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapsHomeScreen.kt`（删 NEARBY 区块、接 RECENT 横滑、collectAsState recentTrackIds）
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`（构造参数 + 顶层 field + selectTrack 内部 + init block）—— **本 round 与 round A `fix-lap-binary-ts-hygiene` 共享此文件，函数级不重叠**（A 改 `bridgeGpsToLapTiming:562` 1 行公式；本 round 不动该函数）。详细协同登记见 `docs/implementation-design/parallel-change-collab.md` §5/§6
- `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`（注册 RecentTracksStore + TestSessionViewModel 构造参数）
- `feature/test/build.gradle.kts`（加 `androidx.datastore:datastore-preferences` 依赖）

### 测试

- 新增 `feature/test/src/test/java/com/blazepush/feature/test/datastore/RecentTracksStoreTest.kt`（DataStore 行为：add 头插 / 自动去重 / 5 条上限滚动覆盖 / 跨进程恢复）
- 扩展 `TestSessionViewModelTrackSelectionTest.kt`：`selectTrack(track)` 调用后 RecentTracksStore.add 被触发；recentTrackIds StateFlow 推送一致

### 协议兼容性

- 不涉及 RaceChrono BLE 协议
- 不涉及 replay JSON/VBO 协议
- DataStore Preferences 是本地持久化，无外部协议

### 双端任务划分

- 仅接收端 gps-app 改动，simulator 不涉及

### 并行 round 协同

- **本 round** = round E（看板 §5）
- 共享文件 `TestSessionViewModel.kt` 在 §6 已登记 ongoing
- 与 round A 函数级不重叠，可并行；rebase 风险低（A line 562 vs E ~line 100-160 顶层 field + ~line 124 selectTrack）
- 真机验证按 §4.2 串行规则，准备 install 前在对话窗口告知 user
- push 顺序由 user 拍板（§4.1）
