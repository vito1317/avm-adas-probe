# AVM ADAS Probe

把改裝安卓車機（Unisoc UIS7862 / Mali-G52）上的「全景360」環景，做成像特斯拉那樣的**周遭物件 3D 顯示**，全部在原機上跑，不外掛算力。

## v0.2 做了什麼

```
全景360 俯視圖（螢幕截圖）
   │  只裁「俯視區」ROI 餵模型（不再把整個寬螢幕塞進去，較準也較快）
   ▼
YOLO11n（TFLite，GPU 或 CPU 4 核；256/320/416、fp32/int8 可切換）
   │  俯視圖本身就是 IPM 拼好的地面 → 框的位置直接換算成公尺
   ▼
3D 面板（WebView + Three.js r128，懸浮在螢幕右半）
   ├─ 跨幀追蹤：ID 穩定、位置平滑、橫停車輛自動轉向
   ├─ 每個物件顯示「離車身多少公尺」，< 1.2 m 碰撞預警、< 0.5 m 距離過近
   ├─ 實景地面：俯視區畫面即時貼成 3D 地面
   └─ 面板內「校正」：滑桿調俯視區範圍與公尺寬度，馬上生效
```

## 使用

1. 主畫面選 **模型 / 引擎 / 3D 面板位置**（改完要停止再開始）
2. **① 授予懸浮窗權限** → **② 開始偵測** → 允許螢幕擷取
3. 切到全景360、開**俯視畫面**；螢幕左半會出現黃色虛線框（偵測區）
4. 右側 3D 面板按 **校正**：拖滑桿讓虛線框剛好框住俯視圖；調「橫向寬度」讓實景地面上的車身跟 3D 自車一樣寬
5. 用面板底部切 **俯視 / 3D 跟車 / 繞行**

## 硬體測試

- **鏡頭直開測試（Camera2）**：360 鏡頭不是 USB 裝置（AHD 解碼晶片多半走 MIPI-CSI），若系統透過標準相機介面公開，就能免 root 直接拿原始畫面。
- **硬體探測**：列 Camera2 鏡頭、USB 裝置、`/dev/video*`。
- **UVC 直讀**：USB 鏡頭用（本車沒有）。

## 模型

`app/src/main/assets/model_*.tflite`（YOLO11n，COCO）。重新匯出：

```bash
pip install ultralytics
python tools/export_model.py --imgsz 320
```

> 第一次匯出若出現 `duplicate registrations for aten.miopen_batch_norm`，是轉換器在同一個行程裡自動安裝造成的，重跑一次即可。

int8 版信心分數會被量化成粗階（多半顯示 50%），偵測結果與 fp32 相同。

## 簽章

每版都用同一把固定金鑰簽（憑證 SHA-256 `fa5736a4…6e92`），新版可直接覆蓋安裝，設定不會被清掉。

金鑰不進版控：`signing/avm-adas-probe.jks` + 專案根目錄的 `keystore.properties`：

```properties
storeFile=signing/avm-adas-probe.jks
storePassword=…
keyAlias=avm
keyPassword=…
```

沒有這兩個檔案時會退回本機 debug key（可以建置，但裝到車機上要先解除安裝舊版）。

## 3D 面板本機預覽

```bash
python3 -m http.server 8811 --directory app/src/main/assets/bev3d
```

開 `http://localhost:8811/index.html#demo` 會跑示範資料。

## 實測紀錄

| 版本 | 設定 | 推論 | FPS |
|---|---|---|---|
| v0.1 | 640 fp32 GPU，全螢幕 | ~180 ms | ~4 |
| v0.2 | 待測（建議先試 320 GPU、320 int8 CPU） | | |

## 已知限制

- 俯視圖上的車是被 IPM 拉長的俯視形狀，COCO 模型信心偏低；下一步換空拍俯視權重（VisDrone）或魚眼去畸變後逐鏡頭偵測。
- 3D 面板與偵測共用 Mali-G52，面板渲染限 30fps。
- 正式商用版建議改 NanoDet + NCNN（Apache-2.0）；Ultralytics 權重為 AGPL-3.0。
