## MODIFIED Requirements

### Requirement: 设备别名

用户 SHALL 能对已保存设备设置或修改基于 address 持久化的别名。所有设备展示点及新建性能成绩 SHALL 按 `alias(非空白) > 真实设备名(非空白) > address` 优先级选择名称。

性能测试 SHALL 在 `startTest` 时冻结包含显示名和地址的不可变设备快照；完成与保存 MUST 使用该快照，而不是重新读取完成瞬间的连接状态。这里的真实设备名来自扫描或连接状态记录，MUST NOT 使用 `RaceChrono GPS` 等硬编码占位值替代已知名称。

#### Scenario: 别名优先保存到成绩

- **GIVEN** 当前设备地址 X、真实名 `BlazePush-Gen2-0003`、别名 `张豪`
- **WHEN** 开始并完成性能测试
- **THEN** 成绩设备名称保存为 `张豪`
- **AND** 成绩设备地址保存为 X

#### Scenario: 测试中断连不改变快照

- **GIVEN** 测试开始时设备已连接且快照名称为 A
- **WHEN** 测试完成前设备断开或连接状态名称清空
- **THEN** 成绩仍保存名称 A 和开始时地址

#### Scenario: 别名为空时逐级回退

- **GIVEN** 别名为空白
- **WHEN** 创建测试开始快照
- **THEN** 使用非空真实设备名
- **AND** 真实设备名也为空时使用地址

#### Scenario: 修改当前已连接设备的别名

- **GIVEN** 设备 X 当前已连接
- **WHEN** 用户将 alias 修改为 `新名`
- **THEN** 后续开始的测试使用 `新名`
- **AND** 已开始测试的冻结快照不变
