## ADDED Requirements

### Requirement: 出圈实时上报与前置校验

出圈(一圈完成,产出 `LapTelemetry`)时,系统 SHALL 在满足**全部**前置条件时向 `POST /api/v1/laps` 上报该圈:(a) 上报开关 `livetimingEnabled == true`;(b) `driverName` 非空;(c) `LapTelemetry.trackId != null`。任一不满足 → SHALL 跳过上报且 SHALL NOT 入待传队列。上报字段按 `LapTelemetry` 映射(`driver`←driverName,`lapNo`←lapIndex+1,`lapTimeMs`←lapDurationMs,`sectorsMs`←sectorBoundaries 相邻差,`lappedAt`←lapEndWallClock 转 RFC3339,`trackId`←trackId);`carModel` SHALL NOT 上传(`LapTelemetry` 无此字段)。上报 SHALL 为旁路副作用,失败 MUST NOT 影响本地圈速记录与 UI。

#### Scenario: 前置满足时上报成功

- **GIVEN** `livetimingEnabled=true`、`driverName="老王"`、`LapTelemetry.trackId="preset-tfic-lpcc"`
- **WHEN** 一圈完成触发上报,服务端返回 `201`
- **THEN** 调用 `POST /api/v1/laps`,请求体 `driver="老王"`、`lapNo=lapIndex+1`、`lapTimeMs=lapDurationMs`、含 `Authorization: Bearer <token>`
- **AND** 该圈不进待传队列(已成功)

#### Scenario: 开关关闭时不上报不入队

- **GIVEN** `livetimingEnabled=false`
- **WHEN** 一圈完成
- **THEN** 不调用上报接口
- **AND** 该圈 **不** 进待传队列(关闭 = 完全不上报,而非延后)

#### Scenario: 无车手名跳过并提示（反例方向）

- **GIVEN** `livetimingEnabled=true` 但 `driverName` 为空
- **WHEN** 一圈完成
- **THEN** 不调用上报接口、不入队
- **AND** 触发一次性"请先设车手名"提示(引导设置)

#### Scenario: trackId 为 null（自由跑）跳过

- **GIVEN** `LapTelemetry.trackId == null`(未选赛道的自由跑)
- **WHEN** 一圈完成
- **THEN** 不上报、不入队(无法归属赛道)

### Requirement: clientLapId 按圈稳定幂等

`clientLapId` MUST 一圈一个、稳定唯一,且**首次上报与所有重试复用同一个值**。实现 SHALL 取 `"${sessionId}:${lapIndex}"`(`LapTelemetry` 现成字段,天然按圈稳定)。系统 MUST NOT 在每次请求构造时新生成(如 `UUID.randomUUID()`);否则弱网重试时键漂移 → 服务端视为新圈 → 重复入库,幂等失效。

#### Scenario: 同一圈首传与重试用同一 clientLapId

- **GIVEN** 某圈 `sessionId="s1"`、`lapIndex=2`
- **WHEN** 首次上报失败入队、随后 flush 重试
- **THEN** 首次与重试的 `clientLapId` 均为 `"s1:2"`(完全相等)
- **AND** 服务端对重复 `clientLapId` 返回 `201` 且只入库一次(幂等)

#### Scenario: 不同圈生成不同 clientLapId

- **GIVEN** 同一 session `s1` 的第 2、3 圈
- **WHEN** 分别上报
- **THEN** `clientLapId` 分别为 `"s1:2"` / `"s1:3"`(不冲突)

#### Scenario: 反例锁——上报路径不得在请求构造处随机生成 clientLapId

- **GIVEN** 上报 / 重试相关生产代码(DTO 组装 + 队列入队/出队路径)
- **WHEN** 静态扫描这些路径的源码
- **THEN** 构造上报请求体处 **不含** `UUID.randomUUID()` / `randomUUID` 等每次新生成调用
- **AND** `clientLapId` 来源 **是** 稳定派生(`sessionId` + `lapIndex` 拼接)或队列中已持久化的列值(证明"生成一次、复用",违反此约束的实现此断言 fail)

### Requirement: 失败落 Room 待传队列与幂等补传

上报失败(网络异常 / 5xx / 429 / 离线)时,系统 SHALL 把该圈持久化到待传队列表(`clientLapId` 列 UNIQUE);并在**出圈时**与 **app 启动时** flush 队列、用同一 `clientLapId` 重试。收到 `201`(含幂等重复)SHALL 将该条出队。同一 `clientLapId` SHALL NOT 重复入队(唯一约束)。

#### Scenario: 网络失败入队

- **GIVEN** 前置满足,上报时网络异常 / 无网
- **WHEN** 一圈完成上报失败
- **THEN** 该圈以 `clientLapId="${sessionId}:${lapIndex}"` 持久化进待传队列(retryCount 记录)

#### Scenario: flush 复用 clientLapId 重试成功后出队

- **GIVEN** 待传队列含一条 `clientLapId="s1:2"`
- **WHEN** flush(出圈或 app 启动)触发,服务端返回 `201`
- **THEN** 用 **同一** `clientLapId="s1:2"` 重试
- **AND** 成功后该条从队列删除(出队)

#### Scenario: 同圈重复入队被唯一约束挡（反例方向）

- **GIVEN** 待传队列已有 `clientLapId="s1:2"`
- **WHEN** 同一圈再次失败尝试入队
- **THEN** 受 `clientLapId` UNIQUE 约束,队列中该圈仍只有一条(不重复堆积)

### Requirement: 错误与限流处理

系统 SHALL 区分处理上报响应:`429` → 读 `Retry-After` 头退避后重试,SHALL NOT 无视退避狂发;`400`(合理性校验失败)→ SHALL NOT 进入无限重试死循环(标记/丢弃 + 日志);`401`(token 错)→ 该圈落队列待 token 修复后补传(不丢)。flush 多条时 SHALL 串行/带间隔,避免触发限流。

#### Scenario: 429 按 Retry-After 退避

- **GIVEN** 上报返回 `429` + `Retry-After: 1`
- **WHEN** 处理响应
- **THEN** 不立即重发,按 `Retry-After` 等待后再试(该圈保留在队列)

#### Scenario: 400 不进入死循环

- **GIVEN** 上报返回 `400`(如分段和与整圈相差过大)
- **WHEN** 处理响应
- **THEN** 该圈 **不** 无限重试(标记为不可上报 / 丢弃 + 记日志)
- **AND** 不阻塞后续圈的上报

#### Scenario: 上报异常不影响本地圈速记录

- **GIVEN** 上报链路抛任意异常
- **WHEN** 一圈完成
- **THEN** 本地 `LapTelemetry` 记录与圈速 UI 正常(上报是旁路,异常被吞进队列/日志)
