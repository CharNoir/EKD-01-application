package com.char_noir.ekd01

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.ceil
import kotlin.math.max

/** A day strip: one coloured block per ride on a time axis (whole hours around the first and last ride). */
class TimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    class Segment(val start: Long, val end: Long, val color: Int)

    private var segments: List<Segment> = emptyList()
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface_deep) }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_hint)
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val rect = RectF()

    fun setData(data: List<Segment>) {
        segments = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty()) return
        val hour = 3_600_000L
        val from = Calendar.getInstance().apply {
            timeInMillis = segments.minOf { it.start }
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var to = from + ceil((segments.maxOf { it.end } - from) / hour.toDouble()).toLong() * hour
        if (to - from < 2 * hour) to = from + 2 * hour
        val span = (to - from).toDouble()
        val w = width.toFloat()
        val trackH = 24 * density
        val r = 8 * density

        rect.set(0f, 0f, w, trackH)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        for (s in segments) {
            val x1 = (w * (s.start - from) / span).toFloat()
            val x2 = max(x1 + 4 * density, (w * (s.end - from) / span).toFloat())
            segPaint.color = s.color
            rect.set(x1, 3 * density, x2, trackH - 3 * density)
            canvas.drawRoundRect(rect, 4 * density, 4 * density, segPaint)
        }

        val hours = ((to - from) / hour).toInt()
        val step = max(1, ceil(hours / 4.0).toInt())
        val y = trackH + 16 * density
        var i = 0
        while (i <= hours) {
            val text = timeFormat.format(Date(from + i * hour))
            val x = (w * i / hours.toFloat())
            val tw = textPaint.measureText(text)
            val tx = when (i) { 0 -> 0f; else -> (x - tw / 2).coerceAtMost(w - tw) }
            canvas.drawText(text, tx, y, textPaint)
            i += step
        }
    }
}
