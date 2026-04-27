# Track Tech V2 Visual Tokens

本文档把 Track Tech V2 的视觉准备落到字段级：**色号 / 字体角色 / 切角形状 / 装饰图形 / icon 清单**，与 `track-tech-v2-cc-guidance.md` 对齐，作为 OpenSpec change `add-track-tech-app-shell` 的视觉 artifact 配套。

> 边界：本文档描述的代码片段是 **目标实现样例**，不是 production 代码（由 `add-track-tech-app-shell` change apply 阶段落地到 `feature/test/.../ui/tracktech/` 子包）。

---

## 1. 色号（Color Tokens）

### 1.1 基础 hex token

完整复用 `track-tech-v2-cc-guidance.md` §Color Guidance 的 12 个 hex 值：

| Token 名 | Hex | RGB | Swatch | 用途 |
|---|---|---|---|---|
| `Background` | `#07080D` | (7, 8, 13) | `■` 近黑 | App 根背景 / Scaffold 背景 |
| `Surface` | `#11131C` | (17, 19, 28) | `■` 石墨 | 卡片 / 面板 / Sheet 背景 |
| `SurfaceDark` | `#0B0D13` | (11, 13, 19) | `■` 暗石墨 | 嵌套面板内深一层背景 |
| `Border` | `#303442` | (48, 52, 66) | `▢` 灰描边 | 1dp 通用描边色 |
| `Purple` | `#9B5CFF` | (155, 92, 255) | `■` 紫 | 主行动 / 选中态 / 当前 tab |
| `DeepPurple` | `#5B2AA8` | (91, 42, 168) | `■` 深紫 | PrimaryActionPanel 渐变起点 |
| `Cyan` | `#67E8F9` | (103, 232, 249) | `■` 亮青 | GPS / BLE / 遥测线 / 速度曲线 |
| `Green` | `#76D05E` | (118, 208, 94) | `■` 绿 | Ready / Connected / Good 状态 |
| `Red` | `#F25F5C` | (242, 95, 92) | `■` 红 | Braking / Cancel / Failed / Disconnect |
| `TextPrimary` | `#ECECF2` | (236, 236, 242) | `□` 浅灰白 | 主要文字 / 大号指标数字 |
| `TextSecondary` | `#A5A6B1` | (165, 166, 177) | `□` 中灰 | 副文 / 状态文字 |
| `TextMuted` | `#70727E` | (112, 114, 126) | `□` 暗灰 | 次要 hint / 不可点元素 |

**视觉比例约束**（参考 guidance §Color Style 7-15-8-4-3 配比）：
- 黑/石墨（Background + Surface + SurfaceDark）≈ 70% 屏幕面积
- 文字灰白（TextPrimary + TextSecondary）≈ 15%
- 紫色（Purple + DeepPurple）≈ 8%（仅主行动 + 当前 tab + 选中态）
- Cyan ≈ 4%（GPS/BLE/遥测/track）
- Green/Red ≈ 3%（仅状态点缀，**不**铺面）

### 1.2 Compose 落地（`TrackTechColors.kt`）

```kotlin
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.ui.graphics.Color

object TrackTechColors {
    // 基础底色
    val Background = Color(0xFF07080D)
    val Surface = Color(0xFF11131C)
    val SurfaceDark = Color(0xFF0B0D13)
    val Border = Color(0xFF303442)

    // 强调色（克制使用）
    val Purple = Color(0xFF9B5CFF)
    val DeepPurple = Color(0xFF5B2AA8)
    val Cyan = Color(0xFF67E8F9)
    val Green = Color(0xFF76D05E)
    val Red = Color(0xFFF25F5C)

    // 文字
    val TextPrimary = Color(0xFFECECF2)
    val TextSecondary = Color(0xFFA5A6B1)
    val TextMuted = Color(0xFF70727E)

    // 派生（透明叠加）
    val PurpleAlpha20 = Purple.copy(alpha = 0.20f)  // 当前 tab 填充
    val PurpleAlpha40 = Purple.copy(alpha = 0.40f)  // 选中行轻填充
    val CyanAlpha60 = Cyan.copy(alpha = 0.60f)      // 遥测线降饱和
    val BorderAlpha60 = Border.copy(alpha = 0.60f)  // 弱化分隔线
}
```

