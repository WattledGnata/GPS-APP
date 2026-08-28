## Context

App 当前**零网络栈**(无 `INTERNET`、无 HTTP client)。圈速记录闭环在本地:`LapTelemetry`(`core/domain/.../model/LapTelemetry.kt`)含 `sessionId / lapIndex / lapStartWallClock / lapEndWallClock / lapDurationMs / sectorBoundaries(List<Long>) / trackId(String?) / trackNameSnapshot`,**无 carModel**。车手名前置已就位:`UserProfileRepository`(`feature/test/.../datastore/UserProfileRepository.kt`,DataStore,driverName)+ `SettingsScreen`。Room 在 `core/data`,`AppDatabase @Database(version=6)` 带完整 migration chain(`migration2To3`…`migration5To6`,SQL 字符串列表 + Migration 对象惯例)。

服务端契约:`livetiming-server/docs/api/client-integration.md`。`POST /api/v1/laps`(Bearer token,JSON,gzip),201 成功(**重复 clientLapId 也 201,已去重**),400/401/429。读接口公开、写接口需 token。

## Goals / Non-Goals

**Goals:**
- 出圈实时上报整圈成绩到 `POST /laps`,P 房可在 `/board` 实时看榜。
- 弱网/离线不丢圈:失败落 Room 待传队列,幂等补传。
- 上报可控:Settings 开关(默认开)+ 车手名/trackId 前置校验。
- 首开引导用户设车手名(一次性)。
- 不污染现有圈速主流程(上报是旁路副作用,失败不影响本地记录)。

**Non-Goals:**
- 不拉赛道下发(本地 trackId 直接用,赛道仍内置)。
- 不做站内榜单/实时 UI(先用服务端 `/board` 网页)。
- 不传 carModel(App 无车型概念)、不做位置/轨迹上报。
- 不做密码学级防伪(token 是 App 级共享密钥,doc 已声明仅合理性下限)。

## Decisions

### Decision 1：实时逐圈上报 + Room 待传队列幂等补传

出圈(lap 完成)→ 立即尝试 `POST /laps`;成功(201)即完成。失败(网络错 / 5xx / 429 / 离线)→ 把该圈持久化到 Room 待传表;**出圈时 + app 启动时** flush 队列重试。flush 用同一 `clientLapId`,201(含幂等重复)即出队。

- **Alt A:批量收车上报** — 拒:服务端 `live` 推送按"出一圈推一帧"设计,批量=榜单滞后到收车,实时形同虚设。
- **Alt B:纯实时无队列** — 拒:弱网/离线直接丢圈,违"不丢圈"目标。
- **Risk**:flush 与出圈并发可能重复发同一圈 → **Mitigation**:`clientLapId` 幂等(服务端去重)+ 入队/出队对 `clientLapId` 唯一约束。

### Decision 2（命门）：clientLapId 按圈稳定,生成一次、持久化、重试复用

`clientLapId` MUST 一圈一个、**入队/首次上报时生成一次**、作为 Room 表列持久化、**每次重试复用同一个**。**MUST NOT** 在请求构造处 new(每次 new → 弱网重试键变 → 服务端当新圈 → 重复入库,幂等失效)。

实现取 `clientLapId = "${sessionId}:${lapIndex}"`(`LapTelemetry` 现成字段,天然按圈稳定、无需额外持久化随机源,且可读)。

- **Alt:每圈持久化随机 UUID** — 同样满足"按圈存+重试复用"(user 确认),但需额外存一列随机值且不可读;`sessionId:lapIndex` 用现成字段更简、调试友好。两者幂等性等价,选前者。
- **Alt(反模式,明确拒绝)**:请求构造处 `UUID.randomUUID()` — 重试键漂移,幂等失效。spec 反例锁 + 测试断言。

### Decision 3：上报开关默认开 + 前置校验

`UserProfileRepository` 加 `livetimingEnabled: Flow<Boolean>`(default **true**)。上报前置:`livetimingEnabled == true` **且** `driverName` 非空 **且** `LapTelemetry.trackId != null`。任一不满足 → 跳过上报(不入队)。开但无车手名 → 跳过 + 一次性提示(引导去设置)。

- **Alt:默认关** — 拒:user 明确要默认开。
- **Alt:无开关静默上传** — 拒:隐私不可控。

