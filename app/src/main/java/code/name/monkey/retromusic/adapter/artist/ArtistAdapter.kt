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
package code.name.monkey.retromusic.adapter.artist

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.base.AbsMultiSelectAdapter
import code.name.monkey.retromusic.adapter.base.MediaEntryViewHolder
import code.name.monkey.retromusic.extensions.hide
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.asBitmapPalette
import code.name.monkey.retromusic.glide.RetroMusicColoredTarget
import code.name.monkey.retromusic.helper.menu.SongsMenuHelper
import code.name.monkey.retromusic.interfaces.IAlbumArtistClickListener
import code.name.monkey.retromusic.interfaces.IArtistClickListener
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.MusicUtil
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import me.zhanghai.android.fastscroll.PopupTextProvider

class ArtistAdapter(
    override val activity: FragmentActivity,
    var dataSet: List<Artist>,
    var itemLayoutRes: Int,
    val IArtistClickListener: IArtistClickListener,
    val IAlbumArtistClickListener: IAlbumArtistClickListener? = null,
    var onMerge: ((Artist) -> Unit)? = null,
    var onRename: ((Artist) -> Unit)? = null,
    /** The artists list shows a "favorite artists" card as its first item. */
    var showFavoritesCard: Boolean = false,
    var onFavoritesClick: (() -> Unit)? = null,
    /** True inside the favorites sheet: pins are then the sheet's own, not the main list's. */
    private val favoritesSheet: Boolean = false
) : AbsMultiSelectAdapter<ArtistAdapter.ViewHolder, Artist>(activity, R.menu.menu_media_selection),
    PopupTextProvider {

    var albumArtistsOnly = false
    private var rawDataSet: List<Artist> = dataSet

    /** Pinned artists are piled into one cell until the user taps it. */
    private var stackExpanded = false
    private var stackHead: Artist? = null
    private var stackRest: List<Artist> = emptyList()

    init {
        this.setHasStableIds(true)
    }

    /** Read once per data set instead of once per bound row (the getter parses a string). */
    private var pinnedNames: Set<String> = readPins()

    /** Pinned artists are piled into one card only when the user chose to group them (main list only). */
    private fun grouped() = !favoritesSheet && PreferenceUtil.pinnedArtistsGrouped

    private fun readPins(): Set<String> =
        if (favoritesSheet) PreferenceUtil.pinnedFavoriteArtists else PreferenceUtil.pinnedArtists

    private fun writePins(v: Set<String>) {
        if (favoritesSheet) PreferenceUtil.pinnedFavoriteArtists = v else PreferenceUtil.pinnedArtists = v
    }

    /** Placeholder item that becomes the "favorite artists" card; never a real artist. */
    private fun favoritesCard() =
        Artist(FAVORITES_CARD_ID, listOf(Album(FAVORITES_CARD_ID, listOf(Song.emptySong))))

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(dataSet: List<Artist>) {
        val real = dataSet.filter { it.id != FAVORITES_CARD_ID }
        rawDataSet = real
        val pinned = readPins()
        pinnedNames = pinned
        val sorted = if (pinned.isEmpty()) real
        else real.sortedBy { if (it.name in pinned) 0 else 1 }
        val pinnedList = sorted.filter { it.name in pinned }
        val body = if (pinnedList.size >= 2 && grouped() && !stackExpanded) {
            stackHead = pinnedList[0]
            stackRest = pinnedList.drop(1)
            listOf(pinnedList[0]) + sorted.filter { it.name !in pinned }
        } else {
            stackHead = null
            stackRest = emptyList()
            sorted
        }
        this.dataSet = if (showFavoritesCard) listOf(favoritesCard()) + body else body
        notifyDataSetChanged()
        albumArtistsOnly = PreferenceUtil.albumArtistsOnly
    }

    override fun getItemId(position: Int): Long {
        return dataSet[position].id
    }

    override fun getItemViewType(position: Int): Int =
        if (dataSet.getOrNull(position)?.id == FAVORITES_CARD_ID) VIEW_TYPE_FAVORITES else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
            try {
                LayoutInflater.from(activity).inflate(itemLayoutRes, parent, false)
            } catch (e: Resources.NotFoundException) {
                LayoutInflater.from(activity).inflate(R.layout.item_grid_circle, parent, false)
            }
        return createViewHolder(view)
    }

    private fun createViewHolder(view: View): ViewHolder {
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val artist = dataSet[position]
        if (artist.id == FAVORITES_CARD_ID) {
            bindPileCard(holder, rawDataSet.filter { it.name in PreferenceUtil.favoriteArtists },
                activity.getString(R.string.favorite_artists) + " (${PreferenceUtil.favoriteArtists.size})")
            return
        }
        val isChecked = isChecked(artist)
        holder.itemView.isActivated = isChecked
        val artistName = artist.name
        holder.title?.text = artistName
        holder.image?.setTag(R.id.image, null)
        val isStack = stackHead === artist && stackRest.isNotEmpty()
        if (isStack) {
            // grouped pinned artists: the pictures piled and turned, no name
            bindPileCard(holder, listOf(artist) + stackRest, null)
            markPinned(holder, true)
            return
        }
        bindPile(holder, emptyList())
        markPinned(holder, artistName in pinnedNames)
        holder.text?.hide()
        val transitionName =
            if (albumArtistsOnly) artist.name else artist.id.toString()
        if (holder.imageContainer != null) {
            holder.imageContainer?.transitionName = transitionName
        } else {
            holder.image?.transitionName = transitionName
        }
        loadArtistImage(artist, holder)
    }

    /** Same coloured card as an artist, with a heart instead of a picture. */
    private fun bindPileCard(holder: ViewHolder, favs: List<Artist>, title: String?) {
        holder.itemView.isActivated = false
        val ctx = holder.itemView.context
        val bg = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorPrimaryContainer, 0
        )
        val fg = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, 0
        )
        bindPile(holder, emptyList())
        holder.title?.text = title.orEmpty()
        holder.title?.compoundDrawableTintList = null
        holder.title?.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        holder.title?.setTextColor(fg)
        holder.text?.hide()
        holder.image?.let { img ->
            Glide.with(activity).clear(img)
            val pad = (28 * img.resources.displayMetrics.density).toInt()
            img.setPadding(pad, pad, pad, pad)
            img.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            img.setImageResource(R.drawable.ic_favorite)
            img.imageTintList = ColorStateList.valueOf(fg)
            img.setBackgroundColor(bg)
            if (favs.isNotEmpty()) {
                // the wall's pile: up to four of the favorites' pictures, turned, with a count badge
                val token = Any()
                img.setTag(R.id.image, token)
                val models = favs.take(4).map { RetroGlideExtension.getArtistModel(it) }
                val total = favs.size
                val accent = code.name.monkey.appthemehelper.ThemeStore.accentColor(activity)
                Thread {
                    val bitmaps = models.mapNotNull {
                        try {
                            Glide.with(activity).asBitmap().load(it).override(300, 300).centerCrop()
                                .submit().get()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bitmaps.isNotEmpty()) {
                        val pile = favoritesPile(bitmaps, total, accent)
                        img.post {
                            if (img.getTag(R.id.image) === token) {
                                img.setPadding(0, 0, 0, 0)
                                img.imageTintList = null
                                img.setBackgroundColor(0)
                                img.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                img.setImageBitmap(pile)
                            }
                        }
                    }
                }.start()
            }
        }
        holder.paletteColorContainer?.setBackgroundColor(bg)
        holder.imageContainerCard?.setCardBackgroundColor(bg)
    }

    /** The wall's stack look: turned pictures with a white frame and a count badge. */
    private fun favoritesPile(bitmaps: List<android.graphics.Bitmap>, total: Int, accent: Int): android.graphics.Bitmap {
        val size = 300
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
        val angles = floatArrayOf(0f, -9f, 8f, -16f)
        val shifts = floatArrayOf(0f, -0.04f, 0.04f, -0.02f)
        val card = size * 0.78f
        for (k in bitmaps.size - 1 downTo 0) {
            val saved = canvas.save()
            canvas.rotate(angles[k], size / 2f, size / 2f)
            val left = (size - card) / 2f + shifts[k] * size
            val top = (size - card) / 2f
            val box = android.graphics.RectF(left, top, left + card, top + card)
            val r = card * 0.1f
            val frame = android.graphics.RectF(box)
            frame.inset(-size * 0.012f, -size * 0.012f)
            canvas.drawRoundRect(frame, r, r, border)
            val bm = bitmaps[k]
            canvas.drawBitmap(bm, android.graphics.Rect(0, 0, bm.width, bm.height), box, paint)
            canvas.restoreToCount(saved)
        }
        val badge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        val br = size * 0.13f
        canvas.drawCircle(size * 0.86f, size * 0.86f, br, badge)
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = br * 1.25f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText(total.toString(), size * 0.86f, size * 0.86f + br * 0.42f, text)
        return out
    }

    /** Draws up to two more covers behind the cell, tilted, so a pile shows. */
    private fun bindPile(holder: ViewHolder, behind: List<Artist>) {
        val img = holder.image ?: return
        val parent = img.parent as? androidx.constraintlayout.widget.ConstraintLayout ?: return
        for (i in parent.childCount - 1 downTo 0) {
            if (parent.getChildAt(i).tag == PILE_TAG) parent.removeViewAt(i)
        }
        parent.clipChildren = false
        parent.clipToPadding = false
        val d = parent.resources.displayMetrics.density
        behind.forEachIndexed { i, a ->
            val v = code.name.monkey.retromusic.views.RetroShapeableImageView(activity)
            v.tag = PILE_TAG
            v.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, 0)
            lp.dimensionRatio = "1:1"
            lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            v.layoutParams = lp
            val sign = if (i == 0) 1 else -1
            v.rotation = sign * (7f + 3f * i)
            v.translationX = sign * 6f * d
            v.translationY = 2f * d * (i + 1)
            v.alpha = 0.9f
            parent.addView(v, 0)
            Glide.with(activity).load(RetroGlideExtension.getArtistModel(a))
                .artistImageOptions(a).into(v)
        }
    }

    /** A small pin before the name of a pinned artist. */
    private fun markPinned(holder: ViewHolder, pinned: Boolean) {
        val t = holder.title ?: return
        if (pinned) {
            val size = (16 * t.resources.displayMetrics.density).toInt()
            val d = androidx.core.content.ContextCompat.getDrawable(t.context, R.drawable.ic_menu_pin)
            d?.setBounds(0, 0, size, size)
            t.setCompoundDrawablesRelative(d, null, null, null)
            t.compoundDrawablePadding = 6
            t.compoundDrawableTintList = ColorStateList.valueOf(t.currentTextColor)
        } else {
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    private fun setColors(processor: MediaNotificationProcessor, holder: ViewHolder) {
        holder.mask?.backgroundTintList = ColorStateList.valueOf(processor.primaryTextColor)
        if (holder.paletteColorContainer != null) {
            holder.paletteColorContainer?.setBackgroundColor(processor.backgroundColor)
            holder.title?.setTextColor(processor.primaryTextColor)
        }
        holder.title?.compoundDrawableTintList = ColorStateList.valueOf(holder.title?.currentTextColor ?: 0)
        holder.imageContainerCard?.setCardBackgroundColor(processor.backgroundColor)
    }

    private fun loadArtistImage(artist: Artist, holder: ViewHolder) {
        if (holder.image == null) {
            return
        }
        Glide.with(activity)
            .asBitmapPalette()
            .artistImageOptions(artist)
            .load(RetroGlideExtension.getArtistModel(artist))
            .transition(RetroGlideExtension.getDefaultTransition())
            .into(object : RetroMusicColoredTarget(holder.image!!) {
                override fun onColorReady(colors: MediaNotificationProcessor) {
                    setColors(colors, holder)
                }
            })
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    override fun getIdentifier(position: Int): Artist {
        return dataSet[position]
    }

    override fun getName(model: Artist): String {
        return model.name
    }

    override fun onMultipleItemAction(
        menuItem: MenuItem,
        selection: List<Artist>
    ) {
        SongsMenuHelper.handleMenuClick(activity, getSongList(selection), menuItem.itemId)
    }

    private fun getSongList(artists: List<Artist>): List<Song> {
        val songs = ArrayList<Song>()
        for (artist in artists) {
            songs.addAll(artist.songs) // maybe async in future?
        }
        return songs
    }

    override fun getPopupText(position: Int): String {
        return getSectionName(position)
    }

    private fun getSectionName(position: Int): String {
        if (dataSet[position].id == FAVORITES_CARD_ID) return "★"
        return MusicUtil.getSectionName(dataSet[position].name)
    }

    inner class ViewHolder(itemView: View) : MediaEntryViewHolder(itemView) {

        init {
            menu?.isVisible = false
        }

        override fun onClick(v: View?) {
            super.onClick(v)
            if (dataSet.getOrNull(layoutPosition)?.id == FAVORITES_CARD_ID) {
                if (!isInQuickSelectMode) onFavoritesClick?.invoke()
                return
            }
            if (isInQuickSelectMode) {
                toggleChecked(layoutPosition)
            } else {
                val artist = dataSet[layoutPosition]
                if (stackHead === artist && stackRest.isNotEmpty()) {
                    stackExpanded = true
                    swapDataSet(rawDataSet)
                    return
                }
                image?.let {
                    if (albumArtistsOnly && IAlbumArtistClickListener != null) {
                        IAlbumArtistClickListener.onAlbumArtist(artist.name, imageContainer ?: it)
                    } else {
                        IArtistClickListener.onArtist(artist.id, imageContainer ?: it)
                    }
                }
            }
        }

        override fun onLongClick(v: View?): Boolean {
            if (dataSet.getOrNull(layoutPosition)?.id == FAVORITES_CARD_ID) return true
            if (isInQuickSelectMode) return toggleChecked(layoutPosition)
            val pos = layoutPosition
            if (pos < 0 || pos >= dataSet.size) return true
            val artist = dataSet[pos]
            val pinned = readPins()
            val isPinned = artist.name in pinned
            val songs = artist.songs
            val entries = mutableListOf<Pair<String, () -> Unit>>()
            entries.add(activity.getString(if (isPinned) R.string.artist_unpin else R.string.artist_pin) to {
                writePins(if (isPinned) pinned - artist.name else pinned + artist.name)
                swapDataSet(rawDataSet)
            })
            if (isPinned && pinned.size >= 2 && !favoritesSheet) {
                if (!grouped()) {
                    entries.add(activity.getString(R.string.artist_group_pinned) to {
                        PreferenceUtil.pinnedArtistsGrouped = true
                        stackExpanded = false
                        swapDataSet(rawDataSet)
                    })
                } else {
                    if (stackExpanded) {
                        entries.add(activity.getString(R.string.artist_stack_pinned) to {
                            stackExpanded = false
                            swapDataSet(rawDataSet)
                        })
                    }
                    entries.add(activity.getString(R.string.artist_ungroup_pinned) to {
                        PreferenceUtil.pinnedArtistsGrouped = false
                        swapDataSet(rawDataSet)
                    })
                }
            }
            entries.add(activity.getString(R.string.action_play_next) to {
                SongsMenuHelper.handleMenuClick(activity, songs, R.id.action_play_next)
            })
            entries.add(activity.getString(R.string.action_add_to_playing_queue) to {
                SongsMenuHelper.handleMenuClick(activity, songs, R.id.action_add_to_current_playing)
            })
            entries.add(activity.getString(R.string.action_add_to_playlist) to {
                SongsMenuHelper.handleMenuClick(activity, songs, R.id.action_add_to_playlist)
            })
            entries.add(activity.getString(R.string.merge_artist) to { onMerge?.invoke(artist) })
            entries.add(activity.getString(R.string.edit_artist_title) to { onRename?.invoke(artist) })
            entries.add(activity.getString(R.string.artist_select) to { toggleChecked(layoutPosition) })
            ArtistPopup.show(image ?: itemView, entries)
            return true
        }
    }

    companion object {
        private const val PILE_TAG = "artist_pile"
        const val VIEW_TYPE_FAVORITES = 1
        const val FAVORITES_CARD_ID = -4242L
    }
}
