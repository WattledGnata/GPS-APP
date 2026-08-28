## 1. 数据层（DataStore）

- [x] 1.1 在 `feature/test/build.gradle.kts` 加依赖：`implementation("androidx.datastore:datastore-preferences:1.0.0")`（版本与项目其他 androidx 依赖对齐；用 `libs.versions.toml` 统一更佳，但本 round 直接 inline 字符串依赖以最小改动；后续 D 风格清理 round 可统一）
- [x] 1.2 新建 `feature/test/src/main/java/com/blazepush/feature/test/datastore/RecentTracksStore.kt`，**MUST 接口 + 双入口构造**（spec Requirement 已明确）：
  - **接口** `interface RecentTracksStoreApi { val recentIds: Flow<List<String>>; suspend fun add(trackId: String) }` —— 所有下游消费方（ViewModel / DI）MUST 绑接口、不绑具体类
  - 顶层 extension：`private val Context.recentTracksDataStore by preferencesDataStore(name = "recent_tracks")`（生产路径）
  - **生产实现**：`class RecentTracksStore : RecentTracksStoreApi`，双构造：
    - **主构造（test-friendly）**：`internal constructor(private val dataStore: DataStore<Preferences>)` —— 接收已构造的 DataStore，便于 JVM 单测注入临时文件 DataStore
    - **生产构造**：`constructor(context: Context) : this(context.recentTracksDataStore)` —— DI 调此构造
  - `companion object { const val MAX_RECENT_COUNT = 5; private val KEY_RECENT_TRACK_IDS = stringPreferencesKey("recent_track_ids") }`
  - `override val recentIds: Flow<List<String>>` —— 从 `dataStore.data.map { prefs -> prefs[KEY_RECENT_TRACK_IDS]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList() }`
  - `override suspend fun add(trackId: String)`：`dataStore.edit { prefs -> ... }` 内部头插 + 去重 + 滚动覆盖（参照 design D3 算法）
- [x] 1.3 新建 `feature/test/src/test/java/com/blazepush/feature/test/datastore/RecentTracksStoreTest.kt`（普通 JUnit，**通过 test-friendly 主构造注入临时 DataStore**，不依赖 Robolectric / Android Context）：
  - 用 `@TempFolder JUnit Rule` + `PreferenceDataStoreFactory.create(scope = TestScope(...) , produceFile = { tmpFolder.newFile("recent_tracks.preferences_pb") })` 构造临时 DataStore
  - `RecentTracksStore(testDataStore)` 实例化（走主构造）
  - 测试 1：首次创建 store → `recentIds.first()` MUST 等于 `emptyList()`
  - 测试 2：`add("a")` → `recentIds.first()` MUST 等于 `["a"]`
  - 测试 3：`add("a") → add("b") → add("c")` → `recentIds.first()` MUST 等于 `["c", "b", "a"]`（头插）
  - 测试 4：`add("a") → add("b") → add("a")` → `recentIds.first()` MUST 等于 `["a", "b"]`（去重提到头部）
  - 测试 5：`add("a")..add("e") → add("f")` → `recentIds.first()` MUST 等于 `["f", "a", "b", "c", "d"]`（滚动覆盖，"e" 丢失）
  - 测试 6：跨 Store 实例恢复 —— 创建第一个 Store add 几条、关闭 scope、第二个 Store 同 file 读 → 结果一致

## 2. ViewModel 层（TestSessionViewModel）

