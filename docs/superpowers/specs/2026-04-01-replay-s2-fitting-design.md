# Replay S2 Fitting Design

## 背景

当前 `tianfu_track.vbo` 与 replay 资产对齐后，`起点` 与 `s1` 已能贴近 replay 轨迹，但 `s2` 仍明显落在轨迹包围盒之外，导致 `ReplayLapTimingIntegrationTest` 无法完成 `起点 -> s1 -> s2 -> 起点` 的闭环。

已经确认：

- parser 原先使用 VBO 第一条 `[data]` 记录做归一化锚点，这会对仅保留第 2~4 圈的 replay 小样本造成整体漂移；该问题已修复为按 `referenceSample.timestampMillis` 就近匹配 VBO data 行。
- 修复锚点后，`起点` 与 `s1` 可贴近 replay 轨迹，`s2` 仍无法贴近。
- 同样结论对完整 replay `tianfu_track_replay_5hz.json` 也成立，因此当前更像是 `tianfu_track.vbo` 中 `s2` 定义与 replay 资产本身不一致，而不是小样本裁剪导致。

## 目标

在不污染正式赛道资产与 `TrackCatalog` 的前提下，为 replay / 集成测试链路引入一个临时 `fittedS2`，满足：

1. 保留 `s2` 的语义身份。
2. 尽量继承现有 VBO `s2` 的方向意图。
3. 将 `s2` 平移/拟合到 replay 轨迹真实经过的位置。
4. 让 `ReplayLapTimingIntegrationTest` 至少能完成 1 圈。

## 非目标

以下内容不在本次设计范围：

- 不修改正式 `TrackCatalog` 或预置赛道资产。
- 不将 `fittedS2` 设计成通用线上能力。
- 不对 `LapTimingEngine` 主状态机做补丁式容错以掩盖资产问题。
- 不引入“无限放宽 gate 容差”这类模糊判定策略。

## 方案对比

### 方案 A：移除 `s2`

只保留 `起点 + s1`，先验证 replay 可以完成简化链路。

优点：
- 实现最简单。
- 能快速验证 replay 主链路是否通畅。

缺点：
- 失去完整 sector 语义。
- 会让当前测试目标从“两段门圈速”降级成“一段门圈速”。

### 方案 B：拟合 replay 专用 `fittedS2`（推荐）

保留原始 `s2` 语义与方向约束，但将其位置修正到 replay 轨迹真实经过的位置。仅在 replay / 集成测试链路中使用。

优点：
- 保留完整 `起点 -> s1 -> s2 -> 起点` 语义。
- 不污染正式资产。
- 与未来真实 `S1..S6` 资产替换路径兼容。

缺点：
- 需要增加一层 replay 专用 gate 校正逻辑。
- 临时 gate 仍然不是官方资产，只适合作为验证手段。

### 方案 C：保留现有 `s2` 并扩大误差窗

优点：
- 代码改动可能较少。

缺点：
- 当前 `s2` 与轨迹偏差不是“小误差”级别。
- 简单扩大容差会污染 gate 判定语义，可能误伤其他 crossing。
- 不能证明资产本身合理。

结论：采用 **方案 B**。

## 总体设计

在 replay / 集成测试链路中新增一个 **ReplayGateFitter**，其职责是：

- 输入：
  - replay 轨迹样本列表
  - parser 输出的原始 gates
- 输出：
  - 保留原样的 `起点`
  - 保留原样的 `s1`
  - 一个经过拟合的 `fittedS2`

正式赛道资产、正式目录与主业务链路不感知这层能力。

## 组件边界

### 1. `RaceChronoReplayParser`

职责：
- 继续负责 CSV / VBO 原始解析。
- 继续保留当前“按 reference sample 时间就近匹配 VBO data 行”的归一化修复。

边界：
- 不负责 replay 语义修正。
- 不负责根据轨迹移动 gate。

### 2. `ReplayGateFitter`

职责：
- 检测原始 gate 是否贴近 replay 轨迹。
- 对需要修正的 `s2` 生成 `fittedS2`。

边界：
- 只用于 replay / test。
- 不写回 VBO 文件。
- 不修改 `起点` 与 `s1`。

### 3. `ReplayLapTimingIntegrationTest`

职责：
- 在测试中先 parse 原始 gates，再通过 `ReplayGateFitter` 得到最终 gate 集。
- 用修正后的 gates 构造 Track，验证至少能完成 1 圈。

边界：
- 这是 replay 资产验证测试，不承担正式赛道资产发布职责。

