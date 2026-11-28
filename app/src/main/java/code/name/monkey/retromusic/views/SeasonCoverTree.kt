package code.name.monkey.retromusic.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import code.name.monkey.retromusic.util.Season
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A tree that grows out from behind the player cover: the trunk comes out under it, two limbs wrap
 * around the cover and its progress ring, twigs carry blossoms / leaves / snow, and one limb reaches
 * the top-right corner. Drawn between the classic season scene and its falling particles.
 * All shapes are generated once per cover position (deterministic) and only sway while drawing.
 */
internal class SeasonCoverTree(private val season: Season) {

    private class Branch(val x: FloatArray, val y: FloatArray, val w0: Float, val w1: Float, val sway: Float, val phase: Float) {
        val n get() = x.size
    }

    /** kind: 0 flower, 1 summer leaf, 2 autumn leaf, 3 snow cap, 4 ice crystal, 5 bud, 6 white flower */
    private class Bloom(val branch: Int, val point: Int, val size: Float, val color: Int, val phase: Float, val kind: Int, val angle: Float)

    private val branches = ArrayList<Branch>()
    private val blooms = ArrayList<Bloom>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    private var w = 0f
    private var h = 0f
    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private var haveCover = false
    private var baseY = 0f

    private val wood = when (season) {
        Season.WINTER -> 0xFF7A6658.toInt()
        Season.AUTUMN -> 0xFF5A3D2E.toInt()
        else -> 0xFF5D4037.toInt()
    }
    private val woodLight = 0x66B08E78

    fun resize(width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        if (!haveCover) setDefaultCover()
        rebuild()
    }

    fun setCover(centerX: Float, centerY: Float, radius: Float) {
        if (radius <= 0f) return
        if (haveCover && kotlin.math.abs(centerX - cx) < 1f && kotlin.math.abs(centerY - cy) < 1f && kotlin.math.abs(radius - r) < 1f) return
        haveCover = true
        cx = centerX; cy = centerY; r = radius
        rebuild()
    }

    private fun setDefaultCover() {
        cx = w / 2f; cy = h * 0.36f; r = w * 0.36f
    }

    private fun rebuild() {
        branches.clear()
        blooms.clear()
        if (w <= 0f || h <= 0f || r <= 0f) return
        val rng = Random(11 + season.ordinal * 31)
        baseY = cy + r * 1.62f

        // trunk: comes out from behind the bottom of the cover
        addBranch(floatArrayOf(cx, cx + r * 0.02f, cx - r * 0.01f), floatArrayOf(baseY, cy + r * 1.3f, cy + r * 0.9f), r * 0.17f, r * 0.10f, 0.2f, rng)

        for (side in intArrayOf(-1, 1)) {
            val steps = 20
            val limbX = FloatArray(steps)
            val limbY = FloatArray(steps)
            val th1 = if (side > 0) PI * 1.02 else PI * 0.9
            for (i in 0 until steps) {
                val th = 0.28 + (th1 - 0.28) * i / (steps - 1)
                val rad = r * (1.08 + 0.05 * sin(th * 3 + side))
                limbX[i] = (cx + side * sin(th) * rad).toFloat()
                limbY[i] = (cy + cos(th) * rad).toFloat()
            }
            val limb = addBranch(limbX, limbY, r * 0.085f, r * 0.022f, 0.6f, rng)
            for (i in 1 until steps) {
                if (i % 2 == 0 || i == steps - 1) addTwig(limb, i, side, 0.16f, 0.34f, rng, 3)
                else if (rng.nextFloat() < 0.5f) addBloom(limb, i, r * 0.05f, rng)
            }
        }

        // long limb to the top-right corner, from the right side of the ring
        run {
            val th = PI * 0.62
            val sx = (cx + sin(th) * r * 1.1).toFloat()
            val sy = (cy + cos(th) * r * 1.1).toFloat()
            reach(sx, sy, w * 1.02f, h * 0.02f, r * 0.06f, 15, -1f, rng)
        }
        // a shorter one reaching out to the left edge, higher up
        run {
            val th = PI * 0.5
            val sx = (cx - sin(th) * r * 1.1).toFloat()
            val sy = (cy + cos(th) * r * 1.1).toFloat()
            reach(sx, sy, -w * 0.02f, cy - r * 0.55f, r * 0.045f, 11, 1f, rng)
        }
        // and one over the top left
        run {
            val th = PI * 0.82
            val sx = (cx - sin(th) * r * 1.1).toFloat()
            val sy = (cy + cos(th) * r * 1.1).toFloat()
            reach(sx, sy, w * 0.12f, h * 0.02f, r * 0.04f, 9, 1f, rng)
        }
    }

