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
package code.name.monkey.retromusic.fragments.artists

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.tageditor.TagWriter
import code.name.monkey.retromusic.db.RetroDatabase
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.model.AudioTagInfo
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.ArtistGroups
import code.name.monkey.retromusic.repository.Repository
import code.name.monkey.retromusic.util.MusicUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.tag.FieldKey
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * Writes a new artist name into the tags of a list of songs, so that two artists that are really
 * the same one (different spelling, "feat." variants) end up as a single artist in the library.
 * Every successful merge is also remembered BY NAME (old name -> new name, table artist_aliases),
 * which goes into the backup; after a restore, [findPendingGroups] finds the songs whose files are
 * not merged yet so the same merge can be written into them again.
 * Create it as a property of the fragment or activity: it registers an activity result launcher.
 */
class ArtistMerger private constructor(
    caller: ActivityResultCaller,
    private val contextProvider: () -> Context?,
    private val scopeProvider: () -> CoroutineScope
) {
    constructor(fragment: Fragment) : this(
        fragment, { fragment.context?.applicationContext }, { fragment.lifecycleScope }
    )

    constructor(activity: ComponentActivity) : this(
        activity, { activity.applicationContext }, { activity.lifecycleScope }
    )

    private class Item(val cache: File, val uri: Uri, val path: String)

    private var items: List<Item> = emptyList()
    private var recorded: Map<String, List<String>> = emptyMap()
    private var onDone: (() -> Unit)? = null

    private val launcher = caller.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            copyBack()
        } else {
            cleanUp()
        }
    }

    fun merge(songs: List<Song>, newName: String, done: () -> Unit) =
        mergeGroups(mapOf(newName.trim() to songs), done)

    /** [groups]: new artist name -> the songs that get it. One permission request for all. */
    fun mergeGroups(groups: Map<String, List<Song>>, done: () -> Unit) {
        val context = contextProvider() ?: return
        val clean = groups.filter { it.key.isNotBlank() && it.value.isNotEmpty() }
        if (clean.isEmpty()) return
        onDone = done
        recorded = clean.mapValues { (name, songs) ->
            songs.flatMap { listOfNotNull(it.artistName, it.albumArtist) }
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
                .distinct()
        }
        scopeProvider().launch {
            if (VersionUtils.hasR()) {
                val all = ArrayList<Item>()
                for ((name, songs) in clean) {
                    val tags = mapOf(FieldKey.ARTIST to name, FieldKey.ALBUM_ARTIST to name)
                    val files = TagWriter.writeTagsToFilesR(
                        context, AudioTagInfo(songs.map { it.data }, tags, null)
                    )
                    if (files.size != songs.size) {
                        files.forEach { it.delete() }
                        all.forEach { it.cache.delete() }
                        return@launch
                    }
                    songs.forEachIndexed { i, song ->
                        all += Item(files[i], MusicUtil.getSongFileUri(song.id), song.data)
                    }
                }
                items = all
                val request = MediaStore.createWriteRequest(
                    context.contentResolver, all.map { it.uri }.distinct()
                )
                launcher.launch(IntentSenderRequest.Builder(request).build())
            } else {
                for ((name, songs) in clean) {
                    val tags = mapOf(FieldKey.ARTIST to name, FieldKey.ALBUM_ARTIST to name)
                    TagWriter.writeTagsToFiles(context, AudioTagInfo(songs.map { it.data }, tags, null))
                }
                recordGroups()
                finish()
            }
        }
    }

    private fun copyBack() {
        val context = contextProvider() ?: return
        scopeProvider().launch {
            withContext(Dispatchers.IO) {
                for (item in items) {
                    context.contentResolver.openOutputStream(item.uri)?.use { output ->
                        item.cache.inputStream().use { it.copyTo(output) }
                    }
                }
            }
            TagWriter.scan(context, items.map { it.path })
            cleanUp()
            recordGroups()
            finish()
        }
    }

    /** Remembers old name -> new name, so the merge can be repeated after a restore. */
    private suspend fun recordGroups() = withContext(Dispatchers.IO) {
        runCatching {
            val groups = ArtistGroups(GlobalContext.get().get<RetroDatabase>().artistAliasDao())
            recorded.forEach { (main, olds) -> if (olds.isNotEmpty()) groups.merge(main, olds) }
        }
    }

    private fun cleanUp() {
        items.forEach { it.cache.delete() }
        items = emptyList()
    }

    private fun finish() {
        contextProvider()?.showToast(R.string.merge_artist_done)
        onDone?.invoke()
        onDone = null
    }

    companion object {
        /** Exists after a restore brought merge rows that were not applied to the files yet. */
        fun pendingMarker(context: Context) = File(context.filesDir, "pending_artist_merge")

        /**
         * Songs whose artist (or album artist) tag is a remembered old name: main name -> songs.
         * Null when the library has no songs yet (no permission), so the check must be retried.
         */
        suspend fun findPendingGroups(): Map<String, List<Song>>? = withContext(Dispatchers.IO) {
            val koin = GlobalContext.get()
            val songs = koin.get<Repository>().allSongs()
            if (songs.isEmpty()) return@withContext null
            val rows = ArtistGroups(koin.get<RetroDatabase>().artistAliasDao()).allRows()
            val mainOf = rows.associate { ArtistGroups.key(it.aliasName) to it.mainName }
            val groups = HashMap<String, MutableList<Song>>()
            for (song in songs) {
                val main = mainOf[ArtistGroups.key(song.artistName)]
                    ?: song.albumArtist?.let { mainOf[ArtistGroups.key(it)] }
                    ?: continue
                groups.getOrPut(main) { mutableListOf() }.add(song)
            }
            groups
        }
    }
}
