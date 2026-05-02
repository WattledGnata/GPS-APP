#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线把 RaceChrono .rcz session 的原始 GPS 时序喂给我们算法的等价 Python 实现，
对比"我们"和 RaceChrono 各自算出来的 lap times 差异。

算法是 GateCrossingDetector + LapTimingEngine 的简化 Python 移植：
- gate 中点投影到米空间
- 线段相交求 t 参数（denominator/cross-product 公式）
- crossingMillis = prev.ts + t * (curr.ts - prev.ts)（线性时间插值）
- passDirection 校验

仅依赖 Python 标准库。
"""

import argparse
import math
import struct
import sys
import zipfile
from typing import List, Tuple

# RaceChrono .rcz channel encoding constants（参考 docs/design/rcz-format-decoding.md）
COORD_SCALE = 6_000_000

EARTH_R_M = 6_371_000.0
METERS_PER_DEGREE_LAT = 111_320.0
FLOAT_BOUNDARY_TOLERANCE = 1e-9


# ---------------------------------------------------------------------------
# .rcz 解码
# ---------------------------------------------------------------------------

def find_channel(zf: zipfile.ZipFile, channel_id: int, size_flag: int) -> bytes:
    suffix = f"_{channel_id}_{size_flag}"
    for name in zf.namelist():
        if name.startswith("channel_") and name.endswith(suffix):
            return zf.read(name)
    raise RuntimeError(f"channel {channel_id} (size_flag={size_flag}) not found")


def decode_ts_channel(data: bytes) -> List[int]:
    n = len(data) // 8
    return list(struct.unpack(f"<{n}q", data))


def decode_packed_latlon_channel(data: bytes) -> List[Tuple[float, float]]:
    n = len(data) // 8
    samples = []
    for i in range(n):
        lat_i, lon_i = struct.unpack_from("<ii", data, i * 8)
        samples.append((lat_i / COORD_SCALE, lon_i / COORD_SCALE))
    return samples


def load_rcz_samples(rcz_path: str) -> List[Tuple[int, float, float]]:
    """返回 [(timestampMillis, lat, lon), ...]。"""
    with zipfile.ZipFile(rcz_path, "r") as zf:
        ts_list = decode_ts_channel(find_channel(zf, 1, 1))
        latlon_list = decode_packed_latlon_channel(find_channel(zf, 3, 1))
    n = min(len(ts_list), len(latlon_list))
    samples = []
    for i in range(n):
        lat, lon = latlon_list[i]
        samples.append((ts_list[i], lat, lon))
    return samples


# ---------------------------------------------------------------------------
# 我们算法的 Python 移植（GateCrossingDetector.detect + LapTimingEngine 核心）
# ---------------------------------------------------------------------------

def detect_crossing(
    prev_lat: float, prev_lon: float,
    curr_lat: float, curr_lon: float,
    gate_start_lat: float, gate_start_lon: float,
    gate_end_lat: float, gate_end_lon: float,
    pass_dir_x: float, pass_dir_y: float,
):
    """跟 GateCrossingDetector.detect 对齐的 Python 移植。

    返回 (accepted: bool, t: float | None)。
    t 是 prev→curr 线段上的归一化进度 ∈ [0, 1]，accepted 时非 None。
    """
    origin_lat = (gate_start_lat + gate_end_lat) / 2.0
    origin_lon = (gate_start_lon + gate_end_lon) / 2.0
    lon_scale = METERS_PER_DEGREE_LAT * math.cos(math.radians(origin_lat))

    prev_n = (prev_lat - origin_lat) * METERS_PER_DEGREE_LAT
    prev_e = (prev_lon - origin_lon) * lon_scale
    curr_n = (curr_lat - origin_lat) * METERS_PER_DEGREE_LAT
    curr_e = (curr_lon - origin_lon) * lon_scale
    gs_n = (gate_start_lat - origin_lat) * METERS_PER_DEGREE_LAT
    gs_e = (gate_start_lon - origin_lon) * lon_scale
    ge_n = (gate_end_lat - origin_lat) * METERS_PER_DEGREE_LAT
    ge_e = (gate_end_lon - origin_lon) * lon_scale

    # segments_intersect_meters
    abx = curr_n - prev_n
    aby = curr_e - prev_e
    cdx = ge_n - gs_n
    cdy = ge_e - gs_e
    denom = abx * cdy - aby * cdx
    if denom == 0.0:
        return False, None
    acx = gs_n - prev_n
    acy = gs_e - prev_e
    t = (acx * cdy - acy * cdx) / denom
    u = (acx * aby - acy * abx) / denom
    tol = FLOAT_BOUNDARY_TOLERANCE
    if not (-tol <= t <= 1.0 + tol and -tol <= u <= 1.0 + tol):
        return False, None

    # passDirection 投影 + 单位向量化
    pass_dir_n = pass_dir_x * METERS_PER_DEGREE_LAT
    pass_dir_e = pass_dir_y * lon_scale
    pass_dir_len = math.sqrt(pass_dir_n * pass_dir_n + pass_dir_e * pass_dir_e)
    if pass_dir_len == 0.0:
        return False, None
    pass_unit_n = pass_dir_n / pass_dir_len
    pass_unit_e = pass_dir_e / pass_dir_len

    # movement 点积 → directionScore
    move_n = curr_n - prev_n
    move_e = curr_e - prev_e
    direction_score = move_n * pass_unit_n + move_e * pass_unit_e
    if direction_score <= 0.0:
        return False, None

    return True, max(0.0, min(1.0, t))


def find_lap_times(samples, gate, name="lap"):
    """对每相邻帧调 detect_crossing，找出所有 accepted 的 startFinish 过线时刻。
    返回 lap_times_ms 列表（相邻 accepted 之间的差）。
    """
    crossings_ms = []
    gs_lat, gs_lon = gate["start"]
    ge_lat, ge_lon = gate["end"]
    pdx, pdy = gate["pass_dir"]
    for i in range(1, len(samples)):
        ts_prev, lat_prev, lon_prev = samples[i - 1]
        ts_curr, lat_curr, lon_curr = samples[i]
        accepted, t = detect_crossing(
            lat_prev, lon_prev, lat_curr, lon_curr,
            gs_lat, gs_lon, ge_lat, ge_lon, pdx, pdy,
        )
        if accepted:
            crossing_ms = ts_prev + t * (ts_curr - ts_prev)
            crossings_ms.append(round(crossing_ms))

    laps_ms = []
    for i in range(1, len(crossings_ms)):
        laps_ms.append(crossings_ms[i] - crossings_ms[i - 1])
    return crossings_ms, laps_ms


def fmt_lap(ms: int) -> str:
    minutes = ms // 60_000
    seconds = (ms % 60_000) / 1000.0
    return f"{minutes}:{seconds:06.3f}"


# Boyu loop track startFinish gate（hardcoded from ExtraPresetTracksDebug.kt）
BOYU_START_FINISH = {
    "start": (30.3997726667, 104.0617311259),
    "end": (30.3997726667, 104.0612088741),
    "pass_dir": (-0.000450450450, 0.000000000000),  # x = lat方向, y = lon方向
}


def main(argv):
    p = argparse.ArgumentParser()
    p.add_argument("rcz_path", help="path to RaceChrono session .rcz")
    p.add_argument("--racechrono-laps", nargs="+", type=str,
                   help="optional RaceChrono own lap times for comparison, e.g. 2:19.76 2:07.14 2:19.41")
    args = p.parse_args(argv)

    samples = load_rcz_samples(args.rcz_path)
    print(f"loaded {len(samples)} GPS samples from {args.rcz_path}")
    print(f"first ts: {samples[0][0]}, last ts: {samples[-1][0]}, "
          f"duration: {(samples[-1][0] - samples[0][0]) / 1000:.1f}s")
    print(f"first pos: {samples[0][1:]}")
    print(f"last pos: {samples[-1][1:]}")

    crossings_ms, laps_ms = find_lap_times(samples, BOYU_START_FINISH)
    print(f"\nstartFinish accepted crossings: {len(crossings_ms)}")
    print(f"computed lap count: {len(laps_ms)}")
    print()

    print(f"{'lap#':<5} {'our (Python port)':<22} {'RaceChrono':<22} {'diff (ms)':<10}")
    print("-" * 70)
    rc_laps = []
    if args.racechrono_laps:
        for s in args.racechrono_laps:
            mins, secs = s.split(":")
            rc_laps.append(int(round(int(mins) * 60_000 + float(secs) * 1000)))

    for i, lap_ms in enumerate(laps_ms):
        our = fmt_lap(lap_ms)
        if i < len(rc_laps):
            rc = fmt_lap(rc_laps[i])
            diff = lap_ms - rc_laps[i]
            print(f"{i+1:<5} {our:<22} {rc:<22} {diff:+d}")
        else:
            print(f"{i+1:<5} {our:<22}")

    if rc_laps and len(laps_ms) >= len(rc_laps):
        diffs = [laps_ms[i] - rc_laps[i] for i in range(len(rc_laps))]
        print(f"\ndiff stats: mean={sum(diffs)/len(diffs):+.1f}ms "
              f"min={min(diffs):+d}ms max={max(diffs):+d}ms "
              f"abs_max={max(abs(d) for d in diffs)}ms")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
