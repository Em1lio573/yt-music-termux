use serde::Deserialize;
use serde_json::{json, Value};
use crate::models::{StreamInfo, TrackMetadata};
use regex::Regex;

pub const VISIONOS_UA: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";

pub struct InnertubeClient {
    client: reqwest::Client,
}

#[derive(Debug, Clone)]
pub struct SponsorBlockSegment {
    pub start: f32,
    pub end: f32,
    pub category: String,
}

impl InnertubeClient {
    pub fn new(client: reqwest::Client) -> Self {
        Self { client }
    }

    /// Busca canciones en YouTube Music mediante la API interna de WEB_REMIX
    pub async fn search_music(&self, query: &str) -> Vec<TrackMetadata> {
        let url = "https://music.youtube.com/youtubei/v1/search";
        let body = json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240101.01.00",
                    "hl": "es",
                    "gl": "US"
                }
            },
            "query": query,
            "params": "Eg-KAQwIABAAGAAgACgAMABqChAEEAMQCRAFEAo%3D"
        });

        let resp = match self.client.post(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
            .header("Referer", "https://music.youtube.com/")
            .header("Origin", "https://music.youtube.com")
            .json(&body)
            .send()
            .await 
        {
            Ok(r) => r,
            Err(_) => return self.search_youtube_fallback(query).await,
        };

        let val: Value = match resp.json().await {
            Ok(v) => v,
            Err(_) => return self.search_youtube_fallback(query).await,
        };

        let mut tracks = Vec::new();
        Self::parse_music_search_results(&val, &mut tracks);

        if tracks.is_empty() {
            return self.search_youtube_fallback(query).await;
        }

        tracks
    }

    /// Búsqueda estándar de YouTube con cliente WEB
    pub async fn search_youtube_fallback(&self, query: &str) -> Vec<TrackMetadata> {
        let url = "https://www.youtube.com/youtubei/v1/search";
        let body = json!({
            "context": {
                "client": {
                    "clientName": "WEB",
                    "clientVersion": "2.20240101.01.00",
                    "hl": "es",
                    "gl": "US"
                }
            },
            "query": format!("{} official audio", query)
        });

        let resp = match self.client.post(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
            .json(&body)
            .send()
            .await 
        {
            Ok(r) => r,
            Err(_) => return Vec::new(),
        };

        let val: Value = match resp.json().await {
            Ok(v) => v,
            Err(_) => return Vec::new(),
        };

        let mut tracks = Vec::new();
        Self::parse_youtube_search_results(&val, &mut tracks);
        tracks
    }

    /// Resuelve información de reproducción y streams de audio para un videoId usando VISIONOS (sin n-sig / antibot 403)
    pub async fn get_audio_streams(&self, video_id: &str) -> Result<Vec<StreamInfo>, String> {
        // 1. Obtener la página del video para inicializar cookies y extraer visitorData
        let watch_url = format!("https://www.youtube.com/watch?v={}", video_id);
        let watch_resp = self.client.get(&watch_url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
            .send()
            .await
            .map_err(|e| format!("Error cargando página de YouTube: {}", e))?;

        let html = watch_resp.text().await.unwrap_or_default();

        let visitor_re = Regex::new(r#""visitorData":"([^"]+)""#).unwrap();
        let visitor_id = visitor_re.captures(&html)
            .and_then(|c| c.get(1))
            .map(|m| m.as_str().to_string());

        // 2. Consultar el reproductor con cliente VISIONOS (cliente 101)
        let player_url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

        let body = json!({
            "context": {
                "client": {
                    "clientName": "VISIONOS",
                    "clientVersion": "1.02",
                    "deviceMake": "Apple",
                    "deviceModel": "RealityDevice17,1",
                    "userAgent": VISIONOS_UA,
                    "osName": "visionOS",
                    "osVersion": "26.5.23O471",
                    "hl": "en",
                    "timeZone": "UTC",
                    "utcOffsetMinutes": 0
                }
            },
            "videoId": video_id,
            "playbackContext": {
                "contentPlaybackContext": {
                    "html5Preference": "HTML5_PREF_WANTS"
                }
            },
            "contentCheckOk": true,
            "racyCheckOk": true
        });

        let mut req = self.client.post(player_url)
            .header("User-Agent", VISIONOS_UA)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", "101")
            .header("X-Youtube-Client-Version", "1.02")
            .header("Origin", "https://www.youtube.com");

        if let Some(ref vid) = visitor_id {
            req = req.header("X-Goog-Visitor-Id", vid);
        }

        let resp = req.json(&body).send().await
            .map_err(|e| format!("Error conectando a YouTube Player API: {}", e))?;

        let val: Value = resp.json().await
            .map_err(|e| format!("Error decodificando respuesta JSON: {}", e))?;

        let status = val["playabilityStatus"]["status"].as_str().unwrap_or("ERROR");
        if status != "OK" {
            let reason = val["playabilityStatus"]["reason"].as_str().unwrap_or("Vídeo no reproducible o bloqueado");
            return Err(format!("Estado de reproducción: {} ({})", status, reason));
        }

        let adaptive_formats = val["streamingData"]["adaptiveFormats"]
            .as_array()
            .ok_or_else(|| "No se encontraron formatos adaptativos de audio".to_string())?;

        let mut streams = Vec::new();

        for fmt in adaptive_formats {
            let mime = fmt["mimeType"].as_str().unwrap_or_default();
            if !mime.starts_with("audio/") {
                continue;
            }

            let itag = fmt["itag"].as_u64().unwrap_or(0) as u32;
            let bitrate_kbps = (fmt["bitrate"].as_u64().unwrap_or(0) / 1000) as u32;
            let content_length = fmt["contentLength"]
                .as_str()
                .and_then(|s| s.parse::<u64>().ok())
                .unwrap_or(0);

            let codec = if mime.contains("opus") {
                "opus".to_string()
            } else if mime.contains("mp4a") {
                "m4a".to_string()
            } else {
                "audio".to_string()
            };

            if let Some(stream_url) = fmt["url"].as_str() {
                streams.push(StreamInfo {
                    url: stream_url.to_string(),
                    codec,
                    bitrate_kbps,
                    itag,
                    content_length,
                });
            }
        }

        streams.sort_by(|a, b| b.bitrate_kbps.cmp(&a.bitrate_kbps));

        if streams.is_empty() {
            return Err("No se encontraron enlaces de audio directos descargables".to_string());
        }

        Ok(streams)
    }

    /// Consulta segmentos de diálogo o intro mediante SponsorBlock
    pub async fn fetch_sponsorblock(&self, video_id: &str) -> Vec<SponsorBlockSegment> {
        let url = format!(
            "https://sponsor.ajay.app/api/skipSegments?videoID={}&categories=[\"music_offtopic\"]",
            video_id
        );

        let resp = match self.client.get(&url).send().await {
            Ok(r) => r,
            Err(_) => return Vec::new(),
        };

        if !resp.status().is_success() {
            return Vec::new();
        }

        #[derive(Deserialize)]
        struct SbSegment {
            segment: [f32; 2],
            category: String,
        }

        let segments: Vec<SbSegment> = resp.json().await.unwrap_or_default();
        segments
            .into_iter()
            .map(|s| SponsorBlockSegment {
                start: s.segment[0],
                end: s.segment[1],
                category: s.category,
            })
            .collect()
    }

    fn parse_music_search_results(val: &Value, out: &mut Vec<TrackMetadata>) {
        if let Some(sections) = val["contents"]["tabbedSearchResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"].as_array() {
            for sec in sections {
                if let Some(items) = sec["musicShelfRenderer"]["contents"].as_array() {
                    for item in items {
                        let row = &item["musicResponsiveListItemRenderer"];
                        if let Some(track) = Self::parse_music_row(row) {
                            out.push(track);
                        }
                    }
                }
            }
        }
    }

    fn parse_music_row(row: &Value) -> Option<TrackMetadata> {
        let flex_cols = row["flexColumns"].as_array()?;
        let title = flex_cols.get(0)?["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"][0]["text"].as_str()?.to_string();
        
        let sub_runs = flex_cols.get(1)?["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"].as_array()?;

        // Extraer TODOS los artistas (principal + secundarios/colaboradores)
        let mut artists: Vec<String> = sub_runs.iter()
            .filter(|r| {
                r["navigationEndpoint"]["browseEndpoint"]["browseId"]
                    .as_str()
                    .map_or(false, |id| id.starts_with("UC"))
            })
            .filter_map(|r| r["text"].as_str())
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .collect();

        if artists.is_empty() {
            // Fallback: recolectar nombres antes del álbum o duración, omitiendo separadores y etiquetas de tipo
            let skip_labels = ["canción", "song", "video", "vídeo", "episodio", "episode"];
            let mut found_any = false;
            for r in sub_runs {
                let is_album = r["navigationEndpoint"]["browseEndpoint"]["browseId"]
                    .as_str()
                    .map_or(false, |id| id.starts_with("MPREb"));
                if is_album {
                    break;
                }
                let txt = r["text"].as_str().unwrap_or("").trim();
                let lower = txt.to_lowercase();
                if txt.is_empty() || txt == "," || txt == "&" || txt == "y" || skip_labels.contains(&lower.as_str()) {
                    continue;
                }
                if txt == "•" || txt == "·" {
                    if found_any {
                        break;
                    }
                    continue;
                }
                // Si parece duración (ej. "3:42") o reproducciones, detener
                if txt.contains(':') && txt.chars().all(|c| c.is_ascii_digit() || c == ':') {
                    break;
                }
                artists.push(txt.to_string());
                found_any = true;
            }
        }

        let artist = if artists.is_empty() {
            sub_runs.get(0).and_then(|r| r["text"].as_str()).unwrap_or("Unknown").to_string()
        } else {
            artists.join(", ")
        };

        let album = sub_runs.iter().find(|r| r["navigationEndpoint"]["browseEndpoint"]["browseId"].as_str().map_or(false, |id| id.starts_with("MPREb")))
            .and_then(|r| r["text"].as_str())
            .unwrap_or("Single")
            .to_string();

        let id = row["playlistItemData"]["videoId"].as_str()
            .or_else(|| flex_cols.get(0)?["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"][0]["navigationEndpoint"]["watchEndpoint"]["videoId"].as_str())?
            .to_string();

        let duration_str = sub_runs.last().and_then(|r| r["text"].as_str()).unwrap_or("0:00");
        let duration_sec = parse_duration_seconds(duration_str);

        let cover_url = row["thumbnail"]["musicThumbnailRenderer"]["thumbnail"]["thumbnails"].as_array()
            .and_then(|arr| arr.last())
            .and_then(|t| t["url"].as_str())
            .unwrap_or_default()
            .to_string();

        let is_explicit = row["badges"].as_array()
            .map_or(false, |b| b.iter().any(|badge| badge["musicInlineBadgeRenderer"]["accessibilityData"]["accessibilityData"]["label"].as_str().map_or(false, |l| l.contains("Explicit"))));

        Some(TrackMetadata {
            id,
            title: clean_title(&title),
            artist,
            album,
            duration_sec,
            cover_url,
            is_explicit,
            source: "ytmusic".to_string(),
        })
    }

    fn parse_youtube_search_results(val: &Value, out: &mut Vec<TrackMetadata>) {
        if let Some(contents) = val["contents"]["twoColumnSearchResultsRenderer"]["primaryContents"]["sectionListRenderer"]["contents"].as_array() {
            for sec in contents {
                if let Some(items) = sec["itemSectionRenderer"]["contents"].as_array() {
                    for item in items {
                        let v = &item["videoRenderer"];
                        if let Some(id) = v["videoId"].as_str() {
                            let title = v["title"]["runs"][0]["text"].as_str().unwrap_or("Unknown").to_string();
                            let artist = v["ownerText"]["runs"][0]["text"].as_str().unwrap_or("Unknown").to_string();
                            let duration_str = v["lengthText"]["simpleText"].as_str().unwrap_or("0:00");
                            let duration_sec = parse_duration_seconds(duration_str);
                            let cover_url = format!("https://i.ytimg.com/vi/{}/maxresdefault.jpg", id);

                            out.push(TrackMetadata {
                                id: id.to_string(),
                                title: clean_title(&title),
                                artist,
                                album: "Single".to_string(),
                                duration_sec,
                                cover_url,
                                is_explicit: false,
                                source: "youtube".to_string(),
                            });
                        }
                    }
                }
            }
        }
    }
}

pub fn clean_title(title: &str) -> String {
    let re = Regex::new(r"(?i)\s*[\(\[](?:Official\s*(?:Music\s*)?Video|Video\s*Oficial|Audio\s*Oficial|Audio|Lyric\s*Video|Letra|4K|HD|Remastered|Remasterizado)[\)\]]").unwrap();
    let cleaned = re.replace_all(title, "");
    cleaned.trim().to_string()
}

pub fn parse_duration_seconds(duration_str: &str) -> u32 {
    let parts: Vec<&str> = duration_str.split(':').collect();
    match parts.len() {
        2 => {
            let m: u32 = parts[0].parse().unwrap_or(0);
            let s: u32 = parts[1].parse().unwrap_or(0);
            m * 60 + s
        }
        3 => {
            let h: u32 = parts[0].parse().unwrap_or(0);
            let m: u32 = parts[1].parse().unwrap_or(0);
            let s: u32 = parts[2].parse().unwrap_or(0);
            h * 3600 + m * 60 + s
        }
        _ => 0,
    }
}
