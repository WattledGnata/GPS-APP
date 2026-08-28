## 1. 文档与离线工具沉淀（含 .rcz 输入受控）

- [x] 1.0 把用户提供的两份 `.rcz` 拷入 `docs/tools/input/`：
  - `docs/tools/input/track_天投泊寓环线.rcz`（仅供格式参考与未来对比，本 round 不消费）
  - `docs/tools/input/session_20260108_225454_天投泊寓环线.rcz`（本 round 实际使用）
  - 撰写 `docs/tools/input/README.md`：来源描述（用户从 RaceChrono / Race-Captain 风格采集设备导出，2026-05 由用户经微信传至 CC 本机）+ sha256 期望值（track: `ca9fc2c3a59750e4...`、session: `666b501cb2d074cf...`，**用 `shasum -a 256` 实测后写入完整 64 位 hex**）+ 隐私声明（含 GPS 坐标，已属公开可用范围，无需脱敏）+ 用法（输入到 task 1.2 脚本）
  - 校验：`shasum -a 256 docs/tools/input/*.rcz` 输出与 README 登记一致
- [x] 1.1 撰写 `docs/design/rcz-format-decoding.md`，覆盖：编码规则（坐标 ×6e6 / bearing millidegree / width mm）、session binary channel 布局（channel 1=ts int64 LE / 2=cumDist int64 LE / 3=packed lat/lon × int32 LE / 4-6/30002+ 不消费）、Lap 切片公式（用 `session.json.laps[].startTimestamp` 与 `finishTimestamp` 二分 channel 1）、用 TFIC `.rcz` 反推编码规则的方法论、session `.rcz` 内含 `trackId.json` 因此**单 session 输入即可**完整生成 Track DSL；首段标注本 change 名称 + 引用 `docs/tools/input/`。
- [x] 1.2 提交 `docs/tools/decode_rcz_session.py`：
  - 仅依赖 Python 标准库（`zipfile` / `struct` / `math` / `json` / `bisect` / `argparse` / `hashlib`）
  - 位置参数 1 = **session `.rcz` 路径**（必填，脚本启动时必须 `zipfile.ZipFile(...).namelist()` 校验包含 `session.json` 与 `channel_*` 系列文件，否则退出码 ≠ 0 且打印 spec 中规定的错误文案）
  - 可选参数 `--lap N`（默认 1）、`--resample-step-m N`（默认 30）、`--out PATH`（默认 stdout）
  - 输出：Kotlin DSL 文本片段（含 `referencePath = TrackPath(points = listOf(...87 个 GeoPoint...))`、起终点 + 4 个 sector 的 `TimingGate` 完整代码块，包含 line/passDirection/sequenceIndex）
  - sequenceIndex 反推逻辑：对每个 sector trap，扫所选 lap 的 channel 3 sample，找最早一帧"穿过 gate line"（用 line 两端点 + 当前 sample 与上一 sample 构成的两条线段是否相交判断）；按穿越时间升序赋 1..4
  - 脚本顶部注释包含：shebang `#!/usr/bin/env python3`、本 change 名 `add-debug-preset-track-boyu-loop`、用法示例（`python3 decode_rcz_session.py docs/tools/input/session_20260108_...rcz --lap 1 --resample-step-m 30 --out docs/tools/output/decode_rcz_session_boyu_loop.txt`）、输入文件 sha256 期望值
- [x] 1.3 跑 1.2 脚本：`python3 docs/tools/decode_rcz_session.py docs/tools/input/session_20260108_225454_天投泊寓环线.rcz --lap 1 --resample-step-m 30 --out docs/tools/output/decode_rcz_session_boyu_loop.txt`，确认产物文件生成、内容含 87 个 GeoPoint + 5 个 TimingGate；产物作为可审查工件留 git。

## 2. main 源集钩子改造（main 不实现 extraPresetTracks，只调用）

