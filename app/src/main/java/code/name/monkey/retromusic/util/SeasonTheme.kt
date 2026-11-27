package code.name.monkey.retromusic.util

import android.net.Uri
import java.util.Calendar

/** One entry per season; [prefKey] stores the file (content Uri) the user picked for it. */
enum class Season(val prefKey: String) {
    WINTER("season_theme_winter"),
    SPRING("season_theme_spring"),
    SUMMER("season_theme_summer"),
    AUTUMN("season_theme_autumn")
}

object SeasonTheme {
    const val ENABLED = "season_theme_enabled"
    const val FORCE = "season_theme_force"
    const val AUTO = "auto"

    /** Northern-hemisphere seasons from [Calendar.MONTH]. */
    fun fromMonth(month: Int): Season = when (month) {
        Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> Season.WINTER
        Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> Season.SPRING
        Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> Season.SUMMER
        else -> Season.AUTUMN
    }

    /** The season to show: the one forced in settings (for previewing), else the one of the current month. */
    fun current(): Season {
        val forced = PreferenceUtil.seasonThemeForce
        Season.values().firstOrNull { it.name == forced }?.let { return it }
        return fromMonth(Calendar.getInstance().get(Calendar.MONTH))
    }

    /** The file to show right now, or null when the theme is off or no file was picked for this season. */
    fun currentUri(): Uri? {
        if (!PreferenceUtil.seasonThemeEnabled) return null
        return PreferenceUtil.seasonThemeUri(current())?.let { Uri.parse(it) }
    }
}
