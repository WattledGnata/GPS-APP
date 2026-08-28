## Context

`enhance-track-presentation` round 落地后，`LapsHomeScreen.kt` 内 `NEARBY TRACKS` 区块（L185-202）仍是 `add-track-tech-app-shell` round 留下的硬编码占位列表 `["Shanghai Tianma", "TFIC LPCC", "Coming soon"]` + `TrackTechRow` 列表项 + 死路 toast `"Track detail placeholder"`。原 round 通过 §10 baseline 守护把这块"逐字符冻结"——只是为了避免 round 范围漂移。本 round 解除该边界、彻底替换。

设计稿提供的方向：横滑缩略图卡片（5 条 + VIEW ALL 入口）。背后语义不是"附近"而是"最近选过"——直接消化用户的 SELECT TRACK 操作历史，转化为可视化的快捷入口。这跟 RaceChrono / Sportity 等赛车 app 的"recent tracks"模式一致。

## Goals / Non-Goals

**Goals**：

- G1：删除 `NEARBY TRACKS` 占位区块、解除 `enhance-track-presentation` 的 §10 baseline 边界守护
- G2：新建 `RecentTracksStrip` 横滑卡片组件，展示用户最近选过的赛道（缩略图 + 中文名 + 距离 + 当前选中紫框高亮）
- G3：新建 `RecentTracksStore`（DataStore Preferences），持久化"最近选过的 trackId 列表"——头部插入、自动去重、滚动覆盖最多 5 条
- G4：`TestSessionViewModel.selectTrack(track)` 触发持久化写、`recentTrackIds: StateFlow<List<String>>` 推送 RECENT 列表
- G5：`VIEW ALL` 卡片复用现有 `SelectTrackBottomSheet`，不新建 UI
- G6：横滑卡片点击直接调 `selectTrack(track)`，与 SELECT TRACK 弹窗内的切换行为一致（都进 ViewModel 同一接口）

**Non-Goals**：

- NG1：不做赛道收藏 ★（与 `enhance-track-presentation` 一致）
- NG2：不做附近赛道（无定位、无地理排序）
- NG3：不做自定义赛道（无 Custom 卡片入口）—— 设计稿原图有 Custom 占位，user 拍板"先不要"
- NG4：不做 RECENT 列表手动管理（删除某条 / 置顶 / 重排）—— DataStore 自动滚动覆盖即可
- NG5：不做 RECENT 上限可配置 —— 写死 5
- NG6：不动 Records tab —— Records LAPS segment 仍 follow `currentSelectedTrack`
- NG7：不动 `SelectTrackBottomSheet` 现有 stateless API —— 仅复用调用
- NG8：不引入 RECENT 与 lap session / records 的关联（即不在 RECENT 卡片上显示"该赛道历史 best lap"等）—— 那是 Records filter 的事

## Decisions

### D1：DataStore Preferences vs Room vs SharedPreferences

**选择**：`androidx.datastore:datastore-preferences`

**备选**：

- (a) Room 表（如 `RecentTrackEntity(trackId, selectedAt)`）：过度抽象 —— 我们只需要一个 trackId 列表，没有关系查询、没有事务需求
- (b) `SharedPreferences`：Android 旧 API，同步 IO 阻塞 Main 线程、API 设计陈旧；Google 已推 DataStore 替代
- (c) 内存 state（不持久化）：跨进程重启就丢，不像"recent"

**理由**：DataStore Preferences 是 Google 推荐的轻量持久化方案，coroutine + Flow 原生支持，与现有 `kotlinx-coroutines-android` 生态对齐；序列化只用 `Preferences.Key<String>("recent_track_ids")` 一个 key（值为 `","` 拼接的 trackId 列表），不需要 schema migration。

### D2：序列化策略：单 String key 拼接 vs `Preferences.Key<Set<String>>`

**选择**：单 `Preferences.Key<String>` + `","` 拼接。

**备选**：

- (a) `Preferences.Key<Set<String>>`：DataStore 原生支持 Set，但 **Set 无顺序**，我们要的是"时间倒序"（最近选的在前）→ 不能用
- (b) `Preferences.Key<List<String>>`：DataStore Preferences **不原生支持 List**，要 Proto DataStore 或 JSON 序列化 —— 引入额外复杂度
- (c) JSON 序列化 List：用 Gson / kotlinx-serialization 序列化为 String —— 引入 JSON 处理负担，A56 项目记忆里写过"拒绝 JSON 格式存储高频 GPS 点阵（binary 优先）"，这里虽然不是高频但同样原则适用：能用更简单格式就别上 JSON

**理由**：trackId 是 kebab-case ASCII 字符串（如 `preset-tfic-lpcc`），不含 `","`；用 `","` 拼接安全、轻量、跨进程恢复 0 解析负担。`split(",").filter { it.isNotEmpty() }` 即可读出。

### D3：去重 + 滚动覆盖策略

**选择**：

