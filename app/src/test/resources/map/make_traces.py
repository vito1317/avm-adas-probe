#!/usr/bin/env python3
"""Synthetic 1 Hz GPS traces along real OSM roads (MapAssist test fixtures).

usage: make_traces.py OSM_DIR OUT_DIR
Reads <name>.json (raw Overpass responses) from OSM_DIR, writes trace_<name>.json to OUT_DIR.

Model: the car follows the route centreline at a lane offset; speed limited by cruise speed,
lateral acceleration on curves, stops and turn speed caps. GPS = truth + AR(1) correlated noise
(tau 15 s) + white noise; bearing noise grows at low speed and is often missing when stopped.
"""
import json
import math
import os
import random
import sys

R = 6371008.8

TRACES = [
    {
        "name": "taipei_zhongxiao", "seed": 11, "sigma": 5.0, "white": 1.5, "cruise": 13.0, "offset": 1.75,
        # 忠孝東路四段東行（雙向分隔的單行車道）→ 光復南路右轉南行
        "parts": [[[348745652, 1, 6], [506322859, 0, 4], [348745651, 0, 2], [1546881445, 0, 6],
                   [506322872, 0, 1], [1445960945, 0, 1], [255431333, 0, 20], [255431326, 0, 5],
                   [1044271657, 0, 2], [1044172129, 0, 1]]],
        "turns": [618956126],
        "stops": [[618955901, -12.0, 15.0]],     # 延吉街路口前停 15 s
        "caps": [[618956126, 25.0, 5.0]],
    },
    {
        "name": "yangde", "seed": 22, "sigma": 6.0, "white": 2.0, "cruise": 11.0, "offset": 1.75,
        # 仰德大道一段→二段上山（雙向、彎道），二段路口前迴轉下山
        "parts": [[[258321583, 20, 24], [296979080, 0, 2], [543999915, 0, 46], [266789540, 9, 4]],
                  [[266789540, 4, 9], [543999915, 46, 25]]],
        "turns": [],
        "stops": [[1667377324, -10.0, 10.0]],
        "caps": [],
        "uturn": 8.0,
    },
    {
        "name": "tainan_west", "seed": 33, "sigma": 7.0, "white": 2.0, "cruise": 7.0, "offset": 0.8,
        # 中成路南行 → 左轉慈音街（巷弄、慢速）→ 右轉公園路
        "parts": [[[158783758, 0, 15], [81357763, 2, 9], [286841509, 9, 13]]],
        "turns": [1709220982, 948017009],
        "stops": [[4406950643, -8.0, 8.0]],
        "caps": [[1709220982, 15.0, 3.5], [948017009, 15.0, 3.5]],
        "crawl": [[5524470248, 40.0, 1.2]],      # 慈音街上一段 1.2 m/s 慢行
    },
]


def load_osm(path):
    d = json.load(open(path, encoding="utf-8"))
    nodes, ways = {}, {}
    for e in d["elements"]:
        if e["type"] == "node":
            old = nodes.get(e["id"])
            if old is None or ("tags" in e and "tags" not in old):
                nodes[e["id"]] = e
        elif e["type"] == "way":
            ways[e["id"]] = e
    return nodes, ways


class Proj:
    def __init__(self, lat0, lon0):
        self.lat0, self.lon0 = lat0, lon0
        self.k = math.cos(math.radians(lat0))

    def xy(self, lat, lon):
        return (math.radians(lon - self.lon0) * self.k * R, math.radians(lat - self.lat0) * R)

    def ll(self, x, y):
        return (self.lat0 + math.degrees(y / R), self.lon0 + math.degrees(x / (R * self.k)))


def build_part(legs, nodes, ways):
    seq = []   # (node_id, way_id, way_forward)
    for wid, i0, i1 in legs:
        nl = ways[wid]["nodes"]
        idx = list(range(i0, i1 + 1)) if i1 >= i0 else list(range(i0, i1 - 1, -1))
        fwd = i1 >= i0
        for k, i in enumerate(idx):
            nid = nl[i]
            if seq and seq[-1][0] == nid:
                continue
            if seq and k == 0:
                raise SystemExit("legs not connected at way %d" % wid)
            seq.append((nid, wid, fwd))
    return seq


def sig_applies(tags, fwd):
    d = tags.get("traffic_signals:direction") or tags.get("direction")
    if d == "forward":
        return fwd
    if d == "backward":
        return not fwd
    return True


