## Context

**现状**（2026-06-18）：
- `PresetTracks.kt:19 mainPresets` list 唯一 entry 为 TFIC（`preset-tfic-lpcc`），所有 variant 共用
- debug variant 额外通过 `extraPresetTracks()`（src/debug/.../ExtraPresetTracksDebug.kt）追加天投泊寓环线（preset-boyu-loop）
- release variant 的 `extraPresetTracks()` 返回 emptyList，最终 release 包仅 TFIC
- `track-catalog-hot-start` capability spec 锁定 variant 行为（archived round `add-debug-preset-track-boyu-loop` design D5 落地）
- 用户提供的两个外部数据源：
  - vbo（`session_20260530_1340.vbo`）：RaceLogic VBO 格式 25Hz × 31157 行 × 15 个 laps；其中 lap=002 是稳定 fast lap（70.3s / 1758 samples / 累计 1662.0m）
  - rcz（`track_厦门国际赛车场_的副本.rcz`）：RaceChrono trap JSON 含 3 个 traps（Start/Finish + Split1 + Split2），仅含计时门元数据无几何

**约束**：
- CLAUDE.md memo `project_track_authoring_model_assisted`：".rcz 仅含云端 id 无几何；非标准来源喂 CC 直接产 Track(...) 块，passDirection 确定性计算，不建固定工具"
- V2 视觉约束（TrackName 三字段 + UI fallback）保留
- 不引入新 module / 新 capability / 不改公共协议 / 不改 Room schema → small 复杂度
- 加速通道 + road-test-first 模式（user 明早后真机验证补）

**stakeholders**：用户单一；CC 主会话 Opus 起草工件 + 实施代码 + 算几何数据。

## Goals / Non-Goals

**Goals:**

- 把 XIC 加为 main-variant 预置赛道（release/debug 都可见），用户选 XIC 直接能跑圈速
- referencePath / startFinishGate / sectorGates 数据**确定性可算**（任何人按本 design 的算法 + 同 vbo/rcz 输入复跑都得相同浮点值）
- 不破坏既有 TFIC scenario 锁定（mainPresets[0] 仍是 TFIC + TFIC 坐标契约不动）
- 不破坏天投泊寓 debug-only 隔离（XIC 进 mainPresets，跟 boyu 处于不同 layer）

**Non-Goals:**

- 不引入 vbo / rcz 解析工具/库（按 memo "不建固定工具"）；本 round 的几何数据由 CC 在本 design 中确定性算出后直接写为 Track(...) 常量字面量
- 不动 LapTimingEngine 算法（gate × ray + passDirection 点积复用，不变）
- 不为 XIC 准备 thumbnail（`thumbnailAssetPath = null`，UI fallback 已覆盖；thumbnail 制作走 follow-up）
- 不动 ReplayAlignedTrackCatalog（XIC 是静态 preset，不进 replay 拟合 cache 路径）
- 不为 XIC 准备额外的圈速基线（best-lap reference 走运行时记录路径）

## Decisions

### Decision 1：referencePath 从 vbo lap=002 等距采样 15 点

**选择**：从 vbo lap=002（25Hz × 1758 samples / 1662.0m / 70.3s fast lap）中**按累计 haversine 距离等距**采样 15 个点作为 `referencePath.points`。起点对齐 rcz Start/Finish 中心：先在 lap=002 内找距 rcz S/F 中心最近的 sample 作为 sample[0]，再 roll 数组使 sample[0] 位于列表头。

**Rationale**：
- lap=002 是稳定 fast lap（70.3s 跟 003/004/005/006 都在 70.2-70.6s 区间，差异 < 1%），代表赛道几何
- haversine 等距采样确保 referencePath 形状均匀，不偏向高速直线或低速弯心
- 15 点（TFIC 13 点 + 闭合点 = 14 总 entry；本 round 15 点 + 闭合点 = 16 entry）量级一致，避免点数过少导致弯道几何粗糙
- 起点对齐 S/F 让用户在 TrackPolylineMap 看到的"起点"跟实际起跑线一致

**Alternatives 考虑**：

- (A) 取所有 1758 个 sample 作为 referencePath：精度最高但 `Track` 数据膨胀 ~117 倍；运行时几何计算（gate 相交 / map 渲染）不需要这么细。**拒绝**。
- (B) 按时间等距采样（每 4.7s 一个点）：高速段路径点稀疏（直线 250m+ 之间无中间点），低速弯心密集；几何形状不均。**拒绝**。
- (C) 用其他 fast lap（如 lap=003 / 004）：等价选择，但 lap=002 已经是 first lap=002，文档可读性最强。任选其一即可。**接受 lap=002 作为锚点**。
- (D) 不对齐 S/F，直接用 lap=002 原始头部：会导致 referencePath 起点在 S/F 之后（lap 列切换发生在过线后下一个 sample），跟 startFinishGate 在视觉上偏移。**拒绝**：UI 期望起点 = S/F。

