## 1. 数据模型升级（基础契约）

- [x] 1.1 在 `feature/test/src/main/java/com/blazepush/feature/test/model/track/` 新建 `TrackName.kt`，定义 `data class TrackName(val zh: String, val en: String, val abbr: String? = null)`
- [x] 1.2 修改 `feature/test/src/main/java/com/blazepush/feature/test/model/track/Track.kt`：
  - 把 `name: String` 改为 `name: TrackName`
  - 删除 `layoutName: String? = null` 字段
  - 新增 `thumbnailAssetPath: String? = null` 字段
  - 新增 `lengthKm: Double` 字段（必填）
  - 保留 `orderedSectorGates` lazy 派生字段不动
- [x] 1.3 在 `feature/test/src/test/java/com/blazepush/feature/test/model/track/` 新增 `TrackNameTest.kt`：断言 `TrackName(zh, en, abbr=null)` 与 `TrackName(zh, en, abbr=null)` 相等；断言 `abbr=null` 与 `abbr=""` 不等（边界用例）

## 2. 预置数据更新（PresetTrackCatalog）

- [x] 2.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`，把 TFIC 条目改成新字段：
  - `name = TrackName(zh = "成都天府国际赛道", en = "Chengdu Tianfu International Circuit", abbr = "TFIC")`
  - `lengthKm = 3.260`
  - `thumbnailAssetPath = "track_thumbnails/chengdu_tianfu.png"`
  - 移除 `layoutName = "RaceChrono RCZ"` 写入
  - `referencePath` / `startFinishGate` / `sectorGates` 几何坐标保持不变
- [x] 2.2 在 `feature/test/src/test/java/com/blazepush/feature/test/repository/` 新增（或扩展现有 `TrackCatalogTest.kt`）测试：
  - 断言 `PresetTrackCatalog().getTrack("preset-tfic-lpcc")?.name?.zh == "成都天府国际赛道"`
  - 断言 `track.name.en == "Chengdu Tianfu International Circuit"`、`track.name.abbr == "TFIC"`
  - 断言 `track.lengthKm == 3.260`
  - 断言 `track.thumbnailAssetPath == "track_thumbnails/chengdu_tianfu.png"`
  - 断言 `track.source == TrackSource.Preset`
- [x] 2.3 **资产存在性验证（独立 task）**：新增 `feature/test/src/test/java/com/blazepush/feature/test/repository/PresetTrackAssetTest.kt`（普通 JUnit，不依赖 Robolectric）。**MUST 使用与现有测试（`ReplayAlignedTrackCatalogTest` / `TestSessionViewModelTrackLoadingTest` / `TestSessionViewModelTrackLapTest`）相同的 `projectRoot()` helper 模式**：从 `javaClass.protectionDomain.codeSource.location` 和 `System.getProperty("user.dir")` 双源向上递归，以 `settings.gradle{,.kts}` 为仓库根锚点。然后 `File(projectRoot(), "feature/test/src/main/assets/track_thumbnails/chengdu_tianfu.png")` 断言 `exists()` 为 `true` 且 `length() > 0`。**禁止**用 `File("src/main/assets/...")` 这种依赖运行时工作目录的相对路径（Gradle CLI / IDE / `:feature:test:test` 子模块工作目录不一致会导致假红/假绿）。AssetManager runtime 验证由真机 gate 13.1 兜底
- [x] 2.4 修改 `feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt:32` 现有测试断言（如果断言 `.name == "TFIC LPCC"`），改为 `.name.zh == "成都天府国际赛道"`

## 3. ReplayAlignedTrackCatalog 调整

- [x] 3.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt`：
  - `buildReplayAlignedTrack`（L82-102）移除 `layoutName = "REAL_TRACK_REPLAY"` 写入
  - 新增 `lengthKm = fallbackTrack.lengthKm` 沿用 preset 值
  - 新增 `thumbnailAssetPath = fallbackTrack.thumbnailAssetPath` 沿用 preset 值
  - 保留 `source = TrackSource.Generated`、`name = fallbackTrack.name` 不变
