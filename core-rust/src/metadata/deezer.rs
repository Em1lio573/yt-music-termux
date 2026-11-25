use serde::Deserialize;
use crate::models::TrackMetadata;

#[derive(Deserialize, Debug)]
struct DeezerResponse {
    #[serde(default)]
    data: Vec<DeezerTrack>,
}

#[derive(Deserialize, Debug)]
struct DeezerTrack {
    id: Option<u64>,
    title: Option<String>,
    duration: Option<u32>,
    explicit_lyrics: Option<bool>,
    artist: Option<DeezerArtist>,
    album: Option<DeezerAlbum>,
}

#[derive(Deserialize, Debug)]
struct DeezerArtist {
    name: Option<String>,
}

#[derive(Deserialize, Debug)]
struct DeezerAlbum {
    title: Option<String>,
    cover_xl: Option<String>,
    cover_big: Option<String>,
}

pub async fn search_deezer(client: &reqwest::Client, query: &str) -> Vec<TrackMetadata> {
    let url = format!(
        "https://api.deezer.com/search?q={}&limit=5",
        urlencoding(query)
    );

    let resp = match client.get(&url).send().await {
        Ok(r) => r,
        Err(_) => return Vec::new(),
    };

    let data: DeezerResponse = match resp.json().await {
        Ok(d) => d,
        Err(_) => return Vec::new(),
    };

    data.data
        .into_iter()
        .filter_map(|item| {
            let title = item.title?;
            let artist = item.artist.and_then(|a| a.name).unwrap_or_else(|| "Unknown".to_string());
            let (album_title, cover_url) = if let Some(album) = item.album {
                let cov = album.cover_xl.or(album.cover_big).unwrap_or_default();
                (album.title.unwrap_or_else(|| "Single".to_string()), cov)
            } else {
                ("Single".to_string(), String::new())
            };
            let duration_sec = item.duration.unwrap_or(0);
            let is_explicit = item.explicit_lyrics.unwrap_or(false);
            let id = item.id.map(|id| id.to_string()).unwrap_or_default();

            Some(TrackMetadata {
                id,
                title,
                artist,
                album: album_title,
                duration_sec,
                cover_url,
                is_explicit,
                source: "deezer".to_string(),
            })
        })
        .collect()
}

fn urlencoding(input: &str) -> String {
    url::form_urlencoded::byte_serialize(input.as_bytes()).collect()
}
