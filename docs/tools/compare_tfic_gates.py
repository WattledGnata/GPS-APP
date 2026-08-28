#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""TFIC 赛道门位置对比：iOS 同事 .bpz vs 我们的 PresetTracks.kt vs Session RCZ 原生门。

用法：
    python3 docs/tools/compare_tfic_gates.py

依赖：session RCZ 文件（固定路径）。
"""

import json
import math
import struct
import sys
import zipfile

# ============================================================================
# 常量
# ============================================================================
COORD_SCALE = 6_000_000       # RCZ session 用
BPZ_COORD_SCALE = 10_000_000  # .bpz iOS 用（1e7）
BEARING_SCALE = 1_000
WIDTH_SCALE = 1_000
EARTH_R_M = 6_371_000.0
METERS_PER_DEGREE_LAT = 111_320.0
FLOAT_BOUNDARY_TOLERANCE = 1e-9

RCZ_PATH = "/Users/wattledgnata/Downloads/session_20260314_170249_天府赛道_lap7.rcz"
BPZ_PATH = "/Users/wattledgnata/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/wxid_rmidw8h8xvrl21_d3ff/msg/file/2026-07/天府国际赛道.bpz"

# ============================================================================
# 几何工具
# ============================================================================
def haversine_m(p1, p2):
    lat1, lon1 = math.radians(p1[0]), math.radians(p1[1])
    lat2, lon2 = math.radians(p2[0]), math.radians(p2[1])
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_R_M * math.asin(min(1.0, math.sqrt(a)))


def gate_center_from_line(start, end):
    """从 GeoLine 端点反推 gate center。"""
    return ((start[0] + end[0]) / 2, (start[1] + end[1]) / 2)


def gate_width_from_line(start, end):
    """从 GeoLine 端点反推 gate 宽度(m)。"""
    return haversine_m(start, end)


def gate_bearing_from_pass_dir(center_lat, pass_dir_x, pass_dir_y):
    """从 passDirection vector 反推 bearing（度）。pass_dir_x 是 lat 分量。"""
    n = pass_dir_x * METERS_PER_DEGREE_LAT
    e = pass_dir_y * METERS_PER_DEGREE_LAT * math.cos(math.radians(center_lat))
    bearing = math.degrees(math.atan2(e, n))
    if bearing < 0:
        bearing += 360
    return bearing


def gate_line_endpoints(center, bearing_deg, width_m):
    """trap (center, bearing, width) → gate line 两端点 (deg)。"""
    line_bearing = bearing_deg - 90.0
    half = width_m / 2.0
    lat0 = center[0]
    n_off = half * math.cos(math.radians(line_bearing)) / METERS_PER_DEGREE_LAT
    e_off = half * math.sin(math.radians(line_bearing)) / (
        METERS_PER_DEGREE_LAT * math.cos(math.radians(lat0))
    )
    start = (center[0] + n_off, center[1] + e_off)
    end = (center[0] - n_off, center[1] - e_off)
    return start, end


def gate_pass_direction(center_lat, bearing_deg, width_m):
    """trap → passDirection 向量 (lat分量, lon分量)，宽度归一化。"""
    # 用宽度归一化，方向向量 magnitude 保持跟现有 TFIC 一致（~0.00026° lat）
    ref_mag = 0.00026  # 参考 magnitude
    n_pd = math.cos(math.radians(bearing_deg)) * ref_mag
    e_pd = math.sin(math.radians(bearing_deg)) * ref_mag / math.cos(math.radians(center_lat))
    return n_pd, e_pd


def detect_crossing(prev_lat, prev_lon, curr_lat, curr_lon,
                    gate_start_lat, gate_start_lon,
                    gate_end_lat, gate_end_lon,
                    pass_dir_x, pass_dir_y):
    """跟 GateCrossingDetector.detect 对齐的 Python 移植。"""
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


def find_crossings(samples, gate):
    """对所有相邻帧调 detect_crossing，返回过线时刻列表(ms)。"""
    crossings = []
    gs_lat, gs_lon = gate["start"]
    ge_lat, ge_lon = gate["end"]
    pdx, pdy = gate["pass_dir"]
    for i in range(1, len(samples)):
        ts_p, lat_p, lon_p = samples[i - 1]
        ts_c, lat_c, lon_c = samples[i]
        accepted, t = detect_crossing(
            lat_p, lon_p, lat_c, lon_c,
            gs_lat, gs_lon, ge_lat, ge_lon, pdx, pdy,
        )
        if accepted:
            crossings.append(round(ts_p + t * (ts_c - ts_p)))
    return crossings


def fmt_lap(ms):
    m = int(ms // 60_000)
    s = (ms % 60_000) / 1000.0
    return f"{m}:{s:06.3f}"


# ============================================================================
# 数据加载
# ============================================================================

def load_bpz_gates():
    """从 .bpz 文件提取 gate 定义。"""
    with open(BPZ_PATH, "rb") as f:
        data = f.read()
    # 找到 JSON 块
    json_start = data.find(b'{"id"')
    json_end = json_start
    depth = 0
    for i in range(json_start, len(data)):
        if data[i:i+1] == b'{':
            depth += 1
        elif data[i:i+1] == b'}':
            depth -= 1
            if depth == 0:
                json_end = i + 1
                break
    obj = json.loads(data[json_start:json_end])
    track = obj["track"]
    gates = {}
    for trap in track["traps"]:
        center = (trap["centerLatitude"] / BPZ_COORD_SCALE,
                  trap["centerLongitude"] / BPZ_COORD_SCALE)
        bearing_d = trap["bearing"] / BEARING_SCALE
        width_m = trap["width"] / WIDTH_SCALE
        gs, ge = gate_line_endpoints(center, bearing_d, width_m)
        pd = gate_pass_direction(center[0], bearing_d, width_m)
        gate_def = {
            "center": center,
            "bearing": bearing_d,
            "width_m": width_m,
            "start": gs,
            "end": ge,
            "pass_dir": pd,
            "name": trap["name"],
        }
        if trap["type"] == 3:  # StartFinish
            gates["start_finish"] = gate_def
        elif trap["type"] == 4:  # Sector
            name = trap["name"]
            if "s1" in name.lower() or "cp1" in name.lower() or "1" in name:
                gates["s1"] = gate_def
            elif "s2" in name.lower() or "cp2" in name.lower() or "2" in name:
                gates["s2"] = gate_def
    return gates


def load_our_gates():
    """从 PresetTracks.kt 硬编码提取（与生产代码完全一致）。"""
    # StartFinish — PresetTracks.kt lines 55-61
    sf_start = (30.495674664699337, 104.4333934545891)
    sf_end = (30.495698171686513, 104.43287290301339)
    sf_pd = (-0.0002602757878550089, -0.000023506987175358924)
    sf_center = gate_center_from_line(sf_start, sf_end)
    sf_width = gate_width_from_line(sf_start, sf_end)
    sf_bearing = gate_bearing_from_pass_dir(sf_center[0], sf_pd[0], sf_pd[1])

    # S1 — PresetTracks.kt lines 68-77
    s1_start = (30.49004451419976, 104.43252709154902)
    s1_end = (30.48959781913357, 104.43258157511764)
    s1_pd = (0.00002724178431097556, 0.00044669506619011374)
    s1_center = gate_center_from_line(s1_start, s1_end)
    s1_width = gate_width_from_line(s1_start, s1_end)
    s1_bearing = gate_bearing_from_pass_dir(s1_center[0], s1_pd[0], s1_pd[1])

    # S2 — PresetTracks.kt lines 84-93
    s2_start = (30.4957579139104, 104.4369620745035)
    s2_end = (30.495765752756267, 104.43748325882984)
    s2_pd = (0.0002605921631704301, -0.000007838845867048829)
    s2_center = gate_center_from_line(s2_start, s2_end)
    s2_width = gate_width_from_line(s2_start, s2_end)
    s2_bearing = gate_bearing_from_pass_dir(s2_center[0], s2_pd[0], s2_pd[1])

    return {
        "start_finish": {
            "center": sf_center, "bearing": sf_bearing, "width_m": sf_width,
            "start": sf_start, "end": sf_end, "pass_dir": sf_pd,
            "name": "起终点 (我们的)",
        },
        "s1": {
            "center": s1_center, "bearing": s1_bearing, "width_m": s1_width,
            "start": s1_start, "end": s1_end, "pass_dir": s1_pd,
            "name": "S1 (我们的)",
        },
        "s2": {
            "center": s2_center, "bearing": s2_bearing, "width_m": s2_width,
            "start": s2_start, "end": s2_end, "pass_dir": s2_pd,
            "name": "S2 (我们的)",
        },
    }


def load_session_gates():
    """从 session RCZ 自带的 trackId.json 提取 gate 定义。"""
    with zipfile.ZipFile(RCZ_PATH, "r") as zf:
        track = json.loads(zf.read("trackId.json"))["track"]
    gates = {}
    for trap in track["traps"]:
        center = (trap["centerLatitude"] / COORD_SCALE,
                  trap["centerLongitude"] / COORD_SCALE)
        bearing_d = trap["bearing"] / BEARING_SCALE
        width_m = trap["width"] / WIDTH_SCALE
        gs, ge = gate_line_endpoints(center, bearing_d, width_m)
        pd = gate_pass_direction(center[0], bearing_d, width_m)
        gate_def = {
            "center": center,
            "bearing": bearing_d,
            "width_m": width_m,
            "start": gs,
            "end": ge,
            "pass_dir": pd,
            "name": trap["name"],
        }
        if trap["type"] == 3:
            gates["start_finish"] = gate_def
        elif trap["type"] == 4:
            name = trap["name"]
            if "s1" in name.lower() or "1" in name:
                gates["s1"] = gate_def
            elif "s2" in name.lower() or "2" in name:
                gates["s2"] = gate_def
    return gates


def load_gps_samples():
    """从 session RCZ 加载 GPS 样本。"""
    with zipfile.ZipFile(RCZ_PATH, "r") as zf:
        # Find channel_1 (timestamps) and channel_3 (GPS) for device 200
        ts_data = None
        xy_data = None
        for name in zf.namelist():
            if not name.startswith("channel_"):
                continue
            parts = name.split("_")
            # channel_<N>_<devId>_<flag>_<chId>_<sizeFlag>
            if len(parts) < 6:
                continue
            dev_id = parts[2]
            ch_id = parts[4]
            sf = parts[5]
            if dev_id != "200":
                continue
            if ch_id == "1" and sf == "1":
                ts_data = zf.read(name)
            elif ch_id == "3" and sf == "1":
                xy_data = zf.read(name)

        if ts_data is None or xy_data is None:
            print("ERROR: cannot find GPS channels for device 200", file=sys.stderr)
            sys.exit(2)

        n_ts = len(ts_data) // 8
        n_xy = len(xy_data) // 8
        n = min(n_ts, n_xy)
        ts_list = list(struct.unpack(f"<{n_ts}q", ts_data))
        samples = []
        for i in range(n):
            a, b = struct.unpack_from("<ii", xy_data, i * 8)
            samples.append((ts_list[i], a / COORD_SCALE, b / COORD_SCALE))
        return samples


def slice_lap(samples, start_ts, end_ts):
    """按时间戳区间切出单圈样本。"""
    import bisect
    i0 = bisect.bisect_left([s[0] for s in samples], start_ts)
    i1 = bisect.bisect_right([s[0] for s in samples], end_ts)
    return samples[i0:i1]


# ============================================================================
# 主流程
# ============================================================================

def main():
    print("=" * 80)
    print("TFIC 赛道门位置对比：iOS .bpz vs 我们的 PresetTracks.kt vs Session RCZ")
    print("=" * 80)

    # --- 加载 ---
    bpz = load_bpz_gates()
    ours = load_our_gates()
    session = load_session_gates()

    # --- 几何对比 ---
    print("\n" + "=" * 80)
    print("一、门中心点几何对比")
    print("=" * 80)

    for gate_key, gate_label in [("start_finish", "起终点"), ("s1", "S1 (CP1)"), ("s2", "S2 (CP2)")]:
        print(f"\n--- {gate_label} ---")
        for src_name, src_gates in [("iOS .bpz      ", bpz), ("我们的 (Preset)", ours), ("Session RCZ  ", session)]:
            g = src_gates.get(gate_key)
            if g:
                print(f"  {src_name}: center=({g['center'][0]:.8f}, {g['center'][1]:.8f})  "
                      f"bearing={g['bearing']:.1f}°  width={g['width_m']:.1f}m")
            else:
                print(f"  {src_name}: MISSING")

        # 两两距离
        print("  距离对比:")
        pairs = [
            ("iOS ↔ 我们", bpz, ours),
            ("iOS ↔ Session", bpz, session),
            ("我们 ↔ Session", ours, session),
        ]
        for label, ga, gb in pairs:
            a = ga.get(gate_key)
            b = gb.get(gate_key)
            if a and b:
                dist_m = haversine_m(a["center"], b["center"])
                bearing_diff = abs(a["bearing"] - b["bearing"])
                if bearing_diff > 180:
                    bearing_diff = 360 - bearing_diff
                width_diff = abs(a["width_m"] - b["width_m"])
                print(f"    {label}: 中心距={dist_m:.2f}m  bearing差={bearing_diff:.1f}°  宽度差={width_diff:.1f}m")

    # --- 用 GPS 数据验证 ---
    print("\n" + "=" * 80)
    print("二、GPS 实测数据验证（Session RCZ Lap 7, 25Hz, 112.8s）")
    print("=" * 80)

    samples = load_gps_samples()
    print(f"GPS 样本数: {len(samples)}")
    print(f"首帧: ts={samples[0][0]} pos=({samples[0][1]:.7f}, {samples[0][2]:.7f})")
    print(f"末帧: ts={samples[-1][0]} pos=({samples[-1][1]:.7f}, {samples[-1][2]:.7f})")
    intervals = [samples[i][0] - samples[i - 1][0] for i in range(1, len(samples))]
    avg_ms = sum(intervals) / len(intervals)
    print(f"平均间隔: {avg_ms:.1f}ms ≈ {1000 / avg_ms:.1f}Hz")

    # 检查三套门的起终点过线
    print("\n三套门的起终点过线检测：")
    for label, gates in [("iOS .bpz      ", bpz), ("我们的 (Preset)", ours), ("Session RCZ  ", session)]:
        sf = gates.get("start_finish")
        if not sf:
            print(f"  {label}: 无起终点")
            continue
        crossings = find_crossings(samples, sf)
        print(f"  {label}: {len(crossings)} 次过线 → {max(0, len(crossings) - 1)} 圈")
        if len(crossings) >= 2:
            laps_ms = [crossings[i] - crossings[i - 1] for i in range(1, len(crossings))]
            # 过滤异常圈（< 60s 或 > 180s）
            valid = [l for l in laps_ms if 60_000 < l < 180_000]
            if valid:
                mean_lap = sum(valid) / len(valid)
                std_lap = math.sqrt(sum((l - mean_lap) ** 2 for l in valid) / len(valid))
                print(f"    有效圈数: {len(valid)}, 均值: {fmt_lap(int(mean_lap))}, σ: {std_lap:.0f}ms")
                print(f"    各圈: {', '.join(fmt_lap(l) for l in valid)}")
            else:
                print(f"    无有效圈（范围 60-180s）")

    # --- 起终点过线位置散点分析 ---
    print("\n" + "=" * 80)
    print("三、过线位置分析（所有 SF 过线时刻的 GPS 点 vs 门中心距离）")
    print("=" * 80)

    for label, gates in [("iOS .bpz      ", bpz), ("我们的 (Preset)", ours), ("Session RCZ  ", session)]:
        sf = gates.get("start_finish")
        if not sf:
            continue
        print(f"\n{label}:")
        center = sf["center"]
        # 找每次过线的采样点
        crossings_ts = find_crossings(samples, sf)
        # 对每次过线找最近采样点的 GPS 位置
        distances = []
        for c_ts in crossings_ts:
            # 找到过线时刻前后的采样点
            nearest = min(samples, key=lambda s: abs(s[0] - c_ts))
            d = haversine_m((nearest[1], nearest[2]), center)
            distances.append(d)
        if distances:
            mean_d = sum(distances) / len(distances)
            std_d = math.sqrt(sum((d - mean_d) ** 2 for d in distances) / len(distances))
            print(f"  过线次数: {len(distances)}")
            print(f"  最近采样点距门中心: 均值={mean_d:.2f}m, σ={std_d:.2f}m, "
                  f"min={min(distances):.2f}m, max={max(distances):.2f}m")

    # --- Sector 门分析 ---
    print("\n" + "=" * 80)
    print("四、Sector 门过线检测")
    print("=" * 80)

    for src_label, gates in [("iOS .bpz      ", bpz), ("我们的 (Preset)", ours), ("Session RCZ  ", session)]:
        print(f"\n{src_label}:")
        sf = gates.get("start_finish")
        for sk in ["s1", "s2"]:
            sg = gates.get(sk)
            if not sg or not sf:
                print(f"  {sk}: 缺门定义")
                continue
            s_crossings = find_crossings(samples, sg)
            sf_crossings = find_crossings(samples, sf)
            print(f"  {sk}: {len(s_crossings)} 次过线 (SF共{len(sf_crossings)}次)")

    # --- 综合结论 ---
    print("\n" + "=" * 80)
    print("五、综合评估")
    print("=" * 80)
    print("""
评估维度：
1. 门中心绝对位置：iOS .bpz 门用的是 RaceChrono 默认 trap，我们的门经过
   2026-06-19 session 142605bb 真机 17 圈标定（起终点前移 55m 对齐 MYLAPS 龙门架）。

2. 起终点位置差异直接影响圈速：起终点前移/后移 X 米 = 圈速偏差约
   X / 尾速(m/s) 秒。TFIC 起终点区尾速约 170 km/h (47 m/s)，每 1m 偏差 ≈ 21ms。

3. 本次 session (2026-03-14) 的 track 定义是用户自己画的（宽度 50m）vs
   iOS 同事画的（宽度 100m）= 更接近官方 MYLAPS 龙门架宽度（约 10m）。

建议：以真机 session 142605bb 标定的我们的门为基准。iOS .bpz 门可作参考。""")

    return 0


if __name__ == "__main__":
    sys.exit(main())
