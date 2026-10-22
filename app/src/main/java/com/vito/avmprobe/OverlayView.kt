package com.vito.avmprobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 全螢幕透明懸浮窗，畫偵測框 + 左上角統計資訊。
 * 偵測框座標已由 Service 轉成「螢幕像素」空間。
 */
class OverlayView(context: Context) : View(context) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#00E5FF")
    }
    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC00E5FF")
    }
    private val labelTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = 30f
        isFakeBoldText = true
        isAntiAlias = true
    }
    private val statsBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#AA000000")
    }
    private val statsTextPaint = Paint().apply {
        color = Color.parseColor("#00FF88")
        textSize = 34f
        isFakeBoldText = true
        isAntiAlias = true
    }

    @Volatile private var dets: List<Pair<RectF, String>> = emptyList()
    @Volatile private var stats: String = "等待第一幀…"

    fun update(boxes: List<Pair<RectF, String>>, statsLine: String) {
        dets = boxes
        stats = statsLine
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for ((rect, text) in dets) {
            canvas.drawRect(rect, boxPaint)
            val tw = labelTextPaint.measureText(text)
            val top = (rect.top - 36f).coerceAtLeast(0f)
            canvas.drawRect(rect.left, top, rect.left + tw + 12f, top + 36f, labelBgPaint)
            canvas.drawText(text, rect.left + 6f, top + 28f, labelTextPaint)
        }

        // 左上角統計條
        val lines = stats.split("\n")
        val pad = 16f
        val lineH = 42f
        val boxH = pad * 2 + lineH * lines.size
        var maxW = 0f
        for (l in lines) maxW = maxOf(maxW, statsTextPaint.measureText(l))
        canvas.drawRect(0f, 0f, maxW + pad * 2, boxH, statsBgPaint)
        var y = pad + 34f
        for (l in lines) {
            canvas.drawText(l, pad, y, statsTextPaint)
            y += lineH
        }
    }
}
