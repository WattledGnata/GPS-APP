## ADDED Requirements

### Requirement: 全屏 LapVideoPlaybackScreen 提供中央播放按钮 + 双击切换的用户暂停控件

`LapVideoPlaybackScreen`（`feature/test/.../ui/tracktech/LapVideoPlaybackScreen.kt`）SHALL 提供两种切换播放/暂停的入口：(a) 中央叠加 IconButton 仅在暂停态可见，(b) 屏幕任意位置双击。两者共同操作同一 `userPaused` state，state 由圈时间轴主导状态机识别并暂停 / 恢复推进。

实现 MUST 满足：

1. **`userPaused` state hoisting**：MUST 在 Composable 顶层加 `var userPaused by remember { mutableStateOf(false) }`。
2. **状态机集成**：圈时间轴主导状态机循环（`while (isActive)` 内）MUST 在循环开头判 `if (userPaused)` 分支：
   - 调 `exoPlayer.pause()`（若 isPlaying）
   - `playheadUiWallClock = playheadWallClock`（state 镜像保持，不推进）
   - `delay(PLAYHEAD_TICK_MS)` 然后 `continue`，跳过覆盖段内/外/段切换/atEnd 逻辑
   - 不推进 `playheadWallClock`（覆盖段外黑屏 ticker advance 也跳过）
   - 不更新 overlay（保持 lastIdx 缓存值）
3. **中央按钮可见性**：MUST 仅在 `userPaused == true` 时渲染中央 `IconButton`：
   - `Modifier.align(Alignment.Center)`
   - `Icons.Filled.PlayArrow` 图标
   - 半透明圆形背景（如 `Color.Black.copy(alpha = 0.4f)` + `CircleShape`）便于在黑屏 / 亮帧上都识别
   - `onClick { userPaused = false }`
4. **双击切换**：MUST 在 Box 顶层（视频垫底之上、IconButton 之下）加 `Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { userPaused = !userPaused }) }`。
5. **进度条不受 userPaused 影响**：底部 Slider 的 onValueChange 写 `seekRequestWallClock` 路径与状态机循环解耦；`userPaused == true` 时 Slider 仍可拖动，恢复播放（userPaused=false）后状态机下一 tick 消费积压的 seek。
6. **进入屏 / 切换段不重置 userPaused**：`userPaused` 状态 hoisted 在屏 Composable 顶层，进圈、加载 segments、段切换都不重置（用户暂停后切段恢复仍是暂停态）。
7. **FileLogger 埋点**：`userPaused` 由 false 翻 true 或 true 翻 false 时 MUST 各埋一条 `FileLogger.d(TAG, "userPaused -> $userPaused via <doubleTap|centerButton>")`。
8. **V2 视觉约束**：中央 IconButton size MUST 显式给定（推荐 `size(80.dp)` + Icon `size(56.dp)`）；图标 tint `Color.White`；不引入 autoSize / 字号自适应。

#### Scenario: 双击屏幕切换播放→暂停，中央按钮出现

- **GIVEN** 用户进入 `LapVideoPlaybackScreen`，视频正在播放（`userPaused == false`，覆盖段内 ExoPlayer 推进 playhead）
- **WHEN** 用户双击屏幕中央或任意非按钮区域
- **THEN** `detectTapGestures(onDoubleTap)` 触发 → `userPaused = true`
- **AND** 状态机循环下一 tick 进入 `if (userPaused)` 分支 → `exoPlayer.pause()`
- **AND** 中央 `IconButton`（PlayArrow 图标）渲染到画面中心
- **AND** `playheadWallClock` 不再推进，画面冻结在暂停瞬间的帧
- **AND** `FileLogger.d` 记录 `userPaused -> true via doubleTap`

#### Scenario: 中央按钮点击切换暂停→播放，按钮消失

- **GIVEN** 用户在 `LapVideoPlaybackScreen` 已暂停状态（`userPaused == true`，中央按钮可见）
- **WHEN** 用户点击中央 `IconButton`
- **THEN** `onClick` 触发 → `userPaused = false`
- **AND** 状态机循环下一 tick 不再进入 `if (userPaused)` 分支 → 走既有覆盖段内/外逻辑
- **AND** 中央 `IconButton` 不再渲染（条件 `userPaused == true` 失败）
- **AND** 视频从暂停瞬间的 `playheadWallClock` 继续播放
- **AND** `FileLogger.d` 记录 `userPaused -> false via centerButton`

#### Scenario: 暂停态下拖 Slider 仍生效，恢复播放从拖到位置继续

- **GIVEN** 用户在 `LapVideoPlaybackScreen` 已暂停（`userPaused == true`），当前 playhead=PH1
- **WHEN** 用户拖底部 Slider 到目标 playhead=PH2
- **THEN** `Slider.onValueChange` 写 `seekRequestWallClock = PH2.toLong()`（状态机循环判 userPaused 提前 continue，不消费 seek）
- **AND** UI 不立即更新画面（仍冻结在 PH1，因为暂停态状态机不消费 seek）
- **AND** 用户双击 / 点中央按钮 → `userPaused = false`
- **AND** 状态机下一 tick 进入 line 361 `seekRequestWallClock?.let` 分支 → 跨段 seek 到 PH2 → 视频从 PH2 播放

#### Scenario: 反例——MUST NOT 让 userPaused 影响段切换 / atEnd 既有语义

- **GIVEN** 状态机循环既有逻辑（覆盖段内/外 / 段切换 / atEnd 停驻）
- **WHEN** contract test 扫描 `LapVideoPlaybackScreen.kt` 状态机循环代码
- **THEN** `if (userPaused) { ...; continue }` MUST 出现在 `while (isActive)` 循环开头（首句之一，在 seekRequest 消费分支之前或之后皆可，但必须在覆盖段判定 / atEnd 判定之前）
- **AND** 既有 `withinCoverage` / `wasWithinCoverage` / `lastTickRealtimeMs` / `tickCounter` 等状态变量 MUST NOT 被新 `if (userPaused)` 分支改写
- **AND** 若实现把 userPaused 判定漏到 line 388 之后，会导致暂停态画面继续推进 playhead（覆盖段外 ticker advance 仍跑），contract test fail

#### Scenario: 反例——MUST NOT 移除 useController=false 切换为 ExoPlayer 原生控制条

- **GIVEN** `LapVideoPlaybackScreen.kt:478` 既有 `PlayerView` 配置 `useController = false`
- **WHEN** 实现新增中央按钮 + 双击手势
- **THEN** `useController = false` MUST 保留（不暴露 ExoPlayer 原生底部控制条，避免与现有底部 Slider / 顶部 ExportButton / 圈时间轴主导状态机冲突）
- **AND** 若实现把 `useController = true`，contract test fail，锁死状态机主导播放语义