def make(spec, osm_dir):
    nodes, ways = load_osm(os.path.join(osm_dir, spec["name"] + ".json"))
    first = build_part(spec["parts"][0], nodes, ways)
    n0 = nodes[first[0][0]]
    pr = Proj(n0["lat"], n0["lon"])
    parts = []
    for legs in spec["parts"]:
        seq = build_part(legs, nodes, ways)
        pts = [pr.xy(nodes[n]["lat"], nodes[n]["lon"]) for n, _, _ in seq]
        s = [0.0]
        for i in range(1, len(pts)):
            s.append(s[-1] + math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]))
        # 號誌：節點 tag 是 traffic_signals 且方向適用（way 方向 = 行進方向時 forward 適用）
        sig = []
        for i, (n, wid, fwd) in enumerate(seq):
            tg = nodes[n].get("tags", {})
            if tg.get("highway") == "traffic_signals":
                # 交會點同時屬於前後兩條 way：方向以進入此節點的路段（= 加入此節點的 leg）為準
                if sig_applies(tg, fwd):
                    sig.append(round(s[i], 2))
        parts.append({"seq": seq, "pts": pts, "s": s, "sig": sig})
    return nodes, ways, pr, parts


def at(part, s):
    pts, ss = part["pts"], part["s"]
    s = max(0.0, min(s, ss[-1]))
    i = 1
    while i < len(ss) - 1 and ss[i] < s:
        i += 1
    a, b = pts[i - 1], pts[i]
    L = ss[i] - ss[i - 1]
    t = 0.0 if L <= 0 else (s - ss[i - 1]) / L
    x, y = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
    hd = math.atan2(b[0] - a[0], b[1] - a[1])   # 方位角：北 = 0，順時針
    return x, y, hd, i - 1


def heading_smooth(part, s, w=6.0):
    x0, y0, _, _ = at(part, s - w)
    x1, y1, _, _ = at(part, s + w)
    return math.atan2(x1 - x0, y1 - y0)


def node_s(part, nid):
    for i, (n, _, _) in enumerate(part["seq"]):
        if n == nid:
            return part["s"][i]
    return None


def speed_profile(spec, part, pi):
    """v_max(s) on a 0.5 m grid: cruise, curvature, caps, plus stop/turn braking."""
    L = part["s"][-1]
    n = int(L / 0.5) + 2
    vmax = [spec["cruise"]] * n
    for k in range(n):
        s = k * 0.5
        h0 = heading_smooth(part, s - 6)
        h1 = heading_smooth(part, s + 6)
        dh = abs((h1 - h0 + math.pi) % (2 * math.pi) - math.pi)
        kappa = dh / 12.0
        if kappa > 1e-4:
            vmax[k] = min(vmax[k], math.sqrt(2.5 / kappa))
    for nid, rad, v in spec.get("caps", []) + spec.get("crawl", []):
        sc = node_s(part, nid)
        if sc is None:
            continue
        for k in range(n):
            if abs(k * 0.5 - sc) <= rad:
                vmax[k] = min(vmax[k], v)
    # 減速：往回推 v² ≤ v_next² + 2·a·ds
    for k in range(n - 2, -1, -1):
        vmax[k] = min(vmax[k], math.sqrt(vmax[k + 1] ** 2 + 2 * 1.8 * 0.5))
    stops = []
    for nid, ds, dur in spec.get("stops", []):
        sc = node_s(part, nid)
        if sc is not None:
            stops.append([sc + ds, dur, False])
    return vmax, stops


def simulate(spec, osm_dir):
    rnd = random.Random(spec["seed"])
    nodes, ways, pr, parts = make(spec, osm_dir)
    dt = 0.1
    t = 0.0
    truth = []   # 每 0.1 s
    for pi, part in enumerate(parts):
        vmax, stops = speed_profile(spec, part, pi)
        L = part["s"][-1]
        s, v = (0.0, 0.0) if pi == 0 else (0.0, 1.0)
        end_stop = (pi < len(parts) - 1)
        while s < L - 0.05:
            k = min(int(s / 0.5), len(vmax) - 1)
            target = vmax[k]
            for st in stops:
                if not st[2] and s <= st[0]:
                    target = min(target, math.sqrt(max(0.0, 2 * 1.8 * (st[0] - s))))
            if end_stop:
                target = min(target, math.sqrt(max(0.0, 2 * 1.8 * (L - s))) + 0.3)
            v = min(target, v + 1.5 * dt) if target > v else max(target, v - 3.0 * dt)
            stopped = False
            for st in stops:
                if not st[2] and s >= st[0] - 0.6 and v < 0.4:
                    for _ in range(int(st[1] / dt)):
                        rec(truth, t, part, pi, s, 0.0, spec, pr)
                        t += dt
                    st[2] = True
                    v = 0.0
                    stopped = True
            if stopped:
                continue
            s = min(L, s + v * dt)
            rec(truth, t, part, pi, s, v, spec, pr)
            t += dt
        if end_stop and spec.get("uturn"):
            # 迴轉：以道路中心為圓心、半徑 = 車道偏移的半圓
            x, y, hd, _ = at(part, L)
            hd = heading_smooth(part, L - 6)
            r = spec["offset"]
            dur = spec["uturn"]
            steps = int(dur / dt)
            for j in range(steps):
                a = math.pi * (j + 1) / steps   # 0 → π（往左轉）
                # 起點在右側 r：中心 + r·右法向；右法向 = (cos hd, −sin hd)
                rx, ry = math.cos(hd), -math.sin(hd)
                fx, fy = math.sin(hd), math.cos(hd)
                px = x + r * (rx * math.cos(a) + fx * math.sin(a) * 0.9)
                py = y + r * (ry * math.cos(a) + fy * math.sin(a) * 0.9)
                h = hd - a
                spd = math.pi * r / dur
                truth.append({"t": round(t, 2), "x": px, "y": py, "hdg": h, "v": spd, "part": pi,
                              "s": L, "way": None, "a": None, "b": None, "uturn": True})
                t += dt
    return nodes, ways, pr, parts, truth


