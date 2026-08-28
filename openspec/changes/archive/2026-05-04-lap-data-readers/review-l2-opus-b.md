# L2 Adversarial Review — Opus 子 agent B 线（lap-data-readers）

> 触发时机：2026-05-05 主区合回 commit `3c2f2d9` 之后、归档之前
>
> Reviewer：CC 主会话 spawn 的 general-purpose 子 agent，model=opus，不持有此 round 主会话 context
>
> Codex review 因后端失效（reconnect 失败 + 僵尸任务）由本线替代。差异化角度：实施代码 line-by-line vs spec normative 对齐 / 跨 module ripple / W2 兼容性 / v3 高频盲点 #9-#14 / tasks §10 backlog 真实性 / design D1/D2/D5 决策最优性
>
> Round 代码作者：mimo-v2.5-pro

## 必跑核查动作结果

### A. spec normative vs code 对齐表

| spec 条款 | 代码位置 | 状态 |
|---|---|---|
| Req1: getSession 不存在 → null | TelemetryRepository.kt:273 | ✓ |
| Req1: filter accepted+SF + sort wallClock 升序 + null 排末 | TelemetryRepository.kt:275-277 | ✓ |
| Req1: zipWithNext 配对 + 越界 → null | TelemetryRepository.kt:278 | ✓ |
| Req1: 任一 wallClock null → null | TelemetryRepository.kt:279-280 | ✓ |
| Req1: readLapSamples 包 runCatching | TelemetryRepository.kt:281-284 | ✓ |
| Req1: 派生字段 | TelemetryRepository.kt:286-308 | ✓ |
| Req1: flags 透传 + accelerationG=null | TelemetryRepository.kt:294-295 | ✓ |
| Req1: rawSamples.isEmpty → null | TelemetryRepository.kt:285 | **✗（spec 未声明该 invariant，case D 测试依赖）** |
| Req2: testRecordDao + dataFilePath="" → null | TestResultRepository.kt:144-145 | ✓ |
| Req2: readPerformanceSamples 包 runCatching + emptyList → null | TestResultRepository.kt:146-150 | ✓ |
| Req2: 派生 testStart/EndWallClock + samples | TestResultRepository.kt:151-170 | ✓（细节有 P1 风险，详 P1-1） |
| Req3: 数据契约字段全包含 + 不可变 + accelerationG default null | LapTelemetry.kt | ✓ |
| Req4 gate-A: ≥2 次 | TelemetryRepository.kt:277/279/280 | ⚠️（spec pattern dead，详 P1-2） |
| Req4 gate-B | TelemetryRepository.kt:272-309 | ✓ |
| Req5: TelemetryRepository.kt 0 实质引用 TestResult 系列 | grep | ✓ |

### B. W2 测试在 HEAD 上仍绿
跑 `:feature:test:testDebugUnitTest` BUILD SUCCESSFUL，0 failure（含 SpeedTimeChartContractTest / AccelTimeChartContractTest / SectorBarContractTest / TrackPolylineMapContractTest / GrepGateTest）。**W2 命名参数构造调用兼容 HEAD 新增的 `flags: Int = 0` default，无回归。**

### C. 本 round 测试在 HEAD 上仍绿
10/10 cases 全部通过（time=0.565s）。

### D. 跨 module caller 完整性
`grep "TestResultRepository(" --include='*.kt'` 仅 3 处命中：
- `LapTelemetryReadersTest.kt:55` — 已加第 3 参数 ✓
- `TestResultRepository.kt:30` — class 定义本体 ✓
- `feature/test/di/AppModule.kt:89` — `single { TestResultRepository(get(), get(), get()) }` ✓

**生产代码无遗漏**。

### E. fake DAO abstract method 完整性
对照所有 4 个 DAO 接口签名（TelemetrySessionDao 11 / CrossingEventDao 3 / TestRecordDao 10 / SpeedSegmentDao 4）与 `LapTelemetryReadersTest.kt` 内 fake 类实现，**全部 abstract method 都已 override**，零遗漏。v3 高频盲点 #14 已规避。

### F. deferred memo 同步状态
`docs/design/speed-curve-real-data-persistence-deferred.md` 顶部第 3-13 行已加 ⚠️ 状态更新块。**v3 高频盲点 #15 已规避。**

## 发现项

### P0：无

### P1-1：getDataPointsForResult 的 entity.timestamp 锚点跨时钟域风险（spec 未锁 invariant）

**位置**：`core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt:151-155` + `openspec/changes/lap-data-readers/specs/lap-telemetry-readers/spec.md:72-75`

