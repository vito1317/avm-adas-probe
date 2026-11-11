#!/usr/bin/env python3
"""
試 UFLDv2（CULane 預訓練，MIT）當「老師」：前／後魚眼 → 轉成行車記錄器那種一般視角 → 找車道線 → 換回地面座標，
跟審查過的標註比。

用法：
  python tools/lane_label/ufld_teacher.py --ufld ~/avm-data/ext/ufld2 --weights ~/avm-data/ext/ufld2/weights/culane_res18.pth \
      --labels ~/avm-data/labels --review ~/avm-data/label_review --train ~/avm-data/train --n 20 --out ~/avm-data/ufld_test
輸出：每張一個檢查圖（轉正畫面＋UFLD 找到的線）、summary.json（跟審查結果的比對）。
"""
import argparse, glob, json, math, os, random, sys
import numpy as np
import torch
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import avm_geometry as ag   # noqa: E402

VW, VH = 1640, 590            # CULane 原始畫面大小
HFOV = 100.0                  # 轉正畫面的水平視角
PITCH = -12.0                 # 往下看幾度（保險桿高度低，往下一點才看得到近處的線）


def cam_to_fisheye(cam, X, Y, Z, qw, qh):
    """車身座標的 3D 點 → 魚眼象限像素（跟 ag.ground_to_pixel 同一套，只是 y 不固定在地面）。"""
    m = cam.m
    dX = X - cam.px; dY = Y - cam.py; dZ = Z - cam.pz
    x = m[0][0] * dX + m[1][0] * dY + m[2][0] * dZ
    y = m[0][1] * dX + m[1][1] * dY + m[2][1] * dZ
    z = m[0][2] * dX + m[1][2] * dY + m[2][2] * dZ
    r = np.sqrt(x * x + y * y)
    th = np.arctan2(r, z)
    t2 = th * th
    rd = th * (1.0 + cam.k1 * t2 + cam.k2 * t2 * t2)
    ca, sa = ag._unit_dir(x, y, r)
    u = cam.cx * qw + cam.fx * qw * rd * ca
    v = cam.cy * qh + cam.fy * qw * rd * sa
    if cam.mirror:
        u = qw - u
    ok = (th <= cam.max_rad) & (u >= 0) & (u < qw) & (v >= 0) & (v < qh)
    return u, v, ok


def virt_rays(back):
    """轉正畫面每個像素的射線（車身座標，單位向量）。back=True → 往後看。
    前鏡頭在保險桿上很低，車道線一下就從畫面兩側跑出去 → 前鏡頭用更廣的視角、少往下看。"""
    hfov = HFOV if back else 130.0
    f = (VW / 2) / math.tan(math.radians(hfov / 2))
    i = np.arange(VW) + 0.5 - VW / 2; j = np.arange(VH) + 0.5 - VH / 2
    I, J = np.meshgrid(i, j)
    # 相機座標：x 右、y 下、z 前
    cx, cy, cz = I / f, J / f, np.ones_like(I)
    p = math.radians(PITCH if back else -4.0)
    # 繞 x 軸往下轉：y 下為正
    yy = cy * math.cos(p) - cz * math.sin(p)
    zz = cy * math.sin(p) + cz * math.cos(p)
    X, Yup, Z = cx, -yy, zz
    if back:
        X, Z = -X, -Z
    n = np.sqrt(X * X + Yup * Yup + Z * Z)
    return X / n, Yup / n, Z / n


