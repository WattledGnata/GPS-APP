# Tasks: fix-video-finalize-error-salvage

## 1. 锚点自检

- [x] 1.1 grep:`grep -n "hasError()" feature/test/src/main/java/com/blazepush/feature/test/recording/CameraRecordingEngine.kt`(Finalize ERROR 分支锚点,~line 521);`grep -n "bindToLifecycle" 同文件`(camera 获取点);`grep '^### Decision ' openspec/changes/fix-video-finalize-error-salvage/design.md`(3 决策)。

## 2. 实现(CameraRecordingEngine.kt)

- [x] 2.1 ERROR 分支(521-529)重构:读 path/size/wallClock/sessionId;`sessionId!=null && file.exists() && length()>0` → engineScope.launch attachVideoToSession(runCatching + "ERROR 降级救援" 日志,Decision 1);sessionId==null && size>0 → 白名单删除(OK 分支语义);清 _capturedWallClock/_capturedSessionId;RecordingState.Error 与 pendingOnFinalized invoke 保持不变。
- [x] 2.2 bind 内 camera 获取后:`cameraInfo.cameraState` removeObservers(lifecycleOwner) 后 observe(Decision 2);error 用 FileLogger.e(含 code),常规转移 FileLogger.d。
- [x] 2.3 编译 + `:feature:test:testDebugUnitTest` 既有用例全绿。

## 3. 验证

- [x] 3.1 spec 场景由真机路测验证(ERROR 难以单测模拟——CameraX VideoRecordEvent 不可构造,Recorder final 类;透明声明:本 round 验证路径=路测攒批,日志锚点为 gate)。CC 自审记录:#14/#16 空命中。

## 10. Follow-up backlog

- `multi-video-per-session`:单 videoFilePath 装不下多段录像(救活段会被后续 OK 段覆盖)——Room schema 加 session_videos 表 + 可播性标记 + 回放多段对齐;deferred memo `docs/design/multi-video-per-session-deferred.md`(本 round 同步沉淀)。
- SOURCE_INACTIVE 根因修复:等 cameraState 诊断日志的下一轮路测数据。
