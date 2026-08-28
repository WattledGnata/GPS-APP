## Why

`TrackTechTypography.MetricHero / MetricMedium / MetricSmall` 当前**全部**用 DSEG7 七段数码字体（`Dseg7FontFamily`）。所有用 `MetricTile` / `MetricNumber` 或直接用 `Metric*` TextStyle 的位置都自动得到七段字体。

但实际渲染的字段类型差异巨大：

| 字段类型 | 例子 | 七段字体下视觉效果 |
|---|---|---|
| 纯数字仪表瞬时读数 | SPEED `132` km/h / SATELLITES `8` / HDOP `1.5` / Hz `25` | ✅ 仪表盘风，合适 |
| 时间字符串 | `1:32.457` / `4.21 s` / `Today, 10:35` | ⚠️ 冒号毫秒小数点视觉差，与赛车成绩感不符 |
| **文字状态** | FIX `3D Fix` / QUALITY `Good` / LAST PACKET `Now` / `5s ago` | ❌ **字母严重变形，是用户明确反馈的痛点** |
| 计数 | TOTAL RUNS `24` / SESSIONS `8` / TOTAL LAPS `56` | ⚠️ 介于成绩/仪表之间，用户偏好 Score（成绩感）|

用户反馈（2026-04-30 真机签收时）："不是成绩时间类的显示区域，就不要用七段线数字字体了。很多文本类的现在也被用七段线了，显示效果很差。"

**追加问题（2026-05-01 用户反馈）**：七段字体宽度大于 SansSerif 30%-50%，加上 baseline `Text` 普遍**未设 `maxLines = 1`**，小屏手机（窄宽设备）上多个原本单行的 metric / row / label 字段开始换行，例如 `MetricTile` 的 `label = "BEST 0-100"` 在窄屏被折成两行、`TrackTechRow` 的 `subtitle = "3.063 km · Clockwise"` 同样换行。"该单行显示的也得严格要求了"。

两个问题同源（七段字体宽 + 缺单行约束），本 round 一并解决：
1. 字体角色拆分（Mechanical vs Score）—— 修字母变形
2. 单行强制 + 溢出策略 —— 修小屏换行

修复时机：上一 round `split-records-tab-performance-and-laps` 已合并到 `feature/track-tech-v2` 远程，本 round 是 V2 视觉的一致性收口（双重）。

## What Changes

- **TrackTechTypography 拆分两套字体角色**：
  - `MechanicalHero / MechanicalMedium / MechanicalSmall`（沿用 DSEG7 七段，仅用于纯数字仪表瞬时读数）
  - `ScoreHero / ScoreMedium / ScoreSmall`（FontFamily.SansSerif + ExtraBold + Italic + 数字字号 96/36/20.sp，与 RacingTitle 风格同源，用于成绩 / 时间 / 计数 / 文字状态）
- **`MetricHero / MetricMedium / MetricSmall` 保留作 deprecated alias**，绑到 `Score*`（语义对应"任意 metric"，默认走 Score 更安全），并在 KDoc 标注"deprecated, use Score* or Mechanical* explicitly"
- **`MetricNumber` 加 `kind: MetricKind` 参数**，默认 `MetricKind.Score`：错把 Score 用在 SPEED 上只是视觉不够仪表感（fallback OK），错把 Mechanical 用在文字上是真的字母变形（不可接受），所以默认 Score 更安全
- **`MetricTile` 加 `valueKind: MetricKind` 参数**透传给内部 `MetricNumber`，默认 `MetricKind.Score`
- **`GpsDetailsScreen.DetailMetricTile` 加 `valueKind` 参数**（不通过 MetricTile，是该文件内的 private 实现）
- **批量更新调用方**：每个 `MetricTile` / `MetricNumber` / `MetricMedium` 直接 import 调用点按字段语义显式标注 `kind = MetricKind.Mechanical` 或省略默认走 Score
- **删除/替换直接 import `MetricMedium` 的 3 处**（RecordsHomeScreen:488 / TrackTechTestExecutionScreen:432 / GpsDetailsScreen:631）：用 ScoreMedium 或 MechanicalMedium
- **强制单行 + 溢出策略**（新增 capability `track-tech-card-single-line-policy`）：给 `MetricNumber` / `MetricTile` / `TrackTechRow` / `DetailMetricTile` 内所有应单行的 `Text(...)` 调用加 `maxLines = 1, overflow = TextOverflow.Ellipsis`；具体字段清单与 overflow 策略选择见 design D10 拍板

具体字段分流见 design D5 清单（建议 design 拍板）；单行约束清单见 D10。

## Capabilities

### New Capabilities

