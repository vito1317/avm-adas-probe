package com.vito.avmprobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 疊在四分割鏡頭畫面上：每個象限的名稱、偵測框、左上角狀態。
 * 框的座標是「整幀像素」，依目前顯示（四分割或放大單一象限）換算到 view。
 */
class QuadOverlayView(context: Context) : View(context) {

    class Box(val rect: RectF, val text: String, val warn: Boolean)

    private val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.parseColor("#38E1FF") }
    private val warnPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.parseColor("#FF5A4D") }
    private val tagBg = Paint().apply { color = Color.parseColor("#CC0B1118") }
    private val tagText = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true; isFakeBoldText = true }
    private val quadText = Paint().apply { color = Color.parseColor("#FFC24B"); textSize = 24f; isAntiAlias = true; isFakeBoldText = true }
    private val gridPaint = Paint().apply { color = Color.parseColor("#66000000"); strokeWidth = 2f }
    private val statText = Paint().apply { color = Color.parseColor("#5EF08A"); textSize = 22f; isAntiAlias = true }

    @Volatile private var boxes: List<Box> = emptyList()
    @Volatile private var status: String = ""
    @Volatile var frameW = 1920
    @Volatile var frameH = 1080
    /** -1 = 四分割；0..3 = 放大該象限。 */
    @Volatile var zoomQuad = -1
    var quadNames = arrayOf("前", "後", "左", "右")

    fun update(b: List<Box>, statusText: String) {
        boxes = b
        status = statusText
        postInvalidate()
    }

    /** 整幀座標 → view 座標。 */
    private fun mapX(x: Float): Float {
        val z = zoomQuad
        return if (z < 0) x * width / frameW else (x - (z % 2) * frameW / 2f) * width / (frameW / 2f)
    }

    private fun mapY(y: Float): Float {
        val z = zoomQuad
        return if (z < 0) y * height / frameH else (y - (z / 2) * frameH / 2f) * height / (frameH / 2f)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (zoomQuad < 0) {
            c.drawLine(w / 2, 0f, w / 2, h, gridPaint)
            c.drawLine(0f, h / 2, w, h / 2, gridPaint)
            for (q in 0..3) c.drawText(quadNames[q], (q % 2) * w / 2 + 10f, (q / 2) * h / 2 + 30f, quadText)
        } else {
            c.drawText(quadNames[zoomQuad] + "（點一下回四分割）", 10f, 30f, quadText)
        }
        c.save()
        c.clipRect(0f, 0f, w, h)
        for (b in boxes) {
            val r = RectF(mapX(b.rect.left), mapY(b.rect.top), mapX(b.rect.right), mapY(b.rect.bottom))
            if (r.right < 0 || r.left > w || r.bottom < 0 || r.top > h) continue
            c.drawRect(r, if (b.warn) warnPaint else boxPaint)
            val tw = tagText.measureText(b.text)
            val top = (r.top - 28f).coerceAtLeast(0f)
            c.drawRect(r.left, top, r.left + tw + 10f, top + 28f, tagBg)
            c.drawText(b.text, r.left + 5f, top + 21f, tagText)
        }
        c.restore()
        if (status.isNotEmpty()) {
            var y = h - 12f - 26f * (status.count { it == '\n' })
            for (line in status.split("\n")) {
                val tw = statText.measureText(line)
                c.drawRect(6f, y - 22f, 16f + tw, y + 6f, tagBg)
                c.drawText(line, 11f, y, statText)
                y += 26f
            }
        }
    }
}
