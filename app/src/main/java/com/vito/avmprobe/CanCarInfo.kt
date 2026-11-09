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
    private var fails = 0
    private var handler: Handler? = null

    /** CAN 車速可用（讀到過非 0、最近 1 秒內有讀到）。 */
    fun fresh(): Boolean = trusted && SystemClock.elapsedRealtime() - lastAt < 1000

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
        started = true
        val app = ctx.applicationContext ?: ctx
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
            val spd = arr[2]; val r = arr[3]
            if (spd !in 0..300) return true
            speedKmh = spd; rpm = r; lastAt = SystemClock.elapsedRealtime()
            if (spd > 0 && !trusted) { trusted = true; Engine360.log("讀到車機 CAN 車速（$spd km/h），改用它當車速") }
            if (trusted) VehicleBus.signals.reportSpeed(spd.toFloat())
            status = "CAN 車速 $spd km/h、轉速 $r" + (if (trusted) "（使用中）" else "（還沒讀到非 0，暫不採用）")
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
