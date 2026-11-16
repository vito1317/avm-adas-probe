#!/usr/bin/env python3
"""
車道線模型的「誤判候選」：拿目前的模型跑大量還沒標過的影格，把它在地面上（自車周圍、俯視圖範圍內）
畫成線的每一塊切出來，排成審查格子圖，讓審查者逐塊判斷是不是真的車道線。

為什麼：原本的標註只審過離鏡頭 3 m 內的地面，3 m 外訓練時「不計分」——模型從沒被教過那邊哪裡沒有線，
可是車機的道路畫面用到 6～7 m。路邊、車身反光、地平線、草地交界就這樣被畫成線，白天晚上都有。
審查結果（tile → lane / other_paint / not_paint / unsure）由 hardneg_ds.py 變成只在這些區塊計分的訓練資料。

輸出 OUT/：
  img/<name>.jpg        鏡頭影像（480x272，跟 build_masks 一樣的裁切）
  comp/<name>.npz       區塊編號圖（uint16）與機率圖（uint8，×255）
  grids/G0001.png …     審查格子圖（每張 12 塊，編號 1–12）
  tiles.json            [{grid, tile, name, comp, area, dmin, dmax, bright}]
用法：
  python tools/lane_label/far_cands.py --train ~/avm-data/train --ckpt ~/avm-data/lane_runs/v7/best.pt \
      --out ~/avm-data/lane_hn --frames 700 --size 224x128
實車影像只放在本機，不要 commit。
"""
import argparse, glob, json, os, random, sys
import numpy as np, torch
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE); sys.path.insert(0, os.path.dirname(HERE))
import train as T            # noqa: E402
import avm_geometry as ag    # noqa: E402

OW, OH = 480, 272
BEV_X, BEV_Z = 6.0, 7.0      # 車機俯視圖範圍（300x350 @ 0.04 m/px）
MIN_AREA = 30                # 太小的碎點不審
PER_CAM = 3                  # 每顆鏡頭最多幾塊（大的優先）
TILES = 12


def labeled_frames(avm):
    done = set()
    for f in glob.glob(os.path.join(avm, "label_review*", "*.cand.json")):
        b = os.path.basename(f)[:-10]; s, fr = b.rsplit("_", 1); done.add((s, int(fr)))
    return done


