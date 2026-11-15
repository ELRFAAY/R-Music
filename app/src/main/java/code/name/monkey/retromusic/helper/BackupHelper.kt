package code.name.monkey.retromusic.helper

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.ALBUM_COVER_STYLE
import code.name.monkey.retromusic.ALBUM_COVER_TRANSFORM
import code.name.monkey.retromusic.BuildConfig
import code.name.monkey.retromusic.CAROUSEL_EFFECT
import code.name.monkey.retromusic.Constants
import code.name.monkey.retromusic.HOME_COVERS_ENABLED
import code.name.monkey.retromusic.LAST_USED_TAB
import code.name.monkey.retromusic.LIBRARY_CATEGORIES
import code.name.monkey.retromusic.REMEMBER_LAST_TAB
import code.name.monkey.retromusic.TAB_TEXT_MODE
import code.name.monkey.retromusic.TOGGLE_HOME_BANNER
import code.name.monkey.retromusic.BACKUP_FOLDER_URI
import code.name.monkey.retromusic.USER_NAME
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.HistoryEntity
import code.name.monkey.retromusic.db.ListeningEventEntity
import code.name.monkey.retromusic.db.PlayCountEntity
import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.RetroDatabase
import code.name.monkey.retromusic.db.toHistoryEntity
import code.name.monkey.retromusic.db.toPlayCount
import code.name.monkey.retromusic.db.toSongEntity
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.extensions.zipOutputStream
import code.name.monkey.retromusic.db.ArtistAliasEntity
import code.name.monkey.retromusic.helper.BackupContent.*
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.ArtistGroups
import code.name.monkey.retromusic.repository.Repository
import code.name.monkey.retromusic.repository.SongRepository
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.getExternalStoragePublicDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object BackupHelper : KoinComponent {
    private val repository by inject<Repository>()
    private val songRepository by inject<SongRepository>()
    private val database by inject<RetroDatabase>()

    /** Returns true when the backup file was written. [silent] skips the toast (automatic backups). */
    suspend fun createBackup(context: Context, name: String, silent: Boolean = false): Boolean {
        // Build the zip in the cache first, then copy it to the chosen folder.
        val tempFile = File(context.cacheDir, name + APPEND_EXTENSION)
        val zipItems = mutableListOf<ZipItem>()
        zipItems.addAll(getPlaylistZipItems(context))
        zipItems.addAll(getSettingsZipItems(context))
        getUserImageZipItems(context)?.let { zipItems.addAll(it) }
        zipItems.addAll(getCustomArtistZipItems(context))
        zipItems.addAll(getListeningDataZipItems(context))
        zipItems.addAll(getLyricsZipItems(context))
        zipItems.addAll(getArtistGroupsZipItems(context))
        zipItems.addAll(getUserDataZipItems(context))
        val done = zipAll(zipItems, tempFile) && deliver(context, tempFile, name)
        if (!silent) {
            withContext(Dispatchers.Main) {
                context.showToast(
                    if (done) R.string.message_backup_create_success else R.string.error_create_backup
                )
            }
        }
        tempFile.delete()
        // Clean Cache Playlist Directory
        File(context.filesDir, PLAYLISTS_PATH).deleteRecursively()
        File(context.filesDir, LISTENING_PATH).deleteRecursively()
        File(context.filesDir, ARTIST_GROUPS_PATH).deleteRecursively()
        File(context.filesDir, USER_DATA_PATH).deleteRecursively()
        return done
    }

    /** Deletes the oldest automatic backups, keeping the newest [keep]. Manual ones are never touched. */
    fun deleteOldAutoBackups(context: Context, keep: Int) {
        // The sortable timestamp in the name puts the newest first when sorted in reverse.
        val old = listBackups(context)
            .filter { it.name.startsWith(AUTO_BACKUP_PREFIX) }
            .sortedByDescending { it.name }
            .drop(keep.coerceAtLeast(1))
        old.forEach { entry ->
            runCatching {
                val file = entry.file
                if (file != null) file.delete()
                else DocumentFile.fromSingleUri(context, entry.uri)?.delete()
            }
        }
    }

    /** Name for an automatic backup, for example AutoBackup_2026-11-15_101500. */
    fun autoBackupName(): String =
        AUTO_BACKUP_PREFIX + SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())

    private suspend fun zipAll(zipItems: List<ZipItem>, backupFile: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                backupFile.outputStream().buffered().zipOutputStream().use { out ->
                    for (zipItem in zipItems) {
                        File(zipItem.filePath).inputStream().buffered().use { origin ->
                            val entry = ZipEntry(zipItem.zipPath)
                            out.putNextEntry(entry)
                            origin.copyTo(out)
                        }
                    }
                }
            }.isSuccess
        }

    /** Copies the finished zip to the folder the user picked, or the old default folder. */
    private suspend fun deliver(context: Context, tempFile: File, name: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val fileName = name + APPEND_EXTENSION
                val treeUri = PreferenceUtil.backupFolderUri
                if (treeUri != null) {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                    check(tree != null && tree.canWrite()) { "backup folder is not writable" }
                    tree.findFile(fileName)?.delete()
                    val target = tree.createFile("application/octet-stream", fileName)
                        ?: error("could not create $fileName")
                    context.contentResolver.openOutputStream(target.uri, "w")!!.use { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                    }
                } else {
                    val target = File(getBackupRoot(), fileName)
                    target.parentFile?.mkdirs()
                    tempFile.copyTo(target, overwrite = true)
                }
            }.isSuccess
        }

    /** The chosen backup folder, or null when none is set or it can no longer be used. */
    fun backupFolder(context: Context): DocumentFile? {
        val treeUri = PreferenceUtil.backupFolderUri ?: return null
        return runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.takeIf { it.canWrite() }
        }.getOrNull()
    }

    /** Backups in the chosen folder, or in the old default folder when none was chosen. */
    fun listBackups(context: Context): List<BackupEntry> {
        if (PreferenceUtil.backupFolderUri != null) {
            val tree = backupFolder(context) ?: return emptyList()
            return runCatching {
                tree.listFiles()
                    .filter { it.isFile && it.name?.endsWith(APPEND_EXTENSION) == true }
                    .sortedByDescending { it.lastModified() }
                    .map { BackupEntry(it.name!!.removeSuffix(APPEND_EXTENSION), it.uri, null) }
            }.getOrDefault(emptyList())
        }
        return getBackupRoot().listFiles { _, fileName ->
            fileName.endsWith(BACKUP_EXTENSION)
        }?.map { BackupEntry(it.nameWithoutExtension, it.toUri(), it) } ?: emptyList()
    }

    /**
     * Artist names the user grouped as one artist. Saved by NAME, so the groups come back on a new
     * phone, and restored even when some of the artists are not in the library (yet).
     */
    private suspend fun getArtistGroupsZipItems(context: Context): List<ZipItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rows = ArtistGroups(database.artistAliasDao()).allRows()
                if (rows.isEmpty()) return@runCatching emptyList<ZipItem>()
                val folder = File(context.filesDir, ARTIST_GROUPS_PATH)
                if (!folder.exists()) folder.mkdirs()
                val root = JSONObject()
                root.put("version", 1)
                root.put("groups", JSONArray().also { arr ->
                    rows.forEach {
                        arr.put(JSONObject().put("alias", it.aliasName).put("main", it.mainName))
                    }
                })
                val file = File(folder, ARTIST_GROUPS_FILE)
                file.writeText(root.toString(), Charsets.UTF_8)
                listOf(ZipItem(file.absolutePath, ARTIST_GROUPS_PATH.child(ARTIST_GROUPS_FILE)))
            }.getOrDefault(emptyList())
        }

    private fun restoreArtistGroups(context: Context, zipIn: ZipInputStream) {
        val root = JSONObject(zipIn.readBytes().toString(Charsets.UTF_8))
        val arr = root.optJSONArray("groups") ?: return
        val rows = mutableListOf<ArtistAliasEntity>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val alias = o.optString("alias").trim()
            val main = o.optString("main").trim()
            if (alias.isNotEmpty() && main.isNotEmpty() && !alias.equals(main, ignoreCase = true)) {
                rows += ArtistAliasEntity(alias, main)
            }
        }
        ArtistGroups(database.artistAliasDao()).restoreRows(rows)
        // The tags of the files may not carry these merges yet: the app offers to write them.
        if (rows.isNotEmpty()) {
            runCatching {
                code.name.monkey.retromusic.fragments.artists.ArtistMerger.pendingMarker(context)
                    .writeText("1")
            }
        }
    }

    /** Lyrics fetched from LRCLIB or synced by hand, kept in the app's own storage. */
    private fun getLyricsZipItems(context: Context): List<ZipItem> {
        return File(context.filesDir, LYRICS_PATH).listFiles()?.filter { it.isFile }?.map {
            ZipItem(it.absolutePath, LYRICS_PATH.child(it.name))
        } ?: emptyList()
    }

    private fun restoreLyrics(context: Context, zipIn: ZipInputStream, zipEntry: ZipEntry) {
        val folder = File(context.filesDir, LYRICS_PATH)
        folder.mkdirs()
        // Only the file name is used, so an entry can never write outside the lyrics folder.
        File(folder, zipEntry.getFileName()).outputStream().buffered().use { out ->
            zipIn.copyTo(out)
        }
    }

    private suspend fun getListeningDataZipItems(context: Context): List<ZipItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = File(context.filesDir, LISTENING_PATH)
                if (!folder.exists()) folder.mkdirs()
                val root = JSONObject()
                root.put("version", 2)
                root.put("history", JSONArray().also { arr ->
                    database.historyDao().allHistory().forEach { arr.put(it.toJson()) }
                })
                root.put("playCounts", JSONArray().also { arr ->
                    database.playCountDao().playCountSongs().forEach { arr.put(it.toJson()) }
                })
                // Added in version 2; older backups simply don't have this key.
                root.put("events", JSONArray().also { arr ->
                    database.listeningEventDao().allEvents().forEach { arr.put(it.toJson()) }
                })
                val file = File(folder, LISTENING_FILE)
                file.writeText(root.toString(), Charsets.UTF_8)
                listOf(ZipItem(file.absolutePath, LISTENING_PATH.child(LISTENING_FILE)))
            }.getOrDefault(emptyList())
        }

    // Songs are matched by title/artist/album/duration, not by MediaStore id or file path,
    // because both change when the library moves to another device.
    private suspend fun restoreListeningData(zipIn: ZipInputStream) {
        val text = zipIn.readBytes().toString(Charsets.UTF_8)
        val root = JSONObject(text)
        val songs = songRepository.songs()
        val byFullKey = songs.groupBy { songKey(it.title, it.artistName, it.albumName) }
        val byShortKey = songs.groupBy { songKey(it.title, it.artistName, null) }

        fun findSong(o: JSONObject): Song? {
            val title = o.optString("title")
            val artist = o.optString("artist_name")
            val album = o.optString("album_name")
            val duration = o.optLong("duration")
            val candidates = byFullKey[songKey(title, artist, album)]
                ?: byShortKey[songKey(title, artist, null)]
                ?: return null
            return candidates.firstOrNull { kotlin.math.abs(it.duration - duration) <= 2000 }
                ?: candidates.first()
        }

        val historyDao = database.historyDao()
        val playCountDao = database.playCountDao()
        root.optJSONArray("history")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val song = findSong(o) ?: continue
                historyDao.upsertSongInHistory(song.toHistoryEntity(o.optLong("time_played")))
            }
        }
        root.optJSONArray("playCounts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val song = findSong(o) ?: continue
                val existing = playCountDao.findSongExistInPlayCount(song.id)
                val count = maxOf(o.optInt("play_count"), existing?.playCount ?: 0)
                val timePlayed = maxOf(o.optLong("time_played"), existing?.timePlayed ?: 0L)
                playCountDao.upsertSongInPlayCount(
                    song.toPlayCount().let {
                        PlayCountEntity(
                            id = it.id, title = it.title, trackNumber = it.trackNumber,
                            year = it.year, duration = it.duration, data = it.data,
                            dateModified = it.dateModified, albumId = it.albumId,
                            albumName = it.albumName, artistId = it.artistId,
                            artistName = it.artistName, composer = it.composer,
                            albumArtist = it.albumArtist, timePlayed = timePlayed,
                            playCount = count
                        )
                    }
                )
            }
        }
        // Events keep their own title/artist/album, so they are restored even when the song
        // is no longer in the library; songId is only filled in when a match is found.
        val eventDao = database.listeningEventDao()
        root.optJSONArray("events")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val title = o.optString("title")
                val artist = o.optString("artist_name")
                val playedAt = o.optLong("played_at")
                if (eventDao.countSameEvent(playedAt, title, artist) > 0) continue
                eventDao.insert(
                    ListeningEventEntity(
                        songId = findSong(o)?.id ?: -1L,
                        title = title,
                        artistName = artist,
                        albumName = o.optString("album_name"),
                        duration = o.optLong("duration"),
                        playedAt = playedAt,
                        listenedMs = o.optLong("listened_ms")
                    )
                )
            }
        }
    }

    private fun songKey(title: String, artist: String, album: String?): String {
        fun n(s: String) = s.trim().lowercase(Locale.ROOT)
        return if (album == null) "${n(title)}|${n(artist)}" else "${n(title)}|${n(artist)}|${n(album)}"
    }

    private fun HistoryEntity.toJson() = JSONObject().apply {
        put("title", title); put("artist_name", artistName); put("album_name", albumName)
        put("duration", duration); put("track_number", trackNumber); put("year", year)
        put("composer", composer ?: JSONObject.NULL); put("album_artist", albumArtist ?: JSONObject.NULL)
        put("time_played", timePlayed)
    }

    private fun ListeningEventEntity.toJson() = JSONObject().apply {
        put("title", title); put("artist_name", artistName); put("album_name", albumName)
        put("duration", duration); put("played_at", playedAt); put("listened_ms", listenedMs)
    }

    private fun PlayCountEntity.toJson() = JSONObject().apply {
        put("title", title); put("artist_name", artistName); put("album_name", albumName)
        put("duration", duration); put("track_number", trackNumber); put("year", year)
        put("composer", composer ?: JSONObject.NULL); put("album_artist", albumArtist ?: JSONObject.NULL)
        put("time_played", timePlayed); put("play_count", playCount)
    }

    private suspend fun getPlaylistZipItems(context: Context): List<ZipItem> {
        val playlistZipItems = mutableListOf<ZipItem>()
        // Cache Playlist files in App storage
        val playlistFolder = File(context.filesDir, PLAYLISTS_PATH)
        if (!playlistFolder.exists()) {
            playlistFolder.mkdirs()
        }
        for (playlist in repository.fetchPlaylistWithSongs()) {
            runCatching {
                M3UWriter.writeIO(playlistFolder, playlist)
            }.onSuccess { playlistFile ->
                if (playlistFile.exists()) {
                    playlistZipItems.add(
                        ZipItem(
                            playlistFile.absolutePath,
                            PLAYLISTS_PATH.child(playlistFile.name)
                        )
                    )
                }
            }
        }
        return playlistZipItems
    }

    /**
     * "User data": the user name, profile image, banner and the look of the library (grid styles,
     * items per row, sort orders, tabs, home options). Preferences are stored as typed JSON by key,
     * so a backup restores into any build of the app (debug / fast / release) whatever its package id.
     */
    private fun getUserDataZipItems(context: Context): List<ZipItem> {
        val dir = File(context.filesDir, USER_DATA_PATH).apply { mkdirs() }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val arr = JSONArray()
        for ((key, value) in prefs.all) {
            if (!isUserDataKey(key)) continue
            val o = JSONObject().put("k", key)
            when (value) {
                is Boolean -> o.put("t", "b").put("v", value)
                is Int -> o.put("t", "i").put("v", value)
                is Long -> o.put("t", "l").put("v", value)
                is Float -> o.put("t", "f").put("v", value.toDouble())
                is String -> o.put("t", "s").put("v", value)
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            arr.put(o)
        }
        val file = File(dir, USER_DATA_FILE)
        file.writeText(JSONObject().put("version", 1).put("prefs", arr).toString())
        val items = mutableListOf(ZipItem(file.absolutePath, USER_DATA_PATH.child(USER_DATA_FILE)))
        for (name in USER_DATA_IMAGES) {
            val image = File(context.filesDir, name)
            if (image.exists()) items += ZipItem(image.absolutePath, USER_DATA_PATH.child(name))
        }
        return items
    }

    private fun isUserDataKey(key: String): Boolean =
        key in USER_DATA_KEYS || key.contains("grid_style") || key.contains("grid_size") ||
                key.contains("sort_order") || key.startsWith("home_")

    private fun restoreUserDataImage(context: Context, zipIn: ZipInputStream, zipEntry: ZipEntry) {
        val name = zipEntry.getFileName()
        if (name !in USER_DATA_IMAGES) return // only the two known files, never an arbitrary path
        File(context.filesDir, name).outputStream().buffered().use { zipIn.copyTo(it) }
    }

    /** Writes the saved values into the app's preferences and flushes them to disk right away. */
    private fun applyUserDataPrefs(context: Context, root: JSONObject) {
        val arr = root.optJSONArray("prefs") ?: return
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("k")
            if (key.isEmpty() || !isUserDataKey(key)) continue
            when (o.optString("t")) {
                "b" -> editor.putBoolean(key, o.optBoolean("v"))
                "i" -> editor.putInt(key, o.optInt("v"))
                "l" -> editor.putLong(key, o.optLong("v"))
                "f" -> editor.putFloat(key, o.optDouble("v").toFloat())
                "s" -> editor.putString(key, o.optString("v"))
                "ss" -> {
                    val values = o.optJSONArray("v") ?: continue
                    editor.putStringSet(key, (0 until values.length()).map { values.optString(it) }.toSet())
                }
            }
        }
        editor.commit()
    }

    /**
     * All app settings (the main preferences and the theme preferences) as typed JSON, key by key,
     * so they move to any build (debug / fast / release) exactly as they are. Only values that
     * belong to one phone (the picked backup folder) are left out.
     */
    private fun getSettingsZipItems(context: Context): List<ZipItem> {
        val dir = File(context.filesDir, APP_SETTINGS_PATH).apply { mkdirs() }
        val root = JSONObject().put("version", 1)
        for ((name, prefs) in settingsPrefs(context)) {
            val arr = JSONArray()
            for ((key, value) in prefs.all) {
                if (key in SETTINGS_SKIP_KEYS) continue
                val o = JSONObject().put("k", key)
                when (value) {
                    is Boolean -> o.put("t", "b").put("v", value)
                    is Int -> o.put("t", "i").put("v", value)
                    is Long -> o.put("t", "l").put("v", value)
                    is Float -> o.put("t", "f").put("v", value.toDouble())
                    is String -> o.put("t", "s").put("v", value)
                    is Set<*> -> o.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                    else -> continue
                }
                arr.put(o)
            }
            root.put(name, arr)
        }
        val file = File(dir, APP_SETTINGS_FILE)
        file.writeText(root.toString())
        return listOf(ZipItem(file.absolutePath, APP_SETTINGS_PATH.child(APP_SETTINGS_FILE)))
    }

    private fun settingsPrefs(context: Context): Map<String, android.content.SharedPreferences> = mapOf(
        "main" to PreferenceManager.getDefaultSharedPreferences(context),
        "theme" to context.getSharedPreferences(THEME_PREFS_KEY_DEFAULT, Context.MODE_PRIVATE)
    )

    /** Replaces the settings with the saved ones (keeping this phone's own backup folder) and writes them to disk now. */
    private fun restoreAppSettings(context: Context, root: JSONObject) {
        for ((name, prefs) in settingsPrefs(context)) {
            val arr = root.optJSONArray(name) ?: continue
            val kept = prefs.all.filterKeys { it in SETTINGS_SKIP_KEYS }
            val editor = prefs.edit().clear()
            for ((k, v) in kept) when (v) {
                is Boolean -> editor.putBoolean(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Float -> editor.putFloat(k, v)
                is String -> editor.putString(k, v)
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("k")
                if (key.isEmpty() || key in SETTINGS_SKIP_KEYS) continue
                when (o.optString("t")) {
                    "b" -> editor.putBoolean(key, o.optBoolean("v"))
                    "i" -> editor.putInt(key, o.optInt("v"))
                    "l" -> editor.putLong(key, o.optLong("v"))
                    "f" -> editor.putFloat(key, o.optDouble("v").toFloat())
                    "s" -> editor.putString(key, o.optString("v"))
                    "ss" -> {
                        val values = o.optJSONArray("v") ?: continue
                        editor.putStringSet(key, (0 until values.length()).map { values.optString(it) }.toSet())
                    }
                }
            }
            editor.commit()
        }
    }

    private fun getUserImageZipItems(context: Context): List<ZipItem>? {
        return context.filesDir.listFiles { _, name ->
            name.endsWith(".jpg")
        }?.map {
            ZipItem(it.absolutePath, IMAGES_PATH.child(it.name))
        }
    }

    private fun getCustomArtistZipItems(context: Context): List<ZipItem> {
        val zipItemList = mutableListOf<ZipItem>()
        val sharedPrefPath = File(context.filesDir.parentFile, "shared_prefs")

        zipItemList.addAll(
            File(context.filesDir, "custom_artist_images")
                .listFiles()?.map {
                    ZipItem(
                        it.absolutePath,
                        CUSTOM_ARTISTS_PATH.child("custom_artist_images").child(it.name)
                    )
                }?.toList() ?: listOf()
        )
        File(sharedPrefPath, "custom_artist_image.xml").let {
            if (it.exists()) {
                zipItemList.add(
                    ZipItem(
                        it.absolutePath,
                        CUSTOM_ARTISTS_PATH.child("prefs").child("custom_artist_image.xml")
                    )
                )
            }
        }
        return zipItemList
    }

    suspend fun restoreBackup(
        context: Context,
        inputStream: InputStream?,
        contents: List<BackupContent>
    ) {
        withContext(Dispatchers.IO) {
            var userDataPrefs: JSONObject? = null
            ZipInputStream(inputStream).use {
                var entry = it.nextEntry
                while (entry != null) {
                    if (entry.isUserDataEntry() && contents.contains(USER_DATA)) {
                        if (entry.getFileName() == USER_DATA_FILE) {
                            userDataPrefs = JSONObject(it.readBytes().toString(Charsets.UTF_8))
                        } else {
                            restoreUserDataImage(context, it, entry)
                        }
                    } else if (entry.isPlaylistEntry() && contents.contains(PLAYLISTS)) {
                        restorePlaylists(it, entry)
                    } else if (entry.isAppSettingsEntry() && contents.contains(SETTINGS)) {
                        restoreAppSettings(context, JSONObject(it.readBytes().toString(Charsets.UTF_8)))
                    } else if (entry.isPreferenceEntry() && contents.contains(SETTINGS)) {
                        restorePreferences(context, it, entry) // backups made before the JSON settings
                    } else if (entry.isImageEntry() && contents.contains(USER_IMAGES)) {
                        restoreImages(context, it, entry)
                    } else if (entry.isListeningEntry() && contents.contains(LISTENING_DATA)) {
                        restoreListeningData(it)
                    } else if (entry.isLyricsEntry() && contents.contains(LYRICS)) {
                        restoreLyrics(context, it, entry)
                    } else if (entry.isArtistGroupsEntry() && contents.contains(ARTIST_GROUPS)) {
                        restoreArtistGroups(context, it)
                    } else if (entry.isCustomArtistEntry() && contents.contains(CUSTOM_ARTIST_IMAGES)) {
                        if (entry.isCustomArtistPrefEntry()) {
                            restoreCustomArtistPrefs(context, it, entry)
                        } else if (entry.isCustomArtistImageEntry()) {
                            restoreCustomArtistImages(context, it, entry)
                        }
                    }
                    entry = it.nextEntry
                }
            }
            // When the whole settings file was restored it already holds these values; writing them
            // through the live preferences as well would overwrite that file with the old in-memory copy.
            userDataPrefs?.let { json ->
                if (!contents.contains(SETTINGS)) applyUserDataPrefs(context, json)
            }
            withContext(Dispatchers.Main) {
                context.showToast(R.string.message_restore_success)
            }
        }
    }

    private fun restoreImages(context: Context, zipIn: ZipInputStream, zipEntry: ZipEntry) {
        val file = File(
            context.filesDir.path, zipEntry.getFileName()
        )
        file.outputStream().buffered().use { bos ->
            zipIn.copyTo(bos)
        }
    }

    private fun restorePreferences(context: Context, zipIn: ZipInputStream, zipEntry: ZipEntry) {
        // The file is named after the package id of the build that made the backup (debug, fast,
        // release...). Restore it under THIS build's name, or the app would never read it.
        val entryName = zipEntry.getFileName()
        val targetName = if (entryName.endsWith("_preferences.xml")) {
            "${context.packageName}_preferences.xml"
        } else entryName
        val file = File(
            context.filesDir.parent!! + File.separator + "shared_prefs" + File.separator + targetName
        )
        if (file.exists()) {
            file.delete()
        }
        file.outputStream().buffered().use { bos ->
            zipIn.copyTo(bos)
        }
    }

    private suspend fun restorePlaylists(
        zipIn: ZipInputStream,
        zipEntry: ZipEntry
    ) {
        val playlistName = zipEntry.getFileName().substringBeforeLast(".")
        val songs = mutableListOf<Song>()

        // Get songs from m3u playlist files
        zipIn.bufferedReader().lineSequence().forEach { line ->
            if (line.startsWith(File.separator)) {
                if (File(line).exists()) {
                    songs.addAll(songRepository.songsByFilePath(line))
                }
            }
        }
        val playlistEntity = repository.checkPlaylistExists(playlistName).firstOrNull()
        if (playlistEntity != null) {
            val songEntities = songs.map {
                it.toSongEntity(playlistEntity.playListId)
            }
            repository.insertSongs(songEntities)
        } else {
            val playListId = repository.createPlaylist(PlaylistEntity(playlistName = playlistName))
            val songEntities = songs.map {
                it.toSongEntity(playListId)
            }
            repository.insertSongs(songEntities)
        }
    }

    private fun restoreCustomArtistImages(
        context: Context,
        zipIn: ZipInputStream,
        zipEntry: ZipEntry
    ) {
        val parentFolder = File(context.filesDir, "custom_artist_images")

        if (!parentFolder.exists()) {
            parentFolder.mkdirs()
        }
        val file = File(
            parentFolder,
            zipEntry.getFileName()
        )
        file.outputStream().buffered()
            .use { bos ->
                zipIn.copyTo(bos)
            }
    }

    private fun restoreCustomArtistPrefs(
        context: Context,
        zipIn: ZipInputStream,
        zipEntry: ZipEntry
    ) {
        val file =
            File(context.filesDir.parentFile, "shared_prefs".child(zipEntry.getFileName()))
        file.outputStream().buffered().use { bos ->
            zipIn.copyTo(bos)
        }
    }

    fun getBackupRoot(): File {
        return File(
            getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "RetroMusic/Backups"
        )
    }

    const val AUTO_BACKUP_PREFIX = "AutoBackup_"
    const val BACKUP_EXTENSION = "rmbak"
    const val APPEND_EXTENSION = ".$BACKUP_EXTENSION"
    private const val PLAYLISTS_PATH = "Playlists"
    private const val SETTINGS_PATH = "prefs"
    private const val IMAGES_PATH = "userImages"
    private const val APP_SETTINGS_PATH = "appsettings"
    private const val APP_SETTINGS_FILE = "settings.json"
    private val SETTINGS_SKIP_KEYS = setOf(BACKUP_FOLDER_URI)
    private const val USER_DATA_PATH = "userdata"
    private const val USER_DATA_FILE = "user_data.json"
    private val USER_DATA_IMAGES = listOf(Constants.USER_PROFILE, Constants.USER_BANNER)
    private val USER_DATA_KEYS = setOf(
        USER_NAME, LIBRARY_CATEGORIES, TOGGLE_HOME_BANNER, CAROUSEL_EFFECT, ALBUM_COVER_STYLE,
        ALBUM_COVER_TRANSFORM, TAB_TEXT_MODE, REMEMBER_LAST_TAB, LAST_USED_TAB, HOME_COVERS_ENABLED
    )
    private const val LISTENING_PATH = "listening"
    private const val LYRICS_PATH = "lyrics"
    private const val ARTIST_GROUPS_PATH = "artistgroups"
    private const val ARTIST_GROUPS_FILE = "artist_groups.json"
    private const val LISTENING_FILE = "listening.json"
    private const val CUSTOM_ARTISTS_PATH = "artistImages"
    private const val THEME_PREFS_KEY_DEFAULT = "[[kabouzeid_app-theme-helper]]"

    private fun ZipEntry.isPlaylistEntry(): Boolean {
        return name.startsWith(PLAYLISTS_PATH)
    }

    private fun ZipEntry.isAppSettingsEntry(): Boolean {
        return name.startsWith(APP_SETTINGS_PATH + File.separator)
    }

    private fun ZipEntry.isPreferenceEntry(): Boolean {
        return name.startsWith(SETTINGS_PATH)
    }

    private fun ZipEntry.isUserDataEntry(): Boolean {
        return name.startsWith(USER_DATA_PATH + File.separator)
    }

    private fun ZipEntry.isLyricsEntry(): Boolean {
        return name.startsWith(LYRICS_PATH + File.separator)
    }

    private fun ZipEntry.isArtistGroupsEntry(): Boolean {
        return name.startsWith(ARTIST_GROUPS_PATH)
    }

    private fun ZipEntry.isListeningEntry(): Boolean {
        return name.startsWith(LISTENING_PATH)
    }

    private fun ZipEntry.isImageEntry(): Boolean {
        return name.startsWith(IMAGES_PATH)
    }

    private fun ZipEntry.isCustomArtistEntry(): Boolean {
        return name.startsWith(CUSTOM_ARTISTS_PATH)
    }

    private fun ZipEntry.isCustomArtistImageEntry(): Boolean {
        return name.startsWith(CUSTOM_ARTISTS_PATH) && name.contains("custom_artist_images")
    }

    private fun ZipEntry.isCustomArtistPrefEntry(): Boolean {
        return name.startsWith(CUSTOM_ARTISTS_PATH) && name.contains("prefs")
    }

    private fun ZipEntry.getFileName(): String {
        return name.substring(name.lastIndexOf(File.separator) + 1)
    }

    fun getTimeStamp(): String {
        return SimpleDateFormat("dd-MMM yyyy HHmmss", Locale.getDefault()).format(Date())
    }
}

data class ZipItem(val filePath: String, val zipPath: String)

fun CharSequence.sanitize(): String {
    return toString().replace("/", "_")
        .replace(":", "_")
        .replace("*", "_")
        .replace("?", "_")
        .replace("\"", "_")
        .replace("<", "_")
        .replace(">", "_")
        .replace("|", "_")
        .replace("\\", "_")
        .replace("&", "_")
}

fun String.child(child: String): String {
    return this + File.separator + child
}

enum class BackupContent {
    SETTINGS,
    USER_IMAGES,
    CUSTOM_ARTIST_IMAGES,
    PLAYLISTS,
    LISTENING_DATA,
    LYRICS,
    ARTIST_GROUPS,
    USER_DATA
}