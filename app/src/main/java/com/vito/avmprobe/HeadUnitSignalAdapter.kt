package com.vito.avmprobe

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.ts.tsspeechlib.car.ITsSpeechCar
import java.util.concurrent.atomic.AtomicReference

/**
 * 車機車身訊號來源。兩條管道同時用（都是從「全景360」反編譯出來的，見 docs/head-unit-signals.md）：
 *
 * 1. 廣播 `Voice_*`：車機韌體主動送，不需任何權限。有倒車、車速、ACC、方向燈聯動。
 * 2. AIDL `com.ts.tsspeechlib.car.ITsSpeechCar`（服務在 `com.ts.MainUI`）：輪詢。
 *    **方向盤轉角與雷達只有這條路拿得到**；能不能 bind 要看車機有沒有把服務 exported。
 *
 * 兩邊都回報時以 AIDL 為準（更新率高、欄位齊）。任何一邊拿不到就只是少那幾個欄位，不會壞。
 * 所有解碼細節（位元遮罩、轉角換算、雷達等級表）都在 [Decode]，有單元測試。
 */
class HeadUnitSignalAdapter(
    private val ctx: Context,
    private val pollMs: Long = 100L,   // 全景360 用 50 ms；100 ms 對顯示夠用且省電
) : VehicleSignalAdapter {

    override val name = "車機訊號（廣播＋TS 服務）"

    /** 給訊號探測頁顯示用的現況。 */
    data class Status(
        val bound: Boolean,
        val bindError: String?,
        val polls: Long,
        val pollErrors: Long,
        val actionsSeen: Set<String>,
        val lastEps: Int?,
        val lastFrRadar: List<Int>?,
        val lastLrRadar: List<Int>?,
        val lastCameraMode: Int?,
    )

    private val statusRef = AtomicReference(
        Status(false, null, 0, 0, emptySet(), null, null, null, null)
    )

    fun status(): Status = statusRef.get()

    private var sink: VehicleSignals? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var car: ITsSpeechCar? = null
    @Volatile private var running = false

    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    // ---------------------------------------------------------------- 廣播

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val s = sink ?: return
            seen.add(action)
            bump()
            when (action) {
                Decode.ACT_REVERSE -> {
                    val v = intent.getIntExtra(Decode.KEY_STATE, -1)
                    if (v >= 0) s.reportGear(if (v != 0) 'R' else null)
                }
                Decode.ACT_SPEED -> {
                    val v = intent.getIntExtra(Decode.KEY_STATE, -1)
                    if (v >= 0) s.reportSpeed(v.toFloat())
                }
                // 方向燈聯動：位元遮罩，跟 AIDL 的 GetLeft/Right/Emergency 同一套編碼
                Decode.ACT_LRREVERSE -> {
                    val v = intent.getIntExtra(Decode.KEY_STATE, -1)
                    if (v >= 0) s.reportTurn(Decode.turnOf(v))
                }
            }
        }
    }

    // ---------------------------------------------------------------- AIDL

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            car = ITsSpeechCar.Stub.asInterface(b)
            statusRef.updateAndGet { it.copy(bound = car != null, bindError = null) }
        }

        override fun onServiceDisconnected(n: ComponentName?) {
            car = null
            statusRef.updateAndGet { it.copy(bound = false) }
        }
    }

    override fun start(sink: VehicleSignals) {
        if (running) return
        running = true
        this.sink = sink

        val f = IntentFilter()
        for (a in Decode.ACTIONS) f.addAction(a)
        // 車機韌體送的是一般廣播；用 RECEIVER_EXPORTED 才收得到別的程序送的
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, f)
        }

        try {
            val i = Intent(Decode.TS_ACTION).setPackage(Decode.TS_PKG)
            if (!ctx.bindService(i, conn, Context.BIND_AUTO_CREATE)) {
                statusRef.updateAndGet { it.copy(bindError = "bindService 回傳 false（服務不存在或未 exported）") }
            }
        } catch (e: Throwable) {
            statusRef.updateAndGet { it.copy(bindError = e.javaClass.simpleName + ": " + e.message) }
        }

        val t = HandlerThread("headunit-signal").apply { start() }
        thread = t
        handler = Handler(t.looper).also { it.post(poll) }
    }

    override fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        runCatching { ctx.unregisterReceiver(receiver) }
        runCatching { ctx.unbindService(conn) }
        car = null
        sink = null
        statusRef.updateAndGet { it.copy(bound = false) }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            pollOnce()
            handler?.postDelayed(this, pollMs)
        }
    }

    private fun pollOnce() {
        val c = car
        val s = sink
        if (c == null || s == null) return
        try {
            val left = c.GetLeftTurnSignal()
            val right = c.GetRightTurnSignal()
            val hazard = c.GetEmergency()
            s.reportTurn(Decode.turnOf(Decode.turnBits(left, right, hazard)))

            s.reportSpeed(c.GetSpeed().toFloat())

            val eps = c.getLineEps()
            s.reportSteerRaw(Decode.steerDeg(eps))

            // IsCameraMode 可能是「倒車畫面開著」而不是排檔 R，待實車確認
            val cam = c.IsCameraMode()
            s.reportGear(if (cam != 0) 'R' else null)

            val fr = c.GetCarFrRadar()
            val lr = runCatching { c.GetCarLrRadar() }.getOrNull()
            if (fr != null && fr.size >= 8) {
                s.reportPdc(Decode.radar(fr, 0), Decode.radar(fr, 4))
            } else {
                s.reportPdc(null, null)
            }

            statusRef.updateAndGet {
                it.copy(
                    polls = it.polls + 1,
                    actionsSeen = seen.toSet(),
                    lastEps = eps,
                    lastFrRadar = fr?.toList(),
                    lastLrRadar = lr?.toList(),
                    lastCameraMode = cam,
                )
            }
        } catch (e: Throwable) {
            statusRef.updateAndGet { it.copy(pollErrors = it.pollErrors + 1, bindError = e.javaClass.simpleName) }
        }
    }

    private fun bump() = statusRef.updateAndGet { it.copy(actionsSeen = seen.toSet()) }

    /** 純解碼，不碰 android.*，方便測試。 */
    object Decode {
        const val TS_PKG = "com.ts.MainUI"
        const val TS_ACTION = "com.ts.tsspeechlib.car.TsCarService"

        const val KEY_STATE = "Signal_State"
        const val KEY_HEAD_LAMP = "HeadLampStatus"

        const val ACT_ACC = "Voice_Acc_State"
        const val ACT_REVERSE = "Voice_Reverse_State"
        const val ACT_LRREVERSE = "Voice_LRReverse_State"
        const val ACT_CAMERA = "Voice_Camera_State"
        const val ACT_FORBID = "Voice_Forbid_State"
        const val ACT_SPEED = "Voice_Speed_State"
        const val ACT_BACKSIGHT = "xy.image.wideAngle.or.backSight"
        const val ACT_LAMP = "action.com.flyaudio.device.head.lamp.status"

        val ACTIONS = arrayOf(
            ACT_ACC, ACT_REVERSE, ACT_LRREVERSE, ACT_CAMERA,
            ACT_FORBID, ACT_SPEED, ACT_BACKSIGHT, ACT_LAMP
        )

        /** 雷達等級上限：到這個等級就是「沒東西」（DISPLAY_RADAR_MAX 預設 1500 mm 推出來的） */
        const val RADAR_CLEAR_LEVEL = 11

        /** 方向燈位元：bit0 = 右、bit1 = 左、兩個都亮 = 雙閃 */
        fun turnBits(left: Int, right: Int, hazard: Int): Int = when {
            hazard == 1 -> 3
            left == 1 -> 2
            right == 1 -> 1
            else -> 0
        }

        fun turnOf(bits: Int): Char? = when {
            bits and 3 == 3 -> 'H'
            bits and 2 == 2 -> 'L'
            bits and 1 == 1 -> 'R'
            else -> null
        }

        /**
         * 方向盤原始值 → 度。全景360：deg = (eps+1080)*1560/2160 - 780，再取負。
         * 正負到底是左還右要實車確認，所以送進 reportSteerRaw 由 steerCal 再調。
         */
        fun steerDeg(eps: Int): Float = -(((eps + 1080) * 1560f / 2160f) - 780f)

        /**
         * 雷達等級 → 公尺。1 → 0.10、2..10 → (lv-1)*0.15+0.10、>= 11 或 <= 0 → 無障礙。
         * 有效量程只到約 1.45 m。
         */
        fun radarMetres(level: Int): Float =
            if (level <= 0 || level >= RADAR_CLEAR_LEVEL) Float.POSITIVE_INFINITY
            else ((level - 1) * 0.15f + 0.10f)

        /** 從 GetCarFrRadar() 取 4 顆（off = 0 前、4 後） */
        fun radar(raw: IntArray, off: Int): FloatArray? {
            if (raw.size < off + 4) return null
            return FloatArray(4) { radarMetres(raw[off + it]) }
        }
    }
}
