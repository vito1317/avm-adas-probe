#!/usr/bin/env python3
"""
魚眼道路線標註：產生「候選線＋檢查圖」。

每張訓練影格（train_*/f_xxxxx.jpg + .json）：
  1. 用該批的校正拼出俯視圖（300x350、0.04 m/px，跟車機一樣）。
  2. 候選線 = 車機當時抓到的線 ＋ 放寬條件重跑 perception_proto 的線（看得更遠、更短的也收），去重後編號。
  3. 檢查圖：左邊俯視圖、右邊四顆魚眼原圖，同一條線同一個顏色、同一個編號。人（或審查者）看圖決定每條留不留。

用法：
  python tools/lane_label/candidates.py SESSION_DIR --frames 12,40,77 --out OUT_DIR
輸出 OUT_DIR/<session>_<frame>.png（檢查圖）與 .cand.json（候選線，車身座標 m）。
實車影像只放在本機，不要 commit。
"""
import argparse, json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import avm_geometry as ag          # noqa: E402
import perception_proto as pp      # noqa: E402

W, H, MPP, CX, CY = 300, 350, 0.04, 150.0, 175.0
EGO = [[-1.075, -2.57, 1.075, 2.57]]
COLORS = [(255, 59, 48), (52, 199, 89), (0, 122, 255), (255, 149, 0), (175, 82, 222), (255, 45, 85), (90, 200, 250),
          (255, 204, 0), (88, 86, 214), (162, 132, 94), (0, 199, 190), (255, 255, 255), (142, 142, 147), (48, 209, 88),
          (191, 90, 242), (100, 210, 255)]
LOOSE = dict(pp.TUNED, maxLines=16, minLen=0.30, minCovered=0.5, minSeg=0.35, tW=18, minArea=0.02)


def font(sz):
    for p in ["/System/Library/Fonts/STHeiti Medium.ttc", "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"]:
        if os.path.exists(p):
            return ImageFont.truetype(p, sz)
    return ImageFont.load_default()


def load_calib(session_dir):
    p = os.path.join(session_dir, "session.json")
    if os.path.exists(p):
        return json.load(open(p))["calib"]
    # 沒有 session.json（還沒拉到）→ 用同一時間的 raw_ 檔
    ts = os.path.basename(session_dir.rstrip("/")).replace("train_", "")
    for q in [os.path.expanduser(f"~/avm-data/raw/raw_{ts}.json")]:
        if os.path.exists(q):
            return json.load(open(q))["calib"]
    raise SystemExit(f"找不到 {session_dir} 的校正")


