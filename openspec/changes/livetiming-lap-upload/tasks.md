# Tasks — livetiming-lap-upload

> 实施前必读：proposal.md（Why + 协议兼容性 + Impact）、design.md（6 Decisions，命门=Decision 2 clientLapId）、specs/（2 capability：livetiming-lap-upload 4 requirements + first-launch-driver-prompt 1 requirement，含反例锁）。
> 契约权威：`livetiming-server/docs/api/client-integration.md`。

## 1. 锚点 verify（apply 启动自查 · #3 grep 对齐 + #16 共享字段）

- [x] 1.1 grep `data class LapTelemetry` 于 `core/domain/.../model/LapTelemetry.kt`，确认字段：`sessionId/lapIndex/lapStartWallClock/lapEndWallClock/lapDurationMs/sectorBoundaries(List<Long>)/trackId(String?)`，**确认无 carModel**。done：字段名与 design 映射一致。
- [x] 1.2 grep `sectorBoundaries` 产出点，验证 doc §3.7 假设——`sectorBoundaries[0]` 是否 == `lapStartWallClock`，末段到 `lapEndWallClock`。据实定相邻差算法（首段 = b1-lapStart 或 b1-b0）。done：分段切分算法锚定，和≈整圈。
- [x] 1.3 grep 一圈完成 / LapTelemetry 产出落点（候选 `feature/test/.../viewmodel/TestSessionViewModel.kt`），定位上报触发挂点。done：触发点确认。
- [x] 1.4 grep `@Database` 于 `core/data/.../local/AppDatabase.kt` 确认 version=6 + 现有 migration chain 形态。done：v6→v7 migration 锚点。
- [x] 1.5 grep `UserProfileRepository` + `SettingsScreen`（`fun SettingsScreen` :45，driverName :50），确认双构造惯例 + koinInject 注入。done：扩展点锚定。

## 2. core/network 新模块（首个网络栈隔离）

- [x] 2.1 新建 `core/network` 模块（`build.gradle` + `AndroidManifest.xml`），`settings.gradle` include `:core:network`。预加 `// @IgnoreFormatCheck` 到所有新 .kt。done：模块编译。
- [x] 2.2 加依赖 Retrofit + OkHttp + Gson converter（logging-interceptor debug 可选）。done：依赖解析（注意华为云镜像）。
- [x] 2.3 `LivetimingApi`（Retrofit interface）：`@POST("api/v1/laps") suspend fun postLap(@Body dto: LapUploadDto): Response<Unit>`。done：接口定义。
- [x] 2.4 `LapUploadDto`（Gson）：`trackId/driver/carModel?/lapNo/lapTimeMs/sectorsMs?/clientLapId/lappedAt?`，字段名与 API 完全一致。done：DTO。
- [x] 2.5 OkHttp `Interceptor` 注入 `Authorization: Bearer <BuildConfig.LIVETIMING_TOKEN>` + `Accept-Encoding: gzip`；Base URL `http://111.229.149.252:8080`。done：client 配置。

## 3. 明文流量 + 权限（Decision 5 Risk）

- [x] 3.1 `app/AndroidManifest.xml` 加 `<uses-permission android:name="android.permission.INTERNET"/>`。done：权限。
- [x] 3.2 `network_security_config.xml` 对 `111.229.149.252` 放行 cleartext（Android 9+ 默认禁明文）；manifest application 引用。done：明文流量放行（将来上 https 移除）。

## 4. core/data 待传队列 Room 表 + v6→v7 migration（高危·schema）

- [x] 4.1 `PendingLapUploadEntity`：`clientLapId(主键/UNIQUE)/trackId/driver/lapNo/lapTimeMs/sectorsMsJson/lappedAtRfc3339/createdAtMs/retryCount`。done：entity（注意 #6：默认值用合理值，retryCount default 0）。
- [x] 4.2 `PendingLapUploadDao`：insert（OnConflict IGNORE/REPLACE 保唯一）、queryAll、deleteByClientLapId、incrementRetry。done：DAO。
- [x] 4.3 `AppDatabase`：entities 加 `PendingLapUploadEntity`，version 6→**7**，加 `migration6To7`（CREATE TABLE，沿用现有 Migration 对象 + SQL 字符串列表惯例）。done：migration（#5 三类对齐：domain LapTelemetry / DTO / Entity 字段分清，不混）。
- [x] 4.4 AppModule 注册 dao + 把 migration6To7 加入 migrations 列表。done：DI + 迁移挂载。