## `fittedS2` 拟合规则

### 前提条件

拟合仅在以下条件满足时触发：

1. `起点` 与 `s1` 已能贴近 replay 轨迹。
2. `s2` 与 replay 轨迹最近距离明显超出可接受范围。
3. replay 样本中能够识别出位于 `s1` 之后、下一次 `起点` 之前的候选轨迹区段。

### 几何约束

`fittedS2` 采用“最小必要修正”原则：

1. **保留方向意图**
   - 原始 `s2` 线段方向向量作为主参考。
   - 不做大幅旋转，只允许必要的平移。

2. **保留长度级别**
   - `fittedS2` 线段长度尽量接近原始 `s2`。

3. **贴近真实 crossing 区段**
   - 候选点必须来自 replay 轨迹中 `s1` 之后、下一次 `起点` 之前的区间。
   - 候选 crossing 必须与当前运动方向不冲突。

4. **避免破坏门顺序**
   - `fittedS2` 不能移动到导致 `起点 -> s1 -> 起点` 或 `起点 -> s2 -> s1` 的位置。

## 候选点选择策略

推荐采用以下流程：

1. 先按 lap 顺序找到每圈：
   - 已接受的 `起点`
   - 已接受的 `s1`
   - 下一次 `起点`

2. 在 `s1` 与下一次 `起点` 之间的轨迹片段中，寻找满足以下条件的候选样本对：
   - 局部运动方向与原始 `s2.passDirection` 同向。
   - 该点对附近的轨迹曲率/位置表现出适合作为第二分段的几何特征。
   - 使用原始 `s2` 方向构造垂直门线后，能形成稳定 crossing。

3. 选择评分最高的候选作为 `fittedS2`：
   - 顺序合法。
   - 几何上最自然。
   - 与原始 `s2` 朝向差异最小。
   - 在多圈 replay 中复现最稳定。

## 误差策略

本次接受“真实资产存在一定误差范围”的前提，但误差控制应建立在**拟合后的几何自洽**基础上，而不是放宽所有判定阈值。

具体原则：

- 对原始 `s2`，不通过单纯放大 gate 命中容差来掩盖资产偏移。
- 对 `fittedS2`，允许小范围几何误差，但必须仍然依赖正常 crossing 检测逻辑。
- 容差只能作为拟合验证的辅助阈值，不作为主修复手段。

## 测试策略

### 1. Parser 测试

继续保留并通过：
- VBO 解析基础测试。
- 按 `referenceSample` 最近时间匹配 VBO data 行的归一化测试。

### 2. Gate Fitter 测试

新增针对 `ReplayGateFitter` 的测试，覆盖：
- `起点` 与 `s1` 已贴轨时，不应修改这两个 gate。
- 原始 `s2` 明显偏离轨迹时，应生成 `fittedS2`。
- `fittedS2` 应贴近 replay 轨迹，且顺序位于 `s1` 之后、下一次 `起点` 之前。
- `fittedS2` 长度与原始 `s2` 保持同量级。

### 3. 集成测试

更新 `ReplayLapTimingIntegrationTest`：
- 用 `ReplayGateFitter` 输出的 gates 构建 Track。
- 目标是跑出至少 1 圈 completed lap。
- 同时保留对 crossing 顺序的检查，防止通过“脏命中”误过测试。

## 风险与回退

### 风险

1. replay 专用拟合逻辑过强，隐藏真实资产问题。
2. 候选点选择不稳，导致不同 replay 样本结果不一致。
3. `fittedS2` 几何上虽然能命中，但语义位置不够自然。

### 回退策略

如果拟合效果不稳定：
- 保留 parser 时间锚点修复。
- 停止继续修改主状态机。
- 暂时降级到 `起点 + s1` 的 replay 验证链路，等待真实 `S1..S6` 资产。

## 未来替换路径

未来你提供真实 `S1..S6` 后：

- 删除 replay 专用 `ReplayGateFitter` 或仅保留为调试工具。
- `ReplayLapTimingIntegrationTest` 直接使用官方 gate 资产。
- 当前 `fittedS2` 不进入正式目录，不承担长期维护责任。

## 结论

本次采用 **replay 专用 `fittedS2`** 方案：

- parser 保留时间锚点修复；
- `s2` 不删除、不靠无限放宽容差；
- 通过 replay 专用拟合，将原始 `s2` 位置修正到几何上合理、顺序上自洽的位置；
- 该能力仅服务于测试 / 回放链路，未来由真实 `S1..S6` 资产替换。