def rec(truth, t, part, pi, s, v, spec, pr):
    x, y, hd, i = at(part, s)
    hs = heading_smooth(part, s, 3.0)
    off = spec["offset"]
    px, py = x + off * math.cos(hs), y - off * math.sin(hs)
    seq = part["seq"]
    a, b = seq[i], seq[i + 1]
    truth.append({"t": round(t, 2), "x": px, "y": py, "hdg": hs, "v": v, "part": pi, "s": s,
                  "way": b[1], "a": a[0], "b": b[0], "uturn": False})


def to_fixes(spec, pr, truth):
    rnd = random.Random(spec["seed"] * 7 + 1)
    tau, sig, wh = 15.0, spec["sigma"], spec["white"]
    rho = math.exp(-1.0 / tau)
    nx = rnd.gauss(0, sig)
    ny = rnd.gauss(0, sig)
    fixes = []
    last_t = -1.0
    for tr in truth:
        if tr["t"] - last_t < 0.999:
            continue
        last_t = tr["t"]
        nx = rho * nx + math.sqrt(1 - rho * rho) * rnd.gauss(0, sig)
        ny = rho * ny + math.sqrt(1 - rho * rho) * rnd.gauss(0, sig)
        gx = tr["x"] + nx + rnd.gauss(0, wh)
        gy = tr["y"] + ny + rnd.gauss(0, wh)
        lat, lon = pr.ll(gx, gy)
        v = tr["v"]
        spd = max(0.0, v + rnd.gauss(0, 0.25)) if v > 0.05 else abs(rnd.gauss(0, 0.15))
        if v < 0.5 and rnd.random() < 0.5:
            brg = None
        else:
            sd = 2.0 if v > 5 else (6.0 if v > 2 else 70.0)
            brg = (math.degrees(tr["hdg"]) + rnd.gauss(0, sd)) % 360.0
        acc = round(max(3.0, sig * 1.1 + rnd.gauss(0, 1.0)), 1)
        tlat, tlon = pr.ll(tr["x"], tr["y"])
        fixes.append({
            "t": int(round(tr["t"] * 1000)), "lat": round(lat, 7), "lon": round(lon, 7),
            "spd": round(spd, 2), "brg": None if brg is None else round(brg, 1), "acc": acc,
            "truth": {"lat": round(tlat, 7), "lon": round(tlon, 7),
                      "hdg": round(math.degrees(tr["hdg"]) % 360.0, 1), "v": round(v, 2),
                      "part": tr["part"], "s": round(tr["s"], 2), "way": tr["way"],
                      "a": tr["a"], "b": tr["b"], "uturn": tr["uturn"]},
        })
    return fixes


def main():
    osm_dir, out_dir = sys.argv[1], sys.argv[2]
    for spec in TRACES:
        nodes, ways, pr, parts, truth = simulate(spec, osm_dir)
        fixes = to_fixes(spec, pr, truth)
        out_parts = []
        for pi, part in enumerate(parts):
            turns = []
            for nid in spec["turns"]:
                sc = node_s(part, nid)
                if sc is not None:
                    turns.append(round(sc, 2))
            out_parts.append({
                "nodes": [n for n, _, _ in part["seq"]],
                "ways": [w for _, w, _ in part["seq"]],
                "poly": [[round(pr.ll(x, y)[0], 7), round(pr.ll(x, y)[1], 7), round(s, 2)]
                         for (x, y), s in zip(part["pts"], part["s"])],
                "signals": part["sig"],
                "turns": sorted(turns),
                "length": round(part["s"][-1], 2),
            })
        out = {"name": spec["name"], "osm": spec["name"] + ".json", "sigma": spec["sigma"],
               "offset": spec["offset"], "parts": out_parts, "fixes": fixes}
        path = os.path.join(out_dir, "trace_" + spec["name"] + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
        print("%s: %d fixes, parts %s, signals %s, turns %s" % (
            path, len(fixes), [p["length"] for p in out_parts], [p["signals"] for p in out_parts],
            [p["turns"] for p in out_parts]))


if __name__ == "__main__":
    main()
