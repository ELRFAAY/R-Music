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
package code.name.monkey.retromusic.fragments.player.ring

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.navOptions
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentRingPlayerBinding
import code.name.monkey.retromusic.extensions.drawAboveSystemBars
import code.name.monkey.retromusic.fragments.MusicSeekSkipTouchListener
import code.name.monkey.retromusic.fragments.base.AbsPlayerFragment
import code.name.monkey.retromusic.glide.BlurTransformation
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.simpleSongCoverOptions
import code.name.monkey.retromusic.glide.crossfadeListener
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.fragments.lyrics.LyricsSheetFragment
import code.name.monkey.retromusic.helper.PlayPauseButtonOnClickHandler
import code.name.monkey.retromusic.service.MusicService
import code.name.monkey.retromusic.util.LyricUtil
import code.name.monkey.retromusic.util.MusicUtil
import code.name.monkey.retromusic.util.NavigationUtil
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import me.tankery.lib.circularseekbar.CircularSeekBar

/**
 * "Ring cover" player: a round cover inside a white progress ring that can be dragged to seek,
 * a blurred cover as background, and tapping the cover swaps it for the synced lyrics.
 */
class RingPlayerFragment : AbsPlayerFragment(R.layout.fragment_ring_player),
    MusicProgressViewUpdateHelper.Callback, CircularSeekBar.OnCircularSeekBarChangeListener {

    private var _binding: FragmentRingPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var progressViewUpdateHelper: MusicProgressViewUpdateHelper
    private var lastRequest: RequestBuilder<Drawable>? = null
    private var isSeeking = false
    private var showingLyrics = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentRingPlayerBinding.bind(view)

        binding.playerToolbar.apply {
            inflateMenu(R.menu.menu_player)
            setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
            setOnMenuItemClickListener(this@RingPlayerFragment)
            ToolbarContentTintHelper.colorizeToolbar(this, Color.WHITE, requireActivity())
            drawAboveSystemBars()
        }

        binding.ringTitle.isSelected = true
        binding.progressRing.setOnSeekBarChangeListener(this)

        binding.playPauseButton.setOnClickListener(PlayPauseButtonOnClickHandler())
        binding.nextButton.setOnTouchListener(MusicSeekSkipTouchListener(requireActivity(), true))
        binding.previousButton.setOnTouchListener(
            MusicSeekSkipTouchListener(requireActivity(), false)
        )
        binding.shuffleButton.setOnClickListener { MusicPlayerRemote.toggleShuffleMode() }
        binding.repeatButton.setOnClickListener { MusicPlayerRemote.cycleRepeatMode() }
        binding.favoriteButton.setOnClickListener { toggleFavorite(MusicPlayerRemote.currentSong) }
        binding.queueButton.setOnClickListener {
            requireActivity().findNavController(R.id.fragment_container).navigate(
                R.id.playing_queue_fragment, null, navOptions { launchSingleTop = true }
            )
            mainActivity.collapsePanel()
        }
        binding.shareButton.setOnClickListener {
            startActivity(
                Intent.createChooser(
                    MusicUtil.createShareSongFileIntent(
                        requireContext(), MusicPlayerRemote.currentSong
                    ), null
                )
            )
        }
        binding.equalizerButton.setOnClickListener { NavigationUtil.openEqualizer(requireActivity()) }
        binding.ringTitle.setOnClickListener { code.name.monkey.retromusic.fragments.base.goToAlbum(requireActivity()) }
        binding.ringArtist.setOnClickListener { code.name.monkey.retromusic.fragments.base.goToArtist(requireActivity()) }

        // On the cover: a tap swaps the cover for the lyrics and back; a swipe to the RIGHT plays
        // the next song and a swipe to the LEFT the previous one (physical directions, the same
        // in Arabic and English, as the user asked).
        val swipeDistance = 72 * resources.displayMetrics.density
        val coverGestures = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    setLyricsVisible(!showingLyrics)
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) < swipeDistance || abs(dx) < abs(dy) * 1.5f) return false
                    swipeSong(next = dx > 0)
                    return true
                }
            })
        binding.ringCover.setOnTouchListener { _, event -> coverGestures.onTouchEvent(event) }
        binding.lyricsChip.setOnClickListener { setLyricsVisible(!showingLyrics) }
        setupGestures()
        setupLyricsTools()
        binding.ringLyrics.apply {
            setCurrentColor(Color.WHITE)
            setNormalColor(Color.argb(140, 255, 255, 255))
            setTimeTextColor(Color.WHITE)
            setTimelineColor(Color.WHITE)
            setTimelineTextColor(Color.WHITE)
            setDraggable(true) { time ->
                MusicPlayerRemote.seekTo(time.toInt())
                MusicPlayerRemote.resumePlaying()
                true
            }
            setOnClickListener { setLyricsVisible(false) }
        }
    }

    private val hideVolume = Runnable { _binding?.volumeOverlay?.isVisible = false }
    private var lyricsScaleIndex = 0
    private val lyricsScales = floatArrayOf(1f, 1.25f, 1.5f, 0.85f)

    /** Drag up/down on the cover = volume, double tap right/left half = -/+10 seconds. */
    private fun setupGestures() {
        val pillHeight = 170 * resources.displayMetrics.density
        binding.ringCoverArea.apply {
            onVolumeChanged = { fraction ->
                _binding?.let {
                    it.volumeOverlay.removeCallbacks(hideVolume)
                    it.volumeOverlay.isVisible = true
                    it.volumeValue.text = (fraction * 100).roundToInt().toString()
                    it.volumeFill.layoutParams.height = (pillHeight * fraction).toInt()
                    it.volumeFill.requestLayout()
                }
            }
            onRingTouch = { fraction, final ->
                _binding?.let { b ->
                    if (!isSeeking) {
                        isSeeking = true
                        progressViewUpdateHelper.stop()
                    }
                    val target = fraction * b.progressRing.max
                    b.progressRing.progress = target
                    if (final) {
                        isSeeking = false
                        MusicPlayerRemote.seekTo(target.toInt())
                        progressViewUpdateHelper.start()
                    }
                }
            }
            onVolumeGestureEnd = { _binding?.volumeOverlay?.postDelayed(hideVolume, 800) }
            // Physical halves: right = forward, left = back (same in Arabic and English).
            onDoubleTap = { forward -> skipSeconds(if (forward) 10 else -10) }
        }
    }

    private fun setupLyricsTools() {
        val openSheet = View.OnClickListener {
            LyricsSheetFragment().show(childFragmentManager, LyricsSheetFragment.TAG)
        }
        // The panel searches LRCLIB, shows the full text and has the hand-sync (edit) mode.
        binding.lyricsFullscreen.setOnClickListener(openSheet)
        binding.lyricsSearch.setOnClickListener(openSheet)
        binding.lyricsEdit.setOnClickListener(openSheet)
        binding.lyricsFont.setOnClickListener {
            lyricsScaleIndex = (lyricsScaleIndex + 1) % lyricsScales.size
            binding.ringLyrics.setTextScale(lyricsScales[lyricsScaleIndex])
        }
        binding.lyricsClose.setOnClickListener { setLyricsVisible(false) }
    }

    private var contentColor = Color.WHITE
    private var slideInFrom = 0 // -1 = new cover enters from the left, 1 = from the right
    private var tintAnimator: ValueAnimator? = null

    private fun coverTravel(): Float {
        val b = _binding ?: return 0f
        return b.ringCover.width + 48 * resources.displayMetrics.density
    }

    /** Swipe on the cover: the cover slides off the side, the ring empties, the next/previous song's cover slides in. */
    private fun swipeSong(next: Boolean) {
        val b = _binding ?: return
        val outDir = if (next) 1f else -1f
        slideInFrom = (-outDir).toInt()
        b.progressRing.progress = 0f
        b.ringCover.animate().cancel()
        b.ringCover.animate().translationX(outDir * coverTravel()).alpha(0f).setDuration(170)
            .withEndAction {
                if (next) MusicPlayerRemote.playNextSong() else MusicPlayerRemote.playPreviousSong()
            }.start()
        // If nothing changed (end of the queue), bring the cover back.
        b.ringCover.postDelayed({
            val bb = _binding ?: return@postDelayed
            if (slideInFrom != 0) {
                slideInFrom = 0
                bb.ringCover.animate().translationX(0f).alpha(1f).setDuration(200).start()
            }
        }, 1200)
    }

    private fun slideCoverIn() {
        val b = _binding ?: return
        val from = slideInFrom
        slideInFrom = 0
        b.ringCover.animate().cancel()
        if (from == 0) {
            b.ringCover.translationX = 0f
            b.ringCover.alpha = 1f
            return
        }
        b.ringCover.translationX = from * coverTravel()
        b.ringCover.alpha = 1f
        b.ringCover.animate().translationX(0f).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** Background tint from the cover's colours; dark text/icons when the colour is light. */
    private fun applyCoverPalette(bitmap: android.graphics.Bitmap) {
        Palette.from(bitmap).maximumColorCount(16).generate { palette ->
            val b = _binding ?: return@generate
            val base = palette?.getMutedColor(palette.getDominantColor(Color.DKGRAY)) ?: Color.DKGRAY
            var color = base
            val lum = ColorUtils.calculateLuminance(color)
            if (lum in 0.3..0.5) color = ColorUtils.blendARGB(color, Color.BLACK, 0.4f)
            val content = if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
            val from = (b.ringTint.background as? ColorDrawable)?.color ?: 0xB3000000.toInt()
            val to = ColorUtils.setAlphaComponent(color, 0xC0)
            tintAnimator?.cancel()
            tintAnimator = ValueAnimator.ofArgb(from, to).apply {
                duration = 350
                addUpdateListener { a -> _binding?.ringTint?.setBackgroundColor(a.animatedValue as Int) }
                start()
            }
            applyContentColor(content)
        }
    }

    private fun applyContentColor(c: Int) {
        val b = _binding ?: return
        contentColor = c
        val soft = ColorUtils.setAlphaComponent(c, 0xCC)
        val onContent = if (c == Color.BLACK) Color.WHITE else Color.BLACK
        b.ringTitle.setTextColor(c)
        b.ringArtist.setTextColor(soft)
        b.playingFrom.setTextColor(c)
        b.playingFromLabel.setTextColor(soft)
        b.artistCounter.setTextColor(soft)
        b.lyricsChip.setTextColor(c)
        val tint = ColorStateList.valueOf(c)
        listOf(
            b.queueButton, b.favoriteButton, b.previousButton, b.nextButton,
            b.shareButton, b.equalizerButton, b.lyricsFullscreen, b.lyricsClose
        ).forEach { it.imageTintList = tint }
        listOf(b.lyricsSearch, b.lyricsEdit, b.lyricsFont).forEach { it.setTextColor(c) }
        b.playPauseButton.backgroundTintList = tint
        b.playPauseButton.imageTintList = ColorStateList.valueOf(onContent)
        b.progressRing.circleColor = ColorUtils.setAlphaComponent(c, 0x33)
        b.progressRing.circleProgressColor = c
        b.progressRing.pointerColor = c
        b.ringLyrics.setCurrentColor(c)
        b.ringLyrics.setNormalColor(ColorUtils.setAlphaComponent(c, 140))
        b.ringLyrics.setTimeTextColor(c)
        b.ringLyrics.setTimelineColor(c)
        b.ringLyrics.setTimelineTextColor(c)
        ToolbarContentTintHelper.colorizeToolbar(b.playerToolbar, c, requireActivity())
        updateShuffle()
        updateRepeat()
    }

    private fun skipSeconds(seconds: Int) {
        val duration = MusicPlayerRemote.songDurationMillis
        val position = MusicPlayerRemote.songProgressMillis
        if (duration <= 0 || position < 0) return
        MusicPlayerRemote.seekTo((position + seconds * 1000).coerceIn(0, duration))
        _binding?.seekSector?.show(seconds > 0, getString(R.string.ring_seek_seconds))
    }

    /**
     * Name shown under "Playing from". The app does not store which screen the queue was started
     * from, so it is derived from the queue: one album -> album name, one artist -> artist name,
     * anything else -> "Queue".
     */
    private fun playingSourceName(): String {
        val queue = MusicPlayerRemote.playingQueue
        if (queue.isEmpty()) return MusicPlayerRemote.currentSong.albumName
        val albumIds = queue.map { it.albumId }.toSet()
        if (albumIds.size == 1) return queue.first().albumName
        val artistIds = queue.map { it.artistId }.toSet()
        if (artistIds.size == 1) return queue.first().artistName
        return getString(R.string.queue)
    }

    private fun setLyricsVisible(visible: Boolean) {
        showingLyrics = visible
        binding.ringLyrics.isVisible = visible
        binding.ringCover.isVisible = !visible
        // With lyrics shown the ring goes away and the tool chips appear under the text.
        binding.progressRing.visibility = if (visible) View.INVISIBLE else View.VISIBLE
        binding.lyricsTools.isVisible = visible
    }

    private fun updateSong() {
        val song = MusicPlayerRemote.currentSong
        binding.ringTitle.text = song.title
        binding.ringArtist.text = song.artistName
        binding.playingFrom.text = playingSourceName()

        Glide.with(this)
            .load(RetroGlideExtension.getSongModel(song))
            .simpleSongCoverOptions(song)
            .error(R.drawable.default_audio_art)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean
                ): Boolean {
                    slideCoverIn()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable?, model: Any?, target: Target<Drawable>?,
                    dataSource: DataSource?, isFirstResource: Boolean
                ): Boolean {
                    slideCoverIn()
                    (resource as? BitmapDrawable)?.bitmap?.let { applyCoverPalette(it) }
                    return false
                }
            })
            .into(binding.ringCover)

        Glide.with(this)
            .load(RetroGlideExtension.getSongModel(song))
            .simpleSongCoverOptions(song)
            .transform(
                BlurTransformation.Builder(requireContext())
                    .blurRadius(PreferenceUtil.blurAmount.toFloat()).build()
            )
            .thumbnail(lastRequest)
            .error(Glide.with(this).load(ColorDrawable(Color.DKGRAY)).fitCenter())
            .also {
                lastRequest = it.clone()
                it.crossfadeListener().into(binding.ringBackground)
            }
        updateFavoriteHeart()
        updateQueueCounter()
        updateLyrics()
    }

    /**
     * "6/155": position of the playing song in the play queue and the size of the queue. The queue
     * is the list the user opened and started playing from (folder, album, playlist, artist).
     */
    private fun updateQueueCounter() {
        val size = MusicPlayerRemote.playingQueue.size
        val index = MusicPlayerRemote.position
        _binding?.artistCounter?.text = if (size == 0 || index < 0) "" else "${index + 1}/$size"
    }

    private fun updateFavoriteHeart() {
        val id = MusicPlayerRemote.currentSong.id
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val isFavorite = libraryViewModel.isSongFavorite(id)
            withContext(Dispatchers.Main) {
                _binding?.favoriteButton?.setImageResource(
                    if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                )
            }
        }
    }

    private fun updateLyrics() {
        val song = MusicPlayerRemote.currentSong
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val lrcFile = LyricUtil.getSyncedLyricsFile(song)
            val embedded = if (lrcFile == null) LyricUtil.getEmbeddedSyncedLyrics(song.data) else null
            withContext(Dispatchers.Main) {
                val view = _binding?.ringLyrics ?: return@withContext
                when {
                    lrcFile != null -> view.loadLrc(lrcFile)
                    embedded != null -> view.loadLrc(embedded)
                    else -> {
                        view.reset()
                        view.setLabel(getString(R.string.ring_no_lyrics))
                    }
                }
            }
        }
    }

    private fun updatePlayPause() {
        binding.playPauseButton.setImageResource(
            if (MusicPlayerRemote.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
    }

    private fun updateShuffle() {
        binding.shuffleButton.setColorFilter(
            if (MusicPlayerRemote.shuffleMode == MusicService.SHUFFLE_MODE_SHUFFLE) contentColor
            else ColorUtils.setAlphaComponent(contentColor, 90), PorterDuff.Mode.SRC_IN
        )
    }

    private fun updateRepeat() {
        val mode = MusicPlayerRemote.repeatMode
        binding.repeatButton.setImageResource(
            if (mode == MusicService.REPEAT_MODE_THIS) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        binding.repeatButton.setColorFilter(
            if (mode == MusicService.REPEAT_MODE_NONE) ColorUtils.setAlphaComponent(contentColor, 90)
            else contentColor, PorterDuff.Mode.SRC_IN
        )
    }

    override fun onResume() {
        super.onResume()
        progressViewUpdateHelper.start()
        updateSong()
        updatePlayPause()
        updateShuffle()
        updateRepeat()
    }

    override fun onPause() {
        super.onPause()
        lastRequest = null
        progressViewUpdateHelper.stop()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateSong()
        updatePlayPause()
        updateShuffle()
        updateRepeat()
    }

    override fun onQueueChanged() {
        super.onQueueChanged()
        if (_binding != null) updateQueueCounter()
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        if (_binding != null) updateSong()
    }

    override fun onPlayStateChanged() {
        super.onPlayStateChanged()
        if (_binding != null) updatePlayPause()
    }

    override fun onShuffleModeChanged() {
        super.onShuffleModeChanged()
        if (_binding != null) updateShuffle()
    }

    override fun onRepeatModeChanged() {
        super.onRepeatModeChanged()
        if (_binding != null) updateRepeat()
    }

    override fun onFavoriteStateChanged() {
        super.onFavoriteStateChanged()
        if (_binding != null) updateFavoriteHeart()
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        val b = _binding ?: return
        if (total > 0) b.progressRing.max = total.toFloat()
        if (!isSeeking) b.progressRing.progress = progress.toFloat().coerceIn(0f, b.progressRing.max)
        b.ringLyrics.updateTime(progress.toLong())
    }

    override fun onProgressChanged(
        circularSeekBar: CircularSeekBar?, progress: Float, fromUser: Boolean
    ) = Unit

    override fun onStartTrackingTouch(seekBar: CircularSeekBar?) {
        isSeeking = true
        progressViewUpdateHelper.stop()
    }

    override fun onStopTrackingTouch(seekBar: CircularSeekBar?) {
        isSeeking = false
        seekBar?.let { MusicPlayerRemote.seekTo(it.progress.toInt()) }
        progressViewUpdateHelper.start()
    }

    override fun playerToolbar(): Toolbar = binding.playerToolbar

    override fun onShow() = Unit

    override fun onHide() = Unit

    override fun toolbarIconColor(): Int = contentColor

    override val paletteColor: Int
        get() = Color.BLACK

    override fun onColorChanged(color: MediaNotificationProcessor) = Unit

    override fun onFavoriteToggled() = Unit

    override fun onDestroyView() {
        _binding?.volumeOverlay?.removeCallbacks(hideVolume)
        tintAnimator?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
