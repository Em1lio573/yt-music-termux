package com.emilio.ytmusic.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.emilio.ytmusic.service.AudioDspManager
import com.emilio.ytmusic.service.PlaybackCoordinator
import com.emilio.ytmusic.storage.PlayStatsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.ytmusic_core.*
import java.io.File

enum class ShuffleMode {
    OFF,
    SHUFFLE,
    SMART_SHUFFLE
}

data class PlayerUiState(
    val currentTrack: LocalTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lyrics: LyricsData? = null,
    val currentLyricIndex: Int = -1,
    val isSheetExpanded: Boolean = false,
    val showEqualizer: Boolean = false,
    val showLyrics: Boolean = false,
    val currentPreset: String = "flat",
    val bassBoost: Int = 0,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val dspManager = AudioDspManager()
    private val playStats = PlayStatsManager.getInstance(application)
    private var exoPlayer: ExoPlayer? = null
    private var tickerJob: Job? = null
    private var originalQueue: List<LocalTrack> = emptyList()
    private var queue: List<LocalTrack> = emptyList()

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(application)
            .setAudioAttributes(audioAttributes, false) // Foco gestionado por el servicio unificado
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                        if (isPlaying) {
                            startTicker()
                        } else {
                            stopTicker()
                        }
                        _uiState.value.currentTrack?.let { track ->
                            val dur = duration.coerceAtLeast(track.durationSec.toLong() * 1000L)
                            PlaybackCoordinator.onLocalStateChanged(
                                getApplication(),
                                track.title,
                                track.artist,
                                isPlaying,
                                track.filePath,
                                currentPosition.coerceAtLeast(0L),
                                dur
                            )
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            val dur = duration.coerceAtLeast(0L)
                            _uiState.value = _uiState.value.copy(durationMs = dur)
                            dspManager.attachToAudioSession(audioSessionId)
                            _uiState.value.currentTrack?.let { track ->
                                PlaybackCoordinator.onLocalStateChanged(
                                    getApplication(),
                                    track.title,
                                    track.artist,
                                    isPlaying,
                                    track.filePath,
                                    currentPosition.coerceAtLeast(0L),
                                    dur
                                )
                            }
                        } else if (playbackState == Player.STATE_ENDED) {
                            playNext()
                        }
                    }
                })
            }

        PlaybackCoordinator.onLocalPlay = { resume() }
        PlaybackCoordinator.onLocalPause = { pause() }
        PlaybackCoordinator.onLocalNext = { playNext() }
        PlaybackCoordinator.onLocalPrevious = { playPrevious() }
        PlaybackCoordinator.onLocalSeekTo = { seekTo(it) }
    }

    fun playTrack(track: LocalTrack, newQueue: List<LocalTrack>? = null) {
        val player = exoPlayer ?: return
        if (!newQueue.isNullOrEmpty()) {
            originalQueue = newQueue
            queue = when (_uiState.value.shuffleMode) {
                ShuffleMode.OFF -> originalQueue
                ShuffleMode.SHUFFLE -> playStats.fisherYatesShuffle(originalQueue, pinnedFirst = track)
                ShuffleMode.SMART_SHUFFLE -> playStats.smartWeightedShuffle(originalQueue, pinnedFirst = track)
            }
        }
        val fileUri = Uri.fromFile(File(track.filePath))
        val mediaItem = MediaItem.fromUri(fileUri)

        // Registrar reproducción para el algoritmo de Aleatorio Inteligente
        playStats.recordPlay(track.filePath)

        // Pausar silenciosamente cualquier audio web antes de reproducir la pista local
        com.emilio.ytmusic.web.WebMusicDownloader.pauseWebPlayerSilently()

        _uiState.value = _uiState.value.copy(
            currentTrack = track,
            positionMs = 0L,
            durationMs = track.durationSec.toLong() * 1000L,
            currentLyricIndex = -1
        )

        player.volume = 1f
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        loadLyricsForTrack(track)
    }

    /**
     * Inicia reproducción inmediata desde la biblioteca con Aleatorio o Aleatorio Inteligente
     */
    fun startWithShuffleMode(tracks: List<LocalTrack>, mode: ShuffleMode) {
        if (tracks.isEmpty()) return
        originalQueue = tracks
        _uiState.value = _uiState.value.copy(shuffleMode = mode)
        queue = when (mode) {
            ShuffleMode.OFF -> originalQueue
            ShuffleMode.SHUFFLE -> playStats.fisherYatesShuffle(originalQueue)
            ShuffleMode.SMART_SHUFFLE -> playStats.smartWeightedShuffle(originalQueue)
        }
        playTrack(queue.first())
    }

    /**
     * Alterna entre Orden Normal -> Aleatorio -> Aleatorio Inteligente sin interrumpir la canción actual
     */
    fun cycleShuffleMode() {
        val nextMode = when (_uiState.value.shuffleMode) {
            ShuffleMode.OFF -> ShuffleMode.SHUFFLE
            ShuffleMode.SHUFFLE -> ShuffleMode.SMART_SHUFFLE
            ShuffleMode.SMART_SHUFFLE -> ShuffleMode.OFF
        }
        _uiState.value = _uiState.value.copy(shuffleMode = nextMode)
        val current = _uiState.value.currentTrack
        if (originalQueue.isNotEmpty()) {
            queue = when (nextMode) {
                ShuffleMode.OFF -> originalQueue
                ShuffleMode.SHUFFLE -> playStats.fisherYatesShuffle(originalQueue, pinnedFirst = current)
                ShuffleMode.SMART_SHUFFLE -> playStats.smartWeightedShuffle(originalQueue, pinnedFirst = current)
            }
        }
    }

    fun playNext() {
        if (queue.isEmpty()) return
        val current = _uiState.value.currentTrack ?: return
        val idx = queue.indexOfFirst { it.filePath == current.filePath }
        if (idx >= 0) {
            val nextIdx = idx + 1
            if (nextIdx >= queue.size) {
                // Al terminar la cola, re-mezclar automáticamente si está en modo aleatorio
                if (_uiState.value.shuffleMode == ShuffleMode.SHUFFLE && originalQueue.isNotEmpty()) {
                    queue = playStats.fisherYatesShuffle(originalQueue)
                } else if (_uiState.value.shuffleMode == ShuffleMode.SMART_SHUFFLE && originalQueue.isNotEmpty()) {
                    queue = playStats.smartWeightedShuffle(originalQueue)
                }
                playTrack(queue.first())
            } else {
                playTrack(queue[nextIdx])
            }
        }
    }

    fun playPrevious() {
        if (queue.isEmpty()) return
        val current = _uiState.value.currentTrack ?: return
        val idx = queue.indexOfFirst { it.filePath == current.filePath }
        if (idx >= 0) {
            val prevTrack = queue[if (idx - 1 < 0) queue.size - 1 else idx - 1]
            playTrack(prevTrack)
        }
    }

    fun resume() {
        val player = exoPlayer ?: return
        if (!player.isPlaying) {
            com.emilio.ytmusic.web.WebMusicDownloader.pauseWebPlayerSilently()
            player.play()
        }
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            resume()
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    private var seekFadeJob: Job? = null

    fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        seekFadeJob?.cancel()

        // Suavizado anti-pop/anti-pico de volumen al saltar bruscamente en la barra de progreso
        player.volume = 0f
        player.seekTo(positionMs)
        _uiState.value = _uiState.value.copy(positionMs = positionMs)

        seekFadeJob = viewModelScope.launch {
            delay(25)
            val steps = 5
            for (i in 1..steps) {
                player.volume = i.toFloat() / steps.toFloat()
                delay(12)
            }
            player.volume = 1f
        }

        _uiState.value.currentTrack?.let { track ->
            PlaybackCoordinator.onLocalStateChanged(
                getApplication(),
                track.title,
                track.artist,
                _uiState.value.isPlaying,
                track.filePath,
                positionMs,
                _uiState.value.durationMs
            )
        }
    }

    fun toggleSheet(expanded: Boolean) {
        _uiState.value = _uiState.value.copy(isSheetExpanded = expanded)
    }

    fun toggleEqualizer(show: Boolean) {
        _uiState.value = _uiState.value.copy(showEqualizer = show)
    }

    fun toggleLyrics(show: Boolean) {
        _uiState.value = _uiState.value.copy(showLyrics = show)
    }

    fun applyPreset(presetKey: String) {
        dspManager.applyPreset(presetKey)
        _uiState.value = _uiState.value.copy(currentPreset = presetKey)
    }

    fun setBassBoost(level: Int) {
        dspManager.setBassBoost(level)
        _uiState.value = _uiState.value.copy(bassBoost = level)
    }

    private fun loadLyricsForTrack(track: LocalTrack) {
        viewModelScope.launch {
            val lyrics = ytmFetchLyrics(track.title, track.artist, track.album, track.durationSec)
            _uiState.value = _uiState.value.copy(lyrics = lyrics)
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val currentPos = exoPlayer?.currentPosition ?: 0L
                val lyrics = _uiState.value.lyrics
                var activeLyricIdx = -1

                if (lyrics != null && lyrics.isSynced) {
                    for (i in lyrics.lines.indices.reversed()) {
                        if (currentPos >= lyrics.lines[i].timestampMs.toLong()) {
                            activeLyricIdx = i
                            break
                        }
                    }
                }

                _uiState.value = _uiState.value.copy(
                    positionMs = currentPos,
                    currentLyricIndex = activeLyricIdx
                )
                delay(200)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
    }

    override fun onCleared() {
        dspManager.release()
        exoPlayer?.release()
        exoPlayer = null
        super.onCleared()
    }
}
