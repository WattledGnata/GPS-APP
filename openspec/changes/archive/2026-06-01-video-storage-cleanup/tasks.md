## 1. 锚点校验（apply 启动前 grep）

- [x] 1.1 `grep -nE "fun attachVideoToSession|fun deleteSession|videoFilePath|allowedPaths" core/data/.../TelemetryRepository.kt`——确认 attach@293 / deleteSession@251 / 白名单@267。
- [x] 1.2 `grep -nE "updateVideoMetadata|@Query.*videoFilePath" core/data/.../dao/TelemetrySessionDao.kt`——确认 updateVideoMetadata query 形态，clearVideo 仿它。
- [x] 1.3 `grep -nE "hasVideo|telemetryRepository|LaunchedEffect\(sessionId\)" feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`——确认 hasVideo@212 + repo 注入@77 + session 加载@90。

## 2. 仓库层（core/data · helper + 删旧 + 手动删）

- [x] 2.1 `TelemetryRepository` 抽 `private fun deleteVideoFileIfPresent(videoPath: String?, tag: String)`：白名单（`/video/` `/telemetry/`）+ exists + delete + 日志（复用 deleteSession:264-280 逻辑）；`deleteSession` 改调此 helper。
- [x] 2.2 `attachVideoToSession` 覆盖前：`queryBySessionId` 取旧 videoFilePath，若非空且 ≠ 新路径 → `deleteVideoFileIfPresent(old)`，再 updateVideoMetadata（spec 重录 Requirement）。
- [x] 2.3 新增 `suspend fun deleteSessionVideo(sessionId)`：查 entity → `deleteVideoFileIfPresent(entity.videoFilePath)` → `sessionDao.clearVideo(sessionId)`（spec 手动删 Requirement）。

## 3. DAO（core/data）

- [x] 3.1 `TelemetrySessionDao` 加 `@Query("UPDATE telemetry_sessions SET videoFilePath = NULL, videoStartedAtWallClock = NULL WHERE sessionId = :sessionId") suspend fun clearVideo(sessionId: String)`。同步所有 fake DaO 补 stub（grep `interface TelemetrySessionDao` impl，v3 盲点 #14）。

## 4. 录制引擎（feature/test · 无 session 删）

- [x] 4.1 `CameraRecordingEngine.handleVideoRecordEvent` Finalize OK 分支：`sessionId == null` 时删 outputFile（`deleteVideoFileIfPresent` 等价的白名单删，引擎内可直接 File.delete + 白名单 + FileLogger）+ 不写库（spec 无 session Requirement）。埋 `FileLogger.d(TAG, "Finalize: 无 session 孤儿，删 <path>")`。

## 5. 成绩页 UI（feature/test · 删视频入口）

- [x] 5.1 `LapSessionDetailScreen` 加 `var refreshTick`，session 加载 LaunchedEffect key 改 `sessionId, refreshTick`。
- [x] 5.2 hasVideo 时加"删除视频"入口（按钮/菜单，Track Tech V2 视觉 + maxLines=1+Ellipsis）：点击 → 确认 → coroutine `telemetryRepository.deleteSessionVideo(sessionId)` → bump refreshTick。埋 FileLogger。
- [x] 5.3 二次确认（AlertDialog "删除视频?保留圈速成绩"）避免误删。

## 6. 单元测试

- [x] 6.1 `deleteVideoFileIfPresent` 白名单单测：路径含 `/video/` → 删；含 `/telemetry/` → 删；都不含 → skip；不存在 → skip 不抛（用 tmp 文件 + 路径构造）。
- [x] 6.2 `deleteSessionVideo` 经 fake DAO 单测：clearVideo 被调 + 文件删除调用（fake DAO 验证 video 字段置空，session 行不删）。
- [x] 6.3 attach 删旧逻辑：fake DAO 注旧 videoFilePath，attach 新路径 → 验旧文件删除调用 + updateVideoMetadata 写新（tmp 文件验存在性）。
- [x] 6.4 fake TelemetrySessionDao 补 `clearVideo` stub（盲点 #14）。

## 7. 编译 + road-test-first gate

- [x] 7.1 `:core:data:compileDebugKotlin` + `:feature:test:compileDebugKotlin` + 相关 `testDebugUnitTest --offline` 全绿。
- [x] 7.2 `:app:assembleDebug --offline` 构建 apk。列 FileLogger 锚点。
- [ ] 7.3 【真机·攒批·user 路测】① 同 session 重录→旧文件不留 ② 成绩页删视频→成绩仍在、回放入口消失 ③ 无 session 录制完成→文件不留。

## 10. Follow-up backlog

- [ ] 10.1 存量孤儿一次性 reconcile（A 不扫地，存量留待分片阶段做安全 reconcile）。见 `docs/design/video-segmentation-data-model-deferred.md`。
- [ ] 10.2 分片落地后 A 钩子平移成按段删（memo §7）。