**现状**：实现 `testStartWallClock = entity.timestamp`，`absoluteTsMs = testStartWallClock + sample.tsDeltaMs`。spec line 75 声称"§8.4/M anchor 已对齐"。

**问题**：通过追溯生产代码路径：
```
RaceChronoParser.kt:240  → protocolTimestamp = reference.hourStartMillis + timeSinceHourStart  (GPS 协议时间)
                           或 Long.MIN_VALUE sentinel（GPS 未同步时）
GpsData.timestamp        ← protocolTimestamp
FilteredGpsData.timestamp ← raw.timestamp
TestSessionViewModel.kt:727  session.startTime = filteredData.timestamp  (GPS 协议时间)
CalculateResultUseCase.kt:56  TestResult.timestamp = session.startTime
TestResultRepository.kt:50    TestRecordEntity.timestamp = result.timestamp  (GPS 协议时间)
```

而 `sample.tsDeltaMs` 在 `TestSessionViewModel.kt:744` 是 `System.currentTimeMillis() - sessionStartTs`（接收侧真壁钟 delta）。

两个 anchor **不严格同源**：
- GPS 协议时间 = `hourStartMillis (UTC 卫星时间衍生) + timeSinceHourStart` —— 与 `System.currentTimeMillis()` 仅在 GPS 同步且本地时钟与 UTC 同步时近似相等
- 当 GPS 未同步时 `entity.timestamp = Long.MIN_VALUE` sentinel，`absoluteTsMs = Long.MIN_VALUE + tsDeltaMs ≈ Long.MIN_VALUE`，整个数据契约崩塌

虽然生产实务中 PERFORMANCE_TEST 仅在 GPS 同步（satellites>=6 + hdop<2.0）时才能 trigger，但 spec **未 normative 锁定** "entity.timestamp 必须 != Long.MIN_VALUE" + "GPS 协议时间与本地 wallClock 已对齐"的 invariant。这是 v3 高频盲点 #9（fallback 表达式跨时钟域回归）的潜在复发。

**修订建议**：
- spec Requirement 2 加 normative：`entity.timestamp != Long.MIN_VALUE` 是前提，否则返回 null
- 实现层在 line 151 之前加 `if (entity.timestamp == Long.MIN_VALUE) return null`
- spec line 75 的"§8.4/M anchor 已对齐"声明需补充具体 invariant

### P1-2：spec / tasks 的 grep gate-A pattern 在 macOS BSD/ugrep 下 dead（trivially fail）

**位置**：
- `spec.md:169` `grep -nE 'crossing(\w+)?\.crossingWallClockTimestampMs \?:'`
- `spec.md:182` 同形态
- `tasks.md:64` 同形态

**问题**：在 macOS BSD grep / ugrep 跑此 ERE pattern：
- `\w` 不是 POSIX ERE 标准
- 实测命中 **0 次**

**这构成 v3 高频盲点 #7（grep gate trivially pass / fail）**：spec 写的 grep gate 用户跑出来 0 命中，但 spec 期望 "恰好 2 行"——按 spec 字面 verify 会 fail。**真正 effective 的 gate 是测试代码 line 184 的 Kotlin Regex**。

**修订建议**：
- spec line 169 / 183 + tasks line 64 的 grep pattern 改为 POSIX ERE 兼容形态：`crossing[a-zA-Z]*\.crossingWallClockTimestampMs[[:space:]]*\?:`
- 或显式注明"用 GNU grep（ggrep）/Kotlin Regex"才有意义

### P1-3：实现的 `if (rawSamples.isEmpty()) return null` 未在 spec Requirement 1 normative 锁定

**位置**：`TelemetryRepository.kt:285` + `spec.md:7-21`

**问题**：与 Opus A 线 P1-2 同向。case D 测试隐式依赖该分支。

**修订建议**：spec Requirement 1 同步加 normative：
```
- readLapSamples 返回 emptyList 视为读取失败 → 返回 null
```

并对齐 Requirement 2 line 71 措辞。

### P1-4：metrics.yaml 与看板 commit_merge=`13c4791` 是不存在的 commit hash

**位置**：
- `metrics.yaml:29` `commit_merge: "13c4791"`
- `parallel-change-collab.md:137` 末列 `13c4791`
- `parallel-change-collab.md:175-177` 备注列均含 `13c4791`

