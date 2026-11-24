package com.vito.avmlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 常駐前景服務：讓中控台的行程不被系統回收，這樣 [PhoneLink] 的手機互聯伺服器（埠 8765）
 * 在使用者切到 360 畫面（中控台退到背景）時也不會死掉。車機是 Android 10，前景服務很單純。
 */
class LinkService : Service() {
    companion object {
        private const val CH = "link"
        fun start(ctx: Context) {
            val i = Intent(ctx, LinkService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "車機互聯", NotificationManager.IMPORTANCE_MIN))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("車機互聯執行中")
            .setContentText("手機可連到車機看車況、導航").setContentIntent(pi).setOngoing(true).build()
        startForeground(7, n)
        return START_STICKY
    }
}
