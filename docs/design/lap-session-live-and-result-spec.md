# Lap Session Live And Result Spec

这份文档定义圈速测试真正进入 session 后的实时页和结果页信息结构。

视觉参考：

- `docs/design/visual-refs/lap-live-landscape-balanced-v1.png`
- `docs/design/visual-refs/lap-live-landscape-minimal-v1.png`
- `docs/design/visual-refs/lap-live-minimal-v1.png`

## Design Principle

圈速测试实时页服务的是正在开车的用户。

进入圈速测试实时 session 后，页面必须强制横屏。

原因：

- 横屏更适合车载支架和中控位置。
- 横屏能显著放大核心数字。
- 横屏减少纵向滚动和导航干扰。
- 驾驶中用户只适合扫视，不适合阅读竖屏密集信息。

驾驶中用户没有精力阅读复杂信息，所以默认实时页必须极简：

- 一眼能读。
- 大数字优先。
- 不显示车辆速度。
- 不显示 GPS 细节。
- 不显示卫星数、HDOP、刷新率。
- 不显示图表。
- 不显示密集列表。
- 正常状态下不打扰用户。

默认实时页只回答四个问题：

```text
当前第几圈？
这一圈跑了多久？
我比最佳圈快还是慢？
最佳圈是多少？
```

## Live Session Default Template

默认模板名称：

```text
Lap Timing Balanced
```

方向：

```text
Landscape only
```

必须展示：

```text
Lap number
Current lap timer
Delta to best
Last lap
Best lap
```

推荐层级：

1. `Delta to best`
2. `Current lap timer`
3. `Last lap`
4. `Best lap`
5. `Lap number`

推荐横屏布局：

```text
Top strip: LAPS · track name · LAP 4 · tiny Ready · pause/stop controls
2x2 dashboard:
  Delta to best
  Current lap timer
  Last lap
  Best lap
Bottom: Hold to end
```

实时 session 中不要显示 bottom tab bar，也不要保留普通页面导航结构。

不要让 `Current lap timer` 独占视觉中心。驾驶过程中它更多是状态信息，不是唯一行动信息。
默认横屏页应更接近 RaceChrono 的均衡仪表，但保留 Track Tech V2 的视觉语言。

### Current Lap Timer

格式：

```text
M:SS.mmm
```

示例：

```text
1:23.456
```

规则：

- 页面最大数字。
- 跑圈中持续更新。
- 未开始有效圈时显示 `--:--.---` 或 `Waiting for start line`。

### Delta To Best

格式：

```text
+0.42 s
-0.18 s
```

语义：

- 负数表示当前圈快于最佳参考。
- 正数表示当前圈慢于最佳参考。

颜色：

- 快于最佳：green / cyan。
- 慢于最佳：red。
- 无参考圈：muted gray，显示 `--`。

可以展示一条极简 delta bar，但不能抢当前圈计时的视觉中心。

### Best Lap

格式：

```text
M:SS.mmm
```

示例：

```text
1:21.908
```

规则：

- 作为固定参考信息。
- 低于 `Delta to best` 的视觉层级。

### Last Lap

格式：

```text
M:SS.mmm
```

示例：

```text
1:22.184
```

规则：

- 展示上一圈完成成绩。
- 过线后短暂强化，帮助用户确认刚刚一圈结果。
- 未完成第一圈时显示 `--:--.---`。

### Lap Number

格式：

```text
LAP 4
```

规则：

- 小而清楚。
- 不要做成主视觉。

## Live Session Hidden By Default

默认实时页不要展示：

- Vehicle speed。
- GPS ready。
- BLE connected。
- Satellite count。
- HDOP。
- 25Hz。
- Track distance。
- Sector table。
- Lap list。
- Telemetry chart。
- Map replay。

这些信息可以在异常、暂停、详情页或未来自定义模板中出现。

## Normal And Abnormal Status

正常状态下，只允许极小状态提示，例如：

```text
Ready
```

异常状态可以打断主页面，但必须短句明确：

```text
GPS SIGNAL LOST
WAITING FOR GPS LOCK
BLE DISCONNECTED
LAP INVALIDATED
```

异常状态优先级高于默认模板。

## End Session Control

结束 session 是危险操作，必须防误触。

推荐：

```text
HOLD TO END
```

要求：

- 放在底部。
- 红色 outline。
- 长按触发。
- 不抢主计时视觉中心。

## Future Live Templates

RaceChrono 的专业性来自高度自定义。

本 App 未来可以支持实时数据模板，但第一版只实现默认模板。

未来模板候选：

- `Minimal`: current lap, delta, best lap, lap number。
- `Sector`: current lap, sector deltas, best lap。
- `Telemetry`: current lap, delta, speed, G-force。
- `Coach`: delta bar, predicted lap, last sector。

模板选择应发生在测试前，不应要求用户驾驶中配置。

## Session End And Record Detail

圈速测试结束后不强制进入传统结果页。

结束后：

1. 停止 recorder。
2. 保存 session。
3. 显示轻量保存反馈。
4. 默认回到 Laps 首页或 Records/Laps 列表。
5. 可提供 `View Record` 进入记录详情。

一期记录详情属于：

```text
Records > Laps > Session Detail
```

不是 live session 的必经承接页面。

### Phase 1 Record Detail

一期只做 Overview，不做 tabs。

必须展示：

```text
Track name
Session date/time
Best lap
Total laps
Valid laps
Invalid laps
Lap records list
```

如果当前数据可靠，可以展示：

```text
Top speed
Duration
Distance
```

不要展示：

- Theoretical best。
- S1/S2/S3。
- Sector matrix。
- Chart tab。
- Map tab。
- Video tab。
- Lap-vs-lap comparison。

### Lap Records List

一期列：

```text
Lap
Time
Diff
Status
```

如果每圈 top speed 已有可靠字段，可以加：

```text
Top Speed
```

示例：

```text
1   3:47.560   +2:25.652   VALID
2   1:45.940   +24.032     VALID
6   1:21.908   BEST        BEST
8   --:--.---   --         INVALID GPS LOST
```

规则：

- Best lap 必须突出。
- Invalid lap 必须可识别。
- 不要默认展示 RaceChrono 那种高密度全矩阵。
- 不要假造分段和理论最佳。

## Future Analysis Mode

未来可以做独立高级分析模式。

建议入口：

```text
Records > Laps > Session Detail > menu > Analysis Mode
```

Analysis Mode 可以强制横屏，承载：

- Sector timing。
- Theoretical best。
- Lap-vs-lap comparison。
- Speed curve。
- Longitudinal acceleration。
- Lateral acceleration。
- Track map replay。
- Video overlay。

这些不属于一期。

## First Slice Acceptance

第一版可接受范围：

- Laps 首页进入 session。
- 实时页默认模板展示 `Delta / Current / Last / Best / Lap number`。
- 实时页强制横屏。
- 实时页保持亮屏。
- 实时页不展示速度。
- 实时页不展示 GPS 细节。
- 实时页返回手势不会直接退出。
- 支持长按结束 session。
- 结束后保存 session，不强制进入传统结果页。
- Records/Laps session detail 只展示 Overview 和 lap records list。
- 不展示假的 sector/theoretical best/chart/map/video 入口。

如果当前数据能力暂时不足，允许用占位字段，但页面结构必须符合本规格。
