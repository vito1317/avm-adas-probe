#!/usr/bin/env python3
"""
把車機原廠的校正檔（/data/flyconfig 下的 calibinfo.lua + lens.lua + sensor.lua）
轉成我們的 app/src/main/assets/avm_calib.json 格式（docs/v03-spec.md 第 2.1 / 2.2 節）。

用法：
    python3 tools/calib_from_lua.py --dir /path/to/flyconfig -o calib.json \\
        [--raw raw.jpg] [--scale 1.0] [--verify]

原廠檔的意思（已核對過數值，不要再推公式）
--------------------------------------
* `CameraPos` / `LookatPos` 單位 mm，軸是 (X=前, Y=上, Z=右)；`CameraUp` 是上方向量。
  我們的軸是 x=右、y=上、z=前，所以 our_x = their_Z、our_y = their_Y、our_z = their_X。
  原點是標定布（BlankArea）的中心，`FrontEdge` 是車頭位置 → 平移 FrontEdge − 車長/2 讓原點回到
  車身投影中心。
* 四顆鏡頭的象限由 `CameraOffsets` 的 UV 偏移決定：
  {0,0}=左上=前、{0.5,0}=右上=後、{0,0.5}=左下=左、{0.5,0.5}=右下=右。
* `Internal.Center` 是 1280x720 原生影像上的主點 → cx = Center.x/1280、cy = Center.y/720
  （我們的 cx/cy 是比例，換到 960x540 象限不用再乘）。
* `lens.lua` 的 `["6053"]`：每列 { θ度, c2, c3, 畸變 }，**c2 = 感光元件上的像高（mm）**，
  c3 = 理想針孔參考（1.3565·tanθ），第 4 欄 = (c2−c3)/c3。
  感光元件 "2053" 像素 4.2 µm → 像素半徑 r_px(θ) = c2(θ)/0.0042 × (960/1280) × Scale × S。

唯一沒定的是那個全域縮放 S
--------------------------
`Internal` 裡的 Focal / DefaultFocal / Scale / DefaultScale 兜不出合理的圓半徑（試過），
所以這裡把 **每顆的 `Scale` 當相對係數照用**，另外留一個全域 S（預設 1.0）。
S 用 `tools/selfcal.py --mode scale` 以跨鏡頭重疊一致性從實際影像定出來。

k1 / k2 / fx 以最小平方擬合我們的模型 rd = θ(1 + k1θ² + k2θ⁴)、r_px = fx·QW·rd 去逼近
畸變表給的 r_px(θ)；k1/k2 只跟鏡頭型號有關（四顆同型），fx 才帶 Scale。

`maxDeg`：有給 `--raw` 時量原始畫面裡魚眼成像圓的半徑（圓左右不被畫框切到），換算成角度；
沒給就用 --max-deg（預設 95）。
需要 numpy、scipy（擬合）、Pillow（量成像圓）。
"""
import argparse
import json
import math
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import avm_geometry as ag  # noqa: E402
import fit_calib as fc  # noqa: E402

DEG = math.pi / 180.0

# CameraOffsets（UV 偏移）→ 我們的象限編號與名稱
OFFSET_TO_QUAD = {(0.0, 0.0): 0, (0.5, 0.0): 1, (0.0, 0.5): 2, (0.5, 0.5): 3}
QUAD_NAME = {0: "front", 1: "rear", 2: "left", 3: "right"}

FRAME_W, FRAME_H = 1920, 1080      # Camera2 id "2" 的 2x2 畫面
DARK_SUM = 150                      # 量成像圓用的亮度門檻（跟 selfcal 一致）


# ---------------------------------------------------------------- 迷你 Lua table 讀取

_TOK = re.compile(r"""
    (?P<ws>\s+|--[^\n]*)
  | (?P<str>"[^"]*")
  | (?P<num>-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)
  | (?P<name>[A-Za-z_]\w*)
  | (?P<sym>[{}\[\]=,;/*+-])
""", re.VERBOSE)


def _tokens(text):
    i, n = 0, len(text)
    while i < n:
        m = _TOK.match(text, i)
        if m is None:
            raise ValueError("lua 解析失敗，位置 %d：%r" % (i, text[i:i + 30]))
        i = m.end()
        if m.lastgroup != "ws":
            yield m.lastgroup, m.group()