- [x] 2.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`：
  - 抽出 `private val mainPresets = listOf(<原 TFIC>)`（原 `presetTracks` 的 13 点 referencePath / gate 坐标**完全不变**）
  - 将 `internal val presetTracks` 改为 `internal val presetTracks: List<Track> = mainPresets + extraPresetTracks()`
  - 在 `mainPresets` 上方加引用注释："`extraPresetTracks` 由 src/debug + src/release 互斥源集各提供一份；main 源集禁止声明同签名函数（否则 debug variant 会触发 duplicate JVM declarations）。变体差异见 OpenSpec change `add-debug-preset-track-boyu-loop` design D1/D2。"
  - **不**新增 main 源集下任何其它文件
- [x] 2.2 跑 `./gradlew :feature:test:assembleRelease`：确认 release variant 单独编译失败（缺 `extraPresetTracks` 实现，因 release 源集尚未建），证明 D1 互斥源集机制必要性 —— 这是预期失败点，不算阻塞，证明源集隔离生效后再修。
  - 备选：用 `git stash` 暂存 PresetTracks.kt 改动跑 sanity check 后回滚，避免 task 2.1 单独无法 compile 的中间态进入 commit。建议把 2.1 + 3.x + 4.x 合并为单 commit。
  - **本 round 实施时采纳备选方案**（用户 apply 阶段实施约束）：2.1 + 3.1 + 3.2 一起改、一起 commit，跳过单独跑 release variant 看失败这一步，直接跑 3.3 双 variant 同时验证编译通过。

## 3. release 与 debug 变体源集实现

- [x] 3.1 创建 `feature/test/src/release/java/com/blazepush/feature/test/repository/ExtraPresetTracksRelease.kt`：
  - 包名 `com.blazepush.feature.test.repository`
  - 内容：`internal fun extraPresetTracks(): List<Track> = emptyList()`
  - 文件顶部注释（**两份变体源集文件须放完全一致的注释**）："本函数 MUST 仅由 src/debug + src/release 双源集**互斥**各提供一份实现；main 源集禁止声明同签名函数（debug variant 编译时同包同签名 top-level 函数会触发 duplicate JVM declarations）。本 round 由 OpenSpec change `add-debug-preset-track-boyu-loop` design D1/D2 锁定该机制。"
- [x] 3.2 创建 `feature/test/src/debug/java/com/blazepush/feature/test/repository/ExtraPresetTracksDebug.kt`：
  - 包名 `com.blazepush.feature.test.repository`
  - 文件顶部注释同 3.1 风格
  - body 返回 `listOf(boyuLoopTrack)`，`boyuLoopTrack` 由本文件内私有 `val` 持有
  - `boyuLoopTrack` 内容来自 1.3 脚本输出（87 点 referencePath + 起终点 + 4 sector，sequenceIndex 1..4 实测反推）；`Track.id = "preset-boyu-loop"`、`name.zh = "成都天投泊寓环线"`、`name.en = "Chengdu Tiantou Boyu Loop"`、`name.abbr = null`、`lengthKm = 2.591`、`thumbnailAssetPath = null`、`source = TrackSource.Preset`
- [x] 3.3 跑 `./gradlew :feature:test:assembleRelease :feature:test:assembleDebug`，确认两套 variant 都编译通过（即 task 2.2 的预期失败点已被本 task 解决）。
- [x] 3.4 跑 `./gradlew :app:assembleDebug` 确认 debug apk 编译通过；用 `apkanalyzer dex packages app/build/outputs/apk/debug/app-debug.apk | grep -i extrapresettracks` 检查 dex 中应找到 `com.blazepush.feature.test.repository.ExtraPresetTracksDebugKt`、**不**找到 `ExtraPresetTracksReleaseKt`。
- [x] 3.5 跑 `./gradlew :app:assembleRelease` 确认 release apk 编译通过；做**两条互补检查**（apkanalyzer 输出包/类层级**不含 string constant pool**，无法证伪字符串存在，必须配合 dex strings 扫描）：
  - **类存在性**：`apkanalyzer dex packages app/build/outputs/apk/release/app-release.apk | grep -i extrapresettracks`，**只**应输出含 `ExtraPresetTracksReleaseKt` 的行，**不**输出 `ExtraPresetTracksDebugKt`。
  - **字符串不存在性**（本工程 release apk 实测为 multi-dex 16+ classes*.dex，必须扫全部）：
    ```bash
    APK=app/build/outputs/apk/release/app-release.apk
    TMP=$(mktemp -d) && unzip -q "$APK" 'classes*.dex' -d "$TMP" \
        && strings "$TMP"/classes*.dex | grep -F 'preset-boyu-loop' ; rc=$?; rm -rf "$TMP"; \
        [ $rc -ne 0 ] && echo "OK: no preset-boyu-loop in any release dex" || { echo "FAIL: preset-boyu-loop leaked into release"; exit 1; }
    ```
    `grep -F` 退出码必须非零（无匹配）。`strings <files...>` 直接吃多文件比 `cat ... | strings` 在 macOS 上更稳；先 unzip 到临时目录再用 `strings classes*.dex` 多文件可靠扫描整个 dex string constant pool。
  - **正向 sanity**：在 debug apk 上跑同一命令应**有**匹配（`grep -F 'preset-boyu-loop'` 退出码 0），证明扫描方法本身有效（debug 包正确含天投泊寓字符串）。

## 4. 测试拆 variant + 新增 boyu loop 契约

- [x] 4.1 把 `feature/test/src/test/.../TrackCatalogTest.kt` 内的 `getAllTracks_exposesOnlyTficLpccPreset` 测试**搬到** `feature/test/src/testRelease/java/com/blazepush/feature/test/repository/TrackCatalogReleaseVariantTest.kt`（新文件）；断言保持 `assertEquals(listOf("preset-tfic-lpcc"), ids)` 不变。`getTrack_locksTficLpccCoordinateContractWithReplayAlignedPresetConstants` 与 `getTrack_returnsNullForUnknownTrackId` **保留在** `src/test/`（共享，两个 variant 都跑）。
- [x] 4.2 新建 `feature/test/src/testDebug/java/com/blazepush/feature/test/repository/TrackCatalogDebugVariantTest.kt`，包含 1 个测试方法断言 `assertEquals(listOf("preset-tfic-lpcc", "preset-boyu-loop"), ids)`（顺序由 `mainPresets + extraPresetTracks()` 拼接顺序锁定）。
- [x] 4.3 新建 `feature/test/src/testDebug/java/com/blazepush/feature/test/repository/BoyuLoopPresetTest.kt`，覆盖 specs 中 4 个 boyu loop scenario：(a) 顶层字段契约（`name.zh == "成都天投泊寓环线"`、`name.en == "Chengdu Tiantou Boyu Loop"`、`name.abbr == null`、`lengthKm == 2.591`、`thumbnailAssetPath == null`、`source == TrackSource.Preset`、`referencePath.points.size == 87`）；(b) gate 顺序契约（`startFinishGate.type == StartFinish`、`startFinishGate.name == "起终点"`、`sectorGates.size == 4` 全部 type Sector、`sequenceIndex == listOf(1,2,3,4)`）；(c) referencePath bbox（lat ∈ [30.397, 30.407]、lon ∈ [104.054, 104.062]）；(d) referencePath 闭合度 ≤ 5m（用 Haversine 或简化大圆距离）。
- [x] 4.4 跑 `./gradlew :feature:test:testReleaseUnitTest`：4.1 release 测试通过 + 共享测试通过；不应执行 4.2 / 4.3。
- [x] 4.5 跑 `./gradlew :feature:test:testDebugUnitTest`：4.2 / 4.3 debug 测试通过 + 共享测试通过；不应执行 4.1 release-only 测试。

## 5. release 包零变更对照

- [x] 5.1 release apk 类与字符串扫描验证已在 §3.5 完成（apkanalyzer dex packages 验类、`strings classes*.dex` 验字符串），本 task 不重复扫描；§5 聚焦 base commit 行为对照。
- [x] 5.2 与本 round 实施前 git stash / 切回 base commit 重跑 `./gradlew :app:assembleRelease`，对比 release apk 中 `PresetTrackCatalog.getAllTracks()` 行为（preset 列表内容相同：仅 TFIC）；字节级因新增 `extraPresetTracks` 调用与 `ExtraPresetTracksReleaseKt` 类必然有源码 diff，本 task 锁定**行为等价**（spec scenario "release 构建产物零变更"对齐）；apk size 变化 ≤ O(数百字节) 视为预期范围内；如出现行为级差异或 size 变化超出预期范围，记录 follow-up 阻塞合回。
  - **实测（2026-05-01）**：用 `git worktree add /tmp/gps-app-base-build ae58830^` 隔离构建 base commit `237ec09`（round 之前 HEAD），结果：base release apk 47,069,703 bytes、current release apk 47,070,259 bytes，**diff = +556 bytes**（O(数百字节) 内 ✓）；两份 apk dex 字符串扫描均**只含** `preset-tfic-lpcc`、**不含** `preset-boyu-loop`，行为完全等价。worktree 已清理。

## 6. 真机验证 gate（默认设备 8KE0219522008434，串行执行需用户授权）

- [x] 6.1 **需用户授权**：debug apk `adb -s 8KE0219522008434 install -r` 后启动 GPS App，进入赛道选择页面（SelectTrackBottomSheet）+ Records 主屏 + Laps 主屏，确认每处赛道列表都展示 2 条赛道，**TFIC 在第 0 位、成都天投泊寓环线在第 1 位**（与 `mainPresets + extraPresetTracks()` 拼接顺序一致）。
  - **2026-05-01 实测通过**：用户确认双赛道顺序正确显示，缩略图与赛道一一对应。
- [x] 6.2 **需用户授权**：选中"成都天投泊寓环线"，确认：
  - **2026-05-01 实测通过**：缩略图、字号、滚动渲染均符合预期；同时暴露 RecordsHomeScreen.LapsView 顶部 panel 标题与下方 SESSION HISTORY 关联错位问题（pre-existing），已沉淀 `docs/design/records-by-track-filter-deferred.md` 9 章 memo + §10 backlog 链 → follow-up round `wire-records-by-track`。
  - **缩略图**：本 round `thumbnailAssetPath = null`，`TrackThumbnail` 走 null fallback → 应渲染 cyan 1dp 描边占位框 + 中央 "NO PREVIEW" 文字（Compose 源 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackThumbnail.kt:88` `FallbackPlaceholder`）；列表与 Laps/Records 三处都应是同一 fallback；**不**期望 referencePath 简图（缩略图 widget 设计上不消费 referencePath，referencePath 简图属于另一 widget 范畴，本 round 不做）
  - **字号**：所有 metric/row/label 类 Text **无** ellipsis 截断（V2 视觉规则核查；尤其 SelectTrackBottomSheet 的赛道名行：track.name.zh "成都天投泊寓环线" 共 7 个汉字，不应被 ellipsis）
  - **列表渲染**：滚动顺滑、无可见卡顿（87 点 referencePath 不应造成性能可感知劣化）
