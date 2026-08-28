## Context

`Track` 数据契约（`feature/test/.../model/track/Track.kt`）当前形态：

```kotlin
data class Track(
    val id: String,
    val name: String,
    val layoutName: String? = null,
    val source: TrackSource = TrackSource.Preset,
    val referencePath: TrackPath,
    val startFinishGate: TimingGate,
    val sectorGates: List<TimingGate> = emptyList()
)
```

存在两个结构问题：

1. `name: String` 是单一字符串，无法同时承载中文全称、英文全称与缩写三种写法。设计稿在不同 UI 位置基于空间需要选用不同写法（列表用中文全称、紧凑场景用 abbr），单字段无法支撑。
2. `layoutName: String?` 字段语义混乱：`PresetTracks.kt:15` 用作"布局名"（`"RaceChrono RCZ"`），`ReplayAlignedTrackCatalog.kt:92` 用作"来源标识"（`"REAL_TRACK_REPLAY"`）。两个语义在同一字段撞车，下游消费方（`TestSessionViewModel.kt:542` 日志、`LapDebugConfigScreen.kt:197` UI 条件）无法区分。

UI 现状：`LapsHomeScreen.CurrentTrackPanel`（L201-239）只渲染 `trackName: String` + 120dp 高灰盒占位，无缩略图、无距离、无 fallback；`CHANGE TRACK` 按钮（L139-151）点击 toast `"placeholder for future round"`，不接真实切换。

产品决策（已与用户对齐）：

- 不同布局算独立 Track，从 `id` 隔离，不引入 `Circuit` / `Layout` 二级抽象（"成都天府国际赛道"与"天府卡丁车场"是两个独立 Track）
- 缩略图由大模型生成 PNG，应用内置（不接地图 API、不做矢量渲染）
- 长度由用户提供官方数据（国际汽联认定），preset 硬编码
- 收藏、附近赛道一期不做

## Goals / Non-Goals

**Goals**：

- G1：`Track.name` 升级为结构化 `TrackName(zh, en, abbr?)`，UI 默认 `name.zh`
- G2：删除 `Track.layoutName`，来源语义由 `source: TrackSource` 完全接管
- G3：新增 `Track.thumbnailAssetPath: String?` 与 `Track.lengthKm: Double`，UI 渲染缩略图与官方距离
- G4：`LapsHomeScreen.CurrentTrackPanel` 替换占位灰盒为真实缩略图渲染区
- G5：新建 `SelectTrackBottomSheet`，`CHANGE TRACK` 按钮接通真实切换流程，ViewModel 持有当前选中赛道状态
- G6：缺图时 UI 必须 fallback，不允许空白或崩
- G7：所有写入 `layoutName` 的位置同步移除，下游读取改用 `source` 判断

**Non-Goals**：

- NG1：附近赛道功能（定位、排序、距离用户位置计算）—— `NEARBY TRACKS` 区块（L174-195）保留不动
- NG2：收藏 ★ 功能 —— CurrentTrackPanel 不画占位星标
- NG3：缩略图运行时生成（地图 API 底图、Compose Canvas 矢量渲染）
- NG4：服务端下发赛道（preset 仅 TFIC 一条；扩展点留给后续 change）
- NG5：`Circuit` / `Layout` 二级抽象（不同布局通过独立 Track id 隔离）
- NG6：多语言 i18n 联动（UI 位置选用哪种 name 由设计稿钉死，不跟随 app locale）
- NG7：协议改动（RaceChrono BLE、replay JSON/VBO 字段）

## Decisions

### D1：`TrackName` 用值对象，而非三个独立字段或 `Map<Locale, String>`

**选择**：

```kotlin
data class TrackName(
    val zh: String,
    val en: String,
    val abbr: String? = null,
)
```

**备选**：

- (a) `Track` 上拆三个字段 `nameZh: String` / `nameEn: String` / `nameAbbr: String?`：失去内聚，UI 端访问 `track.nameZh` 与 `track.nameEn` 散落，扩展（增加 `enShort`）需同时改 `Track`
- (b) `Map<Locale, String>`：过度抽象，强制把缩写也塞 Locale 体系，与产品决策（abbr 不与 locale 联动）冲突
- (c) 保留 `name: String` + 加 `nameAlternates: NameAlternates`：双轨并存，违反硬边界 1（"不允许 String name 混用"）

