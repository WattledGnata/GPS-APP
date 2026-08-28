# Tasks: fix-accel-last-crossing

## 1. 锚点自检(apply 启动前,#3 盲点)

- [x] 1.1 grep 验证工件锚点与生产代码对齐:`grep -n "return dataPoints" core/domain/src/main/java/com/blazepush/core/domain/usecase/CalculateResultUseCase.kt`(预期命中 line 86 一处);`grep -n "prev.speed < targetSpeed" 同文件`(line 112);`grep '^### Decision ' openspec/changes/fix-accel-last-crossing/design.md` 列 6 决策(1/1b/2/3/4/5)。偏移则先修订工件再动代码。

## 2. 算法实现(CalculateResultUseCase.kt)

- [x] 2.1 `correctTimingPoints`(line 73-97)重写:签名 `→ List<GpsDataPoint>?`;按 design Decision 1 正向状态机收集完整(起步→首次过线)候选段、取最后一个;加速/刹车镜像(Decision 1b);运动阈值 `max(模板哨兵, 1.0)`(Decision 2);零完整候选 → null(Decision 3)。
- [x] 2.2 `findPrecisePoint`(line 102-133)替换为状态机内的插值 helper:相邻对过线线性插值产 GpsDataPoint(elapsedTime/lat/lon 插值,speed=target);`prev.speed == curr.speed` 跳过该对(spec R4)。
- [x] 2.3 `invoke`(line 40-49)null 分流:`correctTimingPoints` 返回 null → 计时类字段归零 + segments 空 + dataPoints 空,但 SG 三项(avg/max/maxDec,line 33-37 已在 raw 上算好)保留(spec R2 Scenario 3)。
- [x] 2.4 编译过 + 既有 `CalculateResultUseCaseTest` 全绿(若现有用例依赖旧 fallback 行为则按新语义修订断言并在 commit body 说明)。

## 3. 单测(CalculateResultUseCaseTest.kt)

- [x] 3.1 spec R1 三场景:多次蠕动只算最后冲刺(断言 totalTime 在 8s±插值容差,**绝不**≈38s)/单次干净起步/回落再破百取最后过线。
- [x] 3.2 spec R2 反例:99 km/h 未破百 → totalTime==0.0(53.32s 回归锁);全程静止 DNF;DNF 时 maxAcceleration 仍非零。
- [x] 3.3 spec R3 刹车:刹停+挪车不延长成绩;未刹停(最低 8)DNF。
- [x] 3.4 spec R4 边界:平台速度段无异常;畸形序列不产负值。

## 4. 调用方日志锚点(TestSessionViewModel.kt)

- [x] 4.1 `finishTest`(line 1022-1042)落 `FileLogger.d(TAG, "perfResult window: total=… dist=… points=窗口N/全量M dnf=…")`(design Decision 5);确认 TAG 常量已存在。

## 5. 自审 gate(road-test-first)

- [x] 5.1 CC 主会话单遍自审(§A 角度:scope 假闭环/决策最优性);#14 fake DAO、#16 共享字段 drift 自查(本 round 预期空命中,记录"已查无")。
- [x] 5.2 `./gradlew :core:domain:testDebugUnitTest :feature:test:compileDebugKotlin` 全绿。

## 10. Follow-up backlog

- `shouldEnd-filtered-judgement`:`template.shouldEnd(filteredData.raw)`(TestSessionViewModel.kt:686)改 filtered 判停 + 触发余量评估;现由 DNF fallback 兜恶果,真机攒批观察"仪表破百却 DNF"出现率再立项。
- `perftest-dnf-ui`:result 屏对 totalTime=0 的显式 DNF 标识(文案/配色);依赖本 round DNF 语义落地后的 UI 设计。
- `braking-prebuffer-from-ready`:刹车成绩从物理 100 km/h 起算——需 Ready 态(95-105 巡航)预缓冲数据、触发后把 100→95 段并入 session;当前数据从触发(~95)开始,窗口起点只能取首帧(design Decision 1b 实施期修订,2026-06-04)。涉及采集链路改造,独立立项。
