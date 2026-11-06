#!/usr/bin/env bash
# =====================================================================
# build-b.sh — 把原廠 baony 360 改成「影格共用」版（B 模式）
#
#   原廠 360 原封不動 + 注入 com.vito.FrameTap：
#     - bowl view 啟動時把我們的 OverlayService 叫起來
#     - 原廠建相機 session 時，把我們的 Surface 加進去（一次開鏡頭、兩邊共用影格）
#   安全：旗標檔 /sdcard/avm-adas/frameshare.on 不存在時，注入的程式完全不碰相機。
#
# 用法：
#   ./build-b.sh <原廠 360 APK>                                → avm360-b-signed.apk（B 模式，要搭配我們的 App）
#   ./build-b.sh <原廠 360 APK> <engine360 APK> <App assets 目錄> → avm360-merged-signed.apk（合併版，只裝這一個）
# 都用 AOSP platform key 簽，可直接蓋回原廠。
#
# 需要：apktool、JDK(javac)、Android build-tools(d8/zipalign/apksigner)、curl、openssl
# =====================================================================
set -euo pipefail

APK="${1:?用法: ./build-b.sh <原廠 360 APK> [engine360 APK] [App assets 目錄]}"
ENGINE="${2:-}"
ASSETS="${3:-}"
if [ -n "$ENGINE" ]; then
  [ -f "$ENGINE" ] || { echo "找不到 engine APK：$ENGINE"; exit 1; }
  [ -d "$ASSETS" ] || { echo "合併版要給 App 的 assets 目錄（模型）"; exit 1; }
  OUT="avm360-merged-signed.apk"
else
  OUT="avm360-b-signed.apk"
fi
WORK="$(mktemp -d)"
SELF="$(cd "$(dirname "$0")" && pwd)"

echo "== 1/7 檢查工具"
command -v apktool >/dev/null || { echo "缺 apktool（brew install apktool）"; exit 1; }
command -v javac   >/dev/null || { echo "缺 javac（裝 JDK 17）"; exit 1; }
command -v curl    >/dev/null || { echo "缺 curl"; exit 1; }
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT="$(ls -d "$SDK/build-tools"/* 2>/dev/null | sort -V | tail -1)"
ANDJAR="$SDK/platforms/android-34/android.jar"
[ -x "$BT/d8" ] || { echo "找不到 d8（$SDK/build-tools）"; exit 1; }
[ -f "$ANDJAR" ] || ANDJAR="$(ls "$SDK"/platforms/*/android.jar | sort -V | tail -1)"
[ -f "$ANDJAR" ] || { echo "找不到 android.jar"; exit 1; }

echo "== 2/7 反組譯原廠 APK"
apktool d -f -o "$WORK/dec" "$APK" >/dev/null

echo "== 3/7 注入 FrameTap 呼叫點"
DEC="$WORK/dec"

# (a) bowl view 啟動 → FrameTap.start(activity)。注入每個 *BirdView*/AVMBV* activity 的 onCreate。
inject_start() {
  local f="$1"
  grep -q "Lcom/vito/FrameTap;->start" "$f" && return 0
  perl -0777 -i -pe '
    s/(\.method public onCreate\(Landroid\/os\/Bundle;\)V\s*\n\s*\.locals \d+\s*\n(?:\s*\.param[^\n]*\n)*\s*invoke-super \{p0, p1\}, L[^\n]+->onCreate\(Landroid\/os\/Bundle;\)V\n)/$1\n    invoke-static {p0}, Lcom\/vito\/FrameTap;->start(Landroid\/app\/Activity;)V\n/;
  ' "$f"
}
for f in $(grep -rl "AVMBVActivity\|BirdViewActivity" "$DEC"/smali*/com/baony/ui/activity/ 2>/dev/null); do
  case "$f" in *'$'*) continue;; esac   # 跳過內部類別
  inject_start "$f" && echo "   start() → ${f#$DEC/}"
done

