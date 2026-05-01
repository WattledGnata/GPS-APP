#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线 .rcz session 解码 → Track DSL 文本片段。

本脚本由 OpenSpec change ``add-debug-preset-track-boyu-loop`` 落地。仅依赖
Python 标准库（zipfile / struct / json / math / bisect / argparse / hashlib）。

输入约定：
    必须是 session ``.rcz``（包含 ``session.json`` + ``trackId.json`` + 多个
    ``channel_*`` 二进制流）。track-only ``.rcz``（只含 ``trackId.json``）会
    被显式拒绝，因为缺乏抽 referencePath / 反推 sector 流向所需的 GPS 实测。

来源 ``.rcz`` 期望 sha256（本 round 已锁，落 ``docs/tools/input/README.md``）::

    track_天投泊寓环线.rcz  → ca9fc2c3a59750e4e2c02b0183e2aa3b7bfdfe608ec822c7afae53ab5e816343
    session_20260108_225454_天投泊寓环线.rcz
                            → 666b501cb2d074cfae8948af7ca1dc925f152937b79b38b492a7a53cb1d1fd21

用法示例::

    python3 docs/tools/decode_rcz_session.py \\
        docs/tools/input/session_20260108_225454_天投泊寓环线.rcz \\
        --lap 1 \\
        --resample-step-m 30 \\
        --out docs/tools/output/decode_rcz_session_boyu_loop.txt

