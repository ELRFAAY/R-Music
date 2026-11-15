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

import android.annotation.SuppressLint
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
                    if (dx > 0) MusicPlayerRemote.playNextSong()
                    else MusicPlayerRemote.playPreviousSong()
                    return true
                }
            })
        binding.ringCover.setOnTouchListener { _, event -> coverGestures.onTouchEvent(event) }
        binding.lyricsChip.setOnClickListener { setLyricsVisible(!showingLyrics) }
        setupGestures()
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
    private val hideSeekFeedback = Runnable { _binding?.seekFeedback?.isVisible = false }

    /** Drag up/down on the cover = volume, double tap right/left half = -/+10 seconds. */
    private fun setupGestures() {
        binding.ringCoverArea.apply {
            onVolumeChanged = { fraction ->
                _binding?.let {
                    it.volumeOverlay.removeCallbacks(hideVolume)
                    it.volumeOverlay.isVisible = true
                    it.volumeBar.setProgressCompat((fraction * 100).roundToInt(), false)
                }
            }
            onVolumeGestureEnd = { _binding?.volumeOverlay?.postDelayed(hideVolume, 800) }
            // Physical halves: right = forward, left = back (same in Arabic and English).
            onDoubleTap = { forward -> skipSeconds(if (forward) 10 else -10) }
        }
    }

    private fun skipSeconds(seconds: Int) {
        val duration = MusicPlayerRemote.songDurationMillis
        val position = MusicPlayerRemote.songProgressMillis
        if (duration <= 0 || position < 0) return
        MusicPlayerRemote.seekTo((position + seconds * 1000).coerceIn(0, duration))
        _binding?.seekFeedback?.let {
            it.removeCallbacks(hideSeekFeedback)
            it.setText(if (seconds > 0) R.string.cover_seek_forward else R.string.cover_seek_back)
            (it.layoutParams as android.widget.FrameLayout.LayoutParams).gravity =
                android.view.Gravity.CENTER_VERTICAL or
                        (if (seconds > 0) android.view.Gravity.END else android.view.Gravity.START)
            it.requestLayout()
            it.isVisible = true
            it.postDelayed(hideSeekFeedback, 600)
        }
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
            if (MusicPlayerRemote.shuffleMode == MusicService.SHUFFLE_MODE_SHUFFLE) Color.WHITE
            else Color.argb(90, 255, 255, 255), PorterDuff.Mode.SRC_IN
        )
    }

    private fun updateRepeat() {
        val mode = MusicPlayerRemote.repeatMode
        binding.repeatButton.setImageResource(
            if (mode == MusicService.REPEAT_MODE_THIS) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        binding.repeatButton.setColorFilter(
            if (mode == MusicService.REPEAT_MODE_NONE) Color.argb(90, 255, 255, 255)
            else Color.WHITE, PorterDuff.Mode.SRC_IN
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

    override fun toolbarIconColor(): Int = Color.WHITE

    override val paletteColor: Int
        get() = Color.BLACK

    override fun onColorChanged(color: MediaNotificationProcessor) = Unit

    override fun onFavoriteToggled() = Unit

    override fun onDestroyView() {
        _binding?.volumeOverlay?.removeCallbacks(hideVolume)
        _binding?.seekFeedback?.removeCallbacks(hideSeekFeedback)
        super.onDestroyView()
        _binding = null
    }
}