# (b) createCaptureSession(List,...) 之前 → FrameTap.addSurface(該 List)
#     支援一般 {a, b, c, d} 與 range {v0 .. v3} 兩種呼叫形式；List 是第 2 個暫存器（第 1 個是 this）。
inject_addsurface() {
  perl -0777 -i -pe '
    sub reg2 {
      my ($regs) = @_;
      if ($regs =~ /^\s*([pv]\d+)\s*\.\.\s*([pv]\d+)\s*$/) {        # range
        my ($lo,$hi)=($1,$2); my ($lp,$ln)=($lo=~/([pv])(\d+)/);
        return "$lp".($ln+1);
      }
      my @r = ($regs =~ /([pv]\d+)/g);                              # 列舉
      return $r[1];
    }
    s/(invoke-(?:virtual|interface)(?:\/range)? \{([^}]*)\}, L[^\n]*->createCaptureSession\(Ljava\/util\/List;)/
      "invoke-static {".reg2($2)."}, Lcom\/vito\/FrameTap;->addSurface(Ljava\/util\/List;)V\n\n    ".$1/ge;
  ' "$1"
}
N=0
for f in $(grep -rl "createCaptureSession(Ljava/util/List;" "$DEC"/smali*/ 2>/dev/null); do
  grep -q "Lcom/vito/FrameTap;->addSurface" "$f" && continue
  inject_addsurface "$f"
  grep -q "Lcom/vito/FrameTap;->addSurface" "$f" && { echo "   addSurface → ${f#$DEC/}"; N=$((N+1)); }
done
echo "   共注入 $N 個 createCaptureSession 呼叫點"
[ "$N" -gt 0 ] || { echo "⚠ 一個都沒注入到！可能是 SessionConfiguration 形式，回報我"; exit 1; }

# (c) 原廠相機物件建立時 → FrameTap.register(this)。原廠相機多半開機就開好、不會重建 session，
#     所以 (b) 不一定等得到；登記起來之後可以呼叫它自己的 addOutputSurface 在運作中加輸出。
CAMIF="$(ls "$DEC"/smali*/com/baony/hardware/camera/I360CameraInterface.smali 2>/dev/null | head -1)"
[ -n "$CAMIF" ] || { echo "⚠ 找不到 I360CameraInterface"; exit 1; }
perl -0777 -i -pe '
  s/(\.method public constructor <init>\(\)V\n(?:(?!\.end method).)*?invoke-direct \{p0\}, Ljava\/lang\/Object;-><init>\(\)V\n)/$1\n    invoke-static {p0}, Lcom\/vito\/FrameTap;->register(Ljava\/lang\/Object;)V\n/s;
' "$CAMIF"
grep -q "Lcom/vito/FrameTap;->register" "$CAMIF" || { echo "⚠ register 沒注入到 I360CameraInterface"; exit 1; }
echo "   register() → ${CAMIF#$DEC/}"

echo "== 4/7 編譯 FrameTap → dex"
mkdir -p "$WORK/j/com/vito"
cp "$SELF/inject-src/com/vito/FrameTap.java" "$WORK/j/com/vito/FrameTap.java"
mkdir -p "$WORK/cls" "$WORK/dexout"
javac -source 8 -target 8 -cp "$ANDJAR" -d "$WORK/cls" "$WORK/j/com/vito/FrameTap.java"
"$BT/d8" --min-api 26 --output "$WORK/dexout" $(find "$WORK/cls" -name '*.class')
# d8 產出 classes.dex；原廠已有 classes.dex/classes2.dex，改名成 classes3.dex
mv "$WORK/dexout/classes.dex" "$WORK/classes3.dex"