def make_view(frame, cam, back):
    X, Y, Z = virt_rays(back)
    u, v, ok = cam_to_fisheye(cam, cam.px + X * 10, cam.py + Y * 10, cam.pz + Z * 10, 960, 540)
    ox, oy = (cam.quad % 2) * 960, (cam.quad // 2) * 540
    ui = np.clip(u.astype(int), 0, 959) + ox; vi = np.clip(v.astype(int), 0, 539) + oy
    out = frame[vi, ui]
    out[~ok] = 0
    return out, (X, Y, Z)


def pix_to_ground(cam, rays, px, py):
    X, Y, Z = rays
    i = int(np.clip(px, 0, VW - 1)); j = int(np.clip(py, 0, VH - 1))
    dx, dy, dz = X[j, i], Y[j, i], Z[j, i]
    if dy >= -1e-3:
        return None
    t = cam.py / -dy
    return cam.px + dx * t, cam.pz + dz * t


def load_ufld(root, weights, dev):
    sys.path.insert(0, root)
    # 只做推論：utils.common 會載入 NVIDIA DALI（Mac 沒有），換成只有 initialize_weights 的替身
    import types
    stub = types.ModuleType("utils.common")
    def initialize_weights(*models):
        pass                                  # 權重等一下整個載入，不用初始化
    stub.initialize_weights = initialize_weights
    import utils as _u                        # noqa: F401（UFLD 的 utils 套件）
    sys.modules["utils.common"] = stub
    from model.model_culane import parsingNet
    net = parsingNet(pretrained=False, backbone='18', num_grid_row=200, num_cls_row=72, num_grid_col=100, num_cls_col=81,
                     num_lane_on_row=4, num_lane_on_col=4, use_aux=False, input_height=320, input_width=1600, fc_norm=True)
    sd = torch.load(weights, map_location='cpu')['model']
    net.load_state_dict({(k[7:] if k.startswith('module.') else k): v for k, v in sd.items()}, strict=False)
    return net.eval().to(dev)


def ufld_lanes(net, img, dev):
    """回傳 [ [(x,y),...] , ... ]（轉正畫面像素）。照 demo.py 的 pred2coords。"""
    im = Image.fromarray(img).resize((1600, int(320 / 0.6)), Image.BILINEAR)
    a = np.asarray(im, np.float32)[-320:] / 255.0
    a = (a - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
    x = torch.from_numpy(a.transpose(2, 0, 1)).float().unsqueeze(0).to(dev)
    with torch.no_grad():
        pred = net(x)
    row_anchor = np.linspace(0.42, 1, 72); col_anchor = np.linspace(0, 1, 81)
    lanes = []
    lr = pred['loc_row'].cpu(); vr = pred['exist_row'].argmax(1).cpu(); mr = lr.argmax(1)
    lc = pred['loc_col'].cpu(); vc = pred['exist_col'].argmax(1).cpu(); mc = lc.argmax(1)
    for i in [1, 2]:
        if vr[0, :, i].sum() > 72 / 2:
            pts = []
            for k in range(72):
                if vr[0, k, i]:
                    idx = torch.arange(max(0, mr[0, k, i] - 1), min(199, mr[0, k, i] + 1) + 1)
                    o = (lr[0, idx, k, i].softmax(0) * idx.float()).sum() + 0.5
                    pts.append((float(o) / 199 * VW, row_anchor[k] * VH))
            lanes.append(pts)
    for i in [0, 3]:
        if vc[0, :, i].sum() > 81 / 4:
            pts = []
            for k in range(81):
                if vc[0, k, i]:
                    idx = torch.arange(max(0, mc[0, k, i] - 1), min(99, mc[0, k, i] + 1) + 1)
                    o = (lc[0, idx, k, i].softmax(0) * idx.float()).sum() + 0.5
                    pts.append((col_anchor[k] * VW, float(o) / 99 * VH))
            lanes.append(pts)
    return lanes


def seg_dist(px, pz, l, ext=6.0):
    """點到審查過的線的距離；線往前後各延伸 ext 公尺（審查的線只在車身旁，UFLD 的點在車前車後幾公尺）。"""
    ax, az, bx, bz = l["x1"], l["z1"], l["x2"], l["z2"]
    dx, dz = bx - ax, bz - az; L = math.hypot(dx, dz) or 1e-9
    ux, uz = dx / L, dz / L
    t = max(-ext, min(L + ext, (px - ax) * ux + (pz - az) * uz))
    return math.hypot(px - (ax + t * ux), pz - (az + t * uz))


def main():
    ap = argparse.ArgumentParser()
    for k in ["ufld", "weights", "labels", "review", "train", "out"]:
        ap.add_argument("--" + k, required=True)
    ap.add_argument("--n", type=int, default=20)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    net = load_ufld(a.ufld, a.weights, dev)
    labels = []
    for f in sorted(glob.glob(os.path.join(a.labels, "out_*.jsonl"))):
        labels += [json.loads(x) for x in open(f) if x.strip()]
    good = [L for L in labels if L.get("ok", True) and L.get("keep")]
    random.Random(3).shuffle(good)
    picks = good[:a.n]
    geo_c = {}
    summ = []
    for L in picks:
        base = L["img"][:-4]
        cand = json.load(open(os.path.join(a.review, base + ".cand.json")))
        sess, fr = cand["session"], cand["frame"]
        if sess not in geo_c:
            geo_c[sess] = ag.Geometry(json.load(open(os.path.join(a.train, sess, "session.json")))["calib"])
        geo = geo_c[sess]
        frame = np.asarray(Image.open(os.path.join(a.train, sess, "f_%05d.jpg" % fr)).convert("RGB"))
        kept = [c for c in cand["cands"] if str(c["id"]) in L["keep"]]
        rec = dict(img=L["img"], speed=cand.get("speed"), kept=len(kept), views={})
        tiles = []
        for name, quad, back in [("front", 0, False), ("rear", 1, True)]:
            cam = geo.cam_for_quad(quad)
            view, rays = make_view(frame, cam, back)
            lanes = ufld_lanes(net, view, dev)
            im = Image.fromarray(view); d = ImageDraw.Draw(im)
            gl = []
            for ln in lanes:
                d.line(ln, fill=(0, 255, 0), width=5)
                g = [pix_to_ground(cam, rays, x, y) for x, y in ln]
                g = [p for p in g if p is not None and math.hypot(p[0] - cam.px, p[1] - cam.pz) < 30]
                gl.append(g)
            # 比對：只看離車身近（|z| < 6 m）的點；UFLD 每條線有沒有對上某條審查過的線（中位距離 < 0.35 m）
            match, near_lanes = 0, 0
            covered = set()
            for g in gl:
                pts = [p for p in g if abs(p[1]) < 6.5 and abs(p[0]) < 5]
                if len(pts) < 3: continue
                near_lanes += 1
                best = None
                for c in kept:
                    dmed = float(np.median([seg_dist(px, pz, c) for px, pz in pts]))
                    if best is None or dmed < best[0]: best = (dmed, c["id"])
                if best and best[0] < 0.35:
                    match += 1; covered.add(best[1])
            rec["views"][name] = dict(ufld_lanes=len(lanes), near=near_lanes, matched=match, covered=sorted(covered))
            tiles.append(im.resize((820, 295)))
        rec["kept_covered"] = len(set(rec["views"]["front"]["covered"]) | set(rec["views"]["rear"]["covered"]))
        canvas = Image.new("RGB", (820, 600), (0, 0, 0)); canvas.paste(tiles[0], (0, 0)); canvas.paste(tiles[1], (0, 305))
        canvas.save(os.path.join(a.out, base + "_ufld.jpg"), quality=85)
        summ.append(rec)
        print(base, rec["views"], "審查留下", len(kept), "條、UFLD 對上", rec["kept_covered"], flush=True)
    tot_near = sum(r["views"][v]["near"] for r in summ for v in r["views"]); tot_m = sum(r["views"][v]["matched"] for r in summ for v in r["views"])
    tot_k = sum(r["kept"] for r in summ); tot_c = sum(r["kept_covered"] for r in summ)
    res = dict(frames=len(summ), ufld_near_lanes=tot_near, ufld_matched=tot_m, kept=tot_k, kept_covered=tot_c)
    json.dump(dict(summary=res, frames=summ), open(os.path.join(a.out, "summary.json"), "w"), ensure_ascii=False, indent=1)
    print("合計", res)


if __name__ == "__main__":
    main()
