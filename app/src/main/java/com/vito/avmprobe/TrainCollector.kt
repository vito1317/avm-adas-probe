package com.vito.avmprobe

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 收集訓練資料（魚眼道路線模型用）：開車時每秒存一張四分割原圖到「下載/avm-adas/train_日期時間/」，
 * 每張旁邊一個 JSON：車速、檔位、方向盤、方向燈、在不在停車場，加上目前規則抓到的道路線（當成預標，之後人工修）。
 * 停著不動每 10 秒才存一張（避免一堆一樣的畫面）。壓縮與寫檔在自己的執行緒，不拖慢偵測。
 * 一次最多 [MAX_FRAMES] 張；手機剩不到 [MIN_FREE] 就停。
 */
object TrainCollector {
    private const val MAX_FRAMES = 4000
    private const val MIN_FREE = 2L * 1024 * 1024 * 1024
    private const val MOVING_MS = 1000L
    private const val STILL_MS = 10_000L

    @Volatile var count = 0; private set
    @Volatile var status = "關閉"; private set
    private var session = ""
    private var lastAt = 0L
    @Volatile private var busy = false
    private val h: Handler by lazy { Handler(HandlerThread("train-save").apply { start() }.looper) }

    // 事件觸發：前車煞車／打方向燈、旁邊車打方向燈時多存幾張（燈亮的例子平常太少，車燈模型標不到）。
    // 一次事件最多連存 EVT_BURST 張、每張隔 EVT_GAP_MS（方向燈一亮一暗都會存到），之後冷卻 EVT_COOL_MS。
    private const val EVT_GAP_MS = 700L
    private const val EVT_BURST = 8
    private const val EVT_COOL_MS = 15_000L
    private var evtCount = 0
    private var evtLast = 0L
    private var evtCoolUntil = 0L

    /** 有車燈事件時：這一幀要不要額外存（[evt] = null 表示目前沒有事件）。 */
    fun dueEvent(now: Long, evt: String?): Boolean {
        if (evt == null || busy || count >= MAX_FRAMES || now < evtCoolUntil) return false
        if (now - evtLast < EVT_GAP_MS) return false
        if (now - evtLast > 3000L) evtCount = 0            // 新的一次事件
        evtCount++; evtLast = now
        if (evtCount >= EVT_BURST) { evtCoolUntil = now + EVT_COOL_MS; evtCount = 0 }
        return true
    }

    /** 該不該存這一幀（在影格執行緒上呼叫，很便宜）。 */
    fun due(now: Long, speedKmh: Float?): Boolean {
        if (busy || count >= MAX_FRAMES) return false
        val gap = if ((speedKmh ?: 0f) >= 3f) MOVING_MS else STILL_MS
        return now - lastAt >= gap
    }

    /** 交一幀出去存（[nv21] 要是複本）。[meta] 是這張的車況與預標。 */
    fun submit(ctx: Context, nv21: ByteArray, w: Int, h0: Int, now: Long, meta: JSONObject, calib: String) {
        lastAt = now; busy = true
        h.post {
            try {
                val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }
                if (free < MIN_FREE) { status = "空間不夠（剩 ${free / 1_000_000} MB），已停止"; return@post }
                if (session.isEmpty()) {
                    session = "train_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    MediaSaver.save(ctx, "session.json", "application/json",
                        JSONObject().put("width", w).put("height", h0).put("calib", JSONObject(calib))
                            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").toString(2).toByteArray(), session)
                }
                val bos = ByteArrayOutputStream()
                YuvImage(nv21, ImageFormat.NV21, w, h0, null).compressToJpeg(Rect(0, 0, w, h0), 90, bos)
                val name = "f_%05d".format(count)
                MediaSaver.save(ctx, "$name.jpg", "image/jpeg", bos.toByteArray(), session)
                MediaSaver.save(ctx, "$name.json", "application/json", meta.toString().toByteArray(), session)
                count++
                status = "收集中：$count 張（下載/avm-adas/$session）" + if (count >= MAX_FRAMES) "，已滿" else ""
            } catch (t: Throwable) {
                status = "存檔失敗：${t.message}"
            } finally { busy = false }
        }
    }

    /** 關掉再開＝新的一批（新資料夾）。 */
    fun reset() { session = ""; count = 0; lastAt = 0L; status = "待命（開車時每秒存一張）" }

    fun lines(lines: List<RoadLine>?): JSONArray {
        val a = JSONArray()
        lines?.forEach { l ->
            a.put(JSONObject().put("k", l.kind.toString()).put("d", if (l.dashed) 1 else 0)
                .put("x1", l.x1).put("z1", l.z1).put("x2", l.x2).put("z2", l.z2).put("c", l.conf).put("kv", l.k))
        }
        return a
    }
}