- [x] 3.2 修改 `feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt`：
  - L168 / L189 原先断言 `layoutName == "REAL_TRACK_REPLAY"` 改为断 `source == TrackSource.Generated`
  - 新增断言 `replayAlignedTrack.lengthKm == 3.260`（验证不重算）
  - 新增断言 `replayAlignedTrack.thumbnailAssetPath == "track_thumbnails/chengdu_tianfu.png"`（验证沿用）

## 4. 下游消费方修复（layoutName + name 引用）

- [x] 4.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:542`（`buildTrackDebugSummary` 中的 `layoutName` 日志）：改为输出 `track.source.name` 替代 `track.layoutName`
- [x] 4.2 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt:196`：`.name` → `.name.zh`
- [x] 4.3 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt:197`：`layoutName` 引用改为 `source == TrackSource.Generated` 等价条件判断（保持原 UI 条件渲染语义）
- [x] 4.4 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt:67`：`.name` → `.name.zh`
- [x] 4.5 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapsHomeScreen.kt:72`：`availableTracks.firstOrNull()?.name ?: "Shanghai Tianma"` → 在 task 8 重构 CurrentTrackPanel 时整体替换为 `currentSelectedTrack` 消费，本步骤暂不动（避免双重改动）

## 5. TestSessionViewModel 当前选中状态

- [x] 5.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`：
  - 新增 `private val _currentSelectedTrack = MutableStateFlow<Track?>(null)`
  - 新增 `val currentSelectedTrack: StateFlow<Track?> = _currentSelectedTrack.asStateFlow()`
  - 新增 `fun selectTrack(track: Track) { _currentSelectedTrack.value = track }`
  - 在 `availableTracks` 加载完成的回调中（找到现有 `_availableTracks.value = ...` 的位置）：若 `_currentSelectedTrack.value == null`，则设为 `availableTracks.firstOrNull()`
- [x] 5.2 在 `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/` 新增 `TestSessionViewModelTrackSelectionTest.kt`：
  - 测试 1：ViewModel 启动且 `availableTracks` 加载至少一条 → `currentSelectedTrack.value` MUST 等于 `availableTracks.value.first()`
  - 测试 2：`availableTracks` 为空 → `currentSelectedTrack.value` MUST 为 `null`
  - 测试 3：调用 `selectTrack(trackX)` → `currentSelectedTrack.value` MUST 等于 `trackX`，StateFlow MUST 推送新值

## 6. TrackThumbnail Composable

- [x] 6.1 在 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 新建 `TrackThumbnail.kt`：
  - `@Composable fun TrackThumbnail(assetPath: String?, modifier: Modifier = Modifier)`
  - 内部用 `LaunchedEffect(assetPath)` + `BitmapFactory.decodeStream(context.assets.open(assetPath))` 加载 `ImageBitmap`
  - 用 `runCatching` 捕获 `IOException` / `IllegalStateException`，失败时落到 fallback
  - 加载成功 → `Image(bitmap = imageBitmap, contentDescription = ..., contentScale = ContentScale.Fit)`
  - 加载失败 / `assetPath == null` → fallback Box（`SurfaceDark` 背景 + cyan 1dp 描边 + 中央 `"NO PREVIEW"` 文字 `TextMuted`）
- [x] 6.2 `TrackThumbnail` 不写 Compose 单测（feature/test 模块当前 testImplementation 仅有 junit/mockito/coroutines-test，无 Robolectric/Compose UI test 依赖；引入这些依赖超出本 change 范围）。fallback / 加载行为由真机 gate 12.1（正常加载）和 12.5（缺图 fallback）兜底

## 7. CurrentTrackPanel 增强

