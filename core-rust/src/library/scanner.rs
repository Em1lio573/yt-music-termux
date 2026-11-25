use std::path::Path;
use walkdir::WalkDir;
use crate::models::LocalTrack;
use crate::tagger::lofty_tagger::read_tags;

pub struct LibraryScanner;

impl LibraryScanner {
    pub fn scan_folder(folder_path: &str) -> Vec<LocalTrack> {
        let base = Path::new(folder_path);
        if !base.exists() || !base.is_dir() {
            return Vec::new();
        }

        let mut tracks = Vec::new();
        let valid_extensions = ["opus", "m4a", "mp3", "flac", "ogg", "aac"];

        for entry in WalkDir::new(base).into_iter().filter_map(|e| e.ok()) {
            let path = entry.path();
            if path.is_file() {
                if let Some(ext) = path.extension().and_then(|e| e.to_str()).map(|e| e.to_lowercase()) {
                    if valid_extensions.contains(&ext.as_str()) {
                        let size_bytes = entry.metadata().map(|m| m.len()).unwrap_or(0);
                        let (title, artist, album, duration_sec) = read_tags(path)
                            .unwrap_or_else(|_| {
                                let name = path.file_stem().and_then(|s| s.to_str()).unwrap_or("Unknown").to_string();
                                let parent_album = path.parent()
                                    .and_then(|p| p.file_name())
                                    .and_then(|s| s.to_str())
                                    .unwrap_or("Singles")
                                    .to_string();
                                let grandparent_artist = path.parent()
                                    .and_then(|p| p.parent())
                                    .and_then(|p| p.file_name())
                                    .and_then(|s| s.to_str())
                                    .unwrap_or("Unknown")
                                    .to_string();
                                (name, grandparent_artist, parent_album, 0)
                            });

                        // Detectar carátula en disco (misma carpeta o con el mismo nombre)
                        let cover_path = {
                            let mut found = None;
                            for img_ext in &["jpg", "jpeg", "png", "webp"] {
                                let candidate = path.with_extension(img_ext);
                                if candidate.exists() {
                                    found = Some(candidate.to_string_lossy().to_string());
                                    break;
                                }
                            }
                            if found.is_none() {
                                if let Some(parent) = path.parent() {
                                    for cover_name in &["cover.jpg", "cover.png", "folder.jpg", "cover.jpeg"] {
                                        let candidate = parent.join(cover_name);
                                        if candidate.exists() {
                                            found = Some(candidate.to_string_lossy().to_string());
                                            break;
                                        }
                                    }
                                }
                            }
                            found
                        };

                        tracks.push(LocalTrack {
                            file_path: path.to_string_lossy().to_string(),
                            title,
                            artist,
                            album,
                            duration_sec,
                            format: ext,
                            size_bytes,
                            cover_path,
                        });
                    }
                }
            }
        }

        // Ordenar alfabéticamente por artista y luego título
        tracks.sort_by(|a, b| {
            a.artist.to_lowercase().cmp(&b.artist.to_lowercase())
                .then(a.title.to_lowercase().cmp(&b.title.to_lowercase()))
        });

        tracks
    }
}
