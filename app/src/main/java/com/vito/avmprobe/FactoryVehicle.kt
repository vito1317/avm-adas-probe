package com.vito.avmprobe

import android.os.SystemClock

/**
 * 從原廠 360 自己的車輛狀態讀車速（合併版跟原廠在同一個程式裡）。
 *
 * 原廠打方向燈時會依車速切中速／高速 3D 視角，所以它一定有車速：來源是車機 CAN 訊號或 GPS，
 * 存在 `CameraModelManager.getInstance().getDisplayProcessor().getVehicleState()`
 * （BVDisplayManager.VehicleState：kmPerHour、leftSignalState、rightSignalState、wheelDegree、gearState）。
 * 車機的 ITsSpeechCar.GetSpeed() 在這台車上一直是 0，所以讀得到原廠的就以原廠的為準。
 */
object FactoryVehicle {
    private const val POLL_MS = 200L

    @Volatile var status: String = "還沒讀"
        private set
    @Volatile private var lastValidAt = 0L
    /** 原廠的方向盤角度（診斷、自動存檔用；單位與正負待實車確認）。 */
    @Volatile var wheel: Float? = null
        private set
    @Volatile private var running = false

    /** 最近 2 秒內從原廠讀到有效車速（車機那邊的車速就不要再蓋過去）。 */
    fun speedFresh(): Boolean = SystemClock.elapsedRealtime() - lastValidAt < 2000

    private var getState: (() -> Any?)? = null
    private var fails = 0

    // GPS 備援：原廠拿不到（或一直是 0）時用 GPS 車速
    @Volatile private var gpsKmh = -1f
    @Volatile private var gpsAt = 0L

    fun start(ctx: android.content.Context? = null) {
        if (running) return
        running = true
        ctx?.let { startGps(it) }
        Thread({
            // 原廠剛啟動時不碰它的物件
            SystemClock.sleep(10_000)
            while (running) {
                try { pollOnce() } catch (t: Throwable) { status = "讀取失敗：$t" }
                SystemClock.sleep(POLL_MS)
            }
        }, "factory-vehicle").apply { isDaemon = true }.start()
    }

    private fun resolve(): (() -> Any?)? {
        getState?.let { return it }
        val cmm = Class.forName("com.baony.sdk.media.CameraModelManager")
        // 直接讀它已經建好的單例欄位（mInstance）：不叫 getInstance()，那個「沒有就新建」，
        // 原廠還沒建好時我們會在別的執行緒搶先建出來
        val instF = cmm.getDeclaredField("mInstance").apply { isAccessible = true }
        val disp = cmm.getMethod("getDisplayProcessor")
        val f: () -> Any? = {
            val dp = instF.get(null)?.let { disp.invoke(it) }
            dp?.javaClass?.getMethod("getVehicleState")?.invoke(dp)
        }
        getState = f
        return f
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startGps(ctx: android.content.Context) {
        try {
            val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 500L, 0f, object : android.location.LocationListener {
                override fun onLocationChanged(l: android.location.Location) {
                    if (l.hasSpeed()) { gpsKmh = l.speed * 3.6f; gpsAt = SystemClock.elapsedRealtime() }
                    if (l.hasBearing() && l.hasSpeed()) MotionCurvature.onGps(l.bearing, l.speed * 3.6f)
                }
                @Deprecated("") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }, android.os.Looper.getMainLooper())
        } catch (t: Throwable) { Engine360.log("GPS 車速不能用：$t") }
    }

    private var lastLog = 0L

    private fun pollOnce() {
        val vs = try { resolve()?.invoke() } catch (t: Throwable) {
            if (++fails % 50 == 1) status = "讀不到原廠車輛狀態：${t.javaClass.simpleName}"
            getState = null
            return
        } ?: run { status = "原廠車輛狀態還沒建立"; return }
        val c = vs.javaClass
        fun f(name: String) = try { c.getField(name).get(vs) } catch (_: Throwable) { null }
        val kmh = (f("kmPerHour") as? Float) ?: -1f
        val left = f("leftSignalState") as? Boolean
        val right = f("rightSignalState") as? Boolean
        val wheel = f("wheelDegree") as? Float
        this.wheel = wheel
        val gear = f("gearState") as? Int
        val now = SystemClock.elapsedRealtime()
        val gps = if (now - gpsAt < 3000) gpsKmh else -1f
        // 原廠有正的車速就用原廠的；原廠沒有或是 0 但 GPS 說在動 → 用 GPS；都沒有就照原廠的 0
        val use = when {
            kmh > 0.5f && kmh < 320f -> kmh
            gps >= 0f && (kmh < 0f || gps > 5f) -> gps
            kmh >= 0f && kmh < 320f -> kmh
            else -> -1f
        }
        // 停著時原廠會報 2～3 km/h 的雜訊（實車）：4 km/h 以下當 0，除非 GPS 也說在動
        val useClean = if (use in 0f..4f && !(gps > 4f)) 0f else use
        if (useClean >= 0f) {
            lastValidAt = now
            VehicleBus.signals.reportSpeed(useClean)
        }
        status = "原廠車速 ${if (kmh >= 0f) "%.0f km/h".format(kmh) else "—"}；GPS ${if (gps >= 0f) "%.0f km/h".format(gps) else "—"}；方向燈 ${if (left == true) "左" else ""}${if (right == true) "右" else ""}${if (left != true && right != true) "無" else ""}；方向盤 ${wheel?.let { "%.1f".format(it) } ?: "—"}；檔位碼 ${gear ?: "—"}"
        if (now - lastLog > 30_000) { lastLog = now; Engine360.log(status) }
    }
}