- [x] 7.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapsHomeScreen.kt` 中 `CurrentTrackPanel`（L201-239）签名：
  - `private fun CurrentTrackPanel(trackName: String)` → `private fun CurrentTrackPanel(track: Track?)`
- [x] 7.2 重写 `CurrentTrackPanel` 内部渲染（遵循 CLAUDE.md "UI 视觉约束" V2 规则）：
  - 标签 `"CURRENT TRACK"` 保留（已有 `UiTextLabel` 风格 + `maxLines = 1, overflow = TextOverflow.Ellipsis`）
  - 名称 Text：`track?.name?.zh ?: "NO TRACK SELECTED"`，**MUST 加** `maxLines = 1, overflow = TextOverflow.Ellipsis`（V2 规则 §2，长名截断由 Ellipsis 接管，不引入字号自适应）
  - 长度 Text：`track?.let { "%.3f km".format(it.lengthKm) } ?: ""`，**MUST 加** `maxLines = 1, overflow = TextOverflow.Ellipsis`；用普通 `Text` 渲染（不用 `MetricNumber`/`MetricTile`），**MUST NOT** 走 Mechanical 七段字体（V2 §1：含字母后缀 km 不符合 Mechanical "纯数字仪表瞬时读数" 范畴）
  - 替换原 120dp 占位灰盒为 `TrackThumbnail(assetPath = track?.thumbnailAssetPath, modifier = Modifier.fillMaxWidth().height(120.dp))`
  - **不得**渲染 `Icons.Filled.Star` 或任何收藏 ★ 视觉
- [x] 7.3 修改 `LapsHomeScreen` 主 Composable（L41-199）调用方：
  - 删除 L72 `val currentTrackName = availableTracks.firstOrNull()?.name ?: "Shanghai Tianma"`
  - 新增 `val currentTrack by testSessionViewModel.currentSelectedTrack.collectAsState()`
  - L107 `CurrentTrackPanel(trackName = currentTrackName)` → `CurrentTrackPanel(track = currentTrack)`
  - L166 `MetricTile(label = currentTrackName.uppercase(), ...)` → `MetricTile(label = (currentTrack?.name?.zh ?: "—").uppercase(), ...)`

## 8. SelectTrackBottomSheet 新建

- [x] 8.1 在 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 新建 `SelectTrackBottomSheet.kt`：
  - `@OptIn(ExperimentalMaterial3Api::class) @Composable fun SelectTrackBottomSheet(onDismiss: () -> Unit, testSessionViewModel: TestSessionViewModel = koinViewModel())`
  - 用 `ModalBottomSheet(onDismissRequest = onDismiss, containerColor = TrackTechColors.Background)`
  - 顶栏 Row：标题 `"SELECT TRACK"`（RacingTitleLarge）+ 装饰条纹（参考 `cutCornersDiagonal` 风格）+ `Icon(Icons.Filled.Close)` 点击触发 `onDismiss`
  - 列表 `LazyColumn`：消费 `availableTracks.collectAsState()` 与 `currentSelectedTrack.collectAsState()`
- [x] 8.2 实现列表项 `@Composable fun TrackSelectionRow(track: Track, isCurrent: Boolean, onClick: () -> Unit)`（遵循 CLAUDE.md V2 规则 §2 caveat："水平多元素 Row 必须配布局约束"）：
  - 左侧 `TrackThumbnail(track.thumbnailAssetPath)`（小尺寸，约 64dp 高）
  - 中部 Column **MUST 加** `Modifier.weight(1f, fill = false)`：
    - `track.name.zh`（RacingTitleMedium）**MUST 加** `maxLines = 1, overflow = TextOverflow.Ellipsis`
    - `"%.3f km".format(track.lengthKm)`（UiTextSmall TextMuted）**MUST 加** `maxLines = 1, overflow = TextOverflow.Ellipsis`；普通 `Text` 渲染，**MUST NOT** 走 Mechanical（同 §7.2 理由）
  - 末尾固定元素前 `Spacer(Modifier.width(8.dp))` 保间距
  - 右侧：当 `isCurrent == true` 显示绿色 `"Current"` 文本（`TrackTechColors.Green` 或对应的设计 token）
  - 整行 Box 容器：当 `isCurrent == true` 用 `border(1.dp, TrackTechColors.Purple)`，否则用细灰边
  - 主 Row **MUST NOT** 用 `horizontalArrangement = Arrangement.SpaceBetween`（V2 §2 caveat）
- [x] 8.3 列表项点击行为：`onClick = { if (!isCurrent) { testSessionViewModel.selectTrack(track); onDismiss() } }`，当前项点击 `onClick` 为 no-op（不重复触发切换）
- [x] 8.4 `SelectTrackBottomSheet` 不写 Compose 单测（同 §6.2 理由：缺 Robolectric/Compose UI test 依赖，且 `Modifier.border` 颜色/宽度不进 Compose semantics tree，紫色 border 断言不可观察）。验证拆分到：
  - 关键交互行为 → §5.2 `TestSessionViewModelTrackSelectionTest` 已覆盖 `selectTrack(trackX)` 后 StateFlow 推送
  - 当前项视觉（紫色描边 + Current 文本）→ 真机 gate 12.2
  - 点击非当前项触发切换 → 真机 gate 12.2
  - 点击当前项 no-op → 真机 gate 12.6（新增）

## 9. CHANGE TRACK 按钮接通

- [x] 9.1 修改 `LapsHomeScreen.kt` 主 Composable：新增 `var showSelectTrackSheet by remember { mutableStateOf(false) }`
- [x] 9.2 修改 `SecondaryActionPanel` "CHANGE TRACK"（L139-151）的 `onClick`：
  - 移除 `Toast.makeText(context, "Track selection — placeholder for future round", ...)`
  - 改为 `showSelectTrackSheet = true`
- [x] 9.3 在 `LapsHomeScreen` 主 Composable 末尾（`Spacer(Modifier.height(16.dp))` 之后）新增条件渲染：`if (showSelectTrackSheet) { SelectTrackBottomSheet(onDismiss = { showSelectTrackSheet = false }) }`
- [x] 9.4 全局搜索确认 `LapsHomeScreen.kt` 中已无 **完整字符串** `"Track selection — placeholder for future round"`（CHANGE TRACK 那一处）。**禁止**用 `"placeholder for future round"` 子串匹配作为 gate —— START LAP SESSION 保留的 toast 文案 `"Lap session entry — placeholder for future round"` 子串匹配会误伤

## 10. NEARBY TRACKS 区块边界守护

- [x] 10.1 实施前用稳定的**内容标记**（不是行号）截取 baseline：从 `Text(text = "NEARBY TRACKS", ...)` 开始，到包含该 Text 的 `Column { ... }` 闭合花括号结束，整段拷出保存到临时文件 `tmp/nearby_tracks_baseline.txt`。**禁止**用行号定位（任务 7.x / 9.x 会让 LapsHomeScreen.kt 顶部行号偏移）
- [x] 10.2 全部 task 完成后，用同样的内容标记截取当前版本，与 baseline 逐字符比对：必须完全相同。任何 diff（即便只是空格变化）都是边界违反。比对完成后清理 `tmp/nearby_tracks_baseline.txt`

## 11. Records tab LAPS segment 改造（数据真实化 + 资产接入，分离落地）

> 设计依据：`design.md` D9 —— mock 数据真实化 与 资产接入 是两层独立验收，不能因为当前只有一条 TFIC 把"数据对上了"等同于"资产已接入"

- [x] 11.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt`：
  - **删除**顶层 `private val placeholderTrackRecord = CurrentTrackRecord(...)`（L676-684）
  - **保留** `private data class CurrentTrackRecord(...)` 定义不动
