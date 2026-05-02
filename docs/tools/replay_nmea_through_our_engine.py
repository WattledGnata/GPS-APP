#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线把 RaceChrono .nmea session 的 GPS 时序喂给我们算法的 Python 移植，
对比"我们"和 RaceChrono 各自算出来的 lap times 差异。

NMEA 0183 格式：
- $GPRMC,HHMMSS.tt,A,DDMM.mmmmm,N,DDDMM.mmmmm,E,speed_knots,heading,date,...
- $GPGGA,HHMMSS.tt,DDMM.mmmmm,N,DDDMM.mmmmm,E,fix,sats,hdop,alt,M,...

对比 VBO（10Hz 降采样）：NMEA 是 25Hz 完整原始流，跟 RaceChrono 实时 lap timing 同源。
"""

import argparse
import math
import sys

EARTH_R_M = 6_371_000.0
METERS_PER_DEGREE_LAT = 111_320.0
FLOAT_BOUNDARY_TOLERANCE = 1e-9


def parse_ddmm_to_degrees(s: str) -> float:
    """NMEA DDMM.mmmmm → 度。例如 '3023.51264' = 30 + 23.51264/60 = 30.39187733°。"""
    v = float(s)
    deg = int(v // 100)
    minutes = v - deg * 100
    return deg + minutes / 60.0


def parse_hhmmss_to_ms(s: str) -> int:
    """HHMMSS.tt → 当日 ms。"""
    dot = s.index(".")
    hms = s[:dot]
    frac = s[dot:]
    if len(hms) < 6:
        hms = hms.zfill(6)
    h = int(hms[:2])
    m = int(hms[2:4])
    sec = int(hms[4:6])
    millis_frac = round(float("0" + frac) * 1000)
    return ((h * 60 + m) * 60 + sec) * 1000 + millis_frac


def load_nmea_samples(nmea_path: str):
    """从 $GPRMC 句子提取 (ms_of_day, lat_deg, lon_deg)。"""
    samples = []
    with open(nmea_path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line.startswith("$GPRMC"):
                continue
            parts = line.split(",")
            # $GPRMC,162219.240,A,3023.51264,N,10403.70049,E,0.08,45.43,010526,,,*12
            #   0      1         2  3          4  5          6  7    8     9
            if len(parts) < 7 or parts[2] != "A":
                continue
            try:
                ms = parse_hhmmss_to_ms(parts[1])
                lat = parse_ddmm_to_degrees(parts[3])
                if parts[4] == "S":
                    lat = -lat
                lon = parse_ddmm_to_degrees(parts[5])
                if parts[6] == "W":
                    lon = -lon
                samples.append((ms, lat, lon))
            except (ValueError, IndexError):
                continue
    return samples


def detect_crossing(prev_lat, prev_lon, curr_lat, curr_lon,
                    gate_start_lat, gate_start_lon,
                    gate_end_lat, gate_end_lon,
                    pass_dir_x, pass_dir_y):
    origin_lat = (gate_start_lat + gate_end_lat) / 2.0
    origin_lon = (gate_start_lon + gate_end_lon) / 2.0
    lon_scale = METERS_PER_DEGREE_LAT * math.cos(math.radians(origin_lat))

    def to_xy(lat, lon):
        return ((lat - origin_lat) * METERS_PER_DEGREE_LAT,
                (lon - origin_lon) * lon_scale)

    p_n, p_e = to_xy(prev_lat, prev_lon)
    c_n, c_e = to_xy(curr_lat, curr_lon)
    gs_n, gs_e = to_xy(gate_start_lat, gate_start_lon)
    ge_n, ge_e = to_xy(gate_end_lat, gate_end_lon)

    abx, aby = c_n - p_n, c_e - p_e
    cdx, cdy = ge_n - gs_n, ge_e - gs_e
    denom = abx * cdy - aby * cdx
    if denom == 0.0:
        return False, None
    acx, acy = gs_n - p_n, gs_e - p_e
    t = (acx * cdy - acy * cdx) / denom
    u = (acx * aby - acy * abx) / denom
    tol = FLOAT_BOUNDARY_TOLERANCE
    if not (-tol <= t <= 1.0 + tol and -tol <= u <= 1.0 + tol):
        return False, None

    pdN = pass_dir_x * METERS_PER_DEGREE_LAT
    pdE = pass_dir_y * lon_scale
    pdLen = math.sqrt(pdN * pdN + pdE * pdE)
    if pdLen == 0:
        return False, None
    puN, puE = pdN / pdLen, pdE / pdLen
    score = abx * puN + aby * puE
    if score <= 0.0:
        return False, None

    return True, max(0.0, min(1.0, t))


def find_lap_times(samples, gate):
    crossings_ms = []
    gs_lat, gs_lon = gate["start"]
    ge_lat, ge_lon = gate["end"]
    pdx, pdy = gate["pass_dir"]
    for i in range(1, len(samples)):
        ts_p, lat_p, lon_p = samples[i - 1]
        ts_c, lat_c, lon_c = samples[i]
        accepted, t = detect_crossing(lat_p, lon_p, lat_c, lon_c,
                                      gs_lat, gs_lon, ge_lat, ge_lon, pdx, pdy)
        if accepted:
            crossings_ms.append(ts_p + t * (ts_c - ts_p))
    laps_ms = [round(crossings_ms[i] - crossings_ms[i - 1])
               for i in range(1, len(crossings_ms))]
    return crossings_ms, laps_ms


def fmt_lap(ms):
    minutes = int(ms // 60_000)
    seconds = (ms % 60_000) / 1000.0
    return f"{minutes}:{seconds:06.3f}"


BOYU_START_FINISH = {
    "start": (30.3997726667, 104.0617311259),
    "end": (30.3997726667, 104.0612088741),
    "pass_dir": (-0.000450450450, 0.000000000000),
}


def main(argv):
    p = argparse.ArgumentParser()
    p.add_argument("nmea_path")
    p.add_argument("--racechrono-laps", nargs="+",
                   help="例 2:19.760 2:07.140 2:19.410")
    args = p.parse_args(argv)

    samples = load_nmea_samples(args.nmea_path)
    if not samples:
        print("No GPRMC samples found in NMEA file.", file=sys.stderr)
        return 1
    print(f'samples: {len(samples)}, '
          f'first ts (ms-of-day): {samples[0][0]}, '
          f'last ts: {samples[-1][0]}, '
          f'duration: {(samples[-1][0] - samples[0][0]) / 1000:.1f}s')
    intervals = [samples[i][0] - samples[i-1][0] for i in range(1, len(samples))]
    avg_interval = sum(intervals) / len(intervals)
    print(f'avg interval: {avg_interval:.1f}ms (≈ {1000/avg_interval:.1f}Hz)')
    print(f'first pos: lat={samples[0][1]:.6f}, lon={samples[0][2]:.6f}')

    crossings_ms, laps_ms = find_lap_times(samples, BOYU_START_FINISH)
    print(f'\nstartFinish accepted crossings: {len(crossings_ms)}')

    rc_laps = []
    if args.racechrono_laps:
        for s in args.racechrono_laps:
            mins, secs = s.split(":")
            rc_laps.append(int(round(int(mins) * 60_000 + float(secs) * 1000)))

    print(f"\n{'lap#':<5} {'our (Python port)':<22} {'RaceChrono':<22} {'diff (ms)':<10}")
    print("-" * 70)
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
        n = len(diffs)
        mean = sum(diffs) / n
        var = sum((d - mean) ** 2 for d in diffs) / n
        std = math.sqrt(var)
        print(f"\ndiff stats: mean={mean:+.1f}ms std={std:.1f}ms "
              f"min={min(diffs):+d}ms max={max(diffs):+d}ms "
              f"abs_max={max(abs(d) for d in diffs)}ms")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
