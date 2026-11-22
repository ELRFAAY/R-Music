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
import android.media.AudioManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.util.TypedValue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Wraps the player's cover area and adds two gestures on top of whatever the children do:
 *  - drag up/down anywhere on it to change the music volume ([onVolumeChanged] shows the bar)
 *  - double tap on the right / left half to skip forward / back ([onDoubleTap])
 *
 * Plain taps are held back for the double-tap timeout and then replayed to the children, so the
 * cover's own click (opening the lyrics) still works, just a moment later. Horizontal swipes are
 * left alone, so the cover pager keeps changing songs.
 */
class CoverGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** When false (lyrics shown) every gesture here is off and touches go straight to the children. */
    var gesturesEnabled = true

    /** Volume as 0f..1f, called while dragging. */
    var onVolumeChanged: ((fraction: Float) -> Unit)? = null

    /** Called when the volume drag ends. */
    var onVolumeGestureEnd: (() -> Unit)? = null

    /** Called on a double tap; [forward] is true for the right half. */
    var onDoubleTap: ((forward: Boolean) -> Unit)? = null

    /**
     * Touch on the outer band of the cover (where the progress ring is): [fraction] is the angle
     * from 12 o'clock clockwise as 0f..1f; [final] is true when the finger lifts. A tap on the ring
     * jumps there, a drag moves along it, like the reference player.
     */
    var onRingTouch: ((fraction: Float, final: Boolean) -> Unit)? = null

    private enum class Mode { NONE, VOLUME, OTHER, RING }
    private val ringBand = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 26f, resources.displayMetrics
    )
    private var lastRingFraction = 0f

    private fun ringFraction(x: Float, y: Float): Float {
        val dx = x - width / 2f
        val dy = y - height / 2f
        var a = atan2(dx, -dy) // 0 at the top, clockwise
        if (a < 0) a += (2 * Math.PI).toFloat()
        return (a / (2 * Math.PI)).toFloat().coerceIn(0f, 1f)
    }

    private fun inRingBand(x: Float, y: Float): Boolean {
        val radius = minOf(width, height) / 2f
        return hypot(x - width / 2f, y - height / 2f) >= radius - ringBand
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var startVolumeFraction = 0f

    private var pendingTapX = 0f
    private var pendingTapY = 0f
    private var hasPendingTap = false
    private val replayTap = Runnable { replayPendingTap() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesEnabled) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            return super.dispatchTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.NONE
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                startVolumeFraction = currentVolumeFraction()
                if (onRingTouch != null && inRingBand(ev.x, ev.y)) {
                    mode = Mode.RING
                    lastRingFraction = ringFraction(ev.x, ev.y)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onRingTouch?.invoke(lastRingFraction, false)
                    return true
                }
                // The player panel would otherwise grab vertical drags to collapse itself.
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> if (mode == Mode.NONE) mode = Mode.OTHER

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.RING) {
                    lastRingFraction = ringFraction(ev.x, ev.y)
                    onRingTouch?.invoke(lastRingFraction, false)
                    return true
                }
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (mode == Mode.NONE) {
                    if (abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.5f) {
                        mode = Mode.VOLUME
                        cancelChildren(ev)
                    } else if (abs(dx) > slop) {
                        mode = Mode.OTHER
                    }
                }
                if (mode == Mode.VOLUME) {
                    // A full-height drag changes the volume by about 70%.
                    val fraction = (startVolumeFraction - dy / (height * 0.7f)).coerceIn(0f, 1f)
                    setVolumeFraction(fraction)
                    onVolumeChanged?.invoke(fraction)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (mode == Mode.RING) {
                    mode = Mode.NONE
                    lastRingFraction = ringFraction(ev.x, ev.y)
                    onRingTouch?.invoke(lastRingFraction, true)
                    return true
                }
                if (mode == Mode.VOLUME) {
                    mode = Mode.NONE
                    onVolumeGestureEnd?.invoke()
                    return true
                }
                if (mode == Mode.NONE && isShortTap(ev) && (hasPendingTap || !isCenterTap(ev))) {
                    return handleTapUp(ev)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.RING) onRingTouch?.invoke(lastRingFraction, true)
                if (mode == Mode.VOLUME) onVolumeGestureEnd?.invoke()
                mode = Mode.NONE
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** A tap in the middle 40% answers at once (no waiting for a possible double tap). */
    private fun isCenterTap(ev: MotionEvent): Boolean =
        ev.x > width * 0.3f && ev.x < width * 0.7f

    private fun isShortTap(ev: MotionEvent): Boolean =
        ev.eventTime - downTime < longPressTimeout &&
                abs(ev.x - downX) <= slop * 2 && abs(ev.y - downY) <= slop * 2

    /** First tap: hold it back. Second tap in time: double tap. */
    private fun handleTapUp(ev: MotionEvent): Boolean {
        cancelChildren(ev)
        return if (hasPendingTap) {
            removeCallbacks(replayTap)
            hasPendingTap = false
            // Left half = forward (+10 s), right half = back (-10 s), as the user asked.
            onDoubleTap?.invoke(ev.x < width / 2f)
            true
        } else {
            hasPendingTap = true
            pendingTapX = ev.x
            pendingTapY = ev.y
            postDelayed(replayTap, doubleTapTimeout)
            true
        }
    }

    /** No second tap came: let the children see the tap after all. */
    private fun replayPendingTap() {
        if (!hasPendingTap) return
        hasPendingTap = false
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, pendingTapX, pendingTapY, 0)
        val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, pendingTapX, pendingTapY, 0)
        super.dispatchTouchEvent(down)
        super.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun cancelChildren(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun currentVolumeFraction(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    private fun setVolumeFraction(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).roundToInt(), 0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(replayTap)
        hasPendingTap = false
    }
}