**问题**：跑 `git log --all --oneline | grep 13c4791` 命中 **0 次**。HEAD 实际合回 commit 是 `3c2f2d9`。最可能：mimo 在 worktree 内本地 commit 是 `13c4791`，但 ff-only 合回主区时 git 重新签名为 `3c2f2d9`，mimo 没 verify 实际主区 commit 就 pre-populate metrics.yaml。

v3 verification-before-completion 违规。`commit_merge` 字段意义就是给后续 review / archaeology 提供精确锚点。

**修订建议**：
- metrics.yaml line 29 改为 `commit_merge: "3c2f2d9"`
- 看板 line 137 末列 + line 175-177 备注列同步替换

### P2-1：metrics.yaml `review_rounds_l2: 0  # user decided to skip Codex review`

归档前需要更新为 `review_rounds_l2: 2`（A + B 两线 Opus 子 agent）+ 把本 review 的 P1/P2 findings 写入 `review_findings_l2`。

### P2-2：sectorBoundaries.size == 1（半闭环风险已 design R7 mitigation 但 design 标 P 不足）

design R7 列了 "(a)/(b)" 两选项但**未拍板**。这让 Tier2 round design 期需要重新决策——属于"半闭环承诺"（v3 高频盲点 #1）。

### P2-3：spec Scenario 7（混合 row）未对应 case 测试

spec 列了"5 条 SF crossing 前 2 null + 后 3 非空"的混合场景 Scenario，但 tasks 5.2 case A-J **没有** 单独 case 覆盖此混合 row 场景。

## 总结

### P0：无
### P1：4 项
1. **P1-1** spec Requirement 2 未锁 `entity.timestamp != Long.MIN_VALUE` invariant + 跨时钟域风险
2. **P1-2** spec/tasks gate-A grep pattern 在 macOS BSD/ugrep 下 dead trivially-fail
3. **P1-3** 实现 `if (rawSamples.isEmpty()) return null` 未在 spec Requirement 1 锁
4. **P1-4** metrics.yaml + 看板 commit_merge 写错（`13c4791` 不存在，实际 `3c2f2d9`）

### P2：3 项
1. metrics.yaml `review_rounds_l2: 0` 归档前 MUST 补
2. design R7 SectorBar Tier2 落地决策未拍板
3. spec Scenario "混合 row" 无对应 case 测试覆盖

## 是否放行归档：**NO**

**理由**：本 round 实施代码 + 测试质量整体 OK（10/10 测试全绿、W2 0 回归、跨 module caller 完整、DAO fake 完整），但有 1 项 metric/看板 git hash 错误（P1-4）+ 3 项 spec 与实现不一致（P1-1/-2/-3）需在归档前修复。其中 P1-4 + P2-1 是 metric/governance 项目自检失误，不能带瑕疵归档；P1-1/-2/-3 是 spec 缺漏，影响 future round 起草。

虽然代码 runtime 行为正确（生产 robustness 充分），但工件级别的 self-contained / normative 对齐不足，违反 v3 baseline 完整性 7 条款。

## 必修清单（按优先级）

### 必须在归档前修（P1）
1. **修 metrics.yaml + 看板 commit hash**：把 4 处 `13c4791` 改为 `3c2f2d9`
2. **spec Requirement 2 加 sentinel invariant**：normative 加 `entity.timestamp != Long.MIN_VALUE` 否则返回 null + TestResultRepository.kt 实现层加对应 guard
3. **spec gate-A grep pattern 修复**
4. **spec Requirement 1 加 isEmpty normative**

### 归档前可补（P2）
5. **metrics.yaml 补 L2 review_rounds_l2: 1+ + review_findings_l2**
6. **design R7 SectorBar Tier2 决策拍板**
7. **tasks 5.2 加 case K 混合 row 场景**

### 可推延到 follow-up round（P3）
8. `LapTelemetryReadersTest.projectRoot()` 的 `protectionDomain` nullable warning 是 baseline 历史问题（PresetTrackAssetTest verbatim copy）

## Adversarial 收尾

mimo 在工件细节（双语义 caveat / fallback 反例 scenario / DAO fake 完整性 / W2 兼容性 / runCatching 包装）上做得相当扎实，10 cases 全绿不是表面胜利。但在**项目治理元数据**（commit hash）+ **跨时钟域 anchor**（entity.timestamp 来源链路）+ **grep gate 实际可执行性**（POSIX ERE vs PCRE）三个角度上有 sunk-cost 盲点——这恰好是 mimo 不在主会话 context、靠工件文字推理时的容易漏点。本 review 的 4 个 P1 都是无法通过"测试全绿"指标自动 catch 的工件/治理项问题。
