# 車機車身訊號讀取管道（從「全景360」反編譯得到）

> ## 更正（v0.4.8）：重啟的原因是「監聽廣播」，不是綁定服務
>
> 之前把「測車身訊號會讓車機重啟」歸咎到 AIDL 綁定，於是把 `headUnitBind` 預設關掉。
> 使用者後來確認：**重啟是按下「開始監聽 60 秒」造成的**，綁定服務本身沒問題。
>
> 看 `SignalProbeActivity` 的舊程式碼，兩個原因都很像兇手：
>
> 1. `append()` 每收到一筆廣播就 `logView.text = log` —— 把**整份** StringBuilder
>    重新丟給 TextView 排版。車機的 CAN 廣播一秒可能好幾十筆，這是 O(n²) 的主執行緒工作，
>    卡住就會被看門狗重開。
> 2. `watchProps()` 每 500 ms `fork` 一次 `getprop`，60 秒 = **120 次**行程建立，
>    每次還要解析上千個屬性。
>
> 而且 log 只存在記憶體裡、要使用者按「存檔」才落盤 —— 所以**一重啟就什麼證據都不剩**，
> 這也是為什麼到現在都還沒有一筆「實際收到車身廣播」的紀錄。
>
> v0.4.8 的修正：畫面改成最多 250 ms 批次更新一次且只顯示末端、同一個 action 只詳記前 25 筆、
> 系統屬性改成頭尾各讀一次、**紀錄邊收邊寫進檔案**（每秒落盤，重開機後還在）、
> 而且一次只掛 40 個 action 分批聽（萬一還是會重啟，看檔名就知道是哪一批害的）。
> `headUnitBind` 改回**預設開啟**。


分析對象：`com.baony.avm360` 版本 2.22.145（使用者從車機匯出，jadx 反編譯）。
目的：找出方向燈、倒車、方向盤轉角、雷達距離、車速的讀取方式，實作 `VehicleSignalAdapter`。

> 以下結論全部來自靜態分析，**尚未在實車驗證**。每一節末尾列出要在車上確認的事。

## 0. 結論摘要

| 訊號 | 管道 | 可行性 |
|---|---|---|
| 方向燈（左／右／雙閃） | `ITsSpeechCar` AIDL | 要能 bind 到 `com.ts.MainUI` |
| 倒車 | `ITsSpeechCar.IsCameraMode()`，**或**廣播 `Voice_Reverse_State` | 廣播不需權限 |
| 方向盤轉角 | `ITsSpeechCar.getLineEps()` | 只有這一條路 |
| 前／後／左／右雷達 | `ITsSpeechCar.GetCarFrRadar()` / `GetCarLrRadar()` | 只有這一條路 |
| 車速 | `ITsSpeechCar.GetSpeed()`，**或**廣播 `Voice_Speed_State` | 廣播不需權限 |
| ACC 電源 | `ITsSpeechCar.GetMcuPowerState()`，**或**廣播 `Voice_Acc_State` | 廣播不需權限 |

兩條管道互補：**廣播**不需任何權限但只有倒車／車速／ACC／方向燈聯動；
**AIDL** 才有轉角與雷達，但能不能 bind 要在車上才知道。

## 1. 走不通的路（先排除，省得再試）

### bind `com.baony.ui.service.AVMCanBusService`
manifest 裡它是 `exported=true` 又有同名 action，看起來可以 bind，**但沒有用**：

- `CanBusService.onBind()` 回傳 `mCanBinder`（`CanBusService.java:146`）
- `AVMCanBusBinder extends AbsCanBusBinder`，而 `AbsCanBusBinder extends android.os.Binder`
  （`AbsCanBusBinder.java:14`）
- 整個 `com/baony/` 底下 **沒有任何 `onTransact` 覆寫、沒有 `IInterface` 實作、沒有 `Messenger`**

也就是說那是一個純 local Binder。跨程序 bind 得到的是 `BinderProxy`，
`queryLocalInterface()` 回 null，上面那些 `voiceReverseState(int)` 之類的 public 方法**無法跨程序呼叫**。

`com.baony.sdk.canbus.IBaiosCanBus`（有 `onAngle` / `onSpeed` / `onRadar`）同理是程序內介面，不是 AIDL。

