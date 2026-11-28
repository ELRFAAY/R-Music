package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.DrawableRes
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.util.Season
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The "season tree" scene: a ground strip and one tree whose look follows the date inside the season
 * (spring buds -> blossom, summer, autumn leaves thinning out in 5 steps, winter snow in 3), with petals /
 * leaves / snow falling, a tumbleweed in summer and now and then rain (splashes and ripples on the puddles).
 *
 * The art is the processed set in res/drawable-nodpi (see design/season_tree). The tree is drawn so its trunk
 * base (x = 411, y = 726 on its 768 square) sits on the middle of the ground strip. Nothing is loaded until
 * the first frame, and [release] frees the bitmaps when the view goes away.
 */
internal class SeasonTreeScene(
    private val context: Context,
    private val season: Season,
    private val state: Int
) {
    private var w = 0f
    private var h = 0f
    private var u = 1f                       // 1.0 on a 1080 px wide screen

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val matrix = Matrix()

    private var loaded = false
    private var tree: Bitmap? = null
    private var ground: Bitmap? = null
    private var puddles: Bitmap? = null
    private var fall: Bitmap? = null         // petal / leaf / snow sheet
    private var tumble: Bitmap? = null
    private var splash: Bitmap? = null
    private var ripple: Bitmap? = null
    private var farShader: BitmapShader? = null
    private var nearShader: BitmapShader? = null

    private class Particle {
        var x = 0f
        var y = 0f
        var vy = 0f
        var sway = 0f
        var freq = 0f
        var phase = 0f
        var size = 0f
        var frame0 = 0f
        var fps = 0f
        var landY = 0f
    }

    private class Drop(val x: Float, val y: Float, val born: Float, val ripple: Boolean)

    private val rnd = Random(season.ordinal * 31 + state)
    private val particles = ArrayList<Particle>()
    private val drops = ArrayList<Drop>()
    private var nextDrop = 0f

    // ---- layout -------------------------------------------------------------------------------------------

    private val baseY get() = h * 0.62f
    private val groundH get() = w * 240f / 1080f
    private val groundTop get() = baseY - groundH * 0.45f
    private val groundBottom get() = groundTop + groundH

    fun resize(width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        u = w / 1080f
        initParticles()
    }

    fun release() {
        listOf(tree, ground, puddles, fall, tumble, splash, ripple).forEach { it?.recycle() }
        tree = null; ground = null; puddles = null; fall = null; tumble = null; splash = null; ripple = null
        farShader = null; nearShader = null
        loaded = false
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    fun draw(canvas: Canvas, t: Float, dt: Float) {
        if (w <= 0f || h <= 0f) return
        ensureLoaded()
        val rain = rainLevel(t)

        ground?.let {
            dst.set(0f, groundTop, w, groundBottom)
            canvas.drawBitmap(it, null, dst, paint)
        }
        puddles?.let {
            if (rain > 0.02f) {
                dst.set(0f, groundTop, w, groundBottom)
                paint.alpha = (rain * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(it, null, dst, paint)
                paint.alpha = 255
            }
        }
        tree?.let {
            val size = min(w * 0.95f, h * 0.46f)
            val left = w / 2f - 411f / 768f * size
            val top = baseY - 726f / 768f * size
            dst.set(left, top, left + size, top + size)
            canvas.drawBitmap(it, null, dst, paint)
        }

        drawFalling(canvas, t, dt)
        drawTumbleweed(canvas, t)
        if (rain > 0.02f) {
            drawDrops(canvas, t, rain)
            drawRain(canvas, t, rain)
        } else {
            drops.clear()
        }
    }

    private fun drawFalling(canvas: Canvas, t: Float, dt: Float) {
        val sheet = fall ?: return
        val cell = sheet.height
        for (p in particles) {
            p.y += p.vy * dt
            if (p.y > p.landY) reset(p, false)
            val x = p.x + sin(t * p.freq + p.phase) * p.sway
            val frame = ((t * p.fps + p.frame0).toInt() % FRAMES + FRAMES) % FRAMES
            src.set(frame * cell, 0, (frame + 1) * cell, cell)
            val half = p.size / 2f
            dst.set(x - half, p.y - half, x + half, p.y + half)
            canvas.drawBitmap(sheet, src, dst, paint)
        }
    }

    private fun drawTumbleweed(canvas: Canvas, t: Float) {
        val sheet = tumble ?: return
        val size = 84f * u
        val period = 24f
        val travel = w + size * 2f
        val x = -size + (t % period) / period * travel
        val hop = abs(sin(x / (size * 1.6f) * PI.toFloat())) * size * 0.16f
        val y = groundBottom - size * 0.62f - groundH * 0.10f - hop
        val circumference = PI.toFloat() * size
        val frame = ((x / (circumference / FRAMES)).toInt() % FRAMES + FRAMES) % FRAMES
        val cell = sheet.height
        src.set(frame * cell, 0, (frame + 1) * cell, cell)
        dst.set(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f)
        canvas.drawBitmap(sheet, src, dst, paint)
    }

    private fun drawDrops(canvas: Canvas, t: Float, rain: Float) {
        // spawn new splashes / ripples at a rate that follows the rain
        while (t >= nextDrop) {
            nextDrop = t + 1f / (16f * rain).coerceAtLeast(1f)
            if (drops.size < MAX_DROPS) spawnDrop(t)
        }
        val iter = drops.iterator()
        while (iter.hasNext()) {
            val d = iter.next()
            val age = t - d.born
            val life = if (d.ripple) RIPPLE_LIFE else SPLASH_LIFE
            if (age >= life) {
                iter.remove()
                continue
            }
            val sheet = (if (d.ripple) ripple else splash) ?: continue
            val frame = ((age / life) * 6f).toInt().coerceIn(0, 5)
            val cw = sheet.width / 6
            src.set(frame * cw, 0, (frame + 1) * cw, sheet.height)
            val dw = cw * u * (if (d.ripple) 1.5f else 1.3f)
            val dh = sheet.height * u * (if (d.ripple) 1.5f else 1.3f)
            dst.set(d.x - dw / 2f, d.y - dh * 0.8f, d.x + dw / 2f, d.y + dh * 0.2f)
            canvas.drawBitmap(sheet, src, dst, paint)
        }
    }

    private fun spawnDrop(t: Float) {
        val pud = puddles
        if (pud != null && rnd.nextFloat() < 0.45f) {
            // a ripple on a puddle: pick a point whose puddle pixel is visible
            repeat(10) {
                val px = rnd.nextInt(pud.width)
                val py = rnd.nextInt(pud.height)
                if ((pud.getPixel(px, py) ushr 24) > 70) {
                    drops.add(Drop(px / pud.width.toFloat() * w, groundTop + py / pud.height.toFloat() * groundH, t, true))
                    return
                }
            }
        }
        drops.add(Drop(rnd.nextFloat() * w, groundTop + groundH * (0.3f + 0.6f * rnd.nextFloat()), t, false))
    }

    private fun drawRain(canvas: Canvas, t: Float, rain: Float) {
        val far = farShader
        val near = nearShader
        if (far != null) {
            val scale = w * 0.8f / 512f
            val speed = 520f * u
            matrix.setScale(scale, scale)
            matrix.postTranslate(t * speed * SIN10, t * speed * COS10)
            far.setLocalMatrix(matrix)
            paint.shader = far
            paint.alpha = (rain * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        if (near != null) {
            val scale = w * 1.1f / 512f
            val speed = 980f * u
            matrix.setScale(scale, scale)
            matrix.postTranslate(t * speed * SIN10 + 97f, t * speed * COS10 + 53f)
            near.setLocalMatrix(matrix)
            paint.shader = near
            paint.alpha = (rain * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
        paint.alpha = 255
    }

    // ---- weather and particles ----------------------------------------------------------------------------

    /** 0..1: spring and autumn get a rainy spell every minute, summer a short light one, winter none (snow instead). */
    private fun rainLevel(t: Float): Float = when (season) {
        Season.WINTER -> 0f
        Season.SUMMER -> 0.5f * window(t, 150f, 20f, 12f)
        else -> window(t, 60f, 10f, 28f)
    }

    private fun window(t: Float, period: Float, start: Float, len: Float): Float {
        val p = t % period
        if (p < start || p > start + len) return 0f
        return min(1f, min((p - start) / RAMP, (start + len - p) / RAMP))
    }

    private fun initParticles() {
        particles.clear()
        val count = when (season) {
            Season.SPRING -> intArrayOf(0, 10, 24)[(state - 1).coerceIn(0, 2)]
            Season.AUTUMN -> intArrayOf(6, 16, 16, 8, 0)[(state - 1).coerceIn(0, 4)]
            Season.WINTER -> intArrayOf(18, 26, 32)[(state - 1).coerceIn(0, 2)]
            Season.SUMMER -> 0
        }
        repeat(count) { particles.add(Particle().also { reset(it, true) }) }
    }

    private fun reset(p: Particle, anywhere: Boolean) {
        p.x = rnd.nextFloat() * w
        p.landY = groundTop + groundH * (0.3f + 0.5f * rnd.nextFloat())
        p.y = if (anywhere) rnd.nextFloat() * p.landY else -40f * u * rnd.nextFloat()
        when (season) {
            Season.SPRING -> { p.vy = (40f + rnd.nextFloat() * 40f) * u; p.size = (26f + rnd.nextFloat() * 16f) * u; p.sway = (28f + rnd.nextFloat() * 30f) * u; p.fps = 7f }
            Season.AUTUMN -> { p.vy = (55f + rnd.nextFloat() * 50f) * u; p.size = (32f + rnd.nextFloat() * 18f) * u; p.sway = (36f + rnd.nextFloat() * 30f) * u; p.fps = 8f }
            else -> { p.vy = (45f + rnd.nextFloat() * 50f) * u; p.size = (16f + rnd.nextFloat() * 14f) * u; p.sway = (20f + rnd.nextFloat() * 16f) * u; p.fps = 5f }
        }
        p.freq = 0.5f + rnd.nextFloat() * 0.9f
        p.phase = rnd.nextFloat() * 6.28f
        p.frame0 = rnd.nextFloat() * FRAMES
    }

    // ---- art ----------------------------------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        tree = load(treeRes())
        ground = load(groundRes())
        when (season) {
            Season.SPRING -> fall = load(R.drawable.fx_petal)
            Season.AUTUMN -> fall = load(R.drawable.fx_leaf)
            Season.WINTER -> fall = load(R.drawable.fx_snow)
            Season.SUMMER -> tumble = load(R.drawable.fx_tumbleweed)
        }
        if (season != Season.WINTER) {
            puddles = load(R.drawable.ground_puddles)
            splash = load(R.drawable.fx_splash)
            ripple = load(R.drawable.fx_ripple)
            farShader = load(R.drawable.fx_rain_far)?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
            nearShader = load(R.drawable.fx_rain_near)?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        }
        initParticles()
    }

    private fun load(@DrawableRes id: Int): Bitmap? = try {
        BitmapFactory.decodeResource(context.resources, id, BitmapFactory.Options().apply { inScaled = false })
    } catch (e: Throwable) {
        null
    }

    @DrawableRes
    private fun treeRes(): Int = when (season) {
        Season.SPRING -> pick(state, R.drawable.tree_spring_1, R.drawable.tree_spring_2, R.drawable.tree_spring_3)
        Season.SUMMER -> pick(state, R.drawable.tree_summer_1, R.drawable.tree_summer_2)
        Season.AUTUMN -> pick(
            state, R.drawable.tree_autumn_1, R.drawable.tree_autumn_2, R.drawable.tree_autumn_3,
            R.drawable.tree_autumn_4, R.drawable.tree_autumn_5
        )
        Season.WINTER -> pick(state, R.drawable.tree_winter_1, R.drawable.tree_winter_2, R.drawable.tree_winter_3)
    }

    @DrawableRes
    private fun groundRes(): Int = when (season) {
        Season.SPRING -> pick(state, R.drawable.ground_spring_1, R.drawable.ground_spring_2, R.drawable.ground_spring_2)
        Season.SUMMER -> pick(state, R.drawable.ground_summer_1, R.drawable.ground_summer_2)
        Season.AUTUMN -> pick(
            state, R.drawable.ground_autumn_1, R.drawable.ground_autumn_2, R.drawable.ground_autumn_3,
            R.drawable.ground_autumn_4, R.drawable.ground_autumn_5
        )
        Season.WINTER -> pick(state, R.drawable.ground_winter_1, R.drawable.ground_winter_2, R.drawable.ground_winter_3)
    }

    private fun pick(state: Int, vararg ids: Int): Int = ids[(state - 1).coerceIn(0, ids.size - 1)]

    private companion object {
        const val FRAMES = 8
        const val MAX_DROPS = 26
        const val SPLASH_LIFE = 0.45f
        const val RIPPLE_LIFE = 0.9f
        const val RAMP = 3f
        val SIN10 = sin(Math.toRadians(10.0)).toFloat()
        val COS10 = kotlin.math.cos(Math.toRadians(10.0)).toFloat()
    }
}
