package com.schulzcode.y2remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import com.schulzcode.y2remote.R
import com.schulzcode.y2remote.bluetooth.BluetoothConnectionManager
import com.schulzcode.y2remote.protocol.RemoteCommand
import com.schulzcode.y2remote.protocol.RemoteMessage
import com.schulzcode.y2remote.protocol.RemoteProtocol
import com.schulzcode.y2remote.ui.MainActivity
import com.schulzcode.y2remote.util.LastConnection

class RemoteControlService : Service(), BluetoothConnectionManager.Listener {

    inner class LocalBinder : Binder() {
        val service: RemoteControlService get() = this@RemoteControlService
        val connectionManager: BluetoothConnectionManager get() = this@RemoteControlService.connectionManager
    }

    private val binder = LocalBinder()
    val connectionManager = BluetoothConnectionManager()
    private lateinit var mediaSession: MediaSessionCompat
    private var lastState: RemoteMessage.PlayerState? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        connectionManager.addListener(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "Y2RemoteMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    connectionManager.sendCommand(RemoteCommand.Play)
                }

                override fun onPause() {
                    connectionManager.sendCommand(RemoteCommand.Pause)
                }

                override fun onSkipToNext() {
                    connectionManager.sendCommand(RemoteCommand.Next)
                }

                override fun onSkipToPrevious() {
                    connectionManager.sendCommand(RemoteCommand.Previous)
                }

                override fun onSeekTo(pos: Long) {
                    connectionManager.sendCommand(RemoteCommand.Seek(pos))
                }

                override fun onFastForward() {
                    connectionManager.sendCommand(RemoteCommand.Forward())
                }

                override fun onRewind() {
                    connectionManager.sendCommand(RemoteCommand.Rewind())
                }
            })
            isActive = true
        }
    }

    @Volatile private var currentArtwork: android.graphics.Bitmap? = null

    override fun onConnectionStateChanged(state: BluetoothConnectionManager.ConnectionState) {
        when (state) {
            is BluetoothConnectionManager.ConnectionState.Connected -> {
                LastConnection.save(this, state.address)
                startForeground(NOTIFICATION_ID, buildNotification(lastState))
            }
            is BluetoothConnectionManager.ConnectionState.Disconnected,
            is BluetoothConnectionManager.ConnectionState.Error -> {
                currentArtwork = null
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
            }
            is BluetoothConnectionManager.ConnectionState.Connecting -> Unit
        }
    }

    override fun onArtworkReceived(bitmap: android.graphics.Bitmap?) {
        currentArtwork = bitmap
        val state = lastState
        if (state != null) {
            updateMediaMetadata(state)
            val notification = buildNotification(state)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onPlayerStateReceived(state: RemoteMessage.PlayerState) {
        lastState = state
        updateMediaMetadata(state)
        val playbackState = when (state.status) {
            RemoteProtocol.STATUS_PLAYING -> PlaybackStateCompat.STATE_PLAYING
            RemoteProtocol.STATUS_PAUSED -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        updatePlaybackState(playbackState, state.positionMs)
        val notification = buildNotification(state)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun updateMediaMetadata(state: RemoteMessage.PlayerState) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title.ifEmpty { getString(R.string.no_track) })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist.ifEmpty { getString(R.string.unknown_artist) })
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.album.ifEmpty { getString(R.string.unknown_album) })
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)

        if (currentArtwork != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtwork)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArtwork)
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun updatePlaybackState(state: Int, positionMs: Long) {
        val speed = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, positionMs, speed)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun buildNotification(state: RemoteMessage.PlayerState?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = state?.status == RemoteProtocol.STATUS_PLAYING
        val title = state?.title?.ifEmpty { getString(R.string.no_track) } ?: getString(R.string.no_track)
        val artist = state?.artist?.ifEmpty { getString(R.string.unknown_artist) } ?: getString(R.string.unknown_artist)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(pendingOpen)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)

        if (currentArtwork != null) {
            builder.setLargeIcon(currentArtwork)
        }

        builder.addAction(
            R.drawable.ic_skip_previous,
            "Previous",
            createActionIntent(ACTION_PREV)
        )
        .addAction(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            if (isPlaying) "Pause" else "Play",
            createActionIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY)
        )
        .addAction(
            R.drawable.ic_skip_next,
            "Next",
            createActionIntent(ACTION_NEXT)
        )
        .setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )

        return builder.build()
    }

    private fun createActionIntent(action: String): PendingIntent {
        val intent = Intent(this, RemoteControlService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> connectionManager.sendCommand(RemoteCommand.Play)
            ACTION_PAUSE -> connectionManager.sendCommand(RemoteCommand.Pause)
            ACTION_PREV -> connectionManager.sendCommand(RemoteCommand.Previous)
            ACTION_NEXT -> connectionManager.sendCommand(RemoteCommand.Next)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        connectionManager.removeListener(this)
        connectionManager.disconnect()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "y2_remote_channel"
        const val NOTIFICATION_ID = 101

        const val ACTION_PLAY = "com.schulzcode.y2remote.ACTION_PLAY"
        const val ACTION_PAUSE = "com.schulzcode.y2remote.ACTION_PAUSE"
        const val ACTION_PREV = "com.schulzcode.y2remote.ACTION_PREV"
        const val ACTION_NEXT = "com.schulzcode.y2remote.ACTION_NEXT"
    }
}
