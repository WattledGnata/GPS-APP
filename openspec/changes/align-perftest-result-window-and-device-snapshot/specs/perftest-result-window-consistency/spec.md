## ADDED Requirements

### Requirement: 完整原始遥测与最终成绩窗口并存

系统 SHALL 完整保留一次性能测试会话的原始 binary 遥测，并 SHALL 在成绩记录中额外保存最后一个完整有效窗口的 start/end sample index、精确 start/end 时间偏移和算法版本。保存成绩窗口 MUST NOT 裁剪、覆盖或删除原始 binary。

#### Scenario: 38 秒会话包含最后一次 8 秒有效加速

- **GIVEN** 原始会话含测试前爬行、失败尝试和最后一次完整 0–100
- **WHEN** 成绩保存完成
- **THEN** 原始 binary 仍包含完整约 38 秒遥测
- **AND** 成绩窗口只指向最后一次完整 0–100 的约 8 秒区间

### Requirement: 内存和 binary sample 必须可一一映射

性能测试内存序列和 binary 文件 SHALL 以同一个 GPS 首帧为相对时间原点，按相同顺序且恰好一次写入预触发帧、触发帧和运行帧。窗口 sample index MUST 指向 binary 中相同车辆状态的 sample。

#### Scenario: 触发帧只写入一次

- **GIVEN** 预触发缓存含 N 帧，随后第 N+1 帧触发测试开始
- **WHEN** 初始化性能测试会话
- **THEN** 内存和 binary 的前 N+1 帧顺序完全相同
- **AND** 触发帧在两侧都只出现一次

#### Scenario: BLE 到包抖动不改变运动时间轴

- **GIVEN** GPS timestamp 等间隔但手机收到 BLE 包的 wall clock 有抖动
- **WHEN** 写入性能测试 binary
- **THEN** `tsDeltaMs` 按 GPS timestamp 相对首帧计算
- **AND** 不使用到包 wall clock 作为 sample 间隔

### Requirement: 所有成绩消费者使用同一个最终窗口

总时间、总距离、速度分段、速度曲线、G 曲线、平均 G、峰值加速 G 和峰值制动 G SHALL 只消费持久化最终窗口经边界插值和时间归零后的同一组 dataPoints。

#### Scenario: 窗口外尖峰不污染成绩

- **GIVEN** 测试前或完成后的原始遥测包含速度或 G 尖峰
- **WHEN** 生成并展示成绩
- **THEN** 尖峰仍存在于原始 binary
- **AND** 成绩摘要、曲线和分段均不包含该尖峰

### Requirement: 旧成绩只读重建窗口

窗口算法版本为 0 或窗口字段为空的历史记录 SHALL 在详情页从其原始 binary 只读识别最后一个完整窗口，且 MUST NOT 隐式回写数据库。无法识别时页面 SHALL 优雅降级并留下诊断日志。

#### Scenario: 旧 0–100 成绩无窗口字段

- **GIVEN** 一条旧成绩关联完整会话 binary 但没有窗口元数据
- **WHEN** 用户打开详情页
- **THEN** 页面重建最后一个完整 0–100 窗口用于曲线和派生指标
- **AND** 历史数据库行保持不变

