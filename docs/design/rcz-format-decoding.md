# `.rcz` 格式解码备忘

本文档由 OpenSpec change `add-debug-preset-track-boyu-loop` 落地时沉淀，作为
未来再消费此类 `.rcz` 文件（导入新赛道、对比 session 数据等）的权威参考。
对应输入文件登记在 [`docs/tools/input/README.md`](../tools/input/README.md)，
对应离线脚本是 [`docs/tools/decode_rcz_session.py`](../tools/decode_rcz_session.py)。

## 1. 背景

用户从 RaceChrono / Race-Captain 风格的 GPS 圈速记录工具导出 `.rcz` 文件。这是
一个 ZIP 包，结构因导出类型分两种：

| 类型 | 内容 | 用途 |
|---|---|---|
| **track-only** `.rcz` | 仅 `trackId.json` | 单纯的赛道定义（trap 列表 + 名字 + 描述） |
| **session** `.rcz` | `session.json` + `sessionfragment.json` + `trackId.json` + `channel_*` 二进制流 | 一次跑场录制的完整数据：trap 定义 + 多圈 lap 时间窗 + 25 Hz 二进制 GPS 路径 |

session `.rcz` 已自含 `trackId.json`，所以**只要有 session 包**就能离线产出
完整 Track DSL（含 referencePath + gate 几何 + sequenceIndex），无需另传
track-only `.rcz`。

## 2. 坐标 / 角度 / 长度编码

`trackId.json` 内 `traps[]` 数组每个元素是一个计时门：

```json
{
  "name": "起点",
  "centerLatitude": 182977074,
  "centerLongitude": 626599066,
  "bearing": 183000,
  "width": 50000,
  "type": 3,
  "uniDirectional": true
}
```

| 字段 | 编码 | 解码公式 | 说明 |
|---|---|---|---|
| `centerLatitude` / `centerLongitude` | `int32` | `degrees = raw / 6_000_000` | 注意因子是 **6,000,000** 而不是常见的 10^7。等价于"度 × 60（变成角分） × 100,000"。Codex / RaceChrono 的私有约定 |
| `bearing` | `int` | `degrees = raw / 1000` | 单位是 **千分之一度（millidegree）**，罗经方向：0° = 北，90° = 东，180° = 南 |
| `width` | `int` | `meters = raw / 1000` | 单位是 **毫米**。本工程见到的赛道值均为 50 000（= 50 m） |
| `type` | `int` enum | 见下表 | trap 类型 |
| `orderValue` | `int` | 直接读 | 实测全为 0；**不**等于 sector 流向顺序（必须用 GPS 实测过线时间反推） |
| `uniDirectional` | `bool` | 直接读 | 单向通行；本工程见到的均为 `true` |
| `centerLatitude == 2147483647` / `centerLongitude == 2147483647` | sentinel | "未设置" | `Int.MAX_VALUE` 表示 track 整体 center 未指定（仅 traps 各自有有效坐标） |

### `type` 枚举对照

| 值 | 含义 |
|---|---|
| `3` | 起终点（Start/Finish） |
| `4` | sector 计时点 |
| `5` | 其它（pit/位置点等，本 round 暂不消费） |

### 编码因子的反推方法

通过用 TFIC LPCC 的 `.rcz`（导出于同款工具）与现有 `feature/test/src/main/.../PresetTracks.kt`
中已知的 TFIC 坐标做交叉验证：

- TFIC 起终点已知（PresetTracks.kt 中 `startFinishGate.line` 中点）：`(30.49618°, 104.43318°)`
- TFIC `.rcz` 起终点 trap：`centerLatitude = 182977074, centerLongitude = 626599066`
- 试除：`182977074 / 30.49618 = 5_999_994 ≈ 6_000_000` ✓
- `626599066 / 104.43318 = 6_000_003 ≈ 6_000_000` ✓
- 多个 sector trap 反复验证，最大相对误差 < 1e-6，确认编码因子是 6,000,000

bearing / width 单位同样可以用 TFIC 已知 `passDirection` 反推，结论与 RaceChrono
社区文档（millidegree、mm）一致。

未来再处理任何来源的 `.rcz`，都可以走"已知赛道 → 反推因子 → 验证"这一管线
做交叉确认；天投泊寓 `.rcz` 是用本因子直接解出，并用 ESP32 真机当前位置
（成都双流区，距赛道几百米）做了量级验证。

## 3. session `.rcz` binary channel 布局

session `.rcz` 内的 `channel_*` 文件命名遵循：

