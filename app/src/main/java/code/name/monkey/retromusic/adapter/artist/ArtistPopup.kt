package code.name.monkey.retromusic.adapter.artist

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import code.name.monkey.retromusic.R
import com.google.android.material.color.MaterialColors

/** Small menu that opens right next to a cover, with an arrow pointing at it. */
object ArtistPopup {

    fun show(anchor: View, entries: List<Pair<String, () -> Unit>>): PopupWindow {
        val ctx = anchor.context
        val d = ctx.resources.displayMetrics.density
        // Opaque card; text colour picked from the card's own brightness so it is always readable
        // (on a pure black theme the theme's "on surface" colour can also be black).
        val surface = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurface, Color.DKGRAY) or 0xFF000000.toInt()
        val onSurface = if (androidx.core.graphics.ColorUtils.calculateLuminance(surface) > 0.5) Color.BLACK else Color.WHITE

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface); cornerRadius = 14 * d
            }
            elevation = 8 * d
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
        var popup: PopupWindow? = null
        entries.forEach { (label, action) ->
            card.addView(TextView(ctx).apply {
                text = label
                minWidth = (170 * d).toInt()
                minHeight = (44 * d).toInt()
                gravity = Gravity.CENTER_VERTICAL
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
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val anchorCx = loc[0] + anchor.width / 2
        val showBelow = loc[1] + anchor.height / 2 < screenH * 0.6f

        val arrow = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_popup_arrow)
            imageTintList = ColorStateList.valueOf(surface)
            if (!showBelow) rotation = 180f
        }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val arrowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // Fixed card width: with "wrap" the match-parent rows collapsed to the arrow's width and the
        // menu showed as a thin black strip.
        val cardLp = LinearLayout.LayoutParams((210 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        if (showBelow) { root.addView(arrow, arrowLp); root.addView(card, cardLp) }
        else { root.addView(card, cardLp); root.addView(arrow, arrowLp) }

        root.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
        )
        val w = root.measuredWidth
        val h = root.measuredHeight
        val margin = (8 * d).toInt()
        val x = (anchorCx - w / 2).coerceIn(margin, (screenW - w - margin).coerceAtLeast(margin))
        val y = if (showBelow) loc[1] + anchor.height - (4 * d).toInt() else loc[1] + (4 * d).toInt() - h
        arrowLp.leftMargin = (anchorCx - x - (9 * d).toInt()).coerceIn(
            (14 * d).toInt(), (w - (32 * d).toInt()).coerceAtLeast((14 * d).toInt())
        )

        val window = PopupWindow(root, w, h, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 0f
            showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, x, y.coerceAtLeast(0))
        }
        popup = window
        return window
    }
}
