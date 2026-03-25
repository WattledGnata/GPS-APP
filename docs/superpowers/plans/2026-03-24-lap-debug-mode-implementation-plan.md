# Lap Debug Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有测试主链路上增加一套与普通测速隔离的圈速调试模式，打通“选择模式 → 圈速配置 → 手动开始/停止执行 → 调试结果复盘”的可测闭环。

**Architecture:** 继续复用 `TestFlowNavigation` 作为主流程壳，但通过新增 `LapDebug*` 路由、页面、状态模型和分析器，把圈速调试能力隔离在专用链路内。`TestSessionViewModel` 只做最小接缝扩展，负责当前模式、配置、最近一次结果和 `LapDebugAnalyzer` 生命周期；圈速调试结果不复用普通测速结果页，也不写入正式历史。

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow, Android ViewModel, Koin, existing GPS domain models

---

## 文件结构

### 修改文件
1. `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt` - 为圈速调试模式新增路由、跳转和返回规则
2. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestSelectionScreen.kt` - 增加模式切换并输出 `template + carModel + mode`
3. `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` - 增加圈速模式最小状态、实时状态输出、分析器生命周期和最近一次结果驻留

> 默认**不得修改** `core/domain/src/main/java/com/blazepush/core/domain/model/TestModels.kt`。只有在确实存在无法在 `feature/test/model` 内封装的共享需求时，才允许追加最小共享模型；若发生，必须在实现说明里单列理由。

### 新建文件
1. `feature/test/src/main/java/com/blazepush/feature/test/model/TestMode.kt` - 定义普通模式与圈速调试模式
2. `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugConfig.kt` - 圈速调试配置模型
3. `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugState.kt` - 圈速调试执行中的实时状态模型
4. `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugResult.kt` - 圈速调试结果模型
5. `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugReferencePoint.kt` - 起终点参考点/命中区域模型
6. `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapDebugAnalyzer.kt` - 圈速调试分析器
7. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt` - 圈速调试配置页
8. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt` - 圈速调试执行页
9. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugResultScreen.kt` - 圈速调试结果页
10. `feature/test/src/main/java/com/blazepush/feature/test/ui/components/LapDebugMapPlaceholder.kt` - 第一阶段轨迹展示占位组件（如暂不接地图 SDK）
11. `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapDebugAnalyzerTest.kt` - 分析器单元测试
12. `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelLapDebugTest.kt` - 圈速模式状态流测试

### 参考文件
1. `docs/superpowers/specs/2026-03-24-lap-debug-mode-design.md` - 已确认的设计文档
2. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestExecutionScreen.kt` - 普通测速执行页现状参考，不直接复用为圈速执行页
3. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt` - 普通测速结果页现状参考，不直接复用为圈速结果页
4. `core/domain/src/main/java/com/blazepush/core/domain/model/TestModels.kt` - 现有测试模型边界参考

### 验证对象
1. `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapDebugAnalyzerTest.kt`
2. `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelLapDebugTest.kt`
3. `git diff -- feature/test/src/main/java/com/blazepush/feature/test`
4. `git diff -- core/domain/src/main/java/com/blazepush/core/domain/model/TestModels.kt`

---

## Task 1: 建立圈速调试模式的独立模型边界

**Files:**
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/TestMode.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugConfig.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugState.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugResult.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugReferencePoint.kt`
- Reference: `docs/superpowers/specs/2026-03-24-lap-debug-mode-design.md`
- Reference: `core/domain/src/main/java/com/blazepush/core/domain/model/TestModels.kt`

- [ ] **Step 1: 定义 `TestMode` 最小枚举**

要求：
- 只包含 `Normal` 与 `LapDebug`
- 放在 `feature/test/model`，不要加到 `core/domain`
- 明确这是 UI/流程层模式，而不是新的 `TestTemplate`

- [ ] **Step 2: 定义 `LapDebugReferencePoint` 与命中区域模型**

要求：
- 表达起终点参考点
- 包含最小字段：纬度、经度、半径米数
- 不引入地图 SDK 依赖

- [ ] **Step 3: 定义 `LapDebugConfig` 最小配置模型**

要求：
- 包含：参考点设置方式、命中阈值、是否显示轨迹、是否显示命中点
- 允许为后续扩展预留字段，但不要把策略矩阵一次做全
- 不依赖 `TestTemplate.shouldEnd(...)`

- [ ] **Step 4: 定义 `LapDebugState` 实时状态模型**

要求：
- 覆盖：是否已开始记录、轨迹点、当前位置、命中状态、候选圈摘要、采样数、配置摘要
- 只保留执行页渲染所需字段
- 不混入正式成绩字段

- [ ] **Step 5: 定义 `LapDebugResult` 结果模型**

要求：
- 覆盖：原始轨迹、命中点、候选圈/分段、配置快照、调试说明
- 明确这是“调试复盘结果”，不是正式测试成绩
- 不复用 `TestResult`

- [ ] **Step 6: 自查模型边界**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/model`
Expected: 只新增圈速调试专用模型；未修改现有 `TestTemplate`、`TestResult` 语义

