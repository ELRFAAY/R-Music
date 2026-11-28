package code.name.monkey.retromusic.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import code.name.monkey.retromusic.util.Season
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * An original, code-drawn animated scene for one season (no files, no network): winter cabin at night with
 * snow, autumn sunset with falling leaves, spring with blossoms and clouds, summer sea with sun and birds.
 *
 * Nothing is allocated while drawing (paths, paints and shaders are reused), so a frame is cheap.
 */
internal class SeasonScene(private val season: Season) {

    private class Particle {
        var x = 0f
        var y = 0f
        var size = 0f
        var speed = 0f
        var phase = 0f
        var drift = 0f
        var rot = 0f
        var spin = 0f
        var color = 0
        var depth = 0f
    }

    private val rnd = Random(season.ordinal * 7919 + 17)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private var w = 0f
    private var h = 0f
    private var u = 1f // 1 unit = 1/360 of the width, so sizes scale with the screen

    private var sky: Shader? = null
    private var glow: Shader? = null // moon / sun glow
    private var windowGlow: Shader? = null
    private var sea: Shader? = null

    private var stars = FloatArray(0)
    private var particles = emptyArray<Particle>()
    private var flowers = FloatArray(0)
    private var clouds = FloatArray(0)

    // ---------------------------------------------------------------- setup

    fun resize(width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        u = w / 360f
        when (season) {
            Season.WINTER -> {
                sky = vertical(intArrayOf(col(0xFF0B1030), col(0xFF1B2A5C), col(0xFF3A3F7A)), floatArrayOf(0f, 0.55f, 1f))
                glow = RadialGradient(
                    0.74f * w, 0.16f * h, 0.3f * w,
                    Color.argb(95, 244, 241, 216), Color.argb(0, 244, 241, 216), Shader.TileMode.CLAMP
                )
                windowGlow = RadialGradient(
                    0.34f * w, 0.8f * h, 0.22f * w,
                    Color.argb(200, 255, 193, 90), Color.argb(0, 255, 193, 90), Shader.TileMode.CLAMP
                )
            }
            Season.AUTUMN -> {
                sky = vertical(
                    intArrayOf(col(0xFF2B1B4A), col(0xFFB3475E), col(0xFFFF9A4D), col(0xFFFFD27A)),
                    floatArrayOf(0f, 0.42f, 0.68f, 0.9f)
                )
                glow = RadialGradient(
                    0.68f * w, 0.6f * h, 0.45f * w,
                    Color.argb(150, 255, 214, 140), Color.argb(0, 255, 214, 140), Shader.TileMode.CLAMP
                )
            }
            Season.SPRING -> {
                sky = vertical(intArrayOf(col(0xFF8FD0F2), col(0xFFD2ECFA), col(0xFFFFE6EE)), floatArrayOf(0f, 0.55f, 1f))
                glow = RadialGradient(
                    0.78f * w, 0.14f * h, 0.34f * w,
                    Color.argb(170, 255, 246, 190), Color.argb(0, 255, 246, 190), Shader.TileMode.CLAMP
                )
            }
            Season.SUMMER -> {
                sky = vertical(intArrayOf(col(0xFF1E8BFF), col(0xFF63C2FF), col(0xFFC4ECFF)), floatArrayOf(0f, 0.5f, 0.64f))
                glow = RadialGradient(
                    0.5f * w, 0.2f * h, 0.42f * w,
                    Color.argb(190, 255, 244, 150), Color.argb(0, 255, 244, 150), Shader.TileMode.CLAMP
                )
                sea = vertical(intArrayOf(col(0xFF22B1DD), col(0xFF0A68A6)), floatArrayOf(0f, 1f), 0.64f * h, 0.8f * h)
            }
        }
        stars = FloatArray(90 * 3) { rnd.nextFloat() }
        flowers = FloatArray(60 * 4) { rnd.nextFloat() }
        clouds = FloatArray(5 * 4) { rnd.nextFloat() }
        val count = when (season) {
            Season.WINTER -> 90
            Season.AUTUMN -> 26
            Season.SPRING -> 36
            Season.SUMMER -> 24
        }
        particles = Array(count) { Particle().also { p -> resetParticle(p, true) } }
    }

    private fun col(hex: Long): Int = hex.toInt()

    private fun vertical(colors: IntArray, stops: FloatArray, y0: Float = 0f, y1: Float = h): Shader =
        LinearGradient(0f, y0, 0f, y1, colors, stops, Shader.TileMode.CLAMP)

