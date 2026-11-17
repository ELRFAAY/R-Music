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
package code.name.monkey.retromusic.adapter.artist

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.annotation.MenuRes

/**
 * Same calls as [PopupMenu] (inflate, menu, setOnMenuItemClickListener, show) but the menu is shown
 * in the glass style of [ArtistPopup]. Items come from the menu XML, so every cover menu looks the
 * same. Sub menus are not supported (they are skipped).
 */
class GlassPopupMenu(context: Context, private val anchor: View) {
    private val inner = PopupMenu(context, anchor)
    private var listener: PopupMenu.OnMenuItemClickListener? = null

    val menu: Menu get() = inner.menu

    fun inflate(@MenuRes menuRes: Int) = inner.inflate(menuRes)

    fun setOnMenuItemClickListener(l: PopupMenu.OnMenuItemClickListener?) {
        listener = l
    }

    fun show() {
        val entries = ArrayList<ArtistPopup.Entry>()
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible || item.hasSubMenu()) continue
            entries.add(
                ArtistPopup.Entry(item.title?.toString().orEmpty(), 0, item.icon) {
                    listener?.onMenuItemClick(item)
                }
            )
        }
        if (entries.isNotEmpty()) ArtistPopup.showEntries(anchor, entries)
    }

    fun dismiss() = Unit
}
