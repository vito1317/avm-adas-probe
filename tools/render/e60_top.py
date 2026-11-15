#!/usr/bin/env python3
"""
把自車 3D 模型（app/src/main/assets/bev3d/models/e60.glb，BMW 525i E60，CC BY 4.0）從正上方渲染成俯視圖 PNG（透明背景），
給行車記錄器左半邊的俯視圖用（以前是程式畫的通用車）。正交投影、Z-buffer、簡單光照（上方偏前的光＋環境光）。

輸出：app/src/main/assets/e60_top.png（每像素 5 mm；車頭朝上；寬 = 車寬、高 = 車長，跟 EgoTopCar 的 WID／LEN 對齊）
用法：~/avm-data/.venv312/bin/python tools/render/e60_top.py
"""
import os
import numpy as np, trimesh
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "bev3d", "models", "e60.glb")
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "e60_top.png")
MPP = 0.0025          # 先用 2 倍解析度畫，最後縮小一半（抗鋸齒）
OUT_MPP = 0.005

sc = trimesh.load(SRC)
(x0, _, z0), (x1, _, z1) = sc.bounds
W = int(np.ceil((x1 - x0) / MPP)) + 4; H = int(np.ceil((z1 - z0) / MPP)) + 4
col = np.zeros((H, W, 4), np.float32); zb = np.full((H, W), -1e9, np.float32)
L = np.array([0.25, 1.0, 0.35]); L /= np.linalg.norm(L)
for node in sc.graph.nodes_geometry:
    T, gname = sc.graph[node]
    m = sc.geometry[gname].copy(); m.apply_transform(T)
    mat = getattr(m.visual, "material", None)
    base = np.array(getattr(mat, "baseColorFactor", None) if getattr(mat, "baseColorFactor", None) is not None else [128, 128, 128, 255], np.float32) / 255
    if base[3] < 0.3: continue                         # 幾乎全透明的不畫
    V = m.vertices; F = m.faces; N = m.face_normals
    # 螢幕座標：x 往右 = +x、y 往下 = -z（車頭朝上）
    px = (V[:, 0] - x0) / MPP + 2; py = (z1 - V[:, 2]) / MPP + 2; hy = V[:, 1]
    nl = np.abs(N @ L)                                  # 雙面光照：模型有些面的法線是反的
    shade = 0.45 + 0.55 * nl
    spec = nl ** 30 * 0.25
    for f, s, sp in zip(F, shade, spec):
        xs = px[f]; ys = py[f]; hs = hy[f]
        xa, xb = int(max(0, np.floor(xs.min()))), int(min(W - 1, np.ceil(xs.max())))
        ya, yb = int(max(0, np.floor(ys.min()))), int(min(H - 1, np.ceil(ys.max())))
        if xb < xa or yb < ya: continue
        gx, gy = np.meshgrid(np.arange(xa, xb + 1) + 0.5, np.arange(ya, yb + 1) + 0.5)
        (ax, bx, cx), (ay, by, cy) = xs, ys
        den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(den) < 1e-9: continue
        w1 = ((by - cy) * (gx - cx) + (cx - bx) * (gy - cy)) / den
        w2 = ((cy - ay) * (gx - cx) + (ax - cx) * (gy - cy)) / den
        w3 = 1 - w1 - w2
        inside = (w1 >= -1e-4) & (w2 >= -1e-4) & (w3 >= -1e-4)
        if not inside.any(): continue
        h = w1 * hs[0] + w2 * hs[1] + w3 * hs[2]
        sub = zb[ya:yb + 1, xa:xb + 1]; upd = inside & (h > sub + 0.004)   # 幾乎重疊的兩層殼：先畫的留著（避免雜紋）
        sub[upd] = h[upd]
        c = np.clip(base[:3] * s + sp, 0, 1)
        cb = col[ya:yb + 1, xa:xb + 1]; cb[upd, :3] = c; cb[upd, 3] = base[3]
img = Image.fromarray((col * 255).astype(np.uint8), "RGBA")
img = img.resize((W // 2, H // 2), Image.LANCZOS)
img.save(OUT); print("→", OUT, img.size, "每像素", OUT_MPP, "m")