```kotlin
suspend fun add(trackId: String) {
    dataStore.edit { prefs ->
        val current = prefs[KEY_RECENT_TRACK_IDS]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        val deduped = listOf(trackId) + current.filter { it != trackId }  // 头插 + 去重
        val capped = deduped.take(MAX_RECENT_COUNT)                       // 滚动覆盖
        prefs[KEY_RECENT_TRACK_IDS] = capped.joinToString(",")
    }
}
```

`MAX_RECENT_COUNT = 5`（写死）。

**备选**：

- (a) 不去重：用户连续选同一赛道会塞满列表；不直观
- (b) 不滚动覆盖（无限累积）：长期用会爆 → DataStore size 增长虽慢但无意义

**理由**：头插 + 去重 + 5 条上限是赛车 app "recent" 的标准语义。原子操作（`dataStore.edit { }` 是事务性）保证并发 `add` 安全。

### D4：RecentTracksStrip 数据流

**选择**：`RecentTracksStrip` 是 stateless 组件，接收 `recentTrackIds: List<String>` + `availableTracks: List<Track>` + `currentTrack: Track?` + 两个 callback：

```kotlin
@Composable
fun RecentTracksStrip(
    recentTrackIds: List<String>,
    availableTracks: List<Track>,
    currentTrackId: String?,
    onTrackClick: (Track) -> Unit,
    onViewAllClick: () -> Unit,
)
```

内部 `recentTrackIds.mapNotNull { id -> availableTracks.firstOrNull { it.id == id } }` 解析为 Track 对象，filter 掉 stale ID（preset 改名 / 删除场景）。

**备选**：

- (a) Strip 内部直接注入 ViewModel：违反 stateless 模式（参考 SelectTrackBottomSheet 设计），不利于 Records tab 未来类似场景复用
- (b) Strip 接收 `List<Track>`（已解析）：调用方做解析，但调用方需要知道"按 recentTrackIds 顺序解析"逻辑——封装到 Strip 内更内聚

**理由**：Strip 知道"按 ID 列表顺序解析 + filter stale"是它的内部细节，调用方只关心"给我 recent IDs + tracks 字典 + 当前选中 + click 行为"。stateless 化与 `SelectTrackBottomSheet` 风格一致。

### D5：当前选中卡片视觉识别 = 紫色 1dp border

**选择**：`if (track.id == currentTrackId) Modifier.border(1.dp, TrackTechColors.Purple, ...)` else `Modifier.border(1.dp, TrackTechColors.BorderAlpha60, ...)`。

无 ★、无背景色变化、无角标。

**备选**：

- (a) 紫色背景（filled）：与 SELECT TRACK 弹窗当前项的 border 风格不一致
- (b) ★ 角标：用户明确说 ★ 没实际语义、砍掉
- (c) 角标"CURRENT"：占位标签会让卡片信息密度过高（已有名 + 距离）

**理由**：与 SELECT TRACK 弹窗的当前选中识别一致（紫框 + 文字 "当前"）。横滑卡片空间小、不再加文字 "当前"，仅靠紫框。**真机验证 gate 13.x 需要确认紫框在小屏机型仍清晰可辨**（CLAUDE.md V2 §4 小屏机型强制验证）。

### D6：VIEW ALL 卡片在末尾、复用 SelectTrackBottomSheet

**选择**：`LazyRow` 末尾追加一个特殊 item（不是 Track），形态：cyan 1dp 描边、CutCornerPanel 风格、内容仅文本 "VIEW ALL"（UiTextLabel / cyan 色）。点击调 `onViewAllClick`，调用方触发 `showSelectTrackSheet = true`（与 CHANGE TRACK 按钮 / CurrentTrackPanel 卡片完全相同的入口）。

**备选**：

- (a) 跳独立全屏赛道列表页：增加一个新 Screen + Navigation 路由 + 新 UI 组件，纯成本无收益（SelectTrackBottomSheet 已经能做赛道选择）
- (b) "VIEW ALL" 不放卡片、放一个文字按钮在 section header 右侧：与设计稿（VIEW ALL 在卡片末尾）不一致；且在小屏上 section header 已经有 RECENT TRACKS label，加按钮挤

**理由**：复用 `SelectTrackBottomSheet` 是用户拍板（候选 (a)）。设计稿明示 VIEW ALL 在卡片末尾横滑序列内 → 视觉风格统一、用户不用纵向跳转。

### D7：点击 RECENT 卡片 = 直接 selectTrack（不走弹窗）

**选择**：点击非当前选中的 RECENT 卡片 → 直接调 `testSessionViewModel.selectTrack(track)` 切换；点击当前选中卡片 → no-op（与 SELECT TRACK 弹窗当前项行为一致）。

**备选**：

- (a) 点 RECENT 卡片 = 弹 SELECT TRACK 弹窗（卡片只是导航入口）：与"快捷切换"语义冲突，等于 RECENT 与 VIEW ALL 行为重复
- (b) 点 RECENT 卡片 = 弹确认对话框（"切换到 X？"）：增加一次额外交互，违反"快捷"

