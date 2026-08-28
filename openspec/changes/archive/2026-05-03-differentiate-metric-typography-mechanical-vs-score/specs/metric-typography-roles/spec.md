## ADDED Requirements

### Requirement: TrackTechTypography 拆分 Mechanical 与 Score 两套字体角色

`TrackTechTypography` MUST 包含两套独立的 metric 字体角色，按字段语义分流：

- **Mechanical** 系列（仪表瞬时数字）：`MechanicalHero` / `MechanicalMedium` / `MechanicalSmall`
  - `fontFamily = Dseg7FontFamily`（DSEG7 七段数码字体）
  - `fontWeight = FontWeight.Normal`，`fontStyle = FontStyle.Normal`
  - 字号分别为 `96.sp` / `36.sp` / `20.sp`
- **Score** 系列（成绩 / 时间 / 计数 / 文字状态）：`ScoreHero` / `ScoreMedium` / `ScoreSmall`
  - `fontFamily = FontFamily.SansSerif`，`fontWeight = FontWeight.ExtraBold`，`fontStyle = FontStyle.Italic`
  - 字号分别为 `96.sp` / `36.sp` / `20.sp`
  - `letterSpacing` 与 RacingTitle 系列错开（避免视觉重叠），建议 `0.02.em`

baseline 中的 `MetricHero` / `MetricMedium` / `MetricSmall` MUST 保留作 deprecated alias 绑到对应 Score 系列（`MetricHero = ScoreHero` 等），并加 `@Deprecated` 注解 + `ReplaceWith`，让 IDE 红色提示推荐 `Score*` 或 `Mechanical*`。

#### Scenario: TrackTechTypography 同时含 Mechanical 与 Score 三个字号

- **GIVEN** 实施后 `feature/test/.../ui/tracktech/TrackTechTypography.kt` 源码
- **WHEN** grep `MechanicalHero` / `MechanicalMedium` / `MechanicalSmall` 与 `ScoreHero` / `ScoreMedium` / `ScoreSmall`
- **THEN** 6 个 TextStyle 值定义全部命中
- **AND** Mechanical 系列 3 个的 `fontFamily` 引用 `Dseg7FontFamily`
- **AND** Score 系列 3 个的 `fontFamily` 引用 `FontFamily.SansSerif`，`fontWeight` 含 `ExtraBold`，`fontStyle` 含 `Italic`

#### Scenario: Metric* 保留作 deprecated alias

- **GIVEN** 实施后 `TrackTechTypography.kt` 源码
- **WHEN** grep `val MetricHero` / `val MetricMedium` / `val MetricSmall`
- **THEN** 3 个 alias 定义命中
- **AND** 每个 alias 上方含 `@Deprecated(...)` 注解
- **AND** alias 的值绑到对应 Score 系列（`MetricHero = ScoreHero` / `MetricMedium = ScoreMedium` / `MetricSmall = ScoreSmall`），与默认 kind = Score 行为对齐

#### Scenario: Dseg7FontFamily 仅被 Mechanical 系列引用

- **GIVEN** 实施后 `TrackTechTypography.kt` 源码
- **WHEN** grep `Dseg7FontFamily` 的所有引用点
- **THEN** 仅 `MechanicalHero` / `MechanicalMedium` / `MechanicalSmall` 三处引用（`MechanicalMedium` / `MechanicalSmall` 通过 `MechanicalHero.copy(...)` 间接引用也算）
- **AND** Score 系列与 RacingTitle / UiText* 等其他角色 **不**引用 `Dseg7FontFamily`

### Requirement: MetricNumber / MetricTile 加 MetricKind 参数

`MetricNumber.kt` MUST 引入 `enum class MetricKind { Mechanical, Score }`。

`MetricNumber` Composable 函数签名 MUST 加 `kind: MetricKind` 参数，**默认值 `MetricKind.Score`**。函数体内按 `(kind, size)` 二维选 TextStyle：

| kind | size = Hero | size = Medium | size = Small |
|---|---|---|---|
| Mechanical | MechanicalHero | MechanicalMedium | MechanicalSmall |
| Score | ScoreHero | ScoreMedium | ScoreSmall |

`MetricTile` Composable 函数签名 MUST 加 `valueKind: MetricKind` 参数，**默认值 `MetricKind.Score`**，透传给内部 `MetricNumber` 的 `kind` 参数。

#### Scenario: MetricKind enum 定义

