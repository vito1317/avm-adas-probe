# B 模式：跟原廠 360 共用相機影格

一次開鏡頭，原廠 bowl view 與我們的偵測疊圖**同時顯示**，兩邊用同一份原始相機影格。
不是截螢幕 —— 讀的是相機 HAL 直出的 YUV。

## 為什麼要這樣

原廠 360 的 bowl view 是 GPU native 拼接的，畫得比我們好；但它**獨占那四顆鏡頭**。
我們的偵測也要相機影格。兩個 process 不能同時開同一顆相機。

解法：原廠在 Java 層用 Camera2 開鏡頭（`com.baony.hardware.camera.Camera2Instance*`），
`createCaptureSession(List<Surface>, …)` 的 surface 清單是 Java 物件。注入一行，把**我們的
ImageReader 的 Surface 加進那個清單** → 相機同時輸出到原廠的 native surface 和我們的
ImageReader。一次開鏡頭、兩邊各拿一份原始影格。

## 兩個零件

| 零件 | 在哪 | 做什麼 |
|---|---|---|
| `OverlayService`（我們的 App） | `com.vito.avmprobe` process | 開 ImageReader(1920×1080 YUV)、把 Surface 透過 Binder 交出去、收到影格跑完整偵測管線、用懸浮窗把 3D 疊在原廠 bowl 上 |
| `FrameTap`（注入原廠 360） | `com.baony.avm360` process | bowl view 啟動→叫起並綁定 OverlayService；原廠建相機 session 時→把我們的 Surface 加進清單 |

偵測管線跟 `QuadActivity` 同一套（YOLO、BEV、道路、停車格、障礙物、追蹤、
起步/變道/LDW/轉彎/倒車路徑），只是輸入從「自己開的相機」換成「共用來的影格」，
輸出從 Activity 的 WebView 換成懸浮窗的 WebView。標準版 App 完全沒動，B 壞也只壞 B。

## 輸入走相機、輸出走懸浮窗

- **輸入**：相機 HAL → 我們的 ImageReader（共用），不經過螢幕。
- **輸出**：算完的 3D 偵測結果 → `TYPE_APPLICATION_OVERLAY` 懸浮窗，畫在原廠 bowl 上面。
  需要 `SYSTEM_ALERT_WINDOW`，因為我們的渲染在自己的 process、而原廠才是前景 activity。

## 安全設計（預設關）

`AppPrefs.frameShare` 預設 **false**。打開才把旗標檔 `/sdcard/avm-adas/frameshare.on` 寫出來。
`FrameTap` 看這個檔決定要不要動相機：

- 檔案不在 → `addSurface` 什麼都不做 → **原廠相機 100% 照常**。第一次裝進去不會害原廠沒畫面。
- 全程 try/catch：綁不上、HAL 不給第二串流、任何例外 → 用原廠原本的清單。
- 出事就關開關（或 `rm` 那個檔），原廠立刻恢復。

## 已知限制（都要實機調）

- **HAL 第二串流**：不是每台相機 HAL 都允許同時輸出兩路 1080p。不支援 → session 建立失敗。
  `FrameTap` 擋不住這種「加了才失敗」的情況，所以用預設關 + 可隨時關掉當防線。
- **時序**：原廠相機 session 可能在我們服務綁好之前就建了 → 第一次進 360 沒共用到，
  退出再進一次即可（服務會留著）。
- **對齊**：我們的 3D 是獨立重建，不是貼著原廠 bowl 的像素；兩個都看得到，但不是同一個投影。

## 原廠 360 怎麼改

注入腳本與操作見倉庫外的 `build-b.sh` / `README-B模式.md`（改過的原廠 APK 是 baony 閉源
程式的衍生版，不放這個公開 repo；放私有 repo 或自己用腳本產生）。`FrameTap` 的原始碼
（`inject-src/com/vito/FrameTap.java`）是我們寫的，可公開。