### 序列埠 / sysfs
全景360 以 `sharedUserId="android.uid.system"` 執行，持有 `SERIAL_PORT`、`DUMP`、`REBOOT` 等權限。
它用 `com.baony.sdk.canbus.SerialPort` 直接開序列埠讀 MCU。我們是一般第三方 App，沒有這些權限。
字串表另有 `/sys/class/car_reverse/car_reverse/debug/car_preview_status` 與 `/sys/class/car_reverse/camtype`，
**要在車上 `ls -l` 看權限**才知道讀不讀得到（多半是 `root:system`）。

## 2. 廣播管道（不需權限，最穩）

`com/baony/ui/broadcast/CanBusBroadcastReceiver.java` 顯示車機韌體會送這些廣播，
全景360 只是接收端 —— 代表我們註冊同樣的 receiver 也收得到。

```
action                                          extra key          型別
Voice_Acc_State                                 Signal_State       int
Voice_Reverse_State                             Signal_State       int
Voice_LRReverse_State                           Signal_State       int（位元遮罩，見下）
Voice_Camera_State                              Signal_State       int
Voice_Forbid_State                              Signal_State       int
Voice_Speed_State                               Signal_State       int
xy.image.wideAngle.or.backSight                 Signal_State       int
action.com.flyaudio.device.head.lamp.status     HeadLampStatus     boolean
```

取值：`intent.getIntExtra("Signal_State", -1)`（大燈那個是 `getBooleanExtra("HeadLampStatus", false)`）。
這些 action **沒有宣告 permission 保護**。

注意最後兩個 action 帶著別家廠商的前綴（`xy.`、`flyaudio`），全景360 是同一份 APK 裝在很多牌子的車機上，
所以**不是每台都會送每一個**。我們的 adapter 全部都註冊，收到哪個算哪個。

### `Voice_LRReverse_State` 的位元遮罩（方向燈）

`CanBusHandler.onLRReverse(int)` 的解讀（`CanBusHandler.java:245-291`）：

| 條件 | 意義 |
|---|---|
| `i & 3 == 3` | 雙閃（警示燈） |
| `i & 2 == 2` | 左方向燈 |
| `i & 1 == 1` | 右方向燈 |
| `i == 0` | 都沒亮 |

## 3. AIDL 管道（轉角與雷達唯一來源）

`com/ts/ts360bridge/CarStatusManager.java:63-69`：

```java
Intent intent = new Intent("com.ts.tsspeechlib.car.TsCarService");
intent.setPackage("com.ts.MainUI");
mContext.bindService(intent, mTsCarConnection, Context.BIND_AUTO_CREATE);
...
mTsCarManager = ITsSpeechCar.Stub.asInterface(iBinder);
```

服務在車機主程式 **`com.ts.MainUI`** 裡，介面 descriptor 是
**`com.ts.tsspeechlib.car.ITsSpeechCar`**，是標準 AIDL（有 `Stub` / `asInterface` / `onTransact`）。

全景360 以 50 ms 週期輪詢（`CarStatusManager.mDetectRunnable`），不是回呼式。

### 方法與 transaction code

AIDL 的 transaction code 由宣告順序決定（從 1 開始），所以要重建 `.aidl` 檔**必須照這個順序**：

| # | 方法 | 說明 |
|---|---|---|
| 1–16 | `onTurnOnAir` / `onTurnDownAir` / `onHighAir` / `onLowAir` / `onSetLoop(String)` / `onSetAir(String)` / `onUpWindSpeed` / `onDownWindSpeed` / `onWindMode(String)` / `onOpenSkylight` / `onCloseSkylight` / `onOpenAllCarwindow` / `onCloseAllCarwindow` / `onOpenCarwindow(String)` / `onCloseCarwindow(String)` / `onHeatRearwindow` | 空調／天窗／車窗，用不到但要佔位 |
| 17 | `int GetTotalKilometers()` | 總里程 |
| 18 | `int GetOilLeftover()` | 剩餘油量 |
| **19** | `int GetLeftTurnSignal()` | 左方向燈，1 = 亮 |
| **20** | `int GetRightTurnSignal()` | 右方向燈，1 = 亮 |
| **21** | `int GetEmergency()` | 雙閃，1 = 亮 |
| **22** | `int GetSpeed()` | 車速 |
| **23** | `int getLineEps()` | 方向盤轉角原始值 |
| 24 | `int[] GetCarDoorInfo()` | 車門 |
| **25** | `int[] GetCarFrRadar()` | 前＋後雷達 |
| **26** | `int[] GetCarLrRadar()` | 左右側雷達 |
| **27** | `int IsCameraMode()` | 倒車（鏡頭模式） |
| 28 | `int GetMcuPowerState()` | MCU 電源，**0 = ACC 開** |

