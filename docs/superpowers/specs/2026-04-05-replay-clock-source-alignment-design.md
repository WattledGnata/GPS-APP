# Replay 时钟源对齐设计

## 背景

当前圈速调试中的 replay 模式会稳定出现约 `125.xs` 的长圈，而不是资产真值中的约 `106.7s`。

经过排查已确认：

1. 接收端收到的轨迹点内容与当前 replay 资产一致，没有串入其他数据资产。
2. 接收端圈速计算使用的是 `GpsSample.timestampMillis`。
3. 现有公共协议本身已经具备完整时间语义，不允许修改协议结构或新增字段。
4. simulator 在 replay 模式下，协议时间字段当前取值来自 `System.currentTimeMillis()`，而不是 replay / RCZ 数字资产中的原始采样时间。

因此，问题不是轨迹内容错误，也不是接收端闭圈公式错误，而是 replay 模式错误地把“发送墙钟时间”当成了“GPS 原始时间”。

## 目标

在**不修改公共协议**的前提下，使 replay 模式下的圈速计算使用数字资产中的原始时间轴，而不是 simulator 发送过程中的本机系统时间。

## 非目标

本轮不做以下事情：

- 不修改公共协议结构、字段定义或编码格式
- 不新增 replay 专用协议字段
- 不修改真实 GPS 硬件模式的时间行为
- 不改圈速引擎闭圈公式
- 不改赛道几何、gate 判定或 BLE 传输格式

## 现状问题

当前 replay 链路分成两层时间：

1. `ReplaySample.timestampMillis`
   - 来自 RCZ / replay 数字资产
   - 用于 planner 计算帧间 delay
2. 协议时间字段
   - 由 simulator 在发包时使用 `System.currentTimeMillis()` 重新生成
   - 接收端最终使用这个时间做圈速计算

结果是：

- replay 样本内容来自资产真值
- 但圈速时间语义来自实际发送耗时
- 当 replay 发送、BLE、接收、解析存在额外开销时，单圈耗时会被整体拉长

## 方案

### 核心原则

**只修改 replay 模式下协议时间字段的取值来源，不修改协议编码规则。**

### 具体设计

#### 1. 保持协议格式不变

沿用当前协议时间编码方式：

- GPS 主包中的 hour 内毫秒字段
- GPS 时间包中的 date + hour 字段
- sync bits 与现有编码布局

即：字节布局、字段位宽、编码方式全部保持不变。

#### 2. replay 模式切换时间值来源

在 simulator 的 replay 播放场景中：

- 经纬度、速度、方位继续来自 `ReplaySample`
- 协议时间字段的源值也改为当前 `ReplaySample.timestampMillis`

在非 replay 场景中：

- 继续沿用当前 `System.currentTimeMillis()` 语义

#### 3. receiver 保持现状

receiver 不新增 replay 特判。

它继续：

- 解析现有协议时间字段
- 生成 `GpsData.timestamp`
- 将该时间透传给 `GpsSample.timestampMillis`
- 由现有圈速引擎按该时间进行 crossing 与 lap duration 计算

这样 replay 模式下的圈速时间自然回到资产真值时间轴，真实设备模式则保持不变。

## 数据流

### 当前错误链路

`ReplaySample.timestampMillis` → planner 计算 delay → 发包时改写成 `System.currentTimeMillis()` → receiver 解码为 `GpsData.timestamp` → 圈速按发送墙钟记时

### 修正后链路

`ReplaySample.timestampMillis` → planner 计算 delay → 发包时按现有协议编码该 sample 的原始时间 → receiver 解码为 `GpsData.timestamp` → 圈速按资产原始时间记时

## 验证方式

### 代码级验证

1. 增加 replay 模式时间编码相关测试
   - 验证给定 `ReplaySample.timestampMillis` 后，主包与时间包编码出的时间字段符合既有协议规则
2. 增加 receiver / lap timing 集成验证
   - 验证 replay 模式下闭圈长段回到接近资产真值 `106.7s`
   - 不再出现 `125.xs` 的稳定长圈

### 真机验证

在 Huawei 真机上重新安装调试包并复现一轮 replay：

- 检查 log 中 `gpsTs` 是否按资产时间轴推进
- 检查 `start-finish accepted` 之间的长段是否回到约 `106.7s`
- 确认不再稳定出现 `125.xs` 长圈

## 风险与约束

### 公共协议约束

这是最强约束：

- 不允许修改协议格式
- 不允许新增字段
- 不允许引入仅本地可用的协议分支

### 实现风险

需要确保 replay 模式切换时间源后：

- 主包与时间包仍然自洽
- hour 内毫秒与 date/hour 组合后能被 receiver 正常还原
- sync bits 与现有收发同步逻辑不受影响

## 成功标准

满足以下条件即可认为本次修正完成：

1. replay 模式下接收端时间轴与 replay 资产时间轴一致
2. 长圈耗时回到约 `106.7s`
3. `125.xs` 长圈现象消失
4. 协议结构零改动
5. 真实硬件模式行为不变
