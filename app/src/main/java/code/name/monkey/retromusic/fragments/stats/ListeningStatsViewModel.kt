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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.ViewModel
import code.name.monkey.retromusic.db.ListeningEventDao
import code.name.monkey.retromusic.db.ListeningEventEntity
import code.name.monkey.retromusic.db.ListeningStats
import code.name.monkey.retromusic.db.TopRow
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ListeningStatsViewModel(private val dao: ListeningEventDao) : ViewModel() {

    /** Start of the chosen period in ms since epoch; 0 means all time. */
    private val since = MutableLiveData(daysAgo(7))

    /** Days of the chosen period: 7, 30 or 0 for all time. */
    fun setPeriod(days: Int) {
        since.value = if (days == 0) 0L else daysAgo(days)
    }

    val stats: LiveData<ListeningStats> = since.switchMap { dao.stats(it) }
    val topSongs: LiveData<List<TopRow>> =
        since.switchMap { dao.topSongs(it, TOP_LIMIT) }
    val topArtists: LiveData<List<TopRow>> =
        since.switchMap { dao.topArtists(it, TOP_LIMIT) }
    val topAlbums: LiveData<List<TopRow>> =
        since.switchMap { dao.topAlbums(it, TOP_LIMIT) }

    val today: LiveData<ListeningStats> = dao.stats(startOfToday())

    /** Events of the last [CHART_DAYS] days, turned into one number per day by the fragment. */
    val chartEvents: LiveData<List<ListeningEventEntity>> =
        dao.eventsSince(startOfToday() - TimeUnit.DAYS.toMillis(CHART_DAYS - 1L))

    val recent: LiveData<List<ListeningEventEntity>> = dao.recentEvents(RECENT_LIMIT)

    companion object {
        const val CHART_DAYS = 14
        private const val TOP_LIMIT = 100
        private const val RECENT_LIMIT = 200

        private fun daysAgo(days: Int) = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())

        fun startOfToday(): Long {
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
    }
}
