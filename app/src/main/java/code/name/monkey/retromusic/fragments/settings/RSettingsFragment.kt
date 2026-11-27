package code.name.monkey.retromusic.fragments.settings

import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.vocal.VocalSeparator

/** Everything that was added to R Music, in one place. */
class RSettingsFragment : AbsSettingsFragment() {

    override fun invalidateSettings() {}

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
