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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.model.Song
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin

/**
 * A wall of album covers drawn at an angle, darkened, slowly scrolling.
 * The covers sit in tilted columns that drift downwards, with rounded corners and a vignette
 * that is darker on the left. Everything is drawn in [onDraw] from a few small bitmaps, so it
 * stays cheap; the animation stops when the view is hidden, detached, or when the speed is 0
 * or the system has animations turned off.
 */
class TiltedCoverGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var covers: List<Bitmap> = emptyList()
    private var coverSongs: List<Song> = emptyList()

    /** Called with the songs on the wall and the index of the one that was tapped. */
    var onSongClick: ((songs: List<Song>, index: Int) -> Unit)? = null

    /** Called with the songs on the wall and the index of the one that was long-pressed. */
    var onSongLongClick: ((songs: List<Song>, hit: WallHit) -> Unit)? = null

    /** A tap on a card of an opened stack: the songs of that stack and the position of the card. */
    var onStackMemberClick: ((songs: List<Song>, position: Int) -> Unit)? = null

    /** Stacks on the wall: the song shown on top (its id) -> all songs of the stack, top first. */
    private var stacks: Map<Long, List<Song>> = emptyMap()
    private var memberCovers: Map<Long, List<Bitmap>> = emptyMap()

    /** Songs ticked for grouping: they get a coloured frame and a check mark. */
    var selectedIds: Set<Long> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    // The opened stack: its cards spread out over the wall.
    private var expandedSongs: List<Song> = emptyList()
    private var expandedBitmaps: List<Bitmap> = emptyList()
    private val expandedFrom = PointF()
    private var expandedFromSize = 0f
    private var expandProgress = 0f
    private var expandAnimator: ValueAnimator? = null
    private val expandedRects = ArrayList<RectF>()
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint()

    private val isExpanded get() = expandedSongs.isNotEmpty()

    /** A cover under the finger: its index and where it is drawn, in this view's coordinates. */
    class WallHit(val index: Int, val centerX: Float, val centerY: Float, val half: Float)

    private var frozen = false

    /** Stops the drift while a menu is open, so the cover stays under the menu. */
    fun setFrozen(value: Boolean) {
        if (frozen == value) return
        frozen = value
        lastFrameNanos = 0L
        invalidate()
    }

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = covers.isNotEmpty()

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isExpanded) return handleExpandedTap(e.x, e.y)
                if (coverSongs.size != covers.size) return false // only the saved picture is shown
                val hit = findHit(e.x, e.y) ?: return false
                playSoundEffect(android.view.SoundEffectConstants.CLICK)
                handleTap(hit)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isExpanded || coverSongs.size != covers.size) return
                val hit = findHit(e.x, e.y) ?: return
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onSongLongClick?.invoke(coverSongs, hit)
            }
        }
    )
    private val targets = ArrayList<CustomTarget<Bitmap>>()
    private var loadedKey: List<Long> = emptyList()
    private var loading = false
    private var generation = 0

    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint()
    private val fadePaint = Paint()
    private val src = Rect()
    private val dst = RectF()

    private var angleDeg = 15f
    private var darkness = 0.5f
    private var speedDpPerSec = 20f

    private var offsetPx = 0f
    private var lastFrameNanos = 0L
    private val density = resources.displayMetrics.density

    /** Applies the look settings. Safe to call repeatedly. */
    fun configure(angleDegrees: Int, darknessPercent: Int, speedDpPerSecond: Int) {
        angleDeg = angleDegrees.coerceIn(0, 45).toFloat()
        darkness = (darknessPercent.coerceIn(0, 90)) / 100f
        speedDpPerSec = speedDpPerSecond.coerceAtLeast(0).toFloat()
        applyShade(width.toFloat())
        lastFrameNanos = 0L
        invalidate()
    }

    private var restoredFromDisk = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restoreFromDisk()
    }

    private val cacheDir get() = java.io.File(context.filesDir, "wall_cache")

    /**
     * The wall is saved when it has finished loading and drawn again at once on the next start,
     * so it does not stay black while the library and the covers load. Taps are ignored until the
     * real songs arrive (coverSongs is empty meanwhile).
     */
    private fun restoreFromDisk() {
        if (restoredFromDisk || covers.isNotEmpty()) return
        restoredFromDisk = true
        roundExecutor.execute {
            val loadedFromDisk = runCatching {
                val ids = java.io.File(cacheDir, "ids.txt").readLines().mapNotNull { it.toLongOrNull() }
                ids.indices.mapNotNull {
                    android.graphics.BitmapFactory.decodeFile(java.io.File(cacheDir, "$it.png").path)
                }.takeIf { it.size == ids.size && it.isNotEmpty() }
            }.getOrNull() ?: return@execute
            post {
                if (covers.isEmpty() && coverSongs.isEmpty()) {
                    covers = loadedFromDisk
                    invalidate()
                }
            }
        }
    }

    private fun saveToDisk(ids: List<Long>, bitmaps: List<Bitmap>) {
        val snapshot = bitmaps.toList()
        roundExecutor.execute {
            runCatching {
                val dir = cacheDir
                dir.deleteRecursively()
                dir.mkdirs()
                snapshot.forEachIndexed { i, b ->
                    java.io.File(dir, "$i.png").outputStream().use {
                        b.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                java.io.File(dir, "ids.txt").writeText(ids.joinToString("\n"))
            }
        }
    }

    /**
     * Loads the covers of [songs]. [stackMap] says which of them are stacks (top song id -> all songs);
     * a stack is drawn as one pile of covers. Does nothing if the same wall is already shown.
     */
    fun setCovers(songs: List<Song>, stackMap: Map<Long, List<Song>> = emptyMap()) {
        val key = songs.map { it.id } +
            stackMap.flatMap { listOf(-1L, it.key) + it.value.map { s -> s.id } }
        if (key == loadedKey) return
        loadedKey = key
        clearTargets()
        closeStackNow()
        if (songs.isEmpty()) {
            loading = false
            covers = emptyList()
            coverSongs = emptyList()
            stacks = emptyMap()
            memberCovers = emptyMap()
            invalidate()
            return
        }
        loading = true
        val size = (120 * density).toInt()
        val myGeneration = ++generation
        // every song that needs a cover, stack members included
        val flat = ArrayList<Song>()
        songs.forEach { s -> flat.addAll(stackMap[s.id] ?: listOf(s)) }
        val loaded = arrayOfNulls<Bitmap>(flat.size)
        var finished = 0
        fun onOneFinished() {
            finished++
            if (finished == flat.size) {
                loading = false
                val outCovers = ArrayList<Bitmap>()
                val outSongs = ArrayList<Song>()
                val members = HashMap<Long, List<Bitmap>>()
                var pos = 0
                for (s in songs) {
                    val group = stackMap[s.id]
                    if (group == null) {
                        loaded[pos]?.let { outCovers.add(it); outSongs.add(s) }
                        pos++
                    } else {
                        val bitmaps = group.indices.mapNotNull { loaded[pos + it] }
                        pos += group.size
                        if (bitmaps.isNotEmpty()) {
                            outCovers.add(if (bitmaps.size == 1) bitmaps[0] else stackBitmap(bitmaps))
                            outSongs.add(s)
                            members[s.id] = bitmaps
                        }
                    }
                }
                covers = outCovers
                coverSongs = outSongs
                stacks = stackMap.filterKeys { it in members }
                memberCovers = members
                invalidate()
                saveToDisk(coverSongs.map { it.id }, covers)
            }
        }
        flat.forEachIndexed { index, song ->
            val cached = roundedCache.get(song.id)
            if (cached != null) {
                loaded[index] = cached
                onOneFinished()
                return@forEachIndexed
            }
            val target = object : CustomTarget<Bitmap>(size, size) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    // Glide may reuse its own bitmap, so take a quick copy here and do the
                    // slower rounding on a background thread, not on the main thread.
                    val copy = resource.copy(Bitmap.Config.ARGB_8888, false)
                    if (copy == null) {
                        onOneFinished()
                        return
                    }
                    roundExecutor.execute {
                        val rounded = roundedCopy(copy)
                        copy.recycle()
                        roundedCache.put(song.id, rounded)
                        post {
                            if (myGeneration == generation) {
                                loaded[index] = rounded
                                onOneFinished()
                            }
                        }
                    }
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    onOneFinished()
                }

                override fun onLoadCleared(placeholder: Drawable?) = Unit
            }
            targets.add(target)
            Glide.with(context.applicationContext)
                .asBitmap()
                .load(RetroGlideExtension.getSongModel(song))
                .centerCrop()
                .into(target)
        }
    }

    /** A pile of up to four covers, slightly turned, with a count badge: how a stack looks on the wall. */
    private fun stackBitmap(bitmaps: List<Bitmap>): Bitmap {
        val size = bitmaps[0].width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
        val angles = floatArrayOf(0f, -9f, 8f, -16f)
        val shifts = floatArrayOf(0f, -0.04f, 0.04f, -0.02f)
        val card = size * 0.78f
        val shown = min(4, bitmaps.size)
        for (k in shown - 1 downTo 0) {
            val saved = canvas.save()
            canvas.rotate(angles[k], size / 2f, size / 2f)
            val left = (size - card) / 2f + shifts[k] * size
            val top = (size - card) / 2f
            val box = RectF(left, top, left + card, top + card)
            val r = card * 0.1f
            val frame = RectF(box)
            frame.inset(-size * 0.012f, -size * 0.012f)
            canvas.drawRoundRect(frame, r, r, border)
            val bm = bitmaps[k]
            canvas.drawBitmap(bm, Rect(0, 0, bm.width, bm.height), box, paint)
            canvas.restoreToCount(saved)
        }
        // count badge
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThemeStore.accentColor(context) }
        val br = size * 0.13f
        canvas.drawCircle(size * 0.86f, size * 0.86f, br, badge)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = br * 1.25f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText(bitmaps.size.toString(), size * 0.86f, size * 0.86f + br * 0.42f, text)
        return out
    }

    private fun roundedCopy(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val radius = min(source.width, source.height) * 0.1f
        canvas.drawRoundRect(
            RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), radius, radius, paint
        )
        return out
    }

    private fun clearTargets() {
        val glide = Glide.with(context.applicationContext)
        targets.forEach { glide.clear(it) }
        targets.clear()
    }

    /** Darker on the left, lighter on the right, like a soft vignette. */
    private fun applyShade(w: Float) {
        if (w <= 0f) return
        val left = Color.argb((darkness.coerceAtMost(1f) * 255).toInt(), 0, 0, 0)
        val right = Color.argb((darkness * 0.45f * 255).toInt(), 0, 0, 0)
        shadePaint.shader = LinearGradient(0f, 0f, w, 0f, left, right, Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val tv = TypedValue()
        val surface = if (context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) {
            tv.data
        } else {
            Color.BLACK
        }
        applyShade(w.toFloat())
        fadePaint.shader = LinearGradient(
            0f, h * 0.7f, 0f, h.toFloat(),
            Color.TRANSPARENT, surface, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val list = covers
        if (list.isNotEmpty() && w > 0f && h > 0f) {
            drawGrid(canvas, list, w, h)
        }
        canvas.drawRect(0f, 0f, w, h, shadePaint)
        canvas.drawRect(0f, h * 0.7f, w, h, fadePaint)
        if (isExpanded) drawExpanded(canvas, w, h)

        if (shouldAnimate(list)) {
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) {
                val dt = ((now - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.1f)
                offsetPx += speedDpPerSec * density * dt
            }
            lastFrameNanos = now
            postInvalidateOnAnimation()
        } else {
            lastFrameNanos = 0L
        }
    }

    /** Layout numbers shared by drawing and tap detection. */
    private class Grid(
        val tile: Float,
        val step: Float,
        val cells: Int,
        val startX: Float,
        val startY: Float
    )

    private fun gridFor(w: Float, h: Float): Grid {
        // Tiles are sized from the short side so the wall looks the same in portrait and landscape.
        val tile = min(w, h) / 3.2f
        val step = tile + 6f * density
        val diagonal = hypot(w, h)
        return Grid(
            tile = tile,
            step = step,
            cells = ceil(diagonal / step).toInt() + 2,
            startX = w / 2f - diagonal / 2f,
            startY = h / 2f - diagonal / 2f
        )
    }

    /** Every column drifts down; [col] only changes where its covers start. */
    private fun columnShiftAndFrac(step: Float): Pair<Int, Float> {
        val shift = floor(offsetPx / step).toInt()
        return shift to (offsetPx - shift * step)
    }

    private fun drawGrid(canvas: Canvas, list: List<Bitmap>, w: Float, h: Float) {
        val g = gridFor(w, h)
        val (shift, frac) = columnShiftAndFrac(g.step)
        val saved = canvas.save()
        canvas.rotate(-angleDeg, w / 2f, h / 2f)
        for (col in 0 until g.cells) {
            val x = g.startX + col * g.step
            for (row in 0 until g.cells) {
                val y = g.startY + (row - 1) * g.step + frac
                val bitmap = list[Math.floorMod(row - shift + col * 3, list.size)]
                src.set(0, 0, bitmap.width, bitmap.height)
                dst.set(x, y, x + g.tile, y + g.tile)
                canvas.drawBitmap(bitmap, src, dst, tilePaint)
                if (selectedIds.isNotEmpty() && coverSongs.size == list.size) {
                    val id = coverSongs[Math.floorMod(row - shift + col * 3, list.size)].id
                    if (id in selectedIds) drawSelectionMark(canvas, dst)
                }
            }
        }
        canvas.restoreToCount(saved)
    }

    private fun drawSelectionMark(canvas: Canvas, box: RectF) {
        val accent = ThemeStore.accentColor(context)
        accentPaint.style = Paint.Style.STROKE
        accentPaint.strokeWidth = 3.5f * density
        accentPaint.color = accent
        val r = box.width() * 0.1f
        canvas.drawRoundRect(box, r, r, accentPaint)
        accentPaint.style = Paint.Style.FILL
        val cx = box.right - box.width() * 0.17f
        val cy = box.top + box.width() * 0.17f
        val cr = box.width() * 0.11f
        canvas.drawCircle(cx, cy, cr, accentPaint)
        accentPaint.style = Paint.Style.STROKE
        accentPaint.strokeWidth = 2.2f * density
        accentPaint.color = Color.WHITE
        accentPaint.strokeCap = Paint.Cap.ROUND
        val p = android.graphics.Path().apply {
            moveTo(cx - cr * 0.45f, cy)
            lineTo(cx - cr * 0.1f, cy + cr * 0.38f)
            lineTo(cx + cr * 0.5f, cy - cr * 0.36f)
        }
        canvas.drawPath(p, accentPaint)
    }

    /** A tap on a cover: a stack opens (unless covers are being ticked), anything else is passed on. */
    private fun handleTap(hit: WallHit) {
        val song = coverSongs.getOrNull(hit.index) ?: return
        val members = memberCovers[song.id]
        val group = stacks[song.id]
        if (group != null && members != null && selectedIds.isEmpty()) {
            openStack(group, members, hit)
        } else {
            onSongClick?.invoke(coverSongs, hit.index)
        }
    }

    private fun openStack(group: List<Song>, bitmaps: List<Bitmap>, hit: WallHit) {
        expandedSongs = group.take(bitmaps.size)
        expandedBitmaps = bitmaps
        expandedFrom.set(hit.centerX, hit.centerY)
        expandedFromSize = hit.half * 2f
        animateExpand(0f, 1f)
    }

    private fun animateExpand(from: Float, to: Float, onEnd: (() -> Unit)? = null) {
        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                expandProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** Folds the opened stack back into its place. */
    fun closeStack() {
        if (!isExpanded) return
        animateExpand(expandProgress, 0f) { closeStackNow() }
    }

    private fun closeStackNow() {
        expandAnimator?.cancel()
        expandedSongs = emptyList()
        expandedBitmaps = emptyList()
        expandedRects.clear()
        expandProgress = 0f
        lastFrameNanos = 0L
        invalidate()
    }

    private fun handleExpandedTap(x: Float, y: Float): Boolean {
        val index = expandedRects.indexOfFirst { it.contains(x, y) }
        if (index >= 0 && expandProgress > 0.9f) {
            playSoundEffect(android.view.SoundEffectConstants.CLICK)
            onStackMemberClick?.invoke(expandedSongs, index)
        }
        closeStack()
        return true
    }

    /** Draws the opened stack: a dark veil and its cards spread out around where the pile was. */
    private fun drawExpanded(canvas: Canvas, w: Float, h: Float) {
        val n = expandedBitmaps.size
        if (n == 0) return
        val t = expandProgress
        scrimPaint.color = Color.argb((150 * t).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, scrimPaint)
        val gap = 10f * density
        val pad = 16f * density
        val cols = if (n <= 3) n else if (n <= 6) 3 else 4
        val rows = ceil(n / cols.toFloat()).toInt()
        val e = min((w - 2 * pad - (cols - 1) * gap) / cols, (h * 0.8f - (rows - 1) * gap) / rows)
            .coerceAtMost(gridFor(w, h).tile * 1.1f)
        val totalW = cols * e + (cols - 1) * gap
        val totalH = rows * e + (rows - 1) * gap
        val left0 = (expandedFrom.x - totalW / 2f).coerceIn(pad, (w - pad - totalW).coerceAtLeast(pad))
        val top0 = (expandedFrom.y - totalH / 2f).coerceIn(pad, (h * 0.9f - totalH).coerceAtLeast(pad))
        expandedRects.clear()
        for (i in 0 until n) {
            val r = i / cols
            val c = i % cols
            // the last row is centred when it is not full
            val inRow = if (r == rows - 1) n - r * cols else cols
            val rowW = inRow * e + (inRow - 1) * gap
            val tx = left0 + (totalW - rowW) / 2f + c * (e + gap)
            val ty = top0 + r * (e + gap)
            val fromX = expandedFrom.x - expandedFromSize * 0.4f
            val fromY = expandedFrom.y - expandedFromSize * 0.4f
            val size = expandedFromSize * 0.8f + (e - expandedFromSize * 0.8f) * t
            val x = fromX + (tx - fromX) * t
            val y = fromY + (ty - fromY) * t
            val rect = RectF(x, y, x + size, y + size)
            expandedRects.add(RectF(tx, ty, tx + e, ty + e))
            val bm = expandedBitmaps[i]
            tilePaint.alpha = (255 * (0.35f + 0.65f * t)).toInt()
            canvas.drawBitmap(bm, Rect(0, 0, bm.width, bm.height), rect, tilePaint)
            tilePaint.alpha = 255
        }
    }

    /** Index in [coverSongs] of the cover under the touch point, or -1 for a gap / nothing. */
    private fun hitTest(touchX: Float, touchY: Float): Int = findHit(touchX, touchY)?.index ?: -1

    private fun findHit(touchX: Float, touchY: Float): WallHit? {
        val list = covers
        if (list.isEmpty()) return null
        val w = width.toFloat()
        val h = height.toFloat()
        val g = gridFor(w, h)
        // Undo the canvas rotation: rotate the touch point back around the centre.
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = touchX - w / 2f
        val dy = touchY - h / 2f
        val px = (w / 2f + dx * cos(rad) - dy * sin(rad)).toFloat()
        val py = (h / 2f + dx * sin(rad) + dy * cos(rad)).toFloat()

        val colF = (px - g.startX) / g.step
        val col = floor(colF).toInt()
        if (col < 0 || col >= g.cells) return null
        if ((colF - col) * g.step > g.tile) return null
        val (shift, frac) = columnShiftAndFrac(g.step)
        val rowF = (py - g.startY - frac) / g.step
        val rowMinus1 = floor(rowF).toInt()
        if ((rowF - rowMinus1) * g.step > g.tile) return null
        val row = rowMinus1 + 1
        if (row < 0 || row >= g.cells) return null
        val index = Math.floorMod(row - shift + col * 3, list.size)

        // Centre of that tile, rotated forward again so it matches where it is drawn.
        val tileCx = g.startX + col * g.step + g.tile / 2f
        val tileCy = g.startY + rowMinus1 * g.step + frac + g.tile / 2f
        val ox = tileCx - w / 2f
        val oy = tileCy - h / 2f
        val cx = (w / 2f + ox * cos(rad) + oy * sin(rad)).toFloat()
        val cy = (h / 2f - ox * sin(rad) + oy * cos(rad)).toFloat()
        return WallHit(index, cx, cy, g.tile / 2f)
    }

    /** For taps that land on the empty hero area above this view's scrolling content. */
    fun tapAtScreen(rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        if (isExpanded) return handleExpandedTap(rawX - loc[0], rawY - loc[1])
        val hit = findHit(rawX - loc[0], rawY - loc[1]) ?: return false
        if (coverSongs.size != covers.size) return false
        handleTap(hit)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return tapDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun shouldAnimate(list: List<Bitmap>): Boolean {
        if (frozen || isExpanded || speedDpPerSec <= 0f || list.isEmpty() || !isShown) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        resumeFrames()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        resumeFrames()
    }

    private fun resumeFrames() {
        lastFrameNanos = 0L
        if (isShown) invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        expandAnimator?.cancel()
        lastFrameNanos = 0L
        clearTargets()
        if (loading) {
            // The pending loads were cancelled; let the next setCovers call load them again.
            loading = false
            loadedKey = emptyList()
        }
    }

    private companion object {
        /** Rounded covers survive leaving and re-entering the home screen (about 24 MB at most). */
        val roundedCache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
        }
        val roundExecutor = Executors.newSingleThreadExecutor()
    }
}
