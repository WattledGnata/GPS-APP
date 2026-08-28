## Context

`TrackTechTypography` 是 Track Tech V2 视觉系统的字体角色集合。当前 Metric 系列三个 size 都用 DSEG7 七段字体，所有 `MetricTile` / `MetricNumber` 都是七段。但调用方不区分字段语义（仪表瞬时 vs 成绩 vs 文字），导致七段被滥用在不该用的字段上（FIX `3D Fix` / QUALITY `Good` 这种纯文字）。

约束：
- 不引入新依赖
- 不改 `MetricTile` 内部布局结构
- 不改其他 typography 角色（RacingTitle / UiText* 等）
- 不接真实数据
- baseline `add-track-tech-app-shell` 的 spec 对 MetricTile 只要求"渲染 label/value/unit"，本 round 不动这层语义

## Goals / Non-Goals

**Goals:**

- TrackTechTypography 拆分 Mechanical（仪表瞬时数字，DSEG7）vs Score（成绩/时间/文字，SansSerif Italic Bold）双套字体角色
- `MetricNumber` 加 `MetricKind` 参数；`MetricTile` 加 `valueKind` 参数透传
- 默认值选 `MetricKind.Score`：错用 Score 在仪表上只是不够仪表感（视觉降级，可接受）；错用 Mechanical 在文字上是字母严重变形（不可接受），默认 Score 更安全
- 批量更新所有调用方，按字段语义显式标注（仪表点用 `Mechanical`，其他默认 Score）
- 旧 `MetricHero/Medium/Small` 保留作 deprecated alias 绑到 Score（向后兼容）

**Non-Goals:**

- 不重做 typography 系统其他角色
- 不改 `MetricTile` 视觉结构 / `CutCornerPanel` 切角 / 颜色 token
- 不引入领域级 `MetricKind` 概念（仅 UI typography 层语义）
- 不为每个字段单独写测试（外部无可观察行为契约，靠真机视觉兜底）
- 不调整字段尺寸（Hero/Medium/Small 三档保持，不引入 XS/XL）

## Decisions

### D1：双套字体角色 + `MetricKind` enum

**决定**：

```kotlin
// TrackTechTypography.kt
private val Dseg7FontFamily = FontFamily(
    Font(R.font.dseg7_classic_bold, FontWeight.Normal)
)

object TrackTechTypography {
    // Mechanical —— 纯数字仪表瞬时读数（DSEG7 七段）
    val MechanicalHero = TextStyle(
        fontFamily = Dseg7FontFamily,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Normal,
        fontSize = 96.sp,
        letterSpacing = 0.em,
    )
    val MechanicalMedium = MechanicalHero.copy(fontSize = 36.sp)
    val MechanicalSmall = MechanicalHero.copy(fontSize = 20.sp)

    // Score —— 成绩 / 时间 / 计数 / 文字状态（SansSerif Italic Bold）
    val ScoreHero = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 96.sp,
        letterSpacing = 0.02.em,
    )
    val ScoreMedium = ScoreHero.copy(fontSize = 36.sp)
    val ScoreSmall = ScoreHero.copy(fontSize = 20.sp)

    // Deprecated alias —— 默认走 Score（与 MetricKind 默认值一致）
    @Deprecated("Use ScoreHero or MechanicalHero explicitly", ReplaceWith("ScoreHero"))
    val MetricHero = ScoreHero
    @Deprecated("Use ScoreMedium or MechanicalMedium explicitly", ReplaceWith("ScoreMedium"))
    val MetricMedium = ScoreMedium
    @Deprecated("Use ScoreSmall or MechanicalSmall explicitly", ReplaceWith("ScoreSmall"))
    val MetricSmall = ScoreSmall

    // RacingTitle / UiText* 不变
    ...
}

// MetricNumber.kt
enum class MetricKind { Mechanical, Score }
```

**为什么不直接删除 Metric\***：删除会让所有 baseline 调用方编译报错，扩大本 round 的 scope（必须立刻批改全部）。保留 deprecated alias 让旧调用点继续编译，逐步迁移更安全。

