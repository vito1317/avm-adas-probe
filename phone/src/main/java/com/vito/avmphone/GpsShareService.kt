package com.vito.avmphone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 把手機 GPS 每秒送給車機（POST /loc）：手機的 GPS 比車上的準，車機導航會優先用。
 * 前景服務＋常駐通知（Android 規定：背景用定位要讓使用者看得到），手機鎖螢幕、切到別的 App 也繼續送。
 */
class GpsShareService : Service() {
    companion object {
        @Volatile var running = false; private set
        @Volatile var lastSentAt = 0L; private set
        private const val CH = "gps_share"
        fun start(ctx: Context) {
            val i = Intent(ctx, GpsShareService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, GpsShareService::class.java)) }
    }

    private var lm: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) { send(l) }
        @Deprecated("") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "手機定位分享給車機", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("正在把手機定位分享給車機")
            .setContentText("車機導航會用手機的 GPS（比較準）").setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(1, n)
        running = true
        CarLink.start(this)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) } catch (_: Throwable) {}
        return START_STICKY
    }

    private fun send(l: Location) {
        val j = JSONObject().put("lat", l.latitude).put("lon", l.longitude)
        if (l.hasBearing() && l.hasSpeed() && l.speed > 1f) j.put("hdg", l.bearing.toDouble())   // 停著時方向不準，不送
        if (l.hasAccuracy()) j.put("acc", l.accuracy.toDouble())
        if (l.hasSpeed()) j.put("spd", l.speed.toDouble())
        thread { if (CarLink.post("/loc", j.toString()) != null) lastSentAt = System.currentTimeMillis() }
    }

    override fun onDestroy() {
        running = false
        try { lm?.removeUpdates(listener) } catch (_: Throwable) {}
        super.onDestroy()
    }
}
