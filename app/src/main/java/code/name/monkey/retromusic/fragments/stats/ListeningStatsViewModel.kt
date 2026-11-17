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
import androidx.lifecycle.MediatorLiveData
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

    private val periodDays = MutableLiveData(7)

    /** Start of the chosen period in ms since epoch; 0 means all time. */
    private val since = MutableLiveData(daysAgo(7))

    /** Days of the chosen period: 7, 30 or 0 for all time. */
    fun setPeriod(days: Int) {
        periodDays.value = days
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

    /** Numbers that need every event (compare with the previous period, streak, time of day). */
    val extras: LiveData<Extras> = MediatorLiveData<Extras>().apply {
        val all = dao.allEventsLive()
        fun recompute() {
            val events = all.value ?: return
            value = computeExtras(events, periodDays.value ?: 7)
        }
        addSource(all) { recompute() }
        addSource(periodDays) { recompute() }
    }

    val recent: LiveData<List<ListeningEventEntity>> = dao.recentEvents(RECENT_LIMIT)

    /**
     * [prevMs] is -1 when there is nothing to compare with (all time, or an empty previous period).
     * [favHour] is 0..23 or -1, [favDay] a Calendar.DAY_OF_WEEK or -1.
     */
    class Extras(
        val avgPerDayMs: Long,
        val streakDays: Int,
        val currentMs: Long,
        val prevMs: Long,
        val favHour: Int,
        val favDay: Int,
        val hourly: LongArray
    )

    companion object {
        private fun computeExtras(events: List<ListeningEventEntity>, days: Int): Extras {
            val now = System.currentTimeMillis()
            val dayMs = TimeUnit.DAYS.toMillis(1)
            val from = if (days == 0) 0L else now - days * dayMs
            val prevFrom = if (days == 0) 0L else from - days * dayMs
            var current = 0L
            var prev = 0L
            var first = now
            val hourly = LongArray(24)
            val weekday = LongArray(8)
            val cal = Calendar.getInstance()
            val tz = cal.timeZone
            val dayKeys = HashSet<Long>()
            for (e in events) {
                if (e.listenedMs <= 0) continue
                val offset = tz.getOffset(e.playedAt)
                dayKeys.add((e.playedAt + offset) / dayMs)
                if (e.playedAt >= from) {
                    current += e.listenedMs
                    if (e.playedAt < first) first = e.playedAt
                    cal.timeInMillis = e.playedAt
                    hourly[cal.get(Calendar.HOUR_OF_DAY)] += e.listenedMs
                    weekday[cal.get(Calendar.DAY_OF_WEEK)] += e.listenedMs
                } else if (days != 0 && e.playedAt >= prevFrom) {
                    prev += e.listenedMs
                }
            }
            val spanDays = if (days != 0) days
            else (((now - first) / dayMs).toInt() + 1).coerceAtLeast(1)
            // streak: consecutive days with listening, ending today (or yesterday if today is empty)
            val today = (now + tz.getOffset(now)) / dayMs
            var day = if (today in dayKeys) today else today - 1
            var streak = 0
            while (day in dayKeys) { streak++; day-- }
            val favHour = hourly.indices.maxByOrNull { hourly[it] }?.takeIf { hourly[it] > 0 } ?: -1
            val favDay = (1..7).maxByOrNull { weekday[it] }?.takeIf { weekday[it] > 0 } ?: -1
            return Extras(
                avgPerDayMs = current / spanDays,
                streakDays = streak,
                currentMs = current,
                prevMs = if (days == 0 || prev == 0L) -1L else prev,
                favHour = favHour,
                favDay = favDay,
                hourly = hourly
            )
        }

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