### Decision 2：trap GeoLine 端点用 bearing + width/2 right-perpendicular 算出

**选择**：trap（rcz 含 center / bearing / width）转 `TimingGate.line` 时，把 line 端点定义为：

```
right_perp = bearing + 90°（车头右侧水平方向）
end_a = (center_lat + (width/2) × cos(right_perp) / 111111, center_lon + (width/2) × sin(right_perp) / (111111 × cos(lat)))
end_b = (center_lat - (width/2) × cos(right_perp) / 111111, center_lon - (width/2) × sin(right_perp) / (111111 × cos(lat)))
GeoLine(start = end_a, end = end_b)
```

球面常数 1° = 111111m，本 round 用浮点 `111111.0`（不区分 nautical / geodetic 1° = 111319.5m 的 0.2% 差异——本 round 几何精度要求 ±1m 内即可）。

**Rationale**：
- trap 的 line 是赛道宽度方向（垂直于车流），端点 = center ± width/2 × right_perpendicular
- 用 right_perp（bearing + 90°）而非 left_perp（bearing - 90°），是惯例选择；line.start / line.end 顺序在本 round 内自洽即可（LapTimingEngine 用 line × ray 相交不依赖端点顺序）
- 球面常数 111111m 已经覆盖纬度 24.65° 的实际地理距离（误差 < 0.1%）

**Alternatives 考虑**：

- (A) 用更精确的 geodetic 投影（WGS84 椭球）：本 round 几何精度过剩，引入计算复杂度无收益。**拒绝**。
- (B) 把 line 端点设为 center ± width × bearing_unit_vector（沿 bearing 方向延伸 width）：line 会变成"沿车流方向的一段"而非"垂直车流的门线"——LapTimingEngine 几何会判定不出过线。**拒绝**（语义错误）。
- (C) 把 line 端点设为 vbo 中实际 cross trap 时的 GPS 点：依赖 lap=002 的 GPS 噪声，每次 trap 算出的 line 不一致；丧失"trap = 几何契约"的纯函数语义。**拒绝**。

### Decision 3：passDirection magnitude 跟 TFIC 0.0002° 同量级，方向 = bearing 单位向量

**选择**：`passDirection = GeoVector(x = sin(bearing) × MAG / cos(lat), y = cos(bearing) × MAG)`，其中 `MAG = 0.00025°`（跟 TFIC 起终点 magnitude ~0.00026° 同量级）。

**Rationale**：
- LapTimingEngine 用 `passDirection` 跟 sample velocity 向量做点积判过线方向（点积 > 0 = 正向通过），方向是唯一关键信息，magnitude 仅作"小步长"避免数值精度损失
- TFIC 的 passDirection x/y 量级 ~0.0002（约 22m），本 round 用 0.00025（约 28m）落在同量级
- 用 bearing 单位向量 × MAG 让 passDirection 跟 trap 的 bearing 完全对齐（rcz 数据是真相源）

**Alternatives 考虑**：

- (A) 用 vbo 中实际 cross trap 的 GPS 前后两 sample 算 velocity 向量：依赖 lap=002 GPS 噪声，每次 round 算出的 passDirection 不一致；丧失"trap 元数据 = passDirection 真相源"语义。**拒绝**。
- (B) magnitude 用 1.0（单位向量）：跟 TFIC 量级差 5000 倍，可能让 LapTimingEngine 内部某些 epsilon 阈值或归一化逻辑表现不一致；不稳。**拒绝**：跟现有 entry 保持同量级最安全。
- (C) passDirection.x 按 bearing 朝东即 +x（lon delta）、bearing 朝北即 +y（lat delta）：跟 TFIC convention 一致。本 round 沿用。**接受**。

### Decision 4：XIC 放 mainPresets（所有 variant 可见），spec MODIFIED scenario

**选择**：把 XIC Track entry 追加到 `PresetTracks.kt:19 mainPresets` list（TFIC 之后），让 release 和 debug variant 都可见 XIC。不放 `extraPresetTracks()`（那是 debug-only）。

