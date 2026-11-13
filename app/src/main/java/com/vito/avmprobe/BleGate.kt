package com.vito.avmprobe

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * 藍牙連線排隊：氛圍燈、後擋看板、遠端模組都在 360 裡連 BLE，以前三個各自同時 connectGatt／掃描，
 * 車機的藍牙晶片同時處理不來 → 時好時壞（一個在重連就把別的擠掉，錯誤 133）。
 * 現在同一時間只讓一個在「建立連線／掃描」：連上、失敗或逾時就 [done]，換下一個（中間隔一下讓晶片喘口氣）。
 * 已經連上的連線不受影響（只排「正在連」這一段）。遠端模組是使用者按了才連，[request] 用 urgent 插隊。
 */
object BleGate {
    private const val HOLD_MAX_MS = 18_000L      // 有人拿著太久（回呼沒來）就強制放掉
    private const val GAP_MS = 400L

    private class Req(val tag: String, val start: () -> Unit)
    private val waiting = ArrayDeque<Req>()
    private var holder: String? = null
    private var since = 0L
    private val h: Handler by lazy { Handler(HandlerThread("ble-gate").apply { start() }.looper) }

    @Synchronized fun status(): String = holder?.let { "正在連：$it" + if (waiting.isNotEmpty()) "（排隊 ${waiting.joinToString { r -> r.tag }}）" else "" } ?: "閒置"

    /** 要開始連線／掃描。輪到時呼叫 [start]（在排隊執行緒上，請自己 post 回模組的執行緒）。同一個 tag 重複要求只留最新的。 */
    fun request(tag: String, urgent: Boolean = false, start: () -> Unit) {
        val run: Req?
        synchronized(this) {
            waiting.removeAll { it.tag == tag }
            val now = SystemClock.elapsedRealtime()
            if (holder != null && now - since > HOLD_MAX_MS) holder = null
            if (holder == null || holder == tag) { holder = tag; since = now; run = Req(tag, start) }
            else { if (urgent) waiting.addFirst(Req(tag, start)) else waiting.addLast(Req(tag, start)); run = null }
        }
        if (run != null) h.post { run.start() }
        watchdog()
    }

    /** 這個 tag 的連線階段結束（連上、失敗、逾時、放棄都要呼叫）。 */
    fun done(tag: String) {
        synchronized(this) {
            waiting.removeAll { it.tag == tag && holder != tag }
            if (holder != tag) return
            holder = null
        }
        h.postDelayed({ next() }, GAP_MS)
    }

    private fun next() {
        val r: Req
        synchronized(this) {
            if (holder != null) return
            r = waiting.removeFirstOrNull() ?: return
            holder = r.tag; since = SystemClock.elapsedRealtime()
        }
        r.start()
        watchdog()
    }

    private fun watchdog() {
        h.postDelayed({
            val stale: Boolean
            synchronized(this) { stale = holder != null && SystemClock.elapsedRealtime() - since > HOLD_MAX_MS; if (stale) holder = null }
            if (stale) next()
        }, HOLD_MAX_MS + 500)
    }
}
