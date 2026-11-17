package code.name.monkey.retromusic.util

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.R

/** The incognito button in the top bar (next to cast): when on, plays are not recorded anywhere. */
object IncognitoMode {

    /** Call after the toolbar tint helper ran, so the "on" icon can get the accent colour. */
    fun applyState(menu: Menu, context: Context) {
        val item = menu.findItem(R.id.action_incognito) ?: return
        val on = PreferenceUtil.incognito
        item.setIcon(if (on) R.drawable.ic_incognito_on else R.drawable.ic_incognito)
        item.icon?.mutate()?.let { if (on) it.setTint(ThemeStore.accentColor(context)) }
        item.title = context.getString(R.string.incognito_mode)
    }

    /** Returns true when [item] was the incognito button (and has been handled). */
    fun handle(item: MenuItem, activity: FragmentActivity): Boolean {
        if (item.itemId != R.id.action_incognito) return false
        PreferenceUtil.incognito = !PreferenceUtil.incognito
        Toast.makeText(
            activity,
            if (PreferenceUtil.incognito) R.string.incognito_on_msg else R.string.incognito_off_msg,
            Toast.LENGTH_SHORT
        ).show()
        activity.invalidateMenu()
        return true
    }
}
