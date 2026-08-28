# Track Tech V2 CC Guidance

这份文档替代之前的视觉资产 handoff。

我们已经决定：不要再使用那些从效果图“反推”出来的伪资产。
当前只保留当前认可的 V2 效果图作为视觉方向参考。

## Important Boundary

AI 效果图不是结构化设计稿。

它们不能提供：

- 精确图层。
- 可复用 Figma 组件。
- 稳定 SVG 图标资产。
- 可直接切图的按钮/面板。
- 完全可控的字体、阴影、纹理。

所以本次实现目标不是像素级复刻。

本次实现目标是：

- App 大结构正确。
- 页面职责正确。
- 交互路径正确。
- 信息层级正确。
- Track Tech V2 的视觉感觉接近。
- 后续可以基于真实截图继续修细节。

## Visual Source

只看这些参考图：

- `docs/design/visual-refs/four-tabs-v2-calmer.png`
- `docs/design/visual-refs/test-home-v2-calm.png`
- `docs/design/visual-refs/device-home-v2-calm.png`
- `docs/design/visual-refs/records-performance-laps-v2.png`
- `docs/design/visual-refs/lap-live-landscape-balanced-v1.png`
- `docs/design/visual-refs/lap-live-landscape-minimal-v1.png`
- `docs/design/visual-refs/ble-scan-sheet-v2-calmer.png`

不要参考已删除的 `track-tech-assets` 目录。
如果工作区里出现旧资产、旧 handoff、旧 token 文件，不要使用。

## Product Structure

底部四个 tab：

```text
Test | Laps | Records | Device
```

职责：

- `Test`: 加速/制动性能测试入口。
- `Laps`: 圈速测试入口，围绕当前赛道。
- `Records`: 历史记录和专业图表，内部拆成 Performance / Laps 两个子 tab，首版可以骨架化。
- `Device`: BLE GPS 连接、设备状态、GPS 可用性解释。

`Device` 是全局连接入口。
Test/Laps 如果设备或 GPS 不可用，应引导用户进入 Device，而不是各自复制连接流程。

## First Implementation Scope

首版建议只做这个切片：

1. 四 tab shell。
2. Track Tech 基础 Compose 组件。
3. Device tab 首页。
4. BLE scan bottom sheet。
5. Test/Laps 未连接 gating 到 Device。
6. Test/Laps/Records 先做能承载结构的首页骨架，其中 Records 必须先具备 Performance / Laps 子 tab。

暂缓：

- 像素级还原。
- 最终字体文件。
- 最终 icon 体系。
- 完整 Records 图表。
- 完整 Laps 赛道选择体验。
- 高级 GPS Details / Diagnostics 密集页。

## Visual Intensity

不要四个 tab 都用力过猛。

强度分层：

- `Test`: 中强度。
- `Laps`: 中强度。
- `Records`: 低强度，专业图表优先。
- `Device`: 低强度，连接控制台优先。
- `BLE scan bottom sheet`: 低强度工具弹层。
- `Execution`: 后续可以高强度 HUD。

原则：

- Test/Laps 可以酷。
- Records/Device 要稳。
- 弹层/详情/设置要克制。

## Color And Graphic Style

Track Tech V2 的视觉关键词：

```text
黑色赛车仪表盘
低饱和石墨面板
切角机械卡片
细灰描边
克制紫色高亮
cyan 遥测线
绿色 ready 状态
红色 braking/error 状态
少量斜线/网格/HUD 点缀
```

### Color Style

整体底色必须偏黑、偏石墨，不能做成大面积紫色 App。

主色关系：

- 黑/石墨：承载绝大多数背景和面板。
- 紫色：只用于主行动、当前 tab、选中态、少量渐变强调。
- cyan：用于 GPS、BLE、轨迹、图表线、遥测感。
- green：只表达 ready、connected、good。
- red：只表达 braking、cancel、failed、blocked。
- 白/浅灰：用于主要文字和指标。

视觉比例建议：

```text
黑/石墨 70%
文字灰白 15%
紫色 8%
cyan 4%
green/red 3%
```

这是感觉上的比例，不是精确数值。目标是克制、耐看。

### Graphic Style

