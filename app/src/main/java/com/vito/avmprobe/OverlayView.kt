package com.vito.avmprobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 全螢幕透明懸浮窗：偵測框、俯視區(ROI)校正框、右上角統計。
 * 座標皆為螢幕像素。統計放右上角、ROI 框畫在區域外緣 —— 避免被截圖截進偵測區。
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
    private val roiPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FFC24B")
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val statsBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#AA000000")
    }
    private val statsTextPaint = Paint().apply {
        color = Color.parseColor("#00FF88")
        textSize = 30f
        isFakeBoldText = true
        isAntiAlias = true
    }

    @Volatile private var dets: List<Pair<RectF, String>> = emptyList()
    @Volatile private var stats: String = "等待第一幀…"
    @Volatile private var roi: RectF? = null

    fun update(boxes: List<Pair<RectF, String>>, statsLine: String, roiRect: RectF? = null) {
        dets = boxes
        stats = statsLine
        roi = roiRect
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        roi?.let {
            val o = 4f   // 畫在 ROI 外緣，不進偵測/地面貼圖範圍
            canvas.drawRect(it.left - o, it.top - o, it.right + o, it.bottom + o, roiPaint)
        }

        for ((rect, text) in dets) {
            canvas.drawRect(rect, boxPaint)
            val tw = labelTextPaint.measureText(text)
            val top = (rect.top - 36f).coerceAtLeast(0f)
            canvas.drawRect(rect.left, top, rect.left + tw + 12f, top + 36f, labelBgPaint)
            canvas.drawText(text, rect.left + 6f, top + 28f, labelTextPaint)
        }

        if (stats.isEmpty()) return
        val lines = stats.split("\n")
        val pad = 14f
        val lineH = 38f
        var maxW = 0f
        for (l in lines) maxW = maxOf(maxW, statsTextPaint.measureText(l))
        val boxW = maxW + pad * 2
        val left = width - boxW
        canvas.drawRect(left, 0f, width.toFloat(), pad * 2 + lineH * lines.size, statsBgPaint)
        var y = pad + 30f
        for (l in lines) {
            canvas.drawText(l, left + pad, y, statsTextPaint)
            y += lineH
        }
    }
}