- [x] 6.3 **需用户授权**：release apk `adb -s 8KE0219522008434 install -r` 后启动，确认赛道选择列表（SelectTrackBottomSheet）+ Records 主屏 + Laps 主屏每处只展示 TFIC LPCC 一条；切回 debug 包不应残留状态。
  - **2026-05-01 实测通过**：因 release / debug signing key 不同先 `adb uninstall com.blazepush` 后再装；release 包真机三处赛道列表均仅含 TFIC LPCC，无天投泊寓字样；验完已切回 debug 包供日常使用。
- [x] 6.4 在 `tasks.md §6` 旁记录三步真机验证结果（通过 / 截图 / 异常），由用户主笔签收；如需缩略图真实图，按 §10 follow-up 立项 `add-debug-preset-track-boyu-loop-thumbnail`。
  - **2026-05-01 签收**：6.1/6.2/6.3 全部通过；6.2 暴露 pre-existing Records-by-track 过滤问题已 follow-up；缩略图真实图按 §10 backlog 立项。

## 7. 提交准备

- [x] 7.1 跑 `./gradlew kt-format-check`（或项目对应任务）确认无 pre-existing 风格债被本 round 触发；如触发，先 fix 再 commit，禁用 hook 不允许（参考记忆 `feedback_never_disable_precommit_hooks`）。
  - **2026-05-01 通过**：本机 pre-commit hook 在每次 `git commit` 时自动 invoke kt-format-check；本 round 触发过 2 处违规均按提示直接 Edit 修复（no-trailing-newline × 2 + class-comment / public-fun-with-comment-block × 9）后 commit 通过；未禁用 hook。