- [x] 11.2 重写 `LapsView` 函数（L383-463）头部：
  - 函数签名增加默认参数 `testSessionViewModel: TestSessionViewModel = koinViewModel()`
  - 内部新增 `val currentTrack by testSessionViewModel.currentSelectedTrack.collectAsState()`
  - 内部新增派生 `val record = remember(currentTrack) { CurrentTrackRecord(trackName = currentTrack?.name?.zh ?: "—", bestLapTime = "1:32.457", bestLapDate = "May 18, 2024", length = currentTrack?.let { "%.3f km".format(it.lengthKm) } ?: "—", direction = "Clockwise", sessions = 8, totalLaps = 56) }`
  - **保留** `bestLapTime` / `bestLapDate` / `direction` / `sessions` / `totalLaps` 这些 mock 常量值不动（不在本 change 范围）
- [x] 11.3 修改 `CurrentTrackRecordCard` 签名（L466）：从 `(track: CurrentTrackRecord)` 改为 `(track: Track?, record: CurrentTrackRecord)`：
  - 内部所有原 `track.trackName` / `track.bestLapTime` / `track.bestLapDate` 等引用改为 `record.trackName` / `record.bestLapTime` / `record.bestLapDate`
  - 调用处 L391：`CurrentTrackRecordCard(track = placeholderTrackRecord)` → `CurrentTrackRecordCard(track = currentTrack, record = record)`
  - **保留** ★ 收藏图标（L529-536）不动 —— 与本 change 收藏 Non-goal 一致，不做 opportunistic 删除
