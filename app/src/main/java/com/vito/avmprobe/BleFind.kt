package com.vito.avmprobe

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Handler

/**
 * 連藍牙配件前先掃描找到它：車機開機後藍牙還沒「看過」這個裝置時，直接用 MAC 連常常逾時（Android 的裝置快取是空的），
 * 以前要到設定裡按一下裝置（那時會先掃描）才連得上。用 MAC 比對；配件的位址如果會變，再用名稱比對。
 * 掃不到（手機 App 連著時配件不廣播、或配件沒電）[SCAN_MS] 後就直接用 MAC 試。
 */
object BleFind {
    const val SCAN_MS = 8_000L

    /** 找到（或逾時）後在 [h] 上呼叫 [then]（裝置、是不是掃到的）。 */
    fun find(ad: BluetoothAdapter, mac: String, name: String, h: Handler, then: (BluetoothDevice, Boolean) -> Unit) {
        val sc = ad.bluetoothLeScanner ?: run { then(ad.getRemoteDevice(mac), false); return }
        var done = false
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val d = r.device ?: return
                val nm = try { d.name ?: r.scanRecord?.deviceName } catch (_: Throwable) { null }
                if (d.address.equals(mac, true) || (name.isNotEmpty() && nm == name)) h.post {
                    if (done) return@post
                    done = true; try { sc.stopScan(this) } catch (_: Throwable) {}
                    then(d, true)
                }
            }
            override fun onScanFailed(code: Int) { h.post { if (!done) { done = true; then(ad.getRemoteDevice(mac), false) } } }
        }
        try { sc.startScan(null, BleDiag.lowLatency(), cb) } catch (_: Throwable) { then(ad.getRemoteDevice(mac), false); return }
        h.postDelayed({ if (!done) { done = true; try { sc.stopScan(cb) } catch (_: Throwable) {}; then(ad.getRemoteDevice(mac), false) } }, SCAN_MS)
    }
}
