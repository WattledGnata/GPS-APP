## 1. 协同看板登记（不开 worktree —— scope 太小）

- [x] 1.1 看板 §5 登记本 round：`K. enforce-portrait-orientation`，状态"推进中"。worktree 列填"（不需要）"，分支列填"feature/track-tech-v2 直改"
- [x] 1.2 看板 §6 登记共享文件占用：`app/src/main/AndroidManifest.xml`（独占；ongoing 标记）
- [x] 1.3 grep 当前主区无并行 round 在改 AndroidManifest.xml → 独占确认（I round §6 LapLiveScreen.kt 残留 ongoing 行不影响，本 round 不改 LapLiveScreen 源码）

## 2. AndroidManifest 锁默认 portrait

- [x] 2.1 编辑 `app/src/main/AndroidManifest.xml`：MainActivity 节追加 `android:screenOrientation="portrait"`（line 39）
- [x] 2.2 `grep -n screenOrientation app/src/main/AndroidManifest.xml` 命中 line 39 ✅

## 3. Contract test：MainActivity manifest 防回退

- [x] 3.1 新建 `app/src/test/java/com/blazepush/MainActivityOrientationContractTest.kt`：纯 grep 风格，3 个 case 覆盖 manifest 字面量 / MainActivity 节内 portrait / 防回退（landscape/sensor/sensorPortrait/unspecified/fullSensor 任一都 fail）
- [x] 3.2 测试零 Robolectric / Compose runtime / Android Context 依赖
- [x] 3.3 `./gradlew :app:testDebugUnitTest --tests 'com.blazepush.MainActivityOrientationContractTest'` 通过 ✅

## 4. Contract test：LapLiveScreen orientation 字面量防回退

- [x] 4.1 新建 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreenOrientationContractTest.kt`：4 个 case 覆盖 DisposableEffect / SCREEN_ORIENTATION_LANDSCAPE / SCREEN_ORIENTATION_PORTRAIT / `keepScreenOn = true/false` 字面量
- [x] 4.2 `./gradlew :feature:test:testDebugUnitTest --tests 'com.blazepush.feature.test.ui.tracktech.LapLiveScreenOrientationContractTest'` 通过 ✅

## 5. 编译 + 单测里程碑

- [x] 5.1 `./gradlew :app:testDebugUnitTest` 全绿（含新 `MainActivityOrientationContractTest` 3 cases）
- [x] 5.2 `./gradlew :feature:test:testDebugUnitTest` 全绿（含新 `LapLiveScreenOrientationContractTest` 4 cases + 既有 246 tests 零回归）
- [x] 5.3 `./gradlew :app:assembleDebug` 通过；apk 产出 `app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk`（63 MB）

## 6. 真机验证（华为 8KE0219522008434，需 user 授权）

- [x] 6.1 与 user 确认装机时间，等授权 → 2026-05-02 user 授权（"安装"）
- [x] 6.2 `adb -s 8KE0219522008434 install -r app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk` → Success（Performing Streamed Install）
- [x] 6.3 启动 app → 默认竖屏（Test/Laps/Records/Device 四 Tab 都竖屏）✅
- [x] 6.4 旋转设备到横屏方向 → home 不跟随旋转，保持竖屏 ✅
- [x] 6.5 进 test_execution（点 START TEST）/ gps_details / performance_result（详情屏）/ lap_session_detail → 各页面都竖屏 + 旋转设备不跟随 ✅
- [x] 6.6 Records → LAPS → 进 lap_live → 自动横屏 + keepScreenOn 生效 ✅
- [x] 6.7 lap_live 内旋转设备 → 保持横屏（不切 reverse-landscape）✅
- [x] 6.8 lap_live BackHandler → EndConfirmationDialog 点 End Session → 退出 lap_live 自动恢复竖屏 + keepScreenOn 失效 ✅
- [x] 6.9 user 2026-05-02 反馈"可以"——全部场景通过

## 7. commit + ff-only 合回 + push

- [x] 7.1 主区独立 commit `5bb2164`：`feat(orientation): MainActivity 锁默认 portrait + LapLiveScreen 横屏 contract test`
- [x] 7.2 主区编译确认 `:app:compileDebugKotlin :feature:test:compileDebugKotlin` 通过 ✅
- [ ] 7.3 **需 user 显式确认才能 push**：`git push origin feature/track-tech-v2`（CLAUDE.md「STRICT: Never run `git push` ... without explicit human confirmation」）
- [x] 7.4 看板 §5 状态改 done（commit 5bb2164）；§6 K round 占用 1 行标 done
- [x] 7.5 归档 round：`openspec archive enforce-portrait-orientation --yes`
- [x] 7.6 commit `.openspec.yaml` 锚点：`chore(openspec): 归档 enforce-portrait-orientation 工件目录`

## 8. follow-up backlog（不在本 round 实现）

- [ ] 8.1 `enable-landscape-for-lap-session-detail` — `lap_session_detail` 屏切横屏（看视频 / 轨迹图 / 速度曲线）。**触发条件**：user 说"将来肯定是可以切横屏的，后面不管是加视频还是加图表 都需要横屏，但是现在就没必要"。**实施方式**：复用 LapLiveScreen 的 DisposableEffect 模式，进入时 `SCREEN_ORIENTATION_LANDSCAPE` / 离开 `SCREEN_ORIENTATION_PORTRAIT`
- [ ] 8.2 `enable-landscape-for-performance-result` — `performance_result` 详情屏在用户希望看大图速度曲线 / G-Force 曲线时切横屏（user 未提，但可能未来报"曲线太密看不清"）。**触发条件**：用户反馈