### 方向盤轉角換算

`CarStatusManager.detectAngle()`：

```java
int eps = mTsCarManager.getLineEps();
int deg = ((((eps + 1080) * 780) * 2) / 2160) - 780;
callback.onAngle(-deg);
```

即 `deg = (eps + 1080) × 1560 / 2160 − 780`，原始範圍 ±1080 線性映射到 ±780，**再取負號**。
單位推測是方向盤角度（度）。E60 實際鎖點約 ±522°，所以 ±780 應該是協定上限而非實際可達值。

**正負號代表左還右，靜態分析看不出來，一定要在車上實測。**

### 雷達陣列

`CarStatusManager.detectRadar()`：

- `GetCarFrRadar()` 回傳 **至少 8 格**：`[0..3]` = 前方 4 顆，`[4..7]` = 後方 4 顆。
  上限參數**寫死 11**。
- `GetCarLrRadar()` 回傳 **至少 9 格**（長度不足 9 就整個跳過）：
  `[0..3]` = 左側 4 顆，`[4..7]` = 右側 4 顆，**`[8]` = 等級上限**（動態，不是寫死的）。

`[8]` 是動態上限這點很重要：`RadarHelper.a(level, max)` 在 **`max <= 5` 時走的是另一張固定對照表**
（1→225、2→472、3→750、4→1200 mm），不是下面的線性公式。
前後雷達因為寫死 11 一定走線性，但如果這台車的實際上限不是 11，換算就會錯——
所以探測頁會把 `GetCarLrRadar()` 整個陣列（含 `[8]`）原樣印出來。

> 另外，全景360 自己處理右側雷達那段有 copy-paste bug（把 `rRadar[0]`、`rRadar[1]` 跟
> `srRadar[2]`、`srRadar[3]` 混在一起送進 `onRRadar`），所以它的側雷達顯示本來就不可靠。
> 我們沒有用側雷達，不受影響。

值是 **1–11 的等級**，不是距離。`RadarHelper.a(level, 11)` 換算成公釐
（`RadarHelper.java`，`DISPLAY_RADAR_MAX` 系統屬性預設 1500）：

```
level 1      → 100 mm      （下限，算出來 75 被夾到 100）
level 2..10  → (level−1) × 150 + 100 mm
level ≥ 11   → 1600 mm，代表「無障礙」
level 0      → 同樣視為無障礙
```

所以 `公尺 = (level − 1) × 0.15 + 0.10`，有效範圍只到約 **1.45 m**，超過就是沒東西。
這跟我們 `VehicleSignals.PDC_CLEAR = 99` 的約定相容：level ≥ 11 一律送 `PDC_CLEAR`。

**四顆感測器的左右順序（index 0 是最左還最右）靜態分析看不出來，要在車上實測。**

### 倒車

`IsCameraMode()` 直接回傳倒車狀態（`detectBackCar()` 把它當 `onReverse` 送出）。
名字叫 CameraMode，所以**可能是「倒車鏡頭畫面開著」而不是「排檔在 R」**——
按 PDC 鈕叫出畫面時會不會也變 1，要實測。

## 4. 倒車時全景360 怎麼跳出來

`AVMCanBusService.switchFront()` 會在收到車身訊號時把自己帶到前景
（`FloatingController.hideFloating()` + `GlobalManager.getApp().toAvm360Ui()`）。
觸發源就是上面那些訊號，所以我們監聽同一組訊號就能同步接手。

另外 `com.ts.avm.floatview.WindowViewControlService` 是 `exported=true`
（action `com.ts.avm.floatview.RemoteService`），可能可以從外部控制它的浮窗顯示／隱藏——
要確認它的 `onBind` 有沒有真正的跨程序介面（與 `AVMCanBusService` 同樣的陷阱）。