**理由**：值对象内聚、扩展时只改 `TrackName` 不改 `Track`、消费方访问 `track.name.zh` 语义明确。

### D2：`thumbnailAssetPath: String?` 用 asset 相对路径，而非 `@DrawableRes Int` 或 URI

**选择**：`String?` 类型，相对 `feature/test/src/main/assets/` 的路径（如 `track_thumbnails/chengdu_tianfu.png`）。

**备选**：

- (a) `@DrawableRes Int?`：编译期强类型，但跨模块访问 `R.drawable.xxx` 受 Gradle 模块边界限制；未来扩展到远端 URL 不兼容
- (b) `URI?`：抽象层级过高，一期不需要 `file://` / `https://` 区分

**理由**：与现有 `assets/replay/` 约定一致（`ReplayAlignedTrackCatalog` 已用 `context.assets.open(REPLAY_JSON_ASSET_PATH)`）；未来扩展为远端 URL 时 String 路径直接能塞 `https://...`，无需改字段类型；Coil/ImageBitmap 加载方式都接 `String`。

### D3：当前选中赛道状态放在 `TestSessionViewModel`，不新增 ViewModel

**选择**：在 `TestSessionViewModel` 增加：

```kotlin
private val _currentSelectedTrack = MutableStateFlow<Track?>(null)
val currentSelectedTrack: StateFlow<Track?> = _currentSelectedTrack.asStateFlow()

fun selectTrack(track: Track) { _currentSelectedTrack.value = track }
```

初始值由 `availableTracks.firstOrNull()` 兜底（与 `LapsHomeScreen.kt:72` 当前 fallback 行为一致）。

**备选**：

- (a) 新增独立 `TrackSelectionViewModel`：`TestSessionViewModel` 已经持有 `availableTracks`、`lapRunConfig`，把"选中态"也归并语义内聚；新建独立 VM 增加 Koin 注册与生命周期管理成本，无收益
- (b) 直接在 `LapsHomeScreen` 用 `remember { mutableStateOf<Track?>(null) }` 局部 state：`SELECT TRACK` 弹窗与 `CurrentTrackPanel` 跨 composable 共享 + 与圈速测试启动流程联动（未来要让 lap session 知道选了哪条赛道），局部 state 不够

**理由**：状态归属与 `availableTracks` / `lapRunConfig` 同一 VM，避免状态分散；不引入新 Koin 单例；未来 lap session 启动时直接读 `currentSelectedTrack` 即可。

### D4：`SelectTrackBottomSheet` 用 Material3 `ModalBottomSheet`，不用自定义 Box overlay

**选择**：`androidx.compose.material3.ModalBottomSheet` + `rememberModalBottomSheetState()`。

**备选**：

- (a) 自定义 `Box` + `Surface` overlay：要自实现展开动画、背景遮罩、滑动关闭、系统返回键处理、IME 兼容
- (b) `Dialog`：全屏遮罩，与"底部弹出"视觉不符

**理由**：Material3 已提供完整底部弹窗能力，工程已用 Material3（`@Composable Text` / `Icon` / 主题），无需引入新依赖；视觉风格化通过 `containerColor = TrackTechColors.Background` + 自定义 sheet content 完成。

### D5：`lengthKm: Double` 由 preset 硬编码，replay 拟合后不重算

**选择**：`PresetTracks.kt` 直接写 `lengthKm = 4.050`；`ReplayAlignedTrackCatalog.buildReplayAlignedTrack` 调用时 `lengthKm = fallbackTrack.lengthKm`（沿用 preset 值）。

**备选**：

- (a) 从 `referencePath` Haversine 累加：preset 13 个稀疏点会算少（折线长度 ≪ 真实弧长），replay 密集点能算准但语义错位（GPS 实测距离 ≠ 赛道官方长度，会和官方数据漂移）
- (b) `lengthKm: Double?` 可空：UI 渲染要写 fallback `"-- km"`，违反"赛道自身属性"的事实

**理由**：UI 展示"4.050 km"是赛道**官方长度**而非 GPS 实测，应当来自权威源（用户提供）。replay 拟合改 `referencePath` 但不改赛道身份，长度沿用。

### D6：缩略图加载用现有图片库，不引入新依赖

