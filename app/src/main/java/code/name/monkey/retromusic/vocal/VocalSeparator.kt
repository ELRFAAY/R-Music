package code.name.monkey.retromusic.vocal

import android.content.Context
import android.net.Uri
import android.util.Log
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.MusicUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Singer only" mode. The model (MDX-Net Kim_Vocal_2, ~67 MB) is downloaded once, then everything
 * runs offline. The vocals of a song are written once to filesDir/vocal_stems and reused.
 * While [vocalMode] is on, `Song.uri` points to that file when it exists.
 */
object VocalSeparator {
    private const val TAG = "VocalSeparator"
    private const val MODEL_URL =
        "https://huggingface.co/huangjackson/Kim_Vocal_2/resolve/main/Kim_Vocal_2.onnx"
    private const val MODEL_MIN_BYTES = 60_000_000L
    private const val MAX_MINUTES = 15

    sealed class Status {
        object Idle : Status()
        data class Downloading(val percent: Int) : Status()
        data class Processing(val songId: Long, val percent: Int) : Status()
        data class Ready(val songId: Long) : Status()
        data class Error(val message: String) : Status()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var appContext: Context? = null

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    @Volatile
    var vocalMode = false
        private set

    /** Called (on a background thread) when the vocals of [songId] are ready. Set by MusicService. */
    @Volatile
    var onStemReady: ((songId: Long) -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun stemFile(context: Context, song: Song): File {
        val dir = File(context.filesDir, "vocal_stems")
        dir.mkdirs()
        return File(dir, "${song.id}_${song.dateModified}.wav")
    }

    /** Deletes every saved vocal track. Returns the freed bytes. */
    fun clearStems(context: Context): Long {
        var freed = 0L
        File(context.filesDir, "vocal_stems").listFiles()?.forEach {
            freed += it.length(); it.delete()
        }
        return freed
    }

    fun stemsSize(context: Context): Long =
        File(context.filesDir, "vocal_stems").listFiles()?.sumOf { it.length() } ?: 0L

    /** Keeps the stems folder under [maxBytes] by removing the oldest ones (never [keep]). */
    private fun trimStems(context: Context, keep: File, maxBytes: Long = 1_000_000_000L) {
        val files = File(context.filesDir, "vocal_stems").listFiles() ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f == keep) continue
            total -= f.length(); f.delete()
        }
    }

    private fun modelFile(context: Context): File {
        val dir = File(context.filesDir, "models")
        dir.mkdirs()
        return File(dir, "Kim_Vocal_2.onnx")
    }

    fun hasStem(song: Song): Boolean {
        val c = appContext ?: return false
        return stemFile(c, song).let { it.exists() && it.length() > 44 }
    }

    /** Where the player should read [song] from. */
    fun uriFor(song: Song): Uri {
        val c = appContext
        if (vocalMode && c != null) {
            val f = stemFile(c, song)
            if (f.exists() && f.length() > 44) return Uri.fromFile(f)
        }
        return MusicUtil.getSongFileUri(songId = song.id)
    }

    /** Tap on the button. Returns the new mode. */
    fun toggle(current: Song?): Boolean {
        vocalMode = !vocalMode
        if (vocalMode) {
            if (current != null && current.id >= 0) ensure(current)
        } else {
            job?.cancel()
            _status.value = Status.Idle
        }
        return vocalMode
    }

    /** A new song started while the mode is on. */
    fun onSongChanged(song: Song) {
        if (vocalMode && song.id >= 0) ensure(song)
    }

    private fun ensure(song: Song) {
        val c = appContext ?: return
        if (hasStem(song)) {
            _status.value = Status.Ready(song.id)
            return
        }
        job?.cancel()
        job = scope.launch {
            var stage = "start"
            try {
                if (song.duration > MAX_MINUTES * 60_000L) {
                    _status.value = Status.Error("long")
                    return@launch
                }
                val model = modelFile(c)
                if (!model.exists() || model.length() < MODEL_MIN_BYTES) {
                    stage = "download"
                    download(model)
                }
                if (!isActive) return@launch
                _status.value = Status.Processing(song.id, 0)
                stage = "decode"
                val pcm = AudioDecoder.decode(
                    c, MusicUtil.getSongFileUri(song.id), song.duration
                ) { !isActive }
                stage = "model"
                val out = stemFile(c, song)
                val tmp = File(out.parentFile, out.name + ".part")
                MdxSeparator(model).use { sep ->
                    stage = "separate"
                    sep.separate(pcm, tmp, { !isActive }) { p ->
                        _status.value = Status.Processing(song.id, p)
                    }
                }
                if (!isActive) {
                    tmp.delete()
                    return@launch
                }
                tmp.renameTo(out)
                trimStems(c, out)
                _status.value = Status.Ready(song.id)
                onStemReady?.invoke(song.id)
            } catch (e: InterruptedException) {
                Log.i(TAG, "cancelled")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "separation failed", e)
                _status.value = Status.Error("$stage: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun download(target: File) {
        val part = File(target.parentFile, target.name + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        output.write(buf, 0, r)
                        done += r
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _status.value = Status.Downloading(pct)
                            }
                        }
                    }
                }
            }
            if (part.length() < MODEL_MIN_BYTES) {
                part.delete()
                throw IllegalStateException("model download incomplete")
            }
            part.renameTo(target)
        } finally {
            conn?.disconnect()
        }
    }
}
