# `.rcz` 实施输入登记

本目录由 OpenSpec change `add-debug-preset-track-boyu-loop` 落地时建立，
作为离线 `.rcz` 解码脚本（`docs/tools/decode_rcz_session.py`）的可复现输入。

## 文件清单

| 文件 | sha256 | 大小 | 用途 |
|---|---|---|---|
| `track_天投泊寓环线.rcz` | `ca9fc2c3a59750e4e2c02b0183e2aa3b7bfdfe608ec822c7afae53ab5e816343` | 485 B | 仅供 `.rcz` 格式参考与未来对比；本 round **不直接消费**（所需信息已自含在下方 session 包内的 `trackId.json`） |
| `session_20260108_225454_天投泊寓环线.rcz` | `666b501cb2d074cfae8948af7ca1dc925f152937b79b38b492a7a53cb1d1fd21` | 290 494 B | **本 round 实际使用**。同赛道 4 圈 25 Hz 实测，含完整 GPS 路径 + 起终点/sector 几何 + best lap 时间窗，供脚本一键产出 Track DSL |

## 来源描述

用户从 RaceChrono / Race-Captain 风格的 GPS 数据采集与分析工具导出，2026-05 经
微信传至 CC 本机。`.rcz` 是一个 ZIP 包，内含：

- `track_*.rcz`：单文件 `trackId.json`（赛道 trap 定义，5 个计时门）
- `session_*.rcz`：`session.json` + `sessionfragment.json` + `trackId.json`
  + 10 个二进制 channel 文件（`channel_<sessionId>_<deviceId>_<flag>_<channelId>_<sizeFlag>`）

## 隐私与公开范围

含 GPS 坐标，定位到成都市双流区雅州路一处万科泊寓项目内的小型环线，已属公开
可用范围（非住宅精确定位、非用户身份关联），无需脱敏。落 git 进 feature 分支
(`feature/track-tech-v2`) 与未来 develop / release tag 完全可接受。

## 用法

由 `docs/tools/decode_rcz_session.py` 消费：

```bash
python3 docs/tools/decode_rcz_session.py \
    docs/tools/input/session_20260108_225454_天投泊寓环线.rcz \
    --lap 1 \
    --resample-step-m 30 \
    --out docs/tools/output/decode_rcz_session_boyu_loop.txt
```

详见 `docs/design/rcz-format-decoding.md`。

## 校验命令

```bash
shasum -a 256 docs/tools/input/*.rcz
```

输出应与本表登记的 sha256 一致。如不一致，说明文件被修改或重新导出，需要
重跑 `decode_rcz_session.py` 并对照 spec 锁死的 `boyuLoopTrack` 契约确认是否
出现 drift。
