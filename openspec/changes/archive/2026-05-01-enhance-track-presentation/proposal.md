## Why

当前 `Track`（`feature/test/.../model/track/Track.kt`）只承载圈速计时几何（`referencePath` + `startFinishGate` + `sectorGates`），UI 层呈现完全占位：`LapsHomeScreen.CurrentTrackPanel`（`LapsHomeScreen.kt:201-239`）只画一个 120dp 高的灰盒 + `"TRACK PREVIEW · PLACEHOLDER"` 文本，`CHANGE TRACK` 按钮（L139-151）点击只 toast `"placeholder for future round"`，赛道选择 UI 不存在。设计稿 `SELECT TRACK` 弹窗已确认形态（霓虹缩略图 + 中文全称 + 距离 + 当前选中紫色描边），需要把"赛道"从一个无可视化的几何契约升级为有视觉身份的产品概念。

同时 `Track.layoutName: String?` 字段语义混乱：`PresetTracks.kt:15` 把它当布局名（`"RaceChrono RCZ"`），`ReplayAlignedTrackCatalog.kt:92` 把它当来源标识（`"REAL_TRACK_REPLAY"`），两个语义在同一字段上撞车。基于"不同布局算不同 Track，从 ID 隔离"的产品决策，该字段失去存在理由，由 `source: TrackSource` 接管来源语义、由独立字段表达视觉/计量信息。

## What Changes

### Capability 1：`track-presentation`（赛道数据契约 + UI 呈现：Laps tab + Records tab）

#### 数据契约（model/track + repository）

- **BREAKING**：`Track.name: String` → `Track.name: TrackName`，新增值对象 `TrackName(zh: String, en: String, abbr: String?)`
  - UI 默认显示 `name.zh`（中文全称）；`abbr`/`en` 留给小空间或调试场景，`abbr` 可空（卡丁车/小赛道无官方缩写）
  - **不允许**保留 `String name` 字段或以 String 形式混用
- **BREAKING**：删除 `Track.layoutName: String?` 字段
  - 来源语义由 `source: TrackSource` 接管（`ReplayAlignedTrackCatalog` 的 `"REAL_TRACK_REPLAY"` 标识由 `source = TrackSource.Generated` 表达）
  - **不允许**新增字段复刻旧 `layoutName` 用法
- 新增 `Track.thumbnailAssetPath: String?`
  - 一期只支持 asset 静态图（路径相对 `feature/test/src/main/assets/`，例如 `track_thumbnails/chengdu_tianfu.png`）
  - **缺图时 UI 必须 fallback** 到占位 vector 或纯色框，**禁止**让页面空白或崩
- 新增 `Track.lengthKm: Double`
  - 由用户提供的官方距离（国际汽联认定的赛道自身属性）
  - preset 数据硬编码；`ReplayAlignedTrackCatalog` 拟合后**不重新计算长度**
- TFIC preset 数据更新：`zh="成都天府国际赛道"`、`en="Chengdu Tianfu International Circuit"`、`abbr="TFIC"`、`lengthKm=3.260`（用户提供的国际汽联认定官方距离）、`thumbnailAssetPath="track_thumbnails/chengdu_tianfu.png"`

#### UI 呈现（feature/test/.../ui/tracktech）

- **增强** `LapsHomeScreen.CurrentTrackPanel`（L201-239）：
  - 替换 `"TRACK PREVIEW · PLACEHOLDER"` 灰盒为真实缩略图渲染区
  - 渲染顺序：`CURRENT TRACK` 标签 → `name.zh` → `lengthKm` 格式化为 `"X.XXX km"` → 缩略图
  - **不画收藏 ★**（一期不做）
- **新建** `SelectTrackBottomSheet` Composable：
  - 标题 `SELECT TRACK` + 装饰条纹 + 关闭按钮
  - 列表项：缩略图 + `name.zh` + `lengthKm` + 当前项 `Current` 绿色标记
  - 当前选中项紫色描边高亮
  - 底部弹窗形态（参考效果图）
- **接通** `CHANGE TRACK` 按钮（L139-151）：toast 替换为 `SelectTrackBottomSheet` 弹窗触发，选择后真实更新当前赛道（**不允许**只做 UI 占位、不接真实切换）
- ViewModel 层（`TestSessionViewModel` 或新增）持有 `currentSelectedTrack: StateFlow<Track?>`，`CurrentTrackPanel` 跟随状态变化

#### Records tab LAPS segment 数据真实化 + 资产接入（feature/test/.../ui/tracktech/RecordsHomeScreen.kt）

**核心判断**：mock 数据真实化 与 资产接入 是两层独立验收，不能因为当前只有一条 TFIC 就把"mock 改成 TFIC"等同于"资产已经被消费"，必须分别落地。