    private fun resetParticle(p: Particle, initial: Boolean) {
        p.x = rnd.nextFloat() * w
        p.y = if (initial) rnd.nextFloat() * h else -20f * u
        p.depth = rnd.nextFloat()
        p.phase = rnd.nextFloat() * TAU
        when (season) {
            Season.WINTER -> {
                p.size = (1.2f + 2.8f * p.depth) * u
                p.speed = (0.035f + 0.07f * p.depth) * h
                p.drift = (6f + 14f * p.depth) * u
            }
            Season.AUTUMN -> {
                p.size = (7f + 8f * p.depth) * u
                p.speed = (0.05f + 0.05f * p.depth) * h
                p.drift = (24f + 30f * p.depth) * u
                p.spin = (rnd.nextFloat() - 0.5f) * 4f
                p.color = LEAVES[rnd.nextInt(LEAVES.size)]
            }
            Season.SPRING -> {
                p.size = (3.5f + 3f * p.depth) * u
                p.speed = (0.035f + 0.04f * p.depth) * h
                p.drift = (18f + 22f * p.depth) * u
                p.spin = (rnd.nextFloat() - 0.5f) * 3f
                p.color = PETALS[rnd.nextInt(PETALS.size)]
            }
            Season.SUMMER -> {
                // sparkles on the water
                p.y = (0.65f + 0.14f * rnd.nextFloat()) * h
                p.size = (1.2f + 1.8f * p.depth) * u
            }
        }
    }

    // ---------------------------------------------------------------- frame

    fun draw(c: Canvas, t: Float, dt: Float) {
        if (w <= 0f || h <= 0f) return
        paint.style = Paint.Style.FILL
        paint.shader = sky
        paint.alpha = 255
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        when (season) {
            Season.WINTER -> winter(c, t)
            Season.AUTUMN -> autumn(c, t)
            Season.SPRING -> spring(c, t)
            Season.SUMMER -> summer(c, t)
        }
        overlay?.invoke(c, t, dt)
        drawParticles(c, t, dt)
    }

    /** Drawn between the scene and its falling particles (the cover tree). */
    var overlay: ((Canvas, Float, Float) -> Unit)? = null

    // ---------------------------------------------------------------- shared helpers

    private fun hillY(x: Float, base: Float, amp: Float, k: Float, ph: Float): Float =
        base + amp * sin(k * x / w * TAU + ph)

    private fun hill(c: Canvas, base: Float, amp: Float, k: Float, ph: Float, color: Int) {
        path.reset()
        path.moveTo(0f, h)
        var x = 0f
        while (x <= w + 16f) {
            path.lineTo(x, hillY(x, base, amp, k, ph))
            x += 16f
        }
        path.lineTo(w, h)
        path.close()
        paint.shader = null
        paint.color = color
        c.drawPath(path, paint)
    }

    private fun glowAt(c: Canvas, cx: Float, cy: Float, r: Float, alpha: Int = 255) {
        paint.shader = glow
        paint.alpha = alpha
        c.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }

    private fun disc(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Int = 255) {
        paint.shader = null
        paint.color = color
        paint.alpha = alpha
        c.drawCircle(cx, cy, r, paint)
    }

    private fun drawStars(c: Canvas, t: Float, maxY: Float, count: Int, strength: Float) {
        val n = minOf(count, stars.size / 3)
        for (i in 0 until n) {
            val tw = 0.5f + 0.5f * sin(t * 1.5f + stars[i * 3 + 2] * TAU)
            disc(
                c, stars[i * 3] * w, stars[i * 3 + 1] * h * maxY,
                (0.7f + stars[i * 3 + 2] * 1.1f) * u, Color.WHITE,
                (50 + 180 * tw * strength).toInt().coerceIn(0, 255)
            )
        }
    }

    private fun cloud(c: Canvas, cx: Float, cy: Float, r: Float, alpha: Int) {
        disc(c, cx, cy, r, Color.WHITE, alpha)
        disc(c, cx + r * 0.95f, cy + r * 0.18f, r * 0.8f, Color.WHITE, alpha)
        disc(c, cx - r * 0.95f, cy + r * 0.22f, r * 0.7f, Color.WHITE, alpha)
        disc(c, cx + r * 0.25f, cy - r * 0.4f, r * 0.75f, Color.WHITE, alpha)
        paint.color = Color.WHITE
        paint.alpha = alpha
        rect.set(cx - r * 1.55f, cy + r * 0.1f, cx + r * 1.65f, cy + r * 0.85f)
        c.drawRoundRect(rect, r * 0.4f, r * 0.4f, paint)
    }

