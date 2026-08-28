# Design: fix-video-finalize-error-salvage

## Context

`CameraRecordingEngine.handleVideoRecordEvent`(feature/test/.../recording/CameraRecordingEngine.kt:490-578)处理 VideoRecordEvent:
- Start(491-511):记 `_capturedWallClock`(System.currentTimeMillis,遥测同时钟域)+ `_capturedSessionId`。
- Finalize OK(530-571):读文件 path/size → sessionId 非空 `attachVideoToSession`(537-551)/ 空则白名单删孤儿(552-560)→ 清脏字段(562-564)→ Idle → invoke pendingOnFinalized。
- **Finalize ERROR(521-529)**:只记日志 + RecordingState.Error + invoke callback——不读文件、不 attach、不清 `_capturedWallClock/_capturedSessionId`。

2026-06-03 实景:code=4(SOURCE_INACTIVE)的 1.15GB 文件字节完整但 DB 无记录。CameraX `Recorder` 对多数 Finalize 错误仍写完已捕获数据(文档:"the output file may still be usable")。

camera 获取点:`bind()` 内 `cameraProvider.bindToLifecycle(...)` 返回 `Camera` 实例(现仅用于 focus/exposure interop,未 observe cameraState)。

## Goals / Non-Goals

**Goals:**
- ERROR 结束但文件有数据 → 入库可见(救援)。
- camera 生命周期事件(含系统层 error code)落 FileLogger——SOURCE_INACTIVE 死因下次路测可定位。

**Non-Goals:**
- SOURCE_INACTIVE 根因修复(证据不足);多段视频 schema(deferred memo);文件可播性校验(moov 解析超 scope,播放器自然反馈)。

## Decisions

### Decision 1: ERROR 分支与 OK 分支共享 attach 路径,以"文件有数据"为救援条件

条件:`sessionId != null && outputFile.exists() && outputFile.length() > 0` → `attachVideoToSession`(日志前缀"ERROR 降级救援");无 session → 沿用 OK 分支白名单删除;清脏字段对齐 OK 分支。

Alternatives:
- (a) 维持现状(不 attach):1.15GB 黑洞实景,拒绝。
- (b) 按 error code 白名单救(只救 SOURCE_INACTIVE 等"已知文件可用"码):CameraX 各 code 的文件可用性无权威矩阵,白名单漏救;size>0 已是充分必要的最低门槛(0 字节文件 attach 无意义),拒绝。
- (c) 文件级校验(moov box 解析)后才救:引入 MP4 解析依赖,过度工程;损坏文件入库的代价只是播放器报错(用户可感知可删除),拒绝。
- (d) size>0 即救(选):实现 4 行,救援收益最大化,损坏风险由播放路径自然暴露。

### Decision 2: cameraState observer 挂在 bindToLifecycle 后,observe(lifecycleOwner)

`camera.cameraInfo.cameraState.observe(lifecycleOwner) { ... }`:type(PENDING_OPEN/OPENING/OPEN/CLOSING/CLOSED)+ `state.error?.code` 全落 FileLogger(error 用 E 级,常规转移 D 级)。LiveData observe 随 lifecycleOwner(NavBackStackEntry)自动解注册,无泄漏;rebind 时重复 observe 由"先 removeObservers 再 observe"防重(或 observe 前 remove,确定性)。

CameraState error code 是根因直接证据:`ERROR_CAMERA_IN_USE`(他进程抢)/`ERROR_CAMERA_FATAL_ERROR`(驱动/服务崩)/`ERROR_CAMERA_DISABLED`(系统策略禁用)/`ERROR_DO_NOT_DISTURB_MODE_ENABLED` 等——vivo 热管理或后台策略掐 camera 必经 CLOSING/CLOSED + code。

Alternative(Camera2 interop availability callback):更底层但 CameraX 已封装等价信息,引入 interop 面无增益。拒绝。

### Decision 3: 单 videoFilePath 覆盖行为本 round 不动

救活的 ERROR 段(先 attach)会被同 session 后续 OK 段覆盖(`attachVideoToSession` 是 UPDATE)。任何"保留更长/更早"heuristic 都有反例(用户故意重录 vs 意外分段),正确解是多段视频表(Room schema 改,强制 medium 流程)——deferred memo `docs/design/multi-video-per-session-deferred.md` 展开,本 round 仅在 attach 日志里可见覆盖动作。

## Risks / Trade-offs

- **损坏文件入库**:moov 未写完的文件出现在 session 详情,播放报错——可感知优于黑洞;memo 的多段表方案附带"可播性标记"展开。
- **observer 重复注册**:rebind 路径(配置变更)反复 observe 同一 LiveData——实现以 removeObservers(lifecycleOwner) 前置,tasks 锁。
- **ERROR 分支 attach 是 engineScope.launch 异步**:与 OK 分支同模式(runCatching + 日志),写库失败有 E 级日志,不阻塞 Finalize 回调链(pendingOnFinalized 仍同步 invoke)。