    private fun reach(sx: Float, sy: Float, tx: Float, ty: Float, w0: Float, steps: Int, bend: Float, rng: Random) {
        val xs = FloatArray(steps)
        val ys = FloatArray(steps)
        for (i in 0 until steps) {
            val f = i / (steps - 1f)
            val curve = sin(f * PI).toFloat() * r * 0.22f * bend
            xs[i] = sx + (tx - sx) * f + curve * 0.4f
            ys[i] = sy + (ty - sy) * f + curve
        }
        val b = addBranch(xs, ys, w0, w0 * 0.25f, 0.9f, rng)
        for (i in 2 until steps) {
            if (i % 2 == 0 || i == steps - 1) addTwig(b, i, if (tx > sx) 1 else -1, 0.16f, 0.3f, rng, 2)
        }
    }

    private fun addBranch(x: FloatArray, y: FloatArray, w0: Float, w1: Float, sway: Float, rng: Random): Int {
        branches.add(Branch(x, y, w0, w1, sway, rng.nextFloat() * 6.28f))
        return branches.size - 1
    }

    /** A twig leaving [bi] at point [i], pointing away from the cover, carrying foliage at its end. */
    private fun addTwig(bi: Int, i: Int, side: Int, minLen: Float, maxLen: Float, rng: Random, subs: Int) {
        val b = branches[bi]
        val px = b.x[i]
        val py = b.y[i]
        var ang = kotlin.math.atan2((py - cy).toDouble(), (px - cx).toDouble()) // away from the cover centre
        ang += (rng.nextFloat() - 0.5f) * 1.0
        val len = r * (minLen + rng.nextFloat() * (maxLen - minLen))
        val n = 4
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (k in 0 until n) {
            val f = k / (n - 1f)
            val a = ang + side * f * 0.35
            xs[k] = px + cos(a).toFloat() * len * f
            ys[k] = py + sin(a).toFloat() * len * f
        }
        val ti = addBranch(xs, ys, b.w1.coerceAtLeast(r * 0.012f) * 1.6f, r * 0.006f, 1.2f, rng)
        addBloom(ti, n - 1, r * 0.075f, rng)
        if (rng.nextFloat() < 0.7f) addBloom(ti, n - 2, r * 0.055f, rng)
        if (subs > 0 && rng.nextFloat() < 0.55f) {
            // a small fork from the middle of the twig
            val fx = xs[1]; val fy = ys[1]
            val a2 = ang + side * (0.5 + rng.nextFloat() * 0.6)
            val l2 = len * 0.55f
            val sxs = floatArrayOf(fx, fx + cos(a2).toFloat() * l2 * 0.5f, fx + cos(a2).toFloat() * l2)
            val sys = floatArrayOf(fy, fy + sin(a2).toFloat() * l2 * 0.5f, fy + sin(a2).toFloat() * l2)
            val si = addBranch(sxs, sys, r * 0.012f, r * 0.005f, 1.4f, rng)
            addBloom(si, 2, r * 0.06f, rng)
        }
    }

    private fun addBloom(bi: Int, pi: Int, size: Float, rng: Random) {
        val phase = rng.nextFloat() * 6.28f
        val angle = rng.nextFloat() * 360f
        when (season) {
            Season.SPRING -> {
                val flower = intArrayOf(0xFFF8BBD0.toInt(), 0xFFF48FB1.toInt(), 0xFFFFE0EC.toInt(), 0xFFFFFFFF.toInt())
                if (rng.nextFloat() < 0.25f) blooms.add(Bloom(bi, pi, size * 0.45f, 0xFFD81B60.toInt(), phase, 5, angle))
                else blooms.add(Bloom(bi, pi, size, flower[rng.nextInt(flower.size)], phase, 0, angle))
            }
            Season.SUMMER -> {
                val leaf = intArrayOf(0xFF2E7D32.toInt(), 0xFF43A047.toInt(), 0xFF66BB6A.toInt(), 0xFF81C784.toInt())
                for (k in 0 until 3) blooms.add(Bloom(bi, pi, size * 1.1f, leaf[rng.nextInt(leaf.size)], phase + k, 1, angle + k * 70f))
                if (rng.nextFloat() < 0.18f) blooms.add(Bloom(bi, pi, size * 0.7f, 0xFFFFFFFF.toInt(), phase, 6, angle))
            }
            Season.AUTUMN -> {
                val leaf = intArrayOf(0xFFE65100.toInt(), 0xFFF57C00.toInt(), 0xFFFFB300.toInt(), 0xFFBF360C.toInt(), 0xFFA1887F.toInt())
                if (rng.nextFloat() < 0.8f) blooms.add(Bloom(bi, pi, size * 1.15f, leaf[rng.nextInt(leaf.size)], phase, 2, angle))
                if (rng.nextFloat() < 0.4f) blooms.add(Bloom(bi, pi, size, leaf[rng.nextInt(leaf.size)], phase + 1f, 2, angle + 90f))
            }
            Season.WINTER -> {
                blooms.add(Bloom(bi, pi, size * 0.8f, 0xFFFFFFFF.toInt(), phase, 3, angle))
                if (rng.nextFloat() < 0.35f) blooms.add(Bloom(bi, pi, size * 0.9f, 0xFFD6EEFF.toInt(), phase, 4, angle))
            }
        }
    }

