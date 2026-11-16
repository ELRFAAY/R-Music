package code.name.monkey.retromusic.adapter.artist

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import code.name.monkey.retromusic.R
import com.google.android.material.color.MaterialColors

/**
 * Small menu that opens next to a cover. The arrow is part of the menu's own outline (one shape),
 * points at the cover that was pressed and the menu never covers the mini player / bottom bar.
 */
object ArtistPopup {

    /** Rounded card with a triangular tip, drawn as ONE path (fill + thin outline). */
    private class BubbleDrawable(
        private val fill: Int,
        private val stroke: Int,
        private val radius: Float,
        private val arrowW: Float,
        private val arrowH: Float,
        private val tipBelow: Boolean, // true: the tip is on the top edge (menu is below the cover)
        private val strokeW: Float,
    ) : Drawable() {
        var arrowCx = 0f
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke; style = Paint.Style.STROKE; strokeWidth = strokeW
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val body = RectF(
                b.left.toFloat(),
                b.top.toFloat() + if (tipBelow) arrowH else 0f,
                b.right.toFloat(),
                b.bottom.toFloat() - if (tipBelow) 0f else arrowH
            )
            body.inset(strokeW, strokeW)
            val path = Path()
            path.addRoundRect(body, radius, radius, Path.Direction.CW)
            val tip = Path()
            val cx = arrowCx.coerceIn(body.left + radius + arrowW / 2, body.right - radius - arrowW / 2)
            if (tipBelow) {
                tip.moveTo(cx - arrowW / 2, body.top + 1f)
                tip.lineTo(cx, b.top.toFloat() + strokeW)
                tip.lineTo(cx + arrowW / 2, body.top + 1f)
            } else {
                tip.moveTo(cx - arrowW / 2, body.bottom - 1f)
                tip.lineTo(cx, b.bottom.toFloat() - strokeW)
                tip.lineTo(cx + arrowW / 2, body.bottom - 1f)
            }
            tip.close()
            path.op(tip, Path.Op.UNION)
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun show(anchor: View, entries: List<Pair<String, () -> Unit>>) {
        val ctx = anchor.context
        val d = ctx.resources.displayMetrics.density
        // Opaque card; text colour picked from the card's own brightness so it is always readable
        // (on a pure black theme the theme's "on surface" colour can also be black).
        val surface = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurface, Color.DKGRAY) or 0xFF000000.toInt()
        val onSurface = if (ColorUtils.calculateLuminance(surface) > 0.5) Color.BLACK else Color.WHITE
        val outline = ColorUtils.setAlphaComponent(onSurface, 0x33)

        val arrowW = 20 * d
        val arrowH = 11 * d
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
        var popup: PopupWindow? = null
        entries.forEach { (label, action) ->
            card.addView(TextView(ctx).apply {
                text = label
                minHeight = (44 * d).toInt()
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(onSurface)
                setPadding((18 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt())
                val tv = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { popup?.dismiss(); action() }
            })
        }

        val screenW = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels
        val rootView = anchor.rootView
        // The menu must stay above the mini player and the bottom bar.
        var bottomLimit = screenH
        for (id in intArrayOf(R.id.miniPlayerFragment, R.id.navigationView)) {
            val v = rootView.findViewById<View>(id) ?: continue
            if (v.visibility != View.VISIBLE || v.height == 0) continue
            val l = IntArray(2)
            v.getLocationOnScreen(l)
            if (l[1] in 1 until bottomLimit) bottomLimit = l[1]
        }
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val anchorCx = loc[0] + anchor.width / 2

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val cardW = (210 * d).toInt()
        root.addView(card, LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
        )
        val cardH = root.measuredHeight
        val h = cardH + arrowH.toInt()
        val w = cardW

        val topLimit = (24 * d).toInt()
        val spaceBelow = bottomLimit - (loc[1] + anchor.height)
        val spaceAbove = loc[1] - topLimit
        val showBelow = spaceBelow >= h || spaceBelow >= spaceAbove

        val margin = (8 * d).toInt()
        val x = (anchorCx - w / 2).coerceIn(margin, (screenW - w - margin).coerceAtLeast(margin))
        var y = if (showBelow) loc[1] + anchor.height - (3 * d).toInt() else loc[1] + (3 * d).toInt() - h
        y = y.coerceIn(topLimit, (bottomLimit - h - margin).coerceAtLeast(topLimit))

        val bubble = BubbleDrawable(surface, outline, 16 * d, arrowW, arrowH, showBelow, 1 * d)
        bubble.arrowCx = (anchorCx - x).toFloat()
        root.background = bubble
        root.setPadding(0, if (showBelow) arrowH.toInt() else 0, 0, if (showBelow) 0 else arrowH.toInt())
        root.elevation = 0f

        popup = PopupWindow(root, w, h, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 0f
            showAtLocation(rootView, Gravity.NO_GRAVITY, x, y)
        }
    }
}
