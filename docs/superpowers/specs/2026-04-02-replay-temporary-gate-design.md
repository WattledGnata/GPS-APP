# Replay Temporary Gate 设计说明

## 背景

在 `feat/replay-fitted-s2` worktree 中，真实 replay 圈速链路已经排查到比“s2 偏离轨迹”更进一步的结论：

- `RCZ` 与 `VBO` 属于同一套 trap 几何源；
- 当前问题不再优先怀疑 parser 归一化、地图坐标协议或 `LapTimingEngine` 状态机；
- 更像是 `s2` 的上游 trap 定义/版本与 replay 轨迹不一致；
- 当前 `ReplayGateFitter` 的“仅平移、保持原长度与朝向”策略，只能证明 synthetic case 可用，不能作为真实 replay 的最终方案。

因此，本次设计目标不是修正正式赛道资产，而是在 replay 测试链路中构造临时 gate，验证状态机在真实 replay 上可以完成圈。

## 目标

仅在 replay 测试/回放场景里生成 `replay-derived temporary gate`，用于替换异常 gate（当前为 `s2`），打通真实 replay 的圈速集成验证链路。

## 非目标

- 不修改 `TrackCatalog` 或正式赛道资产；
- 不将 replay 推导出的 gate 写回持久化定义；
- 不放宽 `GateCrossingDetector` 或 `LapTimingEngine` 的全局容差；
- 不把资产真值问题伪装成状态机问题；
- 不尝试在本轮解决所有 gate 的资产版本管理问题。

## 方案对比

### 方案 A：window-derived temporary gate（推荐）

在 replay 测试域中，仅对异常 gate 在可信 window 内重新生成临时 gate。

优点：
- 直接针对资产真值问题；
- 不污染正式资产与主链路；
- 不需要修改 `LapTimingEngine`；
- 规则可解释、可测试。

缺点：
- 需要定义 replay window、anchor 选择和构造规则；
- 只适用于 replay/测试态，不是正式资产修复方案。

### 方案 B：继续增强 ReplayGateFitter

允许在现有 fitter 上同时调中心点、朝向、长度，继续“修补”旧 gate。

优点：
- 表面上可复用现有代码路径。

缺点：
- 语义混乱，本质已不是“拟合旧 gate”而是重建新 gate；
- 仍会被原始错误几何绑架；
- 难以清晰划分测试域兜底与正式资产修复的边界。

### 方案 C：在集成测试中硬编码临时 s2

为当前 replay 样本手工指定一个临时 `s2`。

优点：
- 最快打通单条链路。

缺点：
- 缺乏可迁移性与可解释性；
- 无法沉淀通用验证规则；
- 后续必然返工。

## 推荐方案

采用方案 A：`window-derived temporary gate`。

实现语义应从 `ReplayGateFitter` 调整为 `ReplayTemporaryGateBuilder`（或 `ReplayDerivedGateBuilder`），明确它不是在修补旧 gate，而是在 replay 测试域中从真实轨迹派生临时 gate。

## 设计边界

### 作用域

- 仅存在于测试代码：`feature/test/src/test/java/...`
- 只服务于 replay 集成测试与几何验证测试
- 不进入生产代码路径

### 输入

- 原始 replay gates（来自 VBO / parser）
- replay samples

### 输出

- 一组供测试使用的 gates
- 其中异常 gate（当前为 `s2`）可被 replay-derived temporary gate 替换
- 其余 gate（当前为 `Start/Finish` 与 `s1`）保持原值

## 数据流

1. 使用原始 `Start/Finish` 和 `s1` 在 replay 上定位可信 crossing；
2. 用 `s1 -> next Start/Finish` 区间作为 `s2` 搜索窗口；
3. 在窗口内选择一个最适合作为 crossing anchor 的样本对；
4. 依据 replay 局部轨迹方向构造 temporary gate；
5. 用 temporary gate 替换测试态 `s2`，再进入 `LapTimingEngine` 集成验证。

## Temporary Gate 生成规则

### 1. 定位可信 window

- 先找到已接受的 `Start/Finish` crossing；
- 再找到其后的已接受 `s1` crossing；
- 再找到下一个已接受 `Start/Finish` crossing；
- 将 `s1 -> next Start/Finish` 之间作为 `s2` 搜索窗口。

如果任一步无法确定，则视为没有可信 window，直接回退为原始 gates，不做临时替换。

### 2. 选择 crossing anchor

遍历 window 内 `zipWithNext()` 的 replay sample 对，从中挑选最适合构造 gate 的 anchor。

优先级：
- 位于窗口中部附近；
- 速度稳定；
- 局部轨迹不存在明显回头；
- 相邻点位移足够大，降低噪声影响。

如果没有满足条件的 anchor，则回退为原始 gates。

### 3. 构造 temporary gate

- gate 中点：取 anchor 样本对的中点；
- gate 朝向：由 replay 局部切向量的法线决定；
- gate 长度：优先复用原始 `s2` 的宽度；
- `passDirection`：取 replay 实际前进方向，而不是沿用旧 `s2` 的方向；
- 只替换测试态 `s2`，不修改其他 gate。

## 错误处理与回退策略

该设计的原则是“宁可不替换，也不生成不可信 gate”。

因此在以下场景中直接回退为原始 gates：
- 找不到可信 window；
- 找不到有效 anchor；
- 生成的 temporary gate 无法通过基础几何合理性校验。

回退后，集成测试应继续失败，从而保留对资产问题的显式暴露，而不是用宽松规则掩盖问题。

## 测试策略

### A. Builder 单测

验证：
- 仅替换 `s2`；
- 生成 gate 位于 `s1 -> next Start/Finish` window 内；
- 生成 gate 长度符合预期；
- 生成 gate 朝向来自 replay 切向量，而非原始 `s2`；
- 无有效 window / anchor 时回退原始 gates。

### B. 几何合理性测试

验证：
- temporary gate 中点距离 window 轨迹足够近；
- 用 temporary gate 回放窗口样本时，至少能产生一次 accepted crossing；
- crossing 的方向与 `passDirection` 一致。

### C. 集成测试

在 `ReplayLapTimingIntegrationTest` 中验证：
- accepted order 前四个 gate 为 `起点 -> s1 -> s2 -> 起点`；
- `completedLaps.size == 1`；
- sector times 与 lap duration 符合预期。

## 组件职责边界

- `RaceChronoReplayParser`：负责解析 replay 与原始 gate 资产；
- `ReplayTemporaryGateBuilder`：负责在 replay 测试域中从轨迹派生 temporary gate；
- `LapTimingEngine`：继续只负责圈速状态机推进；
- `GateCrossingDetector`：继续只负责 crossing 判定，不承担资产容错职责。

## 成功标准

- 不修改主状态机与全局 crossing 容差；
- 测试态仅替换异常 gate；
- 真实 replay 集成测试可完成一圈；
- 设计可以清楚说明：当前打通的是 replay 测试链路，而不是已经修复正式资产真值。
