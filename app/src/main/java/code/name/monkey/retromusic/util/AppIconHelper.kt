package code.name.monkey.retromusic.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches the launcher icon between the new cobra and the old icon (two activity aliases). */
object AppIconHelper {
    const val PREF_KEY = "app_icon"
    const val ICON_NEW = "new"
    const val ICON_OLD = "old"

    private const val ALIAS_NEW = "code.name.monkey.retromusic.IconNew"
    private const val ALIAS_OLD = "code.name.monkey.retromusic.IconOld"

    /** Makes the launcher match [choice]; enables the wanted alias first so there is always one. */
    fun apply(context: Context, choice: String) {
        val wantOld = choice == ICON_OLD
        set(context, if (wantOld) ALIAS_OLD else ALIAS_NEW, true)
        set(context, if (wantOld) ALIAS_NEW else ALIAS_OLD, false)
    }

    /** Cheap: only touches the system when the enabled alias differs from the saved choice. */
    fun sync(context: Context) {
        val choice = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, ICON_NEW) ?: ICON_NEW
        val wantAlias = if (choice == ICON_OLD) ALIAS_OLD else ALIAS_NEW
        val state = context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, wantAlias))
        // DEFAULT means "as in the manifest": the new alias is on, the old one is off.
        val enabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && wantAlias == ALIAS_NEW)
        if (!enabled) apply(context, choice)
    }

    private fun set(context: Context, alias: String, enable: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, alias),
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
