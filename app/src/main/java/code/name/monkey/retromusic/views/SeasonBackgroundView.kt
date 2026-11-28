package code.name.monkey.retromusic.views

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import code.name.monkey.retromusic.util.Season
import com.bumptech.glide.Glide

/**
 * Full-size background for the player: a looping, muted video (any video the device can decode) or an
 * animated image (GIF / WebP / picture), centre-cropped, with a light scrim so the controls stay readable.
 *
 * It uses a plain [MediaPlayer] on a [TextureView] on purpose: `VideoView` asks for audio focus when it starts
 * and would pause the music. Nothing here touches audio focus.
 */
class SeasonBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), TextureView.SurfaceTextureListener {

    /** The picked file, when the layer shows one. */
    var source: Uri? = null
        private set

    /** What is shown now ("file:<uri>" or "builtin:<season>"); null when nothing is. */
    var key: String? = null
        private set

    private var scene: SeasonSceneView? = null

    private var texture: TextureView? = null
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantPlaying = false
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // scrim on top of the picture
        addView(View(context).apply { setBackgroundColor(SCRIM) })
    }

    fun setSource(uri: Uri) {
        if (uri == source) return
        clearMedia()
        source = uri
        key = "file:$uri"
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        if (mime?.startsWith("video/") == true) showVideo() else showImage(uri)
    }

    /** Plays only while the player screen is open, so a hidden player costs no CPU or battery. */
    fun setActive(active: Boolean) {
        wantPlaying = active
        scene?.setRunning(active)
        val mp = player ?: return
        if (!prepared) return
        try {
            if (active) {
                if (!mp.isPlaying) mp.start()
            } else if (mp.isPlaying) {
                mp.pause()
            }
        } catch (e: IllegalStateException) {
            fail()
        }
    }

    /** Stops everything and frees the decoder. Call when the player view is destroyed. */
    fun release() {
        clearMedia()
        source = null
        key = null
    }

    /** The built-in, code-drawn animated scene of a season (used when no file was picked for it). */
    fun setScene(season: Season) {
        val newKey = "builtin:${season.name}"
        if (newKey == key) return
        clearMedia()
        source = null
        key = newKey
        val view = SeasonSceneView(context, season)
        scene = view
        addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        view.setRunning(wantPlaying)
    }

    /** The season-tree scene for [season] in its look for [state] (see [code.name.monkey.retromusic.util.SeasonTheme.treeState]). */
    fun setTreeScene(season: Season, state: Int) {
        val newKey = "tree:${season.name}:$state"
        if (newKey == key) return
        clearMedia()
        source = null
        key = newKey
        val view = SeasonSceneView(context, season, state)
        scene = view
        addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        view.setRunning(wantPlaying)
    }

    private fun showImage(uri: Uri) {
        val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        addView(iv, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        Glide.with(context).load(uri).centerCrop().into(iv)
    }

    private fun showVideo() {
        val tv = TextureView(context).apply { surfaceTextureListener = this@SeasonBackgroundView }
        texture = tv
        addView(tv, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun openPlayer() {
        val uri = source ?: return
        val target = surface ?: return
        releasePlayer()
        val mp = MediaPlayer()
        try {
            mp.setSurface(target)
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoWidth = w
                videoHeight = h
                applyCrop()
            }
            mp.setOnPreparedListener {
                prepared = true
                if (wantPlaying) it.start()
            }
            mp.setOnErrorListener { _, _, _ ->
                fail()
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            try {
                mp.release()
            } catch (ignored: Exception) {
            }
            player = null
            fail()
        }
    }

    /** TextureView stretches the video to the view; undo that and scale it up to cover (centre crop). */
    private fun applyCrop() {
        val tv = texture ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (videoWidth == 0 || videoHeight == 0 || vw == 0f || vh == 0f) return
        val scale = maxOf(vw / videoWidth, vh / videoHeight)
        val matrix = Matrix()
        matrix.setScale(videoWidth * scale / vw, videoHeight * scale / vh, vw / 2f, vh / 2f)
        tv.setTransform(matrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCrop()
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.setOnPreparedListener(null)
                it.setOnErrorListener(null)
                it.release()
            } catch (ignored: Exception) {
            }
        }
        player = null
        prepared = false
    }

    private fun clearMedia() {
        scene?.setRunning(false)
        scene = null
        releasePlayer()
        surface?.release()
        surface = null
        texture?.let { removeView(it) }
        texture = null
        // drop a previously added picture, keep the scrim (the last child)
        while (childCount > 1) removeViewAt(0)
        videoWidth = 0
        videoHeight = 0
        visibility = VISIBLE
    }

    /** A file that cannot be played: hide the layer instead of showing a black screen. */
    private fun fail() {
        releasePlayer()
        visibility = GONE
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        openPlayer()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyCrop()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releasePlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    private companion object {
        const val SCRIM = 0x40000000
    }
}