---

## Task 2: 先用测试驱动实现 `LapDebugAnalyzer`

**Files:**
- Create: `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapDebugAnalyzerTest.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapDebugAnalyzer.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugConfig.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugState.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugResult.kt`
- Reference: `core/domain/src/main/java/com/blazepush/core/domain/model/GpsData.kt`
- Reference: `core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt`

- [ ] **Step 1: 写 `LapDebugAnalyzerTest` 的第一个失败用例**

用例至少覆盖：
- 未开始记录时喂入 GPS 数据，不应累积轨迹
- 开始记录后喂入 GPS 数据，会累积轨迹与采样数

- [ ] **Step 2: 运行该测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.LapDebugAnalyzerTest"`
Expected: FAIL，提示 `LapDebugAnalyzer` 或相关类型尚未实现

- [ ] **Step 3: 写 `LapDebugAnalyzer` 的最小实现使测试通过**

要求：
- 提供开始记录、追加 GPS 数据、停止记录接口
- 开始前不记录，开始后记录
- 不引入自动结束判定

- [ ] **Step 4: 增加命中检测失败用例**

用例至少覆盖：
- GPS 点进入参考点半径时，命中状态更新
- 结果中能看到命中点集合

- [ ] **Step 5: 运行测试，确认新增用例失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.LapDebugAnalyzerTest"`
Expected: FAIL，提示命中检测或结果字段不完整

- [ ] **Step 6: 扩展 `LapDebugAnalyzer` 命中检测实现**

要求：
- 用简单距离判定实现第一版命中检测
- 不引入复杂判圈算法
- 若没有有效圈，也能产出调试结果

- [ ] **Step 7: 再补一个“无有效圈也能生成结果”的测试**

要求：
- 停止记录后即使没有候选圈，也返回有效 `LapDebugResult`
- 结果包含原因说明或空候选圈列表

- [ ] **Step 8: 运行分析器测试确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.LapDebugAnalyzerTest"`
Expected: PASS

- [ ] **Step 9: 检查分析器复杂度与职责**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/usecase/LapDebugAnalyzer.kt`
Expected: 只实现轨迹累积、命中检测、结果生成；未混入导航或 UI 逻辑

---

## Task 3: 扩展 `TestSessionViewModel` 的最小接缝，不污染普通测速链路

**Files:**
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`
- Create: `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelLapDebugTest.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/TestMode.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugConfig.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugResult.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapDebugAnalyzer.kt`

- [ ] **Step 1: 写 `TestSessionViewModelLapDebugTest` 的第一个失败用例**

用例至少覆盖：
- 选择圈速模式后，ViewModel 能保存 `currentMode`
- 提交配置后，能保存 `lapDebugConfig`
- 开始圈速执行时不进入现有 `Preparing/Running(session)` 测速流程

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelLapDebugTest"`
Expected: FAIL，提示圈速模式相关状态和接口未实现

- [ ] **Step 3: 对 `TestSessionViewModel` 做最小扩展**

只新增：
- `currentMode`
- `lapDebugConfig`
- `lapDebugState` 对外只读 `StateFlow`
- `latestLapDebugResult`
- 启动/停止圈速调试会话的方法
- `LapDebugAnalyzer` 生命周期管理

不要做：
- 把 `LapDebug` 当成新的 `TestTemplate`
- 改写现有普通测速 trigger / end 逻辑
- 让圈速结果走 `CalculateResultUseCase`

- [ ] **Step 4: 增加“实时 GPS 数据桥接到 analyzer”的失败用例**

用例至少覆盖：
- 非 `LapDebug` 模式下，实时 GPS 数据不会驱动 `lapDebugState`
- `LapDebug` 模式且开始记录后，实时 GPS 数据会更新 `lapDebugState`
- 停止或取消后，不再继续向 `LapDebugAnalyzer` 喂数

- [ ] **Step 5: 运行测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelLapDebugTest"`
Expected: FAIL，提示实时状态桥接或清理逻辑未实现

