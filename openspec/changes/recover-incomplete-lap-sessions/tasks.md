## 1. 数据访问与恢复计算

- [x] 1.1 在 `TelemetrySessionDao` 增加按进程启动 cutoff 查询未闭环 LAP_SESSION 的接口，并用 DAO/Repository 测试覆盖已闭环跳过和新 session 排除。
- [x] 1.2 在 `TelemetryRepository` 提取正常结束与恢复共用的 crossing/binary 汇总计算，保持 accepted StartFinish 真壁钟配对语义不变。
- [x] 1.3 实现逐条幂等的 `recoverIncompleteLapSessions`，用 crossing、video segment、binary 候选恢复 endTs，并返回结构化成功/失败摘要。

## 2. 启动接线与诊断

- [x] 2.1 在 `BlazePushApplication` 捕获进程启动 cutoff，使用 Application scope 异步触发恢复；单条或整体失败只写 FileLogger，不阻塞启动。
- [x] 2.2 确认恢复路径不依赖/调用 `LapUploadTrigger`，不创建 pending upload，且日志不输出轨迹、Android ID 或完整文件路径。

## 3. 自动化验证

- [x] 3.1 新增现场形态测试：8 条 accepted StartFinish + 5 个视频段恢复为 7 圈，best lap 正确且视频段不变。
- [x] 3.2 新增幂等与边界测试：重复恢复跳过、缺失/截断 binary、少于两次起终点、未来时间候选、单条失败继续。
- [x] 3.3 运行 `:core:data:testDebugUnitTest`、相关 app 编译任务、`openspec validate recover-incomplete-lap-sessions --type change --strict` 与 `git diff --check`。

## 4. 现场救援交付边界

- [x] 4.1 用诊断包数据库只读演算确认目标 session 预期恢复为 7 圈、最佳 2:25.508，5 个视频段元数据不变。
- [x] 4.2 记录现场覆盖升级前置条件：相同包名/签名、不得卸载或清数据；视频物理文件仍需原手机验证。

## 5. LAPS 页主动恢复

- [x] 5.1 增加进程级恢复 coordinator，共享固定启动 cutoff，并用互斥锁串行化冷启动与页面触发。
- [x] 5.2 同时观察 Records 外层 Pager 实际停稳与 LAPS 子页选中态，每次实际进入时触发恢复检查；预组合不得误触发，失败只记录日志。
- [x] 5.3 增加 coordinator 并发幂等与固定 cutoff 测试，并重新运行 data 测试、app 编译、OpenSpec strict 校验和 diff check。
