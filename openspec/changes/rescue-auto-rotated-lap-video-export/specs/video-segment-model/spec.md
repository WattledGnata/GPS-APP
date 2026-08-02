## ADDED Requirements

### Requirement: 历史相邻视频段的可桥接 chapter 判定

系统 SHALL 在不迁移历史数据库的前提下，根据 `segmentIndex` 顺序、每段实际媒体时长和 wall-clock 区间，构造相邻视频 slices 与 gaps。只有 gap 两侧都存在同一 Session 内的相邻有效段，且 gap 时长不超过 5 秒时，才 SHALL 标记为可导出桥接；原始 `VideoSegment`、文件和持久化时间戳 MUST 保持不变。

#### Scenario: 两侧相邻段形成可桥接 gap

- **WHEN** segment 0 结束于 T，segment 1 开始于 T+1200ms，二者均有可读取时长
- **THEN** 时间轴 SHALL 把 1200ms gap 标记为可桥接，并保留其真实起止 wall-clock

#### Scenario: 短但只有单侧画面不可桥接

- **WHEN** 圈头缺少 2 秒且时间轴中不存在结束于缺口左侧的前一有效 segment
- **THEN** 该缺失 MUST NOT 标记为 chapter bridge

#### Scenario: 超过上限的相邻段不可桥接

- **WHEN** 相邻 segment 间 gap 为 5001ms
- **THEN** 该 gap MUST 保持不可桥接，导出 gate SHALL 保守阻止受影响圈

#### Scenario: 历史记录运行时生效

- **WHEN** 1.0.7 已保存的 Session 在覆盖安装新版本后重新打开
- **THEN** 系统 SHALL 使用现有 `video_segments` 与媒体文件重新计算 chapter bridge，无需重录、重新绑定或 Room migration