**为什么不把 Metric\* 改名为 Score\* 让 IDE 自动重命名**：旧 `MetricHero/Medium/Small` 在 baseline 中语义是"通用 metric 字体"，重命名为 Score 在历史 git blame 上会丢失语义。新建 `Mechanical*` + `Score*` 双套清晰，旧名作为兼容层 deprecated。

**为什么 ScoreSmall 用 20.sp（与 MechanicalSmall 字号一致）**：保持 size 维度独立于 kind 维度，调用方按 size 选大小、按 kind 选风格，正交清晰。

**替代方案考虑**：
- ❌ `MetricKind.Auto`（按 value 字符串内容启发判断）：脆弱，如 "1:32.457" 含冒号被判文字、纯数字被判仪表，但 "24" 应该是计数（Score）—— 字符串无法可靠区分
- ❌ 调用方传 `TextStyle` 直接覆盖：解耦但每个调用点都要写 `TrackTechTypography.ScoreMedium`，啰嗦
- ✅ `MetricKind` enum + `MetricNumber.kind` / `MetricTile.valueKind` 参数（本方案）：API 稳定，语义明确

### D2：MetricKind 默认值 `Score`

**决定**：`MetricNumber.kind` 与 `MetricTile.valueKind` 默认值都是 `MetricKind.Score`。

**理由**：

| 场景 | 错用 Mechanical 在文字（默认 Mechanical 风险） | 错用 Score 在仪表（默认 Score 风险）|
|---|---|---|
| FIX `"3D Fix"` | ❌ 字母严重变形 | ⚠️ 视觉降级（可读但不够仪表感）|
| QUALITY `"Good"` | ❌ 字母变形 | ⚠️ 同上 |
| LAST PACKET `"Now"` | ❌ 字母变形 | ⚠️ 同上 |
| SPEED `132` | ✅ 仪表感正确 | ⚠️ 不够仪表感（但可读）|
| SATS `8` | ✅ 仪表感正确 | ⚠️ 同上 |

错用 Mechanical 是不可接受的视觉缺陷，错用 Score 只是视觉降级 —— 默认 Score 更安全。

**为什么不要求所有调用方显式传 kind（无默认值）**：会让现有 baseline 编译报错（必须立刻批改），扩大 scope。默认值 = "默认安全" 让迁移渐进可行。

### D3：MetricNumber 与 MetricTile API 改造

**决定**：

```kotlin
// MetricNumber.kt
@Composable
fun MetricNumber(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    size: MetricSize = MetricSize.Medium,
    kind: MetricKind = MetricKind.Score,    // 新增
    valueColor: Color = TrackTechColors.TextPrimary,
    unitColor: Color = TrackTechColors.TextSecondary,
) {
    val numberStyle: TextStyle = when (kind) {
        MetricKind.Mechanical -> when (size) {
            MetricSize.Hero -> TrackTechTypography.MechanicalHero
            MetricSize.Medium -> TrackTechTypography.MechanicalMedium
            MetricSize.Small -> TrackTechTypography.MechanicalSmall
        }
        MetricKind.Score -> when (size) {
            MetricSize.Hero -> TrackTechTypography.ScoreHero
            MetricSize.Medium -> TrackTechTypography.ScoreMedium
            MetricSize.Small -> TrackTechTypography.ScoreSmall
        }
    }
    // ... 其他逻辑不变
}

// MetricTile.kt
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    status: String? = null,
    accentColor: Color = TrackTechColors.Cyan,
    valueSize: MetricSize = MetricSize.Medium,
    valueKind: MetricKind = MetricKind.Score,    // 新增
) {
    // ... 透传 valueKind 给 MetricNumber
    MetricNumber(
        value = value,
        unit = unit,
        size = valueSize,
        kind = valueKind,
        ...
    )
}
```

**为什么 MetricTile 也加 valueKind（不仅 MetricNumber）**：MetricTile 是大多数调用方使用的 API，让它直接暴露 `valueKind` 比让调用方自己用 MetricNumber 更符合现有调用模式。

