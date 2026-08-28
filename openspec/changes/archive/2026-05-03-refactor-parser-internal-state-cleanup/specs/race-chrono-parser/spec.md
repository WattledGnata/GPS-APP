# Spec Delta: race-chrono-parser

> Capability: **RaceChronoParser 内部状态污染清理**（A26 + A41）。parser 作为
> "字节→GpsData" 纯翻译层 MUST NOT 副作用式写入与本帧无关的字段（A26），MUST NOT
> 维护任何对外部无可见消费者的内部会话状态（A41）。两 Requirement 互相独立，可
> 单独回退。
>
> 真相源：
> - A26 拍板：`docs/superpowers/reviews/2026-04-22-lap-timing-and-gps-adversarial-review.md § 2.12`
> - A41 拍板：同 review `§ 2.11`
> - backlog：`docs/superpowers/reviews/attack-backlog.md` A26 / A41 核销条件
>
> Non-goals：
> - 不改 parser frequency 计算块（有外部消费者 `GpsDataViewModel.gpsFrequency`，
>   A28 另有清理计划）
> - 不改主包 `isTestReady = satellites >= 6 && hdop < 2.0` 的判定（主包仍是
>   `isTestReady` 的唯一写入源）
> - 不改 `protocolTimeReference` 的写入时序（A8 战役已固化为单源）
> - 不引入新的 distance 计算 domain usecase（若未来需要实时里程再起新 change）

## ADDED Requirements

### Requirement: `parseGpsTimeData` MUST NOT 写 `isTestReady` 字段（R1 / A26）

RaceChronoParser 的 `parseGpsTimeData` 方法 MUST NOT 在任何成功路径或失败路径
修改输入 `GpsData` 的 `isTestReady` 字段值。`isTestReady` 的唯一写入源 MUST 是
`parseGpsData` 主包根据 `satellites >= 6 && hdop < 2.0` 判定。

`parseGpsTimeData` 成功路径 MUST 保留的职责：
- 写入 `protocolTimeReference`（单源时间同步基准，A8 契约）
- 清理 `errorMessage = null`（A25 契约闭合，避免前帧失败残留）

`parseGpsTimeData` 失败路径 MUST 保留的职责：
- 短包 / 解析异常时写 `errorMessage = "short-packet"` 或 `"parse-error: ..."`（A25）

理由：时间包到达只能代表"时间同步基准更新"，不能代表"GPS 定位质量满足测试就绪门
槛"；下一帧主包会用 `satellites / hdop` 覆盖判定，造成 UI "就绪 ↔ 未就绪"闪烁
（冷启动锁星不稳时尤明显）。

#### Scenario: 时间包到达时输入 `isTestReady = false` 保持 false

- **GIVEN** `currentData = GpsData(isTestReady = false, ...)`（初始未就绪）
- **AND** 3 字节合法时间包 `data = byteArrayOf(0x20, 0x12, 0x34)`（`syncBits = 1`，
  `dateAndHour` 任意合法值）
- **WHEN** `parser.parseGpsTimeData(data, currentData)`
- **THEN** 返回的 `result.isTestReady == false`
- **AND 硬区分 v1**：v1 实现返回 `result.isTestReady == true`（本断言证明 v2 已不
  在时间包路径写 isTestReady）
- **AND** `result.errorMessage == null`（A25 清理仍保留）

#### Scenario: 时间包到达时输入 `isTestReady = true` 保持 true

- **GIVEN** `currentData = GpsData(isTestReady = true, ...)`（上一帧主包已判就绪）
- **AND** 合法 3 字节时间包
- **WHEN** `parser.parseGpsTimeData(data, currentData)`
- **THEN** 返回的 `result.isTestReady == true`（时间包路径不覆盖已就绪状态）
- **AND** `result.errorMessage == null`

#### Scenario: 时间包与主包交替冷启动不再闪烁 `isTestReady`

- **GIVEN** 冷启动序列：先收时间包（satellites 不够 6）再收主包（satellites=4）
  再收时间包再收主包（satellites=8）
- **WHEN** 四次 parse 顺序执行
- **THEN** 输出序列 `isTestReady` 状态为 `[false, false, false, true]`
- **AND 硬区分 v1**：v1 会输出 `[true, false, true, true]`（时间包每次把 false
  翻回 true）

#### Scenario: 时间包短包失败不动 `isTestReady`

