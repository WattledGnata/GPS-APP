# ble-auto-reconnect

BLE 意外断开的会话内自动重连:退避策略、用户意图区分、状态可观测。

## ADDED Requirements

### Requirement: 意外断开 MUST 自动退避重连

`BluetoothDataSource` 在 connectionState 变为 DISCONNECTED 且最近一次用户意图为连接(`connect()` 后未调 `disconnect()`)时,SHALL 以指数退避(1s 起步、每次 ×2、封顶 30s)自动重连最近请求的设备地址;重连 MUST 持续直到 CONNECTED 或用户意图变更,MUST NOT 设置尝试次数上限。

#### Scenario: 远端断开后自动恢复
- **GIVEN** connect(A) 已 CONNECTED,随后远端断开(state → DISCONNECTED)
- **WHEN** 退避延迟(首次 1s)到期
- **THEN** 自动发起对 A 的重连(state → CONNECTING);若仍失败,下次延迟 2s、4s…封顶 30s

#### Scenario: 连接成功复位退避
- **GIVEN** 重连重试到第 4 次(延迟已达 8s)后 CONNECTED
- **WHEN** 此后再次意外断开
- **THEN** 退避从 1s 重新开始(attempt 计数已复位)

#### Scenario: 连接超时同样触发重连
- **GIVEN** connect(A) 后 15s 连接超时(CONNECTING → DISCONNECTED,无用户操作)
- **WHEN** 退避延迟到期
- **THEN** 自动重连 A(超时与远端断开同等对待)

### Requirement: 用户主动意图 MUST NOT 触发重连

用户调用 `disconnect()` 后 SHALL 取消挂起的重连任务且不再调度新重连;调用 `connect(B)` 切换设备 SHALL 取消针对旧地址 A 的挂起重连并以 B 为唯一重连目标。

#### Scenario: 主动断开不重连(反例)
- **GIVEN** connect(A) 已 CONNECTED
- **WHEN** 用户调用 disconnect()
- **THEN** state 终态 DISCONNECTED,任意等待时间后 MUST NOT 出现自动 CONNECTING——此断言失败即"主动断开被当意外"回归

#### Scenario: 切设备后旧地址重连作废
- **GIVEN** connect(A) 断开后退避重连排队中
- **WHEN** 用户调用 connect(B)
- **THEN** 对 A 的挂起重连取消;后续意外断开只重连 B

#### Scenario: 冷启动无连接历史不重连
- **GIVEN** App 启动后从未调用 connect()
- **WHEN** 任意时间流逝
- **THEN** MUST NOT 发起任何连接尝试(lastRequestedAddress 为空)

#### Scenario: 新连接建立不被初值 replay 假重连拆链(回归锁)
- **GIVEN** connect(A) 成功进入 CONNECTED(BleConnection StateFlow 的 replay 初值 DISCONNECTED 在 collect 启动时到达)
- **WHEN** 此后 ≥1s 无任何断开事件
- **THEN** MUST NOT 出现自动重连把已建立的连接拆掉——此断言失败即"初值 replay 误判断开"回归(实现以 drop(1) + CONNECTED 取消挂起重连双保险)

### Requirement: 重连过程 MUST 经现有 connectionState 流可观测

每次重连尝试 SHALL 产生 DISCONNECTED → CONNECTING 状态转移并经 `connectionState` StateFlow 传导(feature 层 BleLiveness 日志锚点因此自动落盘);重连不引入新的对外状态接口。

#### Scenario: 状态转移序列完整
- **GIVEN** 一次意外断开 + 两次失败重试 + 第三次成功
- **WHEN** 观察 connectionState 流
- **THEN** 序列为 DISCONNECTED → CONNECTING → DISCONNECTED → CONNECTING → DISCONNECTED → CONNECTING → CONNECTED(每次尝试可见,无静默重试)
