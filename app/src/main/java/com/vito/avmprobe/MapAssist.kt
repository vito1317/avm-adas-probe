package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地圖輔助的輸出。座標都是車輛座標（x 右、z 前，公尺），原點 = (lat, lon)、z 軸朝 headingDeg。
 * path：配對道路的中心線，車後 ~30 m 到車前 ~200 m，每 5 m 一點，沿行進方向、路口取最直的延伸。
 */
data class MapState(
    val path: List<FloatArray>,
    val lanes: Int?,
    val widthM: Float?,
    val oneway: Boolean,
    val maxspeedKmh: Int?,
    val name: String?,
    val signalsAheadM: List<Float>,
    val roads: List<List<FloatArray>>,
    val matched: Boolean,
    val offsetM: Float,                 // 車相對道路中心線的橫向偏移，+ = 車在中心線右側
    val speedKmh: Float = 0f,
    val accM: Float = 0f,
    val headingDeg: Float = 0f,         // 車輛座標 z 軸的方位角（北 = 0，順時針）
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val highway: String? = null,
    val wayId: Long = 0L,
    val roadClass: List<Int> = emptyList(),   // roads 每條的等級：0 主幹道 1 次要 2 巷道 3 服務道路
    val odoM: Float = 0f,               // 沿路累積里程（車道虛線錨點用）
    val timeMs: Long = 0L
)

/**
 * OpenStreetMap 道路圖（Overpass JSON → 可行車道路的有向圖）。
 * 平面座標：以 (refLat, refLon) 為原點的等距圓柱投影，x 東、y 北（公尺）。
 */
