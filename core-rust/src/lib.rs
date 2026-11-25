pub mod models;
pub mod extractor;
pub mod metadata;
pub mod tagger;
pub mod downloader;
pub mod library;
pub mod auth;

use std::sync::Arc;
use once_cell::sync::Lazy;

use models::{DownloadCallback, LyricsData, LocalTrack, TrackMetadata, YtMusicError};
use extractor::innertube::InnertubeClient;
use metadata::itunes::{search_itunes, is_duration_match};
use metadata::lrclib::fetch_lyrics;
use downloader::engine::DownloadEngine;
use library::scanner::LibraryScanner;
use auth::cookies::CookieManager;

uniffi::setup_scaffolding!("ytmusic_core");

static COOKIE_MANAGER: Lazy<CookieManager> = Lazy::new(CookieManager::new);

static HTTP_CLIENT: Lazy<reqwest::Client> = Lazy::new(|| {
    reqwest::Client::builder()
        .cookie_provider(COOKIE_MANAGER.jar.clone())
        .user_agent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
        .build()
        .unwrap_or_default()
});

static RUNTIME: Lazy<tokio::runtime::Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Fallo al inicializar Tokio Runtime en ytmusic-core")
});

#[uniffi::export]
pub fn ytm_get_version() -> String {
    "YouTube Music Pro (Rust Core v1.0.0)".to_string()
}

#[uniffi::export]
pub fn ytm_save_cookies(cookies_text: String) -> bool {
    let count = COOKIE_MANAGER.parse_netscape_cookies(&cookies_text);
    count > 0
}

#[uniffi::export]
pub fn ytm_save_cookie_header(cookie_header: String) -> u32 {
    COOKIE_MANAGER.parse_cookie_header(&cookie_header) as u32
}

#[uniffi::export]
pub fn ytm_has_session() -> bool {
    COOKIE_MANAGER.has_session()
}

#[uniffi::export]
pub fn ytm_search_music(query: String) -> Vec<TrackMetadata> {
    RUNTIME.block_on(async {
        let innertube = InnertubeClient::new(HTTP_CLIENT.clone());
        let mut results = innertube.search_music(&query).await;

        // Si iTunes tiene mejor carátula en alta definición (1200px), enriquecer los primeros resultados
        for track in results.iter_mut().take(5) {
            let itunes_matches = search_itunes(&HTTP_CLIENT, &format!("{} {}", track.artist, track.title)).await;
            if let Some(it) = itunes_matches.into_iter().next() {
                if is_duration_match(track.duration_sec, it.duration_sec, 4) {
                    if !it.cover_url.is_empty() {
                        track.cover_url = it.cover_url;
                    }
                    if track.album == "Single" && !it.album.is_empty() {
                        track.album = it.album;
                    }
                }
            }
        }

        results
    })
}

#[uniffi::export]
pub fn ytm_resolve_official_track(url_or_query: String) -> Option<TrackMetadata> {
    RUNTIME.block_on(async {
        let query_clean = if url_or_query.contains("youtube.com") || url_or_query.contains("youtu.be") {
            // Extraer ID o consultar info
            url_or_query.clone()
        } else {
            url_or_query.clone()
        };

        let innertube = InnertubeClient::new(HTTP_CLIENT.clone());
        let tracks = innertube.search_music(&query_clean).await;
        tracks.into_iter().next()
    })
}

#[uniffi::export]
pub fn ytm_fetch_lyrics(
    title: String,
    artist: String,
    album: String,
    duration_sec: u32,
) -> Option<LyricsData> {
    RUNTIME.block_on(async {
        fetch_lyrics(&HTTP_CLIENT, &title, &artist, &album, duration_sec).await
    })
}

#[uniffi::export]
pub fn ytm_scan_library(folder_path: String) -> Vec<LocalTrack> {
    LibraryScanner::scan_folder(&folder_path)
}

#[uniffi::export]
pub fn ytm_start_download(
    track: TrackMetadata,
    quality: String,
    dest_dir: String,
    callback: Box<dyn DownloadCallback>,
) -> Result<String, YtMusicError> {
    let cb: Arc<dyn DownloadCallback> = Arc::from(callback);

    RUNTIME.block_on(async move {
        let innertube = InnertubeClient::new(HTTP_CLIENT.clone());

        // 1. Obtener streams de audio
        let streams = innertube.get_audio_streams(&track.id).await
            .map_err(|e| YtMusicError::General { reason: e })?;

        // 2. Elegir stream según calidad solicitada
        let selected_stream = match quality.as_str() {
            "m4a" | "m4a_256" => {
                streams.iter()
                    .find(|s| s.codec == "m4a")
                    .or_else(|| streams.first())
                    .ok_or_else(|| YtMusicError::General { reason: "No se encontró stream de audio compatible".to_string() })?
            }
            _ => {
                // native opus por defecto
                streams.iter()
                    .find(|s| s.codec == "opus")
                    .or_else(|| streams.first())
                    .ok_or_else(|| YtMusicError::General { reason: "No se encontró stream de audio compatible".to_string() })?
            }
        };

        // 3. Ejecutar descarga y etiquetado
        let engine = DownloadEngine::new(HTTP_CLIENT.clone());
        engine.download_track(&track, selected_stream, &dest_dir, Some(cb)).await
            .map_err(|e| YtMusicError::General { reason: e })
    })
}