- **mock 数据真实化**：`placeholderTrackRecord`（L676-684）的 `trackName` 与 `length` 字段 MUST 从 mock `"Shanghai Tianma"` / `"3.063 km"` 改为消费 `currentSelectedTrack` 派生：
  - `trackName = currentTrack?.name?.zh ?: "—"`
  - `length = currentTrack?.let { "%.3f km".format(it.lengthKm) } ?: "—"`
  - 范围外的 mock 字段（`bestLapTime` / `bestLapDate` / `direction` / `sessions` / `totalLaps`）**保留不动**
  - `LapsView` 中 `CurrentTrackRecordCard`（L391）+ 紧邻的 `TrackTechRow`（L393-400）消费方同步更新
- **资产接入**：`CurrentTrackRecordCard`（L466-545）内 `TrackPreviewStub(...)` 调用（L537-541）MUST 替换为统一的 `TrackThumbnail(assetPath = currentTrack?.thumbnailAssetPath, ...)`；缺图 fallback 与 Laps tab 一致
- **TrackPreviewStub 移除**：替换后该 Composable（L548-584）在工程内已无任何调用，MUST 删除（避免死代码）
- **`CurrentTrackRecordCard` 签名扩展**：从 `(track: CurrentTrackRecord)` 扩展为 `(track: Track?, record: CurrentTrackRecord)`，`Track?` 用于 `TrackThumbnail`，`CurrentTrackRecord` 保留承载 record 数据
- **★ 收藏图标（L529-536）保留不动**：与本 change 收藏 Non-goal 一致 —— Records tab ★ 是本 change 之前已存在的 UI 元素，本 change 不做"收藏功能化"也不做"opportunistic 删除"

#### Non-goals（明确划出本 change 之外）

- **不做附近赛道**：`LapsHomeScreen.kt:174-195` `NEARBY TRACKS` 区块**保留不动**，硬编码占位 `["Shanghai Tianma", "TFIC LPCC", "Coming soon"]` 一期不动；不引入定位、排序、距离用户位置计算
- **不做收藏**：CURRENT TRACK 卡片右上角不画占位 ★
- **不做缩略图运行时生成**：一期只用大模型直出 PNG 内置；不接地图 API 底图、不做 Compose Canvas 矢量渲染
- **不做服务端下发赛道**：preset 仅 TFIC 一条；扩展点留给后续 change
- **不改公共协议**：RaceChrono BLE、replay JSON/VBO 协议字段不动

## Capabilities

### New Capabilities

- `track-presentation`：赛道数据契约（`Track`/`TrackName`/`TrackPath`/`TimingGate`/`TrackSource`）与 UI 呈现层（Laps tab `CurrentTrackPanel` 增强、`SelectTrackBottomSheet` 新建、Records tab LAPS segment `CurrentTrackRecordCard` 真实化与 `TrackThumbnail` 资产接入、`TrackPreviewStub` 删除、当前选中状态在 ViewModel 的承载）。覆盖 preset 数据契约更新与资产加载 fallback。

### Modified Capabilities

（无 —— `openspec/specs/` 当前为空，本工程未沉淀历史主规范，仅有 changes 历史归档）

## Impact

### 受影响模块路径

- `feature/test/src/main/java/com/blazepush/feature/test/model/track/Track.kt`（数据结构 BREAKING）
- `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`（preset 数据契约更新）
- `feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt`（删除 `layoutName` 写入）
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapsHomeScreen.kt`（CurrentTrackPanel 增强、CHANGE TRACK 接通）
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt`（LAPS segment：placeholderTrackRecord 真实化、CurrentTrackRecordCard 接 TrackThumbnail、TrackPreviewStub 删除）
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/`（新建 `SelectTrackBottomSheet.kt`、新建 `TrackThumbnail.kt`）
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`（删 `layoutName` 日志引用 L542 + 新增 `currentSelectedTrack` 状态）
- `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt`（L196 `.name` → `.name.zh`、L197 `layoutName` 引用清理）
- `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt`（L67 `.name` → `.name.zh`）
- `feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt`（L32 测试断言更新）
- `feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt`（L168/189 测试断言：`layoutName` 断言改为断 `source`）

### 新增资产

- `feature/test/src/main/assets/track_thumbnails/chengdu_tianfu.png`（用户已提供并落盘）

### 协议兼容性

- 不涉及 RaceChrono BLE 协议（`docs/RaceChrono_BLE_Protocol.md`）
- 不涉及 replay JSON/VBO 协议字段
- 仅影响应用内 `Track` 模型与 preset 序列化形式（preset 是 Kotlin 硬编码、非外部协议）

### 双端任务划分

- 仅接收端 gps-app 改动，simulator 不涉及
