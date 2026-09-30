package com.strider.ru.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.strider.ru.R
import kotlin.math.min

class IndexProgressDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        color = ContextCompat.getColor(context, R.color.divider)
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        color = ContextCompat.getColor(context, R.color.ru_crimson)
        strokeCap = Paint.Cap.ROUND
    }

    private val arcRect = RectF()
    private var progress = 0f
    private var indeterminateSweep = 0f
    private var indeterminate = false

    fun setProgress(fraction: Float) {
        indeterminate = false
        progress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    fun setIndeterminate(active: Boolean) {
        indeterminate = active
        if (!active) indeterminateSweep = 0f
        invalidate()
    }

    fun setIndeterminateSweep(sweepDegrees: Float) {
        indeterminateSweep = sweepDegrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val inset = dp(6f)
        arcRect.set(inset, inset, size - inset, size - inset)

        canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)

        if (indeterminate) {
            canvas.drawArc(arcRect, -90f + indeterminateSweep, 90f, false, progressPaint)
        } else if (progress > 0f) {
            canvas.drawArc(arcRect, -90f, 360f * progress, false, progressPaint)
        }
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
