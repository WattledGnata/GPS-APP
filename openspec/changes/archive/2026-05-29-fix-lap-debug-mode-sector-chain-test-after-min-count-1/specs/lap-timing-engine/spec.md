## ADDED Requirements

### Requirement: sector 链不完整时 startFinish 二次过线宽容闭合（lenient lap closure）

`LapTimingEngine` SHALL 在 debug 模式（`TestMode.LapDebug`）下采用**宽容闭合**语义：
当一个 lap session 已有 active lap（首次 startFinish 过线已开圈）且再次出现一个 **accepted
的 StartFinish 过线**时，引擎 MUST 无条件闭合当前圈，**不要求中间 sector 门链全部通过**。

normative 契约（已核实于生产实现 `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:158-223`）：

- 第二次 accepted StartFinish 过线 MUST 使 `LapSession.completedLaps` 长度 +1（产生一条
  闭合的 `LapRecord`），且 `LapSession.currentLapIndex` MUST 推进到下一圈编号。
- 若闭合圈的 `activeLap.sectorEntries.size != track.sectorGates.size`（sector 链不完整），
  该 `LapRecord.qualityFlags` MUST 包含 `LapQualityFlag.IncompleteSectors`
  （`feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt:7`）。
- 若 sector 链**完整**（`sectorEntries.size == sectorGates.size`），闭合圈的 `qualityFlags`
  MUST NOT 包含 `IncompleteSectors`（保证该 flag 不是无条件加的）。
- 宽容闭合 MUST 在 GPS 数据流经过 `GpsDataFilter`（`bridgeGpsToLapTiming` 上游接 filter，
  仅替换 lat/lon/speed/bearing 四字段，见 `TestSessionViewModel.kt:341-357`）之后仍成立——
  即过线判定 MUST 基于物理合理的 GPS 输入，filter 接通不得使有效过线无法闭圈。

**MUST NOT 约束**：

- 本契约 MUST NOT 改写生产判圈逻辑、filter 集成契约（`TestSessionViewModel.kt:346-356`
  锁定的 4 字段替换 + `timestamp` 保 raw）、`GpsDataFilter` 的 `isPositionAnomaly` 阈值。
- 宽容闭合的 sector 不完整提示 MUST 以 in-memory `LapRecord.qualityFlags.IncompleteSectors`
  为权威信号源；实时 banner（`LapLiveStateDeriver.AbnormalState.LAP_INVALIDATED`，其
  `invalidatingReasons` 仅 `{WrongDirection, UnexpectedGateOrder, TooSlow}`，不含 sector 不完整）
  与详情屏 chip 的接通不在本 requirement 范围（独立 follow-up，见 change tasks §10）。

#### Scenario: 正例——filter 接通后 sector 不完整二次过线宽容闭合

- **GIVEN** debug 模式选定 runtime replay 对齐的 TFIC track（含 startFinish + sector 门），
  且 GPS 数据流经 `GpsDataFilter`，已完成首次 accepted StartFinish 过线（开圈，`activeLap != null`，
  期间未通过任何 sector 门或仅通过部分 sector 门）
- **WHEN** 注入物理合理的第二次 StartFinish 过线两帧（位移与 speed 一致、不触
  `isPositionAnomaly`），使其判定 accepted
- **THEN** `session.completedLaps.size == 1`、`session.currentLapIndex == 2`、
  `session.completedLaps.first().qualityFlags.contains(LapQualityFlag.IncompleteSectors)` 为 true，
  且该绿测**靠真实闭圈达成**（非靠放宽 expected 或删断言）

#### Scenario: 反例——退回严格闭合则 normative 被违反、测试 MUST fail

- **GIVEN** 同上 GIVEN（首圈已开、sector 链不完整、filter 接通）
- **WHEN** 实现被错误改回**严格闭合**（sector 链不全则第二次 StartFinish 过线不闭圈）
- **THEN** `session.completedLaps.size` 为 0，断言 `assertEquals(1, completedLaps.size)`
  MUST fail —— 该反例锁死「宽容闭合契约被违反时测试必红」，禁止用"改 expected 回 0 /
  删断言"把红测刷绿

#### Scenario: 边界——sector 链完整闭圈不打 IncompleteSectors

- **GIVEN** debug 模式，首圈开圈后**依次通过全部 sector 门**（`sectorEntries.size ==
  sectorGates.size`）
- **WHEN** 第二次 accepted StartFinish 过线闭圈
- **THEN** `session.completedLaps.size == 1`，且 `completedLaps.first().qualityFlags`
  MUST NOT 包含 `LapQualityFlag.IncompleteSectors`（验证 flag 仅在真 sector 不完整时加，
  防止无条件打 flag 的回归）

#### Scenario: 反例——弱断言放过 sector 标记丢失

- **GIVEN** 闭合圈的 `qualityFlags` 因实现回归丢失 `IncompleteSectors`，但仍含其他 flag
  （如 `ProtocolDesyncGap`）
- **WHEN** 断言写成宽泛的 `qualityFlags.isNotEmpty()`
- **THEN** 该宽泛断言会**错误地通过**，放过 sector 标记丢失的回归 —— 因此本 requirement
  MUST 要求断言用 `contains(LapQualityFlag.IncompleteSectors)` 精确锁定，禁止用 `isNotEmpty()`