### 1.3 Semantic alias（解耦语义与色号）

```kotlin
object TrackTechSemantic {
    val ReadyAccent = TrackTechColors.Green        // READY TO TEST / Connected / Good
    val ConnectingAccent = TrackTechColors.Cyan    // WAITING FOR GPS LOCK / Acquiring
    val NotReadyAccent = TrackTechColors.Red       // CONNECTION FAILED / DISCONNECTED
    val SelectedAccent = TrackTechColors.Purple    // 当前 tab / 选中 device
    val PrimaryActionAccent = TrackTechColors.Purple   // CONNECT / 0-100 / START LAP
    val SecondaryActionAccent = TrackTechColors.Red    // 100-0 / DISCONNECT
    val TelemetryLine = TrackTechColors.Cyan       // GPS / BLE / 速度曲线 / 赛道线
    val MetricLabel = TrackTechColors.Cyan         // section header label "BLE" "SATS" "RATE"
}
```

**用途规则**（不可逾越）：

- `Purple` 只用于"主行动 / 当前态 / 选中态"。**不**用于"信息提示"或"中性 emphasis"。
- `Cyan` 只用于"GPS / BLE / 数据流 / 遥测 / 速度曲线 / 赛道线"。**不**用于纯装饰。
- `Green` 只表达"ready / connected / good"。**不**用于一般 success（确认弹窗等用 TextPrimary）。
- `Red` 只表达"braking / cancel / failed / blocked"。**不**用于纯红色装饰。
- 大面积底色坚持黑/石墨。**不**做"紫色 App"。

---

## 2. 字体角色（Typography Roles）

### 2.1 三角色定义

参考 `guidance §Typography Guidance`，**首版不引入 .ttf 资产**，用系统 SansSerif + FontWeight + FontStyle 模拟。

| 角色 | 用途 | FontFamily | FontWeight | FontStyle | letterSpacing | size variants |
|---|---|---|---|---|---|---|
| `RacingTitle` | 页面标题 / section title / 主操作标题 | SansSerif | ExtraBold (W800) | Italic | 0.05.em | Large 28sp / Medium 20sp / Small 16sp |
| `Metric` | 速度 / 时间 / 成绩 / 卫星数 / 频率 | SansSerif | Black (W900) | Normal | -0.02.em（紧致） | Hero 96sp / Medium 36sp / Small 20sp |
| `UiText` | 普通说明 / 设置 / 列表 / 状态 | SansSerif | Normal (W400) / Medium (W500) | Normal | 0.sp | Body 14sp / Small 12sp / Label 12sp letterSpacing 0.10.em |

> 七段数码字体作为 `Metric` 角色的 future 替换目标，本 change 用 SansSerif Black 模拟。替换路径：单一文件 `TrackTechTypography.kt` 改 `Metric.fontFamily = FontFamily(Font(R.font.seven_segment))` 即可，不影响其他代码。

### 2.2 Compose 落地（`TrackTechTypography.kt`）

```kotlin
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object TrackTechTypography {
    // RacingTitle —— 页面标题 / section title / 主操作标题
    val RacingTitleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 28.sp,
        letterSpacing = 0.05.em,
    )
    val RacingTitleMedium = RacingTitleLarge.copy(fontSize = 20.sp)
    val RacingTitleSmall = RacingTitleLarge.copy(fontSize = 16.sp)

    // Metric —— 速度/时间/卫星数/频率（仅数字与单位）
    val MetricHero = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontStyle = FontStyle.Normal,
        fontSize = 96.sp,
        letterSpacing = (-0.02).em,
    )
    val MetricMedium = MetricHero.copy(fontSize = 36.sp)
    val MetricSmall = MetricHero.copy(fontSize = 20.sp)

    // UiText —— 副文 / 列表 / 状态
    val UiTextBody = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    )
    val UiTextSmall = UiTextBody.copy(fontSize = 12.sp)
    val UiTextLabel = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.10.em,
    )  // section label "BLE" "SATS" "RATE" "PERFORMANCE TEST" 等大写文案
}
```