    private fun drawClouds(c: Canvas, t: Float, count: Int, topFrac: Float, spanFrac: Float, alpha: Int) {
        val n = minOf(count, clouds.size / 4)
        for (i in 0 until n) {
            val speed = (4f + 6f * clouds[i * 4 + 2]) * u
            val span = w + 240f * u
            val x = ((clouds[i * 4] * span + t * speed) % span) - 120f * u
            val y = (topFrac + spanFrac * clouds[i * 4 + 1]) * h
            cloud(c, x, y, (16f + 16f * clouds[i * 4 + 3]) * u, alpha)
        }
    }

    private fun pine(c: Canvas, x: Float, baseY: Float, height: Float, color: Int, snow: Boolean) {
        val tw = height * 0.34f
        paint.shader = null
        paint.color = color
        rect.set(x - tw * 0.06f, baseY - height * 0.12f, x + tw * 0.06f, baseY)
        c.drawRect(rect, paint)
        for (k in 0..2) {
            val top = baseY - height + k * height * 0.22f
            val bottom = baseY - height * 0.1f - (2 - k) * height * 0.2f
            val half = tw * (0.5f + 0.25f * k)
            path.reset()
            path.moveTo(x, top)
            path.lineTo(x - half, bottom)
            path.lineTo(x + half, bottom)
            path.close()
            paint.color = color
            c.drawPath(path, paint)
            if (snow) {
                path.reset()
                path.moveTo(x, top)
                path.lineTo(x - half * 0.45f, top + (bottom - top) * 0.45f)
                path.lineTo(x + half * 0.45f, top + (bottom - top) * 0.45f)
                path.close()
                paint.color = col(0xFFDCE7F8)
                paint.alpha = 190
                c.drawPath(path, paint)
            }
        }
    }

    // ---------------------------------------------------------------- winter

