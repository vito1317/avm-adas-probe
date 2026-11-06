package com.vito.avmprobe

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 找出原廠 360 用的 3D 車模檔（.vmd，檔頭 "BAONYVMD"）並匯出到「下載/avm-adas」（檔名加 carmodel_ 開頭），
 * 給開發者解析車模形狀 → 從畫面上的車模輪廓反推原廠 3D 的角度。
 *
 * 合併版跑在原廠 360 的 process（system uid）裡，讀得到原廠自己的私有資料夾。找的地方：
 * - 原廠的資料夾（/data/data/com.baony.avm360 底下）、它的外部資料夾
 * - /sdcard/.Baios360（原廠下載的車模、設定多半在這）
 * - 整個 /sdcard 往下 [MAX_DEPTH] 層（跳過照片、影片這類大資料夾）
 * 原廠的設定檔（shared_prefs 裡的 xml、.lua 設定）也一起匯出，從裡面看得出目前選的是哪台車。
 */
object CarModelExporter {

    private const val MAX_DEPTH = 5
    private const val MAX_FILES = 40
    private val SKIP = setOf("DCIM", "Pictures", "Movies", "Music", "AVM-ADAS", "avm-adas", "Download", "Android")

    /** 回傳結果摘要（在背景執行緒呼叫，會讀檔、寫檔）。 */
    fun export(ctx: Context): String {
        val found = LinkedHashSet<File>()
        val configs = LinkedHashSet<File>()
        val dataDir = ctx.applicationInfo.dataDir?.let { File(it) }
        val roots = listOfNotNull(
            dataDir,
            ctx.getExternalFilesDir(null)?.parentFile,
            File(Environment.getExternalStorageDirectory(), ".Baios360"),
            Environment.getExternalStorageDirectory()
        )
        for (r in roots) walk(r, 0, found, configs)
        // 原廠的設定檔：看得出目前用哪台車
        dataDir?.let { d -> File(d, "shared_prefs").listFiles()?.filter { it.name.endsWith(".xml") }?.let { configs.addAll(it) } }

        val sb = StringBuilder()
        var n = 0
        for (f in found.take(MAX_FILES)) {
            try {
                MediaSaver.saveFile(ctx, "carmodel_" + f.name, "application/octet-stream", f)
                sb.append("✔ ${f.absolutePath}（${f.length() / 1024} KB）\n"); n++
            } catch (t: Throwable) { sb.append("✘ ${f.absolutePath}：$t\n") }
        }
        var nc = 0
        for (f in configs.take(MAX_FILES)) {
            try {
                MediaSaver.saveFile(ctx, "carmodel_cfg_" + f.parentFile?.name + "_" + f.name, "text/plain", f)
                nc++
            } catch (_: Throwable) {}
        }
        return if (n == 0) "找不到車模檔（.vmd）。設定檔匯出 $nc 個。\n" + sb
        else "匯出車模 $n 個、設定檔 $nc 個到「下載/avm-adas」（carmodel_ 開頭）：\n$sb"
    }

    private fun walk(dir: File, depth: Int, found: MutableSet<File>, configs: MutableSet<File>) {
        if (depth > MAX_DEPTH || found.size >= MAX_FILES) return
        val list = try { dir.listFiles() } catch (_: Throwable) { null } ?: return
        for (f in list) {
            if (f.isDirectory) {
                if (depth >= 1 && f.name in SKIP) continue
                if (f.name.startsWith(".") && depth >= 2 && !f.name.contains("Baios", ignoreCase = true)) continue
                walk(f, depth + 1, found, configs)
            } else {
                val n = f.name.lowercase()
                if (n.endsWith(".vmd") || isVmd(f)) found.add(f.canonicalFile)
                else if ((n.endsWith(".lua") || n.endsWith(".cfg") || n.endsWith(".ini")) && f.length() < 512 * 1024 &&
                    f.absolutePath.contains("Baios", ignoreCase = true)) configs.add(f)
            }
        }
    }

    /** 副檔名不是 .vmd、但檔頭是 BAONYVMD 的也算（只看 100 KB 以上的檔，免得每個小檔都開）。 */
    private fun isVmd(f: File): Boolean {
        if (f.length() < 100 * 1024) return false
        return try {
            f.inputStream().use { s -> val b = ByteArray(8); s.read(b) == 8 && String(b, Charsets.US_ASCII) == "BAONYVMD" }
        } catch (_: Throwable) { false }
    }
}
