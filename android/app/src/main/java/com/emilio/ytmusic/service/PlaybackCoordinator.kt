package com.emilio.ytmusic.service

import android.content.Context
import com.emilio.ytmusic.web.WebMusicDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioSource {
    NONE,
    LOCAL,
    WEB
}

/**
 * Coordinador central de audio que unifica el reproductor Local (ExoPlayer) y el Web (WebView)
 * bajo una sola sesión multimedia, una sola notificación y cambio automático sin solapamientos.
 */
object PlaybackCoordinator {

    private val _activeSource = MutableStateFlow(AudioSource.NONE)
    val activeSource: StateFlow<AudioSource> = _activeSource.asStateFlow()

    // Callbacks registrados por PlayerViewModel (reproductor local)
    var onLocalPlay: (() -> Unit)? = null
    var onLocalPause: (() -> Unit)? = null
    var onLocalNext: (() -> Unit)? = null
    var onLocalPrevious: (() -> Unit)? = null
    var onLocalSeekTo: ((Long) -> Unit)? = null

    fun onLocalStateChanged(
        context: Context,
        title: String,
        artist: String,
        isPlaying: Boolean,
        coverSource: String? = null,
        positionMs: Long = 0L,
        durationMs: Long = 0L
    ) {
        if (isPlaying) {
            if (_activeSource.value == AudioSource.WEB) {
                WebMusicDownloader.pauseWebPlayerSilently()
            }
            _activeSource.value = AudioSource.LOCAL
            WebPlaybackService.syncState(context, title, artist, true, coverSource, positionMs, durationMs)
        } else if (_activeSource.value == AudioSource.LOCAL) {
            WebPlaybackService.syncState(context, title, artist, false, coverSource, positionMs, durationMs)
        }
    }

    fun onWebStateChanged(
        context: Context,
        title: String,
        artist: String,
        isPlaying: Boolean,
        coverSource: String? = null,
        positionMs: Long = 0L,
        durationMs: Long = 0L
    ) {
        if (isPlaying) {
            if (_activeSource.value == AudioSource.LOCAL) {
                onLocalPause?.invoke()
            }
            _activeSource.value = AudioSource.WEB
            WebPlaybackService.syncState(context, title, artist, true, coverSource, positionMs, durationMs)
        } else if (_activeSource.value == AudioSource.WEB) {
            WebPlaybackService.syncState(context, title, artist, false, coverSource, positionMs, durationMs)
        }
    }

    fun onWebTrackMetadataUpdated(
        context: Context,
        title: String,
        artist: String,
        isPlaying: Boolean,
        coverSource: String? = null,
        positionMs: Long = 0L,
        durationMs: Long = 0L
    ) {
        if (_activeSource.value == AudioSource.WEB) {
            WebPlaybackService.syncState(context, title, artist, isPlaying, coverSource, positionMs, durationMs)
        }
    }

    fun seekActive(positionMs: Long) {
        when (_activeSource.value) {
            AudioSource.LOCAL -> onLocalSeekTo?.invoke(positionMs)
            AudioSource.WEB -> WebMusicDownloader.seekWebPlayer(positionMs)
            AudioSource.NONE -> Unit
        }
    }

    fun playActive() {
        when (_activeSource.value) {
            AudioSource.LOCAL -> onLocalPlay?.invoke()
            AudioSource.WEB -> WebMusicDownloader.resumeWebPlayer()
            AudioSource.NONE -> Unit
        }
    }

    fun pauseActive() {
        when (_activeSource.value) {
            AudioSource.LOCAL -> onLocalPause?.invoke()
            AudioSource.WEB -> WebMusicDownloader.pauseWebPlayer()
            AudioSource.NONE -> Unit
        }
    }

    fun toggleActivePlayPause() {
        when (_activeSource.value) {
            AudioSource.LOCAL -> {
                if (WebPlaybackService.isCurrentlyPlaying) onLocalPause?.invoke() else onLocalPlay?.invoke()
            }
            AudioSource.WEB -> WebMusicDownloader.toggleWebPlayPause()
            AudioSource.NONE -> Unit
        }
    }

    fun nextActive() {
        when (_activeSource.value) {
            AudioSource.LOCAL -> onLocalNext?.invoke()
            AudioSource.WEB -> WebMusicDownloader.nextWebTrack()
            AudioSource.NONE -> Unit
        }
    }

    fun previousActive() {
        when (_activeSource.value) {
            AudioSource.LOCAL -> onLocalPrevious?.invoke()
            AudioSource.WEB -> WebMusicDownloader.previousWebTrack()
            AudioSource.NONE -> Unit
        }
    }
}
