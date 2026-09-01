## Context

vivo 在 2026-09-01 12:05 产生了一条 0–100 成绩：成绩 8.23 s、距离 133.1 m，但结果页载入的是约 38.2 s / 1005 点的整段 binary，且速度卡显示“最高 13 km/h”。离线核对表明真实最大速度约 100.2 km/h；13 来自 `robustRange` 的 IQR 上界，不是最大值。

当前链路还有一个会破坏窗口映射的隐患：`TestSession` 启动时把“预触发缓存 + 触发帧”写入内存，而 binary 只写预触发缓存；内存 elapsed 使用 GPS 时间，binary delta 使用手机到包时的 wall clock。即使保存内存窗口索引，也不能可靠切到 binary 中同一批 sample。

约束是完整原始遥测必须继续保留；Room 升级必须保留现有成绩；RaceChrono BLE 公共协议和 simulator 不改；当前 worktree 的 UUID 4 主动补读属于独立实验。

## Goals / Non-Goals

**Goals:**

- 让内存计算序列与 binary sample 具备同顺序、同数量、同一 GPS 相对时间基准。
- 将最后一个完整有效测试窗口作为成绩唯一真相源，并持久化可追溯的索引与精确偏移。
- 让耗时、距离、分段、速度/G 曲线和 G 摘要消费同一窗口。
- 在测试开始时冻结设备身份，避免完成时连接变化。
- 兼容没有窗口元数据的旧记录，并以严格 Room 迁移保留数据。

**Non-Goals:**

- 不裁剪或覆盖原始 binary，不改变诊断证据保存周期。
- 不修改 GPS 过滤器、RaceChrono BLE 协议、UUID 或发射端行为。
- 不重做 `perftest-result-detail-navigation-feedback` 的完成页交互。
- 本轮不声明所有车型和路况均已真机验证；最终仍需后续路测验收。

## Decisions

### 1. 性能测试 sample 使用 GPS 相对时间作为唯一索引时间轴

性能测试启动时先固定 `initialFrames = preTriggerBuffer + triggerFrame`，以首帧 GPS timestamp 为 origin。内存和 binary 按同一列表顺序各写一次；运行期每个过滤后 GPS 帧也按同一顺序写入两者。binary 的 `tsDeltaMs` 使用 `frame.timestamp - origin`，而不是手机收到包时的 `System.currentTimeMillis()`。

选择 GPS 相对时间是因为成绩插值本来就基于 GPS 时间，且 BLE 到包抖动不应改变车辆运动时间轴。binary header 的 wall clock 起始时间仍保留为文件元数据。圈速链路不改，避免影响视频/过线 wall-clock 契约。

### 2. 窗口元数据同时保存 sample 边界和精确时间边界

`PerformanceResultWindow` 保存：左边界 sample index、右边界 sample index、相对原始首帧的 start/end delta、算法版本。sample index 用于快速定位和完整性校验；精确 delta 用于在跨阈值的两帧之间插值，从而保留 8.23 s 等亚 sample 精度。

只保存裁剪后的数组会丢失原始上下文；只保存时间会削弱损坏诊断；只保存索引则无法表达插值边界，因此两者同时保留。

### 3. 先识别最终窗口，再统一派生

窗口识别器返回最后一个完整的模板区间。它从原始序列计算边界，不直接丢弃原始文件。结果计算将窗口切片、插值、从 0 重新计时后，再统一计算耗时、距离、分段和 G 值。结果页也通过相同窗口读取器得到同一组点。

旧记录的窗口版本为 0 或字段为空时，详情页从原始 binary 只读重算最终窗口，不更新数据库。若无法识别完整窗口，则优雅降级为原始数据，并明确记录诊断日志，避免页面崩溃。

### 4. G 摘要由最终窗口的统一平滑序列派生

窗口点继续使用既有 `AccelerationSmoother`。`maxAcceleration`、`maxDeceleration` 和 `avgAcceleration` 只聚合窗口内样本；曲线也绘制这组值。测试前爬行、失败尝试及完成后减速不再污染成绩摘要。

### 5. 速度图拆分“真实最高速度”和“绘图上限”

标题显示 `samples.maxOf(speed)`。绘图 Y 轴上限取真实最大速度与模板上限的较大值；0–100 至少为 100 km/h，100–0 至少为 100 km/h。IQR 仅可用于异常诊断，不再参与最高速度文案或主量程。

### 6. 设备快照在 startTest 冻结

测试开始时从保存设备记录和当前连接状态组装不可变快照，名称优先级为非空用户别名、非空真实设备名、地址。完成和保存只消费该快照，不重新查询连接状态。这样即使完成瞬间断连或用户改名，本次成绩仍保留开跑时身份。

### 7. Room v11 严格增加可空窗口列

`test_records` 新增 start/end sample index、start/end delta 和非空算法版本（默认 0）。v10→v11 只执行 `ALTER TABLE`，旧记录自然落为无窗口元数据。迁移加入连续 `migrationChain`，不扩大 destructive fallback 范围。

## Risks / Trade-offs

- [旧 binary 的时间轴来自 wall clock 到包时间，和新 GPS delta 语义不同] → 旧记录不信任索引字段；仅以速度序列重识别窗口，并保留降级路径。
- [GPS timestamp 倒退或重复] → 写入前对 delta 做非负、单调校验并记录日志；异常时终止成绩窗口持久化而不删除原始文件。
- [窗口边界位于两帧之间] → 保存包围边界的 sample index，同时按精确 delta 插值。
- [完成时 binary actor 尚有排队写入] → 结束会话先有序 flush/close，再保存引用该文件的成绩。
- [新增字段增加历史模型构造成本] → 字段提供兼容默认值，集中映射，补齐迁移与构造回归测试。

## Migration Plan

1. 先加入领域窗口模型、识别器及纯函数测试。
2. 对齐性能测试内存/binary 写入并加数量、单调时间日志。
3. 升级 Room v11 和 repository 映射，验证 v10 数据保留。
4. 结果页改为窗口读取，修正 G 摘要与速度轴。
5. 构建 Debug APK，以 `adb install -r` 覆盖 vivo，保留应用数据；先用旧 12:05 记录验证详情页兼容，再等待下一次 0–100 路测验证新窗口元数据。

回滚代码时 v11 数据库仍可被旧 v10 APK 拒绝打开，因此设备级回滚需要安装同 schema 兼容构建或备份后清数据；本轮不自动执行降级安装。

## Open Questions

- 下一次路测需确认硬件 GPS 时间在完整测试期间严格单调，并核对内存/binary sample 数量一致日志。
- 旧 12:05 binary 可验证曲线兼容，但由于记录创建时尚未保存窗口字段，不能证明新索引持久化路径；该项需新成绩闭环。

