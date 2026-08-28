## 1. 锚点 verify（apply 前 grep 对齐 — v3 #3 自查）

- [x] 1.1 grep `mainPresets` 在 `feature/test/src/main/.../repository/PresetTracks.kt:19` 命中一次（list 定义点），结构与 design Decision 4 描述一致（既有 TFIC 在 list 第 0 位）
- [x] 1.2 grep `preset-tfic-lpcc` 在 `PresetTracks.kt` 命中一次（id 字面量），用作"TFIC 之后追加 XIC"位置锚点
- [x] 1.3 grep `preset-xic-lpcc` 在 main / debug / release 源集 0 命中（baseline 待新增）
- [x] 1.4 grep `assertEquals(listOf("preset-tfic-lpcc"), ids)` 在 `feature/test/src/testRelease/.../TrackCatalogReleaseVariantTest.kt` 命中一次
- [x] 1.5 grep `assertEquals(listOf("preset-tfic-lpcc", "preset-boyu-loop"), ids)` 在 `feature/test/src/testDebug/.../TrackCatalogDebugVariantTest.kt` 命中一次

## 2. 实施 — 加 XIC Track entry 到 mainPresets

- [x] 2.1 `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt` — 在 mainPresets list 内 TFIC entry 后追加一条 `Track(id = "preset-xic-lpcc", ...)`：
  - `name = TrackName(zh = "厦门国际赛车场", en = "Xiamen International Racetrack", abbr = "XIC")`
  - `lengthKm = 1.662`
  - `thumbnailAssetPath = null`
  - `referencePath = TrackPath(points = listOf(...))` 共 16 个 GeoPoint（15 等距采样 + 1 闭合回起点），坐标由 design Decision 1 算出：
    ```
    GeoPoint(24.6546828, 118.3154782),  // 0  起点 S/F 对齐
    GeoPoint(24.6552135, 118.3164043),  // 1
    GeoPoint(24.6546617, 118.3168557),  // 2
    GeoPoint(24.6536902, 118.3166453),  // 3
    GeoPoint(24.6531167, 118.3157807),  // 4
    GeoPoint(24.6523408, 118.3151843),  // 5
    GeoPoint(24.6522182, 118.3142233),  // 6
    GeoPoint(24.6529005, 118.3149722),  // 7
    GeoPoint(24.6535662, 118.3157582),  // 8
    GeoPoint(24.6542783, 118.3163143),  // 9
    GeoPoint(24.6540497, 118.3154542),  // 10
    GeoPoint(24.6533417, 118.3146808),  // 11
    GeoPoint(24.6527822, 118.3138377),  // 12
    GeoPoint(24.6535010, 118.3137048),  // 13
    GeoPoint(24.6540982, 118.3145875),  // 14
    GeoPoint(24.6546828, 118.3154782)   // 15 闭合
    ```
  - `startFinishGate = TimingGate(id = "start-finish", name = "起点", type = TimingGateType.StartFinish, line = GeoLine(start = GeoPoint(24.6544286231580, 118.3156752761548), end = GeoPoint(24.6549747101753, 118.3152387238452)), passDirection = GeoVector(x = 0.0002225331396, y = 0.0001469463131), sequenceIndex = 0, minDirectionalSpeedMps = null)`
  - `sectorGates = listOf(TimingGate(s1...), TimingGate(s2...))`：
    - s1: `line = GeoLine(start = GeoPoint(24.6524060479335, 118.3147959732798), end = GeoPoint(24.6519949520665, 118.3149973600536))`, `passDirection = GeoVector(x = -0.0002512853751, y = -0.0001016841608)`, `sequenceIndex = 1`
    - s2: `line = GeoLine(start = GeoPoint(24.6540243516169, 118.3150808585765), end = GeoPoint(24.6537006483831, 118.3154248080901))`, `passDirection = GeoVector(x = -0.0001978659847, y = -0.0001736645926)`, `sequenceIndex = 2`
  - 完整 entry 风格跟 TFIC 一致（缩进、行宽、字段顺序）

## 3. 同步 variant 测试断言

- [x] 3.1 `feature/test/src/testRelease/.../TrackCatalogReleaseVariantTest.kt:31` — 断言 `assertEquals(listOf("preset-tfic-lpcc"), ids)` 改为 `assertEquals(listOf("preset-tfic-lpcc", "preset-xic-lpcc"), ids)`；更新顶部注释（描述新列表）
- [x] 3.2 `feature/test/src/testDebug/.../TrackCatalogDebugVariantTest.kt:33` — 断言 `assertEquals(listOf("preset-tfic-lpcc", "preset-boyu-loop"), ids)` 改为 `assertEquals(listOf("preset-tfic-lpcc", "preset-xic-lpcc", "preset-boyu-loop"), ids)`；更新顶部注释 + test 方法名（"exposesTficAndBoyuLoopInOrder" → "exposesTficXicLpccAndBoyuLoopInOrder"）

## 4. 编译 + grep gate + 单测

- [x] 4.1 `./gradlew :feature:test:testReleaseUnitTest` 通过（含修改后的 release 断言）
- [x] 4.2 `./gradlew :feature:test:testDebugUnitTest` 通过（含修改后的 debug 断言）
- [x] 4.3 `./gradlew :app:assembleDebug` 通过
- [x] 4.4 grep `preset-xic-lpcc` 命中：PresetTracks.kt 1 次 + TrackCatalogReleaseVariantTest.kt 1 次 + TrackCatalogDebugVariantTest.kt 1 次（共 3 次）
- [x] 4.5 grep `preset-xic-lpcc` 在 `ExtraPresetTracksDebug.kt` + `ExtraPresetTracksRelease.kt` 0 命中（spec 反例 scenario 锁定 — XIC 不进 extraPresetTracks）

## 5. apk 产出 + user 真机验证

- [x] 5.1 apk 落盘 `app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk`，告诉 user 准备装机
- [x] 5.2 user 在赛道选择/管理界面看到 XIC 进入列表（华为 8KE0219522008434 / vivo V2405A）
- [x] 5.3 user 选 XIC 跑一圈圈速：起点过线 → s1 → s2 → 起终点 切换正常，圈时无异常
- [x] 5.4 user 验证 referencePath 在 TrackPolylineMap 渲染形状跟 vbo lap=002 轨迹大致一致

## 6. push 顺序（user 拍板）

- [x] 6.1 真机验证通过后准备 commit；本 round 涉及 3 文件 + 工件目录，单 commit
- [ ] 6.2 user 决定何时 push（可能跟前 round fix-lap-detail-ux 累计起来一起 push）

## 7. 归档（push 后）

- [x] 7.1 metrics.yaml 写入（`review_mode: "road-test-first"` + `review_rounds_l1/l2: 0` + FileLogger 锚点摘要 N/A + design drift 透明声明）
- [x] 7.2 `openspec archive add-preset-track-xic`

## 10. follow-up backlog

- XIC thumbnail asset：当前 `thumbnailAssetPath = null` 导致 UI select track / 历史 session 缩略图位空白；后续 user 提供赛道俯视图 png（参考 `chengdu_tianfu.png` 风格）放到 `feature/test/src/main/assets/track_thumbnails/xiamen_xicLpcc.png` + 改 entry。轻量 round 即可（仅 1 字段修改）。
- 若 user 后续反馈 lengthKm 1.662 跟官方数据偏差（XIC 官方查证为不同 layout，如 full circuit vs short circuit），可起轻量 round 修改字面量。
- 若 user 期望 XIC debug-only 隔离（不进 release 包），起 round 把 XIC 从 mainPresets 移到 extraPresetTracks()（同 design D4 alternative A）。