echo "== 5/7 重打包"
apktool b "$DEC" -o "$WORK/apktool-out.apk" >/dev/null
# 只取 apktool 編出來的 dex；其餘（資源、assets、.so、manifest）一律用原廠 APK 的原檔，
# 而且保留原廠每個檔案的壓縮方式。原廠刻意不壓縮 resources.arsc 與 assets 的 PNG
# （native 渲染用檔案描述子直接讀），被壓縮就會閃退 —— b-v1 就是這樣壞的。
python3 -I - "$APK" "$WORK/apktool-out.apk" "$WORK/classes3.dex" "$WORK/rebuilt.apk" "$ENGINE" "$ASSETS" <<'PY'
import os, sys, zipfile
orig, built, extra, out, engine, assets = sys.argv[1:]
o, b = zipfile.ZipFile(orig), zipfile.ZipFile(built)
with zipfile.ZipFile(out, "w") as z:
    for i in o.infolist():
        n = i.filename
        if n.startswith("META-INF/") and n.upper().endswith((".SF", ".RSA", ".DSA", ".EC", "MANIFEST.MF")):
            continue                                   # 舊簽章丟掉，等一下重簽
        data = b.read(n) if (n.startswith("classes") and n.endswith(".dex")) else o.read(n)
        zi = zipfile.ZipInfo(n, date_time=i.date_time); zi.compress_type = i.compress_type
        zi.external_attr = i.external_attr
        z.writestr(zi, data)
    zi = zipfile.ZipInfo("classes3.dex", date_time=(2020,1,1,0,0,0)); zi.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(zi, open(extra, "rb").read())
    if engine:
        # 合併版：引擎的 dex 接在後面（classes4.dex…），TFLite 的 .so，模型與校正檔
        e = zipfile.ZipFile(engine)
        names_o = set(o.namelist())
        dexes = sorted(n for n in e.namelist() if n.startswith("classes") and n.endswith(".dex"))
        for i, n in enumerate(dexes):
            zi = zipfile.ZipInfo("classes%d.dex" % (4 + i), date_time=(2020,1,1,0,0,0)); zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, e.read(n))
        for n in e.namelist():
            if n.startswith("lib/armeabi-v7a/libtensorflowlite"):
                assert n not in names_o, n
                zi = zipfile.ZipInfo(n, date_time=(2020,1,1,0,0,0)); zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, e.read(n))
        added = 0
        for f in sorted(os.listdir(assets)):
            src = os.path.join(assets, f)
            if not os.path.isfile(src): continue          # bev3d/ 這種網頁不要：系統 process 不能用 WebView
            if not (f.endswith(".tflite") or f.endswith(".json") or f.endswith(".txt")): continue
            n = "assets/" + f
            assert n not in names_o, "跟原廠 assets 撞名：" + n
            zi = zipfile.ZipInfo(n, date_time=(2020,1,1,0,0,0))
            zi.compress_type = zipfile.ZIP_STORED if f.endswith(".tflite") else zipfile.ZIP_DEFLATED   # 模型要 mmap
            z.writestr(zi, open(src, "rb").read()); added += 1
        print("   合併引擎：%d 個 dex、TFLite .so、%d 個 assets" % (len(dexes), added))
names = set(o.namelist())
assert "classes3.dex" not in names, "原廠已經有 classes3.dex，要改用 classes4"
for n in b.namelist():
    if n.startswith("classes") and n.endswith(".dex") and n not in names:
        sys.exit("apktool 多編出 " + n + "，原廠沒有")
print("   保留原廠檔案與壓縮方式：", len(o.infolist()), "個檔")
PY

echo "== 6/7 抓 AOSP platform key + 簽"
KD="$WORK/keys"; mkdir -p "$KD"
B=https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security
curl -s "$B/platform.pk8?format=TEXT"      | base64 -d > "$KD/platform.pk8"
curl -s "$B/platform.x509.pem?format=TEXT" | base64 -d > "$KD/platform.x509.pem"
MOD=$(openssl x509 -in "$KD/platform.x509.pem" -noout -modulus | head -c 40)
[ "$MOD" = "Modulus=9C780592AC0D5D381CDEAA65ECC8A600" ] || { echo "⚠ platform key modulus 不對：$MOD"; exit 1; }
"$BT/zipalign" -p -f 4 "$WORK/rebuilt.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --key "$KD/platform.pk8" --cert "$KD/platform.x509.pem" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" "$WORK/aligned.apk"

echo "== 7/7 驗證"
SHA=$("$BT/apksigner" verify --print-certs "$OUT" | grep -i "SHA-256" | head -1)
echo "   憑證 $SHA"
echo "   期望 …c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
echo
echo "✅ 完成：$OUT"
echo "   安裝：adb install -r $OUT   （baony 在 /data/app，不用 root）"
if [ -n "$ENGINE" ]; then
  echo "   合併版：只要裝這一個。進 360 後左上角有「ADAS」鈕（設定、對位）。"
else
  echo "   然後裝我們的 App，打開「影格共用」開關，倒車/叫起 360 測試。"
fi
rm -rf "$WORK"
