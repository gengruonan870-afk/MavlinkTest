package com.example.mavlinktest // 👈 必须有这一行，它是这个文件的“身份证”

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class RealtimeGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = mutableListOf<Float>()
    private val maxPoints = 50
    private val linePaint = Paint().apply {
        color = Color.parseColor("#00FFFF")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    fun addDataPoint(value: Float) {
        dataPoints.add(value)
        if (dataPoints.size > maxPoints) dataPoints.removeAt(0)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.size < 2) return

        val path = Path()
        val widthPerPoint = width.toFloat() / (maxPoints - 1)
        val heightScale = if (height > 0) height.toFloat() / 100f else 1f

        dataPoints.forEachIndexed { i, value ->
            val x = i * widthPerPoint
            val y = height - (value * heightScale)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