- [x] 2.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`：
  - 构造函数加参数 `private val recentTracksStore: RecentTracksStoreApi`（**MUST 绑接口、不绑具体类** —— 与 spec 一致，便于测试注入 Fake）
  - 顶层 field 加：`private val _recentTrackIds = MutableStateFlow<List<String>>(emptyList())` + `val recentTrackIds: StateFlow<List<String>> = _recentTrackIds.asStateFlow()`
  - `init` block 加：`viewModelScope.launch { recentTracksStore.recentIds.collect { _recentTrackIds.value = it } }`
  - 修改现有 `fun selectTrack(track: Track)`：在 `_currentSelectedTrack.value = track` 之外，追加 `viewModelScope.launch { recentTracksStore.add(track.id) }`
  - **MUST NOT** 在 `availableTracks` 加载完成的初始化 fallback 中调 selectTrack 或 RecentTracksStore.add（避免污染 RECENT 列表）—— 检查 `init` block 现有 `_currentSelectedTrack.value = loaded.firstOrNull()` 路径是否仍直接赋值（不通过 selectTrack）
- [x] 2.2 修改 `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`：
  - 注册 `single<RecentTracksStoreApi> { RecentTracksStore(androidContext()) }`（**接口为 key**、生产 RecentTracksStore 实例为 value、走 Context 生产构造）
  - `TestSessionViewModel` 构造参数注入处加 `recentTracksStore = get()`（resolve 接口）
- [x] 2.3 **新增 `TestSessionViewModel(` 构造参数后所有现有 test helper 同步加 store 参数**（避免编译失败）。`grep -rn "TestSessionViewModel(" feature/test/src/test` 列出所有直接构造点，**全部**更新：
  - `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackSelectionTest.kt:createViewModel(...)` —— 加 `recentTracksStore = FakeRecentTracksStore()`
  - `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLoadingTest.kt:createViewModel(...)` —— 同上
  - `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt:createViewModel(...)` —— 同上
  - 任何遗漏的 `TestSessionViewModel(...)` 直接调用点 —— 编译错暴露后逐个修
- [x] 2.4 在 `feature/test/src/test/java/com/blazepush/feature/test/datastore/` 新建 `FakeRecentTracksStore.kt` 测试支持类（**位于 src/test 不进生产代码**）：
  - `class FakeRecentTracksStore : RecentTracksStoreApi`（接口已在 §1.2 创建）
  - 内部用 `MutableStateFlow<List<String>>(emptyList())` 模拟存储；`override val recentIds: Flow<List<String>> = stateFlow.asStateFlow()`
  - `override suspend fun add(trackId: String)` 实现与生产 `RecentTracksStore.add` **完全相同语义**（头插 + 去重 + 5 条滚动覆盖），算法直接 inline（不复用生产代码避免依赖反转，让 Fake 自洽）
  - 用于 ViewModel test helper 注入 —— ViewModel 构造参数 `RecentTracksStoreApi` 接受 Fake 与生产 RecentTracksStore 同类型
- [x] 2.5 扩展 `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackSelectionTest.kt`（**用 §2.4 的 FakeRecentTracksStore**）：
  - 测试 7：`selectTrack(trackX)` 调用后 `fakeStore.recentIds.first()` MUST 包含 `trackX.id` 在头部
  - 测试 8：连续两次 `selectTrack(trackA) → selectTrack(trackB)` → `recentTrackIds.value` MUST 等于 `["b.id", "a.id"]`（推送一致）
  - 测试 9：初始化 fallback 不污染 RECENT —— ViewModel 启动 + `availableTracks` 加载完成 + `_currentSelectedTrack.value = first` 后，`recentTrackIds.value` MUST 仍为 `emptyList()`、`fakeStore.recentIds.first()` MUST 仍为 `emptyList()`

## 3. UI 组件（RecentTracksStrip）

- [x] 3.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecentTracksStrip.kt`：
  - `@Composable fun RecentTracksStrip(recentTrackIds, availableTracks, currentTrackId, onTrackClick, onViewAllClick, modifier = Modifier)`（按 design D4 签名）
  - 内部 `recentTrackIds.mapNotNull { id -> availableTracks.firstOrNull { it.id == id } }` 解析（自动 filter stale）
  - `LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp))` + items（赛道卡片）+ item（VIEW ALL 卡片）
- [x] 3.2 实现单卡片 `@Composable fun RecentTrackCard(track, isCurrent, onClick)`（按 spec 形态）：
  - 整张卡片宽度约 130dp、cut-corner 形状（`CutCornerPanelShape(cutSize = 8.dp, cutCorners = cutCornersDiagonal)`）
  - border：`isCurrent` → `TrackTechColors.Purple` 1dp；else → `TrackTechColors.BorderAlpha60` 1dp
  - 内部 Column：`TrackThumbnail`（约 96dp 宽 × 64dp 高、复用 `track.thumbnailAssetPath`）→ `Spacer(8.dp)` → `track.name.zh` Text → `"%.3f km"` Text
  - 所有 Text 加 `maxLines = 1, overflow = TextOverflow.Ellipsis`；Text Column 用 `Modifier.weight(1f, fill = false)`（V2 §2 caveat）
  - 整卡 `clickable { onClick() }`
  - **MUST NOT** 渲染 `Icons.Filled.Star` / 任何 ★
- [x] 3.3 实现 VIEW ALL 卡片 `@Composable fun ViewAllCard(onClick)`：
  - 同尺寸（与 RecentTrackCard 等宽 / 等高）
  - cut-corner + cyan 1dp 描边
  - 中心单文本 `"VIEW ALL"`（`TrackTechTypography.UiTextLabel` + cyan + maxLines=1 + Ellipsis）
  - 整卡 clickable
- [x] 3.4 `RecentTracksStrip` 不写 Compose 单测（feature/test 模块当前 testImplementation 缺 Robolectric/Compose UI test 依赖；与 §6.2 / §8.4 enhance-track-presentation 一致策略）。交互验证由真机 gate 兜底

## 4. 集成（LapsHomeScreen）

- [x] 4.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapsHomeScreen.kt`：
  - 主 Composable 顶部新增 `val recentTrackIds by testSessionViewModel.recentTrackIds.collectAsState()`
  - **删除**原 NEARBY TRACKS 区块（从 `Text(text = "NEARBY TRACKS", ...)` 起到包含该 Text 的 `Column { ... }` 闭合花括号止；包含 `listOf("Shanghai Tianma", "TFIC LPCC", "Coming soon").forEach { TrackTechRow(...) }` 全部代码）
  - 在原位置插入新的 Column：`Text(text = "RECENT TRACKS", ...)` + `RecentTracksStrip(recentTrackIds = recentTrackIds, availableTracks = availableTracks, currentTrackId = currentTrack?.id, onTrackClick = { testSessionViewModel.selectTrack(it) }, onViewAllClick = { showSelectTrackSheet = true })`
  - section header `"RECENT TRACKS"` Text MUST 加 `maxLines = 1, overflow = TextOverflow.Ellipsis`
- [x] 4.2 删除 LapsHomeScreen 中已不用的 import（如 `androidx.compose.material.icons.filled.Flag`、`TrackTechRow` 若 NEARBY 区块是唯一调用 —— 实施时编译错或 Android Studio warn 时确认）

## 5. 验证（local）

- [x] 5.1 跑 `(cd /Users/wattledgnata/traeProjects/gps-app/.worktrees/replace-nearby-tracks-with-recent-strip && ./gradlew :feature:test:testDebugUnitTest)`，全部用例 PASS。重点验证：`RecentTracksStoreTest`（§1.3 新增）、`TestSessionViewModelTrackSelectionTest`（§2.3 扩展，含 7/8/9 新测试）
- [x] 5.2 跑 `(cd .worktrees/... && ./gradlew :app:assembleDebug)` 编译产出 APK，无编译错误
- [x] 5.3 全局 grep 确认（边界清零）：
  - `grep -rn "NEARBY TRACKS" feature/test/src/main` 无任何残留
  - `grep -rn "Track detail placeholder" feature/test/src/main` 无任何残留
  - `grep -rn '"Coming soon"' feature/test/src/main` 无任何残留（NEARBY 列表里的占位项）
  - `grep -rn "RecentTracksStore\|RecentTracksStrip" feature/test/src/main` 应有引用（新增组件被消费）
  - `grep -n '"Shanghai Tianma"' feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapsHomeScreen.kt` 无残留（NEARBY 区块删完）

## 6. 合回主干（按看板 §3 checklist）

> 顺序不可乱。每步完成后才能开下一步。MUST 取得 user 授权 commit + push；MUST NOT --amend / --no-verify。

- [x] 6.1 worktree 内独立 `git commit`：按功能单元拆 commit（建议拆 2 commit：一是 DataStore + ViewModel + 测试，二是 UI 组件 + 集成）。MUST 取得 user 授权再 commit
- [x] 6.2 worktree 内 `git fetch origin && git rebase feature/track-tech-v2`（拿主区最新 + 解决与 round A `TestSessionViewModel.kt` 的 import 区可能冲突）
- [x] 6.3 rebase 后再次跑编译 + 单测验证
- [x] 6.4 切回主区 `git checkout feature/track-tech-v2 && git merge feature/replace-nearby-tracks-with-recent-strip --ff-only`
- [x] 6.5 主区编译确认合回态通过：`./gradlew :app:assembleDebug`
- [x] 6.6 `git diff --stat HEAD~N..HEAD -- feature/ core/` 验证 diff 边界符合预期（feature/test 改动；core 不动）
- [x] 6.7 更新看板 §5 round E 状态字段（推进中 → 待合回 / done）+ 最近合回 commit
- [x] 6.8 更新看板 §6 共享文件占用 `TestSessionViewModel.kt` 状态：ongoing → done

## 7. 真机 manual gate（按看板 §4.2 串行规则）

> 准备 install 前 session MUST 在对话窗口告知 user：当前 round / apk / 设备 / 验证场景列表，等 user 明确授权再 `adb install`。其他 round 真机验证等本 round 完成 + user 放行。

- [ ] 7.1 告知 user "round E 准备装机验证（华为 8KE0219522008434）"，等 user 授权
- [ ] 7.2 `ANDROID_SERIAL=8KE0219522008434 ./gradlew :app:installDebug`
- [ ] 7.3 真机进 Laps tab 视觉检查（**单赛道 baseline 可达 gate**）：
  - section header 应显示 `"RECENT TRACKS"`（不是 NEARBY TRACKS）
  - 横滑区初始为空 RECENT（首次启动 + 当前只有 TFIC 一条 preset、ViewModel 初始化 fallback 不污染 RECENT）→ **仅显示 VIEW ALL 卡片**（无赛道卡片）
  - 点 VIEW ALL → 弹出 `SelectTrackBottomSheet`（标题"设置计时赛道"），与 CHANGE TRACK 按钮 / CurrentTrackPanel 卡片入口完全一致
  - 弹窗中点当前选中 TFIC 项 → no-op（弹窗 NOT 关闭、RECENT 横滑 MUST 仍只有 VIEW ALL 卡片，验证"当前项 no-op + 不污染 RECENT"语义）
  - 关闭 X 收掉弹窗，CURRENT TRACK 卡片状态不变
- [ ] 7.4 单赛道 baseline 视觉细节：
  - VIEW ALL 卡片宽高 / cyan 描边 / 文本对齐 与设计稿对照无明显差异
  - 横滑 LazyRow 即使只有 1 个 VIEW ALL 卡片，也不出现布局错位（无空余 padding 异常 / 不撑满全屏）
  - V2 §2 caveat 验证：section header `"RECENT TRACKS"` Text 单行不换行
- [ ] 7.5 **多赛道 RECENT 写入 + 持久化 + 切换 gate（延后到 round 「add-debug-preset-track-boyu-loop」合回后追验）**：
  - **不可达原因**：当前主区只有 TFIC 一条 preset，`SelectTrackBottomSheet` 中点当前 TFIC = no-op（不调 `selectTrack` → 不写 RECENT），无法在真机上观察"切换赛道 → RECENT 出现卡片"行为
  - **追验时机**：等 round `add-debug-preset-track-boyu-loop`（已在 §5 看板可见，主区有工件目录）合回 → 提供 Boyu Loop 第二条 preset 后，回到本 round 真机重做以下检查：
    - 切换到 Boyu → RECENT 横滑出现 Boyu 卡片在头部（紫框高亮 + 名 + 距离 + 缩略图）
    - 切回 TFIC → RECENT 横滑变为 ["TFIC", "Boyu"]，TFIC 头部紫框高亮、Boyu 第二位灰边
    - 再次点 RECENT 中当前选中 TFIC 卡片 → no-op
    - 杀进程（`adb shell am force-stop com.blazepush.gps`）→ 重启 app → RECENT 横滑 MUST 仍显示 ["TFIC", "Boyu"]（DataStore 持久化跨进程恢复）
  - **当前 round 单赛道场景的等价行为**已由 §1.3 RecentTracksStoreTest（DataStore 真实行为：头插 / 去重 / 滚动覆盖 / 跨进程恢复）+ §2.5 ViewModelTrackSelectionTest 测试 7/8/9（FakeRecentTracksStore + selectTrack 触发 add + recentTrackIds 推送 + 初始化不污染）单测覆盖 —— 算法层面正确性有底线
  - **本 task 状态**：标记为 `延后 / 等 Boyu round 合回后追验`，不阻塞本 round 合回与 archive；Boyu round 合回后追加一次 `installDebug` 跑 7.5 子项，结果回写到本 round 归档目录的 follow-up 笔记
- [ ] 7.6 V2 §4 小屏 gate：在 vivo V2405A 或同尺寸级别设备重复 7.3 + 7.4 的视觉检查；section header + VIEW ALL 卡片 MUST 单行不换行；横滑卡片布局在小屏 MUST 不溢出
- [ ] 7.7 告知 user 7.3 / 7.4 / 7.6 全部 PASS（7.5 延后），等 user 拍板 push 顺序（看板 §4.1）

## 8. Codex review（user 触发）

- [ ] 8.1 全部本地与真机验证通过后，提醒 user 触发 Codex review 整个 change（不属于 CC 自动操作；CC 等待 review 结果，按 review 反馈做局部修复或开新 OpenSpec change）

## 9. 归档

- [ ] 9.1 归档门槛（**与 §7.5 延后 gate 兼容**）：
  - **必须 PASS**：Codex review 通过 + 真机 §7.3 / §7.4 / §7.6 全部 PASS（单赛道 baseline 可达 gate + 小屏 gate）
  - **延后 follow-up（不阻塞归档）**：§7.5 多赛道 RECENT 写入 / 切换 / 持久化 gate 在归档目录留 follow-up 笔记，明示"等 round `add-debug-preset-track-boyu-loop` 合回后追验、结果回写"
  - 满足以上即可调 `/opsx:archive replace-nearby-tracks-with-recent-strip` 归档变更
- [ ] 9.2 在归档后的 `openspec/changes/archive/YYYY-MM-DD-replace-nearby-tracks-with-recent-strip/` 目录内新增 `follow-up-7.5-multi-track-gate.md`（或追加到现有 `tasks.md` 末尾），说明：
  - 当前单赛道 baseline 下 §7.5 不可达
  - Boyu round 合回后回到本 follow-up 跑 §7.5 子项 + 在文件内勾选实际结果
  - 算法层正确性已由 §1.3 RecentTracksStoreTest + §2.5 ViewModelTrackSelectionTest 测试 7/8/9 单测覆盖
- [ ] 9.3 清理 worktree：`git worktree remove .worktrees/replace-nearby-tracks-with-recent-strip`（取得 user 授权）
- [ ] 9.4 更新看板 §5 round E 状态：done；§6 共享文件占用全部 done
- [ ] 9.5 **Boyu round 合回后**：回到本 round 归档目录的 `follow-up-7.5-multi-track-gate.md` 跑 §7.5 真机检查、结果回写勾选 —— 此项独立追加，不再走 archive 流程