def dedupe(lines):
    out = []
    for l in lines:
        mx, mz = (l["x1"] + l["x2"]) / 2, (l["z1"] + l["z2"]) / 2
        a = math.atan2(l["x2"] - l["x1"], l["z2"] - l["z1"]) % math.pi
        dup = False
        for o in out:
            ox, oz = (o["x1"] + o["x2"]) / 2, (o["z1"] + o["z2"]) / 2
            b = math.atan2(o["x2"] - o["x1"], o["z2"] - o["z1"]) % math.pi
            da = min(abs(a - b), math.pi - abs(a - b))
            # 中點到對方直線的距離
            dx, dz = o["x2"] - o["x1"], o["z2"] - o["z1"]; L = math.hypot(dx, dz) or 1
            dist = abs(dx * (mz - o["z1"]) - dz * (mx - o["x1"])) / L
            if da < math.radians(6) and dist < 0.25 and math.hypot(mx - ox, mz - oz) < max(L, 2):
                dup = True; break
        if not dup:
            out.append(l)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("session")
    ap.add_argument("--frames", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    geo = ag.Geometry(load_calib(a.session))
    lut = ag.build_bev_lut(geo, 1920, 1080)
    ipm = pp.ipm_valid_mask(geo, W, H, MPP, CX, CY, min_elev_deg=8.0)
    sess = os.path.basename(a.session.rstrip("/"))
    f_big, f_sm = font(18), font(14)
    for fr in [int(x) for x in a.frames.split(",")]:
        base = os.path.join(a.session, "f_%05d" % fr)
        frame = np.asarray(Image.open(base + ".jpg").convert("RGB"))
        meta = json.load(open(base + ".json"))
        bev = ag.stitch(frame, lut)
        rgb = bev.astype(np.int64)
        argb = ((0xFF << 24) | (rgb[..., 0] << 16) | (rgb[..., 1] << 8) | rgb[..., 2]).reshape(-1)
        res = pp.road_extract(argb, W, H, MPP, CX, CY, EGO, P=LOOSE, ipm=ipm)
        dev = [dict(k=l["k"], d=bool(l.get("d")), x1=l["x1"], z1=l["z1"], x2=l["x2"], z2=l["z2"], src="dev") for l in meta.get("lines", [])]
        loose = [dict(k=l["k"], d=bool(l["d"]), x1=l["x1"], z1=l["z1"], x2=l["x2"], z2=l["z2"], src="loose") for l in res["lines"]]
        cands = dedupe(dev + loose)
        for i, c in enumerate(cands):
            c["id"] = i + 1
        # ── 檢查圖 ──
        bimg = Image.fromarray(bev.astype(np.uint8)).resize((W * 2, H * 2), Image.NEAREST)
        d = ImageDraw.Draw(bimg)
        P = lambda x, z: ((CX + x / MPP) * 2, (CY - z / MPP) * 2)
        for c in cands:
            col = COLORS[(c["id"] - 1) % len(COLORS)]
            d.line([P(c["x1"], c["z1"]), P(c["x2"], c["z2"])], fill=col, width=3)
            mx, mz = (c["x1"] + c["x2"]) / 2, (c["z1"] + c["z2"]) / 2
            px, py = P(mx, mz)
            d.rectangle([px - 11, py - 11, px + 11, py + 11], fill=(0, 0, 0)); d.text((px - 7, py - 10), str(c["id"]), fill=col, font=f_big)
        quad = Image.fromarray(frame).resize((960, 540))
        dq = ImageDraw.Draw(quad)
        qw, qh = 960, 540
        for cam in geo.cams:
            ox, oy = (cam.quad % 2) * (qw // 2), (cam.quad // 2) * (qh // 2)
            for c in cands:
                col = COLORS[(c["id"] - 1) % len(COLORS)]
                t = np.linspace(0, 1, 60)
                gx = c["x1"] + (c["x2"] - c["x1"]) * t; gz = c["z1"] + (c["z2"] - c["z1"]) * t
                u, v, th, ok, _ = ag.ground_to_pixel(cam, gx, gz, qw // 2, qh // 2)
                pts = [(ox + uu, oy + vv) for uu, vv, kk in zip(u, v, ok) if kk]
                if len(pts) >= 2:
                    dq.line(pts, fill=col, width=2)
                    mxp, myp = pts[len(pts) // 2]
                    dq.text((mxp + 3, myp - 8), str(c["id"]), fill=col, font=f_sm)
        canvas = Image.new("RGB", (W * 2 + 960 + 10, max(H * 2, 540) + 30), (20, 20, 20))
        canvas.paste(bimg, (0, 30)); canvas.paste(quad, (W * 2 + 10, 30))
        dc = ImageDraw.Draw(canvas)
        dc.text((6, 6), f"{sess} f{fr:05d}  speed={meta.get('speed')} gear={meta.get('gear')} turn={meta.get('turn')} lot={meta.get('lot')}  "
                        f"左：俯視圖（上=車頭）右：魚眼 左上前/右上後/左下左/右下右", fill=(230, 230, 230), font=f_sm)
        out = os.path.join(a.out, f"{sess}_{fr:05d}")
        canvas.save(out + ".png")
        json.dump(dict(session=sess, frame=fr, speed=meta.get("speed"), cands=cands), open(out + ".cand.json", "w"))
        print(out, len(cands), "條候選")


if __name__ == "__main__":
    main()