## 5. UserProfileRepository 扩展（DataStore 加 2 key）

- [x] 5.1 加 `livetimingEnabled: Flow<Boolean>`（`booleanPreferencesKey`，**default true**）+ `setLivetimingEnabled`。done：开关持久化。
- [x] 5.2 加 `hasShownDriverNamePrompt: Flow<Boolean>`（default false）+ `setDriverNamePromptShown`。done：首开 flag 持久化。
- [x] 5.3 同步补 `UserProfileRepositoryTest` 新 key 断言。done：单测。

## 6. 上报编排 + 映射（feature/test · 命门 Decision 2）

- [x] 6.1 `LapUploadMapper`：`LapTelemetry` → `LapUploadDto`。**`clientLapId = "${sessionId}:${lapIndex}"`**（MUST 稳定派生，**禁** UUID.randomUUID）；`lapNo=lapIndex+1`；`sectorsMs` 相邻差（按 1.2 结论）；`lappedAt`=lapEndWallClock 转 RFC3339；`carModel` 不传。done：纯函数 + 单测。
- [x] 6.2 `LapUploadOrchestrator`（或 repository）：前置校验（livetimingEnabled && driverName 非空 && trackId!=null，否则跳过不入队；无车手名触发一次性提示）；调 `postLap`；201→完成，失败→入队。done：编排逻辑。
- [x] 6.3 失败/错误分流（spec R4）：429 读 Retry-After 退避；400 不死循环（标记/丢弃+日志）；401 落队列待 token；异常吞进队列+FileLogger（旁路不阻塞圈速）。done：错误处理。
- [x] 6.4 flush：出圈后 + app 启动 flush 队列，复用持久化的 clientLapId 串行重试，201 出队。done：补传。
- [x] 6.5 出圈触发接线（挂 1.3 落点），上报为旁路副作用（协程，不阻塞主流程）。done：触发接线。

## 7. Settings 上报开关（UI）

- [x] 7.1 `SettingsScreen`（:45）加"上报到 livetiming"`Switch`，绑 `userProfileRepository.livetimingEnabled`/`setLivetimingEnabled`。文案遵守 V2 单行约束（若 metric/row/label 类）。done：开关可切 + 持久化。

## 8. 首开车手名引导 dialog（capability first-launch-driver-prompt）

- [x] 8.1 主页（`DeviceHomeScreen` 或 AppShell 首达点）读 `hasShownDriverNamePrompt`，false → 弹一次性 `AlertDialog`（"设个车手名？livetiming 榜单展示用"，`去设置`/`以后再说`）。done：首开弹。
- [x] 8.2 弹出即 `setDriverNamePromptShown(true)`；`去设置`→导航 settings + flag true；`以后再说`→关闭 + flag true。done：只弹一次（spec 反例锁）。
- [x] 8.3 dialog 长文本 **不** 加 maxLines=1（CLAUDE.md：Toast/AlertDialog 长文本豁免单行约束）。done：文案完整不截断。

## 9. BuildConfig token（高危·密钥不入 git）

- [x] 9.1 token 从 `local.properties`（gitignored）读 → gradle `buildConfigField "String", "LIVETIMING_TOKEN"`。源码/提交 **不含** 明文 token。done：token 注入且 git 无明文。
- [x] 9.2 **依赖 user/后端提供 token 实际值**：缺值时上报 401→落队列（不崩）。done：占位字段 + 文档说明取 token 流程。⚠️ 真机实测上报需真 token，向 user 取。

## 10. DI 接线（Koin）

- [x] 10.1 AppModule 注册 `LivetimingApi`/OkHttp/Retrofit（core/network）+ 上报编排 + PendingLapUploadDao。grep 验证 Koin DSL 实际形态（`single { }` lambda，非 `single<T>`，盲点 #12）。done：DI 注册 + app 启动不崩。