    private fun winter(c: Canvas, t: Float) {
        drawStars(c, t, 0.6f, 70, 1f)
        glowAt(c, 0.74f * w, 0.16f * h, 0.3f * w)
        disc(c, 0.74f * w, 0.16f * h, 0.055f * w, col(0xFFF4F1D8))
        disc(c, 0.76f * w, 0.15f * h, 0.012f * w, col(0xFFDDD9BC))
        hill(c, 0.6f * h, 0.022f * h, 1.3f, 0.4f, col(0xFF2A3768))
        // far pines on the far hills
        for (fx in floatArrayOf(0.04f, 0.12f, 0.2f, 0.82f, 0.9f, 0.97f)) {
            pine(c, fx * w, hillY(fx * w, 0.6f * h, 0.022f * h, 1.3f, 0.4f) + 8f * u, 0.07f * h, col(0xFF1B2754), false)
        }
        hill(c, 0.69f * h, 0.026f * h, 1.7f, 2f, col(0xFF3B4A86))
        for (fx in floatArrayOf(0.1f, 0.18f, 0.88f, 0.95f)) {
            pine(c, fx * w, hillY(fx * w, 0.69f * h, 0.026f * h, 1.7f, 2f) + 10f * u, 0.1f * h, col(0xFF172245), true)
        }
        hill(c, 0.79f * h, 0.012f * h, 1.1f, 4.1f, col(0xFFDCE7F8))

        // cabin on the near ground
        val cx = 0.34f * w
        val gy = hillY(cx, 0.79f * h, 0.012f * h, 1.1f, 4.1f) + 0.03f * h
        val s = 0.15f * w
        // light spilling from the window onto the snow
        val flick = 0.78f + 0.12f * sin(t * 7f) + 0.1f * sin(t * 13.3f)
        paint.shader = windowGlow
        paint.alpha = (255 * flick).toInt().coerceIn(0, 255)
        c.drawCircle(0.34f * w, 0.8f * h, 0.22f * w, paint)
        paint.shader = null
        // body
        paint.color = col(0xFF3B2416)
        rect.set(cx - s, gy - 0.8f * s, cx + s, gy)
        c.drawRect(rect, paint)
        // logs
        paint.color = col(0xFF2A190F)
        for (i in 1..3) {
            c.drawRect(cx - s, gy - 0.8f * s + i * 0.2f * s - 1.2f * u, cx + s, gy - 0.8f * s + i * 0.2f * s, paint)
        }
        // chimney
        paint.color = col(0xFF2A190F)
        rect.set(cx + 0.55f * s, gy - 1.75f * s, cx + 0.85f * s, gy - 1.1f * s)
        c.drawRect(rect, paint)
        // roof: snow on top of a dark roof
        path.reset()
        path.moveTo(cx - 1.25f * s, gy - 0.78f * s)
        path.lineTo(cx, gy - 1.8f * s)
        path.lineTo(cx + 1.25f * s, gy - 0.78f * s)
        path.close()
        paint.color = col(0xFFEFF5FF)
        c.drawPath(path, paint)
        path.reset()
        path.moveTo(cx - 1.12f * s, gy - 0.78f * s)
        path.lineTo(cx, gy - 1.64f * s)
        path.lineTo(cx + 1.12f * s, gy - 0.78f * s)
        path.close()
        paint.color = col(0xFF2A1A12)
        c.drawPath(path, paint)
        // window (flickering) and door
        paint.color = col(0xFFFFC15A)
        paint.alpha = (255 * (0.75f + 0.25f * flick)).toInt().coerceIn(0, 255)
        rect.set(cx - 0.7f * s, gy - 0.6f * s, cx - 0.25f * s, gy - 0.25f * s)
        c.drawRect(rect, paint)
        paint.color = col(0xFF1B0F08)
        rect.set(cx + 0.2f * s, gy - 0.55f * s, cx + 0.55f * s, gy)
        c.drawRect(rect, paint)
        // smoke
        for (i in 0..5) {
            val ph = (t * 0.22f + i / 6f) % 1f
            disc(
                c, cx + 0.7f * s + sin(ph * 6f + i) * s * 0.14f, gy - 1.75f * s - ph * s * 2.2f,
                s * (0.07f + 0.17f * ph), col(0xFFD8DDEA), (90 * (1f - ph)).toInt()
            )
        }
        // snowman
        val sx = 0.74f * w
        val sgy = hillY(sx, 0.79f * h, 0.012f * h, 1.1f, 4.1f) + 0.04f * h
        val snow = col(0xFFF8FBFF)
        disc(c, sx, sgy - 0.042f * w, 0.046f * w, snow)
        disc(c, sx, sgy - 0.1f * w, 0.033f * w, snow)
        disc(c, sx, sgy - 0.142f * w, 0.024f * w, snow)
        paint.color = col(0xFFFF8A3D)
        path.reset()
        path.moveTo(sx, sgy - 0.142f * w)
        path.lineTo(sx + 0.03f * w, sgy - 0.138f * w)
        path.lineTo(sx, sgy - 0.133f * w)
        path.close()
        c.drawPath(path, paint)
        disc(c, sx - 0.008f * w, sgy - 0.148f * w, 0.0028f * w, col(0xFF1B2754))
        disc(c, sx + 0.008f * w, sgy - 0.148f * w, 0.0028f * w, col(0xFF1B2754))
        // near pines
        pine(c, 0.07f * w, gy + 0.04f * h, 0.17f * h, col(0xFF10203F), true)
        pine(c, 0.94f * w, gy + 0.05f * h, 0.19f * h, col(0xFF10203F), true)
    }

    // ---------------------------------------------------------------- autumn

    private fun autumn(c: Canvas, t: Float) {
        drawStars(c, t, 0.3f, 24, 0.7f)
        glowAt(c, 0.68f * w, 0.6f * h, 0.45f * w)
        disc(c, 0.68f * w, 0.6f * h, 0.13f * w, col(0xFFFFE3A3))
        hill(c, 0.62f * h, 0.025f * h, 1.2f, 0.8f, col(0xFF6B2D4F))
        hill(c, 0.7f * h, 0.03f * h, 1.6f, 2.4f, col(0xFF4A2038))
        hill(c, 0.8f * h, 0.015f * h, 1f, 4f, col(0xFF2A1426))
        // trees on the near hill: dark trunk, warm canopy
        val xs = floatArrayOf(0.14f, 0.5f, 0.86f)
        val sizes = floatArrayOf(1f, 0.75f, 1.15f)
        for (i in xs.indices) {
            val tx = xs[i] * w
            val gy = hillY(tx, 0.8f * h, 0.015f * h, 1f, 4f) + 0.03f * h
            val sc = sizes[i]
            paint.shader = null
            paint.color = col(0xFF1B0D18)
            rect.set(tx - 4f * u * sc, gy - 0.12f * h * sc, tx + 4f * u * sc, gy)
            c.drawRect(rect, paint)
            val sway = sin(t * 0.7f + i) * 1.5f * u
            disc(c, tx + sway, gy - 0.16f * h * sc, 38f * u * sc, col(0xFFD9622B))
            disc(c, tx - 26f * u * sc + sway, gy - 0.13f * h * sc, 26f * u * sc, col(0xFFC24A22))
            disc(c, tx + 28f * u * sc + sway, gy - 0.14f * h * sc, 28f * u * sc, col(0xFFE8A33D))
            disc(c, tx + sway, gy - 0.2f * h * sc, 26f * u * sc, col(0xFFEFB74F))
        }
    }