### D4：GpsDetailsScreen.DetailMetricTile 单独加 valueKind

**决定**：`GpsDetailsScreen.kt` 内的 `private fun DetailMetricTile` 与外部 `MetricTile` 是独立实现（紫色描边变体）。**不**复用 `MetricTile`，仅给 `DetailMetricTile` 加 `valueKind: MetricKind = MetricKind.Score` 参数：

```kotlin
@Composable
private fun DetailMetricTile(
    label: String,
    value: String,
    unit: String?,
    status: String?,
    dotColor: Color,
    modifier: Modifier = Modifier,
    valueColor: Color = TrackTechColors.TextPrimary,
    valueKind: MetricKind = MetricKind.Score,    // 新增
) {
    // ...
    Text(
        text = value,
        style = when (valueKind) {
            MetricKind.Mechanical -> TrackTechTypography.MechanicalMedium
            MetricKind.Score -> TrackTechTypography.ScoreMedium
        },
        color = valueColor,
    )
    // ...
}
```

**为什么不重构 DetailMetricTile 复用 MetricTile**：`DetailMetricTile` 有紫色描边 + dot color 状态 + 不同 padding，与 baseline `MetricTile` 视觉差异大，重构会扩大 scope。仅加 valueKind 参数最小化改动。

### D5：调用方字段分流清单

**决定**：按字段语义分类，每个调用点显式或隐式选择 kind。

**保留 Mechanical（DSEG7 七段，仪表瞬时数字）**：

| 文件 / 行号 | 字段 | value 内容 | 显式标注 |
|---|---|---|---|
| `TestHomeScreen.kt:210` | SPEED hero | `gpsData.speed.toInt().toString()` | `kind = MetricKind.Mechanical` |
| `TrackTechTestExecutionScreen.kt:410` | CURRENT SPEED MetricHero（直接 TextStyle）| `%.1f km/h` | 直接用 `TrackTechTypography.MechanicalHero` |
| `DeviceHomeScreen.kt:274` | BLE tile | 见 D6 拍板 | 待 D6 |
| `DeviceHomeScreen.kt:291` | SATS tile | 卫星数字 | `valueKind = MetricKind.Mechanical` |
| `DeviceHomeScreen.kt:300` | RATE tile | Hz 数字 | `valueKind = MetricKind.Mechanical` |
| `GpsDetailsScreen.kt` SATELLITES | 卫星数字 | `gpsData.satelliteCount.toString()` | `valueKind = MetricKind.Mechanical` |
| `GpsDetailsScreen.kt` HDOP | `%.1f` | 数字 | `valueKind = MetricKind.Mechanical` |
| `GpsDetailsScreen.kt` RATE | Hz 数字 | `frequencyHz.toString()` | `valueKind = MetricKind.Mechanical` |
| `GpsDetailsScreen.kt` FRESH | 纯数字（拆字母后）| `freshDisplayStr`（见 D9：数据预处理拆出 `s` / `ms` 到 unit）| `valueKind = MetricKind.Mechanical` |
| `GpsDetailsScreen.kt` DROPPED | 百分比数字 | `droppedStr` | `valueKind = MetricKind.Mechanical` |

**默认 Score（SansSerif Italic Bold）—— 不需要显式标注，依赖默认值**：

