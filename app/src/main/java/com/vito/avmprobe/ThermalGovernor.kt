package com.vito.avmprobe

import android.os.SystemClock
import java.io.File

/**
 * 依 CPU 溫度降速（實車：車機一直重開，開機原因 thermal_reboot，CPU 105°C、電源晶片 100°C；
 * 開機一分鐘 360 吃 2.3 顆核心＋中控台 3D 一起跑）。
 *
 * 每 [READ_MS] 讀一次 MTK 的 mtktscpu 溫度（/sys/class/thermal），分四級：
 * - 0 正常（< 80°C）：照原本跑。
 * - 1 溫（80～90）：兩幀之間至少 [minIntervalMs]（約每秒 5 次），車道線模型隔一次才跑。
 * - 2 熱（90～98）：每秒約 2.5 次，關車道線模型、大範圍停車格。
 * - 3 過熱（≥ 98）：每秒不到 2 次，只留最基本的偵測。
 * 降回來要比門檻低 3°C 才升級（避免在門檻上來回跳）。
 */
object ThermalGovernor {
    private const val READ_MS = 2000L
    private val UP = intArrayOf(80, 90, 98)          // 進入第 1、2、3 級的溫度
    private const val HYST = 3

    @Volatile var level = 0; private set
    @Volatile var tempC = -1; private set
    private var zone: File? = null
    private var readAt = 0L
    private var searched = false

    /** 這一級兩幀之間至少隔幾毫秒（0 = 不限）。 */
    val minIntervalMs: Long get() = when (level) { 0 -> 80L; 1 -> 200L; 2 -> 400L; else -> 600L }   // 正常也限每秒約 12 次（以前不限，一開機就 2 顆核心全速）
    /** 車道線模型這一級還跑不跑；1 級隔一次跑。 */
    fun laneModelAllowed(counter: Long): Boolean = when (level) { 0 -> true; 1 -> counter % 2L == 0L; else -> false }
    /** 大範圍停車格俯視圖（多一份取樣與偵測）。 */
    val farSlotsAllowed: Boolean get() = level < 2

    fun poll() {
        val now = SystemClock.elapsedRealtime()
        if (now - readAt < READ_MS) return
        readAt = now
        val z = zone ?: findZone() ?: return
        val t = try { z.readText().trim().toInt() / 1000 } catch (_: Throwable) { return }
        tempC = t
        var lv = level
        while (lv < 3 && t >= UP[lv]) lv++
        while (lv > 0 && t < UP[lv - 1] - HYST) lv--
        if (lv != level) { level = lv; Engine360.log("CPU ${t}°C → 降速等級 $lv") }
    }

    private fun findZone(): File? {
        if (searched) return null
        searched = true
        val base = File("/sys/class/thermal")
        val zs = base.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        val cpu = zs.firstOrNull { try { File(it, "type").readText().trim() == "mtktscpu" } catch (_: Throwable) { false } }
            ?: zs.firstOrNull { try { File(it, "type").readText().trim().contains("cpu", true) } catch (_: Throwable) { false } }
        zone = cpu?.let { File(it, "temp") }
        return zone
    }

    /** 設定頁顯示。 */
    fun status(): String = if (tempC < 0) "讀不到 CPU 溫度" else
        "CPU ${tempC}°C・" + arrayOf("正常", "溫（偵測減半）", "熱（再減半、關車道線模型）", "過熱（只留基本偵測）")[level]
}
