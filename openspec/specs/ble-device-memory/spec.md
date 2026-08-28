# ble-device-memory Specification

## Purpose
TBD - created by archiving change ble-device-memory. Update Purpose after archive.
## Requirements
### Requirement: 连接成功后设备记录持久化

设备经 `BleDeviceManager` 发起连接且 `connectionState` 转入 CONNECTED 后,系统 SHALL 将该设备写入 `bluetooth_devices` 表:不存在则插入(address/固件名/lastConnectedAtMs),已存在则仅更新固件名与 lastConnectedAtMs——**MUST NOT 覆盖 alias 列**。

#### Scenario: 新设备首次连接成功(正例)

表中无 `AA:BB:CC:DD:EE:FF` → 用户从扫描列表点击连接 → connectionState 转 CONNECTED → 表中出现该 address 行,name=扫描广播名,lastConnectedAtMs=连接时刻,alias=NULL。

#### Scenario: 已有别名设备再次连接(正例,alias 保留)

表中已有行(address=X, alias="老张的车") → 再次连接成功 → 该行 lastConnectedAtMs 刷新、name 更新为最新广播名,alias 仍为 "老张的车"。

#### Scenario: 连接发起但未成功(反例,不写表)

用户点击连接,15s 超时 connectionState 回 DISCONNECTED、从未到 CONNECTED → 表中 MUST NOT 出现该设备行(从未成功的设备不得成为冷启动自动连目标)。

#### Scenario: 写表路径走 REPLACE 抹掉 alias(反例)

实现若直接复用既有 `insertDevice`(OnConflictStrategy.REPLACE)持久化连接事件 → 已设 alias 行被整行替换、alias 变 NULL → 单测 `recordConnected 不得清除既有 alias` 断言失败。

### Requirement: 冷启动自动连接最近设备

App 进程冷启动时,`BleDeviceManager.autoReconnectLastDevice()` SHALL 查询 `lastConnectedAtMs` 最大的设备记录并自动发起连接;查询为空时 SHALL 直接 fallback 到扫描(既有行为);连接发起后超过既有 RECONNECT_TIMEOUT_MS(10s)未 CONNECTED 时 SHALL fallback 到扫描。

#### Scenario: 有连接记录的冷启动(正例)

表中有两行,lastConnectedAtMs 分别为 T1 < T2 → 冷启动 → 自动对 T2 对应 address 发起连接,无需用户操作;FileLogger 落 `cold-start target=<T2 address>`。

#### Scenario: 无连接记录的冷启动(正例,行为不变)

表为空(或所有行 lastConnectedAtMs 为 NULL)→ 冷启动 → 不发起任何连接,直接开始扫描(与改造前行为一致);FileLogger 落 `cold-start target=none`。

#### Scenario: 目标设备不在场(正例,超时 fallback)

最近设备已关机 → 冷启动自动连 10s 未 CONNECTED → fallback 开始扫描;FileLogger 落 `cold-start result=timeout-fallback-scan`。

#### Scenario: 删除记录后冷启动不得自动连(反例锁单一真相源)

用户在已保存设备管理中删除了设备 X(此前 lastConnectedAtMs 最大)→ 冷启动 → MUST NOT 自动连接 X;若 X 是唯一记录则直接 fallback 扫描。实现若把"上次地址"另存于表外(如 DataStore 单 key)导致删除后仍自动连 X → 违反本条。

### Requirement: 设备别名

用户 SHALL 能对已保存设备设置/修改别名(基于 address 持久化);所有设备展示点(扫描列表、Device 主屏已连接卡片、已保存设备管理列表)SHALL 按 `alias(非空白)> 固件名(非空白)> address` 优先级显示名称。

#### Scenario: 设置别名后扫描列表显示别名(正例)

设备 X 已保存且 alias="老张的车" → 再次扫描发现 X(广播名 "BlazePush GPS")→ 扫描列表该行显示 "老张的车"。

#### Scenario: 别名为空白串时 fallback(正例)

alias 为 `"  "`(空白)→ 显示固件名;固件名也为空 → 显示 address。

#### Scenario: 修改当前已连接设备的别名(正例,主屏同步)

设备 X 当前已连接,主屏显示原名 → 用户改 alias 为 "新名" → 主屏已连接卡片即时刷新为 "新名"。

#### Scenario: 别名不因重新连接丢失(反例)

设 alias 后断开再重连 → alias 仍生效(关联 Requirement"连接成功后设备记录持久化"的 REPLACE 反例)。

### Requirement: 扫描列表"上次连接"标识

扫描列表中,address 等于"lastConnectedAtMs 最大的已保存设备"的行 SHALL 显示 "LAST CONNECTED" 徽标;其余行 MUST NOT 显示该徽标。

#### Scenario: 最近设备出现在扫描结果(正例)

设备 X 是最近连接设备且出现在扫描结果 → X 行显示 LAST CONNECTED 徽标(与既有分类徽标并列)。

#### Scenario: 多台已保存设备同时在场(正例,仅一台标识)

已保存设备 X(T2)与 Y(T1<T2)同时被扫到 → 仅 X 显示 LAST CONNECTED,Y 不显示。

#### Scenario: 无保存记录时无徽标(反例)

表为空 → 扫描列表所有行均无 LAST CONNECTED 徽标。

### Requirement: 已保存设备记录管理

Device 主屏 SHALL 提供"已保存设备"入口;管理界面 SHALL 列出全部已保存设备(显示名/address/最近连接时间),并支持对单条记录改别名与删除;删除 SHALL 立即从表中移除该行且 MUST NOT 断开当前活动连接。

#### Scenario: 删除一条记录(正例)

管理列表中删除设备 Y → Y 行从列表与表中消失;FileLogger 落 `record deleted addr=<Y>`。

#### Scenario: 删除当前已连接设备的记录(正例,连接不断)

设备 X 已连接,用户删除 X 的记录 → 记录消失,但 connectionState 保持 CONNECTED、数据流不中断。

#### Scenario: 删除不存在的行为幂等(反例守护)

对已删除的 address 再次发起删除(双击竞态)→ 无异常、无副作用。

### Requirement: 持久化与决策路径 FileLogger 埋点

设备落库、冷启动决策与结果、别名变更、记录删除 SHALL 落 `FileLogger`(TAG="BleDeviceMemory"),作为 road-test-first 模式的事后诊断安全网。

#### Scenario: 冷启动全链路可诊断(正例)

冷启动自动连成功 → debug_log.txt 中按序出现 `cold-start target=..` 与 `cold-start result=connected` 与 `persisted addr=..`。

#### Scenario: 埋点缺失(反例)

任一上述路径无 FileLogger 调用 → 路测问题无法定位,违反 road-test-first 模式 MANDATORY 埋点条款(apply 期自查项)。

