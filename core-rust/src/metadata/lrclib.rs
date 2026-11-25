use regex::Regex;
use serde::Deserialize;
use crate::models::{LyricsData, LyricsLine};

#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
struct LrclibResponse {
    plain_lyrics: Option<String>,
    synced_lyrics: Option<String>,
}

pub async fn fetch_lyrics(
    client: &reqwest::Client,
    track: &str,
    artist: &str,
    album: &str,
    duration_sec: u32,
) -> Option<LyricsData> {
    // 1. Intento con /api/get exacto
    let mut get_url = format!(
        "https://lrclib.net/api/get?track_name={}&artist_name={}",
        urlencoding(track),
        urlencoding(artist)
    );
    if !album.is_empty() && album != "Single" {
        get_url.push_str(&format!("&album_name={}", urlencoding(album)));
    }
    if duration_sec > 0 {
        get_url.push_str(&format!("&duration={}", duration_sec));
    }

    if let Ok(resp) = client.get(&get_url).send().await {
        if resp.status().is_success() {
            if let Ok(data) = resp.json::<LrclibResponse>().await {
                if let Some(parsed) = process_lrclib_response(data) {
                    return Some(parsed);
                }
            }
        }
    }

    // 2. Fallback con /api/search
    let search_url = format!(
        "https://lrclib.net/api/search?q={}+{}",
        urlencoding(artist),
        urlencoding(track)
    );
    if let Ok(resp) = client.get(&search_url).send().await {
        if resp.status().is_success() {
            if let Ok(items) = resp.json::<Vec<LrclibResponse>>().await {
                for item in items {
                    if let Some(parsed) = process_lrclib_response(item) {
                        return Some(parsed);
                    }
                }
            }
        }
    }

    None
}

fn process_lrclib_response(data: LrclibResponse) -> Option<LyricsData> {
    if let Some(synced) = data.synced_lyrics {
        let lines = parse_lrc(&synced);
        if !lines.is_empty() {
            return Some(LyricsData {
                is_synced: true,
                plain_text: data.plain_lyrics.unwrap_or_else(|| synced.clone()),
                lines,
            });
        }
    }

    if let Some(plain) = data.plain_lyrics {
        if !plain.trim().is_empty() {
            return Some(LyricsData {
                is_synced: false,
                plain_text: plain,
                lines: Vec::new(),
            });
        }
    }

    None
}

pub fn parse_lrc(lrc_content: &str) -> Vec<LyricsLine> {
    // Formato estándar: [mm:ss.xx] o [mm:ss.xxx]
    let re = Regex::new(r"\[(\d{1,2}):(\d{2})(?:\.(\d{2,3}))?\](.*)").unwrap();
    let mut lines = Vec::new();

    for line in lrc_content.lines() {
        let trimmed = line.trim();
        if let Some(caps) = re.captures(trimmed) {
            let mins: u32 = caps[1].parse().unwrap_or(0);
            let secs: u32 = caps[2].parse().unwrap_or(0);
            let ms: u32 = caps.get(3).map_or(0, |m| {
                let frac = m.as_str();
                if frac.len() == 2 {
                    frac.parse::<u32>().unwrap_or(0) * 10
                } else {
                    frac.parse::<u32>().unwrap_or(0)
                }
            });

            let timestamp_ms = mins * 60_000 + secs * 1_000 + ms;
            let text = caps[4].trim().to_string();

            lines.push(LyricsLine { timestamp_ms, text });
        }
    }

    lines.sort_by_key(|l| l.timestamp_ms);
    lines
}

fn urlencoding(input: &str) -> String {
    url::form_urlencoded::byte_serialize(input.as_bytes()).collect()
}
