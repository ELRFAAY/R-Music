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
    const val STYLE = "season_theme_style"
    const val STYLE_CLASSIC = "classic"
    const val STYLE_TREE = "tree"

    /** One merged theme now (classic scene + tree around the cover); the separate tree style is no longer offered. */
    fun useTree(): Boolean = false

    /**
     * Which look of the season tree to show (1-based): spring 1-3 (March, April, May), summer 1-2 (June, then
     * July/August), autumn 1-5 (the leaves thin out over September-November), winter 1-3 (December, January,
     * February). A forced season (preview) uses the middle of that season.
     */
    fun treeState(season: Season): Int {
        val forced = Season.values().any { it.name == PreferenceUtil.seasonThemeForce }
        val cal = Calendar.getInstance()
        val month = if (forced) when (season) {
            Season.SPRING -> Calendar.APRIL
            Season.SUMMER -> Calendar.JULY
            Season.AUTUMN -> Calendar.OCTOBER
            Season.WINTER -> Calendar.JANUARY
        } else cal.get(Calendar.MONTH)
        val day = if (forced) 15 else cal.get(Calendar.DAY_OF_MONTH)
        return when (season) {
            Season.SPRING -> when (month) {
                Calendar.MARCH -> 1
                Calendar.APRIL -> 2
                else -> 3
            }
            Season.SUMMER -> if (month == Calendar.JUNE) 1 else 2
            Season.WINTER -> when (month) {
                Calendar.DECEMBER -> 1
                Calendar.JANUARY -> 2
                else -> 3
            }
            Season.AUTUMN -> {
                val idx = (month - Calendar.SEPTEMBER).coerceAtLeast(0) * 30 + day - 1
                (1 + idx * 5 / 91).coerceIn(1, 5)
            }
        }
    }

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

    /** The file the user picked for the current season, or null when the theme is off or none was picked. */
    fun currentUri(): Uri? {
        if (!PreferenceUtil.seasonThemeEnabled) return null
        return PreferenceUtil.seasonThemeUri(current())?.let { Uri.parse(it) }
    }

    /**
     * What the player background should show right now: "file:<uri>" (the picked file wins) or
     * "builtin:<season>" (the classic scene drawn by the app) or "tree:<season>:<state>" (the season tree); null when the theme is switched off.
     */
    fun currentKey(): String? {
        if (!PreferenceUtil.seasonThemeEnabled) return null
        currentUri()?.let { return "file:$it" }
        val season = current()
        return if (useTree()) "tree:${season.name}:${treeState(season)}" else "builtin:${season.name}"
    }
}
