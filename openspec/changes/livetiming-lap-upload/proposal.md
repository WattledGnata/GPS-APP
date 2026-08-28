## Why

App 已能精准记录圈速(`LapTelemetry`:整圈时间 + 分段),也有了车手名前置(2026-06-01 driver-display-name round),但**圈速只存在本地**——跑完没法让 P 房其他人实时看到成绩。livetiming 服务端(独立 repo,API 已就绪)提供 `POST /api/v1/laps` 上报 + 现成观众网页 `/board` 实时看榜。本 round 打通客户端上报链路,让"跑完一圈 → 榜单实时更新"闭环。

这是 app 引入的**第一个网络栈**:当前零网络(无 `INTERNET` 权限、无 HTTP client)。服务端契约权威文档:`livetiming-server/docs/api/client-integration.md`。

**关键好性质**:服务端登记的赛道 id 就是本地预置赛道 id(`preset-tfic-lpcc` / `preset-boyu-loop`,与 `PresetTracks.kt` 一致)→ 上报直接用本地 `LapTelemetry.trackId`,**不需拉赛道下发**,赛道仍纯内置。

## What Changes

- **新增 `core/network` 模块**:隔离首个网络栈(Retrofit + OkHttp + Gson);`LivetimingApi.postLap()` 封装 `POST /api/v1/laps`(Bearer token + gzip)。
- **实时逐圈上报**:出圈(lap 完成)即组装 DTO 上报;成功 201(含幂等重复)即完成。
- **失败落 Room 待传队列**:网络错/可重试失败 → 持久化到新表(`core/data` AppDatabase,**schema migration v6→v7**);出圈时 + app 启动时 flush 队列,用**同一 `clientLapId` 幂等补传**。
- **`clientLapId` 按圈稳定**:一圈一个 id,入队时生成一次、持久化为表列、重试复用;**MUST NOT** 每次请求 new(否则键变→服务端当新圈→重复入库)。
- **Settings 上报开关**:"上报到 livetiming",**默认开**;开 + 车手名非空 → 才上报(`UserProfileRepository` 加 `livetimingEnabled`)。
- **首开车手名引导**:首次打开 app 弹**一次性** dialog 问是否设车手名(`去设置` / `以后再说`),只弹一次(`UserProfileRepository` 加 `hasShownDriverNamePrompt`)。
- `AndroidManifest.xml` 加 `INTERNET` 权限;token 埋 `BuildConfig`(gradle 注入,不入 git 明文)。

**协议兼容性**:不改本地 RaceChrono BLE 接收协议、不改 replay 协议。新依赖一个**外部 HTTP API**(livetiming-server `/api/v1`,版本化;破坏性变更升 `/api/v2`,老客户端不受影响)。**非双端改动**(发射端 simulator 不动;livetiming 是独立服务端 repo)。

## Capabilities

### New Capabilities

- `livetiming-lap-upload`: 整圈成绩上报能力。规定上报触发(实时逐圈)、字段映射(`LapTelemetry`→`POST /laps`)、`clientLapId` 按圈稳定幂等、失败 Room 队列补传、上报开关(默认开)+ 车手名/trackId 前置校验、限流(429 Retry-After)与错误(400/401)处理。
- `first-launch-driver-prompt`: 首开车手名引导能力。规定首次启动一次性弹 dialog、`去设置`/`以后再说` 两路径、`hasShownDriverNamePrompt` flag 保证只弹一次。

### Modified Capabilities

<!-- 无 spec-level requirement 变更。driver-display-name capability 的 requirement 不变（车手名设置行为照旧）；本 round 只在 UserProfileRepository **新增** livetimingEnabled / hasShownDriverNamePrompt 两个 DataStore key（additive），归入上面两个新 capability，不改 driver-display-name 既有 requirement。 -->

## Impact

**新增模块/文件**:
- `core/network/`(新模块):`LivetimingApi`(Retrofit interface)、`LapUploadDto`、OkHttp/Gson 配置、token 注入。
- `core/data`:待传队列 Room entity(`PendingLapUploadEntity`,含 `clientLapId` 唯一列)+ DAO + **AppDatabase v6→v7 migration**(`migration6To7`,走 room-migration-chain 惯例)。
- `feature/test`:上报编排(出圈触发 + flush)、`LapTelemetry`→DTO 映射;`SettingsScreen` 加上报开关;首开 dialog + 触发逻辑;`UserProfileRepository` 加 `livetimingEnabled`(default true)/`hasShownDriverNamePrompt`(default false)。
- `app`:`AndroidManifest.xml` 加 `INTERNET`;DI(Koin)注册 network + 上报编排;BuildConfig token。

**受影响数据流**:
- 出圈事件(lap 完成,落点在 `feature/test` 圈速链路 / TestSessionViewModel)→ 新增上报副作用(不阻塞圈速主流程)。
- `LapTelemetry.trackId` 为 null(自由跑无选赛道)→ 上报 MUST 跳过。
- `LapTelemetry` 无 `carModel` 字段 → `carModel` 不传。
- `sectorsMs` 由 `sectorBoundaries`(List<Long>)算相邻差;apply 期 MUST grep 验证 `sectorBoundaries[0]` 与 `lapStartWallClock` 关系(doc §3.7 假设首元素=lapStart)+ 末段到 `lapEndWallClock`。

**风险/边界**:
- 幂等命门:`clientLapId` 不稳定 → 重复入库(spec MUST + 反例锁)。
- 隐私:开关默认开但可关 + 首开引导透明告知。
- token:非密码学级防伪(doc 已声明,后续每设备签名解决,本 round 不做)。
- 真机验证:需真机(华为 `8KE0219522008434`)实际跑圈 + 联网,验证 201 入榜(`/board` 看到)+ 弱网入队补传 + 开关/首开引导。

**不在本 round(后续增量)**:拉赛道下发(①)、站内榜单/实时 UI(③,先用 `/board` 网页)、carModel 输入、位置上报。