**选择**：先用 Compose 原生 `Image(painter = painterResource(...))` 不可行（asset 不是 res），改用 `BitmapFactory.decodeStream(context.assets.open(path))` 转 `ImageBitmap` 在 `Image` 中渲染，包装为 `@Composable fun TrackThumbnail(assetPath: String?, modifier: Modifier)` 复用组件。

**备选**：

- (a) 引入 Coil `io.coil-kt:coil-compose`：单图、无网络、无缓存需求，引入新依赖收益不足
- (b) 把 PNG 移到 `res/drawable/`：与 D2 决策冲突（路径作为 String 字段、未来兼容远端 URL）

**理由**：单图 + 静态 asset 用原生 BitmapFactory 足够；`TrackThumbnail` 组件封装加载与 fallback 逻辑，调用方一行 `TrackThumbnail(track.thumbnailAssetPath)`。

### D7：缺图 fallback 用 cyan 描边占位框 + 中央文字 `"NO PREVIEW"`

**选择**：`TrackThumbnail` 内部 `LaunchedEffect(assetPath)` 加载 ImageBitmap，加载失败或 `assetPath == null` 时渲染：

```
┌────────────────────┐
│                    │
│     NO PREVIEW     │  ← TextMuted 颜色，UiTextSmall 字号
│                    │
└────────────────────┘  ← cyan 1dp 描边，SurfaceDark 背景
```

**备选**：

- (a) 不渲染（空 Box）：违反硬边界 3（"不让页面空白"）
- (b) 通用占位 vector 图标（赛车旗）：风格与霓虹缩略图不一致，反而更显眼

**理由**：占位与缩略图盒同尺寸，视觉占位与 fallback 状态都明确；用户加资产后无缝切换。

### D9：Records tab LAPS segment：mock 数据真实化 与 资产接入 分离落地

**核心判断**（用户拍板）：本 change 涉及 Records tab `LapsView` 时，必须**分别**验收两层独立概念，不允许合并：

1. **数据真实化**：`placeholderTrackRecord` 的 `trackName="Shanghai Tianma"` / `length="3.063 km"` 这种 mock 串改成消费 `currentSelectedTrack` 派生
2. **资产接入**：`CurrentTrackRecordCard` 内 `TrackPreviewStub(...)` 改成 `TrackThumbnail(assetPath = currentTrack?.thumbnailAssetPath)`

**为什么要分离**：当前只有一条 TFIC preset，把"Shanghai Tianma"的 mock 数据改成"成都天府国际赛道"派生后，**视觉上**与"用了真实 TFIC 缩略图资产"难以区分。如果合并验收，可能出现"数据派生改完了但 `TrackPreviewStub` 还在画 mock 路径"的状态，且不会暴露 —— 需要刻意分别检查。

**实现策略**：

- `placeholderTrackRecord` 从 `private val` top-level 常量改为 `LapsView` 内部 `remember(currentTrack) { CurrentTrackRecord(...) }` 派生
- `trackName` / `length` 从 `currentTrack` 派生；其他 mock 字段（`bestLapTime` / `bestLapDate` / `direction` / `sessions` / `totalLaps`）保留为 mock 常量
- `CurrentTrackRecordCard` 签名从 `(track: CurrentTrackRecord)` 扩展为 `(track: Track?, record: CurrentTrackRecord)`
- `TrackPreviewStub`（L548-584）删除（替换后无其他调用）
- `LapsView` 中 `TrackTechRow`（L393-400）的 `title` / `subtitle` 同步消费派生数据

**备选**（已拒绝）：

- (a) `CurrentTrackRecord` 类型完全重写：把 `trackName` / `length` 字段从 data class 移除，由调用方传 `Track?` —— 改动面更大、且 `direction` 等 mock 字段还要保留某种容器，不如保留 `CurrentTrackRecord` 现有结构、仅改 `placeholderTrackRecord` 的数据来源
- (b) 仅做数据真实化、跳过 `TrackThumbnail` 接入（继续用 `TrackPreviewStub`）：违反"资产接入要单独验收"的核心判断；TFIC 一条数据下视觉看不出差异，但二期来第二条赛道时 stub 会画错的几何

**真机验收 gate**：必须分别验证：

