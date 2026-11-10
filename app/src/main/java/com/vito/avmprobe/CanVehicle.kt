package com.vito.avmprobe

import android.os.SystemClock

/**
 * 車機 CAN（[CanCarInfo] 讀到的 requestCarBaseInfo 陣列）解成車況，並產生提醒。
 *
 * 欄位意義照車機「行車電腦」（com.ts.main.bmw.DrivingComputeActivity）的用法：
 * - Stalls 檔位：0 P、1 R、2/3 N、4 D、5 S、6 M。
 * - DriveSafe 駕駛安全帶：1 = 有繫、0 = 沒繫。HandBrake：1 = 拉著。EndurOil：續航 km。
 * CAN 盒不一定每個欄位都給：每個欄位「看過有作用的值」才採用（例如煞車看過 1、檔位看過非 P），
 * 一直是 0 的欄位不會拿來誤報。溫度、電壓的比例不確定，用合理範圍判斷，不合理就不用。
 */
object CanVehicle {
    data class Info(
        val gear: Char? = null, val brake: Boolean? = null, val handbrake: Boolean? = null, val beltOk: Boolean? = null,
        val highBeam: Boolean? = null, val lowBeam: Boolean? = null, val fogFront: Boolean? = null, val fogRear: Boolean? = null,
        val hazard: Boolean? = null, val rangeKm: Int? = null, val waterC: Int? = null, val oilC: Int? = null,
        val outC: Float? = null, val batV: Float? = null, val rpm: Int? = null, val instFuel: Int? = null, val avgFuel: Int? = null,
        val odoKm: Int? = null, val wiper: Int? = null, val ill: Boolean? = null
    ) {
        /** 大燈有沒有開（近光、遠光、大燈開關任一）；CAN 沒給回 null。 */
        val lightsOn: Boolean? get() = if (lowBeam == null && highBeam == null && ill == null) null else (lowBeam == true || highBeam == true || ill == true)
    }

    data class Msg(val key: String, val text: String, val level: Int)

    /** 純解碼：[seen] 記每個欄位看過的非 0 值（決定能不能用）。 */
    class Decoder(private val remembered: Set<Int> = emptySet(), private val onNewField: (Int) -> Unit = {}) {
        private val nonZero = BooleanArray(82).also { a -> for (i in remembered) if (i in a.indices) a[i] = true }
        private val seenVals = Array(82) { HashSet<Int>() }

        fun decode(a: IntArray): Info {
            for (i in 0 until minOf(a.size, 82)) {
                if (a[i] != 0 && !nonZero[i]) { nonZero[i] = true; onNewField(i) }
                if (seenVals[i].size < 8) seenVals[i].add(a[i])
            }
            fun g(i: Int) = if (i < a.size) a[i] else 0
            fun flag(i: Int): Boolean? = if (nonZero[i]) g(i) == 1 else null
            val gear = if (nonZero[24]) when (g(24)) { 0 -> 'P'; 1 -> 'R'; 2, 3 -> 'N'; 4, 5, 6 -> 'D'; else -> null } else null
            val bat = g(15).let { v -> when (v) { in 9..17 -> v.toFloat(); in 90..170 -> v / 10f; in 9000..17000 -> v / 1000f; else -> null } }
            return Info(
                gear = gear,
                brake = flag(22),
                handbrake = flag(23),
                beltOk = flag(19),
                highBeam = flag(20), lowBeam = flag(21), fogFront = flag(27), fogRear = flag(28), hazard = flag(26),
                rangeKm = g(13).takeIf { it in 1..2000 },
                waterC = g(4).takeIf { nonZero[4] && it in -30..160 },
                oilC = g(68).takeIf { nonZero[68] && it in -30..180 },
                outC = g(67).takeIf { nonZero[67] }?.let { v -> when (v) { in -40..60 -> v.toFloat(); in 0..200 -> (v - 80) / 2f; else -> null } },
                batV = bat,
                rpm = g(3).takeIf { nonZero[3] && it in 0..9000 },
                instFuel = g(12).takeIf { nonZero[12] }, avgFuel = g(11).takeIf { nonZero[11] },
                odoKm = g(14).takeIf { it in 1..2_000_000 },
                wiper = g(60).takeIf { nonZero[60] },
                ill = flag(25)
            )
        }
    }

