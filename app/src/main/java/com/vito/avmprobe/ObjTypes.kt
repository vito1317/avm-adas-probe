package com.vito.avmprobe

import org.json.JSONArray
import org.json.JSONObject

/** 停車格 → 3D 面板 JSON：p 為 8 個公尺座標（入口邊在前），k 種類，o 是否有車。flip 時整組轉 180°。 */
fun slotsJson(slots: List<ParkingSlot>, flip: Boolean): JSONArray {
    val s = if (flip) -1f else 1f
    val arr = JSONArray()
    for (sl in slots) {
        val p = JSONArray()
        for (v in sl.p) p.put(Math.round(v * s * 100f) / 100.0)
        arr.put(JSONObject().put("p", p).put("k", sl.kind.toString())
            .put("o", if (sl.occupied) 1 else 0).put("c", Math.round(sl.conf * 100f) / 100.0))
    }
    return arr
}

/**
 * 模型類別名稱 → 3D 面板物件種類。用名稱對應（不寫死 COCO 編號），換模型只要附上 labels 檔。
 * 種類：car 轎車、van 箱型車、truck 卡車、bus 公車、moto 機車、bigbike 重機、bike 腳踏車、
 * ped 行人、cone 三角錐、light 紅綠燈（另外處理，不當障礙物）。
 */
object ObjTypes {
    const val LIGHT = "light"

    /** 物件約略半寬（m）：偵測點是物件最靠近鏡頭的一側，中心再往外推這麼多。須與 3D 面板一致。 */
    fun radius(t: String): Float = when (t) {
        "truck", "bus" -> 1.2f
        "van" -> 1.0f
        "car" -> 0.9f
        "moto", "bigbike", "bike" -> 0.4f
        "ped" -> 0.3f
        "cone" -> 0.2f
        else -> 0.5f
    }

    /**
     * hasScooter：模型自己分得出速克達時，motorcycle 才算「重機」；
     * COCO 的 motorcycle 涵蓋台灣的機車，就當機車。
     */
    fun typeOf(label: String, hasScooter: Boolean): String? = when (label.trim().lowercase()) {
        "person", "pedestrian" -> "ped"
        "bicycle" -> "bike"
        "car", "sedan", "suv", "taxi", "sports car", "sedan car" -> "car"
        "van", "minibus", "minivan" -> "van"
        "truck", "heavy truck", "pickup truck", "pickup" -> "truck"
        "bus" -> "bus"
        "scooter", "moped" -> "moto"
        "motorcycle", "motorbike", "heavy motorcycle" -> if (hasScooter) "bigbike" else "moto"
        "traffic cone" -> "cone"
        "traffic light" -> LIGHT
        else -> null
    }
}