### 2.3 应用规则

- 页面标题（如 `Drive Test` `Device` `Records`）：`RacingTitleLarge`
- section title（如 `PERFORMANCE TEST` `LATEST RESULT` `CONNECTED DEVICE`）：`UiTextLabel` 大写（**不**用 RacingTitle 避免视觉过载）
- 主操作标题（如 `0-100` `START LAP SESSION` `CONNECT`）：`RacingTitleMedium`
- 大号指标（速度 hero 122 / 卫星数 12 / 时间 4.21s）：`MetricHero` 或 `MetricMedium`
- 单位（km/h / s / Hz / dBm）：`UiTextSmall`，与数字 baseline 对齐底部
- 副文 / 状态文字（"Ready for Test" "GPS locked · BLE connected"）：`UiTextBody`
- hint / 占位提示（"Choose a BLE GPS receiver for tests"）：`UiTextSmall` + `TrackTechColors.TextMuted`

---

## 3. 切角形状（Shape Tokens · CutCornerPanel）

### 3.1 设计原则

参考 `guidance §CutCornerPanel`：

- 大部分卡片 / 按钮 / bottom nav selected item / sheet 容器都用切角面板
- 切角是 Track Tech 视觉系统的"机械感"主要来源
- 切角大小默认 `12.dp`，超大面板（如 Speed Hero）可用 `16.dp`，紧凑卡（如 MetricTile）可用 `8.dp`
- 描边 `1.dp`，色用 `TrackTechColors.Border`；选中/激活态 `Purple` 描边
- 填充背景 `Surface` 或 `SurfaceDark`；激活态可叠 `PurpleAlpha20`

### 3.2 八种 corner variant

| Preset | 切角位置 | 用途 |
|---|---|---|
| `cutCornersDiagonal` | TopLeft + BottomRight | 主面板默认（Speed Hero / Connected Device 主卡） |
| `cutCornersAntiDiagonal` | TopRight + BottomLeft | 装饰对位卡（少用） |
| `cutCornersAll` | 4 角全切 | 紧凑卡（MetricTile / Quick Status Row） |
| `cutCornersTop` | TopLeft + TopRight | section header 容器顶部切角 |
| `cutCornersBottom` | BottomLeft + BottomRight | footer / sheet 顶部边缘切角（倒挂） |
| `cutCornersTopRight` | 仅 TopRight | section title 角落装饰 |
| `cutCornersBottomLeft` | 仅 BottomLeft | secondary action panel 装饰 |
| `cutCornersNone` | 0 角切 | 透明分隔（不应该被实际使用，存在用作 fallback） |

### 3.3 GenericShape Path 设计

切角路径数学（设面板宽 W、高 H、切角大小 c）：

```
TopLeft 切：从 (c, 0) 起，沿顺时针走
不切 TopLeft：从 (0, 0) 起

8 段路径基础轨迹（4 角全切版本）：
moveTo(c, 0)
lineTo(W - c, 0)         // 顶边
lineTo(W, c)             // TopRight 切角斜线
lineTo(W, H - c)         // 右边
lineTo(W - c, H)         // BottomRight 切角斜线
lineTo(c, H)             // 底边
lineTo(0, H - c)         // BottomLeft 切角斜线
lineTo(0, c)             // 左边
lineTo(c, 0)             // TopLeft 切角斜线
close()
```

按 `cutCorners: Set<CutCorner>` 中是否含某角，决定是否走 `lineTo(corner-c, 0); lineTo(corner)` 折线还是 `lineTo(corner)` 直角。

