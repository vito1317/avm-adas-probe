# B 模式（影格共用）— 安裝與測試

原廠 360 原封不動，注入一個掛鉤，把我們的 ImageReader 加進它的相機 session。
一次開鏡頭，**原廠 bowl view 與我們的偵測疊圖同時顯示**，兩邊用同一份影格。

## 組成

| 件 | 哪來 | 怎麼裝 |
|---|---|---|
| 我們的 App（`com.vito.avmprobe`，含 `OverlayService`） | 公開 repo 的 release | GitHub 直載安裝 |
| 改過的原廠 360（注入 `FrameTap`） | 跑 `build-b.sh` 產生 | `adb install -r` 蓋回 |

改過的 360 **不能放公開 repo**（那是 baony 的閉源程式）。所以用 `build-b.sh` 在自己電腦產生，
或放你自己的**私有** repo release。

## 安全設計（重要）

`FrameTap` 看旗標檔 `/sdcard/avm-adas/frameshare.on` 決定要不要動相機：

- **檔案不存在（預設）→ 完全不碰原廠相機。** 第一次裝進去，原廠 360 一切照常，不會沒畫面。
- 我們 App 的「影格共用」開關打開 → 寫出這個檔 → 下次原廠開相機時才會加我們的 surface。
- 全程 try/catch：綁不上、HAL 不給第二串流、任何例外 → 用原廠原本的清單，不影響原廠。

**萬一打開後原廠相機沒畫面：把開關關掉**（或 `adb shell rm /sdcard/avm-adas/frameshare.on`），
原廠立刻恢復。

## 做出改過的 360

```bash
cd scratchpad/merge360
./build-b.sh /path/to/com.baony.avm360.apk      # 你的原廠 360（zip 裡那份）
# 產出 avm360-b-signed.apk
```

腳本會：反組譯 → 注入所有 `createCaptureSession` 呼叫點（不分相機變體）→ 編 FrameTap →
重打包 → 抓 AOSP platform key → 簽 → 驗證憑證對上原廠 `c8a2e9bc…`。

## 安裝順序

```bash
# 1. 先裝我們的 App（GitHub 直載，或）
adb install -r avm-adas-probe-vX.Y.Z.apk
# 2. 裝改過的 360
adb install -r avm360-b-signed.apk
```

兩個都裝好後：
1. 開我們的 App → 「影格共用（B 模式）」→ 選「開啟」→ 會順便要你給懸浮窗權限，准。
2. 倒車 or 叫起環景 360。
3. 應該看到：原廠 bowl view + 上面疊我們的偵測（車輛框、3D、車道線、警告…）。

## 這是 v1，幾乎一定要看 logcat 調

我沒辦法在開發端實機測試。第一次八成不會一次到位，用 logcat 分段確認：

```bash
adb logcat -s FrameTap:* OverlayService:* AndroidRuntime:E
```

對照：

| logcat 看到 | 意思 | 下一步 |
|---|---|---|
| FrameTap: `start 失敗` / 沒有 FrameTap 任何 log | 掛鉤沒觸發（這台走的不是 AVMBVActivity） | 回報我，換注入的 activity |
| FrameTap: `OverlayService 綁上了` 沒出現 | 我們的 App 沒起來 / 綁定失敗 | 確認我們 App 裝了、給過通知權限 |
| FrameTap: `服務還沒綁上，這次不加` | 時序問題（相機 session 比服務早建） | 退出 360 再進一次（服務已在跑就會加到了） |
| FrameTap: `已把我們的 Surface 加進…` 之後**原廠沒畫面** | HAL 不支援第二串流 | 這台不能共用，關掉開關退回；要嘛改用 A 模式 |
| FrameTap: 加進去了、原廠正常、但我們的疊圖沒東西 | 影格有進來但偵測/渲染問題 | 看 OverlayService log（推論時間、有沒有收到幀） |

## 已知限制

- **時序**：原廠相機 session 可能在我們服務綁好之前就建了 → 第一次進 360 可能沒共用到，
  退出再進一次就好（服務會留著）。
- **HAL 第二串流**：不是每台都支援相機同時輸出兩路 1080p。不支援就只能 A 模式。
- **對齊**：我們的 3D 疊圖是獨立重建，不是貼著原廠 bowl 的像素。兩個都看得到，但不是同一個投影。
