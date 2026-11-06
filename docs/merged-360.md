# 合併版：偵測直接編進原廠 360，標注在原廠畫面上

只裝**一個** APK（改過的原廠 360），不用另外裝我們的 App，也不用懸浮窗權限。
原廠 360 的畫面、按鈕、功能全部照舊，我們的偵測結果直接畫在原廠的**俯視圖**上。

## 為什麼沒有 3D 面板

原廠 360 用 `android:sharedUserId="android.uid.system"`（系統身分）執行。Android 不准系統 process
使用 WebView（`WebViewFactory`：*For security reasons, WebView is not allowed in privileged processes*），
而我們的 3D 面板是 three.js + WebView。所以合併版改成用原生 Canvas 畫在原廠畫面上（`AnnotationView`）。
要 3D 面板就用兩個 App 的 B 模式。

## 組成

| 部分 | 在哪 | 做什麼 |
|---|---|---|
| `FrameTap`（Java，注入） | classes3.dex | 掛鉤：bowl 畫面 onCreate、原廠相機物件建構、建 session。偵測到 `Engine360` 就走合併版，否則走 B 模式 |
| 引擎（`engine360` 模組） | classes4.dex | `Engine360`（入口）、`AdasPipeline`（偵測管線）、`AnnotationView`（標注）、`Engine360Settings`（設定） |
| TFLite | `lib/armeabi-v7a/libtensorflowlite*.so` | 推論 |
| 模型、校正檔 | `assets/*.tflite`（不壓縮，mmap）、`avm_calib.json` | |

## 撞名問題與解法

原廠 APK 自帶一份精簡過的 Kotlin 標準庫（`kotlin.*`）和舊 support library。同一個 class loader 裡
同名類別只會載入第一個（原廠的），我們的程式就會呼叫到缺方法的版本而閃退。

`engine360` 模組直接編譯 `app` 的原始碼，用 R8：
- 只保留 `Engine360` 走得到的程式碼；
- 除了 `Engine360` 與 TFLite（JNI 用類別名稱綁定）之外，**全部改名到 `com.vito.z`**（含 Kotlin、AndroidX）；
- 排除 `androidx.media`（它會以原名保留 `android.support.v4.media.*`，原廠也有同名類別）。

建置時用 `tools/dexclasses.py` 檢查合併後所有 dex：**重複類別必須是 0**；引擎也不能引用任何原名的
`kotlin.*` / `android.support.*`。

## 取得影格

原廠相機開機就開好、一直沿用，不會為了我們重建 session。`FrameTap` 在原廠相機物件
（`I360CameraInterface`）建構時登記它，進 360 約 1.5 秒後呼叫原廠自己的 `addOutputSurface`
（它截圖也是這樣在運作中加輸出），把我們 `FrameShareHub` 的 ImageReader 加進去。
尺寸用原廠的 `getCaptureSize()`（它截圖的 ImageReader 就是這個尺寸，HAL 一定支援）。

## 標注與對位

`AnnotationMapper`：世界座標（m；x 右、z 前）→ 螢幕。參數全用螢幕比例：
自車中心 `cx/cy`、比例 `scale`（1 m 佔螢幕高度）、俯視圖範圍 `clip*`、`flip`。
左上角「ADAS」鈕 → 設定 → 打開「對位模式」，畫出紫色自車外框與 1 m 格線，拖滑桿疊到原廠車模上。

畫的內容：車輛／行人外框與距離（<1.2 m 橘、<0.5 m 紅）、車道線、停車格（空格綠、有車紅）、
有車寬的預測走廊（變道：綠／有來車變紅；轉彎：外側車頭角到內側後輪之間的掃過範圍，有人車變紅；倒車入庫：後軸路徑＋停好時的車身外框）、障礙物；畫面上方的警示（碰撞、前車起步、綠燈、LDW、
左右來車、紅綠燈）。警示音照舊。

## 視角自動辨識（m-v3 起）

原廠每次切換視角都會呼叫 `BVDisplayManager.nativesetBvState(int)`（Java 端兩處）。注入 `FrameTap.onBvState(int)`，
用 `BV_state` 列舉把數字換成名稱（`BV_LEFT`、`BV_REAR_3D`、`BV_LEFT_ROTATE`…）告訴引擎。

每個視角各存一組 `ViewProfile`：俯視圖對位（`AnnotationMapper`）與 3D／鏡頭畫面對位（`PerspectiveMapper`，
繞車鏡頭模型：方位、俯角、距離、視野、看向的點、範圍），兩部分都可以關。原廠切到哪個視角就套用哪組，
倒車、打方向燈、手動切的模式都一樣。還沒對位過的視角：俯視圖沿用最近一次的俯視圖對位；名稱含 LEFT/RIGHT
的給打方向燈那組 3D 初值。左右成對的視角可以一鍵鏡像複製。讀不到視角時退回用方向燈判斷（TURN_L／TURN_R）。

3D 上畫的：轉彎掃過範圍、變道走廊、倒車入庫走廊（地面多邊形），車輛／行人畫成立體線框並標距離。

## 其他調整（m-v3）

- 轉彎走廊：內外側原本左右顛倒（右轉的外側是左前角、內側是右後輪），已修正；兩端沿車身收口，不再是三角形。
- 俯視圖預設不畫偵測到的車道線（原廠影像上看得到真的線，再疊一層只會抖動）；偵測照跑。
- 只畫空車位（有車的格子不畫紅框）。
- 設定裡可「存一張原始影格」（原圖、校正、當下偵測結果）到「下載/avm-adas」。

## 安全

- 所有進入點 try/catch。
- 連續 3 次進 360 後沒撐過 30 秒（多半是我們害原廠閃退）→ 自動關掉 ADAS，從「ADAS」鈕可再打開。
- 標注圖層不在畫面上時不跑偵測。
- 關掉 ADAS → 下次進 360 時把我們的 Surface 從原廠相機移除。

## 建置

```bash
./gradlew :engine360:assembleRelease
cd <merge360>
./build-b.sh <原廠 360 APK> <repo>/engine360/build/outputs/apk/release/engine360-release.apk <repo>/app/src/main/assets
# → avm360-merged-signed.apk（AOSP platform key，跟原廠同簽章，直接覆蓋安裝）
```

改過的 APK 是 baony 閉源程式的衍生版，不放公開 repo（放私有 repo）。