- [x] 11.4 修改 `LapsView` 中 `TrackTechRow`（L393-400）：`title = placeholderTrackRecord.trackName` → `title = record.trackName`；`subtitle = "${placeholderTrackRecord.length} · ${placeholderTrackRecord.direction}"` → `subtitle = "${record.length} · ${record.direction}"`
- [x] 11.5 修改 `LapsView` 中 MetricTile（L408-435）：`placeholderTrackRecord.bestLapTime` / `.sessions` / `.totalLaps` → `record.bestLapTime` / `record.sessions` / `record.totalLaps`
- [x] 11.6 替换 `CurrentTrackRecordCard` 内 `TrackPreviewStub(...)` 调用（L537-541）为 `TrackThumbnail(assetPath = track?.thumbnailAssetPath, modifier = Modifier.fillMaxSize().padding(top = 24.dp, bottom = 4.dp))`，保留原 `padding`、`fillMaxSize` modifier 行为
- [x] 11.7 **删除** `TrackPreviewStub` Composable 定义（L548-584）；删除其相关的未使用 import（如 `androidx.compose.foundation.Canvas` 若 `SpeedCurveCanvas` 不再单独需要会被编译器不主动 warn —— 实施时检查是否 SpeedCurveCanvas 仍引用，若是则保留 import）
- [x] 11.8 在 `LapsView` 内部 Text 渲染遵循 CLAUDE.md V2 规则 §2：所有 Text MUST 已有 `maxLines = 1, overflow = TextOverflow.Ellipsis`（核对当前文件已大量加了，确认派生 record 后所有消费点都仍保留）

## 12. 验证（local）

- [x] 12.1 跑 `./gradlew :feature:test:testDebugUnitTest`，全部用例 PASS。重点验证：`TrackCatalogTest`（含 §2.2 新增断言）、`ReplayAlignedTrackCatalogTest`（含 §3.2 修后断言）、`TrackNameTest`（§1.3 新增）、`TestSessionViewModelTrackSelectionTest`（§5.2 新增）、`PresetTrackAssetTest`（§2.3 普通 JUnit + File API 形态）。`TrackThumbnail` / `SelectTrackBottomSheet` / `RecordsHomeScreen` 的 UI 行为不在本步骤验证（由真机 gate 兜底）
- [x] 12.2 跑 `./gradlew assembleDebug` 编译产出 APK，无编译错误
- [x] 12.3 全局 grep 确认（边界清零）：
  - `grep -rn "layoutName" feature/test/src/main` 无任何残留
  - `grep -rn '"REAL_TRACK_REPLAY"' feature/test/src/main` 无任何残留（带引号匹配字符串字面量；测试文件 `EndToEndLapTimingContractTest.kt` 中 `TestScenario.REAL_TRACK_REPLAY` enum 常量同名巧合，**允许**保留）
  - `grep -rn "TRACK PREVIEW · PLACEHOLDER" feature/test/src` 无残留
  - `grep -rn "Track selection — placeholder for future round" feature/test/src` 无残留
  - `grep -rn "TrackPreviewStub" feature/test/src` 无残留（§11.7 验证）
  - `grep -rn '"3.063 km"' feature/test/src` 无残留（§11.x 数据真实化验证）
  - `grep -n '"Shanghai Tianma"' feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt` 无残留（注意：`LapsHomeScreen.kt` 的 `NEARBY TRACKS` 区块**允许**保留 `"Shanghai Tianma"` 占位 —— 边界 §10）

