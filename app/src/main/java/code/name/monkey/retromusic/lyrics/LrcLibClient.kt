package code.name.monkey.retromusic.lyrics

import code.name.monkey.retromusic.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Looks lyrics up on https://lrclib.net (free, no key). Blocking: call it off the main thread. */
object LrcLibClient {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "R Music (https://github.com/ELRFAAY/RetroMusicPlayer)"

    class Found(val synced: String?, val plain: String?, val instrumental: Boolean)

    /** Returns null when nothing was found; throws [IOException] when the service can't be reached. */
    @Throws(IOException::class)
    fun fetch(song: Song): Found? {
        val seconds = (song.duration / 1000).toInt()
        val exact = request(
            "$BASE/get?track_name=${enc(song.title)}&artist_name=${enc(song.artistName)}" +
                    "&album_name=${enc(song.albumName)}&duration=$seconds"
        )
        if (exact != null) {
            toFound(JSONObject(exact))?.let { return it }
        }
        // Tags of K-pop files are messy ("Title (Korean Ver.)", "Artist, Featured", file names, ...),
        // so after the strict lookups the search is relaxed step by step.
        val title = cleanTitle(song.title)
        val artist = firstArtist(song.artistName)
        val queries = LinkedHashSet<String>()
        queries.add("track_name=${enc(song.title)}&artist_name=${enc(song.artistName)}")
        queries.add("track_name=${enc(title)}&artist_name=${enc(artist)}")
        queries.add("q=${enc("$title $artist")}")
        queries.add("q=${enc("$artist $title")}")
        queries.add("track_name=${enc(title)}")
        queries.add("q=${enc(title)}")
        var firstError: IOException? = null
        val seen = HashSet<Int>()
        val pool = ArrayList<JSONObject>()
        for (q in queries) {
            val list = try {
                request("$BASE/search?$q")
            } catch (e: IOException) {
                firstError = firstError ?: e
                null
            } ?: continue
            val array = JSONArray(list)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                if (seen.add(o.optInt("id", i * 7919 + q.hashCode()))) pool.add(o)
            }
            pick(pool, title, artist, seconds)?.let { return toFound(it) }
        }
        if (pool.isEmpty() && firstError != null) throw firstError
        return null
    }

    /**
     * The best candidate, or null when none is a convincing match. A wrong song is worse than none, so
     * the title must match (equal, or one contains the other and is most of it), the artist must match
     * when it is known, and the length must be close.
     */
    private fun pick(items: List<JSONObject>, title: String, artist: String, seconds: Int): JSONObject? {
        fun hasText(o: JSONObject, key: String) = !o.isNull(key) && o.optString(key).isNotBlank()
        val t = norm(title)
        val a = norm(artist)
        if (t.isEmpty()) return null
        var best: JSONObject? = null
        var bestScore = -1
        for (o in items) {
            if (!hasText(o, "syncedLyrics") && !hasText(o, "plainLyrics")) continue
            val ot = norm(o.optString("trackName"))
            val oa = norm(o.optString("artistName"))
            if (ot.isEmpty()) continue
            val exactTitle = ot == t
            val shorter = minOf(ot.length, t.length)
            val longer = maxOf(ot.length, t.length)
            val closeTitle = !exactTitle && shorter >= 3 && (ot.contains(t) || t.contains(ot)) &&
                    shorter.toFloat() / longer >= 0.6f
            if (!exactTitle && !closeTitle) continue
            val artistOk = a.isEmpty() || oa.contains(a) || (oa.length >= 2 && a.contains(oa))
            if (!artistOk) continue
            val durDiff = abs(o.optDouble("duration", 1e9) - seconds)
            if (durDiff > (if (exactTitle) 10 else 5)) continue
            var score = if (exactTitle) 10 else 6
            score += (5 - minOf(durDiff, 5.0)).toInt()
            if (hasText(o, "syncedLyrics")) score += 2
            if (score > bestScore) { bestScore = score; best = o }
        }
        return best
    }

    private val junk = Regex("""[(\[{][^)\]}]*[)\]}]|\s-\s.*$|\b(feat|ft)\.?\s.*$""", RegexOption.IGNORE_CASE)

    /** The title without brackets, "feat." parts and " - Remastered" style suffixes. */
    internal fun cleanTitle(raw: String): String =
        raw.replace(junk, " ").replace(Regex("\\s+"), " ").trim().ifEmpty { raw.trim() }

    /** "A, B & C" and "A feat. B" -> "A". */
    internal fun firstArtist(raw: String): String =
        raw.split(Regex("""\s*(,|&|;|/|\bfeat\.?|\bft\.?|\bx\b|×)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }?.trim() ?: raw.trim()

    /** Lower case, letters and digits of any script only, so Hangul survives. */
    private fun norm(v: String) = v.lowercase().filter { it.isLetterOrDigit() }

    private fun toFound(o: JSONObject): Found? {
        val synced = o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") && it.isNotBlank() }
        val plain = o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") && it.isNotBlank() }
        val instrumental = o.optBoolean("instrumental", false)
        return if (synced == null && plain == null && !instrumental) null
        else Found(synced, plain, instrumental)
    }

    /** Response body, or null for HTTP 404 (nothing found). */
    private fun request(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
