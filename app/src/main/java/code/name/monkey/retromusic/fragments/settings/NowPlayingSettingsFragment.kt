/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import code.name.monkey.retromusic.*
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.Season
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * @author Hemanth S (h4h13).
 */

class NowPlayingSettingsFragment : AbsSettingsFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var pickingSeason: Season? = null

    private val pickSeasonFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val season = pickingSeason
            pickingSeason = null
            if (season != null && uri != null) {
                try {
                    // keep access to the file after the app restarts
                    requireContext().contentResolver
                        .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // not every provider allows it; the file still works until the next restart
                }
                PreferenceUtil.setSeasonThemeUri(season, uri.toString())
                updateSeasonSummaries()
            }
        }

    private fun pickFor(season: Season) {
        pickingSeason = season
        pickSeasonFile.launch(arrayOf("video/*", "image/*"))
    }

    private fun fileName(uri: Uri): String {
        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i)
            }
        } catch (e: Exception) {
            // fall through
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    private fun updateSeasonSummaries() {
        Season.values().forEach { season ->
            val pref: Preference? = findPreference(season.prefKey)
            val uri = PreferenceUtil.seasonThemeUri(season)
            pref?.summary = if (uri == null) {
                getString(R.string.season_file_not_set)
            } else {
                fileName(Uri.parse(uri))
            }
        }
    }

    private fun setupSeasonPreferences() {
        updateSeasonSummaries()
        Season.values().forEach { season ->
            val pref: Preference? = findPreference(season.prefKey)
            pref?.setOnPreferenceClickListener {
                if (PreferenceUtil.seasonThemeUri(season) == null) {
                    pickFor(season)
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setItems(
                            arrayOf(
                                getString(R.string.season_choose_file),
                                getString(R.string.season_remove_file)
                            )
                        ) { _, which ->
                            if (which == 0) {
                                pickFor(season)
                            } else {
                                PreferenceUtil.setSeasonThemeUri(season, null)
                                updateSeasonSummaries()
                            }
                        }
                        .show()
                }
                true
            }
        }
    }

    override fun invalidateSettings() {
        updateNowPlayingScreenSummary()
        setupSeasonPreferences()
        updateAlbumCoverStyleSummary()

        val carouselEffect: TwoStatePreference? = findPreference(CAROUSEL_EFFECT)
        carouselEffect?.setOnPreferenceChangeListener { _, _ ->
            return@setOnPreferenceChangeListener true
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_now_playing_screen)
    }

    private fun updateAlbumCoverStyleSummary() {
        val preference: Preference? = findPreference(ALBUM_COVER_STYLE)
        preference?.setSummary(PreferenceUtil.albumCoverStyle.titleRes)
    }

    private fun updateNowPlayingScreenSummary() {
        val preference: Preference? = findPreference(NOW_PLAYING_SCREEN_ID)
        preference?.setSummary(PreferenceUtil.nowPlayingScreen.titleRes)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        PreferenceUtil.registerOnSharedPreferenceChangedListener(this)
        val preference: Preference? = findPreference(ALBUM_COVER_TRANSFORM)
        preference?.setOnPreferenceChangeListener { albumPrefs, newValue ->
            setSummary(albumPrefs, newValue)
            true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PreferenceUtil.unregisterOnSharedPreferenceChangedListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            NOW_PLAYING_SCREEN_ID -> updateNowPlayingScreenSummary()
            ALBUM_COVER_STYLE -> updateAlbumCoverStyleSummary()
            CIRCULAR_ALBUM_ART, CAROUSEL_EFFECT -> invalidateSettings()
        }
    }
}
