## 实施任务（依赖顺序）

本 change 解决两个同源视觉问题：

1. **字体角色拆分**：`TrackTechTypography.Metric*` 单一七段字体拆为 `Mechanical*`（DSEG7 仪表瞬时）+ `Score*`（SansSerif Italic Bold 成绩/时间/文字），调用方按字段语义分流（capability `metric-typography-roles`）
2. **单行强制 + 溢出策略**：所有 metric / row / label 类 `Text(...)` 加 `maxLines = 1, overflow = Ellipsis`，修小屏换行（capability `track-tech-card-single-line-policy`）

覆盖：

- §0 grep 预检（含 D10 字段排查）
- §1 Typography 拆双套 + Metric* deprecated alias
- §2 MetricNumber + MetricTile 加 kind 参数 + 内部 Text 单行约束
- §3 TrackTechRow + GpsDetailsScreen.DetailMetricTile 加单行/参数
- §4 各 home screen + execution screen 调用方按 D5 字体分流
- §A 各 home screen + execution screen 直接 Text 按 D10 加单行约束
- §B TrackTechBottomNav tab label 加单行（防御性）
- §5 编译/测试门槛
- §6 真机视觉验证（含小屏换行检查）
- §7 commit + 合流门槛

参考 `proposal.md` / `design.md` D1-D10 / `specs/metric-typography-roles/spec.md` + `specs/track-tech-card-single-line-policy/spec.md`。

---

## 0. grep 预检

- [x] 0.1 **当前 Dseg7FontFamily 引用范围核实**：

  ```bash
  grep -rn "Dseg7FontFamily" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  预期：仅 `TrackTechTypography.kt` 内 2 处（声明 + 在 MetricHero 引用）；其他文件零命中。

- [x] 0.2 **MetricHero / MetricMedium / MetricSmall 命中范围核实**：

  ```bash
  grep -rn "TrackTechTypography.MetricHero\|TrackTechTypography.MetricMedium\|TrackTechTypography.MetricSmall" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  预期合计 **10 处命中**，分三类：

  **类别 A — Typography 定义（3 处，自身定义不算调用）**：
  - `TrackTechTypography.kt:33` `val MetricHero = TextStyle(...)`
  - `TrackTechTypography.kt:40` `val MetricMedium = MetricHero.copy(...)`
  - `TrackTechTypography.kt:41` `val MetricSmall = MetricHero.copy(...)`

  本 round §1.1 改为 deprecated alias 绑到 Score* 系列。

  **类别 B — MetricNumber 内部 size→TextStyle 映射（3 处，内部实现）**：
  - `MetricNumber.kt:27` `MetricSize.Hero -> TrackTechTypography.MetricHero`
  - `MetricNumber.kt:28` `MetricSize.Medium -> TrackTechTypography.MetricMedium`
  - `MetricNumber.kt:29` `MetricSize.Small -> TrackTechTypography.MetricSmall`

  本 round §2.1 重写 `numberStyle` 派生为 `(kind, size)` 二维 when，分别走 Mechanical* / Score* 系列；这 3 行被替换。

  **类别 C — Screen 直接引用 TextStyle（4 处，调用方需改）**：
  - `RecordsHomeScreen.kt:488` `style = TrackTechTypography.MetricMedium`（CURRENT TRACK RECORD 卡内 BEST LAP）→ §4.4 改为 `ScoreMedium`
  - `TrackTechTestExecutionScreen.kt:410` `style = TrackTechTypography.MetricHero`（CURRENT SPEED）→ §4.2 改为 `MechanicalHero`
  - `TrackTechTestExecutionScreen.kt:432` `style = TrackTechTypography.MetricMedium`（ELAPSED TIME）→ §4.2 改为 `ScoreMedium`
  - `GpsDetailsScreen.kt:631` `style = TrackTechTypography.MetricMedium`（DetailMetricTile 内部）→ §3.1 改为 `when (valueKind)` 派生

  类别 A + C = 7 处 spec 关心（定义 + 直接调用）；类别 B 是内部映射，重写后被新结构替代，grep 后零残留。

  **§4.7 grep 自检（apply 阶段）预期**：tracktech 子包内 `TrackTechTypography.MetricHero/Medium/Small` 命中**仅限 `TrackTechTypography.kt` 自身的 alias 定义 3 行**（类别 A 的 deprecated alias），其他文件全部零命中。