- **GIVEN** `currentData = GpsData(isTestReady = true, ...)`
- **AND** 短包 `data = byteArrayOf(0x20, 0x12)`（长度 2，不足 3）
- **WHEN** `parser.parseGpsTimeData(data, currentData)`
- **THEN** 返回 `result.isTestReady == true`（失败路径不碰该字段）
- **AND** `result.errorMessage == "short-packet"`（A25 契约保留）

---

### Requirement: parser MUST NOT 维护 tracking 相关内部死状态（R2 / A41）

RaceChronoParser 类体 MUST NOT 声明或写入以下任何字段：
- `totalDistance`
- `hasStartedTracking`
- `startTime`
- `lastLatitude`
- `lastLongitude`

`parseGpsData` 方法体 MUST NOT 包含基于 `fixQuality > 0 && satellites >= 3` 阈值
的 "Tracking Calculation" 代码块；MUST NOT 调用 `Location.distanceBetween` 进行
内部距离累加；MUST NOT 在 `parseGpsData` 路径调用 `System.currentTimeMillis()`
（frequency 计算块的 `System.currentTimeMillis()` 不受本 Requirement 影响，因为
frequency 有外部消费者，属活状态）。

`reset()` 方法 MUST NOT 清理上述 5 个已删字段（字段本身不存在），但 MUST 保留对
现有 frequency / 时间同步活字段的清理行为：
- `gpsDataTimestamps.clear()` + `gpsFrequency = 0.0`（frequency 活状态）
- `protocolTimeReference = null`（时间同步活状态）

本 Requirement MUST NOT 新增或修改对 `lastFrequencyUpdateTime` 字段的 reset 行为
——原 `reset()` 也未清理该字段，是否补充属 frequency 活状态完整性问题，另行评估
（A28 scope），不在本 change 顺手修。

理由：这 5 个字段从不写回 `GpsData`、无 getter、外部零消费者（`rg` 审计
[参见 proposal.md 问题 2 节]），属纯副产物。阈值 `satellites >= 3` 又与
`isTestReady` 的 `satellites >= 6` 不一致，纯历史残留。删除可消除 25Hz × JNI
`Location.distanceBetween` 开销与"parser 依赖系统时钟"的错误暗示（战役 A 时钟源
单源化原则）。

#### Scenario: `RaceChronoParser` 类上不存在 5 个已删字段

- **GIVEN** 通过 Kotlin 反射查询 `RaceChronoParser::class.java.declaredFields`
- **WHEN** 遍历所有声明字段
- **THEN** 字段集合 MUST NOT 包含名为 `totalDistance` / `hasStartedTracking` /
  `startTime` / `lastLatitude` / `lastLongitude` 中的任何一个
- **AND 硬区分 v1**：v1 上述 5 个字段全部存在（本断言证明 v2 已删除）
- **AND** `gpsFrequency` / `gpsDataTimestamps` / `lastFrequencyUpdateTime` /
  `protocolTimeReference` 等合法字段仍然存在（frequency / 时间同步活状态未被
  误删）

#### Scenario: `parseGpsData` 解析有效定位帧不产生 tracking 副作用

- **GIVEN** 合法 20 字节主包 `data`（`fixQuality = 1`，`satellites = 8`，典型
  坐标 60.1725N / 24.9375E）
- **AND** 一个新创建的 `RaceChronoParser`
- **WHEN** `parser.parseGpsData(data, initialGpsData)` 连续调用 100 次（模拟
  25Hz × 4s 真实定位序列）
- **THEN** 所有返回的 `result.*` 字段按协议正确解码（`satelliteCount == 8`、
  `altitude / speed / bearing / hdop / vdop` 等）
- **AND** parser 类上仍不存在上述 5 个已删字段（反射查询结果稳定）
- **AND** `result` 不包含任何"累计距离"字段（`GpsData` 本身也无此字段，
  但断言保留以锁定未来不引入）

#### Scenario: `reset()` 仍清理 frequency + 时间同步活状态

- **GIVEN** 已运行过一段时间的 parser（`gpsFrequency > 0`，`protocolTimeReference
  != null`）
- **WHEN** `parser.reset()`
- **THEN** 反射读取 `gpsFrequency == 0.0`
- **AND** 反射读取 `gpsDataTimestamps.size == 0`
- **AND** 反射读取 `protocolTimeReference == null`
- **AND** 类上仍不存在已删的 5 个字段（反射再次验证）