1. Records tab LAPS segment 显示 `"成都天府国际赛道"` + `"3.260 km"`（数据真实化）
2. Records tab `CurrentTrackRecordCard` 右侧渲染的图与 Laps tab `CurrentTrackPanel` 渲染的图**像素级一致**（资产接入 + 同一组件）
3. 临时改名 asset 重建 → Records tab 与 Laps tab 同步显示 `"NO PREVIEW"` fallback（统一资产管线）

### D8：删除 `layoutName` 的迁移路径

**步骤**：

1. 先在 `Track` 上添加新字段 `lengthKm` / `thumbnailAssetPath` / 把 `name` 改成 `TrackName`（编译错全暴露）
2. 修复所有写入位置（`PresetTracks.kt:15`、`ReplayAlignedTrackCatalog.kt:92`）：先把 `layoutName = "..."` 删除（因为字段被同步删了），preset 改填 `lengthKm` + `thumbnailAssetPath`
3. 修复所有读取位置：
   - `TestSessionViewModel.kt:542`（日志）→ 改输出 `source.name` 替代 `layoutName`
   - `LapDebugConfigScreen.kt:197`（UI 条件）→ 改判 `source == TrackSource.Generated`
   - `ReplayAlignedTrackCatalogTest.kt:168/189`（测试断言）→ 断 `source == TrackSource.Generated`
4. 跑 `:feature:test:test` 全套验证
5. 修被影响的 `.name` → `.name.zh` 4 处

无 DB schema、无外部协议、无远端持久化 → 无运行时迁移需求。

## Risks / Trade-offs

- **R1：`TrackName` 是 BREAKING 改动，所有持有 `Track.name: String` 引用都会编译错** → Mitigation：编译错是好事（强制全量修复），影响面已在 proposal/Impact 列出（4 处 `.name` 读取），无静默漏改风险
- **R2：`@Volatile cachedReplayTrack` 在 `ReplayAlignedTrackCatalog` 里持有的旧 Track 实例（含 layoutName）在 hot reload 期间可能被消费**：构造方式改了字段，序列化形式不兼容 → Mitigation：`cachedReplayTrack` 是 in-memory volatile，不持久化，进程重启即重建；hot reload 场景由 IDE 触发 process restart，无影响
- **R3：缩略图 PNG 直接放 `feature/test/src/main/assets/track_thumbnails/`，APK 体积** → Mitigation：单图 < 50KB，且 asset 不参与 R 类，不影响构建；未来扩展到 N 张可考虑 WebP
- **R4：`SelectTrackBottomSheet` 和 `LapsHomeScreen` 用 `koinViewModel()` 共享同一 `TestSessionViewModel`，状态变更立即可见**：底部弹窗内 `selectTrack(track)` 调用后 `currentSelectedTrack` flow 推送，`CurrentTrackPanel` recompose → 预期行为，但要验证 sheet 关闭时机（`onDismiss` vs 用户手动选完点 Current）
- **R5：`TrackName` 值对象 `equals/hashCode` 基于全部字段**，`abbr = null` vs `abbr = ""` 不等 → Mitigation：preset 数据约定 `abbr` 没有时显式填 `null`，禁止空字符串

## Migration Plan

无运行时迁移：

- 无 DB schema 变更（Room 数据库 `AppDatabase` 不涉及 Track）
- 无外部协议（preset 是 Kotlin 编译期硬编码）
- 无远端依赖（preset 不下发）

部署即生效：

1. 合入 develop 分支后随下一次构建发版
2. 旧 APK 与新 APK 共存场景：仅本地状态（`currentSelectedTrack` in-memory），无跨版本兼容问题

回滚：

- 直接回滚 commit；无 schema、无 asset 依赖（asset 删除即可）

## Open Questions

- Q1：`SelectTrackBottomSheet` 的"装饰条纹"（效果图标题右侧的紫色斜线条纹）是用 `Canvas` 画还是切角形 `Box`？→ 实施时按 `LapsHomeScreen` 现有 `cutCornersDiagonal` 风格统一即可，不阻塞设计
- Q2：`CurrentTrackPanel` 缩略图区高度是固定 `120.dp`（与现有占位一致）还是改成宽高比保持？→ 一期沿用 `120.dp`，效果图缩略图实际比例约 16:9，PNG 居中裁剪
- Q3：`SelectTrackBottomSheet` 关闭时机：用户选中后立即 `onDismiss` 关闭，还是要点 X 才关？→ 实施时倾向"选中即关"减少多余交互，但不绝对，发现体验不好回收
