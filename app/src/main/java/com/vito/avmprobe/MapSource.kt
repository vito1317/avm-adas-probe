package com.vito.avmprobe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.min

/**
 * 地圖輔助的 Android 端：GPS 定位（1 Hz）＋ Overpass API 下載 OpenStreetMap 道路，結果交給 [MapAssist]。
 *
 * - 道路資料以 0.005° 圖磚存在 filesDir/osm_tiles（30 天內不重抓），同一條路再開就不用網路。
 * - 需要時才抓：車前（最多 300 m）±700 m 範圍內缺圖磚就下載，最快 30 秒一次，失敗退避並換備援伺服器。
 * - 權限：INTERNET、ACCESS_FINE_LOCATION。沒有定位權限時 [start] 回傳 false，不會當掉。
 * - 地圖資料 © OpenStreetMap 貢獻者（ODbL），畫面上要顯示「© OpenStreetMap」。
 *
 * [onState] 在背景執行緒（"map"）呼叫，每個 GPS 定位一次；還沒有地圖資料時給 null。
 */
class MapSource(
    context: Context,
    private val onState: (MapState?) -> Unit = {},
    private val onStatus: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MapSource"
        private val SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
        private const val MIN_INTERVAL_MS = 30_000L      // Overpass 使用規範：低頻率
        private const val TIMEOUT_MS = 25_000
        private const val TOTAL_MS = 60_000L              // 整個請求（含慢慢滴下來的回應）最多 60 s
        private const val TILE_TTL_MS = 30L * 24 * 3600 * 1000
        // 沒有道路的圖磚（海上 / 山區，或伺服器資料庫異常回了空結果）3 天後重抓，不要一錯就錯 30 天
        private const val EMPTY_TTL_MS = 3L * 24 * 3600 * 1000
        private const val MAX_TILES_ON_DISK = 1500
        private const val MAX_TILES_PER_REQUEST = 25
        private const val LOAD_HALF_M = 1000.0           // 載入記憶體：車子周圍 ±1 km
        private const val MAX_BODY = 32 * 1024 * 1024
    }

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "osm_tiles")
    private val userAgent: String = run {
        val ver = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Exception) { null }
        "AVM-ADAS-Probe/${ver ?: "0"} (Android; +https://github.com/vito1317/avm-adas-probe)"
    }

    // ---- "map" 執行緒專用 ----
    private val assist = MapAssist()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val loaded = HashSet<String>()
    private var loading = false
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastFixMs = 0L

    // ---- 背景工作 ----
    private var net: ExecutorService? = null      // Overpass 下載（可能卡 25 s）
    private var io: ExecutorService? = null       // 讀圖磚、解析
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null

    @Volatile private var haveMap = false
    @Volatile private var fetching = false
    @Volatile private var lastRequestMs = -MIN_INTERVAL_MS
    @Volatile private var backoffUntil = 0L
    @Volatile private var failures = 0
    @Volatile private var server = 0

    /** 最新一筆結果（任何執行緒可讀）。 */
    @Volatile var latest: MapState? = null
        private set
    @Volatile private var seq = 0
    @Volatile private var taken = 0

    fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 開始收 GPS。沒有權限 / 沒有 GPS → false（並回報狀態文字）。 */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (thread != null) return true
        if (!hasPermission()) { status("沒有定位權限，地圖輔助關閉"); return false }
        val mgr = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (mgr == null || !mgr.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            status("找不到 GPS，地圖輔助關閉"); return false
        }
        dir.mkdirs()
        val t = HandlerThread("map").apply { start() }
        val h = Handler(t.looper)
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location)
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}   // API < 30 必須實作
            override fun onProviderEnabled(provider: String) { status("GPS 已開啟") }
            override fun onProviderDisabled(provider: String) { status("GPS 已關閉") }
        }
        try {
            mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, t.looper)
        } catch (e: SecurityException) {
            t.quitSafely(); status("沒有定位權限，地圖輔助關閉"); return false
        } catch (e: IllegalArgumentException) {
            t.quitSafely(); status("找不到 GPS，地圖輔助關閉"); return false
        }
        thread = t; handler = h; lm = mgr; listener = l
        net = Executors.newSingleThreadExecutor { r -> Thread(r, "map-net").apply { isDaemon = true } }
        io = Executors.newSingleThreadExecutor { r -> Thread(r, "map-io").apply { isDaemon = true } }
        status("等待 GPS 定位…")
        // 用上次位置先把圖磚準備好（開機後第一個定位常要等很久）
        try {
            mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
                h.post { ensureTiles(loc.latitude, loc.longitude, Double.NaN, 0.0) }
            }
        } catch (_: SecurityException) {}
        return true
    }

    fun stop() {
        val l = listener
        if (l != null) try { lm?.removeUpdates(l) } catch (_: Exception) {}
        listener = null; lm = null
        thread?.quitSafely(); thread = null; handler = null
        net?.shutdownNow(); net = null
        io?.shutdownNow(); io = null
        fetching = false
        loading = false
    }

    /**
     * 有新的定位結果才回傳 payload 的 `map` 欄位，否則 null。
     * 3D 面板會自己沿路推算到下一筆，所以不必每幀都送（每筆十幾 KB）。
     */
    @Synchronized
    fun takeJsonIfNew(): JSONObject? {
        val s = seq
        if (s == taken) return null
        taken = s
        val st = latest ?: return null
        return MapAssist.toJson(st)
    }

    // ---------------- GPS ----------------

    private fun onFix(loc: Location) {
        val now = loc.elapsedRealtimeNanos / 1_000_000L
        var spd = if (loc.hasSpeed()) loc.speed.toDouble() else Double.NaN
        // 有些車機 GPS 一直回報 0 速度：位移明顯時改用位移估
        if (spd == 0.0 && lastLat.isFinite() && now - lastFixMs in 1..1500) {
            val dy = (loc.latitude - lastLat) * 111_195.0
            val dx = (loc.longitude - lastLon) * 111_195.0 * Math.cos(Math.toRadians(loc.latitude))
            if (hypot(dx, dy) > 8) spd = Double.NaN
        }
        lastLat = loc.latitude; lastLon = loc.longitude; lastFixMs = now
        // GPS 車速也送進車身訊號：車機服務綁不上（或關著）時，這是唯一的車速來源。
        // 沒有車速就分不出「停著的車」與「同向行駛的車」，他車的車頭朝向會全部變成跟自車同向。
        // 只在真的有速度時送，GPS 沒鎖定就讓它自然過期（VehicleSignals 有 2 s 逾時）。
        if (spd.isFinite() && spd >= 0) VehicleBus.signals.reportSpeed((spd * 3.6).toFloat())
        val brg = if (loc.hasBearing()) loc.bearing.toDouble() else Double.NaN
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.NaN
        val st = try {
            assist.update(loc.latitude, loc.longitude, spd, brg, acc, now)
        } catch (t: Throwable) {
            Log.w(TAG, "update 失敗：${t.message}"); null
        }
        latest = st
        synchronized(this) { seq++ }
        try { onState(st) } catch (t: Throwable) { Log.w(TAG, "onState 失敗：${t.message}") }
        val hd = when {
            st != null && st.matched -> st.headingDeg.toDouble()
            else -> brg
        }
        ensureTiles(loc.latitude, loc.longitude, hd, if (spd.isFinite()) spd else 0.0)
    }

    // ---------------- 圖磚 ----------------

    private fun tileFile(key: String) = File(dir, "$key.json")

    /** map 執行緒：缺圖磚就排下載；磁碟上有、記憶體還沒載入的就重建道路圖。 */
    private fun ensureTiles(lat: Double, lon: Double, headingDeg: Double, speedMps: Double) {
        val need = OsmTiles.needed(lat, lon, headingDeg, speedMps)
        val now = System.currentTimeMillis()
        val missing = ArrayList<IntArray>()
        val stale = ArrayList<IntArray>()
        var haveNew = false
        for (t in need) {
            val k = OsmTiles.key(t[0], t[1])
            val f = tileFile(k)
            if (!f.exists()) { missing.add(t); continue }
            val age = now - f.lastModified()
            if (age > TILE_TTL_MS || (age > EMPTY_TTL_MS && f.length() < OsmTiles.EMPTY_TILE_BYTES)) stale.add(t)
            if (k !in loaded) haveNew = true
        }
        if (missing.isNotEmpty()) fetch(missing, lat, lon)
        else if (stale.isNotEmpty()) fetch(stale, lat, lon)
        if (haveNew && !loading) rebuild(lat, lon)
    }

    /** 背景讀取車子周圍 ±1 km 的圖磚、解析成道路圖，再交回 map 執行緒換上。 */
    private fun rebuild(lat: Double, lon: Double) {
        val ex = io ?: return
        val h = handler ?: return
        loading = true
        ex.execute {
            var keys: List<String> = emptyList()
            try {
                keys = OsmTiles.around(lat, lon, LOAD_HALF_M).map { OsmTiles.key(it[0], it[1]) }
                    .filter { tileFile(it).exists() }
                val texts = keys.mapNotNull { k -> try { tileFile(k).readText() } catch (_: IOException) { null } }
                val g = MapGraph.parse(texts, lat, lon)
                h.post {
                    assist.load(g)
                    loaded.clear(); loaded.addAll(keys)
                    haveMap = !g.isEmpty()
                    loading = false
                    status("地圖：${g.wayCount} 條道路（${keys.size} 塊圖磚）")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "載入圖磚失敗：${t.message}")
                // 也記成已處理：不然每個定位（1 Hz）都會再讀再解析一次同一批壞檔，直到有新圖磚才再試
                val k = keys
                h.post { loaded.clear(); loaded.addAll(k); loading = false }
            }
        }
    }

    /** 下載一批圖磚（外框一次查詢），切成圖磚寫入快取。遵守最小間隔與退避。 */
    private fun fetch(tiles: List<IntArray>, lat: Double, lon: Double) {
        val ex = net ?: return
        val now = SystemClock.elapsedRealtime()
        if (fetching || now - lastRequestMs < MIN_INTERVAL_MS || now < backoffUntil) return
        // 一次最多 25 塊：離車子近的先
        val cy = OsmTiles.iy(lat); val cx = OsmTiles.ix(lon)
        val pick = tiles.sortedBy { (it[0] - cx) * (it[0] - cx) + (it[1] - cy) * (it[1] - cy) }.take(MAX_TILES_PER_REQUEST)
        val bb = OsmTiles.bbox(pick)
        // 外框內的圖磚全部一起存（外框比缺的多出來的也順便更新）
        val all = ArrayList<IntArray>()
        for (y in OsmTiles.iy(bb[0] + 1e-9)..OsmTiles.iy(bb[2] - 1e-9))
            for (x in OsmTiles.ix(bb[1] + 1e-9)..OsmTiles.ix(bb[3] - 1e-9)) all.add(intArrayOf(x, y))
        fetching = true
        lastRequestMs = now
        status("下載地圖中…")
        ex.execute {
            try {
                val body = post(SERVERS[server % SERVERS.size], OsmTiles.query(bb[0], bb[1], bb[2], bb[3]))
                val root = JSONObject(body)
                val remark = root.optString("remark")
                if (!root.has("elements") || remark.contains("error", true) || remark.contains("timed out", true)) {
                    throw IOException("Overpass：${remark.ifBlank { "沒有 elements" }.take(120)}")
                }
                val parts = OsmTiles.split(body, all)
                for ((k, txt) in parts) writeAtomic(tileFile(k), txt)
                failures = 0
                trimCache()
                status("地圖已更新（${parts.size} 塊圖磚）")
            } catch (t: Throwable) {
                failures++
                server++
                val wait = min(600_000L, 30_000L shl min(failures - 1, 5))
                backoffUntil = SystemClock.elapsedRealtime() + wait
                Log.w(TAG, "Overpass 失敗（第 $failures 次）：${t.message}")
                status("地圖下載失敗，${wait / 1000} 秒後重試${if (haveMap) "（先用快取）" else ""}")
            } finally {
                fetching = false
            }
        }
    }

    private fun post(url: String, query: String): String {
        val t0 = SystemClock.elapsedRealtime()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = TIMEOUT_MS
            c.doOutput = true
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            c.inputStream.use { ins ->
                val buf = java.io.ByteArrayOutputStream()
                val b = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(b)
                    if (n < 0) break
                    buf.write(b, 0, n)
                    if (buf.size() > MAX_BODY) throw IOException("回應太大")
                    // readTimeout 只管單次 read 的空檔，伺服器一直慢慢滴資料就永遠不會逾時 → 另外限制總時間
                    if (SystemClock.elapsedRealtime() - t0 > TOTAL_MS) throw IOException("回應太慢")
                }
                return buf.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** 快取超過上限：刪最舊的。 */
    private fun trimCache() {
        val files = dir.listFiles { _, n -> n.endsWith(".json") } ?: return
        if (files.size <= MAX_TILES_ON_DISK) return
        files.sortBy { it.lastModified() }
        for (i in 0 until files.size - MAX_TILES_ON_DISK) files[i].delete()
    }

    private fun status(s: String) {
        try { onStatus?.invoke(s) } catch (_: Throwable) {}
    }
}