### 3.4 Compose 落地（`CutCornerPanel.kt`）

```kotlin
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

enum class CutCorner { TopLeft, TopRight, BottomLeft, BottomRight }

class CutCornerPanelShape(
    private val cutSize: Dp,
    private val cutCorners: Set<CutCorner>,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val c = with(density) { cutSize.toPx() }
        val w = size.width
        val h = size.height
        val path = Path().apply {
            // Top-Left 起点
            if (CutCorner.TopLeft in cutCorners) moveTo(c, 0f) else moveTo(0f, 0f)
            // 顶边 → TopRight
            if (CutCorner.TopRight in cutCorners) {
                lineTo(w - c, 0f); lineTo(w, c)
            } else {
                lineTo(w, 0f)
            }
            // 右边 → BottomRight
            if (CutCorner.BottomRight in cutCorners) {
                lineTo(w, h - c); lineTo(w - c, h)
            } else {
                lineTo(w, h)
            }
            // 底边 → BottomLeft
            if (CutCorner.BottomLeft in cutCorners) {
                lineTo(c, h); lineTo(0f, h - c)
            } else {
                lineTo(0f, h)
            }
            // 左边 → TopLeft 起点闭合
            if (CutCorner.TopLeft in cutCorners) {
                lineTo(0f, c); lineTo(c, 0f)
            } else {
                lineTo(0f, 0f)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

// 常用 preset
val cutCornersDiagonal = setOf(CutCorner.TopLeft, CutCorner.BottomRight)
val cutCornersAntiDiagonal = setOf(CutCorner.TopRight, CutCorner.BottomLeft)
val cutCornersAll = CutCorner.values().toSet()
val cutCornersTop = setOf(CutCorner.TopLeft, CutCorner.TopRight)
val cutCornersBottom = setOf(CutCorner.BottomLeft, CutCorner.BottomRight)

@Composable
fun CutCornerPanel(
    modifier: Modifier = Modifier,
    cutSize: Dp = 12.dp,
    cutCorners: Set<CutCorner> = cutCornersDiagonal,
    fillColor: Color = TrackTechColors.Surface,
    borderColor: Color = TrackTechColors.Border,
    borderWidth: Dp = 1.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val shape = CutCornerPanelShape(cutSize, cutCorners)
    Box(
        modifier = modifier
            .clip(shape)
            .background(fillColor, shape)
            .border(borderWidth, borderColor, shape)
            .padding(contentPadding),
    ) {
        content()
    }
}
```

### 3.5 应用清单

| 组件 | 使用 | corner preset | cutSize | 描边色 |
|---|---|---|---|---|
| Speed Hero | TestHomeScreen | `cutCornersDiagonal` | 16.dp | Border |
| Readiness Hero | DeviceHomeScreen | `cutCornersDiagonal` | 16.dp | Border（active 时 Green/Cyan/Purple） |
| MetricTile（Quick Status Row） | DeviceHomeScreen | `cutCornersAll` | 8.dp | Border |
| Connected Device 主卡 | DeviceHomeScreen | `cutCornersDiagonal` | 12.dp | **Purple** |
| PrimaryActionPanel（0-100 / CONNECT） | TestHomeScreen / Sheet | `cutCornersDiagonal` | 12.dp | DeepPurple（带渐变） |
| SecondaryActionPanel（100-0 / DISCONNECT） | TestHomeScreen / DeviceHome | `cutCornersDiagonal` | 12.dp | Red |
| TrackTechRow（GPS Details / Diagnostics / Settings） | DeviceHomeScreen | `cutCornersAll` | 6.dp | Border alpha 0.6 |
| BLE Scan device row（推荐选中） | BleScanBottomSheet | `cutCornersAll` | 8.dp | **Purple**（选中时） |
| BLE Scan device row（普通） | BleScanBottomSheet | `cutCornersAll` | 8.dp | Border |
| Bottom Nav selected indicator | TrackTechBottomNav | `cutCornersDiagonal` | 6.dp | Purple alpha 0.4 填充 + 1dp Purple 描边 |

