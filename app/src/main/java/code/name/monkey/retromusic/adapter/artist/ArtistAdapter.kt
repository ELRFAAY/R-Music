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
    var onFavoritesClick: (() -> Unit)? = null
) : AbsMultiSelectAdapter<ArtistAdapter.ViewHolder, Artist>(activity, R.menu.menu_media_selection),
    PopupTextProvider {

    var albumArtistsOnly = false
    private var rawDataSet: List<Artist> = dataSet

    init {
        this.setHasStableIds(true)
    }

    /** Read once per data set instead of once per bound row (the getter parses a string). */
    private var pinnedNames: Set<String> = PreferenceUtil.pinnedArtists

    /** Placeholder item that becomes the "favorite artists" card; never a real artist. */
    private fun favoritesCard() =
        Artist(FAVORITES_CARD_ID, listOf(Album(FAVORITES_CARD_ID, listOf(Song.emptySong))))

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(dataSet: List<Artist>) {
        val real = dataSet.filter { it.id != FAVORITES_CARD_ID }
        rawDataSet = real
        val pinned = PreferenceUtil.pinnedArtists
        pinnedNames = pinned
        val ordered = if (pinned.isEmpty()) real
        else real.sortedBy { if (it.name in pinned) 0 else 1 }
        this.dataSet = if (showFavoritesCard) listOf(favoritesCard()) + ordered else ordered
        notifyDataSetChanged()
        albumArtistsOnly = PreferenceUtil.albumArtistsOnly
    }

    override fun getItemId(position: Int): Long {
        return dataSet[position].id
    }

    override fun getItemViewType(position: Int): Int =
        if (dataSet.getOrNull(position)?.id == FAVORITES_CARD_ID) VIEW_TYPE_FAVORITES else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (viewType == VIEW_TYPE_FAVORITES) {
            return createViewHolder(
                LayoutInflater.from(activity)
                    .inflate(R.layout.item_favorite_artists_card, parent, false)
            )
        }
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
            holder.itemView.isActivated = false
            holder.title?.text = activity.getString(R.string.favorite_artists)
            holder.text?.text = activity.getString(
                R.string.favorite_artists_count, PreferenceUtil.favoriteArtists.size
            )
            return
        }
        val isChecked = isChecked(artist)
        holder.itemView.isActivated = isChecked
        val artistName = artist.name
        holder.title?.text = artistName
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
            val pinned = PreferenceUtil.pinnedArtists
            val isPinned = artist.name in pinned
            val songs = artist.songs
            ArtistPopup.show(
                image ?: itemView,
                listOf(
                    activity.getString(if (isPinned) R.string.artist_unpin else R.string.artist_pin) to {
                        PreferenceUtil.pinnedArtists =
                            if (isPinned) pinned - artist.name else pinned + artist.name
                        swapDataSet(rawDataSet)
                    },
                    activity.getString(R.string.action_play_next) to {
                        SongsMenuHelper.handleMenuClick(activity, songs, R.id.action_play_next)
                    },
                    activity.getString(R.string.action_add_to_playing_queue) to {
                        SongsMenuHelper.handleMenuClick(activity, songs, R.id.action_add_to_current_playing)
                    },
                    activity.getString(R.string.action_add_to_playlist) to {
                        SongsMenuHelper.handleMenuClick(activity, songs, R.id.action_add_to_playlist)
                    },
                    activity.getString(R.string.merge_artist) to { onMerge?.invoke(artist) },
                    activity.getString(R.string.edit_artist_title) to { onRename?.invoke(artist) },
                    activity.getString(R.string.artist_select) to { toggleChecked(layoutPosition) }
                )
            )
            return true
        }
    }

    companion object {
        const val VIEW_TYPE_FAVORITES = 1
        const val FAVORITES_CARD_ID = -4242L
    }
}