| 文件 / 行号 | 字段 | value 内容 |
|---|---|---|
| `TestHomeScreen.kt:167` | PERSONAL BEST | `—.—s`（时间）|
| `TestHomeScreen.kt:176` | LAST RUN | 时间 |
| `TrackTechTestExecutionScreen.kt:432` | ELAPSED TIME（直接 TextStyle）| 时间 → 直接用 `TrackTechTypography.ScoreMedium` |
| `LapsHomeScreen.kt:165` | RECENT BEST | `1:32.457` 时间 |
| `RecordsHomeScreen.kt:132/140/148` | PERFORMANCE 视图 BEST 0-100 / BEST BRAKE / TOTAL RUNS | 时间/距离/计数 |
| `RecordsHomeScreen.kt:395/404/413` | LAPS 视图 BEST LAP / SESSIONS / TOTAL LAPS | 时间/计数 |
| `RecordsHomeScreen.kt:488` | CURRENT TRACK RECORD 卡内 BEST LAP | `1:32.457` → 直接用 `TrackTechTypography.ScoreMedium` |
| `GpsDetailsScreen.kt` FIX | `"3D Fix"` 等文字 | 默认 Score（无须显式）|
| `GpsDetailsScreen.kt` QUALITY | `"Good"` 等文字 | 默认 Score |
| `GpsDetailsScreen.kt` LAST PACKET | `"Now"` / `"5s ago"` 文字 | 默认 Score |

### D6：DeviceHomeScreen BLE tile 字段确认

**决定**：DeviceHomeScreen 274 行的 BLE tile 当前 value 是连接状态字符串（`"Connected"` / `"-"` 等文字），**改 Score**（`valueKind` 省略，默认 Score）。SATS / RATE 是数字，**改 Mechanical**（显式标注 `valueKind = MetricKind.Mechanical`）。

**实施时确认**：apply 阶段 §0 grep 预检读 baseline 该 tile 的 value 表达式，若实际是数字（如 `connectedDeviceCount` 计数）则改 Mechanical。

### D7：直接 import MetricMedium 的 3 处

**决定**：

| 文件 / 行号 | 当前 | 改为 |
|---|---|---|
| `RecordsHomeScreen.kt:488` | `style = TrackTechTypography.MetricMedium`（CURRENT TRACK RECORD 卡内 BEST LAP）| `style = TrackTechTypography.ScoreMedium` |
| `TrackTechTestExecutionScreen.kt:432` | `style = TrackTechTypography.MetricMedium`（ELAPSED TIME）| `style = TrackTechTypography.ScoreMedium` |
| `GpsDetailsScreen.kt:631` | `style = TrackTechTypography.MetricMedium`（DetailMetricTile 内部）| 改为按 `valueKind` 参数选 `ScoreMedium` 或 `MechanicalMedium`（D4）|

deprecated `MetricMedium` 现绑到 `ScoreMedium`，所以这 3 处即便不改也是 Score 行为。但显式改名可读性更好，避免 IDE 报 deprecated warning。

### D8：测试范围

**决定**：本 round **不**新增单元测试。

**理由**：
- 字体角色是纯视觉契约，无可观察的运行时行为
- `Score*` / `Mechanical*` TextStyle 值的对比靠 `git diff` 可读
- 调用方分流由 grep + 真机视觉兜底
- 引入 ComposeRule 测试需 ui-test-junit4 依赖（参见上一 round 同样决策），超本 round scope

**Follow-up backlog（不在本 round）**：未来若引入 ComposeRule，可加 "render value with Mechanical kind contains DSEG7 font" 的 semantic test。

### D9：FRESH 字段数据预处理拆分（避免字母进 Mechanical）

**问题**：`GpsDetailsScreen.DataStreamGrid` 内 `freshDisplayStr` baseline 实现：

```kotlin
val freshDisplayStr = when {
    freshMs <= 999L -> freshMs.toString()                                   // "523"
    freshMs <= 9999L -> "${freshMs / 1000}.${(freshMs % 1000) / 100}s"      // "1.2s"  ← 字母 s 内联到 value
    else -> "—"
}
val freshDisplayUnit: String? = when {
    freshMs <= 999L -> "ms"
    freshMs <= 9999L -> null     // ← 因为 s 已经在 value 里了，所以 unit = null
    else -> null
}
```

如果按 D5 把 FRESH 标 Mechanical，**1-9.9s 区间的 `s` 字母仍会被 DSEG7 字体渲染** —— 字母变形问题没修。

**决定**：实施时同步调整 `freshDisplayStr` / `freshDisplayUnit` 数据预处理逻辑，把秒级单位字母拆到 unit 参数：

