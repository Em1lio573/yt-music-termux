use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;
use tokio::fs::{self, File};
use tokio::io::AsyncWriteExt;
use futures_util::StreamExt;

use crate::models::{DownloadCallback, DownloadProgress, StreamInfo, TrackMetadata};
use crate::tagger::lofty_tagger::{apply_tags, AudioTags};
use crate::metadata::lrclib::fetch_lyrics;

pub struct DownloadEngine {
    client: reqwest::Client,
}

impl DownloadEngine {
    pub fn new(client: reqwest::Client) -> Self {
        Self { client }
    }

    pub async fn download_track(
        &self,
        track: &TrackMetadata,
        stream: &StreamInfo,
        dest_base_dir: &str,
        callback: Option<Arc<dyn DownloadCallback>>,
    ) -> Result<String, String> {
        let id = track.id.clone();
        
        let report_progress = |state: &str, percent: f32, speed: f32, eta: u32, dl: u64, total: u64, err: Option<String>, path: Option<String>| {
            if let Some(ref cb) = callback {
                cb.on_progress(DownloadProgress {
                    id: id.clone(),
                    state: state.to_string(),
                    progress_percent: percent,
                    speed_mb_s: speed,
                    eta_seconds: eta,
                    downloaded_bytes: dl,
                    total_bytes: total,
                    error_message: err,
                    saved_path: path,
                });
            }
        };

        report_progress("resolving", 0.0, 0.0, 0, 0, stream.content_length, None, None);

        // 1. Determinar rutas y extensiones
        let ext = match stream.codec.as_str() {
            "opus" => "opus",
            "m4a" => "m4a",
            "mp3" => "mp3",
            "flac" => "flac",
            _ => "opus",
        };

        let main_artist = extract_primary_artist(&track.artist);
        let artist_sanitized = sanitize_filename(&main_artist);
        let album_sanitized = if track.album.is_empty() || track.album == "Single" {
            "Singles".to_string()
        } else {
            sanitize_filename(&track.album)
        };
        let title_sanitized = sanitize_filename(&track.title);

        let biblioteca_dir = PathBuf::from(dest_base_dir).join("Biblioteca");
        let final_dir = biblioteca_dir
            .join(&artist_sanitized)
            .join(&album_sanitized);

        fs::create_dir_all(&final_dir).await
            .map_err(|e| format!("No se pudo crear carpeta de destino: {}", e))?;

        let final_file_path = final_dir.join(format!("{}.{}", title_sanitized, ext));
        let part_file_path = final_dir.join(format!("{}.{}.part", title_sanitized, ext));

        // 1.5 Detectar duplicados existentes (en el mismo o en otro formato dentro de Biblioteca/<Artista>)
        let existing_dupes = find_existing_duplicates(&biblioteca_dir.join(&artist_sanitized), &title_sanitized);
        if let Some(same_fmt) = existing_dupes.iter().find(|p| {
            p.extension().and_then(|e| e.to_str()).map(|e| e.eq_ignore_ascii_case(ext)).unwrap_or(false)
        }) {
            let existing_str = same_fmt.to_string_lossy().to_string();
            let existing_size = std::fs::metadata(same_fmt).map(|m| m.len()).unwrap_or(0);
            report_progress("completed", 100.0, 0.0, 0, existing_size, existing_size, None, Some(existing_str.clone()));
            return Ok(existing_str);
        }

        // 2. Descargar stream de audio con optimización de rangos (Chunking anti-throttling)
        report_progress("downloading", 0.0, 0.0, 0, 0, stream.content_length, None, None);

        let mut file = File::create(&part_file_path).await
            .map_err(|e| format!("No se pudo crear archivo temporal .part: {}", e))?;

        let mut downloaded: u64 = 0;
        let mut total_bytes = stream.content_length;
        let start_time = Instant::now();
        let chunk_size: u64 = 512 * 1024; // 512 KB chunks permiten velocidad de 10+ MB/s sin estrangulamiento
        let mut used_range = false;

        if total_bytes > 0 {
            let mut range_ok = true;
            while downloaded < total_bytes {
                let end = (downloaded + chunk_size - 1).min(total_bytes - 1);
                let range_header = format!("bytes={}-{}", downloaded, end);

                let resp = match self.client.get(&stream.url)
                    .header("User-Agent", crate::extractor::innertube::VISIONOS_UA)
                    .header("Range", range_header)
                    .send().await
                {
                    Ok(r) if r.status().is_success() => r,
                    _ => {
                        range_ok = false;
                        break;
                    }
                };

                let chunk_bytes = match resp.bytes().await {
                    Ok(b) => b,
                    Err(e) => {
                        let err_msg = format!("Error recibiendo chunk de datos: {}", e);
                        report_progress("error", 0.0, 0.0, 0, downloaded, total_bytes, Some(err_msg.clone()), None);
                        return Err(err_msg);
                    }
                };

                if chunk_bytes.is_empty() {
                    break;
                }

                file.write_all(&chunk_bytes).await
                    .map_err(|e| format!("Error escribiendo en archivo .part: {}", e))?;

                downloaded += chunk_bytes.len() as u64;
                used_range = true;

                let elapsed_secs = start_time.elapsed().as_secs_f32().max(0.001);
                let speed_mb_s = (downloaded as f32 / (1024.0 * 1024.0)) / elapsed_secs;
                let percent = (downloaded as f32 / total_bytes as f32) * 100.0;
                let eta_seconds = if speed_mb_s > 0.0 && total_bytes > downloaded {
                    (((total_bytes - downloaded) as f32 / (1024.0 * 1024.0)) / speed_mb_s) as u32
                } else {
                    0
                };

                report_progress("downloading", percent, speed_mb_s, eta_seconds, downloaded, total_bytes, None, None);
            }

            if !range_ok && downloaded == 0 {
                used_range = false;
            }
        }

        // Fallback a streaming continuo si el servidor no acepta Range
        if !used_range || downloaded < total_bytes {
            if downloaded == 0 {
                let resp = self.client.get(&stream.url)
                    .header("User-Agent", crate::extractor::innertube::VISIONOS_UA)
                    .send().await
                    .map_err(|e| {
                        let err_msg = format!("Fallo al solicitar stream: {}", e);
                        report_progress("error", 0.0, 0.0, 0, 0, 0, Some(err_msg.clone()), None);
                        err_msg
                    })?;

                total_bytes = resp.content_length().unwrap_or(total_bytes);
                let mut byte_stream = resp.bytes_stream();
                let mut last_update = Instant::now();

                while let Some(chunk_result) = byte_stream.next().await {
                    let chunk = chunk_result.map_err(|e| {
                        let err_msg = format!("Error durante descarga de chunks: {}", e);
                        report_progress("error", 0.0, 0.0, 0, downloaded, total_bytes, Some(err_msg.clone()), None);
                        err_msg
                    })?;

                    file.write_all(&chunk).await
                        .map_err(|e| format!("Error escribiendo en archivo .part: {}", e))?;

                    downloaded += chunk.len() as u64;

                    if last_update.elapsed().as_millis() >= 150 || downloaded == total_bytes {
                        let elapsed_secs = start_time.elapsed().as_secs_f32().max(0.001);
                        let speed_mb_s = (downloaded as f32 / (1024.0 * 1024.0)) / elapsed_secs;
                        let percent = if total_bytes > 0 {
                            (downloaded as f32 / total_bytes as f32) * 100.0
                        } else {
                            50.0
                        };
                        let eta_seconds = if speed_mb_s > 0.0 && total_bytes > downloaded {
                            (((total_bytes - downloaded) as f32 / (1024.0 * 1024.0)) / speed_mb_s) as u32
                        } else {
                            0
                        };

                        report_progress("downloading", percent, speed_mb_s, eta_seconds, downloaded, total_bytes, None, None);
                        last_update = Instant::now();
                    }
                }
            }
        }

        file.flush().await.map_err(|e| format!("Error en flush: {}", e))?;
        drop(file);

        // 3. Descargar carátula oficial en memoria para incrustar
        report_progress("tagging", 95.0, 0.0, 0, downloaded, total_bytes, None, None);

        let cover_bytes = if !track.cover_url.is_empty() {
            match self.client.get(&track.cover_url).send().await {
                Ok(r) if r.status().is_success() => {
                    r.bytes().await.ok().map(|b| b.to_vec())
                }
                _ => None,
            }
        } else {
            None
        };

        // Nota: NO guardamos archivos .jpg sueltos en /Music/ para evitar que Android los muestre en la Galería.
        // La carátula queda incrustada directamente dentro del archivo de audio con Lofty.

        // 4. Buscar letras sincronizadas
        let lyrics_data = fetch_lyrics(&self.client, &track.title, &main_artist, &track.album, track.duration_sec).await;

        let plain_lyrics = lyrics_data.as_ref().map(|l| l.plain_text.as_str());

        // Guardar archivo .lrc sincronizado si existe
        if let Some(ref l) = lyrics_data {
            if l.is_synced && !l.lines.is_empty() {
                let lrc_path = final_dir.join(format!("{}.lrc", title_sanitized));
                let mut lrc_content = String::new();
                for line in &l.lines {
                    let mins = line.timestamp_ms / 60_000;
                    let secs = (line.timestamp_ms % 60_000) / 1_000;
                    let ms = (line.timestamp_ms % 1_000) / 10;
                    lrc_content.push_str(&format!("[{:02}:{:02}.{:02}]{}\n", mins, secs, ms, line.text));
                }
                let _ = fs::write(&lrc_path, lrc_content).await;
            }
        }

        // 5. Incrustar metadatos con Lofty (artist = principal + secundarios; album_artist = principal)
        let audio_tags = AudioTags {
            title: &track.title,
            artist: &track.artist,
            album: &track.album,
            album_artist: Some(&main_artist),
            year: None,
            lyrics: plain_lyrics,
            cover_bytes: cover_bytes.as_deref(),
            cover_mime: Some("image/jpeg"),
        };

        if let Err(e) = apply_tags(&part_file_path, &audio_tags) {
            eprintln!("Advertencia etiquetando archivo {:?}: {}", part_file_path, e);
        }

        // 6. Renombrar de .part al destino final
        fs::rename(&part_file_path, &final_file_path).await
            .map_err(|e| format!("Error renombrando archivo final: {}", e))?;

        // 7. Si existía la misma canción en otro formato, eliminar la copia anterior para evitar duplicados
        for old_dupe in &existing_dupes {
            if old_dupe != &final_file_path {
                let _ = fs::remove_file(old_dupe).await;
            }
        }

        let final_path_str = final_file_path.to_string_lossy().to_string();

        report_progress("completed", 100.0, 0.0, 0, total_bytes, total_bytes, None, Some(final_path_str.clone()));

        Ok(final_path_str)
    }
}