    // ---------------------------------------------------------------- spring

    private fun spring(c: Canvas, t: Float) {
        glowAt(c, 0.78f * w, 0.14f * h, 0.34f * w)
        disc(c, 0.78f * w, 0.14f * h, 0.06f * w, col(0xFFFFF3B0))
        drawClouds(c, t, 4, 0.08f, 0.3f, 235)
        hill(c, 0.62f * h, 0.03f * h, 1.1f, 0.3f, col(0xFF9FD6A0))
        hill(c, 0.7f * h, 0.03f * h, 1.5f, 2.2f, col(0xFF6FBF73))
        hill(c, 0.79f * h, 0.015f * h, 1f, 4f, col(0xFF4FA55A))
        // flowers on the near hill
        val n = flowers.size / 4
        for (i in 0 until n) {
            val fx = flowers[i * 4] * w
            val top = hillY(fx, 0.79f * h, 0.015f * h, 1f, 4f)
            val fy = top + 8f * u + flowers[i * 4 + 1] * (h - top - 16f * u)
            val color = when ((flowers[i * 4 + 2] * 3f).toInt()) {
                0 -> Color.WHITE
                1 -> col(0xFFFF8FB1)
                else -> col(0xFFFFD84D)
            }
            disc(c, fx, fy, (1.8f + 1.8f * flowers[i * 4 + 3]) * u, color)
        }
        // blossoming tree
        val tx = 0.2f * w
        val gy = hillY(tx, 0.79f * h, 0.015f * h, 1f, 4f) + 0.02f * h
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = col(0xFF6B4A3A)
        paint.strokeWidth = 8f * u
        c.drawLine(tx, gy, tx + 4f * u, gy - 0.15f * h, paint)
        paint.strokeWidth = 4f * u
        c.drawLine(tx + 3f * u, gy - 0.1f * h, tx - 26f * u, gy - 0.17f * h, paint)
        c.drawLine(tx + 4f * u, gy - 0.12f * h, tx + 32f * u, gy - 0.19f * h, paint)
        paint.style = Paint.Style.FILL
        val sway = sin(t * 0.8f) * 1.5f * u
        disc(c, tx + 4f * u + sway, gy - 0.2f * h, 40f * u, col(0xFFFFB7CE))
        disc(c, tx - 30f * u + sway, gy - 0.18f * h, 28f * u, col(0xFFFF9EBD))
        disc(c, tx + 38f * u + sway, gy - 0.2f * h, 30f * u, col(0xFFFFB7CE))
        disc(c, tx + 4f * u + sway, gy - 0.25f * h, 28f * u, col(0xFFFFC9DA))
    }

    // ---------------------------------------------------------------- summer