class _LuaParser:
    """只認得 Lua table 常值：{ k = v, ["k"] = v, v, ... }，數字可以是 a / b 這種簡單算式。"""

    def __init__(self, text):
        self.t = list(_tokens(text))
        self.i = 0

    def peek(self):
        return self.t[self.i] if self.i < len(self.t) else (None, None)

    def take(self, want=None):
        k, v = self.peek()
        if want is not None and v != want:
            raise ValueError("lua 解析失敗：預期 %r，看到 %r" % (want, v))
        self.i += 1
        return k, v

    def parse_file(self):
        """頂層的 `Name = { ... }` 們。"""
        out = {}
        while self.i < len(self.t):
            k, v = self.take()
            if k != "name":
                continue
            self.take("=")
            out[v] = self.value()
            if self.peek()[1] in (",", ";"):
                self.take()
        return out

    def value(self):
        k, v = self.peek()
        if v == "{":
            return self.table()
        if k == "str":
            self.take()
            return v[1:-1]
        if k == "name" and v in ("true", "false", "nil"):
            self.take()
            return {"true": True, "false": False, "nil": None}[v]
        return self.number()

    def number(self):
        """支援 a、-a、a / b、a * b（lens.lua 裡有 `-0.03 / 100.0`）。"""
        acc = self._atom()
        while self.peek()[1] in ("/", "*"):
            _, op = self.take()
            b = self._atom()
            acc = acc / b if op == "/" else acc * b
        return acc

    def _atom(self):
        k, v = self.take()
        if v == "-":
            return -self._atom()
        if v == "+":
            return self._atom()
        if k != "num":
            raise ValueError("lua 解析失敗：預期數字，看到 %r" % (v,))
        return float(v) if ("." in v or "e" in v or "E" in v) else int(v)

    def table(self):
        self.take("{")
        arr, dic = [], {}
        while True:
            k, v = self.peek()
            if v == "}":
                self.take()
                break
            if v == "[":                      # ["key"] = value
                self.take()
                _, key = self.take()
                key = key[1:-1] if key.startswith('"') else key
                self.take("]")
                self.take("=")
                dic[key] = self.value()
            elif k == "name" and self.t[self.i + 1][1] == "=":
                self.take()
                self.take("=")
                dic[v] = self.value()
            else:
                arr.append(self.value())
            if self.peek()[1] in (",", ";"):
                self.take()
        if dic and arr:
            dic["__array__"] = arr
            return dic
        return dic if dic else arr


