package com.example.mavlinktest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class SensorLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Sample(val index: Float, val values: FloatArray)

    private val channelColors = intArrayOf(
        Color.rgb(231, 76, 60),
        Color.rgb(52, 152, 219),
        Color.rgb(46, 204, 113),
        Color.rgb(243, 156, 18),
        Color.rgb(155, 89, 182),
        Color.rgb(26, 188, 156)
    )
    private val channelVisible = BooleanArray(6) { true }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(42, 58, 88)
        strokeWidth = 1f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 135, 170)
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val path = Path()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (samples.size < 2) return false
            val focusRatio = ((detector.focusX - plotLeft) / plotWidth).coerceIn(0f, 1f)
            val focusIndex = viewportStart + viewportSize * focusRatio
            val newSize = (viewportSize / detector.scaleFactor).coerceIn(minViewportSize(), samples.size.toFloat())
            viewportStart = (focusIndex - newSize * focusRatio).coerceIn(0f, samples.size - newSize)
            viewportSize = newSize
            notifyViewportChanged()
            invalidate()
            return true
        }
    })

    private var samples: List<Sample> = emptyList()
    private var viewportStart = 0f
    private var viewportSize = 0f
    private var lastTouchX = 0f
    private var plotLeft = 60f
    private var plotTop = 28f
    private var plotRight = 0f
    private var plotBottom = 0f
    private val plotWidth: Float
        get() = max(1f, plotRight - plotLeft)
    private val plotHeight: Float
        get() = max(1f, plotBottom - plotTop)

    var onViewportChanged: ((Float) -> Unit)? = null

    fun setSamples(nextSamples: List<Sample>) {
        samples = nextSamples
        viewportStart = 0f
        viewportSize = samples.size.toFloat()
        notifyViewportChanged()
        invalidate()
    }

    fun setChannelVisible(channel: Int, visible: Boolean) {
        if (channel !in channelVisible.indices) return
        channelVisible[channel] = visible
        invalidate()
    }

    fun setPanRatio(ratio: Float) {
        if (samples.isEmpty()) return
        val maxStart = (samples.size - viewportSize).coerceAtLeast(0f)
        viewportStart = (ratio.coerceIn(0f, 1f) * maxStart).coerceIn(0f, maxStart)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(5, 10, 26))
        plotRight = width - 18f
        plotBottom = height - 42f

        drawGrid(canvas)
        if (samples.isEmpty()) {
            canvas.drawText("等待数据", plotLeft + 20f, plotTop + 50f, textPaint)
            return
        }

        val start = floor(viewportStart).toInt().coerceIn(0, samples.lastIndex)
        val end = ceil(viewportStart + viewportSize).toInt().coerceIn(start + 1, samples.size)
        val visibleSamples = samples.subList(start, end)
        val yRange = findYRange(visibleSamples)
        drawLines(canvas, visibleSamples, yRange.first, yRange.second)
        drawLabels(canvas, visibleSamples, yRange.first, yRange.second)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && samples.size > viewportSize) {
                    val dx = event.x - lastTouchX
                    val deltaIndex = -dx / plotWidth * viewportSize
                    viewportStart = (viewportStart + deltaIndex).coerceIn(0f, samples.size - viewportSize)
                    notifyViewportChanged()
                    invalidate()
                }
                lastTouchX = event.x
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawGrid(canvas: Canvas) {
        for (i in 0..4) {
            val y = plotTop + plotHeight * i / 4f
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
        }
        for (i in 0..5) {
            val x = plotLeft + plotWidth * i / 5f
            canvas.drawLine(x, plotTop, x, plotBottom, gridPaint)
        }
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisPaint)
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisPaint)
    }

    private fun drawLines(canvas: Canvas, visibleSamples: List<Sample>, yMin: Float, yMax: Float) {
        val step = max(1, ceil(visibleSamples.size / max(1f, plotWidth)).toInt())
        val ySpan = max(1f, yMax - yMin)
        for (channel in 0 until 6) {
            if (!channelVisible[channel]) continue
            linePaint.color = channelColors[channel]
            path.reset()
            var hasPoint = false
            for (i in visibleSamples.indices step step) {
                val sample = visibleSamples[i]
                if (channel >= sample.values.size) continue
                val xRatio = if (visibleSamples.size == 1) 0f else i.toFloat() / (visibleSamples.size - 1)
                val yRatio = (sample.values[channel] - yMin) / ySpan
                val x = plotLeft + xRatio * plotWidth
                val y = plotBottom - yRatio * plotHeight
                if (!hasPoint) {
                    path.moveTo(x, y)
                    hasPoint = true
                } else {
                    path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, linePaint)
        }
    }

    private fun drawLabels(canvas: Canvas, visibleSamples: List<Sample>, yMin: Float, yMax: Float) {
        textPaint.textSize = 22f
        canvas.drawText(String.format("%.1f", yMax), 4f, plotTop + 8f, textPaint)
        canvas.drawText(String.format("%.1f", yMin), 4f, plotBottom, textPaint)
        val first = visibleSamples.first().index
        val last = visibleSamples.last().index
        canvas.drawText("index ${first.toInt()} - ${last.toInt()}", plotLeft, height - 12f, textPaint)
    }

    private fun findYRange(visibleSamples: List<Sample>): Pair<Float, Float> {
        var yMin = Float.POSITIVE_INFINITY
        var yMax = Float.NEGATIVE_INFINITY
        visibleSamples.forEach { sample ->
            for (channel in 0 until 6) {
                if (channelVisible[channel] && channel < sample.values.size) {
                    yMin = min(yMin, sample.values[channel])
                    yMax = max(yMax, sample.values[channel])
                }
            }
        }
        if (!yMin.isFinite() || !yMax.isFinite()) return 0f to 1f
        if (yMin == yMax) return yMin - 1f to yMax + 1f
        val padding = (yMax - yMin) * 0.12f
        return yMin - padding to yMax + padding
    }

    private fun minViewportSize(): Float = min(60f, samples.size.toFloat()).coerceAtLeast(2f)

    private fun notifyViewportChanged() {
        val maxStart = (samples.size - viewportSize).coerceAtLeast(0f)
        val ratio = if (maxStart == 0f) 0f else viewportStart / maxStart
        onViewportChanged?.invoke(ratio)
    }
}