图形风格应该来自组件本身，而不是铺满背景图。

应该出现：

- 切角面板。
- 1dp 细描边。
- 选中态紫色细边。
- 小面积紫色渐变按钮/卡片。
- 细网格，只放在 hero、track、chart 等内容容器内部。
- 少量斜线装饰，放在 section header 或卡片角落。
- cyan 赛道线、速度曲线、信号/遥测线。
- 简洁线性 icon。

不应该出现：

- 大面积模糊紫色渐变背景。
- 每张卡片都有强发光。
- 玻璃拟态。
- 普通 Material 大圆角卡片。
- 复杂插画背景。
- 从 AI 效果图裁出来的按钮/面板。
- 让 Records/Device 也像执行页一样高强度。

### Screen-Specific Style

`Test`

- 可以有速度 hero 和紫色 0-100 主卡。
- 但背景保持干净，右侧道路线条只是轻装饰。

`Laps`

- 赛道图是视觉中心。
- cyan track line 可以亮一点，但周围面板保持克制。
- 一期圈速 session 需求以 `docs/product/lap-session-phase1-requirements.md` 为准。
- 进入圈速 session 后遵守 `docs/design/lap-session-live-and-result-spec.md`。
- 进入圈速 session 后必须强制横屏，不显示 bottom tab bar。
- 实时默认模板只展示相对最佳差值、当前圈计时、上一圈、最佳圈、当前圈号。
- `Current lap` 不要独占视觉中心；横屏实时页应采用更均衡的仪表布局，圈号只做小 badge。
- 实时默认模板不要展示速度、GPS 细节、卫星数、HDOP、刷新率或图表。

`Records`

- 图表优先。
- plot 区域要干净：细网格、清晰曲线、少发光。
- 内部必须有 `Performance | Laps` segmented control。
- `Performance` 只展示加速/制动性能记录，例如 best 0-100、best braking、total runs、速度曲线、recent runs。
- `Laps` 只展示圈速记录，并且必须带赛道上下文，例如 current track、track selector、best lap、sessions、total laps、session history。
- 圈速记录不要和性能测试记录混在同一个列表里。

`Device`

- 像连接控制台。
- 重点是 ready/connected 状态和可操作性，不要堆满诊断指标。

`BLE Scan Sheet`

- 工具型底部弹层。
- 只有推荐/选中设备行可以有紫色边框。
- 其他行安静。

## Native Compose Direction

请用原生 Compose 实现，不要切图复刻。

赛道缩略图例外：可以直接使用 `docs/design/track-thumbnails-v2/transparent/*.png` 作为真实赛道形状素材。
这些 PNG 来自已有赛道图批处理，已经统一成 Track Tech V2 的 cyan 线框风格。
Laps 首页、Records/Laps 子 tab、赛道选择卡片都优先复用这批素材，不要从效果图里裁赛道。
如果已有高清 PDF 派生资产，App 内优先使用 `feature/test/src/main/assets/track_thumbnails/*.png` 的透明底小缩略图，例如 `track_thumbnails/chengdu_tianfu.png`。
高清派生源和大尺寸版本保留在 `docs/design/track-thumbnails-v2/highres/`，不要直接把暗底 preview 图作为 production 素材。

优先实现这些基础组件：

- `TrackTechTheme`
- `CutCornerPanel`
- `TrackTechBottomNav`
- `TrackTechStatusStrip`
- `PrimaryActionPanel`
- `SecondaryActionPanel`
- `MetricNumber`
- `MetricTile`
- `BleScanBottomSheet`

### CutCornerPanel

大部分卡片、按钮、bottom nav selected item、sheet 容器都用切角面板。

实现方式：

- Compose `GenericShape` / `Path`
- 背景色：深黑/石墨
- 边框：细灰线
- active/selected：紫色细边或轻微紫色填充

不要用普通大圆角 Material Card 作为主视觉。

### Bottom Navigation

四个 tab 固定：

- Test
- Laps
- Records
- Device

当前 tab:

- 切角高亮
- 紫色边框/低透明填充
- 不要强发光

### Status Strip

Test/Laps 顶部用 compact status strip。

常见项：

- GPS ready
- BLE connected
- 25Hz
- Good signal / Quality Good

