## Context

V2 性能测试执行屏 `TrackTechTestExecutionScreen.kt` 的进度条逻辑（line 74-85）：

```kotlin
val progress: Float = when (val s = testState) {
    is TestState.Running -> when (currentMode) {
        TestMode.Acceleration -> (speed / 100.0).toFloat().coerceIn(0f, 1f)
        TestMode.Braking -> {
            val start = s.session.dataPoints.firstOrNull()?.speed ?: 100.0
            ((start - speed) / start.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
        }
        else -> 0f
    }
    is TestState.Completed -> 1f
    else -> 0f
}
```

`ProgressPanel`（line 470）渲染该 progress：背景容器 + fill `Modifier.fillMaxWidth(fraction = progress)` + 百分比文字。

用户验证报告：加速测试时"按 START 后进度条停在 0% 一段时间，等车速到中段才有可见移动"。本 round 用阈值切换 + 状态文案解决"起步死气期"体感断层；不动 progress 算法本身。

## Goals / Non-Goals

**Goals:**

- 加速测试低于 `LAUNCH_SPEED_THRESHOLD_KMH`（默认 3.0 km/h）时进度条不渲染 fill，文案改 `"WAITING FOR LAUNCH"`，让用户清楚知道"系统在等起步"
- 阈值跨过后回到正常 progress 渲染（沿用原 speed-based 公式）
- pure-function 单测覆盖阈值边界、加速 / 制动 / clamp 行为
- 不改原 progress 算法

**Non-Goals:**

- 不改时间基准 / sqrt 非线性 / 状态机分段（这些都是 follow-up，单独 round）
- 不改 V1 dead code `TestExecutionScreen.kt:264`
- 不引入 settings UI 调阈值
- 不解决 BLE / GPS first frame 延迟（链路层问题，本 round 不涉及）

## Decisions

### Decision 1: 阈值默认 3.0 km/h

**理由**：

- GpsDataFilter 中位数滤波后，静止状态下 GPS speed 通常在 0-2 km/h 浮动（GPS Doppler 噪声）。3.0 是一个**比静止 noise 高 + 比真起步初速低**的合理隔离值
- 实测车在踩油门后 200-300ms 内 speed 跨过 3 km/h，所以阈值不会让用户感到"起步后还有等待"
- 后续如果发现 3.0 不合适，调常量即可，没有兼容性风险

**对比**：

| 阈值 | 优 | 劣 |
|---|---|---|
| **0.5 km/h** | 几乎无延迟切换 | GPS 静止噪声可能误触发，进度条来回切 |
| **3.0 km/h（采用）** | 抗 GPS 静止噪声；真起步切换时延 < 300ms | 极慢起步车型（如电动车蠕行模式）会停留在文案稍久 |
| **5.0 km/h** | 抗噪更强 | 真起步后还要等 ~500ms 才切，体感"延迟切换" |
| **10.0 km/h** | 完全抗噪 | 真起步后明显滞后，违背"按 START 立刻有反馈"的初衷 |

### Decision 2: 仅 Acceleration 模式启用阈值

**理由**：制动测试按 START 时车已 ≥100 km/h（用户先加速到 100，然后按 START 全力刹车），开始的瞬间 progress 立刻从 0 增长，没有"起步死气期"。给制动加阈值反而会出现"刹车开始后 progress 不动"的反向 bug。

### Decision 3: pure function + 单测

**理由**：

- progress 派生 + waitingForLaunch 派生从原 `Composable` 函数体抽出为 `internal fun computeProgressState(...)`，纯函数，无 Compose 依赖
- 单测放 `feature/test/src/test/`，使用 JUnit4，不引入 Robolectric / Compose UI test 依赖
- 单测保护未来 progress 算法演进（如 follow-up round 改成时间基准时，阈值边界单测仍然有效）

### Decision 4: ProgressPanel 签名变更最小

**选择**：`ProgressPanel(progress: Float, ..., waitingForLaunch: Boolean = false)`，加一个默认值 false 的参数；waitingForLaunch = true 时分支内：

- fill 容器 `Modifier.fillMaxWidth(0f)`（保持容器结构与正常态一致，避免 layout shift）
- 文案改 `"WAITING FOR LAUNCH"` cyan UiTextLabel（替换原百分比数字）

**对比**：

| 方案 | 优 | 劣 |
|---|---|---|
| **A 加 waitingForLaunch 参数（采用）** | 改动最小；ProgressPanel 内部 if-else 分支清晰 | 调用方需要传新参数 |
| **B 抽 WaitingForLaunchPanel 独立 Composable** | 视觉职责清晰 | 引入新 Composable；与 ProgressPanel 共享容器/边框装饰需要复用 helper |
| **C 调用方根据状态选不同 Composable** | 调用方控制 | 调用方代码膨胀 |

A 方案与现有 ProgressPanel 内部结构最贴合，最少改动。

## Risks / Trade-offs

- **[阈值 3.0 不适合所有车型]** → 极慢起步车型可能停留在文案稍久。**Mitigation**：阈值是常量，发现问题改一行；commit message 注明可调
- **[BLE 包下行延迟掩盖体感改善]** → 即使阈值切换正确，BLE first frame 延迟仍会让进度条文案先显示 "WAITING FOR LAUNCH" 一段时间。**Mitigation**：不在本 round scope；BLE / GPS first frame 延迟是数据链路层问题，本 round 通过文案让用户知道"系统知道在等起步"已经显著改善体感
- **[文案 i18n]** → 当前 V2 标签均为英文（`PERFORMANCE` / `BEST 0-100` / `WAITING`），新增 `WAITING FOR LAUNCH` 与现有风格一致，不需要 i18n 处理；如未来引入 i18n 一并处理

## Migration Plan

无 schema / 协议 migration。

部署步骤：

1. 主区开 worktree `.worktrees/improve-test-execution-progress-bar`，切到 `feature/track-tech-v2`
2. 看板 §5 登记本 round；§6 无共享文件占用（独占 TrackTechTestExecutionScreen.kt）
3. 实施 tasks.md
4. 编译 + 单测 + 真机验证（华为）
5. commit + ff-only 合回主区
6. push 等 user 拍板

## Open Questions

无。