## 11. 测试（每条 spec requirement 落地 · 含反例锁）

- [x] 11.1 **clientLapId 幂等反例锁**（spec R2）：(a) 单测断言同圈首传与重试 clientLapId 相等（`s1:2`）；(b) 源码结构断言——上报/映射路径不含 `randomUUID`、clientLapId 来源是 sessionId+lapIndex 拼接（违反 fail）。done：两断言绿。
- [x] 11.2 前置校验（spec R1）：开关关/无车手名/trackId null 三场景断言不上报不入队 + 无车手名触发提示；前置满足断言 postLap 被调（mock api）。done。
- [x] 11.3 队列补传（spec R3）：失败入队 / flush 复用 clientLapId→201→出队 / 唯一约束挡重复入队。done（Room in-memory 测试）。
- [x] 11.4 错误处理（spec R4）：429 退避 / 400 不死循环 / 异常不影响本地记录。done（mock Response 各码）。
- [x] 11.5 首开引导（first-launch spec）：首开弹+置flag / flag true 不再弹（反例锁）/ 去设置+以后再说均置 flag。done。
- [x] 11.6 `LapUploadMapper` 纯函数单测：字段映射 + sectorsMs 相邻差 + RFC3339 + lapNo+1 + carModel 不传。done。
- [x] 11.7 Room migration v6→v7：断言 migrations 列表含 migration6To7 + @Database version=7（沿用现有 migration 测试惯例；MigrationTestHelper 若有则跑 schema 验证）。done。

## 12. apply 期高频盲点自查

- [x] 12.1 #3 grep 锚点对齐（LapTelemetry 字段 / sectorBoundaries 语义 / AppDatabase version / Koin DSL 形态）。done。
- [x] 12.2 #5 三类架构：`LapTelemetry`(domain in-memory) / `LapUploadDto`(网络 DTO) / `PendingLapUploadEntity`(Room) 字段**不混并**，各自独立。done：三类字段对应表清晰。
- [x] 12.3 #6 migration NOT NULL DEFAULT：新表列默认值合理（retryCount default 0；不用 0/-1 哨兵误命中语义）。done。
- [x] 12.4 #16 共享字段：本 round 不改 LapTelemetry 字段（只读消费），无 producer/consumer drift。done：确认只读。

## 13. 构建 + 测试验证

- [x] 13.1 全模块编译（core/network + core/data + feature/test + app），gradle 8.9 + 华为云镜像。done：编译绿。
- [x] 13.2 跑 core/network + core/data + feature/test 新增单测全绿（贴输出）。done。
- [x] 13.3 跑 room-migration-chain 相关测试不回归（version bump 后）。done。

## 14. 真机验证 gate（MUST · 需联网 + 真 token）

- [x] 14.1 ⚠️ 前置：取得后端配发的真 `LIVETIMING_TOKEN` 灌 local.properties。done：token 就位。
- [x] 14.2 真机（华为 `8KE0219522008434`）连 blazepush-peter 实际跑圈：验证出圈 `POST /laps` 201 + `/board` 网页看到成绩入榜。done：真机入榜。
- [x] 14.3 弱网/飞行模式跑圈：验证失败入队 + 恢复网络后 flush 补传（同 clientLapId，不重复入榜）。done：离线补传。
- [x] 14.4 开关关→不上报;首开引导弹一次、再开不弹;无车手名跳过+提示。done：开关/引导验证。
- [x] 14.5 真机串行约束：install 前告知 user round/apk/场景等授权（CLAUDE.md）。done：user 放行后 install。

> 2026-07-23：user 明确确认以上 Livetiming 真机体验场景均已路测，问题不大，按验收通过记录。

---

> push/commit：CC 自驱到 apply 完成 + 真机;push 由 user 拍板。
> ⚠️ apply 启动时机：建议 BLE round（ble-no-fix-keep-link）真机路测过后再开 apply，避免真机验证排队 + 工作区焦点分散。