点击 status strip 可以进入 Device。

### Device Home

参考：

- `device-home-v2-calm.png`

首页只放轻量指标，不做密集调试页。

推荐结构：

```text
Device
  Readiness Hero
    READY TO TEST / CONNECT GPS DEVICE / WAITING FOR GPS LOCK
    GPS locked · BLE connected
    25Hz · Quality Good

  Quick Status Row
    BLE Connected
    SATS 12
    RATE 25Hz

  Connected Device
    RaceChrono GPS
    Ready for Test
    Scan / Disconnect

  GPS Details Entry
    Quality Good · 12 sats · 25Hz

  Diagnostics
  Settings
```

### BLE Scan Bottom Sheet

参考：

- `ble-scan-sheet-v2-calmer.png`

它是工具弹层，不是主舞台。

要求：

- 从底部弹出。
- 背景 Device 页压暗。
- 列表行清晰。
- 只有选中/推荐设备有紫色边。
- 其他设备行保持安静。
- 提供 `CONNECT` / `SCAN AGAIN` / close。

状态：

- scanning
- found devices
- empty
- connecting
- failed

如果当前工程暂时缺少 selected device / failed reason，可以先用最小状态实现，后续补。

## Color Guidance

不需要先做完整 token 文件，但颜色方向如下：

```text
Background      #07080D
Surface         #11131C
Surface dark    #0B0D13
Border          #303442
Purple          #9B5CFF
Deep purple     #5B2AA8
Cyan            #67E8F9
Green           #76D05E
Red             #F25F5C
Text primary    #ECECF2
Text secondary  #A5A6B1
Text muted      #70727E
```

使用规则：

- 紫色只用于主行动、当前态、选中态。
- cyan 用于 GPS/BLE/轨迹/图表线。
- green 用于 ready/connected/good。
- red 用于 braking/cancel/error。
- 大面积底色保持黑/石墨。

## Typography Guidance

首版不要卡在最终字体上。

先用系统字体模拟三种角色：

`RacingTitle`

- 页面标题、section title、主操作标题。
- SansSerif + ExtraBold + Italic。
- 大写 label 可适当 letter spacing。

`Metric`

- 速度、时间、成绩、卫星数、频率。
- 先用 SansSerif + Black。
- 后续再替换七段数码字体。

`UiText`

- 普通说明、设置、列表、状态。
- 系统 sans。

不要把 metric 字体用于正文。

## Layout Guidance

参考宽度：

- 360dp 手机。

建议：

- 页面左右 padding: 16dp。
- 小屏可降到 12dp。
- 内容最大宽度：420dp。
- bottom nav 高度约 68dp。
- touch target 至少 48dp。
- tab 内容可滚动。
- bottom nav 固定。
- bottom sheet 独立滚动。

## What To Reuse From Current App

从功能探针看，当前工程已有：

- GPS/BLE 共享状态：`GpsDataViewModel`
- 扫描状态：`isScanning`
- 扫描结果：`scanResults`
- 连接动作：`connectDevice`
- GPS 数据：`GpsData`
- 质量评估：`DataQuality`
- 当前线性导航和测试/圈速/历史基础页面。

请优先复用这些数据流，不要重写底层能力。

## What Not To Do

- 不要恢复 `docs/design/track-tech-assets` 里的旧伪资产。
- 不要从效果图裁按钮、卡片、图标。
- 不要一次性重做所有 Records/Laps 复杂细节。
- 不要让 Records/Device 像执行页一样高强度。
- 不要把连接流程散落在 Test/Laps 里。
- 不要用普通 Material 大圆角卡片作为最终视觉。

## Acceptance For First Slice

首版完成后，应满足：

- 四个 tab 可以稳定切换。
- Device tab 是连接入口。
- BLE scan bottom sheet 可以打开、扫描、选择设备。
- Test/Laps 在未连接或未 ready 时会引导到 Device。
- 页面整体接近 V2 参考图的视觉强度。
- 大结构正确，即使局部 icon/字体/装饰还需要后续调。

实现后请截图：

- Test tab
- Laps tab
- Records tab
- Device tab
- BLE scan sheet

然后再根据截图和 V2 参考图逐项优化视觉细节。