def load_lua(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return _LuaParser(f.read()).parse_file()


# ---------------------------------------------------------------- 外參

def extrinsics(ext, origin_shift_mm):
    """原廠的 CameraPos / LookatPos / CameraUp → 我們的 (pos, yaw, pitch, roll)。

    軸轉換：our = (their_Z, their_Y, their_X)，單位 mm → m，their_X 先減掉原點位移。
    我們的旋轉是 M = Ryaw·Rpitch·Rroll·B（B 把相機 y 下翻成 y 上，見 spec 2.2），
    A = M·B = [right | up | forward]（欄向量，車身座標）。由 spec 的矩陣展開：
        A[1][2] = −sin(pitch)、A[1][0] = cosφ·sinρ、A[1][1] = cosφ·cosρ、
        A[0][2] = sinψ·cosφ、A[2][2] = cosψ·cosφ
    """
    def conv(p):
        return np.array([p[2], p[1], p[0] - origin_shift_mm], dtype=np.float64) / 1000.0

    pos = conv(ext["CameraPos"])
    look = conv(ext["LookatPos"])
    up = np.array([ext["CameraUp"][2], ext["CameraUp"][1], ext["CameraUp"][0]], dtype=np.float64)
    fwd = look - pos
    fwd /= np.linalg.norm(fwd)
    up = up - fwd * float(np.dot(up, fwd))        # 去掉與光軸平行的分量
    up /= np.linalg.norm(up)
    right = np.cross(up, fwd)                      # A 必須是旋轉（det=+1）
    A = np.stack((right, up, fwd), axis=1)
    pitch = math.degrees(math.asin(max(-1.0, min(1.0, -A[1][2]))))
    roll = math.degrees(math.atan2(A[1][0], A[1][1]))
    yaw = math.degrees(math.atan2(A[0][2], A[2][2]))
    return pos, yaw, pitch, roll, A


def check_rotation(yaw, pitch, roll, A, tol=1e-9):
    """把角度丟回 avm_geometry.rotation() 重建 M，確認 M·B == A。"""
    M = np.array(ag.rotation(yaw, pitch, roll), dtype=np.float64)
    B = np.diag([1.0, -1.0, 1.0])
    err = float(np.max(np.abs(M.dot(B) - A)))
    if err > tol:
        raise AssertionError("角度換算往返不一致：最大誤差 %.3e" % err)
    return err


# ---------------------------------------------------------------- 內參（畸變表 → k1/k2/fx）

def fit_distortion(table, pixel_um, sensor_w, qw, max_deg):
    """畸變表 → (fx_unit, k1, k2, 擬合誤差)。

    fx_unit 是 Scale = 1、全域縮放 = 1 時的 fx（以象限寬度為單位）。
    目標：fx·qw·θ(1+k1θ²+k2θ⁴) ≈ c2(θ)/像素大小 × (qw / 原生寬度)
    """
    rows = [r for r in table if r[0] <= max_deg + 1e-9]
    th = np.array([r[0] for r in rows], dtype=np.float64) * DEG
    r_px = np.array([r[1] for r in rows], dtype=np.float64) / (pixel_um / 1000.0) * (qw / sensor_w)

    def model(p):
        fx, k1, k2 = p
        t2 = th * th
        return fx * qw * th * (1.0 + k1 * t2 + k2 * t2 * t2)

    from scipy import optimize
    w = 1.0 / np.maximum(r_px, 8.0)         # 用相對誤差，別讓大角度獨佔
    fx0 = float(np.sum(r_px[1:] / th[1:]) / len(th[1:]) / qw)
    res = optimize.least_squares(lambda p: (model(p) - r_px) * w, [fx0, 0.0, 0.0],
                                 xtol=1e-14, ftol=1e-14)
    fx, k1, k2 = (float(v) for v in res.x)
    err = np.abs(model(res.x) - r_px)
    # 必須單調遞增，solve_theta 的 Newton 才收斂
    tt = np.linspace(1e-4, max_deg * DEG, 400)
    rd = tt * (1.0 + k1 * tt ** 2 + k2 * tt ** 4)
    mono = bool(np.all(np.diff(rd) > 0))
    return fx, k1, k2, {"max_px": float(err.max()), "rms_px": float(math.sqrt(float((err ** 2).mean()))),
                        "monotonic": mono, "n": len(rows), "max_deg": max_deg}


# ---------------------------------------------------------------- 成像圓 → maxDeg

def circle_radius(quad_rgb, cx_px, cy_px):
    """量魚眼成像圓的半徑（像素）。只取通過圓心那幾列、而且那一側沒被畫框切到的邊。"""
    from scipy import ndimage
    s = np.asarray(quad_rgb).astype(np.int32).sum(axis=2)
    m = s >= DARK_SUM
    m = ndimage.binary_opening(m, np.ones((5, 5)))
    lab, n = ndimage.label(m)
    if n > 1:
        sz = ndimage.sum(m, lab, range(1, n + 1))
        m = lab == (1 + int(np.argmax(sz)))
    m = ndimage.binary_fill_holes(m)
    h, w = m.shape
    rs = []
    for j in range(max(int(cy_px) - 12, 0), min(int(cy_px) + 13, h)):
        cols = np.where(m[j])[0]
        if cols.size < 10:
            continue
        if cols[0] > 1:
            rs.append(cx_px - cols[0])
        if cols[-1] < w - 2:
            rs.append(cols[-1] - cx_px)
    return float(np.median(rs)) if rs else None


def theta_of_radius(r_px, fx, k1, k2, qw, lo=1.0, hi=120.0):
    """解 fx·qw·θ(1+k1θ²+k2θ⁴) = r_px（二分法，rd 在範圍內單調）。"""
    def f(d):
        t = d * DEG
        return fx * qw * t * (1.0 + k1 * t * t + k2 * t ** 4) - r_px
    if f(lo) > 0 or f(hi) < 0:
        return None
    for _ in range(60):
        mid = 0.5 * (lo + hi)
        if f(mid) < 0:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


# ---------------------------------------------------------------- 組出 calib

def build_calib(lua_dir, raw_path=None, global_scale=1.0, car=None, bev=None,
                max_deg_default=95.0, frame=(FRAME_W, FRAME_H)):
    ci = load_lua(os.path.join(lua_dir, "calibinfo.lua"))["CalibInfos"]
    lens_all = load_lua(os.path.join(lua_dir, "lens.lua"))["Lens"]
    sensors = load_lua(os.path.join(lua_dir, "sensor.lua"))["Sensor"]

    car = car or {"length": 4.84, "width": 1.85}
    bev = bev or {"xMin": -6.0, "xMax": 6.0, "zMin": -7.0, "zMax": 7.0, "mpp": 0.04}
    qw, qh = frame[0] // 2, frame[1] // 2
    # 原廠原點 = 標定布中心；FrontEdge 是車頭 → 平移到車身投影中心
    shift = float(ci["FrontEdge"]) - 500.0 * float(car["length"])

    quad_rgb = {}
    if raw_path:
        rgb = fc.load_rgb(raw_path)
        if rgb.shape[:2] != (frame[1], frame[0]):
            raise ValueError("原始畫面是 %dx%d，不是 %dx%d" % (rgb.shape[1], rgb.shape[0], frame[0], frame[1]))
        for q in range(4):
            x0, y0 = (q % 2) * qw, (q // 2) * qh
            quad_rgb[q] = rgb[y0:y0 + qh, x0:x0 + qw]

    cams = {}
    report = {"front_edge_mm": ci["FrontEdge"], "origin_shift_mm": shift, "cams": {},
              "global_scale": global_scale, "raw": raw_path}
    for i, c in enumerate(ci["Cameras"]):
        off = tuple(float(t) for t in ci["CameraOffsets"][i])
        if off not in OFFSET_TO_QUAD:
            raise ValueError("認不得的 CameraOffsets %r" % (off,))
        quad = OFFSET_TO_QUAD[off]
        name = QUAD_NAME[quad]
        inte = c["Internal"]
        sw, sh, pw, _ph = sensors[inte["Sensor"]]
        lens = lens_all[inte["Lens"]]
        pos, yaw, pitch, roll, A = extrinsics(c["External"], shift)
        rot_err = check_rotation(yaw, pitch, roll, A)

        fx_unit, k1, k2, fit = fit_distortion(lens["DistortionTable"], pw, sw, qw, 100.0)
        scale = float(inte.get("Scale", 1.0)) * float(global_scale)
        fx = fx_unit * scale
        cx = float(inte["Center"][0]) / float(sw)
        cy = float(inte["Center"][1]) / float(sh)

        max_deg = max_deg_default
        r_meas = None
        if quad in quad_rgb:
            r_meas = circle_radius(quad_rgb[quad], cx * qw, cy * qh)
            if r_meas:
                t = theta_of_radius(r_meas, fx, k1, k2, qw)
                if t:
                    max_deg = round(min(t, 100.0), 2)
        cams[name] = {
            "quad": quad, "mirror": bool(inte.get("Mirror", False)),
            "cx": round(cx, 6), "cy": round(cy, 6),
            "fx": round(fx, 6), "fy": round(fx, 6),
            "k1": round(k1, 6), "k2": round(k2, 6), "maxDeg": max_deg,
            "pos": [round(float(pos[0]), 4), round(float(pos[1]), 4), round(float(pos[2]), 4)],
            "yaw": round(yaw, 3), "pitch": round(pitch, 3), "roll": round(roll, 3),
        }
        report["cams"][name] = {"index": i + 1, "quad": quad, "lens": inte["Lens"],
                                "sensor": inte["Sensor"], "lua_scale": inte.get("Scale"),
                                "fx_unit": round(fx_unit, 6), "distortion_fit": fit,
                                "rotation_roundtrip_err": rot_err,
                                "circle_radius_px": r_meas, "maxDeg": max_deg}
    out = {"version": 1, "calibrated": True,
           "note": ("由 tools/calib_from_lua.py 轉自車機原廠 calibinfo.lua（全域縮放 %.5f）。"
                    "fx/fy 以象限寬度為單位；角度為度。" % global_scale),
           "car": car, "cams": {n: cams[n] for n in ("front", "rear", "left", "right") if n in cams},
           "bev": bev}
    return out, report


# ---------------------------------------------------------------- 驗證

def verify(calib, report, frame=(FRAME_W, FRAME_H)):
    """往返自洽：ground → pixel → ground，以及 pixel → ground → pixel。"""
    geo = ag.Geometry(calib)
    qw, qh = frame[0] // 2, frame[1] // 2
    out = {}
    gx, gz = np.meshgrid(np.linspace(-5.5, 5.5, 45), np.linspace(-6.5, 6.5, 55))
    for cam in geo.cams:
        u, v, th, ok, _ = ag.ground_to_pixel(cam, gx, gz, qw, qh)
        if not ok.any():
            out[cam.name] = {"ground_pixels": 0}
            continue
        bx, bz, ok2, _ = ag.pixel_to_ground(cam, u[ok], v[ok], qw, qh)
        d = np.hypot(bx - gx[ok], bz - gz[ok])
        uu, vv = np.meshgrid(np.arange(qw) + 0.5, np.arange(qh) + 0.5)
        px, pz, pok, _ = ag.pixel_to_ground(cam, uu, vv, qw, qh)
        u2, v2, _, ok3, _ = ag.ground_to_pixel(cam, px[pok], pz[pok], qw, qh)
        du = np.hypot(u2 - uu[pok], v2 - vv[pok])
        sel = ok3 & (np.hypot(px[pok], pz[pok]) < 12.0)
        out[cam.name] = {
            "ground_pixels": int(ok.sum()),
            "ground_roundtrip_max_m": float(np.nanmax(d[ok2])) if ok2.any() else None,
            "pixel_roundtrip_max_px": float(np.nanmax(du[sel])) if sel.any() else None,
            "rotation_roundtrip_err": report["cams"][cam.name]["rotation_roundtrip_err"],
            "distortion_rms_px": report["cams"][cam.name]["distortion_fit"]["rms_px"],
            "distortion_monotonic": report["cams"][cam.name]["distortion_fit"]["monotonic"],
        }
    return out


# ---------------------------------------------------------------- CLI

def main(argv=None):
    ap = argparse.ArgumentParser(description="原廠 calibinfo.lua / lens.lua / sensor.lua → avm_calib.json")
    ap.add_argument("--dir", required=True, help="放 calibinfo.lua / lens.lua / sensor.lua 的資料夾")
    ap.add_argument("-o", "--output", required=True)
    ap.add_argument("--raw", help="一張原始 2x2 畫面，用來量成像圓決定 maxDeg")
    ap.add_argument("--scale", type=float, default=1.0, help="全域焦距縮放 S（預設 1.0，用 selfcal 定）")
    ap.add_argument("--max-deg", type=float, default=95.0, help="沒給 --raw 時的 maxDeg（預設 95）")
    ap.add_argument("--verify", action="store_true", help="印出往返自洽檢查")
    a = ap.parse_args(argv)

    calib, report = build_calib(a.dir, a.raw, a.scale, max_deg_default=a.max_deg)
    os.makedirs(os.path.dirname(os.path.abspath(a.output)) or ".", exist_ok=True)
    with open(a.output, "w", encoding="utf-8") as f:
        f.write(fc.format_calib(calib))
    with open(a.output, encoding="utf-8") as f:
        ag.Geometry(json.load(f))
    print("→ %s（原點平移 %.1f mm，全域縮放 %.5f）" % (a.output, report["origin_shift_mm"], a.scale))
    for n, c in calib["cams"].items():
        r = report["cams"][n]
        print("  %-5s quad%d pos %7.3f %6.3f %7.3f  yaw %+8.3f pitch %+7.3f roll %+7.3f"
              "  fx %.5f k1 %+.5f k2 %+.5f maxDeg %.1f"
              % (n, c["quad"], c["pos"][0], c["pos"][1], c["pos"][2], c["yaw"], c["pitch"], c["roll"],
                 c["fx"], c["k1"], c["k2"], c["maxDeg"]))
        print("        畸變表擬合 RMS %.2f px / 最大 %.2f px，單調 %s；成像圓半徑 %s px"
              % (r["distortion_fit"]["rms_px"], r["distortion_fit"]["max_px"],
                 r["distortion_fit"]["monotonic"],
                 ("%.1f" % r["circle_radius_px"]) if r["circle_radius_px"] else "未量"))
    if a.verify:
        print(json.dumps(verify(calib, report), ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