    private fun summer(c: Canvas, t: Float) {
        glowAt(c, 0.5f * w, 0.2f * h, 0.42f * w, (200 + 40 * sin(t * 0.8f)).toInt().coerceIn(0, 255))
        // slowly turning rays
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 5f * u
        paint.color = col(0xFFFFF59A)
        paint.alpha = 90
        for (i in 0 until 12) {
            val a = t * 0.12f + i * TAU / 12f
            c.drawLine(
                0.5f * w + cos(a) * 0.12f * w, 0.2f * h + sin(a) * 0.12f * w,
                0.5f * w + cos(a) * 0.19f * w, 0.2f * h + sin(a) * 0.19f * w, paint
            )
        }
        paint.style = Paint.Style.FILL
        disc(c, 0.5f * w, 0.2f * h, 0.085f * w, col(0xFFFFF176))
        drawClouds(c, t, 4, 0.06f, 0.3f, 240)
        // distant island, then the sea in front of its foot
        hill(c, 0.64f * h, 0.012f * h, 0.8f, 1f, col(0xFF2E8B73))
        paint.shader = sea
        paint.alpha = 255
        c.drawRect(0f, 0.645f * h, w, 0.84f * h, paint)
        paint.shader = null
        // moving wave lines
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * u
        paint.color = Color.WHITE
        for (k in 1..5) {
            val y0 = 0.64f * h + k * (0.16f * h) / 6f
            paint.alpha = 40 + k * 14
            path.reset()
            var x = 0f
            path.moveTo(0f, y0)
            while (x <= w + 20f) {
                path.lineTo(x, y0 + 3f * u * sin(x / w * TAU * 3f + t * (0.8f + k * 0.15f) + k))
                x += 20f
            }
            c.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        // sand
        hill(c, 0.82f * h, 0.012f * h, 1.2f, 1.2f, col(0xFFF2D49B))
        hill(c, 0.9f * h, 0.01f * h, 0.9f, 3.1f, col(0xFFE8C07E))
        // palm tree
        val px = 0.82f * w
        val py = hillY(px, 0.82f * h, 0.012f * h, 1.2f, 1.2f) + 0.02f * h
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 9f * u
        paint.color = col(0xFF7A5230)
        path.reset()
        path.moveTo(px, py)
        path.quadTo(px - 18f * u, py - 0.1f * h, px - 6f * u, py - 0.2f * h)
        c.drawPath(path, paint)
        val tx = px - 6f * u
        val ty = py - 0.2f * h
        paint.strokeWidth = 5f * u
        paint.color = col(0xFF2F9E5B)
        for (i in 0 until 6) {
            val a = -PI.toFloat() / 2f + (i - 2.5f) * 0.55f + sin(t * 1.1f + i) * 0.04f
            val ex = tx + cos(a) * 0.12f * w
            val ey = ty + sin(a) * 0.09f * w + 0.04f * w
            path.reset()
            path.moveTo(tx, ty)
            path.quadTo(tx + cos(a) * 0.07f * w, ty + sin(a) * 0.09f * w - 0.02f * w, ex, ey)
            c.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        // birds
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * u
        paint.color = col(0xFF2B4B6B)
        for (i in 0..2) {
            val span = w + 80f * u
            val bx = ((i * 0.37f * span + t * (14f + 5f * i) * u) % span) - 40f * u
            val by = (0.3f + 0.06f * i) * h + sin(t * 0.9f + i) * 6f * u
            val flap = 4f * u * sin(t * 6f + i * 2f)
            path.reset()
            path.moveTo(bx - 7f * u, by - flap)
            path.lineTo(bx, by)
            path.lineTo(bx + 7f * u, by - flap)
            c.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
    }

    // ---------------------------------------------------------------- particles

    private fun drawParticles(c: Canvas, t: Float, dt: Float) {
        for (p in particles) {
            if (season != Season.SUMMER) {
                p.y += p.speed * dt
                p.rot += p.spin * dt
                if (p.y > h + 24f * u) resetParticle(p, false)
            }
            val px = p.x + sin(t * 0.9f + p.phase) * p.drift
            when (season) {
                Season.WINTER -> disc(c, px, p.y, p.size, Color.WHITE, (140 + 100 * p.depth).toInt())
                Season.AUTUMN, Season.SPRING -> {
                    c.save()
                    c.translate(px, p.y)
                    c.rotate(p.rot * 57.29578f + sin(t + p.phase) * 25f)
                    rect.set(-p.size, -p.size * 0.5f, p.size, p.size * 0.5f)
                    paint.shader = null
                    paint.color = p.color
                    c.drawOval(rect, paint)
                    c.restore()
                }
                Season.SUMMER -> {
                    val a = 0.5f + 0.5f * sin(t * 2.2f + p.phase)
                    disc(c, p.x, p.y, p.size, Color.WHITE, (200 * a).toInt().coerceIn(0, 255))
                }
            }
        }
        paint.alpha = 255
    }

    private companion object {
        val TAU = (2.0 * PI).toFloat()
        val LEAVES = intArrayOf(
            0xFFE8742C.toInt(), 0xFFD13F2A.toInt(), 0xFFF2B134.toInt(), 0xFFB5481F.toInt()
        )
        val PETALS = intArrayOf(
            0xFFFFC2D6.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFA6C1.toInt()
        )
    }
}
