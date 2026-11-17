package code.name.monkey.retromusic.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ListeningEventDao {

    @Insert
    suspend fun insert(event: ListeningEventEntity)

    @Insert
    suspend fun insertAll(events: List<ListeningEventEntity>)

    @Query("SELECT * FROM ListeningEventEntity ORDER BY played_at DESC")
    suspend fun allEvents(): List<ListeningEventEntity>

    @Query("SELECT * FROM ListeningEventEntity ORDER BY played_at DESC LIMIT :limit")
    fun recentEvents(limit: Int): LiveData<List<ListeningEventEntity>>

    /** Used by restore to avoid inserting the same session twice. */
    @Query(
        "SELECT COUNT(*) FROM ListeningEventEntity " +
                "WHERE played_at = :playedAt AND title = :title AND artist_name = :artistName"
    )
    suspend fun countSameEvent(playedAt: Long, title: String, artistName: String): Int

    /** Pass 0 as [since] for all-time numbers. */
    @Query(
        "SELECT COALESCE(SUM(listened_ms), 0) AS totalMs, " +
                "COUNT(*) AS plays, " +
                "COUNT(DISTINCT title || '|' || artist_name || '|' || album_name) AS songs " +
                "FROM ListeningEventEntity WHERE played_at >= :since"
    )
    fun stats(since: Long): LiveData<ListeningStats>

    /** The most played songs since [since]; the song is told apart by title and artist. */
    @Query(
        "SELECT title AS name, artist_name AS subtitle, COUNT(*) AS plays, " +
                "COALESCE(SUM(listened_ms), 0) AS totalMs FROM ListeningEventEntity " +
                "WHERE played_at >= :since GROUP BY title, artist_name " +
                "ORDER BY plays DESC, totalMs DESC LIMIT :limit"
    )
    fun topSongs(since: Long, limit: Int): LiveData<List<TopRow>>

    @Query(
        "SELECT artist_name AS name, '' AS subtitle, COUNT(*) AS plays, " +
                "COALESCE(SUM(listened_ms), 0) AS totalMs FROM ListeningEventEntity " +
                "WHERE played_at >= :since GROUP BY artist_name " +
                "ORDER BY plays DESC, totalMs DESC LIMIT :limit"
    )
    fun topArtists(since: Long, limit: Int): LiveData<List<TopRow>>

    @Query(
        "SELECT album_name AS name, artist_name AS subtitle, COUNT(*) AS plays, " +
                "COALESCE(SUM(listened_ms), 0) AS totalMs FROM ListeningEventEntity " +
                "WHERE played_at >= :since GROUP BY album_name, artist_name " +
                "ORDER BY plays DESC, totalMs DESC LIMIT :limit"
    )
    fun topAlbums(since: Long, limit: Int): LiveData<List<TopRow>>

    @Query("SELECT * FROM ListeningEventEntity WHERE played_at >= :since")
    fun eventsSince(since: Long): LiveData<List<ListeningEventEntity>>

    @Query("DELETE FROM ListeningEventEntity")
    suspend fun clear()
}