```kotlin
val freshDisplayStr = when {
    freshMs <= 999L -> freshMs.toString()              // "523"
    freshMs <= 9999L -> "%.1f".format(freshMs / 1000.0) // "1.2"  ← 纯数字
    else -> "—"
}
val freshDisplayUnit: String? = when {
    freshMs <= 999L -> "ms"
    freshMs <= 9999L -> "s"      // ← 字母走 unit，由 MetricNumber 内 unit 文本（UiTextSmall SansSerif）渲染
    else -> null
}
```

`MetricNumber` 内部 unit `Text` 已用 `UiTextSmall`（SansSerif 普通字体），不受 Mechanical / Score kind 影响。这样 Mechanical 只吃数字，单位字母走普通文本字体，问题彻底解决。

**为什么不把 FRESH 改为 Score 兜底**：
- FRESH 的语义是仪表瞬时读数（数据延迟毫秒数），与 SATS / Hz / DROPPED 同类
- 改 Score 会让所有 FRESH 值（含 `523` 三位数字）失去仪表感，与同 Row 邻居 RATE / DROPPED 视觉不一致
- 拆数据更干净，符合 MetricNumber 的设计意图（数字 value + 字母 unit 分离）

**为什么 `else -> "—"` 边界不动**：em-dash 字符（U+2014）不在 DSEG7 字体表内，会 fallback 到系统字体显示，**不**触发字母变形。即便没拆也安全。但仍记入 follow-up backlog（D9.x）作为"清理字母从 value 流"的完整收尾。

**改动文件**：`GpsDetailsScreen.kt` 内 `DataStreamGrid` 函数（约 line 388-407）。

### D10：单行强制 + 溢出策略（capability `track-tech-card-single-line-policy`）

**问题**：baseline 的 `Text(...)` 普遍**未设 `maxLines`**，默认行为是按容器宽度自动换行。七段字体宽度大于 SansSerif 30%-50%，加上某些 label / subtitle 字符串较长，小屏手机上窄宽容器内多个原本预期单行的字段被折成两行：

| 字段 | 字符串 | baseline 在窄屏行为 |
|---|---|---|
| `MetricTile.label` | `"BEST 0-100"` `"PERSONAL BEST"` `"CURRENT TRACK RECORD"` 等 | 长 label 折行 |
| `MetricTile.value` | `"1:32.457"` 等 | 七段下宽度大，可能折行 |
| `TrackTechRow.subtitle` | `"3.063 km · Clockwise"` `"4.21 s · May 18, 2024 · Personal Best"` 等 | 折行 |
| `DetailMetricTile.value` | `"3D Fix"` `"5s ago"` 等 | 文字字段折行 |
| `SegmentedControl` 选项 | `"PERFORMANCE"` `"LAPS"` | PERFORMANCE 在极窄屏可能折 |

**决定**：本 round 在所有"应单行"的 metric / row / label 类 `Text(...)` 调用上加：

```kotlin
Text(
    text = ...,
    style = ...,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)
```

策略统一：**`maxLines = 1` + `overflow = TextOverflow.Ellipsis`**（溢出时尾部省略号），**不**用 `softWrap = false`（会让超长文本被裁切而非省略号，体验更差）。

**应加单行约束的字段清单**（按文件分组）：