fn normalize_match_key(s: &str) -> String {
    s.chars()
        .filter(|c| c.is_alphanumeric())
        .flat_map(|c| c.to_lowercase())
        .collect()
}

fn find_existing_duplicates(artist_dir: &std::path::Path, title_sanitized: &str) -> Vec<PathBuf> {
    let mut found = Vec::new();
    if !artist_dir.exists() {
        return found;
    }
    let target_key = normalize_match_key(title_sanitized);
    if target_key.is_empty() {
        return found;
    }
    let audio_exts = ["opus", "m4a", "mp3", "flac", "ogg", "aac"];
    for entry in walkdir::WalkDir::new(artist_dir).into_iter().filter_map(|e| e.ok()) {
        let p = entry.path();
        if p.is_file() {
            if let Some(ext) = p.extension().and_then(|e| e.to_str()).map(|e| e.to_lowercase()) {
                if audio_exts.contains(&ext.as_str()) {
                    if let Some(stem) = p.file_stem().and_then(|s| s.to_str()) {
                        if normalize_match_key(stem) == target_key {
                            found.push(p.to_path_buf());
                        }
                    }
                }
            }
        }
    }
    found
}

pub fn extract_primary_artist(artists: &str) -> String {
    let lower = artists.to_lowercase();
    let mut split_idx = artists.len();
    for sep in [",", " & ", " feat. ", " ft. ", " featuring ", " x ", " y "] {
        if let Some(idx) = lower.find(sep) {
            if idx > 0 && idx < split_idx {
                split_idx = idx;
            }
        }
    }
    let primary = artists[..split_idx].trim();
    if primary.is_empty() {
        "Unknown".to_string()
    } else {
        primary.to_string()
    }
}

pub fn sanitize_filename(name: &str) -> String {
    let invalid_chars = ['/', '\\', '?', '%', '*', ':', '|', '"', '<', '>', '.', '\0'];
    let mut sanitized: String = name
        .chars()
        .map(|c| if invalid_chars.contains(&c) { '_' } else { c })
        .collect();
    sanitized = sanitized.trim().to_string();
    if sanitized.is_empty() {
        "Unknown".to_string()
    } else {
        sanitized
    }
}