- [ ] **Step 6: 实现 ViewModel 与 `LapDebugAnalyzer` 的实时数据 contract**

要求：
- `TestSessionViewModel` 对外暴露 `lapDebugState`
- 复用现有 GPS 数据源，但仅在 `currentMode == LapDebug` 且“记录中”时向 analyzer 转发
- 普通测速链路继续走原有状态流，不共享 `lapDebugState`
- 停止/取消时停止向 analyzer 喂数并清理圈速会话态

会话状态 contract：
- 选择圈速模式后，只更新 `currentMode`，不自动启动 analyzer
- 提交 `LapDebugConfig` 后，配置冻结并等待执行页触发开始记录
- 点击开始后，`lapDebugState` 进入记录态并开始接收实时 GPS 数据
- 点击停止后，停止桥接实时数据，生成 `latestLapDebugResult`，随后由导航进入结果页
- 执行页返回到配置页时，保留已提交的 `lapDebugConfig`，但不保留“记录中”状态
- `cancelTest()` 应清空圈速执行态与实时状态；`latestLapDebugResult` 只保留到本次结果页消费完成或下一次圈速会话开始前

- [ ] **Step 7: 增加“停止后保留最近一次结果”的失败用例**

用例至少覆盖：
- 手动停止圈速调试后，`latestLapDebugResult` 有值
- `cancelTest()` 仍能正确清理圈速态
- 停止后不写入正式历史链路

- [ ] **Step 8: 运行测试，确认失败**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelLapDebugTest"`
Expected: FAIL，提示停止/结果驻留/历史隔离链路未实现完整

- [ ] **Step 9: 补齐 ViewModel 的停止、结果驻留与清理实现**

要求：
- 手动停止触发 `LapDebugAnalyzer` 产出结果
- 结果驻留在 ViewModel 内存态
- 不保存到正式历史库

- [ ] **Step 10: 运行 ViewModel 测试确认通过**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelLapDebugTest"`
Expected: PASS

- [ ] **Step 11: 人工检查 `TestSessionViewModel` diff**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`
Expected: 变更集中在模式接缝、`lapDebugState` 输出和圈速专用状态；现有普通测速逻辑主体保持不变

---

## Task 4: 接入独立导航与圈速配置页

**Files:**
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestSelectionScreen.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/TestMode.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugConfig.kt`

- [ ] **Step 1: 更新 `TestSelectionScreen` 回调签名**

要求：
- 从 `(TestTemplate, String)` 改为输出 `template + carModel + mode`
- 增加普通模式 / 圈速调试模式切换 UI
- 保持现有测试类型卡片与车型输入结构尽量不动

- [ ] **Step 2: 在 `TestFlowNavigation` 中新增圈速调试路由**

至少新增：
- `LapDebugConfig`
- `LapDebugExecution`
- `LapDebugResult`

并保留现有：
- `Connection`
- `Selection`
- `Execution`
- `Result`
- `History`

- [ ] **Step 3: 新建 `LapDebugConfigScreen` 最小可用实现**

要求：
- 能编辑最小必需配置：参考点设置方式、阈值、显示轨迹、显示命中点
- 点击开始时输出 `LapDebugConfig`
- 不在这一步加入复杂策略矩阵

- [ ] **Step 4: 实现返回规则**

要求：
- `LapDebugConfig → Selection`
- `LapDebugExecution → LapDebugConfig`
- `LapDebugResult → Selection`
- 普通测速链路保持原状
- 圈速调试链路不进入 `History`

- [ ] **Step 5: 人工检查导航 contract**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestSelectionScreen.kt feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt`
Expected: 普通测速链路与圈速调试链路分流清晰；第一阶段采用独立配置页而非内嵌配置区

---

## Task 5: 实现独立的圈速调试执行页与结果页

**Files:**
- Create: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugResultScreen.kt`
- Create: `feature/test/src/main/java/com/blazepush/feature/test/ui/components/LapDebugMapPlaceholder.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestExecutionScreen.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugState.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/model/LapDebugResult.kt`

- [ ] **Step 1: 新建 `LapDebugExecutionScreen` 的最小布局**

第一版至少展示：
- 开始按钮
- 停止按钮
- 当前状态（至少能区分：未开始、记录中、已命中、已形成候选圈）
- 轨迹占位区
- 当前定位点
- 起终点线 / 命中区域
- 当前候选圈或候选分段状态
- GPS 状态（卫星数、频率、HDOP）与采样数
- 当前速度等辅助数据