---

## 4. 装饰图形（Decorative Graphics）

所有装饰图形 **零 bitmap 资产**，全部用 Compose `Canvas` 绘制。

### 4.1 cyan 遥测线 / 速度曲线

参考 device-home-v2-calm.png 右侧 cyan 折线 / Records Speed Curve。

**实现思路**：

```kotlin
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun TelemetryLine(
    samples: List<Float>,  // [0..1] 归一化幅度
    modifier: Modifier = Modifier,
    lineColor: Color = TrackTechColors.Cyan,
    strokeWidth: Dp = 1.dp,
    smoothing: Boolean = true,  // 是否 cubicTo 平滑
) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val w = size.width; val h = size.height
        val stepX = w / (samples.size - 1)
        val points = samples.mapIndexed { i, v ->
            Offset(i * stepX, h - v.coerceIn(0f, 1f) * h)
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            if (smoothing && points.size > 2) {
                for (i in 1 until points.size) {
                    val p0 = points[i - 1]
                    val p1 = points[i]
                    val midX = (p0.x + p1.x) / 2f
                    cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                }
            } else {
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
            ),
        )
    }
}
```

**数据源**：

- Speed Hero 装饰：在 `TestHomeScreen` 内 `LaunchedEffect(gpsData) { samples = (samples + gpsData.speed).takeLast(60) }` 滚动 buffer 60 个采样
- Records Speed Curve：用 `TestSessionViewModel` 的最后一次 run 的 `List<Float>` 速度序列（如不存在，用 `(0..60).map { sin(it * 0.1f) * 0.5f + 0.5f }` placeholder）

### 4.2 细网格

放在 hero / track preview / chart 容器内部背景。

```kotlin
@Composable
fun GridBackground(
    modifier: Modifier = Modifier,
    cellSize: Dp = 16.dp,
    lineColor: Color = TrackTechColors.Border,
    alpha: Float = 0.10f,
) {
    Canvas(modifier = modifier) {
        val cell = cellSize.toPx()
        val color = lineColor.copy(alpha = alpha)
        // 垂直线
        var x = 0f
        while (x <= size.width) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5.dp.toPx())
            x += cell
        }
        // 水平线
        var y = 0f
        while (y <= size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
            y += cell
        }
    }
}
```

### 4.3 斜线装饰

放 section header 角落或卡片角落，HUD 风。

```kotlin
@Composable
fun DiagonalSlashes(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    angleDegrees: Float = 45f,
    lineColor: Color = TrackTechColors.Cyan,
    alpha: Float = 0.15f,
) {
    Canvas(modifier = modifier) {
        val gap = spacing.toPx()
        val color = lineColor.copy(alpha = alpha)
        val angleRad = Math.toRadians(angleDegrees.toDouble()).toFloat()
        val dx = kotlin.math.cos(angleRad)
        val dy = kotlin.math.sin(angleRad)
        // 沿对角铺线
        val maxDim = kotlin.math.hypot(size.width, size.height) * 1.2f
        var offset = -maxDim
        while (offset < maxDim) {
            val x0 = offset
            val y0 = 0f
            val x1 = x0 + maxDim * dx
            val y1 = y0 + maxDim * dy
            drawLine(color, Offset(x0, y0), Offset(x1, y1), strokeWidth = 1.dp.toPx())
            offset += gap
        }
    }
}
```

### 4.4 状态点（● / ○）

```kotlin
@Composable
fun StatusDot(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = TrackTechColors.Green,
    inactiveColor: Color = TrackTechColors.TextMuted,
    size: Dp = 8.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        drawCircle(
            color = if (isActive) activeColor else inactiveColor,
            radius = size.toPx() / 2f,
        )
    }
}
```

应用：Readiness Hero 主文案前 `● READY TO TEST` / Connected Device "Ready for Test" 前 `●`。

