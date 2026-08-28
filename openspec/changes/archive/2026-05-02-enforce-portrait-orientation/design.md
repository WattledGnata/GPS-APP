## Context

`MainActivity` 是 V2 Track Tech 全应用唯一的 Activity（详见 `app/src/main/AndroidManifest.xml`）。Compose Navigation `NavHost` 内当前 6 个 route：

| Route | Composable | 当前 orientation 行为 | 期望 |
|---|---|---|---|
| `home` (Tabs: Test/Laps/Records/Device) | `TestHomeScreen` / `LapsHomeScreen` / `RecordsHomeScreen` / `DeviceHomeScreen` | unspecified（跟随系统） | portrait |
| `test_execution` | `TrackTechTestExecutionScreen` | unspecified | portrait |
| `gps_details` | `GpsDetailsScreen` | unspecified | portrait |
| `lap_live` | `LapLiveScreen` | DisposableEffect 进入时切 LANDSCAPE / 离开切 PORTRAIT | landscape（保留） |
| `lap_session_detail/{id}` | `LapSessionDetailScreen` | unspecified | portrait（本 round）；future 切 landscape（follow-up） |
| `performance_result/{id}` | `PerformanceResultScreen` | unspecified | portrait |

V2 视觉系统全部页面都是为竖屏（手机典型握持方向）紧凑布局设计。除 `lap_live` 仪表盘需要横屏读数距离最大化外，其他 5 类页面在物理设备旋转时不应跟随旋转。

## Goals / Non-Goals

**Goals:**

- 全应用默认 portrait 锁定（manifest 单一 source-of-truth）
- `lap_live` 唯一 landscape 例外（沿用现有 `DisposableEffect` 模式）
- 进入 / 退出 `lap_live` 自动切横屏 / 恢复竖屏，无视觉抖动
- 改动最小化（1 个 manifest 属性 + 0 行代码改动 + 2 个 grep contract test）

**Non-Goals:**

- 不引入 `OrientationProvider` / 全局 orientation manager 抽象（过度设计）
- 不做 `lap_session_detail` 横屏切换（user 拍板"将来加视频/图表再说"，留 follow-up backlog）
- 不做 portrait + reverse-portrait 双向锁（`portrait` 默认含上下方向自适应）
- 不动其他页面 Composable 内部 layout（manifest 锁后任何页面都不会被强迫适配 landscape）
- 不动 `LapLiveScreen.DisposableEffect`（现有逻辑跟 manifest 互补，双重保险）
- 不动 BLE / GPS / RaceChrono 协议
- 不引入 Compose `LocalConfiguration.orientation` 的 runtime 检测

## Decisions

### Decision 1: 用 AndroidManifest `screenOrientation="portrait"` 锁默认（不用 Activity 代码 setRequestedOrientation）

**对比**：

| 方案 | 优 | 劣 |
|---|---|---|
| **A. Manifest `android:screenOrientation="portrait"`（采用）** | app 启动期就锁，零代码；`MainActivity` onCreate 之前就生效；性能零成本；Configuration changes 由 manifest `configChanges` 控制（本 round 不动）；contract test 用单次 grep 字面量验证 | 不能 per-route 切换；但本 round 只有一个 landscape 例外（`lap_live`），用 Activity API 临时覆盖刚好 |
| B. `MainActivity.onCreate` 内 `setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT)` | 跟 LapLiveScreen 同 API 风格（一致性） | onCreate 调用时机晚于 manifest，启动闪屏可能短暂 unspecified；多此一举（manifest 已是声明性） |
| C. 在每个 Composable route 内用 DisposableEffect 设 PORTRAIT | per-route 控制最细 | 5+ 处重复代码；漏一处就破功；DisposableEffect 重组期间可能短暂错向 |

**理由**：A56 round 引入了 single-Activity + Compose Navigation 模式；orientation 是 Activity 级配置，manifest 是最佳归宿。LapLiveScreen 的 DisposableEffect 是**临时覆盖**模式，跟 manifest 默认不冲突。

### Decision 2: LapLiveScreen `DisposableEffect` 完全保留不动

**对比**：

| 方案 | 优 | 劣 |
|---|---|---|
| **保留 LANDSCAPE 进入 / PORTRAIT 离开（采用）** | 显式 `SCREEN_ORIENTATION_PORTRAIT` 在 `onDispose` 调用是**双重保险**——若某天 manifest 被人误改成 unspecified，LapLiveScreen 离开仍能恢复 portrait 而不是不可预料的方向 | 跟 manifest 默认 portrait 行为重叠；strict 简洁主义看是冗余 |
| 改 `onDispose { SCREEN_ORIENTATION_UNSPECIFIED }` 让 manifest 默认接管 | 更"优雅"——单一 source-of-truth，没有显式 PORTRAIT 调用冗余 | 失去"manifest 被误改"的防御；本 round scope 是"加配置"不是"改既有逻辑"，扩散 risk |

**理由**：CLAUDE.md "Don't add ... refactor surrounding code while completing the requested task"。LapLiveScreen 现有逻辑是 J round 之前 baseline 已稳定运行，本 round MUST NOT 改。

### Decision 3: contract test 用纯 grep 不上 Robolectric

**对比**：

