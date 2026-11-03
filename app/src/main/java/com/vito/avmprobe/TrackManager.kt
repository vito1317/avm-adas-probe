package com.vito.avmprobe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一筆偵測（車身座標：x 右、z 前，m）。x/z 為框底中點投到地面、再沿鏡頭視線外推 ObjTypes.radius 的點。
 * cam：來源鏡頭 "front"|"rear"|"left"|"right"（決定量測誤差的方向）。
 * atMs：拍攝時間；比 nowMs 早時會補償推論延遲。-1 = 視為 nowMs。
 */
data class Det(val t: String, val x: Float, val z: Float, val c: Float, val cam: String, val atMs: Long = -1L)

/**
 * 追蹤結果（相對自車）。vx/vz：相對速度 m/s（+z = 往前）。sog：對地速度，有自車車速才有。
 * w/l：依種類的外形寬/長（m），x/z 是外形中心。sd：位置標準差（m）。svx/svz：速度標準差（m/s），
 * 新軌跡很大（遠處 2 Hz、1 m 以上誤差），判斷 TTC 時要扣掉。unseenMs：多久沒被看到（滑行中）。
 */
data class Track(
    val id: Int, val t: String, val x: Float, val z: Float, val vx: Float, val vz: Float,
    val ageMs: Long, val hits: Int, val confirmed: Boolean,
    val w: Float = 0f, val l: Float = 0f, val sog: Float? = null,
    val unseenMs: Long = 0L, val sd: Float = 0f, val cam: String = "",
    val svx: Float = 0f, val svz: Float = 0f
)

/**
 * 多目標追蹤（純 Kotlin）：每個軌跡一個等速卡爾曼濾波（狀態 x, z, vx, vz，相對自車）。
 *
 * 輸入特性：四顆魚眼輪流偵測，每顆只有 2–4 Hz；誤差近處 0.2 m、10–15 m 處 1–2 m，且主要在「視線方向」
 * （單眼投地的距離誤差）→ 量測共變異數依來源鏡頭做成沿視線拉長的橢圓，關聯用馬氏距離門檻。
 * 同一次呼叫裡不同鏡頭的偵測依序各更新一次（後鏡頭→側鏡頭交接時，同一台車兩邊都看得到也不會分裂）。
 *
 * 用法：每跑完一顆鏡頭就呼叫 update(該鏡頭這次的偵測, now, 車速)；沒有新偵測的幀可傳空清單只做預測。
 * 不要把舊結果重送（同一鏡頭完全相同的一批會被略過，但最好別依賴這個）。
 *
 * nearFaceCorrection：偵測點其實是物件「靠鏡頭那一面」再外推 radius；車子從正後方看，真正中心還要再往後
 * 半個車長。開啟時依種類外形沿視線推到中心，讓後鏡頭與側鏡頭的量測落在同一點。
 */
