# Lap Debug Timing Card Reset Design

**Goal:** 重构圈速调试页的起终点计时卡，让它明确展示上一圈、当前圈、当前圈路程和最近起点穿线时间，并保证每次 accepted 起终点都会刷新当前圈基准，重新进入页面时回到干净测试环境。

## Background

当前 `LapDebugExecutionScreen` 的起终点计时卡只使用 `statusLabel / elapsedLabel / detailLabel` 三段文案表达状态：
- 没有 accepted 起终点时显示等待文案
- 第一次 accepted 起终点后显示固定的 `0.000 s`
- 第二次 accepted 起终点后显示上一段耗时与新的起点时间戳

这种表达方式有几个问题：
1. `elapsedLabel` 不区分“上一圈”和“当前圈”，肉眼很难快速判断
2. 当前圈缺少可见计时，无法实时看到已经进行了多久
3. 起点时间戳直接显示 epoch millis，不适合真机调试
4. 没有以起终点为基准的当前圈路程显示
5. 页面退出再进入时，用户需要明确回到干净测试环境

本次只改 LapDebug 圈速调试页的状态表达和会话重置体验，不改穿线检测算法本身。

## Scope

### In scope
- 重构起终点计时卡的状态模型与布局
- 展示上一圈用时、当前圈已进行时长、当前圈累计路程、最近起点穿线时间、状态文案
- 以 accepted 起终点作为当前圈计时与路程的唯一刷新基准
- 最近起点穿线时间改为仅显示时分秒
- 停止并返回后，再重新进入圈速调试页时回到全新干净会话
- 补状态计算与格式化相关测试

### Out of scope
- 修改 `LapTimingEngine` 的 accepted crossing 判定规则
- 修改 Track / Gate 几何
- 在执行页新增“重置”按钮
- 改 telemetry card 的 speed / bearing / G 值逻辑
- 改 simulator replay 发射逻辑

## Recommended Approach

采用 **单卡片重构 + 基于 session 派生状态** 的方式：
- 保留一张“起终点计时”卡，不拆成多张
- 扩展当前 `StartFinishTimingCardState`，让它变成结构化状态而不是三段模糊文案
- 所有显示值都从 `LapSession` 派生，不在 UI 层维护额外可变业务状态
- 退出并重新进入时，通过新建 `LapSession` 达成“干净测试环境”

原因：
- 和当前页面结构最一致，改动集中
- 语义清晰：上一圈、当前圈、路程各有固定槽位
- 测试最好写，状态来源单一
- 不需要引入额外重置按钮或新的复杂交互

## Design

### 1. Card semantics

起终点计时卡改为“圈状态摘要卡”，固定展示 5 项信息：
- 上一圈
- 当前圈
- 当前圈路程
- 最近起点穿线
- 当前状态

accepted 起终点驱动规则如下：

- **0 次 accepted 起终点**
  - 卡片处于初始态
  - 上一圈显示 `--`
  - 当前圈显示 `0.000 s`
  - 当前圈路程显示 `0.0 m`
  - 最近起点穿线显示 `--`
  - 状态显示 `等待起点`

- **第 1 次 accepted 起终点**
  - 这一刻成为当前圈起点
  - 当前圈计时从 0 开始
  - 当前圈路程从 0 开始累计
  - 最近起点穿线刷新为该 crossing 的时分秒
  - 状态显示 `当前圈进行中`

- **第 2 次及以后 accepted 起终点**
  - 结算刚刚结束的一整圈
  - `上一圈用时 = 本次 accepted 起点时间 - 上次 accepted 起点时间`
  - 然后立刻以本次 accepted 起点作为新一圈的起点
  - 当前圈计时清零后重新开始
  - 当前圈路程清零后重新累计
  - 最近起点穿线刷新为新的时分秒

### 2. Layout

卡片标题仍为 `起终点计时`。

正文改为固定字段布局：
- 第一行：`上一圈` | `当前圈`
- 第二行：`当前圈路程` | `最近起点穿线`
- 第三行：`状态`