- **GIVEN** 实施后 `feature/test/.../ui/tracktech/MetricNumber.kt` 源码
- **WHEN** grep `enum class MetricKind`
- **THEN** 命中定义且包含 `Mechanical` / `Score` 两个枚举值
- **AND** 顺序无强制要求

#### Scenario: MetricNumber 函数签名含 kind 参数（默认 Score）

- **GIVEN** 实施后 `MetricNumber.kt` 源码
- **WHEN** 阅读 `@Composable fun MetricNumber(...)` 函数签名
- **THEN** 含参数 `kind: MetricKind`
- **AND** 该参数默认值为 `MetricKind.Score`

#### Scenario: MetricTile 函数签名含 valueKind 参数（默认 Score）

- **GIVEN** 实施后 `feature/test/.../ui/tracktech/MetricTile.kt` 源码
- **WHEN** 阅读 `@Composable fun MetricTile(...)` 函数签名
- **THEN** 含参数 `valueKind: MetricKind`
- **AND** 该参数默认值为 `MetricKind.Score`

#### Scenario: MetricNumber 按 kind 选 TextStyle

- **GIVEN** 实施后 `MetricNumber.kt` 内 `numberStyle` 派生表达式
- **WHEN** 阅读 `when (kind) { ... }` 分支或等价结构
- **THEN** `MetricKind.Mechanical` 分支映射到 `MechanicalHero` / `MechanicalMedium` / `MechanicalSmall`（按 size 子分支）
- **AND** `MetricKind.Score` 分支映射到 `ScoreHero` / `ScoreMedium` / `ScoreSmall`（按 size 子分支）

#### Scenario: MetricTile 透传 valueKind 给 MetricNumber

