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
    var onRename: ((Artist) -> Unit)? = null
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

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(dataSet: List<Artist>) {
        rawDataSet = dataSet
        val pinned = PreferenceUtil.pinnedArtists
        val sorted = if (pinned.isEmpty()) dataSet
        else dataSet.sortedBy { if (it.name in pinned) 0 else 1 }
        val pinnedList = sorted.filter { it.name in pinned }
        if (pinnedList.size >= 2 && !stackExpanded) {
            stackHead = pinnedList[0]
            stackRest = pinnedList.drop(1)
            this.dataSet = listOf(pinnedList[0]) + sorted.filter { it.name !in pinned }
        } else {
            stackHead = null
            stackRest = emptyList()
            this.dataSet = sorted
        }
        notifyDataSetChanged()
        albumArtistsOnly = PreferenceUtil.albumArtistsOnly
    }

    override fun getItemId(position: Int): Long {
        return dataSet[position].id
    }

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
        val isChecked = isChecked(artist)
        holder.itemView.isActivated = isChecked
        holder.title?.text = artist.name
        val isStack = stackHead === artist && stackRest.isNotEmpty()
        if (isStack) holder.title?.text = "${artist.name} +${stackRest.size}"
        bindPile(holder, if (isStack) stackRest.take(2) else emptyList())
        markPinned(holder, artist.name in PreferenceUtil.pinnedArtists)
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
        return MusicUtil.getSectionName(dataSet[position].name)
    }

    inner class ViewHolder(itemView: View) : MediaEntryViewHolder(itemView) {

        init {
            menu?.isVisible = false
        }

        override fun onClick(v: View?) {
            super.onClick(v)
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
            if (isInQuickSelectMode) return toggleChecked(layoutPosition)
            val pos = layoutPosition
            if (pos < 0 || pos >= dataSet.size) return true
            val artist = dataSet[pos]
            val pinned = PreferenceUtil.pinnedArtists
            val isPinned = artist.name in pinned
            val songs = artist.songs
            val entries = mutableListOf<Pair<String, () -> Unit>>()
            entries.add(activity.getString(if (isPinned) R.string.artist_unpin else R.string.artist_pin) to {
                PreferenceUtil.pinnedArtists =
                    if (isPinned) pinned - artist.name else pinned + artist.name
                swapDataSet(rawDataSet)
            })
            if (isPinned && stackExpanded && pinned.size >= 2) {
                entries.add(activity.getString(R.string.artist_stack_pinned) to {
                    stackExpanded = false
                    swapDataSet(rawDataSet)
                })
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
    }
}
