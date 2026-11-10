#!/usr/bin/env python3
"""產生道路畫面的車輛圖（360 引擎的 RoadView 用）：
本機開一個小網頁伺服器，headless Chrome 跑 render.html（每個 bev3d 模型 16 個方位），每個模型算好就 POST 回來，
存成一條 WebP（16 格橫排）＋ rs_index.json 到 app/src/main/assets/（合併版 build-b.sh 會放進 APK）。
用法：/opt/homebrew/bin/python3.14 tools/road_sprites/make.py
"""
import base64, functools, http.server, io, json, os, subprocess, sys, threading
from PIL import Image

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.abspath(os.path.join(here, "..", ".."))
outdir = os.path.join(root, "app", "src", "main", "assets")
chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
index, done = {}, threading.Event()
total = [0]

class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.path == "/save":
            d = json.loads(body)
            frames = [Image.open(io.BytesIO(base64.b64decode(f.split(",", 1)[1]))).convert("RGBA") for f in d.pop("frames")]
            s = frames[0].width
            strip = Image.new("RGBA", (s * len(frames), s))
            for k, f in enumerate(frames): strip.paste(f, (k * s, 0))
            name = d.pop("name")
            p = os.path.join(outdir, "rs_%s.webp" % name)
            strip.save(p, "WEBP", quality=82, method=6)
            total[0] += os.path.getsize(p)
            index[name] = {k: round(v, 3) for k, v in d.items()} | {"n": len(frames), "px": s}
            print("  ", name, "%.0f KB" % (os.path.getsize(p) / 1024), flush=True)
        elif self.path == "/done":
            done.set()
        self.send_response(200); self.end_headers(); self.wfile.write(b"ok")

srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(H, directory=root))
threading.Thread(target=srv.serve_forever, daemon=True).start()
url = "http://127.0.0.1:%d/tools/road_sprites/render.html" % srv.server_address[1]
ch = subprocess.Popen([chrome, "--headless=new", "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
                       "--user-data-dir=" + os.path.join(os.environ.get("TMPDIR", "/tmp"), "rs_chrome"), url],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
ok = done.wait(600)
ch.kill(); srv.shutdown()
if not ok: sys.exit("逾時：Chrome 沒有跑完")
with open(os.path.join(outdir, "rs_index.json"), "w") as f: json.dump(index, f, ensure_ascii=False, indent=1)
print("完成：%d 個模型，共 %.1f MB" % (len(index), total[0] / 1048576))
