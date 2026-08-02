## ADDED Requirements

### Requirement: 冷启动识别未闭环圈速 session

系统 SHALL 在进程启动后扫描启动时刻之前创建、`sessionType = LAP_SESSION` 且 `endTs <= startTs` 的 session，并且 MUST NOT 处理已闭环或本次进程中新建的 session。

#### Scenario: 扫描异常退出遗留 session
- **WHEN** 冷启动时数据库存在 `endTs == startTs` 的旧 LAP_SESSION
- **THEN** 系统将该 session 纳入恢复候选

#### Scenario: 跳过已正常结束 session
- **WHEN** session 的 `endTs > startTs`
- **THEN** 系统不得修改其 `endTs/lapCount/bestLapMs/topSpeedKmh`

#### Scenario: 不误收尾本进程新 session
- **WHEN** 恢复任务执行期间用户新建一个 startTs 不早于进程启动时刻的 LAP_SESSION
- **THEN** 系统不得将该 session 纳入本轮恢复

### Requirement: 每次进入圈速记录页主动复查

系统 SHALL 在用户每次实际进入 Records 的 LAPS 子页时触发一次未闭环 session 恢复检查。该检查 MUST 与冷启动恢复共享同一个进程启动 cutoff，并且并发触发 MUST 串行执行。

#### Scenario: 切换到 LAPS 子页
- **WHEN** 用户从 PERFORMANCE 切换到 LAPS 子页
- **THEN** 系统触发一次恢复检查，恢复成功后历史列表通过既有 Room Flow 展示写回结果

#### Scenario: 离开后再次进入 LAPS
- **WHEN** 用户离开 LAPS 子页后再次进入
- **THEN** 系统再次触发恢复检查，已闭环 session 因幂等条件保持不变

#### Scenario: Records 页被预组合但 LAPS 未进入
- **WHEN** 外层 Pager 预组合 Records 页面且当前子页不是 LAPS
- **THEN** 系统不得仅因 Records 外层组合而触发 LAPS 恢复检查

#### Scenario: 启动恢复与 LAPS 检查并发
- **WHEN** 冷启动恢复尚未完成时用户进入 LAPS 子页
- **THEN** 两次检查由进程级互斥串行执行，且同一 session 最多成功写回一次

#### Scenario: tab 检查不收尾本进程计时
- **WHEN** 本进程已创建一个尚未结束的 LAP_SESSION 后用户进入 LAPS 子页
- **THEN** 检查继续使用进程启动 cutoff，并且不得处理该 session

### Requirement: 从持久化过线证据恢复圈速汇总

系统 MUST 按真壁钟升序配对 accepted StartFinish crossing，相邻两次有效过线形成一圈，并写回 `lapCount` 和 `bestLapMs`；恢复不得依赖已丢失的 ViewModel 或 active writer。

#### Scenario: 现场八次起终点恢复七圈
- **WHEN** 未闭环 session 含 8 条真壁钟非空且递增的 accepted StartFinish crossing
- **THEN** 系统写回 `lapCount = 7`，并以 7 个相邻差值的最小值写回 `bestLapMs`

#### Scenario: 忽略无效和非起终点事件
- **WHEN** session 同时包含 rejected crossing、Sector crossing 和 accepted StartFinish crossing
- **THEN** 圈数与最佳圈只由 accepted StartFinish crossing 配对产生

#### Scenario: 不足两次起终点
- **WHEN** 未闭环 session 少于 2 条真壁钟有效的 accepted StartFinish crossing
- **THEN** 系统闭环保存该 session 且写回 `lapCount = 0`、`bestLapMs = null`

### Requirement: 使用可靠本地证据确定恢复结束时间

系统 SHALL 从 crossing、视频段和 binary 遥测的有效时间候选中选择最晚值作为 `endTs`；候选 MUST 位于 session start 与恢复时刻之间。无有效候选时系统 MUST 使用 `startTs + 1`，保证恢复后 `endTs > startTs`。

#### Scenario: 视频晚于最后过线
- **WHEN** 最后一段视频结束时间晚于最后 crossing 和 binary 末点
- **THEN** 恢复后的 `endTs` 等于该视频结束时间

#### Scenario: 非法未来时间被拒绝
- **WHEN** 某个持久化时间候选晚于恢复时刻
- **THEN** 系统忽略该候选并使用其他有效证据

#### Scenario: 没有时间证据
- **WHEN** session 没有 crossing、视频段或可读 binary 样本
- **THEN** 系统写回 `endTs = startTs + 1`

### Requirement: 恢复必须无损且幂等

恢复流程 MUST 保留 crossing、binary、video_segments 和视频文件，不得触发 Livetiming 上传；同一 session 成功恢复后，后续启动 MUST 跳过且不得改变结果。

#### Scenario: 保留既有视频段
- **WHEN** 未闭环 session 已关联 5 个视频段
- **THEN** 恢复后 5 个视频段及其文件路径保持不变

#### Scenario: 不重复实时上报
- **WHEN** 对已在 Livetiming 实时上报过圈速的 session 执行本地恢复
- **THEN** 系统不得调用圈速上传器或创建 pending upload

#### Scenario: 重复启动幂等
- **WHEN** 已恢复 session 在下次冷启动被再次检查
- **THEN** 系统因 `endTs > startTs` 跳过该 session，汇总值保持不变

### Requirement: 恢复失败不得阻塞应用启动

系统 MUST 隔离单条 session 的读取或写入失败，记录非敏感诊断摘要并继续处理其他候选；恢复任务整体失败不得阻止主界面和既有启动任务运行。

#### Scenario: 单条 binary 不可读
- **WHEN** 某条候选的 binary 文件缺失或截断，但 crossing 可读
- **THEN** 系统仍恢复圈数与最佳圈，`topSpeedKmh` 可为 null，并继续后续候选

#### Scenario: 单条数据库写入失败
- **WHEN** 一条候选写回 summary 时抛出异常
- **THEN** 系统记录失败并继续恢复其他候选，应用启动不崩溃