| 方案 | 优 | 劣 |
|---|---|---|
| **grep `AndroidManifest.xml` / `LapLiveScreen.kt` 字面量（采用）** | 跟 G round / J round contract test 同款风格；零 Android Context 依赖；执行 < 100ms；不引入 Robolectric 或 instrumented test 复杂度 | 不验证 runtime 行为（grep 不证明 Activity 真的被锁竖屏） |
| Robolectric `MainActivityTest` 验证 `requestedOrientation` 真实值 | 验证 runtime | Robolectric setup 复杂；本 round 不值得；真机验证已覆盖 runtime |
| Compose UI test in `androidTest` | 真实 instrumented 验证 | 耗时长 + 设备依赖；本 round 不值得 |

**理由**：grep contract 已是项目内多 round 的成熟模式（`PerformanceResultScreenContractTest` / `RecordsHomeScreenLongPressContractTest`）。runtime 行为靠真机验证（华为 8KE0219522008434）+ DisposableEffect baseline 早已稳定运行。

### Decision 4: portrait（不是 sensorPortrait / userPortrait / fullSensor）

`android:screenOrientation` 候选值：

| 值 | 行为 |
|---|---|
| `portrait` | 强制 portrait（含 reverse-portrait 自适应），跟随设备物理上下方向 |
| `sensorPortrait` | portrait + 跟随 sensor 在 portrait + reverse-portrait 间切（设备倒过来时屏幕跟着翻） |
| `userPortrait` | 用户在系统设置允许旋转才 sensorPortrait，否则 portrait |
| `fullSensor` | 任意方向（含 landscape） |
| `unspecified` | 跟随系统 |

**采用 `portrait`**（不是 `sensorPortrait`）：

- 大多数用户握手机的姿态是 portrait（充电口朝下），不需要 reverse-portrait 的语义
- `sensorPortrait` 在某些设备上跟随陀螺仪过敏感
- `portrait` 是 Material3 baseline 推荐，最稳

## Risks / Trade-offs

- **[`lap_live` 进入瞬间动画抖动]** → DisposableEffect 切 LANDSCAPE 时屏幕从 portrait 转到 landscape 有 ~200ms 旋转动画。**Mitigation**：现有 baseline 已稳定运行，这是已知 trade-off；如未来 user 反馈刺眼可以加 splash mask
- **[manifest 被人误改回 unspecified]** → LapLiveScreen 的 `onDispose { PORTRAIT }` 双重保险仍能恢复竖屏；其他页面会重新跟随系统旋转（视觉变难看但不 crash）。**Mitigation**：本 round 加 `MainActivityOrientationContractTest` grep 字面量防回退
- **[未来加新 route 漏 portrait 配置]** → 因为是 manifest 单一 source-of-truth，新 route 自动继承 portrait（在 Compose Navigation 内任何 route 都属于 `MainActivity` 这一个 Activity，Activity 级 manifest 自动覆盖）。**Mitigation**：天生防御，无需额外动作
- **[未来要加新的 landscape route（如 `lap_session_detail` 加视频）]** → 现有 LapLiveScreen 模式可直接复用（`DisposableEffect` 临时覆盖 + `onDispose` 恢复 PORTRAIT）。**Mitigation**：模式已建立，复制即可
- **[多 change 并行]** → 主区当前无并行 round 在改 `AndroidManifest.xml` 或 `LapLiveScreen.kt`（grep 看板 §6 无登记）。本 round scope 极小，可不开 worktree 直接主区 1 commit ff-only

## Migration Plan

无 schema / 协议 / 数据 migration（manifest 配置改动）。

部署步骤：

1. 看板 §5 登记本 round：`K. enforce-portrait-orientation`，状态"推进中"（scope 太小可不开 worktree，直接主区改）
2. 看板 §6 登记共享文件：`AndroidManifest.xml`（独占）；`LapLiveScreen.kt`（仅 grep contract test 引用，不修改源码）
3. 修改 `app/src/main/AndroidManifest.xml`：`MainActivity` 节加 `android:screenOrientation="portrait"`
4. 新增 2 个 contract test
5. 编译 + 单测全绿（`:app:testDebugUnitTest` + `:feature:test:testDebugUnitTest`）
6. 真机验证（华为 `8KE0219522008434`）：覆盖 `proposal.md` Impact 节列的 3 个场景
7. commit + ff-only 合回主区
8. push 等 user 拍板（遵循 CLAUDE.md「Git Rules: STRICT Never run `git push` ... without explicit human confirmation」）
9. 归档 round：`openspec archive enforce-portrait-orientation`

回滚策略：

- 全部改动 = 1 行 manifest + 2 个新文件。回滚 = revert 1 个 commit
- 不动 schema / 协议 → 数据无回滚成本

## Open Questions

无。所有决策已与 user 在 explore 阶段对齐：

- 横屏白名单 = 只 `lap_live`（user 拍板："目前是圈速实时屏幕；圈速回放详情将来肯定是可以切横屏的，后面不管是加视频还是加图表 都需要横屏，但是现在就没必要"）
- round 名 = `enforce-portrait-orientation`（user 拍板）
- manifest portrait 方案（不动 LapLiveScreen / 不引入 abstraction）符合 user 描述的"小功能"语义