页面 contract：
- 初始进入时为“未开始记录”态
- 未开始时开始按钮可用、停止按钮禁用
- 记录中时停止按钮可用、开始按钮禁用
- 记录中页面可观察到 `lapDebugState` 的实时变化
- 停止后不在本页停留，直接走结果页跳转

- [ ] **Step 2: 接入 ViewModel 的开始/停止动作**

要求：
- 点击开始后进入记录态
- 点击停止后跳到 `LapDebugResult`
- 不复用现有 `TestExecutionScreen` 的测速进度条与语音播报逻辑

- [ ] **Step 3: 新建 `LapDebugMapPlaceholder`**

要求：
- 第一阶段可以先用 Compose Canvas 或文本占位方式表现轨迹/命中点
- 不引入新的地图 SDK
- 只要能表达“有轨迹、有命中点”即可

- [ ] **Step 4: 新建 `LapDebugResultScreen` 最小布局**

第一版至少展示：
- 轨迹占位区
- 命中点/候选圈摘要
- 配置快照
- 调试说明
- 返回按钮

页面 contract：
- 优先读取 `latestLapDebugResult`
- 若结果为空，展示空态并只允许返回 `Selection`
- 不得回退到现有 `TestResultScreen`

- [ ] **Step 5: 接入 `latestLapDebugResult` 数据来源**

要求：
- 结果页直接读取当前会话的内存态结果
- 结果页返回到 `Selection`
- 不要求接历史列表

- [ ] **Step 6: 人工检查页面隔离性**

Run: `git diff -- feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugResultScreen.kt feature/test/src/main/java/com/blazepush/feature/test/ui/components/LapDebugMapPlaceholder.kt`
Expected: 新增页面围绕 `LapDebug*` 命名集中；未把调试 UI 硬塞进现有普通测速页面

---

## Task 6: 完成前验证与收尾

**Files:**
- Verify: `feature/test/src/main/java/com/blazepush/feature/test/**`
- Verify: `docs/superpowers/specs/2026-03-24-lap-debug-mode-design.md`

- [ ] **Step 1: 运行圈速分析器测试**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.LapDebugAnalyzerTest"`
Expected: PASS

- [ ] **Step 2: 运行圈速 ViewModel 测试**

Run: `./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelLapDebugTest"`
Expected: PASS

- [ ] **Step 3: 运行 feature/test 单测（如受现有基线问题影响，记录清楚）**

Run: `./gradlew :feature:test:testDebugUnitTest`
Expected: 新增测试通过；若存在仓库已有失败，需明确区分是否为本次引入

- [ ] **Step 4: 做端到端人工验证清单**

至少检查：
- 普通模式仍进入原 `Execution → Result → History` 链路
- 圈速模式进入 `LapDebugConfig → LapDebugExecution → LapDebugResult`
- `LapDebugConfig → Selection`
- `LapDebugExecution → LapDebugConfig`
- `LapDebugResult → Selection`
- 手动开始后采样数/状态有更新
- 手动停止后即使没有有效圈也能看到结果页
- 完成一次圈速调试后，正式历史中没有新增记录

- [ ] **Step 5: 检查是否污染现有测速链路**

Run: `git diff --stat`
Expected: 变更主要集中在 `feature/test` 的新增 `LapDebug*` 文件和最小接缝修改；未大面积改动现有测速页面与 domain 核心模型

- [ ] **Step 6: 做历史隔离与排除项检查**

检查点：
- 未调用正式结果保存链路保存 `LapDebugResult`
- 未新增把 `LapDebugResult` 持久化到正式历史的通路
- 历史相关模块若有修改，必须能说明必要性，否则视为偏离 spec

- [ ] **Step 7: 对照 spec 做实现完整性审查**

检查点：
- 是否采用独立配置页
- 是否采用独立执行页与结果页
- 是否手动开始/手动停止
- 是否不复用 `TestResultScreen` / `CalculateResultUseCase` / 正式历史链路
- 是否普通测速链路仍然可用

- [ ] **Step 8: 准备交付说明**

交付说明至少包含：
- 新增了哪些 `LapDebug*` 文件
- 对现有 `TestFlowNavigation` / `TestSelectionScreen` / `TestSessionViewModel` 做了哪些最小接缝修改
- 哪些行为刻意未做（历史、视频、复杂算法）
- 新增测试覆盖了哪些圈速调试关键行为
- 若仍有基线测试失败，明确指出与本次无关的失败项
- 若确实修改了 `core/domain/TestModels.kt`，单列原因