## 13. 验证（真机 manual gates）

### Laps tab

- [x] 13.1 在默认真机（华为 `8KE0219522008434`）安装 debug APK，进入 Laps tab，确认 CURRENT TRACK 卡片：显示 `"成都天府国际赛道"` + `"3.260 km"` + 真实缩略图（不是 PLACEHOLDER 灰盒）
- [x] 13.2 真机点击 CHANGE TRACK 按钮：弹出 SELECT TRACK 底部弹窗；列表至少 1 条 TFIC；TFIC 项有紫色描边 + 绿色 `"Current"` 标记
- [x] 13.3 真机点击关闭 X：弹窗关闭，CURRENT TRACK 卡片状态不变
- [x] 13.4 真机点击 SELECT TRACK 弹窗中**当前选中项**（紫色描边那条 + Current 标记）：弹窗 MUST NOT 关闭，CURRENT TRACK 卡片状态 MUST NOT 变化（验证 §8.3 当前项 `onClick` no-op）
- [x] 13.5 真机视觉检查：Laps tab CURRENT TRACK 卡片 MUST NOT 出现任何 ★ 收藏图标；NEARBY TRACKS 区块 MUST 显示原 `["Shanghai Tianma", "TFIC LPCC", "Coming soon"]` 三项硬编码占位

### Records tab LAPS segment（数据真实化 + 资产接入分别验收）

- [x] 13.6 **数据真实化**：进入 Records tab、切到 LAPS segment，确认 `CURRENT TRACK RECORD` 卡片显示 `"成都天府国际赛道"`（不是 `"Shanghai Tianma"`）；卡片下方 `TrackTechRow` 副文显示 `"3.260 km · Clockwise"`（不是 `"3.063 km"`）
- [x] 13.7 **资产接入**：Records tab `CurrentTrackRecordCard` 右侧渲染的缩略图 MUST 与 Laps tab `CurrentTrackPanel` 渲染的缩略图**像素级一致**（同一张 `chengdu_tianfu.png` 经同一 `TrackThumbnail` 组件渲染）。截图对比验证
- [x] 13.8 **缺图 fallback 一致性**：临时把 `chengdu_tianfu.png` 重命名为 `_chengdu_tianfu.png` 重新构建：Laps tab CURRENT TRACK 卡片缩略图区 + Records tab `CurrentTrackRecordCard` 右侧 MUST 都显示 `"NO PREVIEW"` 占位，应用不崩。验证后改回原名
- [x] 13.9 Records tab `CurrentTrackRecordCard` ★ 收藏图标（L529-536）MUST 仍存在（与本 change 边界一致：未做 opportunistic 删除）

### 跨 tab 联动

- [x] 13.10 真机在 Laps tab 通过 SELECT TRACK 切换赛道（如果 preset 有第二条；当前只有 TFIC，此 gate 验证"切回 TFIC"也是合法路径），切到 Records tab LAPS segment 验证：CURRENT TRACK RECORD 卡片显示的赛道名与 Laps tab `currentSelectedTrack` 完全同步

### 小屏机型

- [x] 13.11 在小屏机型（CLAUDE.md "UI 视觉约束 §4 真机验证 gate" 指定的 vivo V2405A 或同尺寸级别设备）安装 debug APK，重复 13.1-13.10 的视觉检查：Laps tab CURRENT TRACK 赛道名 + 距离、Records tab `CURRENT TRACK RECORD` 赛道名、SELECT TRACK 列表项每行布局 MUST 单行不换行（超长触发 Ellipsis 截断）。**MUST NOT** 仅在大屏（华为 `8KE0219522008434`）签收

## 14. Codex review（用户触发）

- [x] 14.1 全部本地与真机验证通过后，提醒用户触发 Codex review 整个 change（不属于 CC 自动操作；CC 等待 review 结果，按 review 反馈做局部修复或开新 OpenSpec change）

## 15. 归档

- [x] 15.1 Codex review 通过 + 真机 13.1-13.11 全部 PASS 后，调 `/opsx:archive enhance-track-presentation` 归档变更
