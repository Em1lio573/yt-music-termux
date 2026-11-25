use serde::{Deserialize, Serialize};

#[derive(uniffi::Record, Debug, Clone, Serialize, Deserialize)]
pub struct TrackMetadata {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_sec: u32,
    pub cover_url: String,
    pub is_explicit: bool,
    pub source: String,
}

#[derive(uniffi::Record, Debug, Clone, Serialize, Deserialize)]
pub struct StreamInfo {
    pub url: String,
    pub codec: String,
    pub bitrate_kbps: u32,
    pub itag: u32,
    pub content_length: u64,
}

#[derive(uniffi::Record, Debug, Clone, Serialize, Deserialize)]
pub struct DownloadProgress {
    pub id: String,
    pub state: String, // "resolving", "downloading", "tagging", "completed", "error"
    pub progress_percent: f32,
    pub speed_mb_s: f32,
    pub eta_seconds: u32,
    pub downloaded_bytes: u64,
    pub total_bytes: u64,
    pub error_message: Option<String>,
    pub saved_path: Option<String>,
}

#[derive(uniffi::Record, Debug, Clone, Serialize, Deserialize)]
pub struct LyricsLine {
    pub timestamp_ms: u32,
    pub text: String,
}

#[derive(uniffi::Record, Debug, Clone, Serialize, Deserialize)]
pub struct LyricsData {
    pub is_synced: bool,
    pub plain_text: String,
    pub lines: Vec<LyricsLine>,
}

#[derive(uniffi::Record, Debug, Clone, Serialize, Deserialize)]
pub struct LocalTrack {
    pub file_path: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_sec: u32,
    pub format: String,
    pub size_bytes: u64,
    pub cover_path: Option<String>,
}

#[derive(uniffi::Error, thiserror::Error, Debug)]
pub enum YtMusicError {
    #[error("Error: {reason}")]
    General { reason: String },
}

impl From<String> for YtMusicError {
    fn from(reason: String) -> Self {
        Self::General { reason }
    }
}

impl From<&str> for YtMusicError {
    fn from(s: &str) -> Self {
        Self::General { reason: s.to_string() }
    }
}

#[uniffi::export(callback_interface)]
pub trait DownloadCallback: Send + Sync {
    fn on_progress(&self, progress: DownloadProgress);
}
