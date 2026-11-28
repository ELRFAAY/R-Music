package code.name.monkey.retromusic.fragments.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import code.name.monkey.appthemehelper.common.prefs.supportv7.ATEListPreference
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.util.AppIconHelper
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.Season
import code.name.monkey.retromusic.vocal.VocalSeparator
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** "R settings": every setting that belongs to the R Music additions, in one place. */
class RSettingsFragment : AbsSettingsFragment() {

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
        setupSeasonPreferences()
        val appIcon: ATEListPreference? = findPreference(AppIconHelper.PREF_KEY)
        appIcon?.setOnPreferenceChangeListener { preference, newValue ->
            AppIconHelper.apply(requireContext(), newValue as String)
            setSummary(preference, newValue)
            true
        }
        setSummary(appIcon)
        listOf(
            "mini_player_style", "mini_player_shape", "mini_player_size", "mini_player_background",
            "korean_pronunciation", "vocal_quality", "season_theme_force"
        ).forEach { key ->
            val pref = findPreference<Preference>(key) as? ATEListPreference
            pref?.setOnPreferenceChangeListener { p, v -> setSummary(p, v); true }
            setSummary(pref)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_r_settings)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        findPreference<Preference>("clear_vocal_stems")?.let { pref ->
            val ctx = requireContext()
            fun refresh() {
                val mb = VocalSeparator.stemsSize(ctx) / 1_000_000
                pref.summary = getString(R.string.pref_clear_vocal_stems_summary, mb)
            }
            refresh()
            pref.setOnPreferenceClickListener {
                VocalSeparator.clearStems(ctx)
                refresh()
                true
            }
        }
    }
}