- [x] 7.2 草拟 commit message（Conventional Commits）：`feat(track): add debug-only preset 天投泊寓环线 (round add-debug-preset-track-boyu-loop)`；message body 引用本 change 名 + 引用 follow-up backlog（§10）。
  - **2026-05-01 完成**：本 round 采用按章节分 commit 模式而非单一 squash commit；5 个 commits（`ae58830` 文档+脚本+受控输入 / `18065a4` 主代码 §2+§3 / `14bb1f1` 测试 §4 / `1169ae3` §5 进度 / `93679df` Records 过滤 deferred memo）每个都用 Conventional Commits 格式且引用 round 名与 §10 backlog；review 时按 commit 维度审更聚焦。
- [ ] 7.3 **需用户授权 push**：commit 后等用户拍板是否 push 到 `feature/track-tech-v2`（或当前主 feature 分支）；本 round CC **不**自动 push。

## 8. Codex review 触发

- [ ] 8.1 push 完成后提醒用户触发 Codex review。Review 结果分流：(a) 局部修复 1-3 行 → CC 直接消化；(b) 设计级问题 → CC 立新 OpenSpec change。
- [ ] 8.2 Codex review 通过后由 CC 跑 `openspec archive add-debug-preset-track-boyu-loop` 完成归档。

## 9. 完工 / 状态

