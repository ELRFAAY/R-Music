/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

package code.name.monkey.retromusic.service.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.widget.RemoteViews
import android.graphics.drawable.Drawable
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.MainActivity
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.service.MusicService
import code.name.monkey.retromusic.service.MusicService.Companion.ACTION_QUIT
import code.name.monkey.retromusic.service.MusicService.Companion.ACTION_REWIND
import code.name.monkey.retromusic.service.MusicService.Companion.ACTION_SKIP
import code.name.monkey.retromusic.service.MusicService.Companion.ACTION_TOGGLE_PAUSE
import code.name.monkey.retromusic.service.MusicService.Companion.ACTION_TOGGLE_SHUFFLE
import code.name.monkey.retromusic.service.MusicService.Companion.TOGGLE_FAVORITE
import code.name.monkey.retromusic.util.PreferenceUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

class PlayingNotification(
    val context: MusicService,
    mediaSessionToken: MediaSessionCompat.Token,
) : NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID) {

    private var currentTarget: CustomTarget<Bitmap>? = null

    // Cobra card state
    private val cobra = COBRA_CARD
    private var cobraTitle: String = ""
    private var cobraArtist: String = ""
    private var cobraCover: Bitmap? = null
    private var cobraPlaying = true
    private var cobraShuffle = false
    private var cobraProgress = 0
    private var cobraDuration = 0
    private var lastBarSecond = -1

    init {
        val action = Intent(context, MainActivity::class.java)
        action.putExtra(MainActivity.EXPAND_PANEL, PreferenceUtil.isExpandPanel)
        action.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val clickIntent =
            PendingIntent.getActivity(
                context,
                0,
                action,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val serviceName = ComponentName(context, MusicService::class.java)
        val intent = Intent(ACTION_QUIT)
        intent.component = serviceName
        val deleteIntent = PendingIntent.getService(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (PendingIntent.FLAG_IMMUTABLE)
        )
        val toggleFavorite = buildFavoriteAction(false)
        val playPauseAction = buildPlayAction(true)
        val previousAction = NotificationCompat.Action(
            R.drawable.ic_skip_previous,
            context.getString(R.string.action_previous),
            retrievePlaybackAction(ACTION_REWIND)
        )
        val nextAction = NotificationCompat.Action(
            R.drawable.ic_skip_next,
            context.getString(R.string.action_next),
            retrievePlaybackAction(ACTION_SKIP)
        )
        val dismissAction = NotificationCompat.Action(
            R.drawable.ic_close,
            context.getString(R.string.action_cancel),
            retrievePlaybackAction(ACTION_QUIT)
        )
        setSmallIcon(R.drawable.ic_notification)
        setContentIntent(clickIntent)
        setDeleteIntent(deleteIntent)
        setShowWhen(false)
        if (cobra) {
            setStyle(
                NotificationCompat.DecoratedCustomViewStyle()
            )
            refreshCobraViews()
        } else {
            addAction(toggleFavorite)
            addAction(previousAction)
            addAction(playPauseAction)
            addAction(nextAction)
            if (VersionUtils.hasS()) {
                addAction(dismissAction)
            }
            setStyle(
                MediaStyle()
                    .setMediaSession(mediaSessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
        }
        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    fun updateMetadata(song: Song, onUpdate: () -> Unit) {
        if (song == Song.emptySong) return
        setContentTitle(song.title)
        setContentText(song.artistName)
        setSubText(song.albumName)
        cobraTitle = song.title
        cobraArtist = song.artistName
        cobraProgress = 0
        lastBarSecond = -1

        // Displays a temporary image while Glide loads the actual album image.
        // This allows us to update the notification immediately
        // without waiting for Glide.
        setAlbumArtImage(null)
        onUpdate()

        val bigNotificationImageSize = context.resources
            .getDimensionPixelSize(R.dimen.notification_big_image_size)
        currentTarget?.let { Glide.with(context).clear(it) }
        currentTarget = Glide.with(context)
            .asBitmap()
            .songCoverOptions(song)
            .load(RetroGlideExtension.getSongModel(song))
            //.checkIgnoreMediaStore()
            .centerCrop()
            .into(object : CustomTarget<Bitmap>(
                bigNotificationImageSize,
                bigNotificationImageSize
            ) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (!cobra) setLargeIcon(resource)
                    cobraCover = resource
                    refreshCobraViews()
                    onUpdate()
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    super.onLoadFailed(errorDrawable)
                    setAlbumArtImage(null)
                    onUpdate()
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    setAlbumArtImage(null)
                    onUpdate()
                }
            })
    }

    private fun setAlbumArtImage(image: Bitmap?) {
        cobraCover = image
        refreshCobraViews()
        if (cobra) return
        if (image == null) {
            setLargeIcon(
                BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.default_audio_art
                )
            )
        } else {
            setLargeIcon(image)
        }
    }

    private fun buildPlayAction(isPlaying: Boolean): NotificationCompat.Action {
        val playButtonResId =
            if (isPlaying) R.drawable.ic_pause_white_48dp else R.drawable.ic_play_arrow_white_48dp
        return NotificationCompat.Action.Builder(
            playButtonResId,
            context.getString(R.string.action_play_pause),
            retrievePlaybackAction(ACTION_TOGGLE_PAUSE)
        ).build()
    }

    private fun buildFavoriteAction(isFavorite: Boolean): NotificationCompat.Action {
        val favoriteResId =
            if (isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        return NotificationCompat.Action.Builder(
            favoriteResId,
            context.getString(R.string.action_toggle_favorite),
            retrievePlaybackAction(TOGGLE_FAVORITE)
        ).build()
    }

    @SuppressLint("RestrictedApi")
    fun setPlaying(isPlaying: Boolean) {
        cobraPlaying = isPlaying
        if (cobra) refreshCobraViews() else mActions[2] = buildPlayAction(isPlaying)
    }

    @SuppressLint("RestrictedApi")
    fun updateFavorite(isFavorite: Boolean) {
        if (!cobra) mActions[0] = buildFavoriteAction(isFavorite)
    }

    fun setShuffle(on: Boolean) {
        cobraShuffle = on
        if (cobra) refreshCobraViews()
    }

    /** Returns true when the bar picture changed (so the notification should be re-posted). */
    fun updateProgress(progressMillis: Int, durationMillis: Int): Boolean {
        if (!cobra) return false
        cobraProgress = progressMillis
        cobraDuration = durationMillis
        val sec = progressMillis / 1000
        if (sec == lastBarSecond) return false
        lastBarSecond = sec
        refreshCobraViews()
        return true
    }

    private fun refreshCobraViews() {
        if (!cobra) return
        try {
            val expanded = RemoteViews(context.packageName, R.layout.notification_cobra_expanded)
            val collapsed = RemoteViews(context.packageName, R.layout.notification_cobra_collapsed)
            val cover = roundedCover(cobraCover ?: BitmapFactory.decodeResource(
                context.resources, R.drawable.notification_cobra_cover
            ))
            val green = 0xFF22D398.toInt()
            for (v in listOf(expanded, collapsed)) {
                v.setTextViewText(R.id.cobraTitle, cobraTitle)
                v.setTextViewText(R.id.cobraArtist, cobraArtist)
                v.setImageViewBitmap(R.id.cobraCover, cover)
                v.setImageViewResource(
                    R.id.cobraPlay,
                    if (cobraPlaying) R.drawable.ic_pause_white_48dp else R.drawable.ic_play_arrow_white_48dp
                )
                v.setInt(R.id.cobraPlay, "setColorFilter", green)
                v.setOnClickPendingIntent(R.id.cobraPlay, retrievePlaybackAction(ACTION_TOGGLE_PAUSE))
                v.setOnClickPendingIntent(R.id.cobraClose, retrievePlaybackAction(ACTION_QUIT))
            }
            expanded.setOnClickPendingIntent(R.id.cobraPrev, retrievePlaybackAction(ACTION_REWIND))
            expanded.setOnClickPendingIntent(R.id.cobraNext, retrievePlaybackAction(ACTION_SKIP))
            expanded.setOnClickPendingIntent(R.id.cobraShuffle, retrievePlaybackAction(ACTION_TOGGLE_SHUFFLE))
            expanded.setInt(R.id.cobraShuffle, "setColorFilter", if (cobraShuffle) green else 0xFF6B767A.toInt())
            expanded.setImageViewBitmap(R.id.cobraBar, drawBar())
            val segs = intArrayOf(
                R.id.seek0, R.id.seek1, R.id.seek2, R.id.seek3, R.id.seek4,
                R.id.seek5, R.id.seek6, R.id.seek7, R.id.seek8, R.id.seek9
            )
            for (i in segs.indices) {
                val seekIntent = Intent(MusicService.ACTION_SEEK_FRACTION)
                seekIntent.component = ComponentName(context, MusicService::class.java)
                seekIntent.putExtra(MusicService.EXTRA_SEEK_FRACTION, (i + 0.5f) / segs.size)
                expanded.setOnClickPendingIntent(
                    segs[i],
                    PendingIntent.getService(
                        context, 100 + i, seekIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            setCustomContentView(collapsed)
            setCustomBigContentView(expanded)
        } catch (e: Exception) {
            android.util.Log.e("PlayingNotification", "cobra card failed", e)
        }
    }

    private fun roundedCover(src: Bitmap): Bitmap {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 36f, 36f, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val side = minOf(src.width, src.height)
        val srcRect = android.graphics.Rect(
            (src.width - side) / 2, (src.height - side) / 2,
            (src.width + side) / 2, (src.height + side) / 2
        )
        c.drawBitmap(src, srcRect, RectF(0f, 0f, size.toFloat(), size.toFloat()), p)
        return out
    }

    private fun drawBar(): Bitmap {
        val w = 560
        val h = 48
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val head = BitmapFactory.decodeResource(context.resources, R.drawable.notification_cobra_head)
        val headH = 40
        val headW = head.width * headH / head.height
        val frac = if (cobraDuration > 0) (cobraProgress.toFloat() / cobraDuration).coerceIn(0f, 1f) else 0f
        val left = headW / 2f
        val right = w - headW / 2f
        val x = left + (right - left) * frac
        val cy = h / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 6f
        p.color = 0xFF3A4246.toInt()
        c.drawLine(left, cy, right, cy, p)
        p.color = 0xFF22D398.toInt()
        c.drawLine(left, cy, x, cy, p)
        c.drawBitmap(
            Bitmap.createScaledBitmap(head, headW, headH, true),
            x - headW / 2f, cy - headH / 2f, null
        )
        return bmp
    }

    fun clear(context: Context) {
        Glide.with(context).clear(currentTarget)
    }

    private fun retrievePlaybackAction(action: String): PendingIntent {
        val serviceName = ComponentName(context, MusicService::class.java)
        val intent = Intent(action)
        intent.component = serviceName
        return PendingIntent.getService(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        internal const val NOTIFICATION_CHANNEL_ID = "playing_notification"
        const val NOTIFICATION_ID = 1
        const val COBRA_CARD = true


        @RequiresApi(26)
        fun createNotificationChannel(
            context: Context,
            notificationManager: NotificationManager
        ) {
            var notificationChannel: NotificationChannel? = notificationManager
                .getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (notificationChannel == null) {
                notificationChannel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.playing_notification_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationChannel.description =
                    context.getString(R.string.playing_notification_description)
                notificationChannel.enableLights(false)
                notificationChannel.enableVibration(false)
                notificationChannel.setShowBadge(false)

                notificationManager.createNotificationChannel(notificationChannel)
            }
        }

        fun from(
            context: MusicService,
            notificationManager: NotificationManager,
            mediaSession: MediaSessionCompat,
        ): PlayingNotification {
            if (VersionUtils.hasOreo()) {
                createNotificationChannel(context, notificationManager)
            }
            return PlayingNotification(context, mediaSession.sessionToken)
        }
    }
}