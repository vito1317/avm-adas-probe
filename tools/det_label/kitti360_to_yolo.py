#!/usr/bin/env python3
"""
KITTI-360 側面魚眼（image_02 左、image_03 右，1400x1400，MEI 模型）＋ 3D 物件框 → YOLO 訓練資料。

每一幀：物件的 3D 框（世界座標）→ 用車輛位置（poses.txt，IMU→世界）與鏡頭外參（calib_cam_to_pose.txt）換到鏡頭座標
→ MEI 魚眼投影 8 個角點 → 外接矩形當 2D 框。
- 靜態物件（timestamp = -1）在 start_frame～end_frame 之間都算；動態物件只算 timestamp 那一幀。
- 只留離鏡頭 MAX_DIST 內、在鏡頭前方半球的；被更近的框蓋住大半（> OCC）的不要（3D 框沒有遮擋資訊）。
- 類別對到我們的：car／truck／bus／motorcycle／bicycle／person（行人、騎士）。
影像縮成 640x640 存成 jpg（跟 WoodScape 裁切一樣大小）。

KITTI-360 授權：非商業研究（CC BY-NC-SA 3.0）；產生的資料與權重只供個人用、不散布。
用法：python tools/det_label/kitti360_to_yolo.py --root ~/avm-data/ext/kitti360/KITTI-360 --seqs 0003,0007,0010 --out ~/avm-data/det_k360 --every 5
"""
import argparse, glob, os, xml.etree.ElementTree as ET
import numpy as np
from PIL import Image

MAP = {"car": 2, "truck": 5, "bus": 4, "caravan": 5, "trailer": 5, "motorcycle": 3, "bicycle": 1, "pedestrian": 0, "rider": 0}
MAX_DIST = 25.0
OCC = 0.7
OUT = 640


def mat(node):
    rows = int(node.find("rows").text); cols = int(node.find("cols").text)
    return np.array([float(v) for v in node.find("data").text.split()], np.float64).reshape(rows, cols)


def load_boxes(xml):
    out = []
    for o in ET.parse(xml).getroot():
        lab = o.find("label")
        if lab is None or lab.text not in MAP: continue
        T = mat(o.find("transform")); V = mat(o.find("vertices"))
        corners = (T[:3, :3] @ V.T).T + T[:3, 3]
        ts = int(o.find("timestamp").text)
        sf = int(o.find("start_frame").text) if o.find("start_frame") is not None else ts
        ef = int(o.find("end_frame").text) if o.find("end_frame") is not None else ts
        out.append(dict(c=MAP[lab.text], corners=corners, ts=ts, sf=sf, ef=ef))
    return out


def load_calib(root):
    cam2pose = {}
    for line in open(os.path.join(root, "calibration", "calib_cam_to_pose.txt")):
        k, v = line.split(":"); m = np.eye(4); m[:3, :4] = np.array([float(x) for x in v.split()]).reshape(3, 4); cam2pose[k.strip()] = m
    intr = {}
    for cam in ("image_02", "image_03"):
        d = {}
        for line in open(os.path.join(root, "calibration", cam + ".yaml")):
            if ":" in line:
                k, v = line.split(":", 1); v = v.strip()
                try: d[k.strip()] = float(v)
                except ValueError: pass
        intr[cam] = d
    return cam2pose, intr


def project(P, d):
    """鏡頭座標 Nx3 → 像素 (u, v)；MEI：單位球 → 加 xi → 徑向畸變 → 內參。"""
    n = np.linalg.norm(P, axis=1, keepdims=True); p = P / n
    x = p[:, 0] / (p[:, 2] + d["xi"]); y = p[:, 1] / (p[:, 2] + d["xi"])
    r2 = x * x + y * y; f = 1 + d["k1"] * r2 + d["k2"] * r2 * r2
    return d["gamma1"] * x * f + d["u0"], d["gamma2"] * y * f + d["v0"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True); ap.add_argument("--seqs", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--every", type=int, default=5); ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    cam2pose, intr = load_calib(a.root)
    for sub in ("images/train", "labels/train"): os.makedirs(os.path.join(a.out, sub), exist_ok=True)
    stat = {}
    for s in a.seqs.split(","):
        seq = f"2013_05_28_drive_{s}_sync"
        boxes = load_boxes(os.path.join(a.root, "data_3d_bboxes", "train", seq + ".xml"))
        poses = {}
        for line in open(os.path.join(a.root, seq, "poses.txt")):
            q = line.split(); m = np.eye(4); m[:3, :4] = np.array([float(x) for x in q[1:]]).reshape(3, 4); poses[int(q[0])] = m
        frames = sorted(poses)[::a.every]
        if a.limit: frames = frames[:a.limit]
        n_img = 0
        for fr in frames:
            for cam in ("image_02", "image_03"):
                ip = os.path.join(a.root, "data_2d_raw", seq, cam, "data_rgb", "%010d.png" % fr)
                if not os.path.exists(ip): continue
                w2c = np.linalg.inv(poses[fr] @ cam2pose[cam]); d = intr[cam]
                W = int(d.get("image_width", 1400)); H = int(d.get("image_height", 1400))
                cands = []
                for b in boxes:
                    if b["ts"] >= 0: ok = b["ts"] == fr
                    else: ok = b["sf"] <= fr <= b["ef"]
                    if not ok: continue
                    P = (w2c[:3, :3] @ b["corners"].T).T + w2c[:3, 3]
                    dist = np.linalg.norm(P.mean(0))
                    if dist > MAX_DIST or P[:, 2].max() < 0.3: continue
                    u, v = project(P, d)
                    x1, y1, x2, y2 = max(0, u.min()), max(0, v.min()), min(W, u.max()), min(H, v.max())
                    if x2 - x1 < 12 or y2 - y1 < 12 or (x2 - x1) * (y2 - y1) > 0.5 * W * H: continue
                    cands.append((dist, b["c"], [x1, y1, x2, y2]))
                cands.sort()
                keep = []
                for dist, c, bb in cands:
                    area = (bb[2] - bb[0]) * (bb[3] - bb[1]); cov = 0.0
                    for _, _, k in keep:
                        ix = max(0, min(bb[2], k[2]) - max(bb[0], k[0])); iy = max(0, min(bb[3], k[3]) - max(bb[1], k[1]))
                        cov = max(cov, ix * iy / area)
                    if cov > OCC: continue
                    keep.append((dist, c, bb))
                name = f"k360_{s}_{cam[-2:]}_{fr:06d}"
                Image.open(ip).convert("RGB").resize((OUT, OUT), Image.BILINEAR).save(os.path.join(a.out, "images", "train", name + ".jpg"), quality=90)
                with open(os.path.join(a.out, "labels", "train", name + ".txt"), "w") as f:
                    for _, c, (x1, y1, x2, y2) in keep:
                        f.write("%d %.6f %.6f %.6f %.6f\n" % (c, (x1 + x2) / 2 / W, (y1 + y2) / 2 / H, (x2 - x1) / W, (y2 - y1) / H))
                        stat[c] = stat.get(c, 0) + 1
                n_img += 1
        print(seq, "影像", n_img, "累計框", stat, flush=True)


if __name__ == "__main__":
    main()
