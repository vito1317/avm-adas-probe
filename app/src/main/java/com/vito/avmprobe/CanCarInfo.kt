package com.vito.avmprobe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock

/**
 * 車機 CAN 的真正車速／轉速：車機主程式（com.ts.MainUI）開放的服務 `com.ts.can.carinfo.CarInfoService`，
 * `requestCarBaseInfo()` 直接回傳 CAN 解出來的 `Can.mCarMsg`（[2] 車速 km/h、[3] 轉速）。
 * 車機「行車電腦」畫面顯示的就是這個值。另一個介面 ITsSpeechCar.GetSpeed() 要 SpeedValid 才回 CAN 車速、
 * 否則回 GPS——這台車 SpeedValid 沒設，所以以前一直拿到 0。
 *
 * 安全：先驗介面名稱是 `com.ts.can.carinfo.ICarInfoService` 才呼叫，而且只用交易碼 6（唯讀的 requestCarBaseInfo，
 * 反編譯確認過）；那個介面也有送 CAN 指令的方法，絕對不呼叫其他交易碼。連續失敗 5 次就停。
 *
 * 讀到過非 0 的車速才相信它（0 可能只是沒接 CAN）；之後它就是最優先的車速來源。
 */
object CanCarInfo {
    private const val PKG = "com.ts.MainUI"
    private const val ACTION = "com.ts.can.carinfo.CarInfoService"
    private const val DESCRIPTOR = "com.ts.can.carinfo.ICarInfoService"
    private const val TX_BASE_INFO = 6
    private const val POLL_MS = 200L

    @Volatile private var binder: IBinder? = null
    @Volatile private var started = false
    @Volatile private var trusted = false
    @Volatile private var lastAt = 0L
    @Volatile var speedKmh: Int = -1; private set
    @Volatile var rpm: Int = -1; private set
    @Volatile var status: String = "未啟動"; private set
    /** 最近一次 requestCarBaseInfo 的整個陣列（設定裡「CAN 全部欄位」看哪些有值）。 */
    @Volatile var last: IntArray? = null; private set

    /** requestCarBaseInfo 各欄位（反編譯 CarInfoService 對照 Can.mCarMsg），沒列的索引是 0。 */
    val NAMES: Map<Int, String> = linkedMapOf(
        0 to "Avalid 資料有效", 1 to "SpeedDw 車速單位", 2 to "Speed 車速", 3 to "Rpm 轉速", 4 to "WaterTemp 水溫",
        5 to "TpmsDw 胎壓單位", 6 to "Tpms 左前胎壓", 7 to "Tpms 右前胎壓", 8 to "Tpms 左後胎壓", 9 to "Tpms 右後胎壓",
        10 to "OilDw 油耗單位", 11 to "AveOil 平均油耗", 12 to "InstantOil 瞬間油耗", 13 to "EndurOil 續航里程",
        14 to "Distance 總里程", 15 to "BatV 電瓶電壓", 16 to "DistanceDw 里程單位", 17 to "TurnLeft 左方向燈",
        18 to "TurnRight 右方向燈", 19 to "DriveSafe 駕駛安全帶", 20 to "HighBeam 遠光燈", 21 to "LowBeam 近光燈",
        22 to "FootBrake 腳煞車", 23 to "HandBrake 手煞車", 24 to "Stalls 檔位", 25 to "Ill 大燈開關", 26 to "WarnLed 雙閃",
        27 to "FrontFogLed 前霧燈", 28 to "RearFogLed 後霧燈", 29 to "Jqmwz 節氣門位置", 30 to "Syyl 剩餘油量",
        31 to "Lqywd 冷卻液溫度", 32 to "Jyyl 機油壓力", 33 to "Dqyl 大氣壓力", 34 to "Syyw 剩餘油位", 35 to "Zcbz 駐車標誌",
        36 to "PassengerSafe 副駕安全帶", 37 to "Bsqyw 變速箱油溫", 38 to "RearOpen 後車廂", 39 to "AveSpeed 平均車速",
        40 to "DriveDis1 行駛距離1", 41 to "DriveDis2 行駛距離2", 59 to "WidthLight 示寬燈", 60 to "WiperSpeed 雨刷速度",
        67 to "OutTemp 車外溫度", 68 to "OilTemp 機油溫度", 69 to "DoorLock 門鎖", 70 to "ChargeSta 充電狀態",
        71 to "WarnSta 警示狀態", 72 to "ACC 電門", 73 to "Fdjqdhsj 引擎啟動後時間", 74 to "Jqwd 進氣溫度",
        75 to "Ymtbxdwz 油門踏板位置", 76 to "Maintenance 保養里程", 77 to "Soc 電量", 78 to "DriveMode 駕駛模式",
        79 to "RlSafetyBelt 左後安全帶", 80 to "RmSafetyBelt 中後安全帶", 81 to "RrSafetyBelt 右後安全帶"
    )
    private var fails = 0
    private var handler: Handler? = null

