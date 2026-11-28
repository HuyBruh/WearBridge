package com.wearos.ancsbridge.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import androidx.wear.ongoing.OngoingActivity
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ble.MediaCommand
import com.wearos.ancsbridge.ble.NowPlaying
import com.wearos.ancsbridge.ui.MainActivity
import kotlin.math.roundToInt

/**
 * Mirrors what the iPhone is playing (Apple Media Service) into a local MediaSession.
 * Wear OS then shows its own media controls on its own when playback starts, like Apple
 * Watch's auto-launching Now Playing, with the crown driving the iPhone's volume, plus a
 * media notification and a watch-face chip.
 */
class PhoneMediaSession(private val context: Context, private val send: (MediaCommand) -> Unit) {

    companion object {
        private const val CHANNEL = "now_playing"
        private const val NOTIFICATION_ID = 88
        private const val VOLUME_STEPS = 16
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var supported: Set<Int>? = null
    private var current = NowPlaying()
    private var posted = false

    private val volume = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, VOLUME_STEPS, VOLUME_STEPS / 2) {
        override fun onAdjustVolume(direction: Int) {
            when {
                direction > 0 -> send(MediaCommand.VOLUME_UP)
                direction < 0 -> send(MediaCommand.VOLUME_DOWN)
            }
        }
    }

    private val session = MediaSessionCompat(context, "WearBridgeiPhone").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = send(if (can(MediaCommand.PLAY)) MediaCommand.PLAY else MediaCommand.TOGGLE_PLAYBACK)
            override fun onPause() = send(if (can(MediaCommand.PAUSE)) MediaCommand.PAUSE else MediaCommand.TOGGLE_PLAYBACK)
            override fun onSkipToNext() = send(MediaCommand.NEXT_TRACK)
            override fun onSkipToPrevious() = send(MediaCommand.PREVIOUS_TRACK)
        })
        setPlaybackToRemote(volume)
        setSessionActivity(openNowPlaying())
    }

    private fun can(command: MediaCommand) = supported?.contains(command.code) == true

    private fun openNowPlaying(): PendingIntent = PendingIntent.getActivity(
        context, 7,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_NOW_PLAYING)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun commandIntent(command: MediaCommand): PendingIntent = PendingIntent.getService(
        context, 100 + command.code,
        Intent(context, AncsService::class.java).setAction(AncsService.ACTION_MEDIA_COMMAND)
            .putExtra(AncsService.EXTRA_MEDIA_COMMAND, command.code),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun update(nowPlaying: NowPlaying, available: Boolean, commands: Set<Int>?, artwork: android.graphics.Bitmap? = null) {
        supported = commands
        current = nowPlaying
        if (!available || !nowPlaying.hasTrack) {
            clear()
            return
        }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, nowPlaying.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, nowPlaying.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, nowPlaying.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, nowPlaying.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, nowPlaying.player.ifBlank { "iPhone" })
                .apply {
                    artwork?.let {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
                    }
                }
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (nowPlaying.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (nowPlaying.isPlaying) 1f else 0f
                )
                .build()
        )
        nowPlaying.volume?.let { volume.currentVolume = (it * VOLUME_STEPS).roundToInt().coerceIn(0, VOLUME_STEPS) }
        session.isActive = true
        // Keep the controls around while paused (like Apple Watch), drop them when the
        // iPhone stops reporting a track.
        postNotification(nowPlaying, artwork)
    }

    private fun postNotification(nowPlaying: NowPlaying, artwork: android.graphics.Bitmap?) {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Now Playing", NotificationManager.IMPORTANCE_LOW)
        )
        val playPause = if (nowPlaying.isPlaying)
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", commandIntent(MediaCommand.TOGGLE_PLAYBACK))
        else NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", commandIntent(MediaCommand.TOGGLE_PLAYBACK))
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(nowPlaying.title.ifBlank { "Now Playing" })
            .setContentText(nowPlaying.artist.ifBlank { nowPlaying.player })
            .setSubText(nowPlaying.player.ifBlank { "iPhone" })
            .setLargeIcon(artwork)
            .setContentIntent(openNowPlaying())
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(nowPlaying.isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Previous", commandIntent(MediaCommand.PREVIOUS_TRACK))
            .addAction(playPause)
            .addAction(android.R.drawable.ic_media_next, "Next", commandIntent(MediaCommand.NEXT_TRACK))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        if (nowPlaying.isPlaying) {
            // Watch-face chip while music plays, tapping opens Now Playing.
            OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_music)
                .setTouchIntent(openNowPlaying())
                .build()
                .apply(context)
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
        posted = true
    }

    fun clear() {
        if (session.isActive) session.isActive = false
        if (posted) {
            notificationManager.cancel(NOTIFICATION_ID)
            posted = false
        }
    }

    fun release() {
        clear()
        session.release()
    }
}