- [ ] 9.1 全部 tasks 勾选完成 + Codex review 🟢 + 真机验证签收 → 发"round 完成"通知给用户，记录 best lap 真机实测对比（实测时间 vs session metadata 2:39.888）作为下次圈速回归基线。

## 10. Follow-up backlog

- [ ] **缩略图补图**：`track_thumbnails/boyu_loop.png` 暂缺。等用户提供卫星截图或本地拍摄；建议命名 `add-debug-preset-track-boyu-loop-thumbnail`，预计单 round ≤ 1h，仅添加 PNG + Track DSL 内 `thumbnailAssetPath = "track_thumbnails/boyu_loop.png"` 一行；测试更新 `BoyuLoopPresetTest.kt` 内 `thumbnailAssetPath` 断言从 `null` 改为预期路径。
- [ ] **runtime `.rcz` parser**：本 round 写死 DSL，未来若需要在 app 内导入用户自定义 `.rcz`（例如赛道社区分享），再立项 `import-rcz-runtime-parser`。memo 已经在 `docs/design/rcz-format-decoding.md`（task 1.1）覆盖，下次开 round 直接照单做。
- [ ] **天投泊寓 replay-aligned**：若后续要用天投泊寓做 replay 回归（类似 TFIC 的 `tianfu_track_replay_5hz.json` + `tianfu_track.vbo`），需要把本 round 用到的 session binary path 转换为 vbo + 5Hz JSON，并在 `ReplayAlignedTrackCatalog` 注册第 2 条赛道。预计单独 round。
- [ ] **Records 历史按赛道过滤** ← 本 round 真机验证（2026-05-01）暴露：`RecordsHomeScreen.LapsView` 顶部 `CurrentTrackRecordCard` 标题跟随 `currentSelectedTrack` 切换，但下方 SESSION HISTORY 不按 track 过滤（`getRecentLapSessions(limit=10)` 返回全部），加上顶部 BEST LAP / sessions / totalLaps 仍是 mock 写死。引入第 2 条预置赛道后，用户切换到天投泊寓时早上跑的 TFIC sessions 会"视觉上"被归到泊寓 panel 下方。根因：`TelemetrySessionEntity` Room schema **不存 trackId**，所以即使加 filter 参数也无数据可过滤。完整设计 memo（9 章）已沉淀 [`docs/design/records-by-track-filter-deferred.md`](../../../docs/design/records-by-track-filter-deferred.md)。建议下次 round 名 `wire-records-by-track`，预计 4-6h 实施 + Room migration。