    /** CAN 車速可用（讀到過非 0、最近 1 秒內有讀到）。 */
    fun fresh(): Boolean = SystemClock.elapsedRealtime() - lastAt < 1000

    /**
     * 車速一律用 CAN（使用者要求）：這台車讀到過一次 CAN 車速就記住（存在設定裡，重開也算數），
     * 之後只要 CAN 服務還連著，其他來源（原廠 VehicleState、車機廣播、GPS）都不報車速；
     * CAN 服務連續失敗停掉了才讓備援接手。
     */
    fun owns(): Boolean = trusted && binder != null
    private var prefs: android.content.SharedPreferences? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            val desc = try { b?.interfaceDescriptor } catch (_: Throwable) { null }
            if (desc != DESCRIPTOR) { binder = null; status = "介面不符：$desc"; return }
            binder = b; status = "已連上"
        }
        override fun onServiceDisconnected(n: ComponentName?) { binder = null; status = "斷線" }
    }

    fun start(ctx: Context) {
        if (started) return
        try { CanVehicle.init(ctx) } catch (_: Throwable) {}
        started = true
        val app = ctx.applicationContext ?: ctx
        prefs = try { app.getSharedPreferences("can_vehicle", Context.MODE_PRIVATE) } catch (_: Throwable) { null }
        if (prefs?.getBoolean("speedTrusted", false) == true) trusted = true
        try {
            val ok = app.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
            status = if (ok) "連線中" else "車機沒有這個服務"
            if (!ok) return
        } catch (t: Throwable) { status = "連不上：$t"; return }
        val th = HandlerThread("can-carinfo").apply { start() }
        val h = Handler(th.looper); handler = h
        h.post(object : Runnable {
            override fun run() {
                if (poll()) h.postDelayed(this, POLL_MS)
            }
        })
    }

    /** 回傳 false = 停止輪詢。 */
    private fun poll(): Boolean {
        val b = binder ?: return true
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            Crumbs.at(Crumbs.OTHER, "canspeed")
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(TX_BASE_INFO, data, reply, 0)
            reply.readException()
            val arr = reply.createIntArray()
            Crumbs.at(Crumbs.OTHER, "canspeed:ok")
            if (arr == null || arr.size < 4) { status = "回傳格式不對"; return fail() }
            fails = 0
            last = arr.copyOf()
            try { CanVehicle.onArray(arr) } catch (_: Throwable) {}
            val spd = arr[2]; val r = arr[3]
            if (spd !in 0..300) return true
            speedKmh = spd; rpm = r; lastAt = SystemClock.elapsedRealtime()
            if (spd > 0 && !trusted) { trusted = true; prefs?.edit()?.putBoolean("speedTrusted", true)?.apply(); Engine360.log("讀到車機 CAN 車速（$spd km/h），之後一律用它當車速") }
            VehicleBus.signals.reportSpeed(spd.toFloat())   // 車速只用 CAN：停著讀到 0 也照送（以前要先讀到過非 0 才採用）
            status = "CAN 車速 $spd km/h、轉速 $r（唯一的車速來源）"
            return true
        } catch (t: Throwable) {
            status = "讀取失敗：${t.javaClass.simpleName}"
            return fail()
        } finally { data.recycle(); reply.recycle() }
    }

    private fun fail(): Boolean {
        fails++
        if (fails < 5) return true
        status = "連續失敗 5 次，已停止：$status"
        binder = null
        return false
    }
}
