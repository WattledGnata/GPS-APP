## Why

当前预置赛道目录只有 1 条 TFIC LPCC，圈速调试场景缺少**真实小型多弯环线**的素材。用户从设备厂商导出了一份 `.rcz` 格式的"成都天投泊寓环线"赛道（双流区雅州路、Vanke 泊寓项目内、5 个计时门、约 2.5 km 单圈、距 ESP32 真机当前位置 ~700m），同时附带一份同赛道的 25 Hz 实测 4 圈 session（best lap 2:39.888，含完整 GPS 路径），可以直接补全 referencePath。

仅 **debug 包**接入这条赛道：发布给外部用户的 release 包应只看到官方授权的 TFIC，调试场景给 CC + 用户私下的真机验证使用。

## What Changes

- 新增"天投泊寓环线"预置 `Track`，**仅在 debug 构建中**进入 `PresetTrackCatalog.getAllTracks()` 返回值
- `PresetTracks.kt`（`feature/test/src/main/`）改为 `mainPresets + extraPresetTracks()`，**`extraPresetTracks` 函数本体仅由 `src/debug/` 与 `src/release/` 互斥变体源集各提供一份**（main 不声明实现，否则 debug variant 编译时 duplicate JVM declarations）
- 新增 `feature/test/src/release/.../ExtraPresetTracksRelease.kt`：`extraPresetTracks() = emptyList()`
- 新增 `feature/test/src/debug/.../ExtraPresetTracksDebug.kt`：`extraPresetTracks() = listOf(<天投泊寓 Track>)`（87 点 referencePath + 1 起终点 + 4 sector gate + sequenceIndex 通过 Lap 1 实测过线时间反推）
- 新增 `docs/design/rcz-format-decoding.md`：沉淀 `.rcz` 编码规则（坐标 ×6,000,000 / bearing 千分度 / width mm）+ session binary channel 布局（channel 1=ts、2=cumDist、3=packed lat/lon、4-6/30002+ 不需要）+ Lap 切片公式
- 新增 `docs/tools/decode_rcz_session.py`：离线一次性脚本（输入 `.rcz` session，输出可粘贴的 Track DSL 片段），仅在 docs/ 留档作未来导新赛道复用，**不进 production runtime**
- 单测在 `feature/test` 增量验证 main vs debug variant 的 catalog 长度差异（如双 variant 在 JVM 测试框架不可共存，用文档化 caveat + 真机覆盖）

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `track-catalog-hot-start`: `PresetTrackCatalog.getAllTracks()` 在 debug 构建下 MUST 额外返回天投泊寓预置赛道；release 构建下 MUST 保持只返回 TFIC LPCC 一条；新增的 debug 路径 MUST 通过 Android Gradle `src/debug/` 源集机制隔离，MUST NOT 依赖 `BuildConfig.DEBUG` 运行时判断。

## Impact

**受影响模块**：
- `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`（最小钩子改造，调用 `extraPresetTracks()`，零行为变更 in release variant）
- `feature/test/src/release/java/com/blazepush/feature/test/repository/ExtraPresetTracksRelease.kt`（**新增**，仅 release 构建编译进 apk，返回 emptyList）
- `feature/test/src/debug/java/com/blazepush/feature/test/repository/ExtraPresetTracksDebug.kt`（**新增**，仅 debug 构建编译进 apk，返回 boyu loop Track）
- `feature/test/src/test/java/com/blazepush/feature/test/repository/`（补/调整 catalog 长度断言，注意 unit test 默认走 main + test 源集，无法看到 debug variant；此点写进 design 与 tasks）

**未受影响**：
- 公共协议（RaceChrono BLE、replay JSON、binary 持久化）— 全部不动
- 接收链路（`BluetoothDataSource`、`RaceChronoParser`、`GpsDataFilter`）— 全部不动
- `LapTimingEngine`、`TimingGate` 几何 — 全部不动；新 Track 复用现有 `Track` / `TimingGate` / `TrackPath` 数据结构
- `ReplayAlignedTrackCatalog` — 全部不动；本 round 不为天投泊寓提供 replay-aligned 拟合
- release apk 行为 — 与本 round 实施前等价（`PresetTrackCatalog.getAllTracks()` 仍只返回 TFIC 一条），不含天投泊寓 Track 数据；release 包内不引入 debug 源集类（`ExtraPresetTracksDebugKt`）。源码新增 `extraPresetTracks()` 调用 + `ExtraPresetTracksReleaseKt` 类会带来字节级 diff 与极微小（O(数百字节)）的 apk size 变化，但不构成行为或可观察体积回归

**协议兼容性**：N/A（不动协议）。

**双端任务**：纯接收端 + debug-only 工件；simulator 端无变更。

**Follow-up（写进 tasks §10 backlog）**：
1. 缩略图 `track_thumbnails/boyu_loop.png` 暂缺 → 单独 round 补图（需要从卫星图截或本地拍）
2. Track DSL 当前是离线脚本生成 + 写死，未来若多条 `.rcz` 进项目，再立项做 runtime `.rcz` parser（含 session 路径自动抽圈）
