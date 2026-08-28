# video-finalize-error-salvage

Finalize ERROR 时的视频数据救援与 camera 死因诊断可观测。

## ADDED Requirements

### Requirement: Finalize ERROR 且文件有数据 MUST 入库救援

`CameraRecordingEngine` 处理 `VideoRecordEvent.Finalize` ERROR 分支时,若 `_capturedSessionId` 非空且输出文件存在且 size > 0,SHALL 调用 `attachVideoToSession`(与 OK 分支同参:path/wallClock);救援动作 SHALL 落 FileLogger(标注 ERROR 降级救援 + error code);并 SHALL 清空 `_capturedWallClock/_capturedSessionId`(对齐 OK 分支)。

#### Scenario: SOURCE_INACTIVE 大文件救援(路测回归锁)
- **GIVEN** 录制中 camera source 失活,Finalize ERROR code=4,文件 1.15GB 完整,session 活跃
- **WHEN** ERROR 分支处理
- **THEN** attachVideoToSession 被调用(2026-06-03 实景下该视频将出现在 session 详情而非黑洞);RecordingState 进入 Error;pendingOnFinalized 照常 invoke

#### Scenario: ERROR 且文件零字节不救
- **GIVEN** Finalize ERROR 且输出文件不存在或 size == 0
- **WHEN** ERROR 分支处理
- **THEN** MUST NOT 调用 attachVideoToSession(零数据入库无意义);其余清理路径照常

#### Scenario: ERROR 且无 session 沿用孤儿删除
- **GIVEN** Finalize ERROR、sessionId == null、文件 size > 0
- **WHEN** ERROR 分支处理
- **THEN** 沿用 OK 分支白名单删除语义(仅 /video/ 目录内删除),MUST NOT attach

### Requirement: camera 生命周期事件 MUST 落盘可观测

bind 成功后 SHALL observe `cameraInfo.cameraState`:CLOSING/CLOSED 及任何 `state.error` SHALL 以 E 级落 FileLogger(含 error code),常规转移 D 级;rebind MUST NOT 造成重复 observer(observe 前 removeObservers)。

#### Scenario: 系统掐 camera 时死因可见
- **GIVEN** 录制中系统层关闭 camera(热管理/他进程抢占)
- **WHEN** cameraState 转移 OPEN → CLOSING(error code 伴随)
- **THEN** FileLogger 含该转移与 code——下次路测 SOURCE_INACTIVE 根因可从日志直接读出

#### Scenario: 反复 rebind 不累积 observer
- **GIVEN** 用户在设置中连续调整曝光触发 5 次 rebind
- **WHEN** 每次 bind 注册 cameraState observer
- **THEN** 同一状态转移只产生一组日志(无 5 倍重复)——此断言失败即 observer 泄漏回归