### 4.5 4 格信号条（BLE Scan Sheet）

```kotlin
@Composable
fun SignalBars(
    bars: Int,  // 0..4
    modifier: Modifier = Modifier,
    activeColor: Color = TrackTechColors.Green,
    inactiveColor: Color = TrackTechColors.Border,
) {
    Canvas(modifier = modifier.size(width = 16.dp, height = 12.dp)) {
        val total = 4
        val barWidth = 2.dp.toPx()
        val gap = 1.5.dp.toPx()
        repeat(total) { idx ->
            val barHeight = (idx + 1) * (size.height / total)
            val x = idx * (barWidth + gap)
            val y = size.height - barHeight
            drawRect(
                color = if (idx < bars) activeColor else inactiveColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

fun rssiToBars(rssi: Int): Int = when {
    rssi >= -50 -> 4
    rssi >= -65 -> 3
    rssi >= -80 -> 2
    else -> 1
}
```

### 4.6 紫色渐变（PrimaryActionPanel）

```kotlin
import androidx.compose.ui.graphics.Brush

val PrimaryActionGradient = Brush.linearGradient(
    colors = listOf(TrackTechColors.DeepPurple, TrackTechColors.Purple),
    start = Offset(0f, 0f),
    end = Offset.Infinite,
)
```

应用：PrimaryActionPanel 背景 `Modifier.background(PrimaryActionGradient, shape = CutCornerPanelShape(...))`。

---

## 5. Icon 资产规划（Material Icons Extended）

**首版不引入自定义 SVG vector**，全部用 `androidx.compose.material:material-icons-extended` 或 `material-icons-core` 提供的 vector icon。

| 用途 | 主选 icon | fallback |
|---|---|---|
| speedometer（Test tab + Speed Hero） | `Icons.Default.Speed` | — |
| brake（100-0 SecondaryActionPanel） | `Icons.Outlined.DoNotDisturbOn` | `Icons.Default.Stop` |
| flag（Laps tab + 起终点） | `Icons.Default.Flag` | `Icons.Outlined.Flag` |
| chart（Records tab + speed curve） | `Icons.Default.Insights` | `Icons.Default.ShowChart` |
| bluetooth（Device tab） | `Icons.Default.Bluetooth` | `Icons.Outlined.Bluetooth` |
| satellite（SATS） | `Icons.Default.Satellite` | `Icons.Default.GpsFixed` |
| signal bars | 自绘 Canvas（见 §4.5） | — |
| gear（settings） | `Icons.Default.Settings` | `Icons.Outlined.Settings` |
| help（page header help） | `Icons.Default.HelpOutline` | `Icons.Outlined.HelpOutline` |
| chevron（row trailing） | `Icons.Default.ChevronRight` | — |
| close（sheet header） | `Icons.Default.Close` | — |
| check（selected radio） | `Icons.Default.Check` | — |
| diagnostics | `Icons.Default.MedicalServices` | `Icons.Default.HealthAndSafety` |
| connecting spinner | `CircularProgressIndicator` | — |

### 5.1 build.gradle 依赖

如未引入：

```kotlin
// feature/test/build.gradle.kts
implementation("androidx.compose.material:material-icons-extended:1.6.8")
```

否则用 `material-icons-core` 默认包，限制在 `Icons.Default.*` 子集。

---

## 6. 字体替换方向（future round）

当前用系统 SansSerif 模拟。**future round** 替换路径：

### 6.1 RacingTitle 替换为真正的"Racing Italic"字体

候选字体（license 待决，本 round 不卡）：

- Eurostile Extended Bold Italic（商业 license）
- Furore（free，racing 风但不够 ExtraBold）
- Audiowide（Google Fonts，免费，机械感）
- Orbitron（Google Fonts，免费，但太"科幻"不够 racing）

替换路径：

1. 字体文件放 `feature/test/src/main/res/font/racing_title.ttf`
2. 改 `TrackTechTypography.RacingTitleLarge.fontFamily = FontFamily(Font(R.font.racing_title))`
3. 单文件改动，无其他代码受影响

