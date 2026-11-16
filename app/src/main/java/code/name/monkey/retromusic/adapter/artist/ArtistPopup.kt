package code.name.monkey.retromusic.adapter.artist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
import android.widget.ImageView
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
        var backdrop: Bitmap? = null
        var backdropSrc: Rect? = null
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
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
            val bd = backdrop
            val src = backdropSrc
            if (bd != null && src != null) {
                canvas.save()
                canvas.clipPath(path)
                canvas.drawBitmap(bd, src, b, bmpPaint)
                canvas.restore()
            }
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
        entries.forEachIndexed { index, (label, action) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (44 * d).toInt()
                setPadding((16 * d).toInt(), (10 * d).toInt(), (22 * d).toInt(), (10 * d).toInt())
                val tv = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { popup?.dismiss(); action() }
            }
            val iconRes = iconFor(ctx, label)
            val icon = ImageView(ctx).apply {
                if (iconRes != 0) setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(onSurface)
                alpha = 0.9f
            }
            row.addView(icon, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()).apply {
                marginEnd = (14 * d).toInt()
            })
            row.addView(TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(onSurface)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            // little entrance: row slides/fades in one after the other, its icon pops
            row.alpha = 0f
            row.translationY = 8 * d
            icon.scaleX = 0f
            icon.scaleY = 0f
            row.animate().alpha(1f).translationY(0f).setStartDelay(60L + index * 40L).setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            icon.animate().scaleX(1f).scaleY(1f).setStartDelay(120L + index * 40L).setDuration(260)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
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

        val blurred = captureBlurred(rootView, screenW, screenH)
        // Glass: blurred picture of what is behind + a light tint of the theme surface.
        val glassTint = ColorUtils.setAlphaComponent(surface, if (blurred != null) 0x8C else 0xE6)
        val bubble = BubbleDrawable(glassTint, outline, 16 * d, arrowW, arrowH, showBelow, 1 * d)
        if (blurred != null) {
            bubble.backdrop = blurred
            val sc = blurred.width.toFloat() / screenW
            bubble.backdropSrc = Rect(
                (x * sc).toInt(), (y * sc).toInt(),
                ((x + w) * sc).toInt().coerceAtMost(blurred.width),
                ((y + h) * sc).toInt().coerceAtMost(blurred.height)
            )
        }
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

    private fun iconFor(ctx: android.content.Context, label: String): Int {
        val map = listOf(
            R.string.artist_pin to R.drawable.ic_menu_pin,
            R.string.artist_unpin to R.drawable.ic_menu_pin,
            R.string.action_play_next to R.drawable.ic_skip_next,
            R.string.action_add_to_playing_queue to R.drawable.ic_queue_music,
            R.string.action_add_to_playlist to R.drawable.ic_playlist_add,
            R.string.merge_artist to R.drawable.ic_menu_merge,
            R.string.edit_artist_title to R.drawable.ic_edit,
            R.string.artist_select to R.drawable.ic_select_all,
            R.string.mini_move to R.drawable.ic_drag_handle,
            R.string.mini_lock to R.drawable.ic_menu_lock,
            R.string.mini_unlock to R.drawable.ic_menu_unlock,
            R.string.mini_reset_position to R.drawable.ic_restore,
        )
        return map.firstOrNull { ctx.getString(it.first) == label }?.second ?: R.drawable.ic_check_circle
    }

    /** What is on screen now, shrunk and blurred (one cheap box blur), or null if it cannot be drawn. */
    private fun captureBlurred(root: View, w: Int, h: Int): Bitmap? = try {
        val scale = 8
        val bmp = Bitmap.createBitmap(w / scale, h / scale, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(1f / scale, 1f / scale)
        root.draw(c)
        boxBlur(bmp, 3)
        bmp
    } catch (e: Throwable) {
        null
    }

    private fun boxBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            blurPass(px, tmp, w, h, radius, true)
            blurPass(tmp, px, w, h, radius, false)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (l in 0 until lines) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            fun idx(i: Int) = if (horizontal) l * w + i else i * w + l
            for (i in -r..r) {
                val p = src[idx(i.coerceIn(0, len - 1))]
                sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (i in 0 until len) {
                dst[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[idx((i + r + 1).coerceIn(0, len - 1))]
                val rem = src[idx((i - r).coerceIn(0, len - 1))]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }
}