    private fun bx(b: Branch, i: Int, t: Float): Float =
        b.x[i] + sin(t * 0.8f + b.phase + i * 0.4f) * r * 0.014f * b.sway * (i / (b.n - 1f))

    private fun by(b: Branch, i: Int, t: Float): Float =
        b.y[i] + cos(t * 0.6f + b.phase + i * 0.5f) * r * 0.008f * b.sway * (i / (b.n - 1f))

    fun draw(c: Canvas, t: Float, dt: Float) {
        if (branches.isEmpty()) return
        drawMound(c)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (b in branches) {
            for (i in 0 until b.n - 1) {
                val f = i / (b.n - 1f)
                val wd = b.w0 + (b.w1 - b.w0) * f
                paint.strokeWidth = wd
                paint.color = wood
                c.drawLine(bx(b, i, t), by(b, i, t), bx(b, i + 1, t), by(b, i + 1, t), paint)
                if (wd > r * 0.03f) {
                    paint.strokeWidth = wd * 0.3f
                    paint.color = woodLight
                    c.drawLine(bx(b, i, t) - wd * 0.15f, by(b, i, t) - wd * 0.15f, bx(b, i + 1, t) - wd * 0.15f, by(b, i + 1, t) - wd * 0.15f, paint)
                }
            }
        }
        paint.style = Paint.Style.FILL
        for (m in blooms) {
            val b = branches[m.branch]
            val x = bx(b, m.point, t)
            val y = by(b, m.point, t)
            val s = m.size
            when (m.kind) {
                0 -> {
                    paint.color = m.color
                    for (k in 0 until 5) {
                        val a = (m.angle + k * 72f + sin(t * 0.7f + m.phase) * 6f) * PI.toFloat() / 180f
                        c.drawCircle(x + cos(a) * s * 0.55f, y + sin(a) * s * 0.55f, s * 0.5f, paint)
                    }
                    paint.color = 0xFFFFD54F.toInt()
                    c.drawCircle(x, y, s * 0.26f, paint)
                }
                5 -> { paint.color = m.color; c.drawCircle(x, y, s, paint) }
                6 -> {
                    paint.color = m.color
                    for (k in 0 until 5) {
                        val a = (m.angle + k * 72f) * PI.toFloat() / 180f
                        c.drawCircle(x + cos(a) * s * 0.5f, y + sin(a) * s * 0.5f, s * 0.35f, paint)
                    }
                    paint.color = 0xFFFFC107.toInt(); c.drawCircle(x, y, s * 0.22f, paint)
                }
                1, 2 -> {
                    paint.color = m.color
                    c.save()
                    c.translate(x, y)
                    c.rotate(m.angle + sin(t * 0.9f + m.phase) * 10f)
                    oval.set(0f, -s * 0.38f, s * 1.5f, s * 0.38f)
                    c.drawOval(oval, paint)
                    c.restore()
                }
                3 -> {
                    paint.color = m.color
                    oval.set(x - s, y - s * 0.75f, x + s, y - s * 0.05f)
                    c.drawOval(oval, paint)
                }
                4 -> {
                    paint.color = m.color
                    paint.alpha = (150 + 90 * sin(t * 1.6f + m.phase)).toInt().coerceIn(60, 240)
                    c.drawCircle(x, y, s * 0.28f, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = s * 0.08f
                    for (k in 0 until 3) {
                        val a = (m.angle + k * 60f) * PI.toFloat() / 180f
                        c.drawLine(x - cos(a) * s * 0.55f, y - sin(a) * s * 0.55f, x + cos(a) * s * 0.55f, y + sin(a) * s * 0.55f, paint)
                    }
                    paint.style = Paint.Style.FILL
                    paint.alpha = 255
                }
            }
        }
    }

    /** The ground at the foot of the trunk: grass and flowers, leaves, or snow. */
    private fun drawMound(c: Canvas) {
        val color = when (season) {
            Season.SPRING -> 0xCC5B8C3A.toInt()
            Season.SUMMER -> 0xCC3E7B27.toInt()
            Season.AUTUMN -> 0xCC9A5B2E.toInt()
            Season.WINTER -> 0xE6EAF2F8.toInt()
        }
        paint.style = Paint.Style.FILL
        paint.color = color
        oval.set(cx - r * 0.85f, baseY - r * 0.14f, cx + r * 0.85f, baseY + r * 0.16f)
        c.drawOval(oval, paint)
        paint.color = Color.argb(70, 255, 255, 255)
        oval.set(cx - r * 0.55f, baseY - r * 0.12f, cx + r * 0.55f, baseY + r * 0.02f)
        c.drawOval(oval, paint)
    }
}
