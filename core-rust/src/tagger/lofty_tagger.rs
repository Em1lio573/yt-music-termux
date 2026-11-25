use lofty::prelude::*;
use lofty::probe::Probe;
use lofty::picture::{Picture, PictureType, MimeType};
use lofty::tag::{ItemKey, Tag};
use lofty::config::WriteOptions;
use std::path::Path;

pub struct AudioTags<'a> {
    pub title: &'a str,
    pub artist: &'a str,
    pub album: &'a str,
    pub album_artist: Option<&'a str>,
    pub year: Option<u32>,
    pub lyrics: Option<&'a str>,
    pub cover_bytes: Option<&'a [u8]>,
    pub cover_mime: Option<&'a str>,
}

pub fn apply_tags(file_path: &Path, tags: &AudioTags) -> Result<(), String> {
    let probe = Probe::open(file_path)
        .map_err(|e| format!("Error abriendo archivo de audio {:?}: {}", file_path, e))?;
    let probe = probe.guess_file_type()
        .map_err(|e| format!("Error identificando formato de audio {:?}: {}", file_path, e))?;
    let mut tagged_file = probe.read()
        .map_err(|e| format!("Error leyendo metadatos de {:?}: {}", file_path, e))?;

    // Obtener la etiqueta primaria o crear una adecuada para el formato
    let tag = match tagged_file.primary_tag_mut() {
        Some(primary) => primary,
        None => {
            if let Some(first) = tagged_file.first_tag_mut() {
                first
            } else {
                let tag_type = tagged_file.primary_tag_type();
                tagged_file.insert_tag(Tag::new(tag_type));
                tagged_file.primary_tag_mut().unwrap()
            }
        }
    };

    tag.set_title(tags.title.to_string());
    tag.set_artist(tags.artist.to_string());
    tag.set_album(tags.album.to_string());

    if let Some(aa) = tags.album_artist {
        tag.insert_text(ItemKey::AlbumArtist, aa.to_string());
    }

    if let Some(year) = tags.year {
        tag.insert_text(ItemKey::Year, year.to_string());
    }

    if let Some(lyrics) = tags.lyrics {
        tag.insert_text(ItemKey::Lyrics, lyrics.to_string());
    }

    if let Some(cover) = tags.cover_bytes {
        let mime = match tags.cover_mime {
            Some("image/png") => MimeType::Png,
            _ => MimeType::Jpeg,
        };
        let pic = Picture::unchecked(cover.to_vec())
            .pic_type(PictureType::CoverFront)
            .mime_type(mime)
            .build();

        // Limpiar carátulas anteriores para evitar duplicados
        tag.remove_picture_type(PictureType::CoverFront);
        tag.push_picture(pic);
    }

    tag.save_to_path(file_path, WriteOptions::default())
        .map_err(|e| format!("Error guardando tags en {:?}: {}", file_path, e))?;

    Ok(())
}

pub fn read_tags(file_path: &Path) -> Result<(String, String, String, u32), String> {
    let tagged_file = Probe::open(file_path)
        .map_err(|e| format!("Error al abrir {:?}: {}", file_path, e))?
        .read()
        .map_err(|e| format!("Error al leer {:?}: {}", file_path, e))?;

    let tag = tagged_file.primary_tag().or_else(|| tagged_file.first_tag());
    let duration = tagged_file.properties().duration().as_secs() as u32;

    if let Some(t) = tag {
        let title = t.title().as_deref().unwrap_or("Unknown").to_string();
        let artist = t.artist().as_deref().unwrap_or("Unknown").to_string();
        let album = t.album().as_deref().unwrap_or("Single").to_string();
        Ok((title, artist, album, duration))
    } else {
        let file_stem = file_path
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("Unknown")
            .to_string();
        Ok((file_stem, "Unknown".to_string(), "Single".to_string(), duration))
    }
}
