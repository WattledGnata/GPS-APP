## MODIFIED Requirements

### Requirement: SPEED CURVE 与 G-FORCE 曲线卡

Metric Row 下方 MUST 依次包含两个 `CutCornerPanel`：

1. **SPEED CURVE 卡** —— 内容 MUST 调用 `SpeedChart` 并传入最终成绩窗口 dataPoints 和对应模板上限。
2. **G-FORCE 卡** —— 内容 MUST 调用 `GForceChart` 并传入同一最终成绩窗口 dataPoints。

Metric Row 的平均 G 与峰值 G MUST 从该最终窗口的统一 `AccelerationSmoother` 序列派生，不得用整段原始 binary 的统计值。若窗口 dataPoints 为空，曲线卡 MUST 优雅退化。

#### Scenario: 两条曲线与摘要消费相同窗口

- **WHEN** 一条成绩同时显示速度曲线、G 曲线、PEAK G 和 AVG G
- **THEN** 四者均由同一个最终窗口 dataPoints 派生
- **AND** 窗口外样本不参与任何一项

#### Scenario: binary 读取或窗口识别失败

- **WHEN** 详情页无法得到有效窗口 dataPoints
- **THEN** 页面不崩溃
- **AND** 曲线卡隐藏或显示 muted 的 `No data`

### Requirement: SPEED SEGMENTS 区段

页面底部 MUST 包含 `SPEED SEGMENTS` 区段，每行展示区间、时间和距离。分段数据 SHALL 仅从与速度/G 曲线相同的最终窗口 dataPoints 派生，且 SHALL 使用边界插值后的时间轴，不得重新读取整段 binary 独立选段。

#### Scenario: 分段总范围与主成绩一致

- **WHEN** 渲染一条 0–100 成绩的速度分段
- **THEN** 分段从 0 km/h 开始并以 100 km/h 结束
- **AND** 分段使用的首尾时间与主成绩窗口首尾一致

## ADDED Requirements

### Requirement: 速度图最高速度与 Y 轴语义真实

`SpeedChart` 标题的最高速度 SHALL 显示最终窗口样本的真实最大速度。Y 轴上限 SHALL 取真实最大速度和测试模板上限中的较大值；IQR 上界 MUST NOT 用作最高速度文案或主 Y 轴上限。

#### Scenario: 长时间低速加一次 0–100

- **GIVEN** 原始会话的速度四分位数导致 IQR 上界约 13 km/h，但最终窗口真实最大速度约 100 km/h
- **WHEN** 渲染 0–100 速度曲线
- **THEN** 标题最高速度显示约 100 km/h
- **AND** Y 轴上限至少为 100 km/h

### Requirement: G 曲线使用方向量程并显示数值刻度

0–100 的 G 曲线 SHALL 使用 `0 → 正峰值` 的单向量程，100–0 SHALL 使用 `负峰值 → 0` 的单向量程；通用调用方可继续使用对称量程。图表 SHALL 显示上、中、下三档 Y 轴 G 数值和零基线，使用户能够判断峰谷范围。系统 MUST NOT 仅凭 GPS 纵向加速度自动标注具体挡位；换挡只通过真实 G 值回落呈现。

#### Scenario: 0–100 换挡回落不被对称轴压缩

- **GIVEN** 最终窗口 G 值均为正且峰值约 0.78G
- **WHEN** 渲染 0–100 G 曲线
- **THEN** Y 轴下限为 0G、上限至少为 0.78G
- **AND** 图上显示 0G、中间值和上限数值
- **AND** 换挡期间的真实 G 值凹谷占用完整单向绘图区
