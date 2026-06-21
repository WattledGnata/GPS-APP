## 1. 诊断数据打包（DiagnosticPackager）

- [x] 1.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/diagnostic/DiagnosticPackager.kt`：`fun pack(filesDir: File, databaseDir: File, outDir: File): File`。打包 `filesDir/telemetry/*.bin` + `databaseDir/race_chrono_database`(+存在的 `-wal`/`-shm`) + `filesDir/debug_log.txt`(+存在的 `.txt.1`) 成 `outDir/diag_<ts>.zip`；硬排除 `filesDir/video/`；缺失文件 `continue` 跳过。done：返回 zip File（实现 design Decision 2/3/4）
- [x] 1.2 新增测试 `feature/test/src/test/.../diagnostic/DiagnosticPackagerTest.kt`：用 `createTempDir()` 造 fake filesDir（含 telemetry/*.bin、video/*.mp4、db 三件套、debug_log.txt[.1]），断言 zip 条目含 bin+db+log、**不含 video/**、缺 `.txt.1` 仍成功。对应 spec『诊断数据全量打包』3 scenarios。（用临时目录，避开 gradle test working-dir 陷阱）

## 2. 元数据采集

- [x] 2.1 新建 `diagnostic/DiagnosticMetadata.kt`：`data class DiagnosticMetadata(deviceModel, androidId, versionName, versionCode, capturedAtMs, ticket: String?)` + `fun collect(context, ticket): DiagnosticMetadata`（`Build.MANUFACTURER+" "+MODEL`、`Settings.Secure.ANDROID_ID`、`BuildConfig.VERSION_NAME/CODE`、`System.currentTimeMillis()`）。done：data class + collect（实现 design Decision 7）
- [x] 2.2 新增测试 `DiagnosticMetadataTest.kt`：mock/Robolectric context，断言必填字段非空、ticket 透传。对应 spec『上传元数据随包上送』。

## 3. 上传链路（core/network）

- [x] 3.1 新建 `core/network/src/main/java/com/blazepush/core/network/DiagnosticLogUploader.kt`：`suspend fun upload(zip: File, meta: DiagnosticMetadata, onProgress: (Float)->Unit): Result<String>`。OkHttp `MultipartBody`：`file`(zip, application/zip) + meta form 字段；进度用自定义 `RequestBody` 包装；复用 `LivetimingClient` 的 OkHttp 实例 + baseUrl + Bearer token；解析响应 JSON `logId`。done：suspend 返回 `Result<logId>`（实现 design Decision 5/9）
- [x] 3.2 实施期简化：端点路径固定无需注入 → 改用代码常量 `DiagnosticLogUploader.LOGS_PATH = "/api/v1/logs"`（companion），baseUrl/token 复用现有 `BuildConfig.LIVETIMING_BASE_URL/TOKEN`，**无需改 build.gradle**。属 spec drift 而非 design 修订（design Decision 5 本就允许常量）。
- [x] 3.3 新增测试 `core/network/src/test/.../DiagnosticLogUploaderTest.kt`：MockWebServer 断言请求是 multipart 且含 `file` + 全部必填 meta 字段、200+`{logId}` 解析成功、400/IO 异常 → `Result.failure`。对应 spec『服务端 API 契约』客户端侧 + 『上传状态』失败路径。

## 4. 上传编排（状态机）

- [x] 4.1 新建 `diagnostic/DiagnosticUploadOrchestrator.kt`：`sealed class DiagnosticUploadState { Idle / Packing / Uploading(progress) / Success(logId) / Failed(reason) }` + `StateFlow<DiagnosticUploadState>` + `suspend fun start(ticket)`，在 `Dispatchers.IO` 串起 packager→uploader，失败归 `Failed(可区分原因：打包失败/网络失败/服务端<码>)`。done：orchestrator + state（实现 design Decision 8，复用 `livetiming/LapUploadOrchestrator` 模式）
- [x] 4.2 新增测试 `DiagnosticUploadOrchestratorTest.kt`：fake packager + fake uploader，`runTest` 断言状态流 Idle→Packing→Uploading→Success(logId)；uploader 抛错 → Failed。对应 spec『上传状态与结果反馈』3 scenarios。

## 5. 暗门入口（SettingsScreen 连点版本号）

- [ ] 5.1 新建 `diagnostic/VersionTapCounter.kt`（纯逻辑，可单测）：`fun tap(nowMs): Boolean`（3 秒窗口累计，达 7 返回 true 并重置，超时清零）。改 `feature/test/src/main/.../ui/settings/SettingsScreen.kt`：展示 `versionName (versionCode)`，版本号 `clickable` 调 counter，达阈值回调 `onOpenDiagnostics`。done：连点触发面板（实现 design Decision 1）
- [x] 5.2 新增测试 `VersionTapCounterTest.kt`：7 次触发 / 6 次不触发 / 第 4 次后超时再 3 次不累加。对应 spec『诊断上传暗门入口』3 scenarios（含 2 反例）。（注：5.1 的 `VersionTapCounter.kt` 逻辑已建+单测绿；5.1 剩 SettingsScreen 接线待 task 6 UI 轮）

## 6. 诊断面板 UI

- [ ] 6.1 新建诊断面板 Composable（`ui/diagnostic/DiagnosticUploadSheet.kt`）：工单号输入框 + 上传按钮 + 隐私确认 `AlertDialog`（文案"将上传行驶轨迹与诊断数据用于排查"）+ 进度条 + 成功展示 logId + 失败展示原因。订阅 `DiagnosticUploadOrchestrator.state`。隐私分支 MUST 用 if/else 渲染，禁 early return（依 [[feedback_compose_no_early_return_in_scope]]）。done：面板接通 orchestrator + 隐私确认 gate（实现 spec『隐私确认』『上传状态反馈』）
- [ ] 6.2 真机视觉验证（无自动化测试）：暗门面板布局/进度/logId 展示。【真机 gate，UI 视觉项】

## 7. DI 接线

- [ ] 7.1 改 `feature/test/src/main/.../di/AppModule.kt`：注册 `DiagnosticPackager` / `DiagnosticLogUploader` / `DiagnosticUploadOrchestrator`。若有 `DomainModuleKoinTest` 覆盖 DI 图，同步补断言。done：Koin 注册 + DI 测试绿

## 8. 服务端 API 契约交付（跨 repo）

- [ ] 8.1 新建 `docs/api/diagnostic-log-upload-contract.md`：`POST /api/v1/logs` 完整契约（multipart 字段表 / 200 响应 / 400·401·413 错误码 / 鉴权复用 livetiming token），与 design Decision 9 一致，供 livetiming-server Go repo 实现。done：契约文档存在且自洽（spec『服务端 API 契约』的实现指引）

## 9. 编译与验证

- [ ] 9.1 `gradle :feature:test:testDebugUnitTest :core:network:testDebugUnitTest --offline` 全绿（gradle 8.9）。done：单测通过
- [ ] 9.2 `assembleRelease` 出 release apk，真机验证：连点版本号 7 次出面板 + 隐私确认 + 上传（服务端就绪或 MockWebServer 桩）。【真机 gate，需 user 授权 install】
- [ ] 9.3 **【高风险·需用户单独确认】** push feature 分支到远端（远端 kt-format hook 逐条验证 push 历史）。MUST 当次显式确认，不得复用既往授权。

## 10. Follow-up backlog

- zip 大小硬上限 / "仅最近 N session" 选项（design Open Question，本 round 不做）
- 工单号对接工单系统下拉（design Open Question）
- **服务端 Go 实现**：livetiming-server repo 按 §8.1 契约实现 `POST /api/v1/logs`（本 round 只交付契约，Go 代码不在本 repo scope）
