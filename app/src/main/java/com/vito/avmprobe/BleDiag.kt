package com.vito.avmprobe

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings

/**
 * 藍牙掃描前的檢查（實車：BLE 掃描找到 0 個，沒有錯誤）。Android 10 的 BLE 掃描要「定位」服務開著才會回傳結果；
 * 原廠 360 是系統程式，可以在使用者按掃描時把定位打開。另外同時跑傳統藍牙搜尋，分辨是 BLE 的問題還是整個藍牙晶片用不到。
 */
object BleDiag {
    fun locationOn(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (android.os.Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0
    } catch (_: Throwable) { false }

    /** 定位沒開就試著打開（系統程式才有權限）；回傳說明。 */
    fun ensureLocation(ctx: Context): String {
        if (locationOn(ctx)) return "定位：開著"
        return try {
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
            if (locationOn(ctx)) "定位：原本關著，已幫你打開（BLE 掃描需要）" else "定位：關著，打不開（請到車機設定開「定位」）"
        } catch (t: Throwable) { "定位：關著，沒權限打開（請到車機設定開「定位」）：${t.javaClass.simpleName}" }
    }

    fun summary(ctx: Context): String {
        val sb = StringBuilder()
        val ad = BluetoothAdapter.getDefaultAdapter()
        sb.append(ensureLocation(ctx)).append('\n')
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        sb.append("定位權限：").append(if (fine) "有" else "沒有").append('\n')
        sb.append("BLE 硬體：").append(if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) "系統說有" else "系統說沒有").append('\n')
        if (ad != null) {
            sb.append("藍牙：").append(try { "${ad.name}（狀態 ${ad.state}）" } catch (_: Throwable) { "狀態 ${ad.state}" }).append('\n')
            val bonded = try { ad.bondedDevices?.joinToString { it.name ?: it.address } } catch (_: Throwable) { null }
            sb.append("已配對：").append(if (bonded.isNullOrEmpty()) "沒有" else bonded)
        } else sb.append("藍牙：沒有介面")
        return sb.toString()
    }

    fun lowLatency(): ScanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    /** 同時跑傳統藍牙搜尋（約 12 秒），每找到一個呼叫 [found]；回傳停止用的函式。 */
    fun classicDiscovery(ctx: Context, found: (String) -> Unit): () -> Unit {
        val ad = BluetoothAdapter.getDefaultAdapter() ?: return {}
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val d = i?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                found("（傳統）" + (d.name ?: "沒有名稱") + "  " + d.address)
            }
        }
        return try {
            ctx.registerReceiver(rx, IntentFilter(BluetoothDevice.ACTION_FOUND))
            ad.startDiscovery()
            val stop: () -> Unit = { try { ad.cancelDiscovery(); ctx.unregisterReceiver(rx) } catch (_: Throwable) {} }
            stop
        } catch (_: Throwable) { {} }
    }
}

/** GATT 小工具：清掉 Android 快取的服務清單（車機常留著舊的，找不到特徵值）、列出裝置有哪些服務。 */
object GattUtil {
    fun refresh(g: android.bluetooth.BluetoothGatt): Boolean = try {
        g.javaClass.getMethod("refresh").invoke(g) as? Boolean ?: false
    } catch (_: Throwable) { false }

    fun describe(g: android.bluetooth.BluetoothGatt): String {
        val list = g.services ?: return "（沒有服務）"
        if (list.isEmpty()) return "（沒有服務）"
        fun short(u: java.util.UUID) = u.toString().let { if (it.endsWith("-0000-1000-8000-00805f9b34fb")) it.substring(4, 8).uppercase() else it }
        return list.joinToString("；") { s -> short(s.uuid) + "[" + s.characteristics.joinToString(",") { c -> short(c.uuid) } + "]" }
    }
}