**理由**：RECENT 的产品价值就是"一键切换"，否则不如直接进 SELECT TRACK。selectTrack 内部还会触发持久化写——最近选的再次出现在头部（自然行为，保持有意义）。

### D8：并行 round 协同 / 与 round A 的同文件交叉

**情况**：本 round（E）与 round A `fix-lap-binary-ts-hygiene` 同改 `TestSessionViewModel.kt`：

- A：改 `bridgeGpsToLapTiming:562` 1 行公式 `tsDeltaMs = ...`
- E：构造函数加 `RecentTracksStore` 参数 + 顶层加 `_recentTrackIds` field + `selectTrack` 函数内部追加 `viewModelScope.launch { recentTracksStore.add(track.id) }` + `init` block 加 collect store flow

物理位置：A 在文件中段（line 562 函数体内），E 在文件顶部（构造函数 + 顶层 field 区，估计 line 80-130 范围 + selectTrack 函数 line 124）+ init block。**函数级不重叠**。

**Mitigation**：

- 看板 §5 登记 E 状态、§6 登记 `TestSessionViewModel.kt` ongoing 占用
- worktree 隔离编译沙盒（`.worktrees/replace-nearby-tracks-with-recent-strip`）
- Rebase 时 git 自动 merge 大概率搞定；若 import 区微冲突就地解决（A 可能不加新 import；E 加 `RecentTracksStore` import）
- 合回顺序：谁先到主区谁先合，另一个 rebase 跟上；按看板 §3 合回 checklist 第 3 步执行
- 真机验证按 §4.2 强制串行，session 必须先告知 user 等授权再 install

## Risks / Trade-offs

- **R1：DataStore migration / 首次启动空 store**：用户首次启动 app 没有 RECENT 历史，`recentTrackIds` 为 `emptyList()`，UI 应显示空 strip + 仅 VIEW ALL 卡片（或者降级显示"暂无最近赛道"提示？）→ Mitigation：UI 渲染 `if (recentTrackIds.isEmpty()) { 显示 VIEW ALL only / 或 降级文案 }`，spec 明示
- **R2：preset 改名/删除导致 stale trackId**：DataStore 里存的 ID 在 `availableTracks` 中找不到 → `mapNotNull` filter 掉。但 stale ID 永久占用 store 槽位（直到被滚动覆盖）→ Mitigation：可以在 ViewModel collect 时做一次性清理（filter 掉不在 availableTracks 的 ID，覆盖写回 store）。本 round 不做（一期 preset 列表稳定，未来真要加再做）
- **R3：横滑性能**：5 张卡片 + VIEW ALL = 6 个 item，`LazyRow` 性能不是问题
- **R4：与 round A 的 TestSessionViewModel.kt 共享**：rebase 冲突风险已在 D8 评估为低；Mitigation 是 git 自动 merge + 就地手解
- **R5：VIEW ALL 复用 SelectTrackBottomSheet 时 currentTrackId 显示**：复用同一弹窗，弹出时仍然显示当前选中（紫框 + "当前"）—— 与 CHANGE TRACK 按钮 / CurrentTrackPanel 卡片入口完全一致，预期行为
- **R6：DataStore IO 边界**：`add()` 是 suspend 函数，`viewModelScope.launch` 默认 Dispatchers.Main，但 DataStore 内部已 dispatch 到 IO 池，不阻塞 Main → 安全
- **R7：跨进程重启场景**：DataStore 自动恢复，`recentTracksStore.recentIds.collect { ... }` 在 ViewModel `init` block 启动后立即推送磁盘上的当前值

## Migration Plan

无运行时 migration：

- DataStore Preferences 是新建的本地存储，没有旧数据
- 旧 APK → 新 APK 升级场景：旧版本无 RECENT 数据 → 新版本读到空列表 → 自然显示 VIEW ALL only
- 新 APK → 旧 APK 降级场景（理论上不发生）：DataStore 文件留在磁盘但旧版本不读

部署即生效，回滚 = 直接回滚 commit + 删 DataStore 文件（用户清缓存即可）。

## Open Questions

- Q1：RECENT strip 的卡片**尺寸**：横滑卡片宽度 / 缩略图比例 / 整体高度 → 实施时按视觉密度调，预估卡片 ~120×96dp（含 padding）；真机 gate 13.x 视觉验证
- Q2：空 RECENT 状态的降级文案：是仅显示 VIEW ALL 卡片 / 还是显示"暂无最近赛道，点击 VIEW ALL 选择"提示？倾向**仅显示 VIEW ALL**（最简洁），但若 VIEW ALL 在空场景下视觉太空旷再加文案
- Q3：section header `"RECENT TRACKS"` 是否需要副标题（如 `"最近选过的"`）？倾向不加（V2 风格 section header 都是单行 label）；真机看效果再说
