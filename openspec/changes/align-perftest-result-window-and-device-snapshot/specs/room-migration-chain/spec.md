## ADDED Requirements

### Requirement: migration10To11 严格增加成绩窗口元数据

`AppDatabase` version SHALL 为 11，并 SHALL 提供连续的 `migration10To11`。迁移 SHALL 为 `test_records` 增加可空 start/end sample index、可空 start/end delta，以及 `NOT NULL DEFAULT 0` 的窗口算法版本；现有行和其他列 MUST 保留。

#### Scenario: v10 设备升级到 v11

- **GIVEN** v10 数据库包含历史测试成绩
- **WHEN** 应用首次以 v11 打开数据库
- **THEN** `migration10To11` 成功增加全部窗口列
- **AND** 历史行仍存在且窗口版本为 0、其余窗口字段为 NULL

#### Scenario: migrationChain 连续覆盖到 v11

- **WHEN** 检查 `migrationChain`
- **THEN** 链尾为 startVersion=10 且 endVersion=11
- **AND** v10 不在 destructive fallback 范围内