- [x] 0.3 **MetricTile / MetricNumber 调用点核实**：

  ```bash
  grep -rn "MetricTile(\|MetricNumber(" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt" | grep -v "fun MetricTile\|fun MetricNumber"
  ```

  预期：~15 处调用：
  - `TestHomeScreen.kt:167` PERSONAL BEST + `:176` LAST RUN + `:210` SPEED hero MetricNumber
  - `LapsHomeScreen.kt:165` RECENT BEST
  - `RecordsHomeScreen.kt:132/140/148/395/404/413` 共 6 处 MetricTile
  - `DeviceHomeScreen.kt:274/291/300` 3 处 MetricTile
  - `MetricTile.kt:43` MetricNumber 内部调用

- [x] 0.4 **GpsDetailsScreen.DetailMetricTile 调用点核实**：

  ```bash
  grep -n "DetailMetricTile(" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/GpsDetailsScreen.kt
  ```

  预期：8 处调用 + 1 处 private fun 定义。按 D5 拆分：
  - 数字（5 处）→ Mechanical：SATELLITES / HDOP / RATE / FRESH / DROPPED
  - 文字（3 处）→ Score（默认值）：FIX / QUALITY / LAST PACKET

- [x] 0.5 **D10 单行字段 baseline 检查**：

  ```bash
  # 检查共享组件内 Text 是否已经有 maxLines (基线现状预期：零命中)
  grep -nE "maxLines\s*=" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/MetricNumber.kt /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/MetricTile.kt /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechRow.kt
  ```

  预期：4 个共享组件文件内 baseline `maxLines` 引用零命中（原本未加单行约束，本 round §2/§3 加）。

  ```bash
  # 检查不应引入 autoSize（防御性）
  grep -rn "BasicText\|autoSize\|TextAutoSize" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  预期：零命中（本 round 不引入 autoSize）。

- [x] 0.6 **DeviceHomeScreen BLE tile value 内容确认**（D6 拍板依据）：BLE value 是 `"ON"` / `"…"` / `"—"` 文字字符串 → 默认 Score（不显式标 Mechanical）。

  ```bash
  sed -n '270,285p' /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/DeviceHomeScreen.kt
  ```

  确认 BLE tile 的 `value = ...` 表达式：是 `connectionState.label`（文字 `"Connected"` / `"Disconnected"`）还是数字状态。
  - 文字 → 默认 Score（不显式标注）
  - 数字 → 显式 `valueKind = MetricKind.Mechanical`

---

## 1. Typography 拆双套 + Metric* deprecated alias

- [x] 1.1 **重写 `TrackTechTypography.kt`**：保留 `Dseg7FontFamily`，新增：

  ```kotlin
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

  // Deprecated alias —— 默认走 Score
  @Deprecated("Use ScoreHero or MechanicalHero explicitly", ReplaceWith("ScoreHero"))
  val MetricHero = ScoreHero
  @Deprecated("Use ScoreMedium or MechanicalMedium explicitly", ReplaceWith("ScoreMedium"))
  val MetricMedium = ScoreMedium
  @Deprecated("Use ScoreSmall or MechanicalSmall explicitly", ReplaceWith("ScoreSmall"))
  val MetricSmall = ScoreSmall
  ```

  RacingTitle / UiText* 三组其他角色保持不变。

- [x] 1.2 编译验证：`./gradlew :feature:test:compileDebugKotlin`（应有 deprecation warning，不阻断）

---

## 2. MetricNumber + MetricTile 加 kind 参数

- [x] 2.1 **`MetricNumber.kt`** 加 `MetricKind` enum + `kind` 参数：

  ```kotlin
  enum class MetricKind { Mechanical, Score }

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
      // unitStyle 不变，仍按 size 选 UiTextBody / UiTextSmall
      // ... 其他 Row + Text 渲染逻辑不变
  }
  ```

- [x] 2.1.1 **`MetricNumber.kt` 内 Text 加单行约束**（D10）：value `Text(...)` 与 unit `Text(...)` 各加 `maxLines = 1, overflow = TextOverflow.Ellipsis`。需新增 `import androidx.compose.ui.text.style.TextOverflow`。

- [x] 2.2 **`MetricTile.kt`** 加 `valueKind` 参数透传：

  ```kotlin
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
          valueColor = TrackTechColors.TextPrimary,
          unitColor = TrackTechColors.TextSecondary,
      )
      // ... 其他逻辑不变
  }
  ```

- [x] 2.2.1 **`MetricTile.kt` 内 Text 加单行约束**（D10）：label `Text(...)` 与 status `Text(...)` 各加 `maxLines = 1, overflow = TextOverflow.Ellipsis`。需新增 `import androidx.compose.ui.text.style.TextOverflow`。

- [x] 2.3 编译验证

---

## 3. TrackTechRow + GpsDetailsScreen.DetailMetricTile 加单行/参数

- [x] 3.0 **`TrackTechRow.kt` 单行约束 + 布局重构**（D10 关键 caveat）：

  baseline 当前布局（约 line 46-80）：
  ```kotlin
  Row(modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {     // ← 删
      Row(verticalAlignment = Alignment.CenterVertically) {    // ← 加 weight(1f)
          Icon(...)
          Spacer(Modifier.width(12.dp))
          Column {                                              // ← 加 weight(1f, fill = false)
              Text(text = title, ...)                           // ← 加 maxLines/overflow
              if (!subtitle.isNullOrEmpty()) {
                  Spacer(Modifier.size(2.dp))
                  Text(text = subtitle, ...)                    // ← 加 maxLines/overflow
              }
          }
      }
      // chevron 之前没有 Spacer ← 加 Spacer(Modifier.width(8.dp))
      Icon(imageVector = Icons.Filled.ChevronRight, ...)
  }
  ```

  改造为：
  ```kotlin
  Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      // 删除 horizontalArrangement = Arrangement.SpaceBetween
  ) {
      Row(
          modifier = Modifier
              .weight(1f)                          // ← leading 占除 chevron 外剩余空间
              .padding(end = 0.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
          Icon(
              imageVector = leadingIcon,
              contentDescription = null,
              tint = TrackTechColors.Purple,
              modifier = Modifier.size(20.dp),
          )
          Spacer(Modifier.width(12.dp))
          Column(
              modifier = Modifier.weight(1f, fill = false),  // ← 让 Text 被 bounded 测量；fill=false 短文本不强制撑满
          ) {
              Text(
                  text = title,
                  style = TrackTechTypography.UiTextLabel,
                  color = TrackTechColors.TextPrimary,
                  maxLines = 1,                              // ← 单行
                  overflow = TextOverflow.Ellipsis,           // ← 溢出省略
              )
              if (!subtitle.isNullOrEmpty()) {
                  Spacer(Modifier.size(2.dp))
                  Text(
                      text = subtitle,
                      style = TrackTechTypography.UiTextSmall,
                      color = TrackTechColors.TextSecondary,
                      maxLines = 1,                          // ← 单行
                      overflow = TextOverflow.Ellipsis,       // ← 溢出省略
                  )
              }
          }
      }
      Spacer(Modifier.width(8.dp))                           // ← chevron 前固定间距
      Icon(
          imageVector = Icons.Filled.ChevronRight,
          contentDescription = null,
          tint = TrackTechColors.TextSecondary,
      )
  }
  ```

  **关键改动 4 处**（缺一不可，否则 ellipsis 不生效）：
  1. 外层 Row **删除** `horizontalArrangement = Arrangement.SpaceBetween`
  2. 内层 leading Row 加 `Modifier.weight(1f)`
  3. 文本 Column 加 `Modifier.weight(1f, fill = false)`
  4. chevron 前加 `Spacer(Modifier.width(8.dp))`

  + title / subtitle 各加 `maxLines = 1, overflow = TextOverflow.Ellipsis`
  + 新增 `import androidx.compose.ui.text.style.TextOverflow`

- [x] 3.1 改造 `GpsDetailsScreen.kt` 内 `private fun DetailMetricTile`：

  - 加参数 `valueKind: MetricKind = MetricKind.Score`
  - body 内 value `Text(...)` 的 `style` 改为按 `valueKind` 派生：

    ```kotlin
    Text(
        text = value,
        style = when (valueKind) {
            MetricKind.Mechanical -> TrackTechTypography.MechanicalMedium
            MetricKind.Score -> TrackTechTypography.ScoreMedium
        },
        color = valueColor,
    )
    ```

- [x] 3.1.1 **`DetailMetricTile` 4 个 Text 加单行约束**（D10）：label / value / unit / status 4 处 `Text(...)` 调用各加 `maxLines = 1, overflow = TextOverflow.Ellipsis`。

- [x] 3.2 编译验证

---

## 4. 调用方按 D5 清单分流

- [x] 4.1 **`TestHomeScreen.kt`**：
  - `:210` SPEED hero MetricNumber 加 `kind = MetricKind.Mechanical`
  - `:167/176` PERSONAL BEST / LAST RUN MetricTile 不动（默认 Score 即正确）

- [x] 4.2 **`TrackTechTestExecutionScreen.kt`**：
  - `:410` CURRENT SPEED Text style 改 `TrackTechTypography.MechanicalHero`
  - `:432` ELAPSED TIME Text style 改 `TrackTechTypography.ScoreMedium`

- [x] 4.3 **`LapsHomeScreen.kt`**：
  - `:165` RECENT BEST MetricTile 不动（默认 Score）

- [x] 4.4 **`RecordsHomeScreen.kt`**：
  - `:132/140/148/395/404/413` 6 个 MetricTile 不动（默认 Score）
  - `:488` CURRENT TRACK RECORD 卡内 BEST LAP Text style 改 `TrackTechTypography.ScoreMedium`

- [x] 4.5 **`DeviceHomeScreen.kt`**：
  - 按 §0.5 grep 结果分流：
    - SATS tile + RATE tile → `valueKind = MetricKind.Mechanical`（数字）
    - BLE tile → 视 value 而定（文字默认 Score；数字显式 Mechanical）

- [x] 4.6 **`GpsDetailsScreen.kt`**：
  - SATELLITES / HDOP / RATE / FRESH / DROPPED 五个 DetailMetricTile 调用 → `valueKind = MetricKind.Mechanical`
  - FIX / QUALITY / LAST PACKET 三个 DetailMetricTile 调用 → 不动（默认 Score）
  - `:631` DetailMetricTile 内部 value Text style 已由 §3.1 按参数派生，本任务核对生效

- [x] 4.6.1 **`GpsDetailsScreen.DataStreamGrid`** 函数内 `freshDisplayStr` / `freshDisplayUnit` 数据预处理拆分（D9 决策，避免字母 `s` 进 Mechanical）：

  baseline（约 line 388-407）：
  ```kotlin
  val freshDisplayStr = when {
      freshMs <= 999L -> freshMs.toString()
      freshMs <= 9999L -> "${freshMs / 1000}.${(freshMs % 1000) / 100}s"   // ← 字母 s 内联
      else -> "—"
  }
  val freshDisplayUnit: String? = when {
      freshMs <= 999L -> "ms"
      freshMs <= 9999L -> null     // ← 因 s 在 value 里所以 null
      else -> null
  }
  ```

  改造为：
  ```kotlin
  val freshDisplayStr = when {
      freshMs <= 999L -> freshMs.toString()                                  // "523"
      freshMs <= 9999L -> "%.1f".format(freshMs / 1000.0)                    // "1.2"  ← 纯数字
      else -> "—"
  }
  val freshDisplayUnit: String? = when {
      freshMs <= 999L -> "ms"
      freshMs <= 9999L -> "s"      // ← 字母走 unit，由 MetricNumber unit 文本（UiTextSmall）渲染
      else -> null
  }
  ```

  这样 FRESH 走 Mechanical 时只把 `"1.2"` / `"523"` / `"—"` 喂进 DSEG7 字体，单位字母 `s` / `ms` 由 `unit` 参数走 SansSerif，避免字母变形。

- [x] 4.7 **grep 自检 1**：

  ```bash
  grep -rn "TrackTechTypography.MetricHero\|TrackTechTypography.MetricMedium\|TrackTechTypography.MetricSmall" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech --include="*.kt"
  ```

  预期：仅 `TrackTechTypography.kt` 内 alias 定义命中（3 行），其他文件零命中。

- [x] 4.8 **grep 自检 2**：

  ```bash
  grep -rn "MetricKind.Mechanical" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech --include="*.kt"
  ```

  预期：约 8-9 处显式命中（TestHomeScreen SPEED + DeviceHomeScreen SATS/RATE + GpsDetailsScreen 5 个数字 tile）。

---

## A. 各 home screen + execution screen 直接 Text 加单行约束（D10）

每个文件下的"应单行"字段清单见 design D10 / `specs/track-tech-card-single-line-policy/spec.md`。统一加：

```kotlin
Text(
    text = ...,
    style = ...,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)
```

每个文件 MUST 新增 `import androidx.compose.ui.text.style.TextOverflow`。

- [x] A.1 **`RecordsHomeScreen.kt`**：
  - `RecordsTitleRow`：`Records` 标题 `Text` 加单行
  - `SegmentedControl` 内 option `Text(text = opt, ...)` 加单行
  - `PerformanceView`：`PERFORMANCE TEST` / `RECENT RUNS` section header `Text` 加单行
  - `LapsView` `CurrentTrackRecordCard`：5 个 `Text` 调用（CURRENT TRACK RECORD label / Shanghai Tianma / BEST LAP label / 1:32.457 ScoreMedium 那处 / May 18 2024）各加单行
  - `LapsView`：`SESSION HISTORY` section header `Text` 加单行
  - `SpeedCurveBubble` 内 `100 km/h` / `4.21 s` 两个 `Text` 加单行（防御）

- [x] A.2 **`TestHomeScreen.kt`**：
  - `Drive Test` 标题 `Text` 加单行
  - `PERFORMANCE TEST` / `LATEST RESULT` section header 各加单行
  - `SpeedHero` 内：`SPEED` label / `STATUS` / `READY`/`STANDBY` 状态文本各加单行

- [x] A.3 **`TrackTechTestExecutionScreen.kt`**：
  - `BigSpeedDisplay`：速度 value Text + `km/h` unit Text 各加单行
  - `ElapsedTimeDisplay`：时间 value Text + `s` unit Text 各加单行
  - `PhaseBanner`：phaseTag / phaseTitle / phaseSub 三个 `Text` 各加单行
  - `ProgressPanel`：左 `0` / 右 `100` / 中央 `displayPct%` / targetLabel 四个 `Text` 各加单行
  - `SignalFooter`：SATELLITES label + value / HDOP label + value 共 4 个 `Text` 各加单行
  - `ExecutionStatusStrip` `StatusCell`：label / status 两个 `Text` 各加单行（这是组件内 Text）
  - `TestTypeHeader`：speedLabel + typeLabel 两个 `Text` 各加单行
  - `CURRENT SPEED` / `ELAPSED TIME` 上方 label `Text` 各加单行
  - `CancelOrDoneButton` 内 button label `Text` 加单行

- [x] A.4 **`LapsHomeScreen.kt`**：
  - `Laps` 标题 `Text` 加单行
  - `CurrentTrackPanel`：当前赛道名 + 副文 `Text` 各加单行
  - `RECENT BEST` section header `Text` 加单行

- [x] A.5 **`DeviceHomeScreen.kt`**：
  - `Device` 标题 `Text` 加单行
  - Readiness Hero：主标 / 副标 / accent label `Text` 各加单行
  - Connected Device 卡：device name `Text` + 副状态文本 `Text` 各加单行
  - 任何 `TrackTechRow` 调用之外的直接 Text（如 `GPS DETAILS` / `DIAGNOSTICS` / `SETTINGS` 入口若非走 TrackTechRow 则需手动加；若走 TrackTechRow 则已由 §3.0 覆盖）

- [x] A.6 **`TrackTechBottomNav.kt`**（防御性）：
  - `TrackTechBottomNavItem` 内 tab label `Text(text = tab.label, ...)` 加单行（4 个 tab 标签都是短文本，单行已是常态，但补防御约束）

- [x] A.7 **grep 自检**：

  ```bash
  # 检查 D10 字段单行覆盖率（应有大量 maxLines = 1 命中）
  grep -rn "maxLines = 1" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech --include="*.kt" | wc -l
  ```

  预期：`maxLines = 1` 命中数 ≥ 30 处（4 个共享组件 ≈ 10 处 + 各 home screen ≈ 25-30 处）。

  ```bash
  # 检查不应引入 autoSize（防御）
  grep -rn "BasicText\|autoSize\|TextAutoSize" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  预期：零命中。

---

## B. 编译验证

- [x] B.1 `./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL

---

## 5. 编译/测试门槛

- [x] 5.1 `./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL（仅 deprecation warning，无 error）
- [x] 5.2 `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 5.3 `./gradlew :feature:test:testDebugUnitTest` 全绿（含 `TrackTechAppShellPagerTest` / `TabGatingPolicyTest` / TestSessionViewModel 套件零回归）
- [x] 5.4 `./gradlew :core:bluetooth:testDebugUnitTest :core:domain:test :core:data:testDebugUnitTest` 全绿（typography 改动不影响数据层）

---

## 6. 真机视觉验证（manual gate）

- [x] 6.1 安装到真机：

  ```bash
  ANDROID_SERIAL=8KE0219522008434 ./gradlew :app:installDebug
  ```

  （华为 8KE0219522008434 默认真机，与上一 round 一致）

- [x] 6.2 验证清单（逐 tab + 屏幕逐一对比 baseline 七段字体效果）：

  **TestHomeScreen**：
  - SPEED hero（实时速度数字）：仍是七段字体（仪表感不变）✓
  - PERSONAL BEST / LAST RUN：从七段变成 SansSerif Italic Bold（赛车感）

  **LapsHomeScreen**：
  - RECENT BEST `1:32.457`：从七段变成 Score 字体

  **RecordsHomeScreen**：
  - PERFORMANCE 视图：BEST 0-100 / BEST BRAKE / TOTAL RUNS 三 metric tile 从七段变成 Score
  - LAPS 视图：BEST LAP / SESSIONS / TOTAL LAPS 三 metric tile 从七段变成 Score
  - CURRENT TRACK RECORD 卡内 BEST LAP `1:32.457`：从七段变成 Score
  - SPEED CURVE 卡内文字标注（`100 km/h` `4.21 s` 气泡）：本身用 UiTextSmall 不受影响

  **DeviceHomeScreen**：
  - BLE tile：视 §0.5 拍板（文字 → Score；数字 → Mechanical）
  - SATS tile（数字）：仍七段
  - RATE tile（数字）：仍七段

  **GpsDetailsScreen**（点 Device tab → GPS DETAILS 行进入）：
  - SATELLITES / HDOP / RATE / FRESH / DROPPED 五个数字字段：仍七段
  - **FIX / QUALITY / LAST PACKET 三个文字字段**：从七段（字母变形）变成 Score（清晰可读）—— 本 round 核心修复点

  **TrackTechTestExecutionScreen**（执行测试时）：
  - CURRENT SPEED hero（速度数字）：仍七段
  - ELAPSED TIME（时间秒数）：从七段变成 Score

- [x] 6.2.1 **D10 单行约束验证**（小屏 vivo V2405A 重点验证）：

  ```bash
  ANDROID_SERIAL=10AF5T0XE3004ZX ./gradlew :app:installDebug
  ```

  逐屏检查所有"应单行"字段在小屏下不再换行：
  - `MetricTile` 的 label `BEST 0-100` / `BEST BRAKE` / `TOTAL RUNS` 在 PERFORMANCE 三 tile 等宽布局下单行不换行
  - `MetricTile` 的 label `BEST LAP` / `SESSIONS` / `TOTAL LAPS` 在 LAPS 三 tile 等宽布局下单行不换行
  - `TrackTechRow` 的 subtitle `3.063 km · Clockwise` / `4.21 s · May 18, 2024 · Personal Best` 等长文单行（必要时显示 ellipsis）
  - `SegmentedControl` 的 `PERFORMANCE` / `LAPS` 选项单行
  - DeviceHomeScreen Quick Status Row 内各 tile label / status 单行
  - GpsDetailsScreen DetailMetricTile 的 label / value / unit / status 单行
  - 4 个底部 tab label 单行
  - **特别检查**：极长字段 `CURRENT TRACK RECORD` (CURRENT TRACK RECORD 卡的左半 label) 在窄屏不换行；如换行视觉问题严重，记录到 follow-up

- [x] 6.3 视觉偏差点（如 ScoreSmall 字号 20.sp 在小字段下字重不够、CURRENT TRACK RECORD 卡的 BEST LAP 字号偏大、单行 ellipsis 出现处的内容设计是否需要短化等）作为 follow-up backlog 记录到 commit message body。

---

## 7. Commit + 合流门槛

- [x] 7.1 **Spec 验证**：`openspec validate differentiate-metric-typography-mechanical-vs-score --strict` 返回 `Change ... is valid`

- [x] 7.2 **grep 自检**（最终汇总）：
  - `MechanicalHero` / `MechanicalMedium` / `MechanicalSmall` + `ScoreHero` / `ScoreMedium` / `ScoreSmall` 6 个 TextStyle 定义在 `TrackTechTypography.kt` 内全部命中
  - `enum class MetricKind` 命中 1 次（在 `MetricNumber.kt`）
  - `valueKind: MetricKind` 命中至少 2 次（MetricTile.kt + GpsDetailsScreen.kt 的 DetailMetricTile）
  - `MetricKind.Mechanical` 显式标注命中约 8-9 处
  - `tracktech/` 子包内 `TrackTechTypography.MetricHero/Medium/Small` 引用零命中（除 alias 定义）
  - `Dseg7FontFamily` 引用仅限 `MechanicalHero` / `Medium` / `Small`（其中 Medium / Small 通过 `.copy(...)` 间接引用）
  - **D10 单行约束覆盖率**：`grep -rn "maxLines = 1" tracktech/` 命中 ≥ 30 处
  - **autoSize 防御**：`grep -rn "BasicText\|autoSize\|TextAutoSize" tracktech/` 零命中
  - **TrackTechRow 布局约束**：
    ```bash
    # 1. SpaceBetween 已删
    grep -n "SpaceBetween" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechRow.kt
    # 预期：零命中
    # 2. weight 应用
    grep -n "weight(1f)\|weight(1f, fill = false)" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechRow.kt
    # 预期：≥ 2 处命中（leading Row weight(1f) + 文本 Column weight(1f, fill = false)）
    # 3. chevron 前 Spacer
    grep -B 2 "Icons.Filled.ChevronRight" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechRow.kt | grep "Spacer(Modifier.width"
    # 预期：命中（chevron 之前的 Spacer）
    ```

- [x] 7.3 **下游零回归**：
  - `:core:bluetooth:testDebugUnitTest` ✅
  - `:core:domain:test` ✅
  - `:core:data:testDebugUnitTest` ✅
  - `:app:compileDebugKotlin` ✅
  - `:feature:test:testDebugUnitTest` 全绿

- [x] 7.4 **真机验证**已完成（§6.2 + §6.2.1 验证清单全过；FIX / QUALITY / LAST PACKET 字母变形问题已解决；窄屏小卡片单行不再换行）

- [x] 7.5 **commit**：`refactor(ui): TrackTechTypography 拆 Mechanical/Score · 字段语义分流 + 单行强制`

  body 要点：
  - **metric-typography-roles capability 新建**：拆 Mechanical (DSEG7 七段，仪表瞬时数字) + Score (SansSerif Italic Bold，成绩/时间/文字状态) 双套 metric 字体角色；MetricKind enum + MetricNumber.kind / MetricTile.valueKind 默认 Score（错用 Score 仅视觉降级，错用 Mechanical 字母变形不可接受）
  - **track-tech-card-single-line-policy capability 新建**：所有 metric / row / label 类 `Text(...)` 加 `maxLines = 1, overflow = Ellipsis`，修小屏换行；MetricNumber/MetricTile/TrackTechRow/DetailMetricTile 4 个共享组件内部统一约束 + 各 home screen 直接 `Text` 按 D10 清单逐个加约束
  - **修复用户反馈 1**：FIX `3D Fix` / QUALITY `Good` / LAST PACKET `Now`/`5s ago` 等文字字段从七段变 SansSerif，字母不再变形
  - **修复用户反馈 2**：小屏 vivo V2405A 上 `BEST 0-100` / `3.063 km · Clockwise` 等长字段不再换行
  - **保留 Mechanical**：SPEED hero (Test home / Execution) / SATS / RATE / HDOP / FRESH / DROPPED 数字仪表
  - **GpsDetailsScreen FRESH 数据预处理**（D9）：`freshDisplayStr` 1-9.9s 区间从 `"1.2s"` 拆为 `"1.2"` + `unit = "s"`，让 Mechanical 字体只吃数字
  - **deprecated alias**：MetricHero/Medium/Small → 绑到 Score* + @Deprecated 注解 + ReplaceWith
  - **零改动**：core/* / simulator/* / 数据层 / RacingTitle / UiText* 其他 typography 角色 / MetricTile 视觉布局 / 任何依赖
  - **不引入 autoSize**：项目当前 composeBom 2023.08.00 不支持 BasicText.autoSize；overflow Ellipsis 作为窄屏 fallback
  - **真机验证**：华为 8KE0219522008434 字体角色 + vivo V2405A 小屏单行验证，两类问题已修复
  - **测试**：本 round 不新增单测（纯 typography + 单行约束重构，无运行时行为契约），现有套件全绿
  - **合流门槛**：openspec validate --strict ✅ / grep 自检全部通过 ✅

  格式约束：
  - Conventional Commits（refactor 而非 feat，因为不引入新功能）
  - body 含 2 个 capability 名 + 受影响 ~10 个文件清单 + 真机验证状态
  - Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>

---

## 8. Post-apply follow-up backlog

- ScoreSmall 20.sp 在小字段下字重 / 字号微调 —— 独立 round（如真机视觉 §6.3 反馈不足）
- CURRENT TRACK RECORD 卡内 BEST LAP value 字号是否从 Medium 36.sp 降到 Small 20.sp —— 独立 round
- 旧 `MetricHero/Medium/Small` deprecated alias 完全删除（确认全工程零引用后）—— 独立 round
- `MetricKind.Auto` 启发式（按 value 字符串内容自动判断）—— 不推荐，仅作为讨论（design D1 已否决）
- 引入 ComposeRule UI test 覆盖"render value with Mechanical kind contains DSEG7 font" —— 独立 round（与上一 round backlog 合并评估）
