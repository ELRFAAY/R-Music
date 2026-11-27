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
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import code.name.monkey.appthemehelper.ThemeStore

/** A small bar chart without any library: one bar per value, a label under each, today highlighted. */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values: List<Float> = emptyList()
    private var labels: List<String> = emptyList()
    private var maxLabel: String = ""
    private var highlight = -1

    /** Colour of the bars; the theme accent when not set. */
    var barColor: Int? = null
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10 * density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private val rect = RectF()

    /** [values] and [labels] have the same length; the last bar is today. [maxLabel] is shown top left. */
    fun setData(values: List<Float>, labels: List<String>, maxLabel: String, highlight: Int = values.size - 1) {
        this.highlight = highlight
        this.values = values
        this.labels = labels
        this.maxLabel = maxLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val accent = barColor ?: ThemeStore.accentColor(context)
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        val textColor = context.getColor(tv.resourceId.takeIf { it != 0 } ?: android.R.color.darker_gray)
        textPaint.color = textColor
        linePaint.color = (textColor and 0x00FFFFFF) or 0x33000000

        val w = width.toFloat()
        val labelH = 18 * density
        val topPad = 16 * density
        val chartBottom = height - labelH
        val chartH = chartBottom - topPad
        val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val slot = w / values.size
        val barW = slot * 0.6f

        canvas.drawLine(0f, chartBottom, w, chartBottom, linePaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(maxLabel, 0f, 11 * density, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2f
            val barH = (v / max * chartH).coerceAtLeast(if (v > 0f) 2 * density else 0f)
            barPaint.color = if (i == highlight) accent else (accent and 0x00FFFFFF) or 0x88000000.toInt()
            rect.set(cx - barW / 2f, chartBottom - barH, cx + barW / 2f, chartBottom)
            canvas.drawRoundRect(rect, 4 * density, 4 * density, barPaint)
            labels.getOrNull(i)?.let { canvas.drawText(it, cx, height - 4 * density, textPaint) }
        }
    }
}