class MapGraph private constructor(
    val refLat: Double,
    val refLon: Double,
    internal val nId: LongArray,
    internal val nx: DoubleArray,
    internal val ny: DoubleArray,
    internal val nSig: ByteArray,          // 0 無；1 雙向；2 只對 forward；3 只對 backward
    internal val ways: Array<OsmWay>,
    internal val sA: IntArray,
    internal val sB: IntArray,
    internal val sWay: IntArray,
    internal val sLen: DoubleArray,
    internal val nSegs: Array<IntArray>,
    private val cells: HashMap<Long, IntArray>
) {
    private val kx = cos(Math.toRadians(refLat)) * R_EARTH * PI / 180.0
    private val ky = R_EARTH * PI / 180.0
    private val index = HashMap<Long, Int>(nId.size * 2).also { for (i in nId.indices) it[nId[i]] = i }

    val nodeCount: Int get() = nId.size
    val wayCount: Int get() = ways.size
    val segmentCount: Int get() = sA.size
    val signalCount: Int get() = nSig.count { it.toInt() != 0 }
    fun isEmpty() = sA.isEmpty()

    fun toX(lon: Double) = (lon - refLon) * kx
    fun toY(lat: Double) = (lat - refLat) * ky
    fun toLon(x: Double) = refLon + x / kx
    fun toLat(y: Double) = refLat + y / ky

    internal fun nodeIndex(id: Long): Int = index[id] ?: -1

    // ---- 有向邊：e = 線段 * 2 + d，d = 0 沿 way 節點順序，1 反向 ----
    internal fun eFrom(e: Int) = if (e and 1 == 0) sA[e shr 1] else sB[e shr 1]
    internal fun eTo(e: Int) = if (e and 1 == 0) sB[e shr 1] else sA[e shr 1]
    internal fun eLen(e: Int) = sLen[e shr 1]
    internal fun eWay(e: Int) = ways[sWay[e shr 1]]
    internal fun allowed(e: Int): Boolean {
        val ow = ways[sWay[e shr 1]].oneway
        return ow == 0 || (ow == 1 && e and 1 == 0) || (ow == -1 && e and 1 == 1)
    }
    internal fun eBearing(e: Int): Double {
        val f = eFrom(e); val t = eTo(e)
        return bearing(nx[t] - nx[f], ny[t] - ny[f])
    }

    /** 半徑 r 內的線段（以格網粗篩，回傳可能重複以外的唯一 id）。 */
    internal fun segmentsNear(x: Double, y: Double, r: Double, out: MutableList<Int>, stamp: IntArray, mark: Int) {
        val c0 = floor((x - r) / CELL).toInt(); val c1 = floor((x + r) / CELL).toInt()
        val r0 = floor((y - r) / CELL).toInt(); val r1 = floor((y + r) / CELL).toInt()
        for (cy in r0..r1) for (cx in c0..c1) {
            val arr = cells[cellKey(cx, cy)] ?: continue
            for (s in arr) if (stamp[s] != mark) { stamp[s] = mark; out.add(s) }
        }
    }

    companion object {
        const val R_EARTH = 6371008.8
        internal const val CELL = 50.0

        /** 可行車的 highway 值（不含 track / path / footway / cycleway / busway…）。 */
        val DRIVABLE: Map<String, Int> = mapOf(
            "motorway" to 0, "motorway_link" to 1, "trunk" to 1, "trunk_link" to 2,
            "primary" to 2, "primary_link" to 3, "secondary" to 3, "secondary_link" to 4,
            "tertiary" to 4, "tertiary_link" to 5, "unclassified" to 5, "road" to 5,
            "residential" to 6, "living_street" to 7, "service" to 8
        )

        internal fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

        fun parse(json: String, refLat: Double, refLon: Double): MapGraph = parse(listOf(json), refLat, refLon)

        /** 解析一或多份 Overpass JSON（多個圖磚合併；node / way 依 id 去重）。 */
        fun parse(jsons: List<String>, refLat: Double, refLon: Double): MapGraph {
            val nodeLat = HashMap<Long, Double>()
            val nodeLon = HashMap<Long, Double>()
            val nodeSig = HashMap<Long, Byte>()
            val rawWays = LinkedHashMap<Long, Pair<LongArray, JSONObject>>()
            for (text in jsons) {
                val root = try { JSONObject(text) } catch (_: Exception) { continue }
                val els = root.optJSONArray("elements") ?: continue
                for (i in 0 until els.length()) {
                    val e = els.optJSONObject(i) ?: continue
                    val id = e.optLong("id", Long.MIN_VALUE)
                    if (id == Long.MIN_VALUE) continue
                    // 單一壞元素（型別錯、缺欄位）只跳過它，不讓整份圖磚失效
                    try {
                        when (e.optString("type")) {
                            "node" -> {
                                val la = e.optDouble("lat", Double.NaN); val lo = e.optDouble("lon", Double.NaN)
                                if (validLatLon(la, lo)) { nodeLat[id] = la; nodeLon[id] = lo }
                                val tg = e.optJSONObject("tags")
                                if (tg != null && tg.optString("highway") == "traffic_signals") {
                                    val d = tg.optString("traffic_signals:direction", tg.optString("direction"))
                                    nodeSig[id] = when (d) { "forward" -> 2; "backward" -> 3; else -> 1 }
                                }
                            }
                            "way" -> {
                                if (rawWays.containsKey(id)) continue
                                val tg = e.optJSONObject("tags") ?: continue
                                if (!drivable(tg)) continue
                                val na = e.optJSONArray("nodes") ?: continue
                                val ids = LongArray(na.length()) { na.getLong(it) }
                                // out geom 格式：座標直接附在 way 上
                                e.optJSONArray("geometry")?.let { ge ->
                                    if (ge.length() == ids.size) for (k in ids.indices) {
                                        val p = ge.optJSONObject(k) ?: continue
                                        val la = p.optDouble("lat", Double.NaN); val lo = p.optDouble("lon", Double.NaN)
                                        if (!nodeLat.containsKey(ids[k]) && validLatLon(la, lo)) {
                                            nodeLat[ids[k]] = la; nodeLon[ids[k]] = lo
                                        }
                                    }
                                }
                                rawWays[id] = ids to tg
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            return build(refLat, refLon, nodeLat, nodeLon, nodeSig, rawWays)
        }

        private fun drivable(tg: JSONObject): Boolean {
            val hw = tg.optString("highway")
            if (!DRIVABLE.containsKey(hw)) return false
            if (tg.optString("area") == "yes") return false
            val no = setOf("no")
            if (tg.optString("motor_vehicle") in no || tg.optString("motorcar") in no || tg.optString("vehicle") in no) return false
            if (tg.optString("access") in no && tg.optString("motor_vehicle").isEmpty() && tg.optString("motorcar").isEmpty()) return false
            return true
        }

        private fun build(
            refLat: Double, refLon: Double,
            nodeLat: HashMap<Long, Double>, nodeLon: HashMap<Long, Double>, nodeSig: HashMap<Long, Byte>,
            rawWays: LinkedHashMap<Long, Pair<LongArray, JSONObject>>
        ): MapGraph {
            val kx = cos(Math.toRadians(refLat)) * R_EARTH * PI / 180.0
            val ky = R_EARTH * PI / 180.0
            val idx = HashMap<Long, Int>()
            val ids = ArrayList<Long>()
            val ways = ArrayList<OsmWay>()
            val wayNodeIdx = ArrayList<IntArray>()
            for ((wid, pr) in rawWays) {
                val (nids, tg) = pr
                val list = ArrayList<Int>(nids.size)
                for (n in nids) {
                    if (!nodeLat.containsKey(n)) continue   // 缺座標的節點跳過
                    val k = idx.getOrPut(n) { ids.add(n); ids.size - 1 }
                    if (list.isEmpty() || list[list.size - 1] != k) list.add(k)
                }
                if (list.size < 2) continue
                wayNodeIdx.add(list.toIntArray())
                ways.add(OsmWay.from(wid, tg))
            }
            val n = ids.size
            val nId = LongArray(n) { ids[it] }
            val nx = DoubleArray(n) { (nodeLon[nId[it]]!! - refLon) * kx }
            val ny = DoubleArray(n) { (nodeLat[nId[it]]!! - refLat) * ky }
            val nSig = ByteArray(n) { nodeSig[nId[it]] ?: 0 }
            var segCount = 0
            for (w in wayNodeIdx) segCount += w.size - 1
            val sA = IntArray(segCount); val sB = IntArray(segCount); val sWay = IntArray(segCount)
            val sLen = DoubleArray(segCount)
            val deg = IntArray(n)
            var s = 0
            val wayArr = Array(ways.size) { i -> ways[i].also { it.nodes = wayNodeIdx[i] } }
            for (wi in wayNodeIdx.indices) {
                val w = wayNodeIdx[wi]
                wayArr[wi].firstSeg = s
                for (k in 0 until w.size - 1) {
                    sA[s] = w[k]; sB[s] = w[k + 1]; sWay[s] = wi
                    sLen[s] = max(1e-3, hypot(nx[w[k + 1]] - nx[w[k]], ny[w[k + 1]] - ny[w[k]]))
                    deg[w[k]]++; deg[w[k + 1]]++
                    s++
                }
            }
            val nSegs = Array(n) { IntArray(deg[it]) }
            val fill = IntArray(n)
            for (q in 0 until segCount) {
                nSegs[sA[q]][fill[sA[q]]++] = q
                nSegs[sB[q]][fill[sB[q]]++] = q
            }
            // 空間格網
            val tmp = HashMap<Long, ArrayList<Int>>()
            for (q in 0 until segCount) {
                val x0 = min(nx[sA[q]], nx[sB[q]]); val x1 = max(nx[sA[q]], nx[sB[q]])
                val y0 = min(ny[sA[q]], ny[sB[q]]); val y1 = max(ny[sA[q]], ny[sB[q]])
                for (cy in floor(y0 / CELL).toInt()..floor(y1 / CELL).toInt())
                    for (cx in floor(x0 / CELL).toInt()..floor(x1 / CELL).toInt())
                        tmp.getOrPut(cellKey(cx, cy)) { ArrayList(4) }.add(q)
            }
            val cells = HashMap<Long, IntArray>(tmp.size * 2)
            for ((k, v) in tmp) cells[k] = v.toIntArray()
            return MapGraph(refLat, refLon, nId, nx, ny, nSig, wayArr, sA, sB, sWay, sLen, nSegs, cells)
        }
    }
}

/** 一條 OSM way 的屬性（只留地圖輔助用得到的）。 */
class OsmWay internal constructor(
    val id: Long,
    val highway: String,
    val oneway: Int,               // 0 雙向；1 順 way 方向單行；-1 逆向單行
    val lanes: Int?,
    val lanesFwd: Int?,
    val lanesBwd: Int?,
    val maxspeed: Int?,
    val maxFwd: Int?,
    val maxBwd: Int?,
    val name: String?,
    val width: Float?,
    val rank: Int                   // 0 = 國道 … 8 = 服務道路，9 = 停車場通道/車道
) {
    internal var nodes: IntArray = IntArray(0)
    internal var firstSeg = 0

    /** 預估路寬（地圖畫帶狀道路、限制橫向偏移用）：lanes × 3.5 → width → 依等級的預設。 */
    val estWidth: Float
        get() = lanes?.let { it * 3.5f } ?: width ?: when {
            rank >= 8 -> 4.5f
            rank >= 6 -> 6f
            else -> 7f
        }

    val roadClass: Int get() = when {
        rank <= 3 -> 0
        rank <= 5 -> 1
        rank <= 7 -> 2
        else -> 3
    }

    companion object {
        internal fun from(id: Long, tg: JSONObject): OsmWay {
            val hw = tg.optString("highway")
            val ow = when (tg.optString("oneway")) {
                "yes", "true", "1" -> 1
                "-1", "reverse" -> -1
                "no", "false", "0", "reversible", "alternating" -> 0
                else -> if (hw == "motorway" || hw == "motorway_link" ||
                    tg.optString("junction") == "roundabout" || tg.optString("junction") == "circular") 1 else 0
            }
            var rank = MapGraph.DRIVABLE[hw] ?: 8
            val svc = tg.optString("service")
            if (hw == "service" && (svc == "parking_aisle" || svc == "driveway" || svc == "drive-through")) rank = 9
            val name = tg.optString("name").ifBlank { tg.optString("ref").ifBlank { null } }
            return OsmWay(
                id, hw, ow,
                parseInt(tg.optString("lanes")), parseInt(tg.optString("lanes:forward")),
                parseInt(tg.optString("lanes:backward")),
                parseSpeed(tg.optString("maxspeed")), parseSpeed(tg.optString("maxspeed:forward")),
                parseSpeed(tg.optString("maxspeed:backward")),
                name, parseWidth(tg.optString("width")), rank
            )
        }

        /** "3"、"3;2"、" 4 " → 第一個整數；沒有 → null。 */
        internal fun parseInt(v: String?): Int? {
            if (v.isNullOrBlank()) return null
            val m = Regex("\\d+").find(v) ?: return null
            return m.value.toIntOrNull()?.takeIf { it in 1..20 }
        }

        /** "50"、"50 km/h"、"50|40"、"30 mph"、"TW:urban" → km/h；"none"/"signals" → null。 */
        internal fun parseSpeed(v: String?): Int? {
            if (v.isNullOrBlank()) return null
            val s = v.trim().lowercase()
            if (s == "tw:urban") return 50
            if (s == "tw:rural") return 60
            if (s == "tw:motorway") return 100
            val m = Regex("\\d+(\\.\\d+)?").find(s) ?: return null
            val n = m.value.toDoubleOrNull() ?: return null
            val kmh = if (s.contains("mph")) n * 1.609344 else n
            return kmh.roundToInt().takeIf { it in 5..160 }
        }

        /** "7"、"7.5 m"、"7,5" → 公尺；英尺或不合理值 → null。 */
        internal fun parseWidth(v: String?): Float? {
            if (v.isNullOrBlank() || v.contains('\'') || v.contains("ft")) return null
            val m = Regex("\\d+([.,]\\d+)?").find(v) ?: return null
            return m.value.replace(',', '.').toFloatOrNull()?.takeIf { it in 1.5f..80f }
        }
    }
}

/**
 * GPS → 道路配對 + 前方道路形狀。純 Kotlin（不碰 android.*），單執行緒使用。
 *
 * 配對：候選路段（有向、遵守單行）以 距離 + 航向一致 + 與上一筆配對的路網連續性 + 道路等級 打分，取最小；
 * 分不清時偏向留在同一條 way（平行匝道、高架下的平面道路）。
 * GPS 航向在 < 2 m/s 時不採用：沿用最後一次可靠的航向（權重放寬）；2–5 m/s 孤立的大偏差當雜訊。
 * 位置先過一個簡單的等速預測濾波，降低 GPS 抖動；單筆離譜跳點不採用（連續 3 筆才當真）。
 * 不硬配：雙向道上沒航向也沒上一筆（開機停著）、精度 > 50 m 又接不上上一筆、超出死巷 / 資料邊緣 → matched = false。
 */
class MapAssist {

    companion object {
        const val BACK_M = 30.0
        const val AHEAD_M = 200.0
        const val SIGNAL_M = 300.0
        const val STEP_M = 5.0
        const val ROADS_M = 300.0
        private const val UTURN_M = 20.0
        private const val MAX_ROAD_PTS = 2500
        private const val MAX_SPEED = 70.0          // m/s，超過的速度 / 位移當成跳點
        private const val MIN_BRG_SPEED = 2.0       // 低於這個速度的 GPS 航向不採用
        private const val POOR_ACC_M = 50.0         // 精度比這差：只在跟上一筆接得起來時才配
        private const val DEAD_END_OVER_M = 12.0    // 超出路尾（死巷 / 已載入範圍邊緣）這麼遠就不算在這條路上
        private const val SAME_WAY_BONUS = 0.5

        /** 送到 3D 面板的 payload 欄位 `map`。 */
        @JvmStatic
        fun toJson(s: MapState): JSONObject {
            val o = JSONObject()
            val path = JSONArray()
            for (p in s.path) path.put(JSONArray().put(r1(p[0])).put(r1(p[1])))
            o.put("path", path)
            o.put("lanes", s.lanes ?: JSONObject.NULL)
            o.put("width", s.widthM?.let { r1(it) } ?: JSONObject.NULL)
            o.put("oneway", s.oneway)
            o.put("maxspeed", s.maxspeedKmh ?: JSONObject.NULL)
            o.put("name", s.name ?: JSONObject.NULL)
            val sig = JSONArray()
            for (d in s.signalsAheadM) sig.put(r1(d))
            o.put("signals", sig)
            val roads = JSONArray()
            for (r in s.roads) {
                val a = JSONArray()
                for (p in r) a.put(JSONArray().put(p[0].roundToInt()).put(p[1].roundToInt()))   // 小地圖用，1 m 就夠
                roads.put(a)
            }
            o.put("roads", roads)
            val rc = JSONArray()
            for (c in s.roadClass) rc.put(c)
            o.put("rc", rc)
            o.put("acc", r1(s.accM))
            o.put("spd", r1(s.speedKmh))
            o.put("matched", s.matched)
            o.put("off", r1(s.offsetM))
            o.put("hw", s.highway ?: JSONObject.NULL)
            o.put("od", r1(s.odoM))
            o.put("t", s.timeMs)
            return o
        }

        private fun r1(v: Float): Double = r1(v.toDouble())
        private fun r1(v: Double): Double = if (v.isFinite()) Math.round(v * 10.0) / 10.0 else 0.0
    }

    var graph: MapGraph? = null
        private set

    /** 除錯：每個候選路段的分數。 */
    internal var debug: ((String) -> Unit)? = null

    // 濾波後位置（目前 graph 的平面座標）
    private var fx = 0.0
    private var fy = 0.0
    private var fVar = -1.0
    private var lastMs = Long.MIN_VALUE
    private var rawX = 0.0
    private var rawY = 0.0
    private var speedEst = 0.0
    private var outliers = 0            // 連續幾筆離譜跳點（先不採用）

    // 航向
    private var goodHeading = Double.NaN
    private var goodHeadingMs = Long.MIN_VALUE
    private var lastBrg = Double.NaN    // 上一筆原始 GPS 航向（判斷前後一致）
    private var lastBrgMs = Long.MIN_VALUE

    // 上一筆配對
    private var prevE = -1
    private var prevT = 0.0
    private var prevMs = Long.MIN_VALUE
    private var offEma = Double.NaN
    private var odo = 0.0

    // 暫存
    private var stamp = IntArray(0)
    private var mark = 0
    private var dist = DoubleArray(0)
    private val touched = ArrayList<Int>()

    fun loadOverpass(json: String, refLat: Double, refLon: Double) = load(MapGraph.parse(json, refLat, refLon))

    /** 換圖（例如補進新的圖磚）。上一筆配對與濾波狀態依 OSM id / 經緯度搬到新圖。 */
    fun load(g: MapGraph) {
        val old = graph
        if (old != null && fVar >= 0) {
            val lat = old.toLat(fy); val lon = old.toLon(fx)
            fx = g.toX(lon); fy = g.toY(lat)
            val rlat = old.toLat(rawY); val rlon = old.toLon(rawX)
            rawX = g.toX(rlon); rawY = g.toY(rlat)
        }
        if (old != null && prevE >= 0) {
            val a = old.nId[old.eFrom(prevE)]; val b = old.nId[old.eTo(prevE)]
            val wid = old.eWay(prevE).id
            prevE = -1
            val ia = g.nodeIndex(a); val ib = g.nodeIndex(b)
            if (ia >= 0 && ib >= 0) for (s in g.nSegs[ia]) {
                if (g.ways[g.sWay[s]].id != wid) continue
                if (g.sA[s] == ia && g.sB[s] == ib) { prevE = s * 2; break }
                if (g.sB[s] == ia && g.sA[s] == ib) { prevE = s * 2 + 1; break }
            }
        } else if (old != null) prevE = -1
        graph = g
        stamp = IntArray(g.segmentCount)
        mark = 0
        dist = DoubleArray(g.nodeCount) { Double.POSITIVE_INFINITY }
        touched.clear()
    }

    /** 清掉配對與濾波狀態（例如 GPS 斷很久）。 */
    fun reset() {
        fVar = -1.0; prevE = -1; offEma = Double.NaN; lastMs = Long.MIN_VALUE; outliers = 0
        goodHeading = Double.NaN; goodHeadingMs = Long.MIN_VALUE; lastBrg = Double.NaN
    }

    /**
     * 一筆 GPS 定位。bearingDeg / speedMps 沒有時給 NaN。
     * 還沒有地圖資料 → null；有地圖但配不上道路 → matched = false（仍附近道路給小地圖）。
     */
    fun update(lat: Double, lon: Double, speedMps: Double, bearingDeg: Double, accuracyM: Double, nowMs: Long): MapState? {
        val g = graph ?: return null
        if (g.isEmpty()) return null
        // NaN / 超出範圍的定位直接丟掉（不然會污染濾波狀態，之後每一筆都是 NaN）
        if (!validLatLon(lat, lon)) return null
        val zx = g.toX(lon); val zy = g.toY(lat)
        val acc = if (accuracyM.isFinite() && accuracyM > 0) accuracyM else 10.0
        val dt = if (lastMs == Long.MIN_VALUE) -1.0 else (nowMs - lastMs) / 1000.0
        val disp = hypot(zx - rawX, zy - rawY)

        // 速度：GPS 都卜勒速度優先；沒有就用位移估（跳點造成的離譜速度不採用）
        var v = speedMps
        if (!v.isFinite() || v < 0) {
            v = if (dt in 0.2..5.0) {
                val inst = disp / dt
                if (inst < MAX_SPEED) speedEst += (inst - speedEst) * 0.5
                speedEst
            } else 0.0
        }
        v = min(v, MAX_SPEED)
        var brg = bearingDeg
        if (!brg.isFinite() && dt in 0.2..5.0 && v >= 3.0 && disp >= 5.0 && disp <= MAX_SPEED * dt) brg = bearing(zx - rawX, zy - rawY)
        rawX = zx; rawY = zy

        // 航向：GPS 航向在 < 2 m/s 幾乎是亂數 → 不採用，沿用最後的可靠航向。2–5 m/s 權重漸增；
        // 這個速度區間跟目前航向差 > 45° 又跟上一筆原始航向差 > 30°/s 的，當成雜訊
        // （真的轉彎時前後幾筆是連續的，最多晚 1–2 筆才跟上）
        var accepted = false
        if (brg.isFinite() && v >= MIN_BRG_SPEED) {
            val b = norm360(brg)
            val bdt = (nowMs - lastBrgMs) / 1000.0
            val consistent = lastBrg.isFinite() && bdt > 0 && bdt <= 2.5 && angDiff(b, lastBrg) < 30.0 * max(1.0, bdt)
            lastBrg = b; lastBrgMs = nowMs
            if (!goodHeading.isFinite()) {
                if (v >= 5.0 || (v >= 3.0 && consistent)) { goodHeading = b; goodHeadingMs = nowMs; accepted = true }
            } else {
                var wt = ((v - MIN_BRG_SPEED) / 3.0).coerceIn(0.0, 1.0)
                if (v < 5.0 && !consistent && angDiff(b, goodHeading) > 45) wt = 0.0
                if (wt > 0) {
                    goodHeading = norm360(goodHeading + signedDiff(b, goodHeading) * wt)
                    goodHeadingMs = nowMs; accepted = true
                }
            }
        }
        val reliable = accepted && v >= 3.0
        val held = !reliable && goodHeading.isFinite() && nowMs - goodHeadingMs < 600_000
        val heading = if (reliable || held) goodHeading else Double.NaN

        // 位置濾波（等速預測 + 量測更新）。離譜的跳點（多路徑反射常見 30–100 m）先不採用、照預測走；
        // 連續 3 筆都離譜才當成真的（例如之前的估計錯了）並重設
        val sm2 = max(acc, 3.0).let { it * it }
        if (fVar < 0 || dt < 0 || dt > 10 || !fx.isFinite() || !fy.isFinite() || !fVar.isFinite()) {
            fx = zx; fy = zy; fVar = sm2; outliers = 0
        } else {
            if (dt > 0) {
                // 預測方向：可靠航向；否則沿上一筆配對的道路（但要跟記住的航向一致，免得錯配時越推越偏）
                var dirB = Double.NaN
                if (reliable) dirB = goodHeading
                else if (prevE >= 0 && nowMs - prevMs < 15_000) {
                    val eb = g.eBearing(prevE)
                    if (!heading.isFinite() || angDiff(eb, heading) < 60) dirB = eb
                }
                if (dirB.isFinite() && v > 0.3) {
                    val r = Math.toRadians(dirB)
                    fx += v * dt * sin(r); fy += v * dt * cos(r)
                }
                val q = (1.5 + 0.3 * v) * dt
                fVar += q * q
            }
            val ix = zx - fx; val iy = zy - fy
            if (hypot(ix, iy) > 3 * sqrt(fVar + sm2) + 10) {
                if (++outliers >= 3) { fx = zx; fy = zy; fVar = sm2; outliers = 0 }
            } else {
                outliers = 0
                val k = fVar / (fVar + sm2)
                fx += k * ix; fy += k * iy
                fVar *= (1 - k)
            }
        }
        lastMs = nowMs
        debug?.invoke(String.format("fix %d raw=(%.1f,%.1f) f=(%.1f,%.1f) sd=%.1f v=%.1f hdg=%.0f rel=%b out=%d",
            nowMs, zx, zy, fx, fy, sqrt(fVar), v, heading, reliable, outliers))

        val hadPrev = prevE >= 0 && nowMs - prevMs < 15_000
        var best = match(g, fx, fy, acc, v, max(dt, 0.0), heading, reliable, nowMs)
        if (best != null && !hadPrev && !heading.isFinite() && g.allowed(best.e xor 1)) {
            best = null   // 雙向道、沒有航向也沒有上一筆：分不出往哪開（開機時停著）→ 先不配
        }
        if (best != null && acc > POOR_ACC_M && !best.net.isFinite()) {
            best = null   // 精度很差又跟上一筆接不起來：不硬配
        }
        if (best == null) {
            if (prevE >= 0 && nowMs - prevMs > 15_000) prevE = -1
            val hd = if (heading.isFinite()) heading else 0.0
            val (roads, rc) = nearbyRoads(g, fx, fy, hd)
            return MapState(
                emptyList(), null, null, false, null, null, emptyList(), roads, false, 0f,
                (v * 3.6).toFloat(), acc.toFloat(), hd.toFloat(), g.toLat(fy), g.toLon(fx),
                null, 0L, rc, odo.toFloat(), nowMs
            )
        }
        val e = best.e
        val w = g.eWay(e)

        // 里程：沿路網的移動距離（虛線錨點）
        odo += if (best.net.isFinite()) best.net.coerceIn(-20.0, 200.0) else v * max(dt, 0.0)

        // 橫向偏移：EMA 後往「該側車道中間」收斂並限制在路面內
        val f = g.eFrom(e); val t = g.eTo(e)
        val ux = (g.nx[t] - g.nx[f]) / g.eLen(e); val uy = (g.ny[t] - g.ny[f]) / g.eLen(e)
        val px = g.nx[f] + ux * best.t; val py = g.ny[f] + uy * best.t
        val meas = (fx - px) * uy - (fy - py) * ux        // 右側為正：右法向 = (uy, -ux)
        offEma = if (offEma.isFinite() && prevE >= 0) offEma + (meas - offEma) * 0.25 else meas
        val half = w.estWidth / 2.0
        val prior: Double; val lo: Double; val hi: Double
        if (w.oneway != 0) {
            prior = 0.0; hi = max(0.0, half - 0.9); lo = -hi
        } else {
            prior = half / 2; lo = min(0.5, half * 0.5); hi = max(lo, half - 0.9)
        }
        val off = (prior + 0.5 * (offEma - prior)).coerceIn(lo, hi)

        prevE = e; prevT = best.t; prevMs = nowMs

        // 中心線：往後 30 m、往前 300 m（號誌找到 300 m，路徑輸出 200 m）
        val fwd = ArrayList<DoubleArray>()
        val sig = ArrayList<Double>()
        walk(g, e, best.t, SIGNAL_M + 5, true, fwd, sig)
        val back = ArrayList<DoubleArray>()
        walk(g, e, best.t, BACK_M + 5, false, back, null)
        val line = ArrayList<DoubleArray>(back.size + fwd.size)
        for (i in back.size - 1 downTo 1) line.add(back[i])   // back[0] = 投影點，fwd[0] 也是
        line.addAll(fwd)

        // 車輛座標：原點 = 投影點 + 橫向偏移，z 軸 = 道路切線
        val a0 = pointAt(line, -4.0); val a1 = pointAt(line, 4.0)
        val hdg = bearing(a1[0] - a0[0], a1[1] - a0[1])
        val hr = Math.toRadians(hdg)
        val sh = sin(hr); val ch = cos(hr)
        val cx = px + off * ch; val cy = py - off * sh
        val path = ArrayList<FloatArray>()
        val sMin = line.first()[2]; val sMax = min(line.last()[2], AHEAD_M)
        var s = -BACK_M
        while (s <= AHEAD_M + 1e-6) {
            if (s >= sMin - 1e-6 && s <= sMax + 1e-6) {
                val p = pointAt(line, s)
                val dx = p[0] - cx; val dy = p[1] - cy
                path.add(floatArrayOf((dx * ch - dy * sh).toFloat(), (dx * sh + dy * ch).toFloat()))
            }
            s += STEP_M
        }

        // 號誌：同一路口（35 m 內）只報第一個
        val sigs = ArrayList<Float>()
        var lastSig = -1e9
        for (d in sig) {
            if (d < -2 || d > SIGNAL_M) continue
            if (d - lastSig < 35) continue
            lastSig = d
            sigs.add(max(0.0, d).toFloat())
        }

        val (roads, rc) = nearbyRoads(g, cx, cy, hdg)
        val dirFwd = e and 1 == 0
        val maxs = (if (dirFwd) w.maxFwd else w.maxBwd) ?: w.maxspeed
        return MapState(
            path, w.lanes, w.width, w.oneway != 0, maxs, w.name, sigs, roads, true, off.toFloat(),
            (v * 3.6).toFloat(), acc.toFloat(), hdg.toFloat(), g.toLat(cy), g.toLon(cx),
            w.highway, w.id, rc, odo.toFloat(), nowMs
        )
    }

    private class Cand(val e: Int, val t: Double, val d: Double, var cost: Double, var net: Double)

    private fun match(
        g: MapGraph, x: Double, y: Double, acc: Double, v: Double, dt: Double,
        heading: Double, reliable: Boolean, nowMs: Long
    ): Cand? {
        val r = (2.5 * max(acc, 6.0)).coerceIn(30.0, 60.0)
        val segs = ArrayList<Int>()
        mark++
        if (mark == Int.MAX_VALUE) { stamp.fill(0); mark = 1 }
        g.segmentsNear(x, y, r, segs, stamp, mark)
        if (segs.isEmpty()) return null

        val sd = max(acc * 0.7, 4.0)
        val hasPrev = prevE >= 0 && nowMs - prevMs < 15_000
        val expected = v * dt
        val bound = expected * 1.6 + 60.0
        if (hasPrev) dijkstra(g, bound)

        var best: Cand? = null
        for (s in segs) {
            val a = g.sA[s]; val b = g.sB[s]
            val ax = g.nx[a]; val ay = g.ny[a]
            val bx = g.nx[b]; val by = g.ny[b]
            val len = g.sLen[s]
            val ux = (bx - ax) / len; val uy = (by - ay) / len
            val tRaw = (x - ax) * ux + (y - ay) * uy
            // 投影落在路尾之外、且那一端沒有別的路接（死巷或已載入資料的邊緣）→ 車子已不在這條路上
            if ((tRaw < -DEAD_END_OVER_M && g.nSegs[a].size == 1) || (tRaw > len + DEAD_END_OVER_M && g.nSegs[b].size == 1)) continue
            val tA = tRaw.coerceIn(0.0, len)
            val d = hypot(x - (ax + ux * tA), y - (ay + uy * tA))
            if (d > r) continue
            val w = g.ways[g.sWay[s]]
            for (dir in 0..1) {
                val e = s * 2 + dir
                if (!g.allowed(e)) continue
                val t = if (dir == 0) tA else len - tA
                var cost = 0.5 * (d / sd) * (d / sd)
                if (heading.isFinite()) {
                    val dh = angDiff(heading, g.eBearing(e))
                    cost += if (reliable) min(0.5 * (dh / 25.0) * (dh / 25.0), 9.0)
                    else min(0.5 * (dh / 45.0) * (dh / 45.0), 3.0)
                }
                var net = Double.NaN
                if (hasPrev) {
                    net = netDistance(g, e, t, bound)
                    cost += if (net.isFinite()) min(abs(net - expected) / (8.0 + 0.5 * expected), 4.5) else 4.5
                    if (v < 1.0 && e == prevE) cost -= 1.0     // 停車時黏住
                    // 分不清時假設還在同一條路上（平行的匝道 / 高架下的平面道路，GPS 分不出來）
                    if (g.sWay[s] == g.sWay[prevE shr 1]) cost -= SAME_WAY_BONUS
                }
                cost += when {
                    w.rank >= 9 -> 1.2
                    w.rank == 8 -> 0.7
                    w.rank == 7 -> 0.4
                    w.rank >= 5 -> 0.2
                    else -> 0.0
                }
                debug?.invoke(String.format("  cand %s e=%d d=%.1f net=%.1f exp=%.1f h=%.0f/%.0f cost=%.2f",
                    w.name, e, d, net, expected, heading, g.eBearing(e), cost))
                if (best == null || cost < best.cost) best = Cand(e, t, d, cost, net)
            }
        }
        if (best == null) return null
        if (best.d > max(3.0 * sd, 30.0)) return null
        return best
    }

    /** 從上一筆配對點出發的路網距離（有界 Dijkstra，結果放 dist[]）。 */
    private fun dijkstra(g: MapGraph, bound: Double) {
        for (n in touched) dist[n] = Double.POSITIVE_INFINITY
        touched.clear()
        val pq = PriorityQueue<DoubleArray>(32) { p, q -> p[0].compareTo(q[0]) }
        fun relax(n: Int, d: Double) {
            if (d < dist[n] && d <= bound) {
                if (dist[n] == Double.POSITIVE_INFINITY) touched.add(n)
                dist[n] = d
                pq.add(doubleArrayOf(d, n.toDouble()))
            }
        }
        relax(g.eTo(prevE), g.eLen(prevE) - prevT)
        if (g.allowed(prevE xor 1)) relax(g.eFrom(prevE), prevT + UTURN_M)
        while (pq.isNotEmpty()) {
            val top = pq.poll()
            val n = top[1].toInt()
            if (top[0] > dist[n]) continue
            for (s in g.nSegs[n]) {
                val e = if (g.sA[s] == n) s * 2 else s * 2 + 1
                if (!g.allowed(e)) continue
                relax(g.eTo(e), top[0] + g.sLen[s])
            }
        }
    }

    private fun netDistance(g: MapGraph, e: Int, t: Double, bound: Double): Double {
        if (e == prevE) return t - prevT
        if (e == (prevE xor 1)) return abs((g.eLen(e) - t) - prevT) + UTURN_M
        val d0 = dist[g.eFrom(e)]
        if (!d0.isFinite()) return Double.NaN
        val d = d0 + t
        return if (d <= bound + g.eLen(e)) d else Double.NaN
    }

    /**
     * 沿道路走：forward = 沿行進方向，否則往後。out：[x, y, s]（s 從投影點起算，往後為負）。
     * 每到一個節點取「最直」的延伸（同名、同等級加分；只有一條路就照走；多岔且都轉超過 75° 就停）。
     */
    private fun walk(g: MapGraph, e0: Int, t0: Double, maxLen: Double, forward: Boolean,
                     out: MutableList<DoubleArray>, sig: MutableList<Double>?) {
        val f = g.eFrom(e0); val t = g.eTo(e0)
        val len = g.eLen(e0)
        val ux = (g.nx[t] - g.nx[f]) / len; val uy = (g.ny[t] - g.ny[f]) / len
        out.add(doubleArrayOf(g.nx[f] + ux * t0, g.ny[f] + uy * t0, 0.0))
        val sign = if (forward) 1.0 else -1.0
        var e = e0
        var s = if (forward) len - t0 else t0
        var node = if (forward) t else f
        out.add(doubleArrayOf(g.nx[node], g.ny[node], sign * s))
        if (sig != null) checkSignal(g, node, e, s, sig)
        val seen = HashSet<Int>()
        seen.add(e0)
        var guard = 0
        while (s < maxLen && guard++ < 400) {
            val nx = nextEdge(g, e, forward) ?: break
            if (!seen.add(nx)) break
            e = nx
            s += g.eLen(e)
            node = if (forward) g.eTo(e) else g.eFrom(e)
            out.add(doubleArrayOf(g.nx[node], g.ny[node], sign * s))
            if (sig != null) checkSignal(g, node, e, s, sig)
        }
    }

    private fun checkSignal(g: MapGraph, n: Int, eIn: Int, s: Double, out: MutableList<Double>) {
        val f = g.nSig[n].toInt()
        if (f == 0) return
        val fwd = eIn and 1 == 0
        if (f == 1 || (f == 2 && fwd) || (f == 3 && !fwd)) out.add(s)
    }

    /** forward：e 之後接哪條；backward：e 之前是哪條（回傳仍是行進方向的有向邊）。 */
    private fun nextEdge(g: MapGraph, e: Int, forward: Boolean): Int? {
        val n = if (forward) g.eTo(e) else g.eFrom(e)
        val hIn = dirAlong(g, e, !forward)      // 進入 / 離開節點的方向（行進方向）
        val wIn = g.eWay(e)
        var best = -1
        var bestScore = Double.MAX_VALUE
        var bestTurn = 0.0
        var count = 0
        for (s in g.nSegs[n]) {
            if (s == e shr 1) continue
            // forward：從 n 出發的邊；backward：結束在 n 的邊
            val c = if (forward) (if (g.sA[s] == n) s * 2 else s * 2 + 1)
            else (if (g.sB[s] == n) s * 2 else s * 2 + 1)
            if (!g.allowed(c)) continue
            count++
            val hc = dirAlong(g, c, forward)
            val turn = angDiff(hIn, hc)
            val wc = g.eWay(c)
            var score = turn
            if (wc.name != wIn.name) score += 15.0
            score += min(30.0, 6.0 * max(0, wc.rank - wIn.rank))
            if (score < bestScore) { bestScore = score; best = c; bestTurn = turn }
        }
        if (best < 0) return null
        if (count >= 2 && bestTurn > 75.0) return null
        if (bestTurn > 170.0) return null
        return best
    }

    /**
     * 有向邊 e 在節點附近的方向（方位角）。fromStart = true：從邊的起點往前量 ~12 m；
     * false：從邊的終點往回量 ~12 m（進入終點時的方向）。同一條 way 的相鄰線段一起算，避開極短線段。
     */
    private fun dirAlong(g: MapGraph, e: Int, fromStart: Boolean): Double {
        var s = e shr 1
        val fwd = e and 1 == 0
        val wi = g.sWay[s]
        val w = g.ways[wi]
        val lastSeg = w.firstSeg + w.nodes.size - 2
        var acc = g.sLen[s]
        val anchor: Int
        var far: Int
        if (fromStart) {
            anchor = g.eFrom(e); far = g.eTo(e)
            while (acc < 12.0) {
                s = if (fwd) s + 1 else s - 1
                if (s < w.firstSeg || s > lastSeg) break
                far = if (fwd) g.sB[s] else g.sA[s]
                acc += g.sLen[s]
            }
            return bearing(g.nx[far] - g.nx[anchor], g.ny[far] - g.ny[anchor])
        } else {
            anchor = g.eTo(e); far = g.eFrom(e)
            while (acc < 12.0) {
                s = if (fwd) s - 1 else s + 1
                if (s < w.firstSeg || s > lastSeg) break
                far = if (fwd) g.sA[s] else g.sB[s]
                acc += g.sLen[s]
            }
            return bearing(g.nx[anchor] - g.nx[far], g.ny[anchor] - g.ny[far])
        }
    }

    /** 附近 300 m 的道路（車輛座標折線，Douglas-Peucker 1 m 簡化），大路排後面（畫在上層）。 */
    private fun nearbyRoads(g: MapGraph, cx: Double, cy: Double, hdg: Double): Pair<List<List<FloatArray>>, List<Int>> {
        val segs = ArrayList<Int>()
        mark++
        if (mark == Int.MAX_VALUE) { stamp.fill(0); mark = 1 }
        g.segmentsNear(cx, cy, ROADS_M, segs, stamp, mark)
        val inSet = HashSet<Int>()
        for (s in segs) {
            val a = g.sA[s]; val b = g.sB[s]
            if (segDist(cx, cy, g.nx[a], g.ny[a], g.nx[b], g.ny[b]) <= ROADS_M) inSet.add(s)
        }
        val byWay = HashMap<Int, MutableList<Int>>()
        for (s in inSet) byWay.getOrPut(g.sWay[s]) { ArrayList() }.add(s)
        val hr = Math.toRadians(hdg)
        val sh = sin(hr); val ch = cos(hr)
        class Poly(val pts: List<FloatArray>, val rank: Int, val dmin: Double)
        val polys = ArrayList<Poly>()
        for ((wi, list) in byWay) {
            list.sort()
            val w = g.ways[wi]
            var run = ArrayList<Int>()
            fun flush() {
                if (run.isEmpty()) return
                val raw = ArrayList<DoubleArray>(run.size + 1)
                raw.add(doubleArrayOf(g.nx[g.sA[run[0]]], g.ny[g.sA[run[0]]]))
                for (s in run) raw.add(doubleArrayOf(g.nx[g.sB[s]], g.ny[g.sB[s]]))
                val simp = simplify(raw, 1.0)
                var dmin = Double.MAX_VALUE
                val pts = ArrayList<FloatArray>(simp.size)
                for (p in simp) {
                    val dx = p[0] - cx; val dy = p[1] - cy
                    dmin = min(dmin, hypot(dx, dy))
                    pts.add(floatArrayOf((dx * ch - dy * sh).toFloat(), (dx * sh + dy * ch).toFloat()))
                }
                polys.add(Poly(pts, w.rank, dmin))
                run = ArrayList()
            }
            for (s in list) {
                if (run.isNotEmpty() && s != run[run.size - 1] + 1) flush()
                run.add(s)
            }
            flush()
        }
        // 點數上限：先丟遠的小路
        polys.sortWith(compareBy({ it.rank }, { it.dmin }))
        val keep = ArrayList<Poly>()
        var total = 0
        for (p in polys) {
            if (total + p.pts.size > MAX_ROAD_PTS) continue
            keep.add(p); total += p.pts.size
        }
        keep.sortWith(compareBy({ -it.rank }, { -it.dmin }))
        return keep.map { it.pts } to keep.map { roadClassOf(it.rank) }
    }

    private fun roadClassOf(rank: Int) = when {
        rank <= 3 -> 0
        rank <= 5 -> 1
        rank <= 7 -> 2
        else -> 3
    }
}

// ---------------- 幾何小工具 ----------------

/** 方位角（度，北 = 0，順時針）。 */
internal fun bearing(dx: Double, dy: Double): Double = norm360(Math.toDegrees(atan2(dx, dy)))

/** 經緯度有限且在合理範圍（擋掉 NaN / 無窮大 / 亂碼座標）。 */
internal fun validLatLon(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() && lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0

internal fun norm360(a: Double): Double { val r = a % 360.0; return if (r < 0) r + 360.0 else r }

/** 兩方位角的夾角（0..180）。 */
internal fun angDiff(a: Double, b: Double): Double { val d = abs(norm360(a - b)); return if (d > 180) 360 - d else d }

/** a − b 的有號角差（-180..180）。 */
internal fun signedDiff(a: Double, b: Double): Double { val d = norm360(a - b); return if (d > 180) d - 360 else d }

internal fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay
    val l2 = dx * dx + dy * dy
    val t = if (l2 <= 0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
    return hypot(px - (ax + dx * t), py - (ay + dy * t))
}

/** 折線（[x, y, s]，s 遞增）上弧長 s 的點；超出兩端則取端點方向外插。 */
internal fun pointAt(line: List<DoubleArray>, s: Double): DoubleArray {
    if (line.size == 1) return line[0]
    var i = 1
    if (s >= line[line.size - 1][2]) {
        i = line.size - 1
    } else if (s > line[0][2]) {
        while (i < line.size - 1 && line[i][2] < s) i++
    }
    val a = line[i - 1]; val b = line[i]
    val ds = b[2] - a[2]
    val k = if (ds <= 1e-9) 0.0 else (s - a[2]) / ds
    return doubleArrayOf(a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, s)
}

/** Douglas-Peucker 折線簡化。 */
internal fun simplify(pts: List<DoubleArray>, tol: Double): List<DoubleArray> {
    if (pts.size < 3) return pts
    val keep = BooleanArray(pts.size)
    keep[0] = true; keep[pts.size - 1] = true
    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, pts.size - 1))
    while (stack.isNotEmpty()) {
        val (i0, i1) = stack.removeLast().let { it[0] to it[1] }
        var dmax = 0.0; var im = -1
        for (i in i0 + 1 until i1) {
            val d = segDist(pts[i][0], pts[i][1], pts[i0][0], pts[i0][1], pts[i1][0], pts[i1][1])
            if (d > dmax) { dmax = d; im = i }
        }
        if (dmax > tol && im > 0) {
            keep[im] = true
            stack.addLast(intArrayOf(i0, im)); stack.addLast(intArrayOf(im, i1))
        }
    }
    return pts.filterIndexed { i, _ -> keep[i] }
}

/**
 * OSM 圖磚（0.005° 見方，台灣約 555 × 505 m）：決定要抓哪些範圍、把 Overpass 回應切成圖磚存快取。
 */
object OsmTiles {
    const val TILE = 0.005
    const val NEED_M = 700.0          // 前導點周圍 ±700 m 都要有資料
    const val LEAD_MAX_M = 300.0      // 前導點最多在車前 300 m（約 20 s 車程）
    /** split() 產生的圖磚檔小於這個大小 = 沒有任何道路（空磚約 40 bytes；一條最短的路就 > 250）。 */
    const val EMPTY_TILE_BYTES = 200L

    val HIGHWAY_RE = MapGraph.DRIVABLE.keys.sorted().joinToString("|")

    fun ix(lon: Double) = floor(lon / TILE).toInt()
    fun iy(lat: Double) = floor(lat / TILE).toInt()
    fun key(ix: Int, iy: Int) = "${iy}_$ix"

    /** 圖磚 (s, w, n, e)。 */
    fun bounds(ix: Int, iy: Int) = doubleArrayOf(iy * TILE, ix * TILE, (iy + 1) * TILE, (ix + 1) * TILE)

    /** 以 (lat, lon) 為中心、半邊長 halfM 的方塊會碰到的圖磚 [ix, iy]。 */
    fun around(lat: Double, lon: Double, halfM: Double): List<IntArray> {
        val dLat = halfM / 111_195.0
        val dLon = halfM / (111_195.0 * cos(Math.toRadians(lat)))
        val out = ArrayList<IntArray>()
        for (y in iy(lat - dLat)..iy(lat + dLat)) for (x in ix(lon - dLon)..ix(lon + dLon)) out.add(intArrayOf(x, y))
        return out
    }

    /** 行車需要的圖磚：前導點（車前 min(300 m, 20 s 車程)）周圍 ±700 m；含車子周圍 ±400 m。 */
    fun needed(lat: Double, lon: Double, headingDeg: Double, speedMps: Double): List<IntArray> {
        var la = lat; var lo = lon
        if (headingDeg.isFinite() && speedMps.isFinite() && speedMps > 1) {
            val lead = min(LEAD_MAX_M, speedMps * 20.0)
            val r = Math.toRadians(headingDeg)
            la += lead * cos(r) / 111_195.0
            lo += lead * sin(r) / (111_195.0 * cos(Math.toRadians(lat)))
        }
        val set = LinkedHashMap<String, IntArray>()
        for (t in around(la, lo, NEED_M)) set[key(t[0], t[1])] = t
        for (t in around(lat, lon, 400.0)) set[key(t[0], t[1])] = t
        return set.values.toList()
    }

    /** 一組圖磚的外框 (s, w, n, e)。 */
    fun bbox(tiles: List<IntArray>): DoubleArray {
        var x0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y0 = Int.MAX_VALUE; var y1 = Int.MIN_VALUE
        for (t in tiles) { x0 = min(x0, t[0]); x1 = max(x1, t[0]); y0 = min(y0, t[1]); y1 = max(y1, t[1]) }
        return doubleArrayOf(y0 * TILE, x0 * TILE, (y1 + 1) * TILE, (x1 + 1) * TILE)
    }

    /** Overpass 查詢：可行車道路 + 號誌節點；way 的節點只要座標（skel）。 */
    fun query(s: Double, w: Double, n: Double, e: Double): String {
        val bb = String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", s, w, n, e)
        return "[out:json][timeout:25];(way[\"highway\"~\"^($HIGHWAY_RE)$\"][\"area\"!=\"yes\"]($bb);" +
            "node[\"highway\"=\"traffic_signals\"]($bb););out body;>;out skel qt;"
    }

    private val KEEP_TAGS = setOf(
        "highway", "oneway", "junction", "lanes", "lanes:forward", "lanes:backward", "maxspeed",
        "maxspeed:forward", "maxspeed:backward", "name", "ref", "width", "service", "area", "access",
        "motor_vehicle", "motorcar", "vehicle", "traffic_signals:direction", "direction"
    )

    /**
     * 把一份 Overpass 回應切成圖磚（只留用得到的 tag）。圖磚含：任一節點落在磚內、或線段穿過磚的 way
     * （整條、含全部節點座標），以及磚內的號誌節點。回傳 key → 精簡 JSON 字串。
     */
    fun split(json: String, tiles: List<IntArray>): Map<String, String> {
        val root = JSONObject(json)
        val els = root.optJSONArray("elements") ?: JSONArray()
        val lat = HashMap<Long, Double>(); val lon = HashMap<Long, Double>()
        val sig = HashMap<Long, JSONObject>()
        val ways = ArrayList<JSONObject>()
        for (i in 0 until els.length()) {
            val e = els.optJSONObject(i) ?: continue
            when (e.optString("type")) {
                "node" -> {
                    val id = e.optLong("id")
                    if (e.has("lat")) { lat[id] = e.getDouble("lat"); lon[id] = e.getDouble("lon") }
                    val tg = e.optJSONObject("tags")
                    if (tg != null && tg.optString("highway") == "traffic_signals") sig[id] = tg
                }
                "way" -> ways.add(e)
            }
        }
        val out = LinkedHashMap<String, String>()
        for (t in tiles) {
            val b = bounds(t[0], t[1])
            val arr = JSONArray()
            val used = LinkedHashSet<Long>()
            for (w in ways) {
                val na = w.optJSONArray("nodes") ?: continue
                var hit = false
                var pLat = Double.NaN; var pLon = Double.NaN
                for (k in 0 until na.length()) {
                    val id = na.getLong(k)
                    val la = lat[id] ?: continue; val lo = lon[id] ?: continue
                    if (la >= b[0] && la < b[2] && lo >= b[1] && lo < b[3]) { hit = true; break }
                    if (pLat.isFinite() && min(pLat, la) < b[2] && max(pLat, la) >= b[0] &&
                        min(pLon, lo) < b[3] && max(pLon, lo) >= b[1]) { hit = true; break }
                    pLat = la; pLon = lo
                }
                if (!hit) continue
                val tg = JSONObject()
                w.optJSONObject("tags")?.let { src -> for (k in src.keys()) if (k in KEEP_TAGS) tg.put(k, src.get(k)) }
                arr.put(JSONObject().put("type", "way").put("id", w.optLong("id")).put("nodes", na).put("tags", tg))
                for (k in 0 until na.length()) used.add(na.getLong(k))
            }
            for ((id, tg) in sig) {
                val la = lat[id] ?: continue; val lo = lon[id] ?: continue
                if (la >= b[0] && la < b[2] && lo >= b[1] && lo < b[3]) used.add(id)
            }
            for (id in used) {
                val la = lat[id] ?: continue; val lo = lon[id] ?: continue
                val n = JSONObject().put("type", "node").put("id", id).put("lat", la).put("lon", lo)
                sig[id]?.let { src ->
                    val tg = JSONObject()
                    for (k in src.keys()) if (k in KEEP_TAGS || k == "traffic_signals") tg.put(k, src.get(k))
                    n.put("tags", tg)
                }
                arr.put(n)
            }
            out[key(t[0], t[1])] = JSONObject().put("tile", key(t[0], t[1])).put("elements", arr).toString()
        }
        return out
    }
}
