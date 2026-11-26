package com.emilio.ytmusic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.emilio.ytmusic.MainActivity
import com.emilio.ytmusic.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground Service unificado para mantener activa la reproducción (tanto Local como Web Music)
 * en segundo plano, con pantalla apagada, foco de audio, detección de desconexión de audífonos
 * y controles nativos en pantalla de bloqueo / Bluetooth / notificaciones.
 */
class WebPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "ytmusic_unified_playback_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START_OR_UPDATE = "com.emilio.ytmusic.action.UNIFIED_START_UPDATE"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.emilio.ytmusic.action.UNIFIED_TOGGLE"
        const val ACTION_NEXT = "com.emilio.ytmusic.action.UNIFIED_NEXT"
        const val ACTION_PREV = "com.emilio.ytmusic.action.UNIFIED_PREV"
        const val ACTION_STOP = "com.emilio.ytmusic.action.UNIFIED_STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_COVER_SOURCE = "extra_cover_source"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_DURATION_MS = "extra_duration_ms"

        @Volatile
        var isCurrentlyPlaying: Boolean = false

        @Volatile
        private var isServiceRunning: Boolean = false

        fun syncState(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean,
            coverSource: String? = null,
            positionMs: Long = -1L,
            durationMs: Long = -1L
        ) {
            isCurrentlyPlaying = isPlaying
            val intent = Intent(context.applicationContext, WebPlaybackService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                if (!coverSource.isNullOrBlank()) {
                    putExtra(EXTRA_COVER_SOURCE, coverSource)
                }
                if (positionMs >= 0L) putExtra(EXTRA_POSITION_MS, positionMs)
                if (durationMs > 0L) putExtra(EXTRA_DURATION_MS, durationMs)
            }
            if (isPlaying) {
                context.applicationContext.startForegroundService(intent)
            } else if (isServiceRunning) {
                try {
                    context.applicationContext.startService(intent)
                } catch (_: Exception) {}
            }
        }

        fun stop(context: Context) {
            isCurrentlyPlaying = false
            try {
                context.applicationContext.stopService(Intent(context.applicationContext, WebPlaybackService::class.java))
            } catch (_: Exception) {}
        }
    }

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentTitle: String = "YouTube Music Pro"
    private var currentArtist: String = "Reproducción activa"
    private var currentIsPlaying: Boolean = false
    private var currentCoverSource: String? = null
    private var currentArtwork: Bitmap? = null
    private var currentPositionMs: Long = 0L
    private var currentDurationMs: Long = 0L

    // Pausa automática si se desconectan audífonos Bluetooth o cableados
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                PlaybackCoordinator.pauseActive()
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                PlaybackCoordinator.pauseActive()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTMusicPro:UnifiedPlaybackWakeLock").apply {
            setReferenceCounted(false)
        }

        mediaSession = MediaSession(this, "YTMusicUnifiedSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    PlaybackCoordinator.playActive()
                }

                override fun onPause() {
                    PlaybackCoordinator.pauseActive()
                }

                override fun onSkipToNext() {
                    PlaybackCoordinator.nextActive()
                }

                override fun onSkipToPrevious() {
                    PlaybackCoordinator.previousActive()
                }

                override fun onSeekTo(pos: Long) {
                    currentPositionMs = pos.coerceAtLeast(0L)
                    PlaybackCoordinator.seekActive(currentPositionMs)
                    updateMediaSessionState()
                }

                override fun onStop() {
                    PlaybackCoordinator.pauseActive()
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> {
                PlaybackCoordinator.toggleActivePlayPause()
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                PlaybackCoordinator.nextActive()
                return START_NOT_STICKY
            }
            ACTION_PREV -> {
                PlaybackCoordinator.previousActive()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                PlaybackCoordinator.pauseActive()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_OR_UPDATE -> {
                currentTitle = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { "YouTube Music Pro" } ?: currentTitle
                currentArtist = intent.getStringExtra(EXTRA_ARTIST)?.ifBlank { "En segundo plano" } ?: currentArtist
                currentIsPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, currentIsPlaying)
                isCurrentlyPlaying = currentIsPlaying
                if (intent.hasExtra(EXTRA_POSITION_MS)) {
                    currentPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, currentPositionMs)
                }
                if (intent.hasExtra(EXTRA_DURATION_MS)) {
                    currentDurationMs = intent.getLongExtra(EXTRA_DURATION_MS, currentDurationMs)
                }
                val newCover = intent.getStringExtra(EXTRA_COVER_SOURCE)
                if (!newCover.isNullOrBlank() && newCover != currentCoverSource) {
                    currentCoverSource = newCover
                    currentArtwork = null
                    loadArtworkAsync(newCover)
                }
            }
        }

        updateMediaSessionState()

        if (currentIsPlaying) {
            requestAudioFocus()
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L)
            }
        } else {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun updateMediaSessionState() {
        val state = if (currentIsPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val speed = if (currentIsPlaying) 1.0f else 0.0f
        val pbState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_STOP
            )
            .setState(state, currentPositionMs, speed)
            .build()
        mediaSession?.setPlaybackState(pbState)

        val metaBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
        if (currentDurationMs > 0L) {
            metaBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, currentDurationMs)
        }
        currentArtwork?.let { bmp ->
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, bmp)
        }
        mediaSession?.setMetadata(metaBuilder.build())
    }

    private fun loadArtworkAsync(source: String) {
        serviceScope.launch {
            val bmp = decodeArtwork(source)
            if (bmp != null && source == currentCoverSource) {
                currentArtwork = bmp
                withContext(Dispatchers.Main) {
                    updateMediaSessionState()
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
    }

    private fun decodeArtwork(source: String): Bitmap? {
        return try {
            if (source.startsWith("http://") || source.startsWith("https://")) {
                val conn = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    doInput = true
                }
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } else {
                // Primero probar caché privada por hash de archivo
                val key = source.hashCode().toUInt().toString(16)
                val cached = File(File(cacheDir, "covers"), "art_$key.jpg")
                if (cached.exists() && cached.length() > 0) {
                    return BitmapFactory.decodeFile(cached.absolutePath)
                }
                val f = File(source)
                if (f.exists() && (source.endsWith(".jpg", true) || source.endsWith(".png", true) || source.endsWith(".webp", true))) {
                    return BitmapFactory.decodeFile(f.absolutePath)
                }
                // Extraer carátula incrustada del archivo de audio (.opus, .m4a, .mp3, .flac)
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(source)
                    val raw = mmr.embeddedPicture
                    if (raw != null && raw.isNotEmpty()) {
                        BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    } else null
                } finally {
                    mmr.release()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevPending = PendingIntent.getService(
            this, 1,
            Intent(this, WebPlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val togglePending = PendingIntent.getService(
            this, 2,
            Intent(this, WebPlaybackService::class.java).apply { action = ACTION_TOGGLE_PLAY_PAUSE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextPending = PendingIntent.getService(
            this, 3,
            Intent(this, WebPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseRes = if (currentIsPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (currentIsPlaying) "Pausar" else "Reproducir"

        val prevAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_previous),
            "Anterior",
            prevPending
        ).build()
        val toggleAction = Notification.Action.Builder(
            Icon.createWithResource(this, playPauseRes),
            playPauseLabel,
            togglePending
        ).build()
        val nextAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_next),
            "Siguiente",
            nextPending
        ).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setContentIntent(openIntent)
            .setOngoing(currentIsPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(prevAction)
            .addAction(toggleAction)
            .addAction(nextAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        currentArtwork?.let { bmp ->
            builder.setLargeIcon(bmp)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproducción de Música",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles y reproducción en segundo plano para biblioteca local y Web Music"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        isCurrentlyPlaying = false
        serviceScope.cancel()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {}
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
