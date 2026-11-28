package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.Canvas
import android.view.View
import code.name.monkey.retromusic.util.Season

/**
 * Hosts a [SeasonScene] and drives its animation at ~30 fps while [setRunning] is true.
 * When it is not running nothing is scheduled, so a hidden player costs no CPU.
 */
internal class SeasonSceneView(context: Context, season: Season, treeState: Int? = null) : View(context) {

    /** The classic code-drawn scene, or (when [treeState] is given) the season-tree scene. */
    private val scene: SeasonScene? = if (treeState == null) SeasonScene(season) else null
    private val treeScene: SeasonTreeScene? =
        if (treeState != null) SeasonTreeScene(context, season, treeState) else null
    private var running = false
    private var startNanos = 0L
    private var lastNanos = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, FRAME_MS)
        }
    }

    fun setRunning(on: Boolean) {
        if (on == running) return
        running = on
        removeCallbacks(ticker)
        if (on) {
            lastNanos = 0L
            postDelayed(ticker, FRAME_MS)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scene?.resize(w, h)
        treeScene?.resize(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        if (startNanos == 0L) startNanos = now
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.1f)
        lastNanos = now
        val t = (now - startNanos) / 1e9f
        scene?.draw(canvas, t, dt)
        treeScene?.draw(canvas, t, dt)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        treeScene?.release()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // a tick queued before attaching is released now: drop it so only one loop runs
        removeCallbacks(ticker)
        if (running) postDelayed(ticker, FRAME_MS)
    }

    private companion object {
        const val FRAME_MS = 33L
    }
}