| 文件 | 字段 / 调用点 | 类别 |
|---|---|---|
| `MetricNumber.kt` | value `Text` + unit `Text`（约 line 40-50）| 数字 + 单位 |
| `MetricTile.kt` | label `Text` + status `Text`（约 line 38-56）| 标签 + 状态 |
| `TrackTechRow.kt` | title `Text` + subtitle `Text`（约 line 56-74）| 行内主副文 |
| `GpsDetailsScreen.kt` `DetailMetricTile` | label / value / unit / status 4 个 `Text` | DetailTile 内 |
| `RecordsHomeScreen.kt` | CURRENT TRACK RECORD 卡内 `Shanghai Tianma` / `1:32.457` / `May 18, 2024` 等直接 `Text` 调用；`SegmentedControl` 选项 `Text` | 卡内独立文本 |
| `TestHomeScreen.kt` | `SpeedHero` 内 SPEED label / STATUS 行的 `Text` | Hero 内文本 |
| `LapsHomeScreen.kt` | `CurrentTrackPanel` 标题 + 副文 / `RECENT BEST` label 等 | 卡内文本 |
| `DeviceHomeScreen.kt` | Readiness Hero 主副标 / Quick Status Row tile 内文本 | Hero + Tile |
| `TrackTechTestExecutionScreen.kt` | `CURRENT SPEED` / `ELAPSED TIME` 等 hero 字段；阶段 banner phaseTag / phaseTitle / phaseSub | 状态文本 |
| `TrackTechBottomNav.kt` | 4 个 tab item label `Test` / `Laps` / `Records` / `Device`（短文本但补防御）| 防御性 |

**MUST NOT 加单行约束的字段**：

- 长描述类 / 列表内可换行 paragraphs（本项目当前没有）
- Toast 内文本（系统级）
- 标题如 `Records` `Drive Test`（短文本不会换行，但加 maxLines 也无害；为一致性可加可不加）

**为什么不引入 autoSize 字号自适应**：