```
channel_<sessionId>_<deviceId>_<flag>_<channelId>_<sizeFlag>
       └─ "1"     └─"100"    └─"0"  └─ 见下表 └─ "1"=8B / "0"=4B
```

每个 channel 文件是该数据流的连续 sample 序列（**Little-Endian**），sample 数
= 文件大小 / sample width。本 round 在 session_20260108 上实测共 26 667 sample，
跨度 1067 秒，正好对齐 25 Hz × 1067 ≈ 26 675。

| channelId | sizeFlag | sample 解码 | 含义 | 本 round 是否消费 |
|---|---|---|---|---|
| `1` | `1` (8B) | `int64 LE` | UTC ms timestamp | ✓ |
| `2` | `1` (8B) | `int64 LE` | 累计距离（mm，0 起点累加到 `session.json.lengthDistance`） | ✗（未来可作精确距离基线） |
| `3` | `1` (8B) | **2× `int32 LE` packed**：`(latitude_int, longitude_int)` 各 ×6,000,000 | **位置主通道**（8 字节里前 4 字节 lat、后 4 字节 lon） | ✓ |
| `4` | `0` (4B) | `int32 LE` | 待定（19/22/14/31… 类小整数；推测精度/卫星数） | ✗ |
| `5` | `0` (4B) | `int32 LE` | 待定（开机后大段为 466 000 常数；推测高度 mm） | ✗ |
| `6` | `0` (4B) | `int32 LE` | 待定（168 910 常数；推测某种 HDOP × 1000 或方向） | ✗ |
| `30002`–`30005` | `0` (4B) | `int32 LE` | 衍生通道（加速度 / 横向力等）；channel id ≥ 30000 通常为应用层算出的派生量 | ✗ |

> **关键**：channel 3 是 packed 双 int32，在 8 字节里两个 int32 各取 4 字节
> （前 lat、后 lon），不是 int64。直接以 `int64 LE` 解码会得到诸如
> `2_681_709_298_414_767_130` 这种乱值，必须 `struct.unpack("<2i", chunk)`。

## 4. Lap 切片公式

`session.json.laps` 是 lap 元数据数组：

```json
{
  "number": 1,
  "startTimestamp": 1767884378576,
  "finishTimestamp": 1767884538464,
  "isInvalid": false
}
```

最后一个 lap 可能没有 `finishTimestamp`（session 结束时跑到一半）。

要拿到第 N 圈在 channel 3 内的 sample 范围：

```python
ts = read_int64_le_array(channel_1_bytes)          # ascending UTC ms
laps = json.load(open("session.json"))["laps"]
lap_n = next(l for l in laps if l["number"] == N)
i_start = bisect_left(ts, lap_n["startTimestamp"])
i_end   = bisect_right(ts, lap_n["finishTimestamp"])
lap_samples = decode_packed_latlon(channel_3_bytes[i_start*8:i_end*8])
```

本 round Lap 1 跑出 3993 个 25 Hz sample，闭合度（首末点距离）1.9 m，路径
长 2587 m，与 `bestLaptime: 159888` 完全对齐。

## 5. sector 流向顺序反推

`traps[]` 数组顺序与 `orderValue` 字段都**不**等于赛道流向顺序。要拿到正确的
`sequenceIndex`：

1. 取 best lap（或任意有效 lap）的全部 GPS sample
2. 对每个 sector trap，把 trap 转成 gate line（中心点 ± width/2 沿 `bearing-90°`
   方向投影到经纬度）
3. 扫 lap 内连续 sample 对，找最早一对让"相邻 sample 段"与 gate line 相交的
   时刻，记录为该 trap 的"过线时间"
4. 4 个 sector 按过线时间升序赋 `sequenceIndex = 1, 2, 3, 4`

`docs/tools/decode_rcz_session.py` 内置了此逻辑，配合 `--lap N` 参数使用。

## 6. 字段速查表（解码常量）

```python
COORD_SCALE       = 6_000_000        # int32 → 度
BEARING_SCALE     = 1_000            # int → 度
WIDTH_SCALE       = 1_000            # int → 米
COORD_UNSET       = 2_147_483_647    # Int.MAX_VALUE = 未设置
SAMPLE_RATE_HZ    = 25               # session binary 默认采样率（实测）
TYPE_START_FINISH = 3
TYPE_SECTOR       = 4
```

## 7. 参考

- 本 round OpenSpec change：`openspec/changes/add-debug-preset-track-boyu-loop/`
- 历史相关：`docs/RaceChrono_BLE_Protocol.md`（同源工具的实时 BLE 协议，与
  `.rcz` 离线导出格式相互独立但语义同源）
