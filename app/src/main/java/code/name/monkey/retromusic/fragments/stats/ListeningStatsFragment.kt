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

import android.os.Bundle
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
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.periodGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chip30 -> viewModel.setPeriod(30)
                R.id.chipAll -> viewModel.setPeriod(0)
                else -> viewModel.setPeriod(7)
            }
        }

        viewModel.stats.observe(viewLifecycleOwner) { stats ->
            binding.cardTimeValue.text = formatDuration(stats.totalMs)
            binding.cardPlaysValue.text = stats.plays.toString()
            binding.cardSongsValue.text = stats.songs.toString()
            binding.emptyView.visibility = if (stats.plays == 0) View.VISIBLE else View.GONE
        }
        viewModel.today.observe(viewLifecycleOwner) { stats ->
            binding.cardTodayValue.text = formatDuration(stats.totalMs)
        }
        viewModel.topSongs.observe(viewLifecycleOwner) {
            songs = it
            fillRows(binding.topSongsContainer, it, PREVIEW_ROWS, showSubtitle = true)
        }
        viewModel.topArtists.observe(viewLifecycleOwner) {
            artists = it
            fillRows(binding.topArtistsContainer, it, PREVIEW_ROWS, showSubtitle = false)
        }
        viewModel.topAlbums.observe(viewLifecycleOwner) {
            albums = it
            fillRows(binding.topAlbumsContainer, it, PREVIEW_ROWS, showSubtitle = true)
        }
        viewModel.chartEvents.observe(viewLifecycleOwner) { events ->
            drawChart(events.map { it.playedAt to it.listenedMs })
        }

        binding.seeAllSongs.setOnClickListener { showAll(R.string.stats_top_songs, songs, true) }
        binding.seeAllArtists.setOnClickListener { showAll(R.string.stats_top_artists, artists, false) }
        binding.seeAllAlbums.setOnClickListener { showAll(R.string.stats_top_albums, albums, true) }
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
    private fun fillRows(container: LinearLayout, rows: List<TopRow>, max: Int, showSubtitle: Boolean) {
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
            container.addView(v)
        }
    }

    private fun showAll(titleRes: Int, rows: List<TopRow>, showSubtitle: Boolean) {
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
        fillRows(rowsHolder, rows, rows.size, showSubtitle)
        BottomSheetDialog(ctx).apply {
            setContentView(NestedScrollView(ctx).apply { addView(column) })
            show()
        }
    }

    private fun showHistory() {
        val ctx = requireContext()
        val adapter = ListeningEventAdapter()
        adapter.submitList(viewModel.recent.value ?: emptyList())
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
            show()
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
        const val PREVIEW_ROWS = 5
    }
}
