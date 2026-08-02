## Why

圈速 session 目前只在用户显式点击“结束计时”时写入 `endTs/lapCount/bestLapMs/topSpeedKmh` 汇总；直接退出、崩溃、系统杀进程或关机会留下 `endTs == startTs` 的未闭环行。原始过线事件、遥测和已 Finalize 的视频仍在，但历史页会把该场显示成 0 圈，形成“Livetiming 已实时上报、本地却没有记录”的数据黑洞。

现有车友设备已经留下可恢复的过线事件和 5 段视频，因此需要尽快提供覆盖升级后首次启动即可执行的无损恢复能力，同时作为今后异常退出的最后一道防线。

## What Changes

- App 启动时以及用户每次进入 Records 的 LAPS 子页时，扫描 `LAP_SESSION` 且 `endTs <= startTs` 的未闭环 session。
- 仅使用已持久化的 accepted StartFinish 真壁钟过线事件重新配对，恢复 `lapCount` 与 `bestLapMs`。
- 从可读 binary 遥测派生 `topSpeedKmh`，并以最后过线、视频段结束或遥测末点作为可靠结束时间提示；证据不足时仍保证写入 `endTs > startTs`。
- 恢复过程幂等：已闭环 session 不处理，重复启动不改变已恢复结果。
- 保留所有 crossing、binary、视频段与文件，不触发 Livetiming 重传，不删除任何现场证据。
- 启动日志记录恢复数量和每条 session 的非敏感摘要，失败不得阻塞 App 启动。

## Capabilities

### New Capabilities

- `incomplete-lap-session-recovery`: 异常退出后从持久化过线、遥测和视频元数据幂等恢复圈速 session 汇总。

### Modified Capabilities

无。

## Impact

- `core/data`：增加未闭环 session 查询、可重启恢复的汇总计算与幂等写回。
- `app` / `feature/test`：在 `BlazePushApplication` 启动后台任务和每次进入 LAPS 子页时触发恢复并记录结果。
- `core/data` tests：覆盖现有车友形态、重复恢复、无圈/缺文件和已闭环跳过。
- Livetiming 服务端、客户端上传协议、RaceChrono BLE 公共协议均不修改；恢复只修本地汇总，不重新发送已完成圈。

## 协议兼容性

不修改 RaceChrono BLE 字段、编码或 Livetiming HTTP 契约；不涉及 simulator 发射端改动。
