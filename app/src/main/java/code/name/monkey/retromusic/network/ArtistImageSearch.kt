package code.name.monkey.retromusic.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** One suggested picture for an artist: where it came from, what it is called, and its address. */
data class ArtistImageCandidate(val title: String, val source: String, val url: String)

/** Looks for artist pictures online (Deezer and Wikipedia, no account or key needed). */
object ArtistImageSearch {

    suspend fun search(context: Context, query: String): List<ArtistImageCandidate> =
        withContext(Dispatchers.IO) {
            val name = query.trim()
            if (name.isEmpty()) return@withContext emptyList()
            val client = DeezerService.createDefaultOkHttpClient(context)
            val out = ArrayList<ArtistImageCandidate>()
            runCatching { out += deezer(client, name) }
            runCatching { out += wikipedia(client, "en", name) }
            if (name.any { it in '\u0600'..'\u06FF' }) {
                runCatching { out += wikipedia(client, "ar", name) }
            }
            out.distinctBy { it.url }
        }

    private fun deezer(client: OkHttpClient, name: String): List<ArtistImageCandidate> {
        val body = DeezerService.invoke(client).searchArtists(name, 8).execute().body()
            ?: return emptyList()
        return body.data.mapNotNull { d ->
            val url = d.pictureXl
            // artists without a picture come back with an empty image id
            if (url.isNullOrBlank() || url.contains("/artist//")) null
            else ArtistImageCandidate(d.name, "Deezer", url)
        }
    }

    private fun wikipedia(client: OkHttpClient, lang: String, name: String): List<ArtistImageCandidate> {
        val q = URLEncoder.encode(name, "UTF-8")
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&generator=search" +
            "&gsrsearch=$q&gsrlimit=6&prop=pageimages&piprop=thumbnail&pithumbsize=900&format=json"
        val request = Request.Builder().url(url).header("User-Agent", "RMusic/1.0").build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: return emptyList()
            val pages = JSONObject(text).optJSONObject("query")?.optJSONObject("pages")
                ?: return emptyList()
            val list = ArrayList<ArtistImageCandidate>()
            for (key in pages.keys()) {
                val page = pages.getJSONObject(key)
                val thumb = page.optJSONObject("thumbnail")?.optString("source").orEmpty()
                if (thumb.isNotEmpty()) {
                    list.add(ArtistImageCandidate(page.optString("title"), "Wikipedia", thumb))
                }
            }
            return list
        }
    }
}
