package com.vito.avmprobe

import org.json.JSONObject

/**
 * 原廠 360 某一個視角（BV_state，例如 BV_LEFT、BV_REAR_3D）上要怎麼標注：
 * - [top]：畫面裡有俯視圖那一格 → 俯視圖對位（null = 這個視角沒有俯視圖，不畫）
 * - [p3]：畫面裡有 3D／鏡頭那一格 → 透視對位（null = 不畫）
 *
 * 原廠切到哪個視角，就套用那個視角存的設定（[AppPrefs.viewProfile]），每個視角對位一次就好。
 */
data class ViewProfile(val top: AnnotationMapper.Params?, val p3: PerspectiveMapper.Params?) {

    fun toJson(): JSONObject = JSONObject().apply {
        top?.let { t ->
            put("top", JSONObject().put("l", t.clipL.toDouble()).put("t", t.clipT.toDouble())
                .put("r", t.clipR.toDouble()).put("b", t.clipB.toDouble())
                .put("cx", t.cx.toDouble()).put("cy", t.cy.toDouble()).put("s", t.scale.toDouble()).put("f", t.flip))
        }
        p3?.let { p ->
            put("p3", JSONObject().put("l", p.clipL.toDouble()).put("t", p.clipT.toDouble())
                .put("r", p.clipR.toDouble()).put("b", p.clipB.toDouble())
                .put("az", p.azDeg.toDouble()).put("el", p.elDeg.toDouble()).put("d", p.dist.toDouble())
                .put("fov", p.fovDeg.toDouble()).put("tx", p.tx.toDouble()).put("tz", p.tz.toDouble()))
        }
    }

    companion object {
        fun fromJson(j: JSONObject): ViewProfile {
            val t = j.optJSONObject("top")?.let {
                AnnotationMapper.Params(
                    clipL = it.optDouble("l").toFloat(), clipT = it.optDouble("t").toFloat(),
                    clipR = it.optDouble("r").toFloat(), clipB = it.optDouble("b").toFloat(),
                    cx = it.optDouble("cx").toFloat(), cy = it.optDouble("cy").toFloat(),
                    scale = it.optDouble("s").toFloat(), flip = it.optBoolean("f")
                )
            }
            val p = j.optJSONObject("p3")?.let {
                PerspectiveMapper.Params(
                    clipL = it.optDouble("l").toFloat(), clipT = it.optDouble("t").toFloat(),
                    clipR = it.optDouble("r").toFloat(), clipB = it.optDouble("b").toFloat(),
                    azDeg = it.optDouble("az").toFloat(), elDeg = it.optDouble("el").toFloat(),
                    dist = it.optDouble("d").toFloat(), fovDeg = it.optDouble("fov").toFloat(),
                    tx = it.optDouble("tx").toFloat(), tz = it.optDouble("tz").toFloat()
                )
            }
            return ViewProfile(t, p)
        }

        /**
         * 還沒對位過的視角給的初值。俯視圖沿用全域對位；名稱看得出是左／右側 3D 的，
         * 給打方向燈那組 3D 初值（照片估的，[PerspectiveMapper.Params.DEFAULT_LEFT]）。
         */
        fun defaultFor(key: String, globalTop: AnnotationMapper.Params): ViewProfile {
            val k = key.uppercase()
            val p3 = when {
                "LEFT" in k -> PerspectiveMapper.Params.DEFAULT_LEFT
                "RIGHT" in k -> PerspectiveMapper.Params.DEFAULT_RIGHT
                k == TURN_L -> PerspectiveMapper.Params.DEFAULT_LEFT
                k == TURN_R -> PerspectiveMapper.Params.DEFAULT_RIGHT
                else -> null
            }
            return ViewProfile(globalTop, p3)
        }

        /** 左右成對的視角名稱（BV_LEFT ↔ BV_RIGHT），沒有對應回 null。 */
        fun counterpart(key: String): String? = when {
            "LEFT" in key -> key.replace("LEFT", "RIGHT")
            "RIGHT" in key -> key.replace("RIGHT", "LEFT")
            key == TURN_L -> TURN_R
            key == TURN_R -> TURN_L
            else -> null
        }

        /** 讀不到原廠視角時，用方向燈當視角（打燈原廠會切側邊 3D）。 */
        const val TURN_L = "TURN_L"
        const val TURN_R = "TURN_R"
        const val DEFAULT = "DEFAULT"
    }
}
