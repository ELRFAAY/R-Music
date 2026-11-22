/*
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Feedback for a double tap on the round cover: a translucent grey half disc over the tapped half (left = forward, right = back)
 * of the (square) view, two arrows and a label such as "10 seconds". Hides itself after a moment.
 */
class SeekSectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 128, 128, 128) }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 15 * resources.displayMetrics.scaledDensity
    }
    private val oval = RectF()
    private val arrow = Path()
    private var forward = true
    private var label = ""
    private val hideRunnable = Runnable { visibility = GONE }

    init {
        visibility = GONE
    }

    fun show(forward: Boolean, label: String) {
        this.forward = forward
        this.label = label
        removeCallbacks(hideRunnable)
        visibility = VISIBLE
        alpha = 0f
        scaleX = 0.92f
        scaleY = 0.92f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
        invalidate()
        postDelayed(hideRunnable, 650)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        oval.set(0f, 0f, w, h)
        // 0 degrees is 3 o'clock and angles run clockwise: -90..+90 is the right half.
        // Forward is the LEFT half (the tapped side), back is the right half.
        canvas.drawArc(oval, if (forward) 90f else -90f, 180f, true, fill)

        val cx = if (forward) w * 0.25f else w * 0.75f
        val cy = h * 0.46f
        val s = 9 * density
        val dir = if (forward) 1f else -1f
        for (i in 0..1) {
            val x = cx + dir * (i - 0.5f) * s * 1.7f
            arrow.reset()
            arrow.moveTo(x - dir * s * 0.8f, cy - s)
            arrow.lineTo(x + dir * s * 0.8f, cy)
            arrow.lineTo(x - dir * s * 0.8f, cy + s)
            arrow.close()
            canvas.drawPath(arrow, arrowPaint)
        }
        canvas.drawText(label, cx, cy + s * 3f, textPaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        super.onDetachedFromWindow()
    }
}