`track-catalog-hot-start` capability spec 两个 variant scenario MODIFIED：
- "release variant 仅含 TFIC" → "release variant 含 [TFIC, XIC]"
- "debug variant 额外含天投泊寓" → "debug variant 含 [TFIC, XIC, 天投泊寓]"
- 既有 mainPresets[0] = TFIC 不变契约保留（XIC 是 mainPresets[1]）

`TrackCatalogReleaseVariantTest` + `TrackCatalogDebugVariantTest` 断言同步更新。

**Rationale**：
- user 没说 "XIC debug-only"；user 是真实赛道用户，期望 release 也能用 → mainPresets
- mainPresets 拼接顺序 `mainPresets + extraPresetTracks()` 已经锁定，加 XIC 到 mainPresets 末尾 (index 1) 不破坏 TFIC[0] 契约
- variant scenario 是行为契约（不是实现细节），MODIFIED 同步是 spec-driven 必须

**Alternatives 考虑**：

- (A) XIC 放 extraPresetTracks() debug-only：release 用户用不到 XIC——跟 user 意图相悖。**拒绝**。
- (B) XIC 放 mainPresets[0] 把 TFIC 推到 [1]：破坏现有 `mainPresets[0] = TFIC` 契约（既有 TrackCatalogTest 没显式断言但 design D5 锁定）+ 用户原有 TFIC 用户在 UI 第一位看到 XIC 会困惑。**拒绝**：在 list 末尾追加最安全。
- (C) 用 ADDED Requirements 而非 MODIFIED：ADDED 不替换既有 scenario，但既有"release variant 仅含 TFIC" scenario 会被违反（XIC 真实出现在 release variant）；spec 自相矛盾。**拒绝**：必须 MODIFIED。

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| vbo 经度符号 convention 弄反（negative=West 误读为 negative=East 或反之）→ XIC 出现在地球另一侧 | rcz Start/Finish 中心点（24.6547017° N, 118.3154570° E）跟 vbo lap=002 起点附近 sample（24.6547407°, 118.3155663°）差异 < 50m → 同一地理位置，证明经度解码正确。已用 python 实测 |
| trap right_perp 朝向（+90° vs -90°）选择导致 GeoLine.start / end 顺序在 LapTimingEngine 内不一致 | LapTimingEngine 用 line × ray 相交（参数化形式）不依赖端点顺序；passDirection 点积判方向也不依赖 GeoLine 端点顺序——两者独立。本 round 用 +90°（right_perp）作为约定 |
| referencePath 等距采样起点对齐 S/F 时，rolled 数组拼接（local_start 之后 + 之前）让最后一段 cumDist 跨过 lap 切换 → 距离不连续 | lap=002 整段属于同一圈（lap 列值不变），roll 在同 lap 内 → 相邻 GPS 点距离 < 0.5m，无大跳。已用 python 实测 |
| `thumbnailAssetPath = null` 导致 UI 在 select track / 历史 session 缩略图位渲染空白 | Track model 字段是 `String? = null`，UI 现有 fallback 已覆盖 null（详见 `track-presentation` 现有 requirement）；XIC 看到无缩略图但不崩。follow-up 加 thumbnail asset |
| 加 XIC 后 mainPresets[0] 仍是 TFIC，但 mainPresets[1] = XIC 让 `add-debug-preset-track-boyu-loop` round 锁的"debug variant 顺序 [TFIC, boyu]"失效 | 本 round MODIFIED 该 scenario 为 [TFIC, XIC, 天投泊寓]；BoyuLoopPresetTest（仅断言 boyu entry 本身字段）不变 |
| road-test-first 模式跳 Codex review → 实施期潜在 bug 只能靠真机攒批兜底 | (1) CC §A 自审一遍（设计骨架 + decisions alternatives + scope 闭环） (2) 几何数据 python 算出已多次复核（rcz 中心 vs vbo 实际位置交叉验证；total 距离 vs 其他 lap 一致性 < 2%） (3) 真机由 user 明早补 |

## Migration Plan

- 改动均在 feature/test module（无 schema migration / 无协议变更 / 无 DI 改动）
- 部署：apply 后 gradle 编译 + 单测（testRelease + testDebug variant 都跑）+ 装 apk 真机验证
- 回滚：单 commit `git revert` 即可；catalog 数据加法，无下游副作用

## Open Questions

无。三个关键选择 user 已拍板（trackId=preset-xic-lpcc / lengthKm 从 vbo 算 / XIC 加 mainPresets 让 release 也可见，最后一点由本 design D4 推断；如 user 后续期望 debug-only 可起 follow-up round 移到 extraPresetTracks）。