- `metric-typography-roles`: TrackTechTypography 内 Mechanical（仪表瞬时数字）vs Score（成绩/时间/文字）字体角色拆分契约 + MetricNumber/MetricTile 透传 kind 参数 + 各调用方的语义分流契约
- `track-tech-card-single-line-policy`: TrackTech V2 视觉系统内所有 metric / row / label 类字段的单行强制契约 —— `MetricNumber` value/unit / `MetricTile` label/status / `TrackTechRow` title/subtitle / `DetailMetricTile` 各字段 / Segmented label 等 MUST 加 `maxLines = 1, overflow = TextOverflow.Ellipsis`，避免窄屏换行

### Modified Capabilities

无（baseline `track-tech-app-shell` 与 `records-home-segmented-views` 中关于 MetricTile 的 Requirement 仅说"渲染指定 label/value/unit"，本 round 不动语义只动字体角色 + 单行约束，不需要 modify 既有 capability spec）

## Impact

### 受影响代码

**字体角色拆分（capability `metric-typography-roles`）+ 单行约束（capability `track-tech-card-single-line-policy`）共同涉及**：

- `feature/test/.../ui/tracktech/TrackTechTypography.kt` — Score* / Mechanical* 双套定义 + Metric* 保留作 alias
- `feature/test/.../ui/tracktech/MetricNumber.kt` — 加 `MetricKind` enum + `kind` 参数 + 按 kind 选 TextStyle；value / unit 两个 `Text(...)` 加 `maxLines = 1, overflow = Ellipsis`
- `feature/test/.../ui/tracktech/MetricTile.kt` — 加 `valueKind` 参数透传；label / status 两个 `Text(...)` 加单行约束
- `feature/test/.../ui/tracktech/TrackTechRow.kt` — title / subtitle 两个 `Text(...)` 加单行约束
- `feature/test/.../ui/tracktech/TestHomeScreen.kt` — PERSONAL BEST / LAST RUN 默认 Score；SPEED hero 显式 Mechanical；`SpeedHero` 内的 STATUS 行加单行
- `feature/test/.../ui/tracktech/TrackTechTestExecutionScreen.kt` — CURRENT SPEED 显式 Mechanical；ELAPSED TIME 改 ScoreMedium（直接 import）；速度 / 时间 `Text(...)` 加单行
- `feature/test/.../ui/tracktech/LapsHomeScreen.kt` — RECENT BEST 默认 Score
- `feature/test/.../ui/tracktech/RecordsHomeScreen.kt` — PERFORMANCE / LAPS 6 个 MetricTile 默认 Score；CURRENT TRACK RECORD 卡内直接用 ScoreMedium 替换 MetricMedium；卡内 `Shanghai Tianma` 标题 / 日期 / `BEST LAP` label 等 `Text(...)` 加单行；`SegmentedControl` 选项文字加单行
- `feature/test/.../ui/tracktech/DeviceHomeScreen.kt` — Quick Status Row 3 个 tile 显式 Mechanical（SATS / RATE 数字；BLE 视实际 value 决定，design 拍板）
- `feature/test/.../ui/tracktech/GpsDetailsScreen.kt` — DetailMetricTile 加 valueKind 参数 + 按字段分流（数字 → Mechanical；文字 → Score）；DetailMetricTile 内 label / value / unit / status 加单行；`freshDisplayStr` 数据预处理（D9）
- `feature/test/.../ui/tracktech/TrackTechBottomNav.kt` — 各 tab item 标签 `Text(...)` 加单行（`Test` / `Laps` / `Records` / `Device` 短文本，单行已是常态但补约束防御）

### 不受影响

- `core/*` 全部模块、`simulator/*` 全部模块
- `app/*`、`TrackTechAppShell` / `TrackTechBottomNav` / Pager 逻辑
- `MetricTile` 内部布局（label / value / status 三段不变）
- 数据层（不接真实数据）
- BLE / GPS 数据链路、RaceChrono BLE 协议

### 协议兼容性

无协议改动。

### 双端

仅接收端（gps-app）改动；发射端（simulator）不动。

### 依赖

无新依赖。`FontFamily.SansSerif` + `FontWeight.ExtraBold` + `FontStyle.Italic` 已在 RacingTitle 系列使用。

### 测试影响

- 本 round **不**新增单元测试（纯 UI 字体角色调整，无可观察行为契约）
- 现有 `:feature:test:testDebugUnitTest` 全套 MUST 零回归（含 `TrackTechAppShellPagerTest` / `TabGatingPolicyTest` / TestSessionViewModel 套件）
- 真机视觉验证：手机上对比所有 metric 字段渲染：仪表读数仍是七段，文字 / 时间 / 计数 改为 SansSerif Italic Bold