显示格式：
- 上一圈：`--` 或 `4.000 s`
- 当前圈：`0.000 s` 或实时递增值
- 当前圈路程：`0.0 m` 或累计值
- 最近起点穿线：`--` 或 `17:02:13`
- 状态：`等待起点` / `当前圈进行中`

不再把关键信息拼接进 `detailLabel` 的自由文案里。

### 3. Data derivation

卡片状态全部由 `LapSession` 派生。

建议扩展 `StartFinishTimingCardState` 为结构化字段，例如：
- `lastLapElapsedLabel`
- `currentLapElapsedLabel`
- `currentLapDistanceLabel`
- `lastStartFinishTimeLabel`
- `statusLabel`

各字段来源：

#### 上一圈用时
来自最近两次 accepted 起终点 crossing 的时间差。

#### 当前圈已进行时长
来自“最近一次 accepted 起点时间”到“当前最新 GPS sample 时间”的差值。
因此它是实时变化值，而不是静态值。

#### 当前圈路程
只统计“最近一次 accepted 起点之后”的 `lapSession.samples`。
按相邻 GPS sample 间的轨迹距离累计。
每次新的 accepted 起点出现后，统计窗口从新的 sample 起点重新开始。

#### 最近起点穿线时间
取最近一次 accepted 起终点 crossing 的 `timestampMillis`，格式化为时分秒，不显示日期。

### 4. Reset semantics

这次明确两层重置：

#### 圈级重置
每经过一次 accepted 起终点：
- 当前圈计时清零并重新开始
- 当前圈路程清零并重新累计
- 上一圈刷新为刚结束那一圈的总时长

#### 会话级重置
用户点击执行页底部的停止按钮后：
- 停止当前 LapDebug session
- 返回上一级入口
- 再重新进入圈速调试页时，新建全新的 `LapSession`
- 所有卡片字段回到初始空状态

这满足“返回并重新进入干净测试环境”，但不在执行页新增额外的重置按钮。

## Files likely affected

- `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt`
  - 重构卡片状态模型、布局和格式化逻辑
- `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestExecutionScreen.kt`
  - 如需要，把当前时间或 session 派生值传入新的卡片状态计算入口
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`
  - 确认停止/退出/重新进入时的会话清空链路满足设计
- `feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt`
  - 更新并补充状态派生测试
- `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/...`
  - 如需要，补“退出并重新进入获得全新 session”相关测试

## Test Plan

### State tests
至少覆盖：
- 无 accepted 起点时显示初始空状态
- 第一次 accepted 起点后：当前圈开始、上一圈仍为空
- 第二次 accepted 起点后：上一圈写入、当前圈重置并重新开始
- 最近起点穿线时间格式化为时分秒
- 当前圈路程只统计最近一次 accepted 起点之后的样本

### Session reset tests
至少覆盖：
- 停止并退出后，重新进入圈速调试页会创建全新 `LapSession`
- 新 session 下卡片状态回到初始态，不保留旧圈结果

## Risks

### Current lap elapsed is time-sensitive
- 风险：如果直接用系统时钟，测试会不稳定
- 处理：尽量基于 `lapSession.samples.lastOrNull()?.timestampMillis` 推导当前圈时长，避免 UI 层依赖真实时钟

### Distance accumulation drift
- 风险：GPS sample 距离累计会有轻微误差
- 处理：当前只做调试展示，接受小量误差；实现上采用稳定的相邻点距离累加即可

### Reset semantics split across stop/exit paths
- 风险：如果停止与退出路径行为不一致，可能出现旧 session 残留
- 处理：把“停止返回后重新进入 = 新 session”作为明确测试契约锁住

## Decision

本次采用：
- 单卡片重构为圈状态摘要卡
- 每次 accepted 起终点都刷新当前圈基准
- 最近起点穿线仅显示时分秒
- 退出并重新进入时回到全新干净会话
- 不新增页内重置按钮
