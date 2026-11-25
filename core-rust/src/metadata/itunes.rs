use serde::Deserialize;
use crate::models::TrackMetadata;

#[derive(Deserialize, Debug)]
struct ITunesResponse {
    #[serde(default)]
    results: Vec<ITunesItem>,
}

#[derive(Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
struct ITunesItem {
    track_name: Option<String>,
    artist_name: Option<String>,
    collection_name: Option<String>,
    track_time_millis: Option<u64>,
    artwork_url100: Option<String>,
    track_id: Option<u64>,
    track_explicitness: Option<String>,
}

pub async fn search_itunes(client: &reqwest::Client, query: &str) -> Vec<TrackMetadata> {
    let url = format!(
        "https://itunes.apple.com/search?term={}&entity=song&limit=5",
        urlencoding(query)
    );

    let resp = match client.get(&url).send().await {
        Ok(r) => r,
        Err(_) => return Vec::new(),
    };

    let data: ITunesResponse = match resp.json().await {
        Ok(d) => d,
        Err(_) => return Vec::new(),
    };

    data.results
        .into_iter()
        .filter_map(|item| {
            let title = item.track_name?;
            let artist = item.artist_name.unwrap_or_else(|| "Unknown".to_string());
            let album = item.collection_name.unwrap_or_else(|| "Single".to_string());
            let duration_sec = item.track_time_millis.map(|ms| (ms / 1000) as u32).unwrap_or(0);
            
            // Reemplazar 100x100bb con 1200x1200bb para máxima resolución
            let cover_url = item
                .artwork_url100
                .map(|u| u.replace("100x100bb", "1200x1200bb"))
                .unwrap_or_default();

            let is_explicit = item.track_explicitness.map(|e| e == "explicit").unwrap_or(false);
            let id = item.track_id.map(|id| id.to_string()).unwrap_or_default();

            Some(TrackMetadata {
                id,
                title,
                artist,
                album,
                duration_sec,
                cover_url,
                is_explicit,
                source: "itunes".to_string(),
            })
        })
        .collect()
}

pub fn is_duration_match(youtube_duration_sec: u32, official_duration_sec: u32, tolerance_sec: u32) -> bool {
    if youtube_duration_sec == 0 || official_duration_sec == 0 {
        return false;
    }
    let diff = (youtube_duration_sec as i64 - official_duration_sec as i64).abs();
    diff <= tolerance_sec as i64
}

fn urlencoding(input: &str) -> String {
    url::form_urlencoded::byte_serialize(input.as_bytes()).collect()
}