- **GIVEN** 实施后 `MetricTile.kt` 内 `MetricNumber(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** 含 `kind = valueKind`（参数名不限，但 `valueKind` MUST 被透传给 MetricNumber 的 `kind`）

### Requirement: 仪表瞬时数字字段显式标注 MetricKind.Mechanical

以下字段的调用点 MUST 显式传入 `valueKind = MetricKind.Mechanical`（MetricTile）或 `kind = MetricKind.Mechanical`（直接用 MetricNumber），渲染纯数字仪表瞬时读数：

- **`TestHomeScreen.kt`** SPEED hero（`MetricNumber` 直接调用）
- **`DeviceHomeScreen.kt`** Quick Status Row 内 SATELLITES (SATS) tile + RATE tile（数字 value）
- **`GpsDetailsScreen.kt`** SATELLITES / HDOP / RATE / FRESH / DROPPED 五个 `DetailMetricTile`（数字 value）
- **`TrackTechTestExecutionScreen.kt`** CURRENT SPEED MetricHero（直接 `style = TrackTechTypography.MechanicalHero`，不通过 MetricNumber）

`DeviceHomeScreen.kt` BLE tile 的 kind 取决于 baseline value 是数字还是文字字符串：apply 阶段 §0 grep 确认 value 表达式，数字 → Mechanical，文字 → Score（默认值，不显式标注）。

#### Scenario: TestHomeScreen SPEED hero 显式 Mechanical

- **GIVEN** 实施后 `TestHomeScreen.kt` 内 SPEED hero 的 `MetricNumber(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** 含 `kind = MetricKind.Mechanical`

#### Scenario: DeviceHomeScreen SATS / RATE 显式 Mechanical

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 内 SATS / RATE 两个 `MetricTile(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** 各含 `valueKind = MetricKind.Mechanical`

#### Scenario: GpsDetailsScreen 五个数字 DetailMetricTile 显式 Mechanical

- **GIVEN** 实施后 `GpsDetailsScreen.kt` 内 SATELLITES / HDOP / RATE / FRESH / DROPPED 五个 `DetailMetricTile(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** 各含 `valueKind = MetricKind.Mechanical`

#### Scenario: FRESH 字段数据预处理拆分字母到 unit

- **GIVEN** 实施后 `GpsDetailsScreen.kt` 内 `DataStreamGrid` 函数的 `freshDisplayStr` / `freshDisplayUnit` 派生表达式
- **WHEN** 阅读 1-9.9 秒区间分支（`freshMs in 1000L..9999L` 或等价范围）
- **THEN** `freshDisplayStr` 该分支的值 MUST 不含字母（如 `"%.1f".format(freshMs / 1000.0)` 返回 `"1.2"`）
- **AND** `freshDisplayUnit` 该分支的值 MUST 为字符串 `"s"`（字母走 unit 参数，由 MetricNumber 内 unit 文本的 UiTextSmall 渲染，不受 Mechanical 字体影响）

#### Scenario: FRESH value 字符串内零字母

- **GIVEN** 实施后 `GpsDetailsScreen.kt` 内 `freshDisplayStr` 三个分支
- **WHEN** 检查每个分支返回值的字符内容
- **THEN** 各分支返回值仅可能为：纯数字（毫秒整数 / 秒小数）或单字符 `"—"`（U+2014 em-dash）；MUST 不包含 `s` 或 `ms` 等字母

#### Scenario: TrackTechTestExecutionScreen CURRENT SPEED 用 MechanicalHero TextStyle

- **GIVEN** 实施后 `TrackTechTestExecutionScreen.kt` 内 CURRENT SPEED 的 `Text(...)` 调用
- **WHEN** 阅读 `style` 参数
- **THEN** 引用 `TrackTechTypography.MechanicalHero`（不再引用 `MetricHero`）

### Requirement: 文字 / 时间 / 计数字段使用默认 Score

以下字段 MUST 走默认 `MetricKind.Score`（即调用点 **不**传 `valueKind` / `kind`，依赖默认值；或显式传 `MetricKind.Score` 也可接受）：

- **`TestHomeScreen.kt`** PERSONAL BEST + LAST RUN（时间型 MetricTile）
- **`LapsHomeScreen.kt`** RECENT BEST（时间型）
- **`RecordsHomeScreen.kt`** PERFORMANCE 视图 BEST 0-100 / BEST BRAKE / TOTAL RUNS + LAPS 视图 BEST LAP / SESSIONS / TOTAL LAPS（共 6 个 MetricTile）
- **`RecordsHomeScreen.kt`** CURRENT TRACK RECORD 卡内 BEST LAP（直接用 `TrackTechTypography.ScoreMedium` TextStyle）
- **`TrackTechTestExecutionScreen.kt`** ELAPSED TIME（直接用 `TrackTechTypography.ScoreMedium` TextStyle）
- **`GpsDetailsScreen.kt`** FIX / QUALITY / LAST PACKET 三个 `DetailMetricTile`（文字 value）

#### Scenario: TestHomeScreen / LapsHomeScreen / RecordsHomeScreen 默认 kind 调用点

- **GIVEN** 实施后上述文件内对应字段的 `MetricTile(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** **不**显式传 `valueKind` 参数（依赖默认值 `MetricKind.Score`）；若显式传 `valueKind = MetricKind.Score` 也接受

#### Scenario: RecordsHomeScreen CURRENT TRACK RECORD BEST LAP 用 ScoreMedium

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 CURRENT TRACK RECORD 卡片的 `Text(...)` 调用
- **WHEN** 阅读 `style` 参数
- **THEN** 引用 `TrackTechTypography.ScoreMedium`（不再引用 `MetricMedium`）

#### Scenario: TrackTechTestExecutionScreen ELAPSED TIME 用 ScoreMedium

- **GIVEN** 实施后 `TrackTechTestExecutionScreen.kt` 内 ELAPSED TIME 的 `Text(...)` 调用
- **WHEN** 阅读 `style` 参数
- **THEN** 引用 `TrackTechTypography.ScoreMedium`（不再引用 `MetricMedium`）

#### Scenario: GpsDetailsScreen 三个文字字段不显式 Mechanical

- **GIVEN** 实施后 `GpsDetailsScreen.kt` 内 FIX / QUALITY / LAST PACKET 三个 `DetailMetricTile(...)` 调用
- **WHEN** 阅读传入的命名参数
- **THEN** **不**显式传 `valueKind = MetricKind.Mechanical`（依赖默认 Score；若显式传 `valueKind = MetricKind.Score` 也接受）

### Requirement: GpsDetailsScreen.DetailMetricTile 加 valueKind 参数

`GpsDetailsScreen.kt` 文件内的 `private fun DetailMetricTile(...)` MUST 加 `valueKind: MetricKind` 参数，**默认值 `MetricKind.Score`**，函数体内按 `valueKind` 选 `MechanicalMedium` 或 `ScoreMedium` 作为 value `Text` 的 `style`。

`DetailMetricTile` MUST NOT 重构为复用 `MetricTile`（baseline 视觉差异：紫色描边 + dot color status 等），仅加参数最小化改动。

#### Scenario: DetailMetricTile 函数签名含 valueKind 参数

- **GIVEN** 实施后 `GpsDetailsScreen.kt` 内 `private fun DetailMetricTile(...)` 函数签名
- **WHEN** 阅读参数列表
- **THEN** 含参数 `valueKind: MetricKind`
- **AND** 默认值为 `MetricKind.Score`

#### Scenario: DetailMetricTile 按 valueKind 选 TextStyle

- **GIVEN** 实施后 `GpsDetailsScreen.kt` 内 `DetailMetricTile` body 的 value `Text(...)` 调用
- **WHEN** 阅读 `style` 参数
- **THEN** 含 `when (valueKind) { ... }` 或等价分支，分别映射 `MechanicalMedium` / `ScoreMedium`
- **AND** **不**直接使用 `TrackTechTypography.MetricMedium`（deprecated）

### Requirement: 直接 import MetricMedium 的位置全部替换

baseline 中**直接** `import` 或引用 `TrackTechTypography.MetricMedium`（不通过 `MetricNumber` / `MetricTile`）的 3 处 MUST 全部替换为对应的 `ScoreMedium` 或 `MechanicalMedium`：

- `RecordsHomeScreen.kt:488` (CURRENT TRACK RECORD 卡内 BEST LAP) → `ScoreMedium`
- `TrackTechTestExecutionScreen.kt:432` (ELAPSED TIME) → `ScoreMedium`
- `GpsDetailsScreen.kt:631` (DetailMetricTile 内部 value Text) → 由 `valueKind` 参数派生（`ScoreMedium` 或 `MechanicalMedium`）

实施后 `tracktech/` 子包内任何 `.kt` 文件 grep `TrackTechTypography.MetricMedium` 应零命中（除了 `TrackTechTypography.kt` 自身的 deprecated alias 定义）；`TrackTechTypography.MetricHero` / `MetricSmall` 同样零命中（除 alias 定义）。

#### Scenario: tracktech 子包内 MetricMedium 引用零命中

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 子包全部 `.kt` 文件
- **WHEN** grep `TrackTechTypography.MetricMedium`
- **THEN** 命中点仅限于 `TrackTechTypography.kt` 自身的 alias 定义行（`val MetricMedium = ScoreMedium`）
- **AND** 其他文件内零命中

#### Scenario: tracktech 子包内 MetricHero / MetricSmall 引用零命中

- **GIVEN** 实施后 `tracktech/` 子包全部 `.kt` 文件
- **WHEN** grep `TrackTechTypography.MetricHero` / `TrackTechTypography.MetricSmall`
- **THEN** 命中点仅限于 `TrackTechTypography.kt` 自身的 alias 定义行
- **AND** 其他文件内零命中

### Requirement: 不引入新依赖且不改其他 typography 角色

本 round MUST NOT：

- 引入新的 Compose / 字体 / 测试依赖
- 修改 `RacingTitleLarge` / `RacingTitleMedium` / `RacingTitleSmall` 三个 RacingTitle 角色
- 修改 `UiTextBody` / `UiTextSmall` / `UiTextLabel` 三个 UiText 角色
- 修改 `MetricTile` / `MetricNumber` 的视觉布局（label / value / unit / status 排列、padding、CutCornerPanel 切角等）
- 修改任何 `MetricSize` 枚举的字号（Hero 96.sp / Medium 36.sp / Small 20.sp 保持）

#### Scenario: 不动 RacingTitle / UiText 角色

- **GIVEN** 实施前后 `TrackTechTypography.kt` 内 `RacingTitle*` / `UiText*` 三组 TextStyle
- **WHEN** `git diff <baseline>..HEAD` 这些 TextStyle 对应行
- **THEN** 零行改动

#### Scenario: 不动 MetricTile 视觉布局

- **GIVEN** 实施前后 `MetricTile.kt` 内 `CutCornerPanel(...)` 调用与 label / value / status 三段 `Text(...)` 的 modifier 链
- **WHEN** `git diff` 这些行
- **THEN** 仅 `MetricNumber(...)` 调用新增 `kind = valueKind` 参数；其余视觉布局零改动

#### Scenario: 不引入新 testImplementation / implementation

- **GIVEN** 实施前后 `feature/test/build.gradle.kts`
- **WHEN** `git diff` 该文件
- **THEN** 零行改动（本 round 不引入 ui-test-junit4 等新依赖）
