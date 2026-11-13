package com.vito.avmprobe

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * 俯視的自車（BMW E60 輪廓）直接用向量畫：行車記錄器影片、俯視畫面用。
 * 以前是從原廠俯視圖截一塊下來貼，邊緣帶到地面與陰影、比例也怪。
 *
 * 座標：中心 (cx, cy)，車頭朝上，[pm] = 每公尺幾像素。車長 4.84 m、寬 1.85 m（不含後照鏡）。
 * 燈跟車況連動：踩煞車尾燈全亮、打方向燈四角琥珀色閃（[blinkOn] 由呼叫端給，跟原車閃的節奏一樣即可）。
 */
object EgoTopCar {
    const val LEN = 4.84f
    const val WID = 1.85f

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    fun draw(c: Canvas, cx: Float, cy: Float, pm: Float, braking: Boolean, turn: String?, blinkOn: Boolean) {
        val hw = WID / 2 * pm; val hl = LEN / 2 * pm
        fun X(x: Float) = cx + x * pm          // x：公尺，+ = 右
        fun Y(z: Float) = cy - z * pm          // z：公尺，+ = 前

        // 陰影
        fill.shader = null; fill.color = Color.argb(110, 0, 0, 0)
        c.drawRoundRect(RectF(cx - hw - 0.12f * pm, cy - hl - 0.10f * pm, cx + hw + 0.12f * pm, cy + hl + 0.14f * pm), 0.5f * pm, 0.5f * pm, fill)

        // 車身：前後收窄的圓角輪廓
        path.reset()
        val w = WID / 2; val l = LEN / 2
        path.moveTo(X(-w * 0.80f), Y(l))
        path.cubicTo(X(-w * 0.98f), Y(l), X(-w), Y(l - 0.25f), X(-w), Y(l - 0.55f))
        path.lineTo(X(-w), Y(-l + 0.45f))
        path.cubicTo(X(-w), Y(-l + 0.12f), X(-w * 0.95f), Y(-l), X(-w * 0.78f), Y(-l))
        path.lineTo(X(w * 0.78f), Y(-l))
        path.cubicTo(X(w * 0.95f), Y(-l), X(w), Y(-l + 0.12f), X(w), Y(-l + 0.45f))
        path.lineTo(X(w), Y(l - 0.55f))
        path.cubicTo(X(w), Y(l - 0.25f), X(w * 0.98f), Y(l), X(w * 0.80f), Y(l))
        path.close()
        body.shader = LinearGradient(cx - hw, 0f, cx + hw, 0f,
            intArrayOf(Color.rgb(38, 42, 50), Color.rgb(92, 100, 114), Color.rgb(38, 42, 50)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, body)
        line.shader = null; line.color = Color.argb(160, 200, 210, 225); line.strokeWidth = (0.025f * pm).coerceAtLeast(1f)
        c.drawPath(path, line)

        // 後照鏡
        fill.color = Color.rgb(48, 52, 60)
        c.drawOval(RectF(X(-w - 0.16f), Y(0.95f), X(-w + 0.02f), Y(0.72f)), fill)
        c.drawOval(RectF(X(w - 0.02f), Y(0.95f), X(w + 0.16f), Y(0.72f)), fill)

        // 前擋、車頂、後擋
        glass(c, X(-w * 0.80f), Y(1.02f), X(w * 0.80f), Y(1.02f), X(w * 0.70f), Y(0.38f), X(-w * 0.70f), Y(0.38f))
        fill.shader = LinearGradient(cx - hw, 0f, cx + hw, 0f,
            intArrayOf(Color.rgb(30, 33, 40), Color.rgb(70, 77, 90), Color.rgb(30, 33, 40)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(X(-w * 0.70f), Y(0.38f), X(w * 0.70f), Y(-0.95f)), 0.12f * pm, 0.12f * pm, fill)
        fill.shader = null
        glass(c, X(-w * 0.70f), Y(-0.95f), X(w * 0.70f), Y(-0.95f), X(w * 0.76f), Y(-1.42f), X(-w * 0.76f), Y(-1.42f))
        // 引擎蓋的兩條稜線
        line.color = Color.argb(70, 210, 220, 235)
        c.drawLine(X(-w * 0.45f), Y(l - 0.15f), X(-w * 0.55f), Y(1.08f), line)
        c.drawLine(X(w * 0.45f), Y(l - 0.15f), X(w * 0.55f), Y(1.08f), line)

        // 大燈（白）、尾燈（紅：平常暗、煞車亮）
        fill.color = Color.rgb(225, 235, 245)
        c.drawRoundRect(RectF(X(-w * 0.92f), Y(l - 0.02f), X(-w * 0.55f), Y(l - 0.16f)), 0.05f * pm, 0.05f * pm, fill)
        c.drawRoundRect(RectF(X(w * 0.55f), Y(l - 0.02f), X(w * 0.92f), Y(l - 0.16f)), 0.05f * pm, 0.05f * pm, fill)
        fill.color = if (braking) Color.rgb(255, 40, 40) else Color.rgb(120, 20, 24)
        c.drawRoundRect(RectF(X(-w * 0.94f), Y(-l + 0.16f), X(-w * 0.50f), Y(-l + 0.02f)), 0.05f * pm, 0.05f * pm, fill)
        c.drawRoundRect(RectF(X(w * 0.50f), Y(-l + 0.16f), X(w * 0.94f), Y(-l + 0.02f)), 0.05f * pm, 0.05f * pm, fill)
        if (braking) {
            // 光暈＋第三煞車燈
            fill.color = Color.argb(90, 255, 30, 30)
            c.drawRoundRect(RectF(X(-w), Y(-l + 0.30f), X(w), Y(-l - 0.25f)), 0.25f * pm, 0.25f * pm, fill)
            fill.color = Color.rgb(255, 60, 60)
            c.drawRect(X(-w * 0.25f), Y(-1.40f), X(w * 0.25f), Y(-1.46f), fill)
        }
        // 方向燈（四角琥珀色）
        val left = turn == "L" || turn == "H"; val right = turn == "R" || turn == "H"
        if (blinkOn && (left || right)) {
            fill.color = Color.rgb(255, 170, 20)
            val r = 0.11f * pm
            if (left) { c.drawCircle(X(-w * 0.92f), Y(l - 0.22f), r, fill); c.drawCircle(X(-w * 0.95f), Y(-l + 0.22f), r, fill) }
            if (right) { c.drawCircle(X(w * 0.92f), Y(l - 0.22f), r, fill); c.drawCircle(X(w * 0.95f), Y(-l + 0.22f), r, fill) }
            fill.color = Color.argb(80, 255, 170, 20)
            if (left) { c.drawCircle(X(-w * 0.92f), Y(l - 0.22f), r * 2.6f, fill); c.drawCircle(X(-w * 0.95f), Y(-l + 0.22f), r * 2.6f, fill) }
            if (right) { c.drawCircle(X(w * 0.92f), Y(l - 0.22f), r * 2.6f, fill); c.drawCircle(X(w * 0.95f), Y(-l + 0.22f), r * 2.6f, fill) }
        }
    }

    private fun glass(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.lineTo(x4, y4); path.close()
        fill.shader = LinearGradient(x1, y1, x4, y4, Color.rgb(70, 95, 125), Color.rgb(22, 30, 42), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
    }
}