### 6.2 Metric 替换为七段数码字体

候选：

- DSEG（free，七段数码标准）
- Digital-7（free，简易七段数码）
- Seven Segment（free）

替换路径同上，改 `Metric*.fontFamily`。

---

## 7. 资产边界（What NOT to do）

参考 `guidance §What Not To Do` + 本文档约束：

- ❌ 不引入 .ttf 字体资产到本 change（首版用系统字体，guidance §Typography 明确）
- ❌ 不引入自定义 SVG vector icon（用 Material Icons Extended）
- ❌ 不从 V2 参考 PNG 裁切按钮 / 卡片 / 图标进 `res/drawable/`
- ❌ 不用 `RoundedCornerShape` 假装切角（必须用 GenericShape Path）
- ❌ 不用 `Modifier.shadow` 强发光（guidance §Color And Graphic Style "不应该出现"）
- ❌ 不引入玻璃拟态 / blur / 大面积模糊渐变背景
- ❌ 不引入 Compose Material3 default `Card` `Button` 形态作为最终视觉（要求是 Track Tech 切角形态）
- ❌ 不引入 bitmap 装饰背景（`docs/design/track-tech-assets/` 旧目录已弃用，guidance §Visual Source 明确）

允许：

- ✅ Material3 `Scaffold` / `NavigationBar` / `NavigationBarItem` / `ModalBottomSheet` / `Surface` / `Text`（标准容器，不属于"Material 大圆角卡片"）
- ✅ `material-icons-core` / `material-icons-extended` vector icons
- ✅ 系统 SansSerif + FontWeight + FontStyle 模拟字体角色
- ✅ Compose `Canvas` 自绘任意装饰图形

---

## 8. 视觉验证清单（apply 阶段交付的 5 张截图与本文档对照）

| 项目 | 验证标准 |
|---|---|
| 黑/石墨底色 | 屏幕背景 70%+ 面积视觉感受为深色，紫色不超过 8% |
| 切角面板 | 主要面板可见斜切角（不是圆角） |
| 1dp 细描边 | 面板边框肉眼可见但不抢戏 |
| 紫色克制 | 紫色仅用于：当前 tab indicator + 主行动按钮 + Connected Device 主卡描边 + 选中 device 描边 |
| Cyan 遥测 | Hero 区右下 / Records Speed Curve / Laps Track Preview 可见 cyan 线条 |
| Green 状态 | Readiness Hero `READY TO TEST` 时主色绿 |
| Red 状态 | Disconnect 按钮 / 100-0 按钮描边红 |
| 字体角色 | 速度数字（96sp）显著大于副文（14sp），视觉层级清晰 |
| Italic Racing | 页面标题肉眼可见斜体 |
| Bottom Nav | 4 tab 等宽，当前 tab 切角紫色 indicator |
| BLE Sheet | 从底部弹出，背景 Device 压暗，列表行选中态紫色描边 |

如截图与本清单某项不符，记录到 commit message body 作为 follow-up。

---

## 9. 与 OpenSpec change 的对应关系

本文档的章节与 `add-track-tech-app-shell` change 的实施任务对应：

| 本文档 | tasks.md 任务 | 落地文件 |
|---|---|---|
| §1 色号 | §1.1 | `tracktech/TrackTechColors.kt` |
| §2 字体 | §2.1 | `tracktech/TrackTechTypography.kt` |
| §3 切角 | §4.1 | `tracktech/CutCornerPanel.kt` |
| §4 装饰图形 | §11.2 / §11.4 / §13.1 | inline `Canvas` 在各 home screen / sheet 内 |
| §5 Icon | §0.4 / §5.2 / §11-§13 | inline `Icons.Default.*` 调用 |
| §6 字体替换方向 | future round | — |
| §7 资产边界 | §17.2 grep 自检 | — |
| §8 视觉验证 | §16.3 | 真机截图对比 |
