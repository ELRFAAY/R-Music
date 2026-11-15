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

import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.ArtistAliasDao
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.repository.ArtistGroups
import code.name.monkey.retromusic.repository.RealRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.get

/**
 * Dialogs for grouping artist names under one artist. Nothing here touches a tag or a file: it only
 * writes the alias table (see [ArtistGroups]). Every database call runs on the IO dispatcher.
 */
object ArtistGroupDialogs {

    private fun groups() = ArtistGroups(get(ArtistAliasDao::class.java))

    /** One selected artist: pick the others in a list. Several: ask, then group them. */
    fun mergeSelection(activity: FragmentActivity, names: List<String>, onDone: () -> Unit) {
        when {
            names.isEmpty() -> Unit
            names.size == 1 -> showPicker(activity, names[0], onDone)
            else -> {
                val main = names[0]
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.merge_artist)
                    .setMessage(activity.getString(R.string.merge_artist_confirm, names.size, main))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.merge_artist_action) { _, _ ->
                        merge(activity, main, names.drop(1), onDone)
                    }
                    .show()
            }
        }
    }

    /** A searchable list of all artists; tick several and they are grouped under [mainName]. */
    fun showPicker(activity: FragmentActivity, mainName: String, onDone: () -> Unit) {
        activity.lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) {
                get<RealRepository>(RealRepository::class.java).fetchArtists()
                    .filter { it.id != Artist.VARIOUS_ARTISTS_ID }
                    .map { it.name }
                    .filter {
                        !it.equals(mainName, ignoreCase = true) &&
                                it != Artist.UNKNOWN_ARTIST_DISPLAY_NAME &&
                                it != Artist.VARIOUS_ARTISTS_DISPLAY_NAME
                    }
                    .distinct()
                    .sortedBy { it.lowercase() }
            }
            if (names.isEmpty()) {
                activity.showToast(R.string.merge_artist_none)
                return@launch
            }
            buildPicker(activity, mainName, names, onDone)
        }
    }

    private fun buildPicker(
        activity: FragmentActivity,
        mainName: String,
        allNames: List<String>,
        onDone: () -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val selected = linkedSetOf<String>()
        var visible: List<String> = allNames

        val search = EditText(activity).apply {
            hint = activity.getString(R.string.merge_artist_search_hint)
            setSingleLine()
        }
        val list = ListView(activity).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (320 * density).toInt()
            )
        }
        val adapter = ArrayAdapter<String>(
            activity, android.R.layout.simple_list_item_multiple_choice, ArrayList<String>()
        )
        list.adapter = adapter
        val count = TextView(activity)

        fun refreshCount() {
            count.text = activity.getString(R.string.merge_artist_selected, selected.size)
        }

        fun applyFilter() {
            val q = search.text.toString().trim()
            visible = if (q.isEmpty()) allNames
            else allNames.filter { it.contains(q, ignoreCase = true) }
            adapter.clear()
            adapter.addAll(visible)
            adapter.notifyDataSetChanged()
            // Ticks survive searching: the selection is kept by name, not by row.
            visible.forEachIndexed { i, n -> list.setItemChecked(i, n in selected) }
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val name = visible.getOrNull(position) ?: return@setOnItemClickListener
            if (list.isItemChecked(position)) selected.add(name) else selected.remove(name)
            refreshCount()
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
        })

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(search)
            addView(count)
            addView(list)
        }
        applyFilter()
        refreshCount()

        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.merge_artist_pick_title, mainName))
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.merge_artist_action) { _, _ ->
                if (selected.isNotEmpty()) merge(activity, mainName, selected.toList(), onDone)
            }
            .show()
    }

    private fun merge(
        activity: FragmentActivity,
        mainName: String,
        aliasNames: List<String>,
        onDone: () -> Unit
    ) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { groups().merge(mainName, aliasNames) }
            activity.showToast(R.string.merge_artist_done)
            onDone()
        }
    }

    /** Lists the names grouped under [mainName] so they can be taken out again. */
    fun showUndo(activity: FragmentActivity, mainName: String, onDone: () -> Unit) {
        activity.lifecycleScope.launch {
            val aliases = withContext(Dispatchers.IO) { groups().aliasesOf(mainName) }
            if (aliases.isEmpty()) {
                activity.showToast(R.string.unmerge_artist_none)
                return@launch
            }
            val checked = BooleanArray(aliases.size)
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.unmerge_artist_title, mainName))
                .setMultiChoiceItems(aliases.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.unmerge_artist_all) { _, _ ->
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { groups().unmergeAll(mainName) }
                        onDone()
                    }
                }
                .setPositiveButton(R.string.unmerge_artist_split) { _, _ ->
                    val chosen = aliases.filterIndexed { i, _ -> checked[i] }
                    if (chosen.isNotEmpty()) {
                        activity.lifecycleScope.launch {
                            withContext(Dispatchers.IO) { chosen.forEach { groups().unmerge(it) } }
                            onDone()
                        }
                    }
                }
                .show()
        }
    }
}
