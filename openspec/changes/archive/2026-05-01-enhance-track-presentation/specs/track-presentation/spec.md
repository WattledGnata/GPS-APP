# Track Presentation Capability

## ADDED Requirements

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

### Requirement: NEARBY TRACKS 区块保留不动（Non-goal 边界）

The system SHALL 在本 change 中保留 `LapsHomeScreen.kt:174-195` 的 `NEARBY TRACKS` 区块**完全不动**：

- 区块标题 `"NEARBY TRACKS"` 文本 MUST 保留
- 硬编码占位列表 `["Shanghai Tianma", "TFIC LPCC", "Coming soon"]` MUST 保留
- 点击 toast `"Track detail placeholder"` MUST 保留
- 区块布局结构 MUST 不被本 change 修改

本 change MUST NOT 引入定位、附近排序、距离用户位置计算等任何附近赛道相关代码或依赖。

#### Scenario: change 完成后 NEARBY TRACKS 区块文本不变

- **WHEN** 本 change 全部 task 完成
- **THEN** `LapsHomeScreen.kt` 中 `"NEARBY TRACKS"` 标签、`["Shanghai Tianma", "TFIC LPCC", "Coming soon"]` 列表、占位 toast 文案 MUST 与 change 启动前的版本逐字符相同
