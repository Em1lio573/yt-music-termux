package com.emilio.ytmusic.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.emilio.ytmusic.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import uniffi.ytmusic_core.*
import java.lang.ref.WeakReference

/**
 * Representa una pista detectada en el reproductor web de YouTube Music
 */
data class WebTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val durationSec: UInt
)

/**
 * Estado del descargador web
 */
data class WebDownloadState(
    val currentTrack: WebTrack? = null,
    val isPlaying: Boolean = false,
    val activeDownload: DownloadProgress? = null,
    val selectedQuality: String = "native",
    val statusMessage: String? = null
)

/**
 * Puente JavaScript para recibir en tiempo real las pistas y el estado de reproducción
 */
class WebMusicBridge(
    private val onTrackDetected: (WebTrack) -> Unit,
    private val onWebPlayStateChanged: ((Boolean) -> Unit)? = null,
    private val onWebPositionChanged: ((Long, Long) -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onWebPlayStateChanged(isPlaying: Boolean) {
        mainHandler.post {
            onWebPlayStateChanged?.invoke(isPlaying)
        }
    }

    @JavascriptInterface
    fun onWebPositionChanged(positionMs: Long, durationMs: Long) {
        mainHandler.post {
            onWebPositionChanged?.invoke(positionMs, durationMs)
        }
    }

    @JavascriptInterface
    fun onTrackUpdate(
        videoId: String?,
        title: String?,
        artist: String?,
        album: String?,
        coverUrl: String?,
        durationSec: Long
    ) {
        if (!videoId.isNullOrBlank()) {
            val track = WebTrack(
                videoId = videoId.trim(),
                title = if (!title.isNullOrBlank()) title.trim() else "Pista de YouTube Music",
                artist = if (!artist.isNullOrBlank()) artist.trim() else "YouTube Music",
                album = if (!album.isNullOrBlank()) album.trim() else "YouTube Music",
                coverUrl = if (!coverUrl.isNullOrBlank()) coverUrl.trim() else "https://i.ytimg.com/vi/${videoId.trim()}/hqdefault.jpg",
                durationSec = durationSec.coerceAtLeast(0L).toUInt()
            )
            mainHandler.post {
                onTrackDetected(track)
            }
        }
    }
}

/**
 * Controlador de descargas y coordinación de audio para el navegador web de YouTube Music
 */
object WebMusicDownloader {

    private val _state = MutableStateFlow(WebDownloadState())
    val state: StateFlow<WebDownloadState> = _state.asStateFlow()

    // Referencia débil y caché persistente del WebView para apertura instantánea y reproducción en segundo plano
    var activeWebView: WeakReference<WebView>? = null
    var cachedWebView: BackgroundWebView? = null
    var appContext: Context? = null
    @Volatile
    var isWebPlaying: Boolean = false

    private fun evalJsOnWebView(js: String) {
        val wv = cachedWebView ?: activeWebView?.get() ?: return
        wv.post {
            try {
                wv.evaluateJavascript(js, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Pausa el <video> del navegador sin sobrescribir la notificación (usado cuando el reproductor local toma el control)
     */
    fun pauseWebPlayerSilently() {
        evalJsOnWebView(
            """
            (function() {
                try {
                    const v = document.querySelector('video');
                    if (v && !v.paused) v.pause();
                } catch(e) {}
            })();
            """.trimIndent()
        )
        isWebPlaying = false
        _state.value = _state.value.copy(isPlaying = false)
    }

    /**
     * Pausa la reproducción de música online en el WebView
     */
    fun pauseWebPlayer() {
        pauseWebPlayerSilently()
        appContext?.let { ctx ->
            val track = _state.value.currentTrack
            com.emilio.ytmusic.service.PlaybackCoordinator.onWebStateChanged(
                ctx,
                track?.title ?: "YouTube Music Web",
                track?.artist ?: "En segundo plano",
                false,
                track?.coverUrl
            )
        }
    }

    fun resumeWebPlayer() {
        evalJsOnWebView(
            """
            (function() {
                try {
                    const v = document.querySelector('video');
                    if (v && v.paused) v.play();
                } catch(e) {}
            })();
            """.trimIndent()
        )
    }

    fun toggleWebPlayPause() {
        evalJsOnWebView(
            """
            (function() {
                try {
                    const v = document.querySelector('video');
                    if (v) { v.paused ? v.play() : v.pause(); }
                } catch(e) {}
            })();
            """.trimIndent()
        )
    }

    fun nextWebTrack() {
        evalJsOnWebView(
            """
            (function() {
                try {
                    const btn = document.querySelector('.next-button, [aria-label*="Siguiente" i], [aria-label*="Next" i]');
                    if (btn) btn.click();
                } catch(e) {}
            })();
            """.trimIndent()
        )
    }

    fun previousWebTrack() {
        evalJsOnWebView(
            """
            (function() {
                try {
                    const btn = document.querySelector('.previous-button, [aria-label*="Anterior" i], [aria-label*="Previous" i]');
                    if (btn) btn.click();
                } catch(e) {}
            })();
            """.trimIndent()
        )
    }

    fun seekWebPlayer(positionMs: Long) {
        val sec = (positionMs.coerceAtLeast(0L) / 1000.0)
        evalJsOnWebView(
            """
            (function() {
                try {
                    const v = document.querySelector('video');
                    if (v && isFinite($sec)) {
                        v.currentTime = $sec;
                    }
                } catch(e) {}
            })();
            """.trimIndent()
        )
    }

    @Volatile
    var lastWebPositionMs: Long = 0L

    fun updateWebPosition(context: Context, positionMs: Long, durationMs: Long) {
        lastWebPositionMs = positionMs
        val track = _state.value.currentTrack
        val dur = if (durationMs > 0L) durationMs else (track?.durationSec?.toLong() ?: 0L) * 1000L
        com.emilio.ytmusic.service.PlaybackCoordinator.onWebTrackMetadataUpdated(
            context.applicationContext,
            track?.title ?: "YouTube Music Web",
            track?.artist ?: "En segundo plano",
            isWebPlaying,
            track?.coverUrl,
            positionMs,
            dur
        )
    }

    fun updateWebPlayState(context: Context, isPlaying: Boolean) {
        appContext = context.applicationContext
        isWebPlaying = isPlaying
        _state.value = _state.value.copy(isPlaying = isPlaying)
        val track = _state.value.currentTrack
        val dur = (track?.durationSec?.toLong() ?: 0L) * 1000L
        com.emilio.ytmusic.service.PlaybackCoordinator.onWebStateChanged(
            context.applicationContext,
            track?.title ?: "YouTube Music Web",
            track?.artist ?: "En segundo plano",
            isPlaying,
            track?.coverUrl,
            lastWebPositionMs,
            dur
        )
    }

    fun updateCurrentTrack(track: WebTrack) {
        _state.value = _state.value.copy(currentTrack = track)
        appContext?.let { ctx ->
            com.emilio.ytmusic.service.PlaybackCoordinator.onWebTrackMetadataUpdated(
                ctx,
                track.title,
                track.artist,
                isWebPlaying,
                track.coverUrl,
                lastWebPositionMs,
                track.durationSec.toLong() * 1000L
            )
        }
    }

    fun setQuality(quality: String) {
        _state.value = _state.value.copy(selectedQuality = quality)
    }

    fun clearStatusMessage() {
        _state.value = _state.value.copy(statusMessage = null)
    }

    /**
     * Inicia la descarga de la pista detectada usando el motor nativo en Rust
     */
    fun startDownload(
        context: Context,
        track: WebTrack,
        scope: CoroutineScope,
        onSuccess: (() -> Unit)? = null
    ) {
        val quality = _state.value.selectedQuality
        val storageManager = StorageManager.getInstance(context)
        val downloadPath = storageManager.getDownloadFolderPath()

        val trackMetadata = TrackMetadata(
            id = track.videoId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = track.durationSec,
            coverUrl = track.coverUrl,
            isExplicit = false,
            source = "youtube"
        )

        scope.launch(Dispatchers.IO) {
            val callback = object : DownloadCallback {
                override fun onProgress(progress: DownloadProgress) {
                    _state.value = _state.value.copy(activeDownload = progress)
                    if (progress.state == "completed") {
                        onSuccess?.invoke()
                    }
                }
            }

            try {
                ytmStartDownload(trackMetadata, quality, downloadPath, callback)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    statusMessage = "Error al descargar: ${e.message}"
                )
            }
        }
    }

    /**
     * Evalúa JavaScript en el WebView para forzar la extracción inmediata de la canción actual
     */
    fun queryCurrentTrackFromWebView(webView: WebView, onResult: (WebTrack?) -> Unit) {
        webView.evaluateJavascript(TRACK_EXTRACT_JS) { raw ->
            val unwrapped = unwrapJsString(raw)
            if (unwrapped.isNullOrBlank()) {
                onResult(null)
                return@evaluateJavascript
            }

            try {
                val json = JSONObject(unwrapped)
                val videoId = json.optString("videoId")
                if (videoId.isNotBlank()) {
                    val track = WebTrack(
                        videoId = videoId.trim(),
                        title = json.optString("title", "Pista de YouTube Music").trim().ifBlank { "Pista de YouTube Music" },
                        artist = json.optString("artist", "YouTube Music").trim().ifBlank { "YouTube Music" },
                        album = json.optString("album", "YouTube Music").trim().ifBlank { "YouTube Music" },
                        coverUrl = json.optString("coverUrl").trim().ifBlank { "https://i.ytimg.com/vi/${videoId.trim()}/hqdefault.jpg" },
                        durationSec = json.optLong("durationSec", 0L).coerceAtLeast(0L).toUInt()
                    )
                    updateCurrentTrack(track)
                    onResult(track)
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onResult(null)
            }
        }
    }

    private fun unwrapJsString(raw: String?): String? {
        if (raw == null || raw == "null" || raw == "\"null\"") return null
        return if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
            try {
                JSONTokener(raw).nextValue() as? String ?: raw.substring(1, raw.length - 1)
            } catch (e: Exception) {
                raw.substring(1, raw.length - 1)
            }
        } else {
            raw
        }
    }

    /**
     * Scriptlet inyectado que observa en tiempo real los cambios de canción y el estado de reproducción
     */
    val TRACK_OBSERVER_JS: String = """
        (function() {
            'use strict';
            if (window.__ytm_track_observer_active) return;
            window.__ytm_track_observer_active = true;

            // Escuchar eventos de reproducción/pausa/seek en el elemento de audio/video
            function reportPos(v) {
                if (window.AndroidBridge && typeof window.AndroidBridge.onWebPositionChanged === 'function') {
                    const pos = Math.floor((v.currentTime || 0) * 1000);
                    const dur = isFinite(v.duration) ? Math.floor(v.duration * 1000) : 0;
                    window.AndroidBridge.onWebPositionChanged(pos, dur);
                }
            }

            function attachMediaEvents() {
                const video = document.querySelector('video');
                if (video && !video.__ytm_audio_events_attached) {
                    video.__ytm_audio_events_attached = true;
                    video.addEventListener('play', function() {
                        reportPos(video);
                        if (window.AndroidBridge && typeof window.AndroidBridge.onWebPlayStateChanged === 'function') {
                            window.AndroidBridge.onWebPlayStateChanged(true);
                        }
                    });
                    video.addEventListener('playing', function() {
                        reportPos(video);
                        if (window.AndroidBridge && typeof window.AndroidBridge.onWebPlayStateChanged === 'function') {
                            window.AndroidBridge.onWebPlayStateChanged(true);
                        }
                    });
                    video.addEventListener('seeked', function() {
                        reportPos(video);
                    });
                    video.addEventListener('durationchange', function() {
                        reportPos(video);
                    });
                    video.addEventListener('pause', function() {
                        reportPos(video);
                        if (window.AndroidBridge && typeof window.AndroidBridge.onWebPlayStateChanged === 'function') {
                            window.AndroidBridge.onWebPlayStateChanged(false);
                        }
                    });
                    video.addEventListener('ended', function() {
                        if (window.AndroidBridge && typeof window.AndroidBridge.onWebPlayStateChanged === 'function') {
                            window.AndroidBridge.onWebPlayStateChanged(false);
                        }
                    });
                }
            }

            function extractCurrentTrack() {
                let videoId = '';
                let title = '';
                let artist = '';
                let album = '';
                let coverUrl = '';
                let durationSec = 0;

                // 0. Priorizar barra de YouTube Music y MediaSession (contienen artista principal + secundarios y álbum)
                try {
                    const bar = document.querySelector('ytmusic-player-bar');
                    if (bar) {
                        const t = bar.querySelector('.title.ytmusic-player-bar, yt-formatted-string.title');
                        if (t) title = (t.textContent || '').trim();

                        const b = bar.querySelector('.byline.ytmusic-player-bar, yt-formatted-string.byline');
                        if (b) {
                            const txt = (b.textContent || '').trim();
                            const parts = txt.split('•').map(s => s.trim()).filter(Boolean);
                            if (parts.length > 0) artist = parts[0];
                            if (parts.length > 1) album = parts[1];
                        }

                        const img = bar.querySelector('img#img, img.image.ytmusic-player-bar, img');
                        if (img && img.src && !img.src.startsWith('data:')) coverUrl = img.src;
                    }
                } catch(e) {}

                try {
                    if (navigator.mediaSession && navigator.mediaSession.metadata) {
                        const ms = navigator.mediaSession.metadata;
                        if (!title && ms.title) title = ms.title.trim();
                        if (!artist && ms.artist) artist = ms.artist.trim();
                        if (!album && ms.album) album = ms.album.trim();
                        if (!coverUrl && ms.artwork && ms.artwork.length > 0) {
                            coverUrl = ms.artwork[ms.artwork.length - 1].src;
                        }
                    }
                } catch(e) {}

                // 1. Consultar API interna de movie_player (ID, duración y fallback)
                try {
                    const mp = document.querySelector('#movie_player');
                    if (mp && typeof mp.getVideoData === 'function') {
                        const vd = mp.getVideoData();
                        if (vd) {
                            if (vd.video_id) videoId = vd.video_id;
                            if (!title && vd.title) title = vd.title;
                            if (!artist && vd.author) artist = vd.author.replace(/\s*-\s*Topic$/i, '').trim();
                        }
                        if (typeof mp.getDuration === 'function') {
                            durationSec = Math.floor(mp.getDuration() || 0);
                        }
                    }
                } catch(e) {}

                // 2. Consultar componente ytmusic-player
                try {
                    const yp = document.querySelector('ytmusic-player');
                    if (yp && typeof yp.getPlayerResponse === 'function') {
                        const pr = yp.getPlayerResponse();
                        if (pr && pr.videoDetails) {
                            if (!videoId && pr.videoDetails.videoId) videoId = pr.videoDetails.videoId;
                            if (!title && pr.videoDetails.title) title = pr.videoDetails.title;
                            if (!artist && pr.videoDetails.author) artist = pr.videoDetails.author.replace(/\s*-\s*Topic$/i, '').trim();
                            if (pr.videoDetails.lengthSeconds) durationSec = parseInt(pr.videoDetails.lengthSeconds, 10) || durationSec;
                            if (pr.videoDetails.thumbnail && pr.videoDetails.thumbnail.thumbnails) {
                                const th = pr.videoDetails.thumbnail.thumbnails;
                                if (th.length > 0) coverUrl = th[th.length - 1].url;
                            }
                        }
                    }
                } catch(e) {}

                // 4. Extraer ID del video desde la URL si aún no se tiene
                if (!videoId) {
                    try {
                        const urlParams = new URLSearchParams(window.location.search);
                        const v = urlParams.get('v');
                        if (v && v.length >= 11) videoId = v.substring(0, 11);
                    } catch(e) {}
                }

                // 5. Título alternativo desde document.title
                if (!title && document.title) {
                    const cleanTitle = document.title.replace(' - YouTube Music', '').trim();
                    if (cleanTitle && cleanTitle !== 'YouTube Music') {
                        title = cleanTitle;
                    }
                }

                // 6. Carátula por defecto de alta resolución si no hay miniatura
                if (!coverUrl && videoId) {
                    coverUrl = 'https://i.ytimg.com/vi/' + videoId + '/hqdefault.jpg';
                }

                return {
                    videoId: videoId || '',
                    title: title || '',
                    artist: artist || '',
                    album: album || '',
                    coverUrl: coverUrl || '',
                    durationSec: durationSec || 0
                };
            }

            function notifyBridge() {
                try {
                    attachMediaEvents();
                    if (!window.AndroidBridge || typeof window.AndroidBridge.onTrackUpdate !== 'function') return;
                    const track = extractCurrentTrack();
                    if (track.videoId && (track.videoId !== window.__lastReportedVideoId || track.title !== window.__lastReportedTitle)) {
                        window.__lastReportedVideoId = track.videoId;
                        window.__lastReportedTitle = track.title;
                        window.AndroidBridge.onTrackUpdate(
                            track.videoId,
                            track.title,
                            track.artist,
                            track.album,
                            track.coverUrl,
                            track.durationSec
                        );
                    }
                } catch (e) {}
            }

            // Monitorear cada 1.2 segundos y ante eventos de navegación de YouTube Music
            setInterval(notifyBridge, 1200);
            document.addEventListener('yt-navigate-finish', notifyBridge);
            window.addEventListener('popstate', notifyBridge);
            document.addEventListener('DOMContentLoaded', notifyBridge);
        })();
    """.trimIndent()

    /**
     * Script de extracción inmediata para cuando el usuario presiona el botón manualmente
     */
    val TRACK_EXTRACT_JS: String = """
        (function() {
            let videoId = '';
            let title = '';
            let artist = '';
            let album = '';
            let coverUrl = '';
            let durationSec = 0;

            try {
                const bar = document.querySelector('ytmusic-player-bar');
                if (bar) {
                    const t = bar.querySelector('.title.ytmusic-player-bar, yt-formatted-string.title');
                    if (t) title = (t.textContent || '').trim();
                    const b = bar.querySelector('.byline.ytmusic-player-bar, yt-formatted-string.byline');
                    if (b) {
                        const txt = (b.textContent || '').trim();
                        const parts = txt.split('•').map(s => s.trim()).filter(Boolean);
                        if (parts.length > 0) artist = parts[0];
                        if (parts.length > 1) album = parts[1];
                    }
                    const img = bar.querySelector('img#img, img.image.ytmusic-player-bar, img');
                    if (img && img.src && !img.src.startsWith('data:')) coverUrl = img.src;
                }
            } catch(e) {}

            try {
                if (navigator.mediaSession && navigator.mediaSession.metadata) {
                    const ms = navigator.mediaSession.metadata;
                    if (!title && ms.title) title = ms.title.trim();
                    if (!artist && ms.artist) artist = ms.artist.trim();
                    if (!album && ms.album) album = ms.album.trim();
                    if (!coverUrl && ms.artwork && ms.artwork.length > 0) {
                        coverUrl = ms.artwork[ms.artwork.length - 1].src;
                    }
                }
            } catch(e) {}

            try {
                const mp = document.querySelector('#movie_player');
                if (mp && typeof mp.getVideoData === 'function') {
                    const vd = mp.getVideoData();
                    if (vd) {
                        if (vd.video_id) videoId = vd.video_id;
                        if (!title && vd.title) title = vd.title;
                        if (!artist && vd.author) artist = vd.author.replace(/\s*-\s*Topic$/i, '').trim();
                    }
                    if (typeof mp.getDuration === 'function') {
                        durationSec = Math.floor(mp.getDuration() || 0);
                    }
                }
            } catch(e) {}

            try {
                const yp = document.querySelector('ytmusic-player');
                if (yp && typeof yp.getPlayerResponse === 'function') {
                    const pr = yp.getPlayerResponse();
                    if (pr && pr.videoDetails) {
                        if (!videoId && pr.videoDetails.videoId) videoId = pr.videoDetails.videoId;
                        if (!title && pr.videoDetails.title) title = pr.videoDetails.title;
                        if (!artist && pr.videoDetails.author) artist = pr.videoDetails.author.replace(/\s*-\s*Topic$/i, '').trim();
                        if (pr.videoDetails.lengthSeconds) durationSec = parseInt(pr.videoDetails.lengthSeconds, 10) || durationSec;
                        if (pr.videoDetails.thumbnail && pr.videoDetails.thumbnail.thumbnails) {
                            const th = pr.videoDetails.thumbnail.thumbnails;
                            if (th.length > 0) coverUrl = th[th.length - 1].url;
                        }
                    }
                }
            } catch(e) {}

            if (!videoId) {
                try {
                    const urlParams = new URLSearchParams(window.location.search);
                    const v = urlParams.get('v');
                    if (v && v.length >= 11) videoId = v.substring(0, 11);
                } catch(e) {}
            }

            if (!title && document.title) {
                const cleanTitle = document.title.replace(' - YouTube Music', '').trim();
                if (cleanTitle && cleanTitle !== 'YouTube Music') {
                    title = cleanTitle;
                }
            }

            if (!coverUrl && videoId) {
                coverUrl = 'https://i.ytimg.com/vi/' + videoId + '/hqdefault.jpg';
            }

            return JSON.stringify({
                videoId: videoId || '',
                title: title || '',
                artist: artist || '',
                album: album || '',
                coverUrl: coverUrl || '',
                durationSec: durationSec || 0
            });
        })()
    """.trimIndent()
}