class TrackManager(
    private val camPos: Map<String, FloatArray> = DEFAULT_CAMS,
    var nearFaceCorrection: Boolean = true,
    /** 呼叫端已經沿視線外推的距離（須與 ObjTypes.radius 一致）。 */
    private val pushRadius: (String) -> Float = ::defaultRadius
) {
    companion object {
        /**
         * 鏡頭地面位置與水平朝向 (x, z, fx, fz)，取 avm_calib.json 預設值；校正後可傳實際值
         * （只給 x, z 也可以，就不做魚眼邊緣的誤差放大）。
         */
        val DEFAULT_CAMS: Map<String, FloatArray> = mapOf(
            "front" to floatArrayOf(0f, 2.35f, 0f, 1f), "rear" to floatArrayOf(0f, -2.40f, 0f, -1f),
            "left" to floatArrayOf(-1.0f, 0.85f, -1f, 0f), "right" to floatArrayOf(1.0f, 0.85f, 1f, 0f)
        )
        const val EGO_HW = 0.925f
        const val EGO_HL = 2.42f

        const val G_VEH = 0
        const val G_TWO = 1
        const val G_PED = 2
        const val G_CONE = 3
        const val G_OTHER = 4

        /** 相容種類：汽車類互通、二輪互通、行人、三角錐（靜止）。 */
        fun group(t: String): Int = when (t) {
            "car", "van", "truck", "bus" -> G_VEH
            "moto", "bigbike", "bike" -> G_TWO
            "ped" -> G_PED
            "cone" -> G_CONE
            else -> G_OTHER
        }

        /** 外形（寬, 長）m。 */
        fun size(t: String): FloatArray = when (t) {
            "car" -> floatArrayOf(1.8f, 4.5f)
            "van" -> floatArrayOf(1.9f, 4.9f)
            "truck" -> floatArrayOf(2.3f, 7.0f)
            "bus" -> floatArrayOf(2.5f, 11.0f)
            "moto" -> floatArrayOf(0.7f, 1.9f)
            "bigbike" -> floatArrayOf(0.8f, 2.1f)
            "bike" -> floatArrayOf(0.6f, 1.7f)
            "ped" -> floatArrayOf(0.5f, 0.5f)
            "cone" -> floatArrayOf(0.4f, 0.4f)
            else -> floatArrayOf(1.0f, 1.0f)
        }

        /** 與 ObjTypes.radius 相同的數值（這裡不直接引用，保持本檔獨立可測）。 */
        fun defaultRadius(t: String): Float = when (t) {
            "truck", "bus" -> 1.2f
            "van" -> 1.0f
            "car" -> 0.9f
            "moto", "bigbike", "bike" -> 0.4f
            "ped" -> 0.3f
            "cone" -> 0.2f
            else -> 0.5f
        }

        private const val GATE = 13.8            // 馬氏距離平方門檻（2 自由度 99.9%）
        private const val HUBER = 4.0            // 馬氏距離平方超過這個（2σ）就放大量測誤差
        private const val HUBER_HITS = 5         // 至少命中這麼多次（已收斂）才做
        private const val COVER_R = 8.0          // 「該看得到」的範圍：偵測率高的距離內
        private val COS_COVER = cos(Math.toRadians(80.0))
        private const val MAX_JUMP = 8.0         // 再怎麼不確定，一次也不跳超過 8 m
        private const val TENTATIVE_MS = 1000L   // 候選軌跡 1 s 內沒第二次命中就丟
        private const val COAST_MS = 1200L       // 確認軌跡沒看到 1.2 s 就刪
        private const val COAST_GAP_MS = 2500L   // 車身旁近距離（鏡頭交界、太近框不完整）可滑行較久
        private const val COAST_FAR_MS = 1800L   // 8 m 外只剩 2 Hz 的前/後鏡頭、偵測率又低
        private const val GAP_RING = 3.5         // 距車身外框幾 m 內算「近距離」
        private const val FAR_RING = 8.0
        private val COS_EDGE = cos(Math.toRadians(70.0))   // 超過 70° 算魚眼邊緣（變形大、框不完整）

        /** 同類軌跡這麼近就不另外生新軌跡（同一台車的重複框或模型誤差），約一個物件的大小。 */
        private fun birthNear(g: Int) = when (g) { G_VEH -> 2.8; G_TWO -> 1.2; G_PED -> 0.7; G_CONE -> 0.5; else -> 1.0 }

        /** 兩條確認軌跡這麼近、速度相近 → 合併。 */
        private fun mergeNear(g: Int) = when (g) { G_VEH -> 2.0; G_TWO -> 1.0; G_PED -> 0.5; G_CONE -> 0.4; else -> 0.8 }
    }

    private class T(val id: Int, val group: Int, val bornMs: Long) {
        val s = DoubleArray(4)       // x, z, vx, vz
        val p = DoubleArray(16)      // 共變異數（列優先）
        var seenMs = bornMs
        var hits = 1
        var confirmed = false
        val votes = LinkedHashMap<String, Float>()
        var type = ""
        var cam = ""
        var dead = false
        /** 連續幾次「鏡頭該看得到卻沒看到」。 */
        var miss = 0
    }

    private class Cand(val di: Int, val tr: T, val cost: Double, val m: DoubleArray, val d2: Double)

    private val tracks = ArrayList<T>()
    private var nextId = 1
    private var lastMs = Long.MIN_VALUE
    private val lastSig = HashMap<String, Int>()
    private val lastSigAt = HashMap<String, Long>()
    private val kk = DoubleArray(8)
    private val m4 = DoubleArray(16)
    private val n4 = DoubleArray(16)

    fun reset() {
        tracks.clear()
        lastSig.clear()
        lastSigAt.clear()
        lastMs = Long.MIN_VALUE
    }

    /**
     * dets：自上次呼叫後新產生的偵測（通常是一顆鏡頭的結果）。egoSpeedMps：車速 m/s，可為 null；
     * 知道在倒車（排檔 R）時傳負值（GPS 只有速率，分不出前進倒退）。
     * egoYawRate：自車橫擺角速度 rad/s（+ = 左轉），有方向盤角/陀螺儀時才傳。
     * cam：這批偵測來自哪顆鏡頭——**沒偵測到東西時也要傳**，追蹤器才知道「該看到卻沒看到」，
     * 兩個誤報湊成的假軌跡才能很快刪掉（不傳就只靠逾時）。回傳所有軌跡（含候選，confirmed=false）。
     */
    fun update(
        dets: List<Det>, nowMs: Long, egoSpeedMps: Float?, egoYawRate: Float? = null, cam: String? = null
    ): List<Track> {
        if (lastMs == Long.MIN_VALUE) lastMs = nowMs
        val dt = (nowMs - lastMs).coerceAtLeast(0L) / 1000.0
        if (dt > 0) {
            val rot = (egoYawRate?.takeIf { it.isFinite() } ?: 0f) * dt
            for (tr in tracks) predict(tr, dt, rot)
            lastMs = nowMs
        }
        val ego = egoSpeedMps?.takeIf { it.isFinite() }?.toDouble()

        val groups = LinkedHashMap<String, ArrayList<Det>>()
        if (cam != null) groups[cam] = ArrayList()
        for (d in dets) if (d.x.isFinite() && d.z.isFinite()) groups.getOrPut(d.cam) { ArrayList() }.add(d)
        for ((c, list) in groups) {
            if (list.isNotEmpty()) {
                val sig = signature(list)
                if (lastSig[c] == sig && nowMs - (lastSigAt[c] ?: Long.MIN_VALUE / 2) < 3000) continue  // 重送的舊結果
                lastSig[c] = sig
                lastSigAt[c] = nowMs
            }
            val hit = associate(list, nowMs, ego)
            countMisses(c, hit)
        }
        // 三角錐是靜止物：相對速度 = −自車速度（虛擬量測）
        if (ego != null) for (tr in tracks) if (tr.group == G_CONE) kalman(tr, 2, 3, 0.0, -ego, 0.0, 0.0, 0.36, 0.0, 0.36)
        manage(nowMs)
        return output(nowMs, ego)
    }

    // ---------------- 量測 ----------------

    /** 偵測 → (中心 x, z, R00, R01, R11, 延遲 s)。type：用哪個種類的外形推中心。 */
    private fun measurement(d: Det, type: String, now: Long, out: DoubleArray) {
        var mx = d.x.toDouble()
        var mz = d.z.toDouble()
        val cp = camPos[d.cam]
        val g = group(type)
        val sr: Double
        val st: Double
        val ux: Double
        val uz: Double
        if (cp == null) {
            val r = max(hypot(mx, mz) - 1.5, 0.5)
            sr = 0.2 + 0.08 * r
            st = sr
            ux = 1.0; uz = 0.0
        } else {
            val cx = cp[0].toDouble()
            val cz = cp[1].toDouble()
            var dist = max(hypot(mx - cx, mz - cz), 0.05)
            ux = (mx - cx) / dist
            uz = (mz - cz) / dist
            var model = 0.0
            if (nearFaceCorrection && g != G_OTHER) {
                val sz = size(type)
                val dc = centreDistance(dist - pushRadius(d.t), ux, uz, sz[0] / 2.0, sz[1] / 2.0)
                if (dc > 0) {
                    dist = dc
                    mx = cx + ux * dist
                    mz = cz + uz * dist
                    if (g == G_VEH) model = 0.25   // 車身方向未知的模型誤差
                }
            }
            // 魚眼邊緣（側看）：框只拍到一部分、方位偏向最近點，誤差放大（90° 時約 1.7 倍）
            val edge = if (cp.size >= 4) 1.0 + 2.0 * max(0.0, COS_EDGE - (ux * cp[2] + uz * cp[3])) else 1.0
            sr = (0.15 + 0.08 * dist + model) * edge
            st = (0.12 + 0.03 * dist) * edge
        }
        val f = 1.0 + 1.5 * max(0.0, 0.45 - d.c)   // 信心低 → 誤差放大
        val a = (sr * f) * (sr * f)
        val b = (st * f) * (st * f)
        // R = a·u·uᵀ + b·n·nᵀ，n ⟂ u
        out[0] = mx
        out[1] = mz
        out[2] = a * ux * ux + b * uz * uz
        out[3] = (a - b) * ux * uz
        out[4] = a * uz * uz + b * ux * ux
        out[5] = if (d.atMs >= 0 && d.atMs in (now - 1500) until now) (now - d.atMs) / 1000.0 else 0.0
    }

    /**
     * 已知：鏡頭到物件最近一點的距離 dn、視線方向 u、外形半寬/半長（與車身同向）。
     * 解中心距離 dc：外形中心在鏡頭 + u·dc 時，鏡頭到外形的最近距離 = dn。dn 太小回傳 −1。
     */
    private fun centreDistance(dn: Double, ux: Double, uz: Double, hw: Double, hl: Double): Double {
        if (dn < 0.1) return -1.0
        val ax = abs(ux)
        val az = abs(uz)
        fun near(dc: Double) = hypot(max(dc * ax - hw, 0.0), max(dc * az - hl, 0.0))
        var lo = dn
        var hi = dn + hw + hl + 0.5
        while (near(hi) < dn && hi < dn + 40) hi += hw + hl + 0.5
        repeat(30) {
            val mid = (lo + hi) / 2
            if (near(mid) < dn) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    // ---------------- 關聯 ----------------

    private fun compatible(tr: T, g: Int, t: String): Boolean =
        tr.group == g && (g != G_OTHER || tr.type == t)

    /** 回傳這批偵測更新到的軌跡。 */
    private fun associate(list: List<Det>, now: Long, ego: Double?): Set<T> {
        val n = list.size
        if (n == 0) return emptySet()
        val m = Array(n) { DoubleArray(6) }
        for (i in 0 until n) measurement(list[i], list[i].t, now, m[i])
        val cands = ArrayList<Cand>()
        for (i in 0 until n) {
            val d = list[i]
            val g = group(d.t)
            for (tr in tracks) {
                if (tr.dead || !compatible(tr, g, d.t)) continue
                // 外形用軌跡累積投票的種類（偵測偶爾把轎車認成卡車，不讓它一次把中心推遠 1 m）
                val mi = if (nearFaceCorrection && tr.type != d.t) DoubleArray(6).also { measurement(d, tr.type, now, it) } else m[i]
                val y0 = mi[0] - (tr.s[0] - tr.s[2] * mi[5])
                val y1 = mi[1] - (tr.s[1] - tr.s[3] * mi[5])
                if (hypot(y0, y1) > MAX_JUMP) continue
                val s00 = tr.p[0] + mi[2]
                val s01 = tr.p[1] + mi[3]
                val s11 = tr.p[5] + mi[4]
                val det = s00 * s11 - s01 * s01
                if (det <= 1e-12) continue
                val d2 = (y0 * y0 * s11 - 2 * y0 * y1 * s01 + y1 * y1 * s00) / det
                if (d2 > GATE) continue
                cands.add(Cand(i, tr, d2 + ln(det) - if (tr.confirmed) 1.0 else 0.0, mi, d2))
            }
        }
        cands.sortBy { it.cost }
        val usedD = BooleanArray(n)
        val usedT = HashSet<T>()
        for (c in cands) {
            if (usedD[c.di] || c.tr in usedT) continue
            usedD[c.di] = true
            usedT.add(c.tr)
            val mi = c.m
            val tr = c.tr
            // 穩健更新（Huber）：已收斂的軌跡遇到偏離超過 2σ 的量測就把 R 放大，偶發的大距離誤差不會把軌跡一次拉走；
            // 新軌跡不做（速度還沒收斂，大偏差多半是真的，壓下去反而跟不上，例如快速經過的路邊停車）
            val f = if (c.d2 > HUBER && tr.hits >= HUBER_HITS) min(c.d2 / HUBER, 10.0) else 1.0
            kalman(tr, 0, 1, mi[0], mi[1], tr.s[2] * mi[5], tr.s[3] * mi[5], mi[2] * f, mi[3] * f, mi[4] * f)
            hit(tr, list[c.di], now, ego)
        }
        for (i in 0 until n) if (!usedD[i]) birth(list[i], m[i], now, ego)?.let { usedT.add(it) }
        return usedT
    }

    /**
     * 這顆鏡頭該看得到（離鏡頭 1.5–8 m、在魚眼中央 160° 內、沒被自己車身擋住）卻沒更新到的軌跡 → miss + 1。
     * 只用來早點刪掉證據很少的軌跡（候選、只有 2–3 次命中的確認軌跡）。
     */
    private fun countMisses(cam: String, hit: Set<T>) {
        val cp = camPos[cam] ?: return
        if (cp.size < 4) return
        for (tr in tracks) {
            if (tr.dead) continue
            if (tr in hit) { tr.miss = 0; continue }
            val dx = tr.s[0] - cp[0]
            val dz = tr.s[1] - cp[1]
            val d = hypot(dx, dz)
            if (d < 1.5 || d > COVER_R) continue
            if ((dx * cp[2] + dz * cp[3]) / d < COS_COVER) continue
            if (segHitsEgo(cp[0].toDouble(), cp[1].toDouble(), tr.s[0], tr.s[1])) continue
            tr.miss++
        }
    }

    /** 線段（鏡頭 → 點）穿過自車車身（內縮 0.1 m）。 */
    private fun segHitsEgo(x0: Double, z0: Double, x1: Double, z1: Double): Boolean {
        val a = EGO_HW - 0.1
        val b = EGO_HL - 0.1
        var t0 = 0.0
        var t1 = 1.0
        val dx = x1 - x0
        val dz = z1 - z0
        val p = doubleArrayOf(-dx, dx, -dz, dz)
        val q = doubleArrayOf(x0 + a, a - x0, z0 + b, b - z0)
        for (i in 0..3) {
            if (p[i] == 0.0) {
                if (q[i] < 0) return false
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0) { if (r > t1) return false; if (r > t0) t0 = r } else { if (r < t0) return false; if (r < t1) t1 = r }
            }
        }
        return t0 < t1 - 1e-9
    }

    /** 第 2 次命中就確認；但兩點算出的運動不合理（多半是兩個誤報剛好連上）時要第 3 次命中。 */
    private fun hit(tr: T, d: Det, now: Long, ego: Double?) {
        tr.seenMs = now
        tr.hits++
        if (!tr.confirmed && tr.hits >= 2) tr.confirmed = tr.hits >= 3 || plausible(tr, ego)
        tr.cam = d.cam
        vote(tr, d)
    }

    private fun plausible(tr: T, ego: Double?): Boolean {
        val vx = tr.s[2]
        val vz = tr.s[3]
        return when (tr.group) {
            G_VEH, G_TWO -> abs(vx) <= 5.0 && abs(vz) <= 25.0
            G_PED -> ego == null || hypot(vx, vz + ego) <= 4.0
            G_CONE -> ego == null || hypot(vx, vz + ego) <= 3.0
            else -> true
        }
    }

    private fun vote(tr: T, d: Det) {
        for (e in tr.votes.entries) e.setValue(e.value * 0.7f)
        tr.votes[d.t] = (tr.votes[d.t] ?: 0f) + max(d.c, 0.05f)
        var best = tr.type
        var bv = -1f
        for ((k, v) in tr.votes) if (v > bv) { bv = v; best = k }
        tr.type = best
    }

    private fun birth(d: Det, mi: DoubleArray, now: Long, ego: Double?): T? {
        val g = group(d.t)
        if (abs(mi[0]) < EGO_HW + 0.05 && abs(mi[1]) < EGO_HL + 0.05) return null   // 自己的車身
        val near = birthNear(g)
        for (tr in tracks) {
            if (tr.dead || tr.group != g) continue
            if (hypot(tr.s[0] - mi[0], tr.s[1] - mi[1]) < near) return null
            // 汽車類：落在既有軌跡的車身範圍裡（大客車/卡車 7–11 m 長，不同鏡頭看到的框中心可以差好幾公尺）。
            // 二輪/行人不套用：並排騎/走的兩個要分得開
            if (g == G_VEH) {
                val sz = size(tr.type)
                if (abs(tr.s[0] - mi[0]) < sz[0] / 2 + 0.6 && abs(tr.s[1] - mi[1]) < sz[1] / 2 + 1.0) return null
            }
        }
        val tr = T(nextId++, g, now)
        val e = ego ?: 0.0
        // 初始速度：靜止物 / 行人以對地靜止為先驗；車輛橫向相對速度很少超過 3 m/s（換道約 1 m/s），
        // 縱向 ±25 km/h 以上都可能。橫向先驗收窄，兩個不相干的誤報就不容易被連成一條軌跡。
        val vz0: Double
        val svx: Double
        val svz: Double
        when (g) {
            G_CONE -> { vz0 = -e; svx = if (ego != null) 1.0 else 4.0; svz = svx }
            G_PED -> { vz0 = -e; svx = if (ego != null) 2.5 else 4.0; svz = svx }
            G_VEH, G_TWO -> { vz0 = 0.0; svx = 3.0; svz = max(7.0, 0.6 * abs(e)) }
            else -> { vz0 = 0.0; svx = 5.0; svz = max(7.0, 0.6 * abs(e)) }
        }
        tr.s[0] = mi[0]; tr.s[1] = mi[1]; tr.s[2] = 0.0; tr.s[3] = vz0
        tr.p.fill(0.0)
        tr.p[0] = mi[2]; tr.p[1] = mi[3]; tr.p[4] = mi[3]; tr.p[5] = mi[4]
        tr.p[10] = svx * svx; tr.p[15] = svz * svz
        tr.cam = d.cam
        vote(tr, d)
        tracks.add(tr)
        return tr
    }

    // ---------------- 卡爾曼 ----------------

    private fun accel(g: Int): DoubleArray = when (g) {
        G_VEH -> doubleArrayOf(1.2, 2.5)
        G_TWO -> doubleArrayOf(1.5, 2.5)
        G_PED -> doubleArrayOf(1.5, 2.0)
        G_CONE -> doubleArrayOf(0.3, 1.5)
        else -> doubleArrayOf(2.0, 2.5)
    }

    private fun predict(tr: T, dt: Double, rot: Double) {
        val s = tr.s
        val p = tr.p
        if (rot != 0.0) rotate(tr, rot)
        s[0] += s[2] * dt
        s[1] += s[3] * dt
        // P ← F P Fᵀ（F = I + dt·[位置 ← 速度]）
        for (c in 0 until 4) { p[c] += dt * p[8 + c]; p[4 + c] += dt * p[12 + c] }
        for (r in 0 until 4) { p[r * 4] += dt * p[r * 4 + 2]; p[r * 4 + 1] += dt * p[r * 4 + 3] }
        val a = accel(tr.group)
        val q4 = dt * dt * dt * dt / 4
        val q3 = dt * dt * dt / 2
        val q2 = dt * dt
        val ax = a[0] * a[0]
        val az = a[1] * a[1]
        p[0] += ax * q4; p[2] += ax * q3; p[8] += ax * q3; p[10] += ax * q2
        p[5] += az * q4; p[7] += az * q3; p[13] += az * q3; p[15] += az * q2
    }

    /** 自車轉了 a 弧度（+ = 左轉）：相對座標整組順時針轉 a。 */
    private fun rotate(tr: T, a: Double) {
        val c = cos(a)
        val sn = sin(a)
        val s = tr.s
        val x = s[0]; val z = s[1]; val vx = s[2]; val vz = s[3]
        s[0] = c * x + sn * z; s[1] = -sn * x + c * z
        s[2] = c * vx + sn * vz; s[3] = -sn * vx + c * vz
        // P ← T P Tᵀ，T = diag(M, M)
        m4.fill(0.0)
        m4[0] = c; m4[1] = sn; m4[4] = -sn; m4[5] = c
        m4[10] = c; m4[11] = sn; m4[14] = -sn; m4[15] = c
        mul(m4, tr.p, n4, false)
        mul(n4, m4, tr.p, true)
    }

    /** out = a·b（bT=true 時 out = a·bᵀ）。 */
    private fun mul(a: DoubleArray, b: DoubleArray, out: DoubleArray, bT: Boolean) {
        for (r in 0 until 4) for (c in 0 until 4) {
            var v = 0.0
            for (k in 0 until 4) v += a[r * 4 + k] * (if (bT) b[c * 4 + k] else b[k * 4 + c])
            out[r * 4 + c] = v
        }
    }

    /** 量測 H 取狀態第 i0、i1 個；h0/h1 是要從預測扣掉的量（延遲補償）。 */
    private fun kalman(
        tr: T, i0: Int, i1: Int, m0: Double, m1: Double, h0: Double, h1: Double,
        r00: Double, r01: Double, r11: Double
    ) {
        val s = tr.s
        val p = tr.p
        val y0 = m0 - (s[i0] - h0)
        val y1 = m1 - (s[i1] - h1)
        val s00 = p[i0 * 4 + i0] + r00
        val s01 = p[i0 * 4 + i1] + r01
        val s11 = p[i1 * 4 + i1] + r11
        val det = s00 * s11 - s01 * s01
        if (det <= 1e-12) return
        val a = s11 / det
        val b = -s01 / det
        val c = s00 / det
        for (r in 0 until 4) {
            val p0 = p[r * 4 + i0]
            val p1 = p[r * 4 + i1]
            kk[r * 2] = p0 * a + p1 * b
            kk[r * 2 + 1] = p0 * b + p1 * c
        }
        for (r in 0 until 4) s[r] += kk[r * 2] * y0 + kk[r * 2 + 1] * y1
        for (i in 0 until 4) { m4[i] = p[i0 * 4 + i]; m4[4 + i] = p[i1 * 4 + i] }
        for (r in 0 until 4) for (cc in 0 until 4) p[r * 4 + cc] -= kk[r * 2] * m4[cc] + kk[r * 2 + 1] * m4[4 + cc]
        for (r in 0 until 4) for (cc in r + 1 until 4) {
            val v = (p[r * 4 + cc] + p[cc * 4 + r]) / 2
            p[r * 4 + cc] = v
            p[cc * 4 + r] = v
        }
    }

    // ---------------- 生死 ----------------

    /** 到自車車身外框的距離（m）。 */
    private fun carDist(x: Double, z: Double): Double =
        hypot(max(abs(x) - EGO_HW, 0.0), max(abs(z) - EGO_HL, 0.0))

    private fun manage(now: Long) {
        for (tr in tracks) {
            if (tr.dead) continue
            val unseen = now - tr.seenMs
            val cd = carDist(tr.s[0], tr.s[1])
            val limit = when {
                !tr.confirmed -> TENTATIVE_MS
                tr.hits >= 3 && cd < GAP_RING -> COAST_GAP_MS
                cd > FAR_RING -> COAST_FAR_MS
                else -> COAST_MS
            }
            val sd = sqrt(max(tr.p[0] + tr.p[5], 0.0) / 2)
            if (unseen > limit || hypot(tr.s[0], tr.s[1]) > 40 || (tr.confirmed && sd > 6)) tr.dead = true
            // 證據很少的軌跡，鏡頭明明該看到卻連續沒看到 → 多半是兩個誤報湊成的，早點刪
            if (!tr.confirmed && tr.miss >= 2) tr.dead = true
            if (tr.confirmed && tr.hits <= 3 && tr.miss >= 3) tr.dead = true
        }
        // 同一物件長出兩條確認軌跡（例如鏡頭交接時量測跳太多）→ 留舊 id，狀態取最近更新的那條
        for (i in tracks.indices) {
            val a = tracks[i]
            if (a.dead || !a.confirmed) continue
            for (j in i + 1 until tracks.size) {
                val b = tracks[j]
                if (b.dead || !b.confirmed || b.group != a.group) continue
                val dx = abs(a.s[0] - b.s[0])
                val dz = abs(a.s[1] - b.s[1])
                // 很近，或是長車（大客車）縱向重疊一半以上
                val overlap = a.group == G_VEH && dx < 1.2 && dz < (size(a.type)[1] + size(b.type)[1]) / 4
                if (hypot(dx, dz) > mergeNear(a.group) && !overlap) continue
                if (hypot(a.s[2] - b.s[2], a.s[3] - b.s[3]) > 3.0) continue
                val keep = if (a.id < b.id) a else b
                val drop = if (keep === a) b else a
                if (drop.seenMs > keep.seenMs) {
                    drop.s.copyInto(keep.s)
                    drop.p.copyInto(keep.p)
                    keep.seenMs = drop.seenMs
                    keep.cam = drop.cam
                }
                keep.hits += drop.hits
                keep.miss = min(keep.miss, drop.miss)
                drop.dead = true
            }
        }
        tracks.removeAll { it.dead }
    }

    private fun output(now: Long, ego: Double?): List<Track> {
        val out = ArrayList<Track>(tracks.size)
        for (tr in tracks) {
            val sz = size(tr.type)
            val s = tr.s
            out.add(Track(
                id = tr.id, t = tr.type, x = s[0].toFloat(), z = s[1].toFloat(),
                vx = s[2].toFloat(), vz = s[3].toFloat(), ageMs = now - tr.bornMs, hits = tr.hits,
                confirmed = tr.confirmed, w = sz[0], l = sz[1],
                sog = ego?.let { hypot(s[2], s[3] + it).toFloat() },
                unseenMs = now - tr.seenMs, sd = sqrt(max(tr.p[0] + tr.p[5], 0.0) / 2).toFloat(), cam = tr.cam,
                svx = sqrt(max(tr.p[10], 0.0)).toFloat(), svz = sqrt(max(tr.p[15], 0.0)).toFloat()
            ))
        }
        out.sortBy { it.id }
        return out
    }

    private fun signature(list: List<Det>): Int {
        var h = list.size
        for (d in list) {
            h = h * 31 + d.t.hashCode()
            h = h * 31 + d.x.toRawBits()
            h = h * 31 + d.z.toRawBits()
            h = h * 31 + d.c.toRawBits()
        }
        return h
    }
}