编码规则与字段语义详见 ``docs/design/rcz-format-decoding.md``。
"""

import argparse
import bisect
import hashlib
import json
import math
import struct
import sys
import zipfile
from typing import List, Tuple

# ----------------------------------------------------------------------
# 编码常量（与 docs/design/rcz-format-decoding.md §6 速查表对齐）
# ----------------------------------------------------------------------
COORD_SCALE = 6_000_000
BEARING_SCALE = 1_000
WIDTH_SCALE = 1_000
COORD_UNSET = 2_147_483_647
TYPE_START_FINISH = 3
TYPE_SECTOR = 4

EARTH_R_M = 6_371_000.0  # 地球半径（米），简化大圆距离用


# ----------------------------------------------------------------------
# 几何工具
# ----------------------------------------------------------------------
def haversine_m(p1: Tuple[float, float], p2: Tuple[float, float]) -> float:
    """大圆距离（米），p = (lat_deg, lon_deg)。"""
    lat1, lon1 = math.radians(p1[0]), math.radians(p1[1])
    lat2, lon2 = math.radians(p2[0]), math.radians(p2[1])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_R_M * math.asin(min(1.0, math.sqrt(a)))


def gate_line_endpoints(
    center: Tuple[float, float], bearing_deg: float, width_m: float
) -> Tuple[Tuple[float, float], Tuple[float, float]]:
    """trap (center, bearing, width) → gate line 两端点 (deg)。

    Gate line 垂直于 bearing；对应 PresetTracks.kt 既有 TFIC 的 line 格式。
    """
    line_bearing = bearing_deg - 90.0
    half = width_m / 2.0
    lat0, _lon0 = center
    n_off = half * math.cos(math.radians(line_bearing)) / 111_000.0
    e_off = half * math.sin(math.radians(line_bearing)) / (
        111_000.0 * math.cos(math.radians(lat0))
    )
    start = (center[0] + n_off, center[1] + e_off)
    end = (center[0] - n_off, center[1] - e_off)
    return start, end


def gate_pass_direction(
    center_lat_deg: float, bearing_deg: float, width_m: float
) -> Tuple[float, float]:
    """trap → 沿 bearing 的 passDirection 向量（lat 轴, lon 轴）。

    与 PresetTracks.kt 既有 TFIC 的 passDirection 格式对齐：分量 .x 是 lat 轴
    delta、分量 .y 是 lon 轴 delta（注意非数学 (x,y) 直觉，但在源工程内是
    既成约定）。本脚本用同样布局。
    """
    n_pd = math.cos(math.radians(bearing_deg)) * width_m / 111_000.0
    e_pd = math.sin(math.radians(bearing_deg)) * width_m / (
        111_000.0 * math.cos(math.radians(center_lat_deg))
    )
    return n_pd, e_pd


def segments_intersect(
    p1: Tuple[float, float],
    p2: Tuple[float, float],
    p3: Tuple[float, float],
    p4: Tuple[float, float],
) -> bool:
    """二维线段相交判断（用经纬度直接近似平面，本工程局部 < 1 km 误差可忽略）。"""

    def ccw(a, b, c):
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])

    d1 = ccw(p3, p4, p1)
    d2 = ccw(p3, p4, p2)
    d3 = ccw(p1, p2, p3)
    d4 = ccw(p1, p2, p4)
    if ((d1 > 0 and d2 < 0) or (d1 < 0 and d2 > 0)) and (
        (d3 > 0 and d4 < 0) or (d3 < 0 and d4 > 0)
    ):
        return True
    return False


# ----------------------------------------------------------------------
# .rcz 解析
# ----------------------------------------------------------------------
def open_session_rcz(path: str) -> zipfile.ZipFile:
    """打开并校验输入是 session .rcz。track-only .rcz 直接拒绝。"""
    try:
        zf = zipfile.ZipFile(path, "r")
    except (zipfile.BadZipFile, FileNotFoundError) as exc:
        sys.stderr.write(f"ERROR: cannot open .rcz at {path}: {exc}\n")
        sys.exit(2)
    names = set(zf.namelist())
    needs = {"session.json", "trackId.json"}
    has_channels = any(n.startswith("channel_") for n in names)
    if not needs.issubset(names) or not has_channels:
        sys.stderr.write(
            "ERROR: input must be a session .rcz containing session.json + "
            "channel binaries; track-only .rcz is not supported (track "
            "definition is read from session's embedded trackId.json).\n"
            f"  observed entries: {sorted(names)}\n"
        )
        sys.exit(2)
    return zf


def find_channel(zf: zipfile.ZipFile, channel_id: int, size_flag: int) -> bytes:
    """按 channel_id + size_flag 在 zip 内定位文件并读出全部 bytes。

    文件名格式 channel_<sessionId>_<deviceId>_<flag>_<channelId>_<sizeFlag>，
    我们只用后两个字段做精确匹配。
    """
    suffix = f"_{channel_id}_{size_flag}"
    for name in zf.namelist():
        if name.startswith("channel_") and name.endswith(suffix):
            return zf.read(name)
    sys.stderr.write(
        f"ERROR: channel {channel_id} (size_flag={size_flag}) not found in session .rcz\n"
    )
    sys.exit(2)


def decode_packed_latlon(data: bytes) -> List[Tuple[float, float]]:
    """channel 3 (packed 2× int32 LE) → [(lat_deg, lon_deg), ...]。"""
    n = len(data) // 8
    out: List[Tuple[float, float]] = []
    off = 0
    for _ in range(n):
        a, b = struct.unpack_from("<2i", data, off)
        off += 8
        out.append((a / COORD_SCALE, b / COORD_SCALE))
    return out


def slice_lap_samples(
    timestamps: List[int], xy: List[Tuple[float, float]], lap_meta: dict
) -> List[Tuple[float, float]]:
    """按 lap startTimestamp / finishTimestamp 二分定位 sample 范围。"""
    i_start = bisect.bisect_left(timestamps, lap_meta["startTimestamp"])
    if "finishTimestamp" not in lap_meta:
        sys.stderr.write(
            f"ERROR: lap {lap_meta.get('number')} has no finishTimestamp "
            "(in-progress lap cannot be decoded)\n"
        )
        sys.exit(2)
    i_end = bisect.bisect_right(timestamps, lap_meta["finishTimestamp"])
    return xy[i_start:i_end]


def resample_polyline(
    pts: List[Tuple[float, float]], step_m: float
) -> List[Tuple[float, float]]:
    """沿弧长等距重采样到 step_m 步长。保留首末点。"""
    if not pts:
        return []
    out = [pts[0]]
    acc = 0.0
    prev = pts[0]
    for p in pts[1:]:
        acc += haversine_m(prev, p)
        if acc >= step_m:
            out.append(p)
            acc = 0.0
        prev = p
    if out[-1] != pts[-1]:
        out.append(pts[-1])
    return out


def reorder_sectors_by_crossing_time(
    sector_traps: List[dict],
    timestamps: List[int],
    xy: List[Tuple[float, float]],
    lap_meta: dict,
) -> List[dict]:
    """对 sector trap 按 lap 内最早过线时间升序排列。

    返回每个 trap 字典上挂一个 ``__sequence_index`` 键（int, 1..N）。
    """
    i_start = bisect.bisect_left(timestamps, lap_meta["startTimestamp"])
    i_end = bisect.bisect_right(timestamps, lap_meta["finishTimestamp"])
    crossings: List[Tuple[int, int]] = []  # (sample_idx, trap_index)
    for ti, trap in enumerate(sector_traps):
        center = (trap["centerLatitude"] / COORD_SCALE, trap["centerLongitude"] / COORD_SCALE)
        bearing_d = trap["bearing"] / BEARING_SCALE
        width_m = trap["width"] / WIDTH_SCALE
        gate_a, gate_b = gate_line_endpoints(center, bearing_d, width_m)
        first_idx = None
        for i in range(i_start + 1, i_end):
            if segments_intersect(xy[i - 1], xy[i], gate_a, gate_b):
                first_idx = i
                break
        if first_idx is None:
            sys.stderr.write(
                f"WARN: sector trap[{ti}] '{trap.get('name')}' was never crossed in lap "
                f"{lap_meta.get('number')}; falling back to JSON array order\n"
            )
            first_idx = 10**18 + ti  # 推到最后并保持原顺序
        crossings.append((first_idx, ti))
    crossings.sort()
    ordered = []
    for seq, (_idx, ti) in enumerate(crossings, start=1):
        trap = dict(sector_traps[ti])
        trap["__sequence_index"] = seq
        ordered.append(trap)
    return ordered


# ----------------------------------------------------------------------
# Kotlin DSL 输出
# ----------------------------------------------------------------------
def emit_geo_point(lat: float, lon: float) -> str:
    return f"            GeoPoint({lat:.7f}, {lon:.7f})"


def emit_timing_gate(
    name: str, type_str: str, line_a: Tuple[float, float], line_b: Tuple[float, float],
    pass_dir: Tuple[float, float], sequence_index: int, gate_id: str,
) -> str:
    return (
        f"        TimingGate(\n"
        f"            id = \"{gate_id}\",\n"
        f"            name = \"{name}\",\n"
        f"            type = TimingGateType.{type_str},\n"
        f"            line = GeoLine(\n"
        f"                start = GeoPoint({line_a[0]:.10f}, {line_a[1]:.10f}),\n"
        f"                end = GeoPoint({line_b[0]:.10f}, {line_b[1]:.10f}),\n"
        f"            ),\n"
        f"            passDirection = GeoVector("
        f"x = {pass_dir[0]:.12f}, y = {pass_dir[1]:.12f}),\n"
        f"            sequenceIndex = {sequence_index},\n"
        f"            minDirectionalSpeedMps = null,\n"
        f"        )"
    )


def emit_track_dsl(
    track_id: str,
    name_zh: str,
    name_en: str,
    name_abbr: str,
    length_km: float,
    points: List[Tuple[float, float]],
    start_finish_gate: str,
    sector_gates: List[str],
) -> str:
    abbr_lit = "null" if name_abbr is None else f"\"{name_abbr}\""
    head = (
        f"// Auto-generated by docs/tools/decode_rcz_session.py\n"
        f"// Source: docs/tools/input/session_20260108_225454_天投泊寓环线.rcz\n"
        f"// Lap selected: 1 (best 2:39.888); resample step: 30 m\n"
        f"// OpenSpec change: add-debug-preset-track-boyu-loop\n"
        f"// 修改请重新运行脚本，勿手工编辑此 DSL 片段。\n\n"
    )
    pts_block = ",\n".join(emit_geo_point(p[0], p[1]) for p in points)
    sectors_block = ",\n".join(sector_gates)
    return (
        head
        + f"val boyuLoopTrack = Track(\n"
        + f"    id = \"{track_id}\",\n"
        + f"    name = TrackName(\n"
        + f"        zh = \"{name_zh}\",\n"
        + f"        en = \"{name_en}\",\n"
        + f"        abbr = {abbr_lit},\n"
        + f"    ),\n"
        + f"    lengthKm = {length_km:.3f},\n"
        + f"    thumbnailAssetPath = null,\n"
        + f"    source = TrackSource.Preset,\n"
        + f"    referencePath = TrackPath(\n"
        + f"        points = listOf(\n"
        + pts_block
        + f"\n        ),\n"
        + f"    ),\n"
        + f"    startFinishGate = \n"
        + start_finish_gate
        + f",\n"
        + f"    sectorGates = listOf(\n"
        + sectors_block
        + f"\n    ),\n"
        + f")\n"
    )


# ----------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------
def main(argv: List[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("session_rcz", help="path to session .rcz (must contain session.json)")
    ap.add_argument("--lap", type=int, default=1, help="lap number to use (default 1)")
    ap.add_argument(
        "--resample-step-m",
        type=float,
        default=30.0,
        help="referencePath equal-arc-length step in meters (default 30)",
    )
    ap.add_argument(
        "--out", default="-", help="output file path; '-' = stdout (default)"
    )
    ap.add_argument(
        "--track-id", default="preset-boyu-loop", help="Track.id (default preset-boyu-loop)"
    )
    ap.add_argument(
        "--name-zh",
        default="成都天投泊寓环线",
        help="Track.name.zh (default 成都天投泊寓环线)",
    )
    ap.add_argument(
        "--name-en",
        default="Chengdu Tiantou Boyu Loop",
        help="Track.name.en (default Chengdu Tiantou Boyu Loop)",
    )
    ap.add_argument(
        "--name-abbr", default=None, help="Track.name.abbr (default null)"
    )
    args = ap.parse_args(argv)

    # 启动时打印输入文件 sha256 便于审查
    sha = hashlib.sha256(open(args.session_rcz, "rb").read()).hexdigest()
    sys.stderr.write(f"[info] session sha256 = {sha}\n")

    zf = open_session_rcz(args.session_rcz)
    session = json.loads(zf.read("session.json"))
    track = json.loads(zf.read("trackId.json"))["track"]

    laps = session["laps"]
    lap_meta = next((l for l in laps if l["number"] == args.lap), None)
    if lap_meta is None:
        sys.stderr.write(f"ERROR: lap {args.lap} not found in session.json\n")
        return 2

    # channel 1 = ts (int64 LE), channel 3 = packed lat/lon (2× int32 LE)
    ts_bytes = find_channel(zf, 1, 1)
    xy_bytes = find_channel(zf, 3, 1)
    if len(ts_bytes) // 8 != len(xy_bytes) // 8:
        sys.stderr.write(
            f"ERROR: ts/xy channel sample count mismatch "
            f"({len(ts_bytes)//8} vs {len(xy_bytes)//8})\n"
        )
        return 2
    n = len(ts_bytes) // 8
    timestamps = list(struct.unpack(f"<{n}q", ts_bytes))
    xy = decode_packed_latlon(xy_bytes)

    # ------------------------------------------------------------------
    # 抽 lap path + resample
    # ------------------------------------------------------------------
    lap_pts = slice_lap_samples(timestamps, xy, lap_meta)
    sampled = resample_polyline(lap_pts, args.resample_step_m)
    # 路径长度
    path_len_m = sum(haversine_m(lap_pts[i - 1], lap_pts[i]) for i in range(1, len(lap_pts)))
    closure_m = haversine_m(lap_pts[0], lap_pts[-1]) if lap_pts else 0.0

    sys.stderr.write(
        f"[info] lap {args.lap}: raw_samples={len(lap_pts)}, "
        f"resampled={len(sampled)} (step={args.resample_step_m}m), "
        f"path_len={path_len_m:.1f}m, closure={closure_m:.2f}m\n"
    )

    # ------------------------------------------------------------------
    # 起终点 + sector
    # ------------------------------------------------------------------
    start_finish = next((t for t in track["traps"] if t["type"] == TYPE_START_FINISH), None)
    if start_finish is None:
        sys.stderr.write("ERROR: no start/finish trap (type=3) in trackId.json\n")
        return 2
    sectors_raw = [t for t in track["traps"] if t["type"] == TYPE_SECTOR]

    # sector 顺序按实测过线时间排
    ordered_sectors = reorder_sectors_by_crossing_time(
        sectors_raw, timestamps, xy, lap_meta
    )

    def trap_to_gate(trap: dict, gate_id: str, type_str: str, sequence_index: int) -> str:
        center = (
            trap["centerLatitude"] / COORD_SCALE,
            trap["centerLongitude"] / COORD_SCALE,
        )
        bearing_d = trap["bearing"] / BEARING_SCALE
        width_m = trap["width"] / WIDTH_SCALE
        line_a, line_b = gate_line_endpoints(center, bearing_d, width_m)
        pass_dir = gate_pass_direction(center[0], bearing_d, width_m)
        # 起终点在本 round 强制规范命名为 "起终点"，sector 沿用 s1/s2/s3/s4
        display_name = "起终点" if type_str == "StartFinish" else f"s{sequence_index}"
        return emit_timing_gate(
            display_name, type_str, line_a, line_b, pass_dir, sequence_index, gate_id
        )

    sf_block = trap_to_gate(start_finish, "start-finish", "StartFinish", 0)
    sector_blocks = [
        trap_to_gate(t, f"s{t['__sequence_index']}", "Sector", t["__sequence_index"])
        for t in ordered_sectors
    ]

    # ------------------------------------------------------------------
    # 渲染 DSL
    # ------------------------------------------------------------------
    dsl = emit_track_dsl(
        track_id=args.track_id,
        name_zh=args.name_zh,
        name_en=args.name_en,
        name_abbr=args.name_abbr,
        length_km=round(path_len_m / 1000.0, 3),
        points=sampled,
        start_finish_gate=sf_block,
        sector_gates=sector_blocks,
    )

    if args.out == "-":
        sys.stdout.write(dsl)
    else:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(dsl)
        sys.stderr.write(f"[info] wrote {len(dsl)} bytes to {args.out}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
