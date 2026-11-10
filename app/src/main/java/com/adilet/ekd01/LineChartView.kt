package com.adilet.ekd01

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max

/** A small line chart: values against time (epoch ms on x), with min/mid/max labels on the left and start/end time below. */
class LineChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var xs: List<Long> = emptyList()
    private var ys: List<Double> = emptyList()

    private val density = resources.displayMetrics.density
    private val lineColor = context.getColor(R.color.amber)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor; style = Paint.Style.FILL }
    private val gridPaint = Paint().apply { color = context.getColor(R.color.card_border); strokeWidth = density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_hint)
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)

    fun setData(x: List<Long>, y: List<Double>) {
        xs = x
        ys = y
        invalidate()
    }

    private fun label(v: Double, range: Double) = if (range >= 10) "%.0f".format(v) else "%.1f".format(v)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ys.isEmpty()) {
            val msg = context.getString(R.string.chart_no_data)
            canvas.drawText(msg, (width - textPaint.measureText(msg)) / 2, height / 2f, textPaint)
            return
        }

        var yMin = ys.minOrNull()!!
        var yMax = ys.maxOrNull()!!
        if (yMax - yMin < 1e-9) { // flat line: give it some room so it sits in the middle
            val pad = max(abs(yMin) * 0.05, 1.0)
            yMin -= pad
            yMax += pad
        }
        val range = yMax - yMin
        val xMin = xs.first()
        val xMax = xs.last()
        val xSpan = max(xMax - xMin, 1L).toDouble()

        val labels = listOf(yMax, (yMax + yMin) / 2, yMin).map { label(it, range) }
        val left = labels.maxOf { textPaint.measureText(it) } + 6 * density
        val right = width - 8 * density
        val top = 8 * density
        val bottom = height - 20 * density
        val plotW = right - left
        val plotH = bottom - top

        // Grid + y labels
        for (i in 0..2) {
            val y = top + plotH * i / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
            val ty = when (i) { 0 -> y + textPaint.textSize * 0.8f; 2 -> y; else -> y + textPaint.textSize * 0.35f }
            canvas.drawText(labels[i], 0f, ty, textPaint)
        }

        // Time labels
        val startText = timeFormat.format(Date(xMin))
        val endText = timeFormat.format(Date(xMax))
        val ty = height - 4 * density
        canvas.drawText(startText, left, ty, textPaint)
        canvas.drawText(endText, right - textPaint.measureText(endText), ty, textPaint)

        fun px(i: Int) = (left + plotW * ((xs[i] - xMin) / xSpan)).toFloat()
        fun py(i: Int) = (top + plotH * (1 - (ys[i] - yMin) / range)).toFloat()

        if (ys.size == 1) {
            canvas.drawCircle(left + plotW / 2, py(0), 3 * density, dotPaint)
            return
        }
        val path = Path()
        path.moveTo(px(0), py(0))
        for (i in 1 until ys.size) path.lineTo(px(i), py(i))
        canvas.drawPath(path, linePaint)
        if (ys.size <= 60) for (i in ys.indices) canvas.drawCircle(px(i), py(i), 2.5f * density, dotPaint)
    }
}
