package dev.bmg.edgeclip.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.content.ContextCompat
import dev.bmg.edgeclip.R

class QuickballView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1).toFloat()
        color = 0x33000000.toInt()
    }

    private var isLeftEdge = false
    private var expansion = 0f // 0.0 to 1.0

    init {
        paint.color = ContextCompat.getColor(context, R.color.handler_bg)
    }

    fun setSide(isLeft: Boolean) {
        isLeftEdge = isLeft
        invalidate()
    }

    fun setExpansion(progress: Float) {
        expansion = progress.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        
        // At resting width (e.g. 26dp out of 40dp), we want to show a segment of the ball.
        // For Left Edge: circle sticks out from the left. Its left-most part is off-screen.
        // So its right-most part (cx + radius) should be at width 'w'.
        // So cx = w - radius.
        // For Right Edge: circle sticks out from the right. Its right-most part is off-screen.
        // So its left-most part (cx - radius) should be at width 0.
        // So cx = radius.
        
        val cx: Float = if (isLeftEdge) {
            w - radius
        } else {
            radius
        }

        canvas.drawCircle(cx, radius, radius, paint)
        canvas.drawCircle(cx, radius, radius, borderPaint)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}