## Context

见 proposal Baseline。`DeviceHomeScreen` SETTINGS Toast 占位；DataStore Preferences 已在 feature/test（`RecordingPreferencesRepository`/`RecentTracksStore` 双构造先例）。lap-upload `driver` 必填。road-test-first 模式。

## Goals / Non-Goals

**Goals:**
- 本地存一个车手显示名，设置页可填、跨会话保留。
- 填空 DeviceHomeScreen 的 SETTINGS 占位（变成真实设置页）。
- 为 livetiming lap-upload `driver` 字段就绪。

**Non-Goals:**
- 网络上报 / track-delivery（需网络地基，round E/F/G）。
- 从 carModel / 蓝牙名自动取默认（保持最小，打磨项）。
- 其他设置项（单位 / 语音 / 自动重连）——本 round 只放车手名，设置页留扩展。

## Decisions

### Decision 1：用 DataStore Preferences 存（仿 RecordingPreferencesRepository）

`UserProfileRepository`（双构造）存 `driverName: String`，缺值空串。

- **Alternative A（选中）·DataStore Preferences**：已在工程、有双构造先例、singleton 标量正合 key-value、可 JVM 单测。
- **Alternative B·Room**：杀鸡用牛刀（单字段非列表），且牵 schema migration。拒绝。
- **Alternative C·SharedPreferences**：deprecated 方向，工程已转 DataStore。拒绝。

### Decision 2：独立 SettingsScreen route（不做成 overlay）

`SettingsScreen` 是独立 NavHost route（从 DeviceHomeScreen 导航进），非浮层。

- **Alternative A（选中）·独立 route**：设置页是 Device tab 的常规子页（无"实时预览"诉求，不像录制设置需要浮在预览上），独立屏最直接，且未来可扩展更多设置项。
- **Alternative B·overlay**：录制设置用 overlay 是因为要"改参数实时看预览效果"；车手名 / 通用设置无此诉求，overlay 反而不自然。拒绝。

（注：与 `RecordingSettingsOverlay` 的形态差异是**刻意的**——录制设置贴相机预览用浮层，通用设置用独立屏。）

### Decision 3：TextField 本地 draft 防光标跳

`SettingsScreen` 的车手名 TextField 用本地 `draft` state 驱动 value，持久化值加载后 `LaunchedEffect(savedName)` 同步一次（仅 draft 空时）；onValueChange 即时持久化（trim）。

- **Alternative A（选中）·draft + 即时持久化**：避免 Flow 直驱 value 导致重组光标跳；名字短、即时存可接受。
- **Alternative B·Flow 直驱 value**：每次 Flow 重发重组，光标易跳。拒绝。
- **Alternative C·draft + "保存"按钮**：更省写，但多一步交互；名字场景即时存够用。本 round 选即时存（可后续加保存按钮）。

## Risks / Trade-offs

- **[Risk 1] 即时持久化每键一写** → 名字输入低频、DataStore 异步，可接受；若未来嫌频繁可改 onValueChangeFinished/防抖。
- **[Risk 2] 空车手名** → 本 round 允许空（livetiming 上报是后续 round，上报前由 G round 校验非空 + 引导填）。
- **[Trade-off] 不自动取默认名** → 用户需手填一次；打磨项（从 carModel/蓝牙名预填）留 follow-up。

## Migration Plan

无 schema migration（DataStore 新增 key，旧安装缺 key → 空串）。Rollback：移除 SettingsScreen route + DeviceHomeScreen 改回 Toast 即可，无数据损坏。

## Open Questions

无。默认名预填 = 非阻塞 follow-up。