def ds3_val_groups(avm):
    p = os.path.join(avm, "lane_ds3", "index.json")
    if not os.path.exists(p): return set()
    _, va = T.split(json.load(open(p)))
    return {(it["session"], it["frame"] // 150) for it in va}


def pick(train, n, seed, avm):
    done = labeled_frames(avm); vg = ds3_val_groups(avm)
    items = []
    for sd in sorted(glob.glob(os.path.join(train, "train_*"))):
        s = os.path.basename(sd); last = -99
        for jf in sorted(glob.glob(os.path.join(sd, "f_*.json"))):
            fr = int(os.path.basename(jf)[2:7])
            if not os.path.exists(jf[:-5] + ".jpg"): continue
            if (s, fr // 150) in vg or any((s, fr + d) in done for d in range(-1, 2)): continue
            try: m = json.load(open(jf))
            except Exception: continue
            if m.get("gear") == "P" or (m.get("speed") or 0) < 5 or m.get("lot"): continue
            if fr - last < 10: continue          # 同一段路不要挑太密
            last = fr; items.append((sd, fr))
    # 每段最多佔 30%（不然一段長的早上就佔掉大半，晚上的誤判學不到）
    rnd = random.Random(seed); rnd.shuffle(items)
    cap = max(1, int(n * 0.3)); cnt, out = {}, []
    for sd, fr in items:
        if cnt.get(sd, 0) >= cap: continue
        cnt[sd] = cnt.get(sd, 0) + 1; out.append((sd, fr))
        if len(out) >= n: break
    return sorted(out)


def calib_for(sd):
    """這段的校正；沒有（session.json 還沒拉到、raw 也沒有）→ 用時間上最接近、在它之前的那段（校正很少變）。"""
    from candidates import load_calib
    try: return load_calib(sd)
    except SystemExit: pass
    prev = [d for d in sorted(glob.glob(os.path.join(os.path.dirname(sd), "train_*"))) if d < sd and os.path.exists(os.path.join(d, "session.json"))]
    if not prev: raise SystemExit("找不到校正：" + sd)
    return json.load(open(os.path.join(prev[-1], "session.json")))["calib"]


def cam_geom(geo):
    """每顆鏡頭輸出解析度下：地面座標、離鏡頭距離、可用區域（打到地面、俯視圖範圍內、不是車身）。"""
    us = (np.arange(OW) + 0.5) * 2.0; vs = (np.arange(OH) + 0.5) * 2.0
    U, V = np.meshgrid(us, vs); out = {}
    for cam in geo.cams:
        gx, gz, ok, _ = ag.pixel_to_ground(cam, U.ravel(), V.ravel(), 960, 540)
        gx = gx.reshape(OH, OW); gz = gz.reshape(OH, OW); ok = ok.reshape(OH, OW) & (V < 540)
        body = (np.abs(gx) <= geo.car_width / 2 + 0.1) & (np.abs(gz) <= geo.car_length / 2 + 0.1)
        use = ok & ~body & (np.abs(gx) <= BEV_X) & (np.abs(gz) <= BEV_Z)
        d = np.hypot(np.nan_to_num(gx) - cam.px, np.nan_to_num(gz) - cam.pz)
        out[cam.quad] = (use, d)
    return out


def tile_img(canvas, lab, cid, font):
    ys, xs = np.nonzero(lab == cid)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    w = max(160, (x1 - x0) + 50); h = max(120, (y1 - y0) + 40)
    if w / h > 4 / 3: h = w * 3 / 4
    else: w = h * 4 / 3
    bx0 = int(max(0, min(OW - w, cx - w / 2))); by0 = int(max(0, min(OH - h, cy - h / 2)))
    box = (bx0, by0, int(min(OW, bx0 + w)), int(min(OH, by0 + h)))
    raw = canvas.crop(box).resize((200, 150), Image.BILINEAR)
    ov = np.asarray(canvas).copy(); msk = lab == cid
    edge = msk & ~ndimage.binary_erosion(msk, iterations=1)
    ov[msk] = (ov[msk] * 0.55 + np.array([255, 0, 255]) * 0.45).astype(np.uint8); ov[edge] = [255, 0, 255]
    ovc = Image.fromarray(ov).crop(box).resize((200, 150), Image.BILINEAR)
    th = Image.fromarray(ov).resize((200, 113), Image.BILINEAR); d = ImageDraw.Draw(th)
    k = 200 / OW; d.rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k], outline=(255, 255, 0), width=2)
    t = Image.new("RGB", (612, 156), (30, 30, 30)); t.paste(th, (2, 20)); t.paste(raw, (206, 3)); t.paste(ovc, (410, 3))
    return t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train", required=True); ap.add_argument("--ckpt", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--frames", type=int, default=700); ap.add_argument("--size", default="224x128"); ap.add_argument("--seed", type=int, default=5)
    ap.add_argument("--thr", type=float, default=0.5)
    a = ap.parse_args()
    avm = os.path.dirname(os.path.abspath(a.train.rstrip("/")))
    for d in ("img", "comp", "grids"): os.makedirs(os.path.join(a.out, d), exist_ok=True)
    dev = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    net = T.LaneNet(pretrained=False); net.load_state_dict(torch.load(a.ckpt, map_location="cpu")); net.to(dev).eval()
    sz = tuple(int(v) for v in a.size.split("x"))
    frames = pick(a.train, a.frames, a.seed, avm)
    print("影格", len(frames), flush=True)
    geo_cache, gcache = {}, {}
    tiles = []
    for k, (sd, fr) in enumerate(frames):
        s = os.path.basename(sd)
        if s not in geo_cache:
            geo_cache[s] = ag.Geometry(calib_for(sd)); gcache[s] = cam_geom(geo_cache[s])
        try: frame = Image.open(os.path.join(sd, "f_%05d.jpg" % fr)).convert("RGB")
        except Exception: continue
        for q in range(4):
            ox, oy = (q % 2) * 960, (q // 2) * 540
            canvas = Image.new("RGB", (OW, OH)); canvas.paste(frame.crop((ox, oy, ox + 960, oy + 540)).resize((OW, 270), Image.BILINEAR), (0, 0))
            x = (torch.from_numpy(np.asarray(canvas.resize(sz, Image.BILINEAR), np.float32) / 255).permute(2, 0, 1) - T.MEAN) / T.STD
            with torch.no_grad():
                pr = torch.softmax(net(x.unsqueeze(0).to(dev)), 1)[:, 1:2]
                pr = torch.nn.functional.interpolate(pr, size=(OH, OW), mode="bilinear", align_corners=False)[0, 0].cpu().numpy()
            use, dist = gcache[s][q]
            lab, n = ndimage.label((pr >= a.thr) & use, structure=np.ones((3, 3)))
            if n == 0: continue
            areas = ndimage.sum(np.ones_like(lab), lab, index=np.arange(1, n + 1))
            ids = [i + 1 for i in np.argsort(-areas) if areas[i] >= MIN_AREA][:PER_CAM]
            if not ids: continue
            name = f"{s}_{fr:05d}_q{q}"
            canvas.save(os.path.join(a.out, "img", name + ".jpg"), quality=92)
            np.savez_compressed(os.path.join(a.out, "comp", name + ".npz"), lab=lab.astype(np.uint16), prob=(pr * 255).astype(np.uint8))
            br = float(np.asarray(canvas.convert("L")).mean())
            for cid in ids:
                dd = dist[lab == cid]
                tiles.append(dict(name=name, comp=int(cid), area=int(areas[cid - 1]), dmin=round(float(dd.min()), 1), dmax=round(float(dd.max()), 1), bright=round(br)))
        if k % 50 == 0: print(k, "/", len(frames), "區塊", len(tiles), flush=True)
    # 排成格子圖（打散順序，同一格裡不要都是同一張影格）
    random.Random(1).shuffle(tiles)
    try: font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 22)
    except Exception: font = ImageFont.load_default()
    labcache = {}
    for g in range(0, len(tiles), TILES):
        gid = "G%04d" % (g // TILES + 1)
        sheet = Image.new("RGB", (2 * 616, 6 * 160), (0, 0, 0)); dr = ImageDraw.Draw(sheet)
        for j, t in enumerate(tiles[g:g + TILES]):
            if t["name"] not in labcache:
                labcache.clear()
                labcache[t["name"]] = (np.load(os.path.join(a.out, "comp", t["name"] + ".npz"))["lab"], Image.open(os.path.join(a.out, "img", t["name"] + ".jpg")).convert("RGB"))
            lab, canvas = labcache[t["name"]]
            ti = tile_img(canvas, lab, t["comp"], font)
            px, py = (j % 2) * 616, (j // 2) * 160
            sheet.paste(ti, (px, py)); dr.text((px + 6, py + 0), str(j + 1), fill=(255, 255, 0), font=font)
            t["grid"], t["tile"] = gid, j + 1
        sheet.save(os.path.join(a.out, "grids", gid + ".png"))
    json.dump(tiles, open(os.path.join(a.out, "tiles.json"), "w"))
    print("完成：區塊", len(tiles), "格子圖", (len(tiles) + TILES - 1) // TILES)


if __name__ == "__main__":
    main()
