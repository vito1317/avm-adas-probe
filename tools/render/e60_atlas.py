#!/usr/bin/env python3
"""
自車 E60 3D 模型 → 多角度的正交渲染圖（行車記錄器右半邊重建原廠 3D 視角時，把車模貼在自車位置）。
方位角每 15°（0 = 從車正後方看、+ = 往右繞，跟 PerspectiveMapper 一樣）× 俯角 10/20/35/60/89°
× 前輪轉角 −30/−15/0/15/30°（+ = 往右打；3D 轉彎：前輪跟著方向盤轉）。
每張：透明背景、每像素 0.012 m，另存車中心（地面原點）在圖上的位置。
輸出：app/src/main/assets/e60_views/a{az}_e{el}[_s{steer}].png ＋ index.json
用法：~/avm-data/.venv312/bin/python tools/render/e60_atlas.py
"""
import json, math, os
from multiprocessing import Pool
import numpy as np, trimesh
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "bev3d", "models", "e60.glb")
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "e60_views")
MPP = 0.012
AZS = list(range(-180, 180, 15)); ELS = [10, 20, 35, 60, 89]; STEERS = [-30, -15, 0, 15, 30]
# 前輪（輪胎、輪圈、煞車）：輪心附近的面跟著轉向，繞通過輪心的垂直軸轉
WHEEL_MATS = {"tire", "material_0", "rim_chrome", "material_17"}
FRONT_WHEELS = [(-0.755, 0.34, 1.56), (0.755, 0.34, 1.56)]; WHEEL_R = 0.42

sc = trimesh.load(SRC)
meshes = []
for node in sc.graph.nodes_geometry:
    T, g = sc.graph[node]; m = sc.geometry[g].copy(); m.apply_transform(T)
    mat = getattr(m.visual, "material", None); bc = getattr(mat, "baseColorFactor", None)
    base = np.array(bc if bc is not None else [128, 128, 128, 255], np.float32) / 255
    if base[3] < 0.3: continue
    V, F, N = m.vertices.copy(), m.faces.copy(), m.face_normals.copy()
    if g in WHEEL_MATS:
        cen = V[F].mean(1); wheel = np.full(len(F), -1)
        for i, c in enumerate(FRONT_WHEELS): wheel[np.linalg.norm(cen - np.array(c), axis=1) < WHEEL_R] = i
        for i in (-1, 0, 1):
            sel = F[wheel == i]
            if len(sel): meshes.append((V, sel, N[wheel == i], base, i))
    else:
        meshes.append((V, F, N, base, -1))


def steered(steer):
    """前輪轉 steer 度（+ = 往右）：(x, z) 繞輪心轉，車頭方向 (0, 1) → (sin, cos)。"""
    w = math.radians(steer); cw, sw = math.cos(w), math.sin(w); out = []
    for V, F, N, base, wi in meshes:
        if wi < 0 or steer == 0: out.append((V, F, N, base)); continue
        cx, _, cz = FRONT_WHEELS[wi]
        def rot(P, c0=cx, c2=cz):
            P = P.copy(); dx, dz = P[:, 0] - c0, P[:, 2] - c2
            P[:, 0] = c0 + dx * cw + dz * sw; P[:, 2] = c2 - dx * sw + dz * cw; return P
        Nr = N.copy(); Nr[:, 0] = N[:, 0] * cw + N[:, 2] * sw; Nr[:, 2] = -N[:, 0] * sw + N[:, 2] * cw
        out.append((rot(V), F, Nr, base))
    return out


def render(az, el, steer=0):
    ms = steered(steer)
    a, e = math.radians(az), math.radians(el)
    # 從鏡頭看向車：視線方向 f（世界），右 r、上 u（跟 PerspectiveMapper 同定義）
    cam = np.array([math.sin(a) * math.cos(e), math.sin(e), -math.cos(a) * math.cos(e)])
    f = -cam; r = np.array([f[2], 0, -f[0]]); r /= max(np.linalg.norm(r), 1e-6); u = np.cross(f, r)
    allV = np.concatenate([v[np.unique(f)] for v, f, *_ in ms]); px = allV @ r; py = allV @ u
    x0, x1, y0, y1 = px.min(), px.max(), py.min(), py.max()
    W = int((x1 - x0) / MPP) + 4; H = int((y1 - y0) / MPP) + 4
    col = np.zeros((H, W, 4), np.float32); zb = np.full((H, W), -1e9, np.float32)
    L = np.array([0.3, 1.0, -0.2]); L /= np.linalg.norm(L)
    for V, F, N, base in ms:
        sx = (V @ r - x0) / MPP + 2; sy = (y1 - V @ u) / MPP + 2; dep = -(V @ f)
        nl = np.abs(N @ L); shade = 0.45 + 0.55 * nl
        for fa, s in zip(F, shade):
            xs, ys, ds = sx[fa], sy[fa], dep[fa]
            xa, xb = int(max(0, math.floor(xs.min()))), int(min(W - 1, math.ceil(xs.max())))
            ya, yb = int(max(0, math.floor(ys.min()))), int(min(H - 1, math.ceil(ys.max())))
            if xb < xa or yb < ya: continue
            gx, gy = np.meshgrid(np.arange(xa, xb + 1) + 0.5, np.arange(ya, yb + 1) + 0.5)
            (ax, bx, cx), (ay, by, cy) = xs, ys
            den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if abs(den) < 1e-9: continue
            w1 = ((by - cy) * (gx - cx) + (cx - bx) * (gy - cy)) / den
            w2 = ((cy - ay) * (gx - cx) + (ax - cx) * (gy - cy)) / den
            w3 = 1 - w1 - w2; ins = (w1 >= -1e-4) & (w2 >= -1e-4) & (w3 >= -1e-4)
            if not ins.any(): continue
            d = w1 * ds[0] + w2 * ds[1] + w3 * ds[2]
            sub = zb[ya:yb + 1, xa:xb + 1]; upd = ins & (d > sub + 0.004); sub[upd] = d[upd]
            cb = col[ya:yb + 1, xa:xb + 1]; cb[upd, :3] = np.clip(base[:3] * s, 0, 1); cb[upd, 3] = base[3]
    # 地面原點（車中心正下方）在圖上的位置
    ox = (0 - x0) / MPP + 2; oy = (y1 - 0) / MPP + 2
    return Image.fromarray((col * 255).astype(np.uint8), "RGBA"), ox, oy


def job(a):
    az, el, st = a
    im, ox, oy = render(az, el, st); name = f"a{az}_e{el}" + (f"_s{st}" if st else "") + ".png"
    im.save(os.path.join(OUT, name), optimize=True)
    return dict(az=az, el=el, s=st, file=name, ox=round(ox, 1), oy=round(oy, 1), w=im.size[0], h=im.size[1])


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    jobs = [(az, el, st) for st in STEERS for el in ELS for az in AZS]
    with Pool(max(1, os.cpu_count() // 2)) as p:
        views = []
        for v in p.imap_unordered(job, jobs):
            views.append(v)
            if len(views) % 50 == 0: print(len(views), "/", len(jobs), flush=True)
    views.sort(key=lambda v: (v["s"], v["el"], v["az"]))
    json.dump({"mpp": MPP, "views": views}, open(os.path.join(OUT, "index.json"), "w"))
    print("完成", len(views))
