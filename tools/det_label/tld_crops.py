#!/usr/bin/env python3
"""
TLD（Tail Light Dataset）→ 車燈分類器的訓練裁切（不解壓整個 zip，直接從 zip 讀圖，只存車框裁切）。

TLD-YT：labelme JSON，label = car_BrakeOn／car_BrakeOff（或 BrakeUnknown），attributes.turn_signal = off／left／right／both／unknown。
TLD 都是從後面拍前車：車子自己的左邊 = 畫面左邊，跟我們的標法（畫面左右）一致。
TLD-LOKI：COCO，類別 brake／go／right／vehicle（標法比較粗，這裡先不用）。

輸出：out/img/*.jpg（96x96，車框四周加 15%、等比縮放、補灰 114，跟 360 端一樣）＋ out/labels.csv：
  name,brake,left,right,src   （1／0／-1 = 不知道）

注意：TLD 大多來自 YouTube 影片（著作權屬原上傳者），只供個人訓練，模型只放私有 release。
用法：python tools/det_label/tld_crops.py --zips ~/avm-data/ext/tld/TLD-YT-part1.zip,~/avm-data/ext/tld/TLD-YT-part2.zip --out ~/avm-data/lamp_ext/tld
"""
import argparse, io, json, os, zipfile
from PIL import Image

S = 96
MIN_W = 40


def letterbox(im, box):
    x1, y1, x2, y2 = box
    pw, ph = (x2 - x1) * 0.15, (y2 - y1) * 0.15
    c = im.crop((max(0, x1 - pw), max(0, y1 - ph), min(im.size[0], x2 + pw), min(im.size[1], y2 + ph)))
    sc = min(S / c.size[0], S / c.size[1])
    c = c.resize((max(1, round(c.size[0] * sc)), max(1, round(c.size[1] * sc))), Image.BILINEAR)
    out = Image.new("RGB", (S, S), (114, 114, 114)); out.paste(c, ((S - c.size[0]) // 2, (S - c.size[1]) // 2))
    return out


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--zips", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--every", type=int, default=1, help="每幾個標註檔取一個（影片相鄰幀很像）")
    a = ap.parse_args()
    os.makedirs(os.path.join(a.out, "img"), exist_ok=True)
    csv = open(os.path.join(a.out, "labels.csv"), "a")
    n = 0; stat = {}
    for zp in a.zips.split(","):
        z = zipfile.ZipFile(os.path.expanduser(zp))
        jsons = sorted(x for x in z.namelist() if x.endswith(".json") and not x.endswith("coco.json"))
        for k, jn in enumerate(jsons[::a.every]):
            try: d = json.loads(z.read(jn))
            except Exception: continue
            imn = os.path.join(os.path.dirname(jn), d.get("imagePath", ""))
            try: im = Image.open(io.BytesIO(z.read(imn))).convert("RGB")
            except Exception: continue
            for si, sh in enumerate(d.get("shapes", [])):
                pts = sh.get("points") or []
                if len(pts) < 2: continue
                xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
                box = (min(xs), min(ys), max(xs), max(ys))
                if box[2] - box[0] < MIN_W: continue
                lab = sh.get("label", "")
                brake = 1 if "BrakeOn" in lab else 0 if "BrakeOff" in lab else -1
                ts = (sh.get("attributes") or {}).get("turn_signal", "unknown")
                left = 1 if ts in ("left", "both") else 0 if ts in ("off", "right") else -1
                right = 1 if ts in ("right", "both") else 0 if ts in ("off", "left") else -1
                name = "%s_%d" % (jn[:-5].replace("/", "_"), si)
                letterbox(im, box).save(os.path.join(a.out, "img", name + ".jpg"), quality=90)
                csv.write("%s,%d,%d,%d,tld\n" % (name, brake, left, right)); n += 1
                key = (brake, ts); stat[key] = stat.get(key, 0) + 1
            if k % 2000 == 0: print(zp.split("/")[-1], k, len(jsons) // a.every, "裁切", n, flush=True)
        csv.flush()
    print("完成", n, sorted(stat.items(), key=lambda t: -t[1])[:12])


if __name__ == "__main__":
    main()
