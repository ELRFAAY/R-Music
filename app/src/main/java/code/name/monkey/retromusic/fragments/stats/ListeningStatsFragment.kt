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
package code.name.monkey.retromusic.fragments.stats

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.EXTRA_ALBUM_ID
import code.name.monkey.retromusic.EXTRA_ARTIST_ID
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.repository.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.util.Calendar
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentListeningStatsBinding
import code.name.monkey.retromusic.db.TopRow
import code.name.monkey.retromusic.extensions.applyToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Listening statistics: summary cards, a 14 day chart and top songs / artists / albums for the
 * chosen period. "See all" and "History" open a bottom sheet with the complete list.
 */
class ListeningStatsFragment : Fragment() {

    private var _binding: FragmentListeningStatsBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModel<ListeningStatsViewModel>()

    private val repository by inject<Repository>()
    private var periodDays = 7
    private var lastStats: code.name.monkey.retromusic.db.ListeningStats? = null

    private var songs: List<TopRow> = emptyList()
    private var artists: List<TopRow> = emptyList()
    private var albums: List<TopRow> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListeningStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyToolbar(binding.toolbar)
        applyPalette()
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.periodGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            periodDays = when (checkedIds.firstOrNull()) {
                R.id.chip30 -> 30
                R.id.chipAll -> 0
                else -> 7
            }
            viewModel.setPeriod(periodDays)
        }

        viewModel.stats.observe(viewLifecycleOwner) { stats ->
            lastStats = stats
            binding.cardTimeValue.text = formatDuration(stats.totalMs)
            binding.cardPlaysValue.text = stats.plays.toString()
            binding.cardSongsValue.text = stats.songs.toString()
            binding.hoursListenedValue.text = formatDuration(stats.totalMs)
            binding.emptyView.visibility = if (stats.plays == 0) View.VISIBLE else View.GONE
        }
        viewModel.topPlaylists.observe(viewLifecycleOwner) { list ->
            val box = binding.topPlaylistsContainer
            box.removeAllViews()
            if (list.isEmpty()) {
                box.addView(TextView(requireContext()).apply { setText(R.string.stats_no_data) })
            }
            val maxMs = (list.firstOrNull()?.ms ?: 1L).coerceAtLeast(1L)
            val d = resources.displayMetrics.density
            val accent = com.google.android.material.color.MaterialColors.getColor(
                box, androidx.appcompat.R.attr.colorPrimary, 0
            )
            list.forEachIndexed { i, p ->
                val row = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(0, if (i == 0) 0 else (8 * d).toInt(), 0, 0)
                    alpha = 0f
                    translationY = 12 * d
                }
                row.addView(TextView(requireContext()).apply {
                    text = "${i + 1}. ${p.name} · ${formatDuration(p.ms)}"
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    if (i == 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                val bar = View(requireContext()).apply {
                    setBackgroundColor(accent)
                    alpha = if (i == 0) 1f else 0.55f
                    scaleX = 0f
                }
                row.addView(bar, android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, (4 * d).toInt()
                ).apply { topMargin = (3 * d).toInt() })
                box.addView(row)
                val fraction = p.ms.toFloat() / maxMs
                row.animate().alpha(1f).translationY(0f).setStartDelay(i * 90L).setDuration(260).start()
                bar.post {
                    bar.pivotX = if (bar.layoutDirection == View.LAYOUT_DIRECTION_RTL) bar.width.toFloat() else 0f
                    bar.animate().scaleX(fraction).setStartDelay(i * 90L + 120).setDuration(500).start()
                }
            }
        }
        viewModel.today.observe(viewLifecycleOwner) { stats ->
            binding.cardTodayValue.text = formatDuration(stats.totalMs)
        }
        viewModel.topSongs.observe(viewLifecycleOwner) {
            songs = it
            showHighlight(it.firstOrNull())
            fillRows(binding.topSongsContainer, it, PREVIEW_ROWS, showSubtitle = true, kind = KIND_SONG)
        }
        viewModel.topArtists.observe(viewLifecycleOwner) {
            artists = it
            fillRows(binding.topArtistsContainer, it, PREVIEW_ROWS, showSubtitle = false, kind = KIND_ARTIST)
        }
        viewModel.topAlbums.observe(viewLifecycleOwner) {
            albums = it
            fillRows(binding.topAlbumsContainer, it, PREVIEW_ROWS, showSubtitle = true, kind = KIND_ALBUM)
        }
        viewModel.chartEvents.observe(viewLifecycleOwner) { events ->
            drawChart(events.map { it.playedAt to it.listenedMs })
        }

        viewModel.extras.observe(viewLifecycleOwner) { showExtras(it) }
        binding.shareButton.setOnClickListener { shareSummary() }

        binding.seeAllSongs.setOnClickListener { showAll(R.string.stats_top_songs, songs, true, KIND_SONG) }
        binding.seeAllArtists.setOnClickListener { showAll(R.string.stats_top_artists, artists, false, KIND_ARTIST) }
        binding.seeAllAlbums.setOnClickListener { showAll(R.string.stats_top_albums, albums, true, KIND_ALBUM) }
        binding.historyButton.setOnClickListener { showHistory() }
    }

    private fun drawChart(events: List<Pair<Long, Long>>) {
        val days = ListeningStatsViewModel.CHART_DAYS
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val todayStart = ListeningStatsViewModel.startOfToday()
        val sums = LongArray(days)
        for ((playedAt, listened) in events) {
            val back = if (playedAt >= todayStart) 0
            else Math.ceil((todayStart - playedAt) / dayMs.toDouble()).toInt()
            if (back in 0 until days) sums[days - 1 - back] += listened
        }
        val format = SimpleDateFormat("d", Locale.getDefault())
        // +12 h so a daylight saving change cannot push the label to the neighbouring day.
        val labels = (0 until days).map {
            format.format(Date(todayStart - (days - 1 - it) * dayMs + dayMs / 2))
        }
        val maxMs = sums.maxOrNull() ?: 0L
        binding.chart.setData(sums.map { it / 60000f }, labels, formatDuration(maxMs))
    }

    /** Fills [container] with the first [max] rows; the bar under each row is its share of the top row. */
    private fun fillRows(container: LinearLayout, rows: List<TopRow>, max: Int, showSubtitle: Boolean, kind: Int) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                setText(R.string.stats_no_data)
                setPadding(0, 8, 0, 8)
            })
            return
        }
        val top = rows.first().plays.coerceAtLeast(1)
        val inflater = LayoutInflater.from(requireContext())
        rows.take(max).forEachIndexed { index, row ->
            val v = inflater.inflate(R.layout.item_top_row, container, false)
            v.findViewById<TextView>(R.id.rank).text = (index + 1).toString()
            v.findViewById<TextView>(R.id.rowTitle).text = row.name.ifBlank { "-" }
            val detail = getString(R.string.stats_row_detail, row.plays, formatDuration(row.totalMs))
            v.findViewById<TextView>(R.id.rowDetail).text =
                if (showSubtitle && row.subtitle.isNotBlank()) row.subtitle + " · " + detail else detail
            v.findViewById<LinearProgressIndicator>(R.id.rowShare).progress = row.plays * 100 / top
            v.setOnClickListener { openRow(kind, row) }
            container.addView(v)
        }
    }

    private fun showAll(titleRes: Int, rows: List<TopRow>, showSubtitle: Boolean, kind: Int) {
        val ctx = requireContext()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(ctx).apply {
            setText(titleRes)
            textSize = 18f
            setPadding(0, 0, 0, 8)
        })
        val rowsHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(rowsHolder)
        fillRows(rowsHolder, rows, rows.size, showSubtitle, kind)
        BottomSheetDialog(ctx).apply {
            setContentView(NestedScrollView(ctx).apply { addView(column) })
            show()
        }
    }

    private fun showHistory() {
        val ctx = requireContext()
        val adapter = ListeningEventAdapter()
        // A Room LiveData has no value until somebody observes it, so read it through an observer
        // (reading `.value` directly always gave an empty list).
        val observer = androidx.lifecycle.Observer<List<code.name.monkey.retromusic.db.ListeningEventEntity>> {
            adapter.submitList(it)
        }
        viewModel.recent.observe(viewLifecycleOwner, observer)
        viewLifecycleOwner.lifecycleScope.launch { adapter.songIndex = viewModel.songIndex() }
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.7f).toInt()
            )
        }
        BottomSheetDialog(ctx).apply {
            setContentView(list)
            setOnDismissListener { viewModel.recent.removeObserver(observer) }
            show()
        }
    }

    private fun hueShift(color: Int, shift: Float): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
        hsl[0] = (hsl[0] + shift) % 360f
        hsl[1] = hsl[1].coerceAtLeast(0.55f)
        hsl[2] = hsl[2].coerceIn(0.42f, 0.58f)
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    private fun onColor(color: Int): Int =
        if (androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.55) 0xFF111111.toInt()
        else 0xFFFFFFFF.toInt()

    /** Gives every card its own colour (rotated from the accent) so the screen is not all dark. */
    private fun applyPalette() {
        val b = binding
        val accent = ThemeStore.accentColor(requireContext())
        fun tint(value: TextView, color: Int, solid: Boolean = false) {
            val card = value.parent.parent as com.google.android.material.card.MaterialCardView
            val box = value.parent as ViewGroup
            if (solid) {
                card.setCardBackgroundColor(color)
                val on = onColor(color)
                for (i in 0 until box.childCount) (box.getChildAt(i) as? TextView)?.setTextColor(on)
            } else {
                card.setCardBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x38))
                value.setTextColor(color)
            }
        }
        tint(b.cardTimeValue, accent, solid = true)
        tint(b.cardPlaysValue, hueShift(accent, 45f))
        tint(b.cardSongsValue, hueShift(accent, 90f))
        tint(b.cardTodayValue, hueShift(accent, 200f))
        tint(b.tileAvg, hueShift(accent, 120f))
        tint(b.tileStreak, hueShift(accent, 170f))
        tint(b.tileHour, hueShift(accent, 235f))
        tint(b.tileDay, hueShift(accent, 290f))
        b.chart.barColor = accent
        b.hoursChart.barColor = hueShift(accent, 150f)
        // the colourful break in the middle of the screen
        val from = accent
        val to = hueShift(accent, 70f)
        b.highlightCard.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)
        ).apply { cornerRadius = 20f * resources.displayMetrics.density }
        val on = onColor(androidx.core.graphics.ColorUtils.blendARGB(from, to, 0.5f))
        b.highlightLabel.setTextColor(on)
        b.highlightTitle.setTextColor(on)
        b.highlightSub.setTextColor(on)
    }

    private fun showHighlight(row: TopRow?) {
        val b = _binding ?: return
        val card = b.highlightCard.parent as View
        if (row == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        b.highlightTitle.text = row.name.ifBlank { "-" }
        b.highlightSub.text = (if (row.subtitle.isNotBlank()) row.subtitle + " · " else "") +
            getString(R.string.stats_row_detail, row.plays, formatDuration(row.totalMs))
    }

    /** Tiles, the compare line and the time-of-day chart. */
    private fun showExtras(e: ListeningStatsViewModel.Extras) {
        val b = _binding ?: return
        b.tileAvg.text = formatDuration(e.avgPerDayMs)
        b.tileStreak.text = getString(R.string.stats_days_n, e.streakDays)
        b.tileHour.text = if (e.favHour < 0) "-" else formatHour(e.favHour)
        b.tileDay.text = if (e.favDay < 0) "-" else {
            val c = Calendar.getInstance()
            c.set(Calendar.DAY_OF_WEEK, e.favDay)
            SimpleDateFormat("EEEE", Locale.getDefault()).format(c.time)
        }
        if (e.prevMs < 0) {
            b.compareText.visibility = View.GONE
        } else {
            val diff = e.currentMs - e.prevMs
            b.compareText.visibility = View.VISIBLE
            b.compareText.text = when {
                Math.abs(diff) < 60_000L -> getString(R.string.stats_compare_same)
                diff > 0 -> getString(R.string.stats_compare_more, formatDuration(diff))
                else -> getString(R.string.stats_compare_less, formatDuration(-diff))
            }
        }
        val labels = (0 until 24).map { if (it % 3 == 0) formatHourShort(it) else "" }
        val peak = if (e.favHour >= 0) e.hourly[e.favHour] else 0L
        b.hoursChart.setData(e.hourly.map { it / 60000f }, labels, formatDuration(peak), e.favHour)
    }

    private fun formatHour(hour: Int): String {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, 0)
        return SimpleDateFormat("h a", Locale.getDefault()).format(c.time)
    }

    private fun formatHourShort(hour: Int): String = hour.toString()

    /** A tap on a row: a song plays, an artist or album opens its page. */
    private fun openRow(kind: Int, row: TopRow) {
        viewLifecycleOwner.lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { repository.allSongs() }
            val match = when (kind) {
                KIND_SONG -> all.firstOrNull { it.title == row.name && it.artistName == row.subtitle }
                KIND_ARTIST -> all.firstOrNull { it.artistName == row.name }
                else -> all.firstOrNull { it.albumName == row.name && it.artistName == row.subtitle }
                    ?: all.firstOrNull { it.albumName == row.name }
            }
            if (match == null) {
                Toast.makeText(requireContext(), R.string.stats_song_not_found, Toast.LENGTH_SHORT).show()
                return@launch
            }
            when (kind) {
                KIND_SONG -> MusicPlayerRemote.openQueue(listOf(match), 0, true)
                KIND_ARTIST -> findNavController().navigate(
                    R.id.artistDetailsFragment, bundleOf(EXTRA_ARTIST_ID to match.artistId)
                )
                else -> findNavController().navigate(
                    R.id.albumDetailsFragment, bundleOf(EXTRA_ALBUM_ID to match.albumId)
                )
            }
        }
    }

    /** Draws a summary picture (total time, top song / artist / album) and opens the share sheet. */
    private fun shareSummary() {
        val ctx = requireContext()
        val stats = lastStats
        val periodLabel = getString(
            when (periodDays) { 7 -> R.string.stats_period_7; 30 -> R.string.stats_period_30; else -> R.string.stats_period_all }
        )
        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val accent = ThemeStore.accentColor(ctx)
        val dark = androidx.core.graphics.ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.78f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), accent, dark, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFFFFFFF.toInt()
        fun text(t: String, x: Float, y: Float, size: Float, bold: Boolean, alpha: Int = 255) {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.alpha = alpha
            c.drawText(if (t.length > 34) t.take(33) + "…" else t, x, y, paint)
        }
        text("R Music", 80f, 140f, 56f, true)
        text(periodLabel, 80f, 205f, 42f, false, 200)
        text(getString(R.string.listening_time), 80f, 400f, 44f, false, 200)
        text(formatDuration(stats?.totalMs ?: 0L), 80f, 520f, 120f, true)
        text(
            getString(R.string.stats_card_plays) + ": " + (stats?.plays ?: 0) +
                "   ·   " + getString(R.string.stats_card_songs) + ": " + (stats?.songs ?: 0),
            80f, 600f, 42f, false, 220
        )
        var y = 780f
        fun line(title: Int, row: TopRow?) {
            text(getString(title), 80f, y, 38f, false, 190)
            text(row?.name?.ifBlank { "-" } ?: "-", 80f, y + 62f, 56f, true)
            y += 190f
        }
        line(R.string.stats_top_songs, songs.firstOrNull())
        line(R.string.stats_top_artists, artists.firstOrNull())
        line(R.string.stats_top_albums, albums.firstOrNull())

        viewLifecycleOwner.lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val f = File(ctx.cacheDir, "stats_summary.png")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                FileProvider.getUriForFile(ctx, ctx.applicationContext.packageName, f)
            }
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, getString(R.string.stats_share_title))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, null
                )
            )
        }
    }

    private fun formatDuration(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return getString(R.string.listening_duration_format, hours, minutes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val PREVIEW_ROWS = 3
        const val KIND_SONG = 0
        const val KIND_ARTIST = 1
        const val KIND_ALBUM = 2
    }
}
