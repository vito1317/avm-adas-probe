package com.vito.avmprobe

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 合併版的設定（原廠 360 畫面左上的「ADAS」鈕）。全部用 Android 原生元件、程式產生，
 * 不依賴我們的資源檔（合併進原廠 APK 時沒有我們的 resources）。
 *
 * 對位：打開「對位模式」會在原廠俯視圖上畫出自車外框與 1 m 格線，
 * 調滑桿讓紫色外框跟原廠車模重疊即可。
 */
object Engine360Settings {
    private var dlgRef: AlertDialog? = null

    /** 開著的設定視窗（系統截圖前要先藏起來）。 */
    @Volatile var dialogDecor: java.lang.ref.WeakReference<android.view.View>? = null

    fun show(act: Activity, prefs: AppPrefs, av: AnnotationView) {
        val dp = act.resources.displayMetrics.density
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        fun section(t: String) = col.addView(TextView(act).apply {
            text = t; textSize = 13f; setTextColor(Color.parseColor("#4fb3d9")); setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
        })
        fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit) = col.addView(CheckBox(act).apply {
            text = label; isChecked = get(); setOnCheckedChangeListener { _, v -> set(v) }
        })

        col.addView(TextView(act).apply {
            text = FrameShareHub.status()
            textSize = 11f; typeface = Typeface.MONOSPACE
        })
        col.addView(TextView(act).apply {
            textSize = 12f
            val v = VehicleBus.signals.snapshot()
            text = "車輛訊號：" + FactoryVehicle.status + "\n車機 CAN：" + CanCarInfo.status + "\n車況：" + CanVehicle.summary() + "\n目前使用的車速：" + (v.speedKmh?.let { "%.0f km/h".format(it) } ?: "沒有") +
                "\n道路彎度來源：" + MotionCurvature.source + (MotionCurvature.curvature(v.speedKmh)?.let { k ->
                    if (kotlin.math.abs(k) < 1e-3) "（直行）" else "（目前%s彎，半徑約 %.0f m）".format(if (k > 0) "右" else "左", 1 / kotlin.math.abs(k)) } ?: "")
        })
        // 找真正的車速來源：放最上面（使用者在下面找不到）
        val dashInfo = TextView(act).apply { textSize = 11f }
        col.addView(Button(act).apply {
            text = "匯出行車電腦 App（找真正的車速來源，傳給開發者）"; isAllCaps = false
            setOnClickListener {
                isEnabled = false; text = "匯出中…"
                Engine360.exportDashboardApps(act) { msg -> isEnabled = true; text = "匯出行車電腦 App（再按一次重新匯出）"; dashInfo.text = msg }
            }
        })
        col.addView(dashInfo)
        col.addView(Button(act).apply {
            text = "CAN 全部欄位（踩煞車、換檔、開燈，看哪個在變）"; isAllCaps = false
            setOnClickListener { showCanFields(act) }
        })
        col.addView(Button(act).apply {
            text = "掃描藍牙裝置（找氛圍燈；先把手機 App 斷開）"; isAllCaps = false
            setOnClickListener { showBleScan(act) }
        })
        // 閃退足跡：上次啟動 1 分鐘內結束時，當時正在跑哪一段（原生層崩潰也記得到）
        if (Crumbs.report.isNotEmpty() || Crumbs.disabledText().isNotEmpty()) {
            col.addView(TextView(act).apply {
                text = "閃退足跡（目前版本 ${Crumbs.BUILD}；截圖傳給開發者）：\n" + Crumbs.report +
                    (if (Crumbs.disabledText().isNotEmpty()) "\n目前自動停用：" + Crumbs.disabledText() else "")
                textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(255, 170, 80))
            })
            col.addView(Button(act).apply {
                text = "重新開啟被停用的功能"; isAllCaps = false
                setOnClickListener { Crumbs.enableAll(); text = "已重新開啟（下次啟動生效）" }
            })
        }
        if (prefs.lastCrash.isNotEmpty()) {
            col.addView(TextView(act).apply {
                text = "上次閃退（完整內容在「下載/avm-adas/crash_*.txt」）：\n" + prefs.lastCrash
                textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(255, 120, 100))
            })
            col.addView(Button(act).apply {
                text = "清除閃退紀錄"; isAllCaps = false
                setOnClickListener { prefs.lastCrash = ""; prefs.annotCrashes = 0; text = "已清除" }
            })
        }

        section("ADAS")
        check("啟用偵測與標注（關掉後下次進 360 起不再接相機）", { prefs.annotEnabled }) {
            prefs.annotEnabled = it; prefs.annotBoots = 0; prefs.annotCrashes = 0
        }
        check("提示音", { prefs.alertSound }) { prefs.alertSound = it }
        check("語音提示（念出警示，例如「左後來車」「壓線行駛」）", { prefs.voiceAlerts }) { prefs.voiceAlerts = it; Speech.enabled = it }
        Engine360.pipelineParkedShot()?.let { f ->
            col.addView(TextView(act).apply { textSize = 12f; text = "最近的停車位置照片：下載/avm-adas/$f" })
        }
        col.addView(Button(act).apply {
            isAllCaps = false
            fun label() { text = "提示音聲道：${AlertTone.CHANNELS[prefs.alertChannel.coerceIn(0, 3)]}（按一下切換；沒播音樂也聽得到的選這個）" }
            label()
            setOnClickListener { prefs.alertChannel = (prefs.alertChannel + 1) % AlertTone.CHANNELS.size; label(); Engine360.testSounds(act) }
        })
        col.addView(Button(act).apply {
            text = "測試提示音（依序播三種）"; isAllCaps = false
            setOnClickListener { Engine360.testSounds(act) }
        })
        check("車道偵測（偏離車道警示、變道輔助要用）", { prefs.groundMode == AppPrefs.GROUND_VIRTUAL }) {
            prefs.groundMode = if (it) AppPrefs.GROUND_VIRTUAL else AppPrefs.GROUND_OFF
        }
        col.addView(Button(act).apply {
            isAllCaps = false
            val opts = listOf(30, 40, 50, 55, 60, 70)
            fun label() { text = "車道偏移／壓線提醒：${prefs.ldwMinKmh} km/h 以上（按一下切換）" }
            label()
            setOnClickListener { prefs.ldwMinKmh = opts[(opts.indexOf(prefs.ldwMinKmh) + 1).mod(opts.size)]; label() }
        })
        check("在俯視圖上畫出偵測到的車道線（除錯用）", { prefs.annotDrawRoad }) {
            prefs.annotDrawRoad = it; av.drawRoad = it
        }
        check("停車格／三角錐（俯視辨識）", { prefs.camBev }) { prefs.camBev = it }
        check("紅綠燈", { prefs.camTrafficLight }) { prefs.camTrafficLight = it }
        check("障礙物（實驗，誤報多）", { prefs.camObstacle }) { prefs.camObstacle = it }
        check("讀取車機訊號（車速／方向燈）", { prefs.headUnitBind }) { prefs.headUnitBind = it }

        val models = AppPrefs.models(act)
        if (models.isNotEmpty()) {
            val b = Button(act).apply { isAllCaps = false }
            fun refresh() { b.text = "模型：" + (models.firstOrNull { it.second == prefs.model }?.first ?: prefs.model) + "（下次進 360 生效）" }
            b.setOnClickListener {
                val i = models.indexOfFirst { it.second == prefs.model }
                prefs.model = models[(i + 1).mod(models.size)].second
                refresh()
            }
            refresh()
            col.addView(b)
        }

        section("遠端模組（亮波紅月 CY103，藍牙；只有鎖車、開鎖、車窗、尾箱、空調，不能遠端發動）")
        CyRemote.init(act, prefs)
        val cyInfo = TextView(act).apply { textSize = 12f }
        fun cyRefresh() {
            cyInfo.text = "金鑰：" + (if (prefs.cyKey.length == 32) "已輸入（${prefs.cyKey.take(4)}…）" else "還沒輸入") +
                "\n模組：" + (if (prefs.cyMac.isEmpty()) "還沒選" else "${prefs.cyName}（${prefs.cyMac}）") +
                "\n狀態：" + CyRemote.status + (CyRemote.log().takeIf { it.isNotEmpty() }?.let { "\n" + it } ?: "")
        }
        cyRefresh()
        col.addView(cyInfo)
        col.addView(Button(act).apply {
            text = "輸入藍牙金鑰（32 個字，0～9、A～F）"; isAllCaps = false
            setOnClickListener {
                val et = android.widget.EditText(act).apply {
                    hint = "例：a4d3…（電腦小程式 --key 印出來的 btkey）"; setSingleLine(); typeface = Typeface.MONOSPACE
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
                AlertDialog.Builder(act).setTitle("藍牙金鑰").setView(et).setPositiveButton("儲存") { _, _ ->
                    val k = et.text.toString().trim().replace(" ", "")
                    if (k.length == 32 && k.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { prefs.cyKey = k.lowercase(); cyRefresh() }
                    else android.widget.Toast.makeText(act, "格式不對：要剛好 32 個字（0～9、a～f），你輸入了 ${k.length} 個", android.widget.Toast.LENGTH_LONG).show()
                }.setNegativeButton("取消", null).show()
            }
        })
        col.addView(Button(act).apply {
            text = "選擇遠端模組（先關掉手機藍牙）"; isAllCaps = false
            // App 是用「藍牙名稱含裝置 ID」認模組；氛圍燈也廣播 FFE0，不能只看服務
            setOnClickListener { pickBle(act, emptySet(), "6600121332") { mac, name -> prefs.cyMac = mac; prefs.cyName = name; cyRefresh() } }
        })
        for (row in listOf(listOf(CyProtocol.Cmd.LOCK, CyProtocol.Cmd.UNLOCK, CyProtocol.Cmd.WINDOW_UP, CyProtocol.Cmd.WINDOW_DOWN),
                           listOf(CyProtocol.Cmd.TRUNK_OPEN, CyProtocol.Cmd.TRUNK_CLOSE, CyProtocol.Cmd.AC_ON, CyProtocol.Cmd.AC_OFF))) {
            col.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                for (c in row) addView(Button(act).apply {
                    text = c.label; isAllCaps = false
                    setOnClickListener {
                        AlertDialog.Builder(act).setMessage("確定要「${c.label}」？").setPositiveButton("確定") { _, _ ->
                            CyRemote.send(c)
                            for (d in listOf(1500L, 4000L, 8000L)) cyInfo.postDelayed({ cyRefresh() }, d)
                        }.setNegativeButton("取消", null).show()
                    }
                })
            })
        }
        check("停好車（P 檔＋手煞車）問要不要升窗、鎖車", { prefs.cyParkPrompt }) { prefs.cyParkPrompt = it }

        section("溫度")
        check("太熱自動降速（減少偵測次數、關車道線模型；可避免過熱重開機）", { prefs.thermalThrottle }) { prefs.thermalThrottle = it; ThermalGovernor.enabled = it }
        val thInfo = TextView(act).apply { setTextColor(0xFFB0B8C4.toInt()); textSize = 13f; text = ThermalGovernor.status() }
        col.addView(thInfo)
        thInfo.postDelayed(object : Runnable { override fun run() { ThermalGovernor.poll(); thInfo.text = ThermalGovernor.status(); if (thInfo.isAttachedToWindow) thInfo.postDelayed(this, 2000) } }, 500)

        section("第一人稱畫面（前／後鏡頭展開）")
        check("原廠畫面跟著方向盤轉時（動態輔助線），標注也跟著轉", { prefs.fpvFollowWheel }) { prefs.fpvFollowWheel = it; FactoryViews.followWheel = it }
        check("標注轉的方向跟畫面相反時勾這個", { prefs.fpvWheelFlip }) { prefs.fpvWheelFlip = it; FactoryViews.wheelFlip = it }

        section("車道線模型（實驗）")
        val segInfo = TextView(act).apply { setTextColor(0xFFB0B8C4.toInt()); textSize = 13f }
        fun segText() = if (!LaneSeg.available(act)) "這版沒有附模型檔" else Engine360.pipelineLaneSegMs().let { ms -> if (ms >= 0) "每顆鏡頭約 $ms ms" else "還沒跑（要在 360 畫面、地面模式為虛擬道路）" }
        check("用自己訓練的模型找車道線（關掉＝原本的亮度規則）", { prefs.laneModel }) { prefs.laneModel = it }
        segInfo.text = segText(); col.addView(segInfo)
        segInfo.postDelayed(object : Runnable { override fun run() { segInfo.text = segText(); if (segInfo.isAttachedToWindow) segInfo.postDelayed(this, 1000) } }, 1000)

        section("收集訓練資料（魚眼道路線模型）")
        val trainInfo = TextView(act).apply { setTextColor(0xFFB0B8C4.toInt()); textSize = 13f; text = TrainCollector.status }
        check("開車時每秒存一張原始影格＋車況到「下載/avm-adas/train_…」（停著每 10 秒一張；360 畫面要開著才收得到；每張約 0.4 MB，一小時約 1.4 GB）",
            { prefs.trainCollect }) { v -> prefs.trainCollect = v; if (v) TrainCollector.reset(); trainInfo.text = if (v) TrainCollector.status else "關閉（已存 ${TrainCollector.count} 張）" }
        col.addView(trainInfo)
        trainInfo.postDelayed(object : Runnable { override fun run() { if (prefs.trainCollect) trainInfo.text = TrainCollector.status; if (trainInfo.isAttachedToWindow) trainInfo.postDelayed(this, 1000) } }, 1000)

        section("中控台")
        check("中控台開著時，360 在背景也跑偵測（中控台的 3D 道路畫面才有資料；實驗，可能讓 360 閃退）", { prefs.launcherBgDetect }) { prefs.launcherBgDetect = it }

        section("後擋 LED 看板（iPixel；先用 iPixel App 把要顯示的字存進看板，這裡選每個情境播第幾則）")
        check("啟用：急煞／雙閃、倒車、方向燈、起步、紅燈停等、跟車太近、路邊停車時切換看板", { prefs.rdOn }) { v -> prefs.rdOn = v; if (v) RearDisplay.start(act, prefs) }
        val rdInfo = TextView(act).apply { textSize = 12f }
        fun rdRefresh() { rdInfo.text = "看板：" + (if (prefs.rdMac.isEmpty()) "還沒選" else "${prefs.rdName}（${prefs.rdMac}）") + "\n狀態：" + RearDisplay.status }
        rdRefresh(); col.addView(rdInfo)
        col.addView(Button(act).apply {
            text = "選擇看板（先把手機 iPixel App 斷開）"; isAllCaps = false
            setOnClickListener { pickBle(act, setOf("00fa", "fee9", "ae00"), "LED") { mac, name -> prefs.rdMac = mac; prefs.rdName = name; prefs.rdOn = true; RearDisplay.start(act, prefs); RearDisplay.reconnect(); rdRefresh() } }
        })
        for ((label, get, set) in listOf<Triple<String, () -> Int, (Int) -> Unit>>(
            Triple("急煞／雙閃", { prefs.rdSlotBrake }, { v -> prefs.rdSlotBrake = v }),
            Triple("倒車", { prefs.rdSlotReverse }, { v -> prefs.rdSlotReverse = v }),
            Triple("左轉燈", { prefs.rdSlotLeft }, { v -> prefs.rdSlotLeft = v }),
            Triple("右轉燈", { prefs.rdSlotRight }, { v -> prefs.rdSlotRight = v }),
            Triple("起步", { prefs.rdSlotStart }, { v -> prefs.rdSlotStart = v }),
            Triple("紅燈停等", { prefs.rdSlotWait }, { v -> prefs.rdSlotWait = v }),
            Triple("跟車太近", { prefs.rdSlotFollow }, { v -> prefs.rdSlotFollow = v }),
            Triple("路邊停車", { prefs.rdSlotPark }, { v -> prefs.rdSlotPark = v }))) {
            col.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                fun txt() = "$label：" + (if (get() <= 0) "不切換" else "第 ${get()} 則")
                val b = Button(act).apply { text = txt(); isAllCaps = false }
                b.setOnClickListener {
                    val et = android.widget.EditText(act).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "看板裡第幾則（0 = 不切換）"; setText(get().toString()) }
                    AlertDialog.Builder(act).setTitle(label).setView(et).setPositiveButton("儲存") { _, _ -> set(et.text.toString().toIntOrNull()?.coerceIn(0, 99) ?: 0); b.text = txt() }.setNegativeButton("取消", null).show()
                }
                addView(b)
                addView(Button(act).apply { text = "試播"; isAllCaps = false; setOnClickListener { if (get() > 0) RearDisplay.test(get()); rdInfo.postDelayed({ rdRefresh() }, 800) } })
            })
        }

        // 平常：可以填好幾則輪播（例如 1,2,3）；一定要設，不然警示結束會停在警示那則
        col.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            fun cur() = RearDisplayProtocol.parseList(prefs.rdIdleList).ifEmpty { listOfNotNull(prefs.rdSlotIdle.takeIf { it > 0 }) }
            fun txt() = "平常（警示結束回到）：" + (cur().takeIf { it.isNotEmpty() }?.let { "第 ${it.joinToString("、")} 則" + (if (it.size > 1) "輪播" else "") } ?: "沒設（警示結束會停在警示那則）")
            val b = Button(act).apply { text = txt(); isAllCaps = false }
            b.setOnClickListener {
                val et = android.widget.EditText(act).apply { hint = "例：3 或 1,2,3（好幾則就輪播）"; setText(cur().joinToString(",")) }
                AlertDialog.Builder(act).setTitle("平常要播的動畫").setView(et).setPositiveButton("儲存") { _, _ ->
                    prefs.rdIdleList = RearDisplayProtocol.parseList(et.text.toString()).joinToString(","); prefs.rdSlotIdle = 0; b.text = txt()
                }.setNegativeButton("取消", null).show()
            }
            addView(b)
            addView(Button(act).apply { text = "試播"; isAllCaps = false; setOnClickListener { RearDisplay.testList(cur()); rdInfo.postDelayed({ rdRefresh() }, 800) } })
        })

        section("氛圍燈連動（藍牙，AmbientLight 那款）")
        check("啟用：危險紅色閃、偏離橘色、前車起步綠色（警示結束恢復原本顏色）", { prefs.ambientOn }) { v ->
            prefs.ambientOn = v; if (v) AmbientLight.start(act, prefs)
        }
        val ambStatus = TextView(act).apply { textSize = 12f; text = "氛圍燈：" + (if (prefs.ambientMac.isEmpty()) "還沒選" else prefs.ambientName + "（" + prefs.ambientMac + "）") + "\n狀態：" + AmbientLight.status }
        col.addView(ambStatus)
        col.addView(Button(act).apply {
            text = "選擇氛圍燈（先把手機 App 斷開）"; isAllCaps = false
            setOnClickListener { pickAmbient(act, prefs) { ambStatus.text = "氛圍燈：${prefs.ambientName}（${prefs.ambientMac}）\n狀態：" + AmbientLight.status } }
        })
        val zoneNames = arrayOf("不分（全部一起亮）", "第 1 組是前座", "第 2 組是前座")
        col.addView(Button(act).apply {
            text = "前後座：" + zoneNames[prefs.ambientLeftZone] + "（前方危險亮前座、後方亮後座）"; isAllCaps = false
            setOnClickListener { prefs.ambientLeftZone = (prefs.ambientLeftZone + 1) % 3; text = "前後座：" + zoneNames[prefs.ambientLeftZone] + "（前方危險亮前座、後方亮後座）" }
        })
        col.addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            fun b(t: String, f: () -> Unit) = addView(Button(act).apply { text = t; isAllCaps = false; setOnClickListener { f(); ambStatus.postDelayed({ ambStatus.text = "狀態：" + AmbientLight.status }, 500) } })
            b("測試全部（紅）") { AmbientLight.test(AmbientProtocol.ZONE_ALL, AmbientRules.RED) }
            b("測試第 1 組（藍）") { AmbientLight.test(AmbientProtocol.ZONE_1, 0x0040FF) }
            b("測試第 2 組（綠）") { AmbientLight.test(AmbientProtocol.ZONE_2, AmbientRules.GREEN) }
        })
        col.addView(Button(act).apply {
            var paused = false
            text = "暫時斷開（讓手機 App 調燈）"; isAllCaps = false
            setOnClickListener { paused = !paused; AmbientLight.pause(paused); text = if (paused) "重新連上氛圍燈" else "暫時斷開（讓手機 App 調燈）" }
        })

        section("行車記錄器（發動就循環錄影：合成 360 畫面＋辨識結果＋車速／方向燈／煞車）")
        col.addView(TextView(act).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE
            text = Dashcam.recorder?.summary() ?: "未啟動"
        })
        check("錄影", { prefs.dashcamOn }) { on ->
            prefs.dashcamOn = on
            if (on) Dashcam.start(act, prefs) else Dashcam.stop()
        }
        col.addView(Button(act).apply {
            isAllCaps = false
            val opts = listOf(4, 8, 16, 32, 64)
            fun label() { text = "最多用 ${prefs.dashcamQuotaGb} GB（按一下切換；滿了刪最舊的，鎖定的不刪）" }
            label()
            setOnClickListener { prefs.dashcamQuotaGb = opts[(opts.indexOf(prefs.dashcamQuotaGb) + 1).mod(opts.size)]; label() }
        })
        col.addView(Button(act).apply {
            text = "鎖定這段影片（不會被循環刪除）"; isAllCaps = false
            setOnClickListener { Dashcam.lock(); text = "已鎖定（關檔時移到 locked 資料夾）" }
        })

        val brakeInfo = TextView(act).apply { textSize = 11f; typeface = Typeface.MONOSPACE }
        col.addView(Button(act).apply {
            text = "煞車訊號探測（按下後 15 秒內踩放煞車 3 次，打 P 檔）"; isAllCaps = false
            setOnClickListener {
                isEnabled = false; text = "記錄中…請踩放煞車 3 次"
                Engine360.probeBrake(act, { p -> brakeInfo.text = p }) { msg ->
                    isEnabled = true; text = "煞車訊號探測（再按一次重做）"; brakeInfo.text = msg
                }
            }
        })
        col.addView(brakeInfo)


        val spriteInfo = TextView(act).apply { textSize = 11f; text = if (EgoSprite.get(act) != null) "行車記錄器正在用原廠車模圖案" else "還沒擷取（自動對位成功時也會自動擷取）" }
        col.addView(Button(act).apply {
            text = "擷取原廠車模給行車記錄器用（停在有俯視圖的畫面再按）"; isAllCaps = false
            setOnClickListener {
                isEnabled = false
                Engine360.captureEgoSprite { msg -> isEnabled = true; spriteInfo.text = msg }
            }
        })
        col.addView(spriteInfo)

        val exportInfo = TextView(act).apply { textSize = 11f; typeface = Typeface.MONOSPACE }
        col.addView(Button(act).apply {
            text = "匯出原廠 3D 車模檔（自動找，給開發者分析 3D 角度）"; isAllCaps = false
            setOnClickListener {
                isEnabled = false; text = "搜尋中…"
                Engine360.exportCarModels(act) { msg -> isEnabled = true; text = "匯出原廠 3D 車模檔（再按一次重新搜尋）"; exportInfo.text = msg }
            }
        })
        col.addView(exportInfo)

        col.addView(Button(act).apply {
            text = "存一張原始影格（停車格認錯、偵測怪怪的時候按，給開發者分析）"; isAllCaps = false
            setOnClickListener { Engine360.saveRaw(act) }
        })
        col.addView(Button(act).apply {
            text = "10 秒後存一張（按了關掉設定、切到要存的畫面；開設定會跳畫面時用）"; isAllCaps = false
            setOnClickListener { Engine360.delayedCapture(act); dlgRef?.dismiss() }
        })

        // ───────── 對位：針對「原廠現在顯示的視角」─────────
        // 原廠每個視角（俯視＋側邊 3D、倒車、全螢幕…）版面不同，各存一組。原廠切到哪個視角就套用哪組。
        val key = av.currentKey()
        var prof = av.currentProfile()
        var win: android.view.Window? = null
        fun save(np: ViewProfile) {
            prof = np
            prefs.setViewProfile(key, np)
            prefs.setViewCalMode(key, "manual")   // 手動調過：自動對位不會再蓋掉
            // 最新的俯視圖對位也當作新視角的預設——只收正常版面（俯視圖在左邊那格）的；
            // 雙閃三格等其他版面調的只存那個視角，不然下次啟動會被當成不合理而重設（看起來像每次都要重新校準）
            np.top?.takeIf { AppPrefs.normalTopLayout(it) }?.let { prefs.annotParams = it }
            av.invalidateProfiles()
        }

        section("對位：目前視角 $key")
        col.addView(TextView(act).apply {
            textSize = 12f
            text = "這組設定只套用在原廠的「$key」視角。要調別的視角（倒車、打方向燈、手動切換的模式），" +
                "先按完成、在原廠切到那個視角，再打開這裡。打方向燈時原廠會自動切側邊 3D，可以打著燈調。"
        })
        col.addView(TextView(act).apply {
            textSize = 12f
            text = when (prefs.viewCalMode(key)) {
                "auto" -> "這個視角已自動對位。"
                "manual" -> "這個視角已手動調過（自動對位不會蓋掉）。"
                else -> "這個視角還沒對位：原廠切到這個視角、車停著時會自動對位。"
            }
        })
        var refreshLater: () -> Unit = {}
        val autoBtn = Button(act).apply { text = "自動對位這個視角（車停著、周圍有地面紋理時按）"; isAllCaps = false }
        autoBtn.setOnClickListener {
            autoBtn.isEnabled = false; autoBtn.text = "自動對位中…（幾秒到十幾秒）"
            Engine360.autoCalibrate(key) { msg ->
                autoBtn.isEnabled = true; autoBtn.text = msg
                prof = av.currentProfile(); refreshLater()
            }
        }
        col.addView(autoBtn)
        check("對位模式（畫出紫色自車外框／立體線框與格線）", { av.calibrating }) { av.calibrating = it }
        var onRight = true
        col.addView(Button(act).apply {
            text = "設定視窗移到另一邊（露出要對位的那格）"; isAllCaps = false
            setOnClickListener {
                onRight = !onRight
                win?.setGravity((if (onRight) android.view.Gravity.END else android.view.Gravity.START) or android.view.Gravity.CENTER_VERTICAL)
            }
        })

        // 通用滑桿：get 讀目前值、set 產生新的 profile
        val refreshers = ArrayList<() -> Unit>()
        fun slider(label: String, lo: Float, hi: Float, fmt: String, get: () -> Float?, set: (Float) -> ViewProfile?) {
            val tv = TextView(act).apply { textSize = 12f }
            val sb = SeekBar(act).apply { max = 1000 }
            val r = {
                val v = get()
                sb.isEnabled = v != null
                if (v != null) {
                    sb.progress = (((v - lo) / (hi - lo)) * 1000).toInt().coerceIn(0, 1000)
                    tv.text = "$label：${fmt.format(v)}"
                } else tv.text = "$label：—"
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, prog: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = lo + (hi - lo) * prog / 1000f
                    set(v)?.let { save(it) }
                    tv.text = "$label：${fmt.format(v)}"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            refreshers.add(r)
            col.addView(tv); col.addView(sb)
        }
        fun refreshAll() { for (r in refreshers) r() }
        refreshLater = ::refreshAll

        // 俯視圖
        section("俯視圖標注（紫框疊到原廠俯視圖的車模上）")
        col.addView(CheckBox(act).apply {
            text = "這個視角有俯視圖"; isChecked = prof.top != null
            setOnCheckedChangeListener { _, v ->
                save(prof.copy(top = if (v) (prof.top ?: prefs.annotParams) else null)); refreshAll()
            }
        })
        fun top(f: (AnnotationMapper.Params) -> AnnotationMapper.Params): ViewProfile? = prof.top?.let { prof.copy(top = f(it)) }
        slider("自車中心 左右", 0f, 1f, "%.3f", { prof.top?.cx }) { v -> top { it.copy(cx = v) } }
        slider("自車中心 上下", 0f, 1f, "%.3f", { prof.top?.cy }) { v -> top { it.copy(cy = v) } }
        slider("比例（1 公尺佔螢幕高度）", 0.02f, 0.30f, "%.3f", { prof.top?.scale }) { v -> top { it.copy(scale = v) } }
        slider("範圍 左緣", 0f, 1f, "%.3f", { prof.top?.clipL }) { v -> top { it.copy(clipL = v) } }
        slider("範圍 右緣", 0f, 1f, "%.3f", { prof.top?.clipR }) { v -> top { it.copy(clipR = v) } }
        slider("範圍 上緣", 0f, 1f, "%.3f", { prof.top?.clipT }) { v -> top { it.copy(clipT = v) } }
        slider("範圍 下緣", 0f, 1f, "%.3f", { prof.top?.clipB }) { v -> top { it.copy(clipB = v) } }
        col.addView(CheckBox(act).apply {
            text = "原廠俯視圖車頭朝下"; isChecked = prof.top?.flip == true
            setOnCheckedChangeListener { _, v -> top { it.copy(flip = v) }?.let { save(it) } }
        })

        // 3D／鏡頭畫面
        section("3D／鏡頭畫面標注（立體紫框疊到原廠 3D 車模上，粗線那面是車頭）")
        col.addView(TextView(act).apply {
            text = FactoryViews.status + if (FactoryViews.forKey(key) != null && prefs.viewCalMode(key) != "manual")
                "\n這個視角直接用原廠的 3D 參數（不用校準）" else ""
            setTextColor(android.graphics.Color.rgb(120, 200, 120))
        })
        col.addView(CheckBox(act).apply {
            text = "這個視角有 3D 或鏡頭畫面"; isChecked = prof.p3 != null
            setOnCheckedChangeListener { _, v ->
                val def = ViewProfile.defaultFor(key, prefs.annotParams).p3 ?: PerspectiveMapper.Params.DEFAULT_LEFT
                save(prof.copy(p3 = if (v) (prof.p3 ?: def) else null)); refreshAll()
                // 3D 那格多在右邊：調 3D 時把設定視窗移到左邊，別擋住
                win?.setGravity((if (v) android.view.Gravity.START else android.view.Gravity.END) or android.view.Gravity.CENTER_VERTICAL)
            }
        })
        fun p3(f: (PerspectiveMapper.Params) -> PerspectiveMapper.Params): ViewProfile? = prof.p3?.let { prof.copy(p3 = f(it)) }
        slider("鏡頭方位（0 正後、± 往右／左繞、180 正前）", -180f, 180f, "%.0f°", { prof.p3?.azDeg }) { v -> p3 { it.copy(azDeg = v) } }
        slider("鏡頭俯角", 0f, 89f, "%.0f°", { prof.p3?.elDeg }) { v -> p3 { it.copy(elDeg = v) } }
        slider("鏡頭距離", 1f, 30f, "%.1f m", { prof.p3?.dist }) { v -> p3 { it.copy(dist = v) } }
        slider("視野（越大東西越小）", 15f, 150f, "%.0f°", { prof.p3?.fovDeg }) { v -> p3 { it.copy(fovDeg = v) } }
        slider("看向的點 左右（− 左）", -10f, 10f, "%.1f m", { prof.p3?.tx }) { v -> p3 { it.copy(tx = v) } }
        slider("看向的點 前後（+ 前）", -15f, 15f, "%.1f m", { prof.p3?.tz }) { v -> p3 { it.copy(tz = v) } }
        col.addView(Button(act).apply {
            isAllCaps = false
            val opts = listOf(0f to "平面（只畫地面外框）", 0.25f to "低", 0.5f to "中", 1f to "實際車高")
            fun label() { text = "3D 物件高度：" + (opts.minByOrNull { kotlin.math.abs(it.first - prefs.obj3dHeight) }!!.second) + "（按一下切換）" }
            label()
            setOnClickListener {
                val i = opts.indexOfFirst { kotlin.math.abs(it.first - prefs.obj3dHeight) < 0.01f }
                val v = opts[(i + 1).mod(opts.size)].first
                prefs.obj3dHeight = v; av.heightScale = v; label()
            }
        })
        slider("看向的點 高度（整個畫面上下移）", -3f, 5f, "%.1f m", { prof.p3?.ty }) { v -> p3 { it.copy(ty = v) } }
        slider("範圍 左緣", 0f, 1f, "%.3f", { prof.p3?.clipL }) { v -> p3 { it.copy(clipL = v) } }
        slider("範圍 右緣", 0f, 1f, "%.3f", { prof.p3?.clipR }) { v -> p3 { it.copy(clipR = v) } }
        slider("範圍 上緣", 0f, 1f, "%.3f", { prof.p3?.clipT }) { v -> p3 { it.copy(clipT = v) } }
        slider("範圍 下緣", 0f, 1f, "%.3f", { prof.p3?.clipB }) { v -> p3 { it.copy(clipB = v) } }

        ViewProfile.counterpart(key)?.let { other ->
            col.addView(Button(act).apply {
                text = "把這個視角鏡像複製到 $other"; isAllCaps = false
                setOnClickListener {
                    // 版面（範圍）不鏡像，只鏡像視角
                    prefs.setViewProfile(other, ViewProfile(prof.top, prof.p3?.let { it.mirrored().copy(clipL = it.clipL, clipR = it.clipR) }))
                    av.invalidateProfiles()
                    android.widget.Toast.makeText(act, "已複製到 $other", android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        }
        col.addView(Button(act).apply {
            text = "這個視角重設為預設值"; isAllCaps = false
            setOnClickListener {
                prefs.resetViewProfile(key); av.invalidateProfiles()
                prof = av.currentProfile(); refreshAll()
            }
        })
        refreshAll()

        dlgRef = AlertDialog.Builder(act)
            .setTitle("ADAS 設定")
            .setView(ScrollView(act).apply { addView(col) })
            .setPositiveButton("完成") { _, _ -> av.calibrating = false }
            .setOnCancelListener { av.calibrating = false }
            .show()
        dlgRef?.window?.let { w ->
                win = w
                dialogDecor = java.lang.ref.WeakReference(w.decorView)
                // 對位時要看得到後面的原廠畫面：對話框不要變暗背景
                // 而且靠右放，左邊的原廠俯視圖要露出來對位
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setGravity(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
                val dm = act.resources.displayMetrics
                w.setLayout((dm.widthPixels * 0.45f).toInt(), (dm.heightPixels * 0.92f).toInt())
            }
    }

    /** 車機 CAN（CarInfoService.requestCarBaseInfo）每個欄位即時顯示；打開後有變過的標 ★，記最小／最大值。 */
    private fun showCanFields(act: Activity) {
        val tv = TextView(act).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(24, 16, 24, 16) }
        val sv = ScrollView(act).apply { addView(tv) }
        val first = HashMap<Int, Int>(); val lo = HashMap<Int, Int>(); val hi = HashMap<Int, Int>()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var dlg: AlertDialog? = null
        val tick = object : Runnable {
            override fun run() {
                val a = CanCarInfo.last
                val sb = StringBuilder("車機 CAN：" + CanCarInfo.status + "\n★ = 打開後有變過（最小～最大）\n\n")
                if (a == null) sb.append("還沒讀到資料（車機沒有 CarInfoService，或還在連線）")
                else for (i in a.indices) {
                    val v = a[i]
                    first.putIfAbsent(i, v)
                    lo[i] = minOf(lo[i] ?: v, v); hi[i] = maxOf(hi[i] ?: v, v)
                    val changed = lo[i] != hi[i]
                    val name = CanCarInfo.NAMES[i] ?: continue
                    if (v == 0 && !changed) continue
                    sb.append(if (changed) "★ " else "  ").append("[%2d] %-24s %d".format(i, name, v))
                    if (changed) sb.append("（${lo[i]}～${hi[i]}）")
                    sb.append('\n')
                }
                val zero = a?.indices?.count { i -> CanCarInfo.NAMES.containsKey(i) && a[i] == 0 && lo[i] == hi[i] } ?: 0
                if (a != null) sb.append("\n另有 $zero 個欄位一直是 0（這台車的 CAN 盒可能沒給）")
                tv.text = sb
                if (dlg?.isShowing != false) h.postDelayed(this, 300)
            }
        }
        dlg = AlertDialog.Builder(act).setTitle("CAN 全部欄位").setView(sv).setPositiveButton("關閉", null).show()
        h.postDelayed(tick, 100)
    }

    /**
     * 掃描附近的藍牙低功耗裝置 15 秒：確認車機的 Android 藍牙能不能用 BLE，並找出氛圍燈控制器的名稱／服務
     * （常見的 ELK-BLEDOM、Triones、LEDnet 等協定已知，之後可以讓警示跟氛圍燈連動）。只掃描、不連線。
     */
    private fun showBleScan(act: Activity) {
        val tv = TextView(act).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(24, 16, 24, 16); text = "掃描中…" }
        val sv = ScrollView(act).apply { addView(tv) }
        val found = java.util.LinkedHashMap<String, String>()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var cb: android.bluetooth.le.ScanCallback? = null
        var scanner: android.bluetooth.le.BluetoothLeScanner? = null
        val diag = try { BleDiag.summary(act) } catch (t: Throwable) { "檢查失敗：$t" }
        val classic = ArrayList<String>()
        fun render(head: String) {
            tv.text = head + "\n\n" + diag + "\n\n" + (if (found.isEmpty()) "BLE：（還沒找到）" else found.values.joinToString("\n\n")) +
                (if (classic.isEmpty()) "\n\n傳統藍牙：（還沒找到）" else "\n\n" + classic.joinToString("\n"))
        }
        val stopClassic = BleDiag.classicDiscovery(act) { line -> h.post { if (line !in classic) { classic.add(line); render("掃描中…") } } }
        val dlg = AlertDialog.Builder(act).setTitle("藍牙裝置").setView(sv).setPositiveButton("關閉", null).show()
        try {
            val ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            when {
                ad == null -> { tv.text = "車機的 Android 沒有藍牙介面（藍牙可能是另一顆只管通話的模組）→ 沒辦法控制氛圍燈\n\n$diag"; return }
                !ad.isEnabled -> { tv.text = "車機的 Android 藍牙是關的（狀態 ${ad.state}）。有些車機的藍牙由通話模組管，Android 這邊打不開\n\n$diag"; return }
            }
            scanner = ad!!.bluetoothLeScanner
            if (scanner == null) { tv.text = "車機藍牙不支援 BLE 掃描"; return }
            cb = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                    val d = r.device
                    val name = try { r.scanRecord?.deviceName ?: d.name } catch (_: Throwable) { null } ?: "（沒有名稱）"
                    val uuids = r.scanRecord?.serviceUuids?.joinToString { it.uuid.toString().let { u -> if (u.endsWith("-0000-1000-8000-00805f9b34fb")) u.substring(4, 8) else u } } ?: ""
                    val mfr = r.scanRecord?.manufacturerSpecificData?.let { m -> (0 until m.size()).joinToString { k -> "%04x".format(m.keyAt(k)) } } ?: ""
                    found[d.address] = "$name\n  ${d.address}  訊號 ${r.rssi} dBm" +
                        (if (uuids.isNotEmpty()) "\n  服務 $uuids" else "") + (if (mfr.isNotEmpty()) "\n  廠商碼 $mfr" else "")
                    h.post { render("掃描中…（找到 ${found.size} 個，訊號越大越近）") }
                }
                override fun onScanFailed(errorCode: Int) { h.post { tv.text = "BLE 掃描失敗（錯誤碼 $errorCode）" } }
            }
            BleDiag.ensureLocation(act); scanner!!.startScan(null, BleDiag.lowLatency(), cb)
            h.postDelayed({
                try { scanner?.stopScan(cb) } catch (_: Throwable) {}
                stopClassic()
                render("掃描完成：BLE ${found.size} 個、傳統 ${classic.size} 個（請截圖給開發者）")
            }, 15_000)
            dlg.setOnDismissListener { try { scanner?.stopScan(cb) } catch (_: Throwable) {}; stopClassic() }
            render("掃描中…")
        } catch (t: Throwable) { tv.text = "沒辦法掃描：$t\n\n$diag" }
    }

    /** 掃描 10 秒，列出像氛圍燈的藍牙裝置（廣播 FFE0／AF30／FFF0／FEE7 服務，或有名稱的），點一下選定。 */
    private fun pickAmbient(act: Activity, prefs: AppPrefs, done: () -> Unit) {
        val known = setOf("ffe0", "af30", "fff0", "fee7")
        val items = ArrayList<Pair<String, String>>()   // 位址, 顯示
        val names = HashMap<String, String>()
        val adapter = android.widget.ArrayAdapter<String>(act, android.R.layout.simple_list_item_1, ArrayList())
        val list = android.widget.ListView(act).apply { this.adapter = adapter }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var scanner: android.bluetooth.le.BluetoothLeScanner? = null
        var cb: android.bluetooth.le.ScanCallback? = null
        val dlg = AlertDialog.Builder(act).setTitle("選擇氛圍燈（掃描中…）").setView(list).setNegativeButton("取消", null).show()
        list.setOnItemClickListener { _, _, pos, _ ->
            val (mac, _) = items[pos]
            prefs.ambientMac = mac; prefs.ambientName = names[mac] ?: ""; prefs.ambientOn = true
            AmbientLight.start(act, prefs); AmbientLight.reconnect()
            dlg.dismiss(); done()
        }
        dlg.setOnDismissListener { try { scanner?.stopScan(cb) } catch (_: Throwable) {} }
        try {
            val ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (ad == null || !ad.isEnabled) { dlg.setTitle("車機藍牙不能用"); return }
            scanner = ad.bluetoothLeScanner ?: run { dlg.setTitle("車機藍牙不支援 BLE"); return }
            cb = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                    val mac = r.device.address
                    if (items.any { it.first == mac }) return
                    val name = try { r.scanRecord?.deviceName ?: r.device.name } catch (_: Throwable) { null }
                    val svc = r.scanRecord?.serviceUuids?.map { it.uuid.toString().substring(4, 8) } ?: emptyList()
                    val likely = svc.any { it in known }
                    if (!likely && name == null) return
                    names[mac] = name ?: ""
                    val label = (if (likely) "★ " else "") + (name ?: "（沒有名稱）") + "\n" + mac + "  訊號 ${r.rssi}"
                    h.post {
                        if (likely) { items.add(0, mac to label); adapter.insert(label, 0) } else { items.add(mac to label); adapter.add(label) }
                    }
                }
            }
            BleDiag.ensureLocation(act); scanner!!.startScan(null, BleDiag.lowLatency(), cb)
            h.postDelayed({ try { scanner?.stopScan(cb) } catch (_: Throwable) {}; if (dlg.isShowing) dlg.setTitle("選擇氛圍燈（★ = 很可能是；找到 ${items.size} 個）") }, 10_000)
        } catch (t: Throwable) { dlg.setTitle("沒辦法掃描：$t") }
    }

    /** 掃描 10 秒列出藍牙裝置（廣播 [svc] 服務或名稱含 [nameHint] 的排前面標 ★），點一下選定。 */
    private fun pickBle(act: Activity, svc: Set<String>, nameHint: String, done: (String, String) -> Unit) {
        val items = ArrayList<String>()
        val names = HashMap<String, String>()
        val adapter = android.widget.ArrayAdapter<String>(act, android.R.layout.simple_list_item_1, ArrayList())
        val list = android.widget.ListView(act).apply { this.adapter = adapter }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var scanner: android.bluetooth.le.BluetoothLeScanner? = null
        var cb: android.bluetooth.le.ScanCallback? = null
        val dlg = AlertDialog.Builder(act).setTitle("掃描中…").setView(list).setNegativeButton("取消", null).show()
        list.setOnItemClickListener { _, _, pos, _ -> val mac = items[pos]; dlg.dismiss(); done(mac, names[mac] ?: "") }
        dlg.setOnDismissListener { try { scanner?.stopScan(cb) } catch (_: Throwable) {} }
        try {
            val ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (ad == null || !ad.isEnabled) { dlg.setTitle("車機藍牙不能用"); return }
            scanner = ad.bluetoothLeScanner ?: run { dlg.setTitle("車機藍牙不支援 BLE"); return }
            cb = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                    val mac = r.device.address
                    if (mac in items) return
                    val name = try { r.scanRecord?.deviceName ?: r.device.name } catch (_: Throwable) { null }
                    val uu = r.scanRecord?.serviceUuids?.map { it.uuid.toString().substring(4, 8) } ?: emptyList()
                    val likely = uu.any { it in svc } || (name?.contains(nameHint) == true)
                    if (!likely && name == null) return
                    names[mac] = name ?: ""
                    val label = (if (likely) "★ " else "") + (name ?: "（沒有名稱）") + "\n" + mac + "  訊號 ${r.rssi}" + (if (uu.isNotEmpty()) "  服務 " + uu.joinToString() else "")
                    h.post { if (likely) { items.add(0, mac); adapter.insert(label, 0) } else { items.add(mac); adapter.add(label) } }
                }
            }
            BleDiag.ensureLocation(act); scanner!!.startScan(null, BleDiag.lowLatency(), cb)
            h.postDelayed({ try { scanner?.stopScan(cb) } catch (_: Throwable) {}; if (dlg.isShowing) dlg.setTitle("點選（★ = 很可能是；找到 ${items.size} 個）") }, 10_000)
        } catch (t: Throwable) { dlg.setTitle("沒辦法掃描：$t") }
    }
}