    /** 提醒（純邏輯）：每種提醒有自己的條件；同一種由呼叫端去重。 */
    fun alerts(i: Info, speedKmh: Float?): List<Msg> {
        val v = speedKmh ?: 0f
        val out = ArrayList<Msg>()
        if (i.beltOk == false && v > 10f) out.add(Msg("belt", "駕駛沒有繫安全帶", 1))
        if (i.handbrake == true && v > 5f) out.add(Msg("hbrake", "手煞車還拉著", 2))
        i.rangeKm?.let { if (it < 50) out.add(Msg("range", "續航只剩 $it km，找加油站", 1)) }
        i.waterC?.let { if (it >= 115) out.add(Msg("water", "水溫過高（$it°C），靠邊停車", 2)) }
        val rpm = i.rpm ?: 0
        i.batV?.let { if (rpm > 500 && it < 12.3f) out.add(Msg("bat", "引擎發動中電壓只有 %.1f V，充電系統可能有問題".format(it), 1)) }
        i.outC?.let { if (it <= 3f && v > 5f) out.add(Msg("ice", "外面 %.0f°C，路面可能結冰".format(it), 0)) }
        if (i.highBeam == true && v > 5f && i.lowBeam != null) out.add(Msg("hibeam", "遠光燈開著", 0))
        return out
    }

    private var decoder = Decoder()
    private var prefs: android.content.SharedPreferences? = null

    /**
     * 看過有值的欄位永久記住（實車：全程沒繫安全帶，安全帶欄位一直是 0，從來沒被採用 → 不會提醒）。
     * 繫過一次安全帶、踩過一次煞車，之後每次開車都會用。
     */
    fun init(ctx: android.content.Context) {
        if (prefs != null) return
        val sp = ctx.applicationContext.getSharedPreferences("can_vehicle", android.content.Context.MODE_PRIVATE)
        prefs = sp
        val known = (sp.getString("fields", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }.toSet()
        decoder = Decoder(known) { i ->
            val cur = (sp.getString("fields", "") ?: "").split(',').filter { it.isNotEmpty() }.toMutableSet()
            if (cur.add(i.toString())) sp.edit().putString("fields", cur.joinToString(",")).apply()
        }
    }
    @Volatile var info = Info(); private set
    @Volatile private var at = 0L

    /** [CanCarInfo] 每次讀到就呼叫。 */
    fun onArray(a: IntArray) {
        info = decoder.decode(a); at = SystemClock.elapsedRealtime()
        // 檔位：CAN 有給就用它（比「倒車畫面開著」準，也分得出 P／N／D）
        info.gear?.let { VehicleBus.signals.reportGear(it) }
    }

    fun fresh(): Boolean = SystemClock.elapsedRealtime() - at < 1500
    fun gearFresh(): Boolean = fresh() && info.gear != null
    /** 真的煞車訊號（CAN 有給才有，否則 null）。 */
    fun brake(): Boolean? = if (fresh()) info.brake else null

    /** 給畫面／設定看的一行車況。 */
    fun summary(): String {
        val i = info
        if (!fresh()) return "沒有車機 CAN 車況"
        val p = ArrayList<String>()
        i.gear?.let { p.add("檔位 $it") }
        i.brake?.let { p.add(if (it) "踩煞車" else "沒踩煞車") }
        i.handbrake?.let { p.add(if (it) "手煞車拉著" else "手煞車放開") }
        i.beltOk?.let { p.add(if (it) "安全帶有繫" else "安全帶沒繫") }
        i.rpm?.let { p.add("轉速 $it") }
        i.waterC?.let { p.add("水溫 $it°C") }
        i.oilC?.let { p.add("機油 $it°C") }
        i.outC?.let { p.add("車外 %.0f°C".format(it)) }
        i.batV?.let { p.add("電壓 %.1f V".format(it)) }
        i.rangeKm?.let { p.add("續航 $it km") }
        i.instFuel?.let { p.add("瞬間油耗 $it") }
        i.avgFuel?.let { p.add("平均油耗 $it") }
        i.odoKm?.let { p.add("里程 $it km") }
        if (i.highBeam == true) p.add("遠光")
        if (i.lowBeam == true) p.add("近光")
        if (i.fogFront == true) p.add("前霧燈")
        if (i.fogRear == true) p.add("後霧燈")
        i.wiper?.let { if (it > 0) p.add("雨刷 $it") }
        return if (p.isEmpty()) "CAN 有連上，但還沒看到可用的車況欄位" else p.joinToString("、")
    }
}