- Compose foundation 1.6 没有原生 `autoSize` API（Compose 1.7+ 才有 `BasicText.autoSize`）
- 项目当前 `composeBom = 2023.08.00` → foundation 1.5/1.6，autoSize 不可用
- 引入第三方库（如 [TextSize Auto-Adjust](https://github.com/jlosito/Compose-AutoSize-Text)）违反"不引入新依赖"原则
- Ellipsis fallback 是可接受的极端窄屏行为，长字符串极少出现

**为什么不让所有 `Text` 都加 maxLines = 1（全工程一刀切）**：

- 全工程一刀切会误改 Toast / 长描述 / 错误信息等本应换行的文本
- 仅"卡片 / 行 / 标签 / 数字 metric"类字段需要严格单行；范围有限可枚举

**实施细节**：

- `MetricNumber` 内部 `value` 与 `unit` 两个 `Text(...)` 调用直接加 `maxLines = 1, overflow = Ellipsis` —— 所有调用方自动受益
- `MetricTile` 内部 `label` `status` 同理
- `TrackTechRow` 内部 `title` `subtitle` 同理
- `DetailMetricTile`（GpsDetailsScreen 私有）4 个 `Text` 同理
- 各 home screen 内**直接** `Text(...)` 调用（不通过上述组件）逐个加 `maxLines = 1, overflow = Ellipsis`

**为什么不抽出 `TrackTechText` 组件统一封装**：

- 增加抽象层级，调用方仍要传 maxLines（除非默认值）
- baseline 已大量直接用 `androidx.compose.material3.Text`，统一替换为新组件等于全工程改 import，scope 爆炸
- 更轻方案：直接给现有 Text 加参数

**关键 caveat（D10 子节）：`maxLines = 1, overflow = Ellipsis` 必须配合宽度约束才生效**

Compose 的 `overflow = TextOverflow.Ellipsis` **仅在 `Text` 被 bounded max width 测量时触发**。如果父级布局让 `Text` 按 intrinsic width 撑开（无 weight / 无 width 限制），`maxLines = 1` 只会让文本不换行，但会**横向撑开**容器，可能压住或推开邻居（如 chevron / 按钮）。

baseline `TrackTechRow.kt` 当前布局：

```
Box (fillMaxWidth)
  Row (fillMaxWidth, SpaceBetween)
    Row (内层 leading: icon + Column[title/subtitle])  ← 无 weight，按 intrinsic width 撑开
    Icon (ChevronRight)                                  ← 右侧
```

外层 Row 用 `SpaceBetween` 把 leading Row 推到左端、chevron 推到右端；但 leading Row 本身没有 weight 约束，`Column` 内的长 subtitle（如 `"4.21 s · May 18, 2024 · Personal Best"`）会按 intrinsic width 撑开，挤压 chevron 的固定空间，甚至让外层 Row 超过容器宽度（因 fillMaxWidth 容器内 SpaceBetween 是按子元素 intrinsic 测量后分布）。

**`TrackTechRow` 同步加布局约束**：

```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    // ❌ 不再用 horizontalArrangement = Arrangement.SpaceBetween（与 weight 配合不直观）
) {
    Row(
        modifier = Modifier.weight(1f),  // ✅ leading 占据除 chevron 之外的所有剩余空间
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(...)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {  // ✅ 文本 Column 在 leading 内 weight=1，让 Text 被 bounded 测量
            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis, ...)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.size(2.dp))
                Text(text = subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, ...)
            }
        }
    }
    Spacer(Modifier.width(8.dp))  // ✅ chevron 前固定间距，避免文本贴住 chevron
    Icon(
        imageVector = Icons.Filled.ChevronRight,
        ...
    )
}
```

**关键改动**：

1. 外层 Row 删除 `horizontalArrangement = Arrangement.SpaceBetween`（与 weight 路径不兼容）
2. 内层 leading Row 加 `Modifier.weight(1f)`（占据剩余空间）
3. 文本 Column 加 `Modifier.weight(1f, fill = false)`（让 Text 被 bounded；fill=false 让 Column 按内容宽度而不强制撑满，避免 ellipsis 提前触发）
4. chevron 前加 `Spacer(Modifier.width(8.dp))` 固定视觉间距

**为什么 `weight(1f, fill = false)` 而不是 `weight(1f)`**：

- `weight(1f)` = 占满分配的空间（即 leading 内除 icon + spacer 之外的全部）；title/subtitle 较短时 Column 会被强制撑满 → ellipsis 不触发，但 Column 内空白浪费
- `weight(1f, fill = false)` = 最大允许占满，但 Column 按内容 intrinsic width 测量，如果内容窄就不撑满；只有当内容过长溢出 weight 分配的空间时才触发 ellipsis
- 实际效果：短文本不撑满（视觉清爽），长文本触发 ellipsis（不挤 chevron）

**对其他共享组件的影响**：

- `MetricTile.kt` 内 label / value / status 是 Column 内堆叠，宽度由 CutCornerPanel `fillMaxWidth` 限定，已是 bounded 测量 —— `maxLines + Ellipsis` 直接生效，**不需要额外布局改动**
- `MetricNumber.kt` 内 value / unit 是 Row 内 Bottom 对齐，宽度由调用方传入的 modifier 限定，bounded 测量 —— 直接生效
- `GpsDetailsScreen.DetailMetricTile` 同 MetricTile，Column 堆叠，bounded 测量 —— 直接生效
- 各 home screen 直接 `Text(...)` 调用：父级一般是 `Column(.fillMaxWidth())` 或 `Row` 内 element，bounded 测量 —— 直接生效。例外是 `TrackTechRow` 这种"水平布局 + 多元素"场景，需特殊处理（已在本节）

**Risks 见后**。

## Risks / Trade-offs

[**Score 风格 SansSerif Italic Bold 与 RacingTitle 视觉重叠**] → 两者风格相似都是赛车感，可能导致 metric value 与 page header 视觉对比度不足。Mitigation：Score 系列用 `letterSpacing = 0.02.em`（RacingTitle 是 0.05.em）拉开差异；字号差也大（ScoreMedium 36.sp vs RacingTitleLarge 28.sp），实际使用场景不重叠（一个是数字 value，一个是文字标题）。

[**已 deprecated 的 Metric\* 别名容易被新代码继续使用**] → 新加的代码可能误用 `MetricMedium` 而不知道应该选 `ScoreMedium` 或 `MechanicalMedium`。Mitigation：`@Deprecated` 注解 + `ReplaceWith` 让 IDE 红色提示；下一 round（不在本 scope）评估直接删除 alias。

[**真机视觉差异预期**] → SansSerif Italic Bold 在小字号（20.sp）下字重可能不够，特别是 1:32.457 这种含冒号毫秒的时间字符串。Mitigation：真机签收时重点看小字号渲染，必要时调整 `ScoreSmall` 的 fontWeight 或 fontSize。

[**MetricKind 默认 Score 可能让某些静态校验工具误判**] → 如 lint 规则要求"显式参数"。Mitigation：项目当前没有这类 lint 规则，本 round 不引入。

[**foundation MetricSize.Hero/Medium/Small 三档可能不足以覆盖所有场景**] → 例如 ScoreSmall 20.sp 用于 RECENT RUNS 副文字时可能太大。Mitigation：本 round 不引入新 size，沿用三档；后续 round 评估是否需要 XS/XL。

[**单行省略号 fallback 在窄屏隐藏关键信息**] → 例如 "Shanghai Tianma" 折行变 "Shanghai..."，用户看不到完整赛道名。Mitigation：长字符串属于内容设计问题（可改用更短的展示名 "Shanghai T."），不通过 UI 层 wrap 解决；本 round 接受 ellipsis 作为退化策略，后续 round 可单独评估字号自适应。**关键数字字段（value）**：`1:32.457` `132` `4.21` `36.8` 等都是固定格式短字符串，不会触发 ellipsis；省略号只可能出现在 label 或 subtitle 等长文本字段。

[**`maxLines = 1` 让某些原本想多行展示的字段被强制单行**] → 例如某些卡片副文设计上想换行展示。Mitigation：D10 显式列出"应单行"清单（白名单），不在清单内的不动；如果未来某字段确实需要多行（如错误描述），调用方可显式设 `maxLines = N` 覆盖。

[**`Text` 调用大量加同样的 `maxLines/overflow` 参数代码膨胀**] → 视觉一致约束散落在每个调用方，未来要变更（如改 ellipsis 为 marquee）需要全工程批改。Mitigation：本 round 接受这种代码膨胀（最轻量方案）；如果未来真的发现要变更全局策略，再单独 round 抽出 `TrackTechText` 组件统一封装（D10 拒绝过度抽象的理由仍成立）。

## Migration Plan

无运行时迁移（纯字体角色重构，无数据格式变更）。

实施顺序：

1. `TrackTechTypography.kt` 加 Score* / Mechanical* + Metric* alias
2. `MetricNumber.kt` 加 `MetricKind` enum + `kind` 参数；value / unit 两个 `Text` 加 `maxLines = 1, overflow = Ellipsis`（D10）
3. `MetricTile.kt` 加 `valueKind` 参数透传；label / status 加单行约束
4. `TrackTechRow.kt` title / subtitle 加单行约束
5. `GpsDetailsScreen.DetailMetricTile` 加 `valueKind` 参数（D4）+ label / value / unit / status 加单行约束 + `freshDisplayStr` 数据预处理（D9）
6. 各 home screen 调用点按 D5 清单分流：仪表点显式 `Mechanical`，其他默认 Score
7. 各 home screen 内**直接** `Text(...)` 调用（不通过组件）按 D10 清单加单行约束
8. 3 处直接 import MetricMedium 替换为 ScoreMedium / MechanicalMedium（D7）
9. `TrackTechBottomNav.kt` 4 个 tab item label 加单行约束（防御性）
10. 编译 + grep 自检
11. 真机装机 + 视觉签收：所有 metric 字体角色正确 + 窄屏（vivo V2405A）单行无换行

回滚：本 round 是纯 typography + 单行约束重构，回滚 = 还原 ~10 个文件 + 删除新加的 `MetricKind` enum 即可。

## Open Questions

1. **DeviceHomeScreen.kt:274 BLE tile** 的 value 字段实际值（D6）—— apply §0 grep 预检确认，按数字 vs 文字分流；不阻塞 ff 阶段
2. **CURRENT TRACK RECORD 卡内 BEST LAP value 字号** 是否需要从 ScoreMedium 36.sp 调小 —— 视觉偏好题，本 round 保持现状，渐进调整记入 follow-up