## 5. 上車要確認的事

1. **AIDL 能不能 bind**：`com.ts.MainUI` 有沒有裝、`TsCarService` 有沒有 exported、
   bind 回來是不是 null。這是轉角與雷達的唯一關卡。
2. **廣播有沒有真的在送**：哪幾個 action 收得到、`Signal_State` 的實際值。
3. **轉角正負號**：方向盤往左打，`getLineEps()` 變大還變小。
4. **轉角滿量程**：左右打到底各是多少原始值（用來校正 ±780 的假設）。
5. **雷達索引順序**：只在車尾左角站一個人，看哪個 index 變小。
6. **`IsCameraMode()` 的語意**：排 R 檔 vs 按 PDC 鈕，值分別是多少。
7. **sysfs 權限**：`ls -l /sys/class/car_reverse/`。

## 6. 校正檔（全景360 的鏡頭參數）

校正結果是**純文字 Lua**，不是二進位、不是埋在 .so 裡，可以直接讀懂並轉成我們的 `avm_calib.json`。

### 檔案

| 檔案 | 內容 | 位置 |
|---|---|---|
| `calibinfo.lua` | 本車的實際校正（內參＋外參），`CalibInfos = {...}` | 依平台，見下 |
| `lens.lua` | 鏡頭畸變查表 | `/storage/emulated/0/.Baios360/birdview/res/` |
| `sensor.lua` | 感光元件 | 同上 |
| `cartype.lua` | 車型尺寸 | 同上 |

`calibinfo.lua` 的目錄由 `SystemUtils.getPlatformType()` 依系統屬性決定：

| 平台 | 判定 | 路徑 |
|---|---|---|
| HZX | `persist.vendor.reversing.type == "hzx360"` | `/cache/config/calibinfo.lua` |
| FYT_7862 | `ro.fyt.platform` 為 `6315` / `6316` | `/lsecfs/calibinfo.lua` |
| ZIQI_UNISOC | `car.hw_name` 以 `a007_` 開頭 | `/data/zq_factory/calibinfo.lua` |

字串表裡有 `persist.vendor.reversing.type`，所以 HZX（`/cache/config/`）機率最高，但要上車 `getprop` 確認。
這三個目錄都在系統分割區，**第三方 App 多半讀不到**（要 root，或用全景360 自己的「匯出參數」功能倒到 USB）。
但 `res/` 底下那幾個在 `/sdcard`，**申請儲存權限就讀得到**。

探測頁的「匯出環景校正檔」按鈕會把上述候選路徑全試一遍、讀得到的就匯出，
並印出平台屬性與目錄列表（路徑猜錯時才知道要往哪找）。

### 鏡頭模型（跟我們的不一樣，不能直接抄）

全景360 用的是**入射角→像高的查表**（`lens.lua` 的 `DistortionTable`），不是 k1/k2 多項式：
第 1 欄是入射場角（度），例如 8255 這顆從 0 到 95 度、每 0.1 度一列；
後面幾欄是 `libAdjust.so` 內部用的係數。每顆鏡頭另有一組 `DistorParam`（4 個係數）。

轉換到我們的格式：

- `maxDeg` 可直接取（8255 → 95°）
- `fx`/`fy` ← `Focal`（px）、`cx`/`cy` ← `Center`（px），來源影像 **1280x720**
  （注意我們的 Camera2 是 1920x1080 的四分割，象限是 960x540，比例要換算）
- `k1`/`k2` **必須用 DistortionTable 重新擬合**，不能直接複製
- 外參是 `gluLookAt` 形式（`CameraPos` / `LookatPos` / `CameraUp`，單位 **mm**），
  要自己分解成 `yaw`/`pitch`/`roll`

### 還要確認

- `Cameras[]` 的索引對應哪顆鏡頭（前/後/左/右）—— 要比對 `camera_module.lua` 或實際影像，不要憑外參座標猜
- 車上 `calibinfo.lua` 裡的 `Internal.Lens` / `Internal.Sensor` 實際值，再去 `lens.lua` / `sensor.lua` 取對應表
- 擬合出的 k1/k2 夠不夠準，還是得納入 per-camera 的 `DistorParam`
