# AVM ADAS Probe — Phase 1

目的：**在不動任何車上硬體的前提下**，驗證「安卓機截『全景360』畫面 → YOLO 偵測周遭車輛 → 疊框」整條鏈路，並量出 **FPS / 推論耗時**，判斷原機算力夠不夠。

同時內建「硬體探測」頁，用來確認能不能跳過截螢幕、改走 **UVC 直讀**（免 root，畫質最好）。

## 架構

```
畫面來源（可抽換）         偵測器（固定）          輸出
┌─────────────────┐     ┌──────────────┐     ┌──────────────┐
│ ① 截螢幕 (本版)   │ ──▶ │ YOLO11n       │ ──▶ │ 懸浮窗疊框     │
│ ② UVC 直讀(待補)  │     │ TFLite+GPU    │     │ FPS/ms 統計   │
│ ③ v4l2 直讀(待補) │     │ NMS→ADAS類別  │     └──────────────┘
└─────────────────┘     └──────────────┘
```

偵測器與來源解耦：`Yolo.detect(bitmap)` 只吃一張圖，之後換 UVC/v4l2 只要換「餵圖的那段」。

## 建置步驟

1. **匯出模型**（主機上跑一次）：
   ```bash
   pip install ultralytics
   python tools/export_model.py
   ```
   會產生 `app/src/main/assets/model.tflite`。

2. 用 **Android Studio**（Koala 以上）開啟本資料夾，讓它自動補 Gradle wrapper 與同步，然後 Build APK。
   （CLI：補好 `gradlew` 後 `./gradlew assembleDebug`，APK 在 `app/build/outputs/apk/debug/`。）

3. side-load 到車機後：
   - 按 **①** 授予懸浮窗權限
   - 按 **②** 開始偵測 → 允許螢幕擷取
   - 切到「全景360」App，畫面上會疊出偵測框，左上角顯示 **引擎 / 推論ms / 端到端ms / FPS / 偵測台數**

## 要回報給我的數字

- SoC 型號（AIDA64）
- 左上角統計條：**引擎(GPU/CPU)、推論ms、FPS**
- 「硬體探測」頁截圖（看有沒有 `VIDEO/UVC ★`）

## 判讀

- 穩定 **>10 FPS** → 近距盲點版可直接做
- 卡在 5~10 → 換 NCNN+Vulkan 或降輸入解析度再戰
- <5 → 走外掛算力（Jetson Orin Nano）

## 已知限制（Phase 1 刻意簡化）

- 截螢幕拿到的是**拼接+縮小後**的畫面，偵測精度不如 raw；確認可行後改走 UVC。
- 魚眼朝下，高速遠距車輛會飄，本版定位在**低速近距驗證**。
- 尚未做多目標追蹤（ByteTrack）與 BEV 投影，Phase 2 再加。