### Decision 4：首开一次性车手名引导

首次到达主页弹 dialog:"设个车手名?(livetiming 榜单展示用)" + `去设置`(→ SettingsScreen)/`以后再说`。`UserProfileRepository` 加 `hasShownDriverNamePrompt`(default false),弹过即置 true,再开不弹。独立 capability `first-launch-driver-prompt`(与上报解耦,可单测 flag 逻辑)。

- **Alt:不引导,靠用户自己进设置** — 拒:默认开上报但无车手名会静默跳过,用户无感知;引导提升首跑可上报率。
- **Alt:首开强制必填车手名** — 拒:打断、强制反感;`以后再说` 更友好。

### Decision 5：core/network 新模块 + Retrofit/OkHttp/Gson

新建 `core/network` 隔离首个网络栈(按"新能力隔离独立模块"约定,不污染 core/data)。Retrofit + OkHttp + Gson(项目已用 Gson;Retrofit 标准、未来加 standings/track 端点顺)。token 走 OkHttp Interceptor 注入 `Authorization: Bearer`,token 值来自 `BuildConfig`(gradle 从 local.properties 注入,不硬编码源码、不进 git 明文)。

- **Alt:Ktor** — 拒:Retrofit 更 Android 标准、团队熟。
- **Alt:塞 core/data 不建模块** — 拒:污染数据层 + 违隔离约定。
- **Risk**:HTTP cleartext(裸 IP + http)Android 9+ 默认禁明文流量 → **Mitigation**:`network_security_config` 对该 host 放行 cleartext(doc 当前 http,将来上 https 移除);apply 期落地。

### Decision 6：待传队列 Room 表 + v6→v7 migration

`PendingLapUploadEntity`(列:`clientLapId`(主键/唯一)、`trackId`、`driver`、`lapNo`、`lapTimeMs`、`sectorsMsJson`、`lappedAtRfc3339`、`createdAtMs`、`retryCount`)进 `core/data` AppDatabase。`@Database` version 6→**7**,加 `migration6To7`(CREATE TABLE),沿用现有 migration chain 惯例(Migration 对象 + SQL 字符串列表 + 反射 version 断言 + MigrationTestHelper 若有)。

- **Alt:DataStore/文件存队列** — 拒:队列要查询/删除/唯一约束,Room 更合适且 schema 受 migration 保护。

## Risks / Trade-offs

- **[Risk] clientLapId 不稳定 → 重复入库** → Decision 2 MUST + 反例锁。
- **[Risk] 弱网/离线丢圈** → Decision 1 Room 队列。
- **[Risk] 429 限流** → 读 `Retry-After` 头退避;flush 不并发狂发(队列串行 + 间隔)。
- **[Risk] cleartext http 被 Android 拦** → Decision 5 network_security_config 放行。
- **[Risk] token 入 git** → BuildConfig 从 local.properties(gitignored)注入;源码/提交不含明文。
- **[Risk] sectorBoundaries 语义假设错**(首元素是否=lapStart)→ apply 期 #3 grep + 实测验证,spec scenario 锁分段和≈整圈。
- **[Trade-off] 出圈副作用**:上报失败 MUST NOT 影响本地圈速记录/UI(旁路,异常吞进队列 + 日志)。

## Migration Plan

- Room v6→v7:加 `pending_lap_uploads` 表(CREATE TABLE migration,非破坏)。回滚:destructive 兜底 + 新表无历史数据依赖。
- 新模块 core/network、INTERNET 权限、BuildConfig token:部署即生效;token 缺失时上报 401 → 落队列(不崩)。
- 灰度:开关默认开,但无车手名/无 trackId 自动跳过,首跑前用户大概率被首开引导提示设名。

## Open Questions

- flush 触发是否加连接恢复监听(NetworkCallback)/ WorkManager 保证最终送达 → 本 round 先做"出圈+app启动 flush"最小集,WorkManager/连接监听列为硬化 follow-up。
- 上报状态是否在 UI 露出(已传/待传/失败 count)→ 本 round 最小:静默 + 队列;轻量 pending count 指示器可选,真机路测后定。
- token 实际值与配发方式 → 需后端提供;apply 前从 user/后端取,先用占位 BuildConfig 字段。
