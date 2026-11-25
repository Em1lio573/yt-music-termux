/**
 * API Client para YouTube Music Pro Móvil (Apple Design)
 * Soporta modo 100% local autónomo (youtubedl-android nativo en el APK)
 * y modo híbrido con servidor HTTP remoto (PC / Termux).
 */

import { Capacitor } from '@capacitor/core';

const STORAGE_KEY = 'ytm_backend_url';
const DEFAULT_URL = 'http://127.0.0.1:8000';
const LAN_FALLBACK_URL = 'http://192.168.100.35:8000';

export function isNativeApp() {
  return Capacitor.isNativePlatform() && Boolean(Capacitor.Plugins && Capacitor.Plugins.NativeDownloader);
}

export function getStoredServerUrl() {
  const url = localStorage.getItem(STORAGE_KEY);
  return (url && url.trim()) ? url.trim().replace(/\/+$/, '') : DEFAULT_URL;
}

export function setStoredServerUrl(url) {
  if (!url) {
    localStorage.removeItem(STORAGE_KEY);
    return DEFAULT_URL;
  }
  const clean = url.trim().replace(/\/+$/, '');
  localStorage.setItem(STORAGE_KEY, clean);
  return clean;
}

/**
 * Consulta el estado del motor (Nativo en el teléfono o Servidor HTTP)
 */
export async function getEngineStatus() {
  if (isNativeApp()) {
    try {
      const res = await Capacitor.Plugins.NativeDownloader.getStatus();
      return {
        isNative: true,
        online: true,
        initialized: res.initialized,
        version: res.version,
        musicDirectory: res.musicDirectory
      };
    } catch (e) {
      console.warn('Error consultando NativeDownloader:', e);
    }
  }

  // Fallback: Probar servidor HTTP local o remoto
  const serverRes = await autoDetectServer();
  return {
    isNative: false,
    online: serverRes.ok,
    url: serverRes.url,
    config: serverRes.config
  };
}

/**
 * Comprueba si un servidor HTTP específico responde
 */
export async function testServerConnection(url) {
  const target = (url || getStoredServerUrl()).replace(/\/+$/, '');
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 2500);
    const res = await fetch(`${target}/api/config`, {
      method: 'GET',
      headers: { 'Accept': 'application/json' },
      signal: controller.signal
    });
    clearTimeout(timeoutId);
    if (res.ok) {
      const config = await res.json();
      return { ok: true, url: target, config };
    }
  } catch (err) {
    // Falló
  }
  return { ok: false, url: target, config: null };
}

/**
 * Auto-detecta el servidor HTTP activo si no está en modo nativo
 */
export async function autoDetectServer() {
  const candidates = [
    getStoredServerUrl(),
    DEFAULT_URL,
    LAN_FALLBACK_URL
  ];
  const unique = [...new Set(candidates)];

  for (const cand of unique) {
    const res = await testServerConnection(cand);
    if (res.ok) {
      setStoredServerUrl(cand);
      return res;
    }
  }
  return { ok: false, url: getStoredServerUrl(), config: null };
}

/**
 * Formatear segundos a mm:ss
 */
export function formatDuration(sec) {
  if (!sec || isNaN(sec)) return '0:00';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}

/**
 * Búsqueda inteligente:
 * Consulta la API de iTunes directamente en el cliente (0 latencia, portadas oficiales 1200x1200px, 0 dependencias).
 */
export async function searchMusic(query) {
  const q = (query || '').trim();
  if (!q) return [];

  let results = [];

  // Intento 1: iTunes API directa en el cliente
  try {
    const itunesUrl = `https://itunes.apple.com/search?term=${encodeURIComponent(q)}&entity=song&limit=25`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 4000);
    const res = await fetch(itunesUrl, { signal: controller.signal });
    clearTimeout(timer);

    if (res.ok) {
      const data = await res.json();
      if (data.results && data.results.length > 0) {
        results = data.results.map(item => {
          const durationSec = (item.trackTimeMillis || 0) / 1000;
          const cover = (item.artworkUrl100 || '')
            .replace('100x100bb', '1200x1200bb')
            .replace('60x60bb', '1200x1200bb');
          return {
            id: String(item.trackId || Math.random()),
            title: item.trackName || 'Título desconocido',
            artist: item.artistName || 'Artista desconocido',
            album: item.collectionName || 'Single / Álbum',
            duration: durationSec,
            duration_str: formatDuration(durationSec),
            cover: cover || 'https://music.youtube.com/img/favicon_144.png',
            previewUrl: item.previewUrl || null,
            url: '',
            source: 'itunes'
          };
        });
      }
    }
  } catch (err) {
    console.warn('Fallo búsqueda directa de iTunes:', err);
  }

  // Intento 2: Búsqueda nativa directa en YouTube Music si iTunes dio pocos o ningún resultado
  if (results.length < 3 && isNativeApp() && Capacitor.Plugins.NativeDownloader?.searchMusic) {
    try {
      const nativeRes = await Capacitor.Plugins.NativeDownloader.searchMusic({ query: q });
      if (nativeRes && nativeRes.results && nativeRes.results.length > 0) {
        const existingTitles = new Set(results.map(r => normalizeText(r.title)));
        const newItems = nativeRes.results.filter(item => !existingTitles.has(normalizeText(item.title)));
        results = [...results, ...newItems];
      }
    } catch (e) {
      console.warn('Fallo búsqueda nativa de YouTube Music:', e);
    }
  }

  // Intento 3: Servidor backend (si estuviera disponible y no es nativo)
  if (results.length === 0 && !isNativeApp()) {
    try {
      const server = getStoredServerUrl();
      const res = await fetch(`${server}/api/search?q=${encodeURIComponent(q)}`);
      if (res.ok) {
        const data = await res.json();
        return (data.results || []).map((item, idx) => ({
          id: `backend_${idx}`,
          title: item.title,
          artist: item.artist,
          album: item.album || 'Single',
          duration: item.duration || 0,
          duration_str: item.duration_str || formatDuration(item.duration),
          cover: item.cover,
          url: item.url,
          source: 'backend'
        }));
      }
    } catch (err) {
      console.warn('Fallo búsqueda en backend:', err);
    }
  }

  return results;
}

/**
 * Enviar orden de descarga al motor nativo (o backend remoto)
 */
export async function requestDownload(song, quality = 'native') {
  if (isNativeApp()) {
    // 100% Local y autónomo en el teléfono con youtubedl-android
    const res = await Capacitor.Plugins.NativeDownloader.downloadTrack({
      url: song.url || '',
      artist: song.artist || '',
      title: song.title || '',
      album: song.album || '',
      cover: song.cover || '',
      quality: quality
    });
    return res;
  }

  // Modo servidor remoto (PC o Termux)
  const server = getStoredServerUrl();
  const payload = {
    url: song.url || `https://music.youtube.com/search?q=${encodeURIComponent(song.artist + ' ' + song.title)}`,
    artist: song.artist || '',
    title: song.title || '',
    album: song.album || '',
    duration: song.duration || 0,
    cover: song.cover || '',
    quality: quality
  };

  const res = await fetch(`${server}/api/download`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });

  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Error ${res.status}: ${text || 'No se pudo iniciar la descarga'}`);
  }

  return await res.json();
}

/**
 * Escucha eventos de progreso y salida de terminal en tiempo real
 */
export function subscribeDownloadEvents(onProgress, onTerminalLine) {
  if (isNativeApp()) {
    const pSub = Capacitor.Plugins.NativeDownloader.addListener('downloadProgress', (data) => {
      if (onProgress) onProgress(data);
    });
    const tSub = Capacitor.Plugins.NativeDownloader.addListener('terminalOutput', (data) => {
      if (onTerminalLine && data.line) onTerminalLine(data.line);
    });

    return () => {
      pSub.then(handle => handle.remove()).catch(() => {});
      tSub.then(handle => handle.remove()).catch(() => {});
    };
  }

  // Modo web: Server-Sent Events (SSE)
  const server = getStoredServerUrl();
  let sse = null;
  try {
    sse = new EventSource(`${server}/api/progress`);
    sse.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (onProgress) onProgress(data);
        if (onTerminalLine && (data.speed || data.title)) {
          onTerminalLine(`[yt-dlp] ${data.title || ''} | ${data.speed || ''} | ${data.eta || ''}`);
        }
      } catch (err) {
        console.error('Error parseando SSE data:', err);
      }
    };
  } catch (e) {
    console.warn('No se pudo conectar a SSE:', e);
  }

  return () => {
    if (sse) sse.close();
  };
}

/**
 * Obtener biblioteca de temas descargados
 */
export async function fetchLibrary() {
  if (isNativeApp()) {
    try {
      const res = await Capacitor.Plugins.NativeDownloader.getLibrary();
      return (res.tracks || []).map(t => {
        let coverUrl = null;
        if (t.coverPath) {
          coverUrl = `${Capacitor.convertFileSrc(t.coverPath)}?t=${t.lastModified || 0}`;
        } else if (t.cover) {
          coverUrl = t.cover;
        }
        return {
          ...t,
          cover: coverUrl
        };
      });
    } catch (e) {
      console.warn('Error leyendo biblioteca nativa:', e);
      return [];
    }
  }

  const server = getStoredServerUrl();
  const res = await fetch(`${server}/api/library`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return data.tracks || [];
}

/**
 * Eliminar pista de la biblioteca
 */
export async function deleteTrack(track) {
  if (isNativeApp()) {
    return await Capacitor.Plugins.NativeDownloader.deleteTrack({ path: track.path });
  }
  return false;
}

/**
 * Obtener historial
 */
export async function fetchHistory() {
  if (isNativeApp()) {
    const histStr = localStorage.getItem('ytm_native_history');
    return histStr ? JSON.parse(histStr) : [];
  }
  const server = getStoredServerUrl();
  const res = await fetch(`${server}/api/history`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return data.history || [];
}

/**
 * Guardar entrada en el historial
 */
export function addHistoryEntry(itemTitle) {
  if (isNativeApp()) {
    const histStr = localStorage.getItem('ytm_native_history');
    const list = histStr ? JSON.parse(histStr) : [];
    if (!list.includes(itemTitle)) {
      list.unshift(itemTitle);
      localStorage.setItem('ytm_native_history', JSON.stringify(list.slice(0, 100)));
    }
    return;
  }
}

/**
 * Eliminar elemento del historial
 */
export async function deleteHistory(id) {
  if (isNativeApp()) {
    const histStr = localStorage.getItem('ytm_native_history');
    let list = histStr ? JSON.parse(histStr) : [];
    list = list.filter(item => item !== id);
    localStorage.setItem('ytm_native_history', JSON.stringify(list));
    return true;
  }

  const server = getStoredServerUrl();
  const res = await fetch(`${server}/api/history/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  });
  return res.ok;
}

/**
 * Genera la URL para reproducir audio en <audio>
 */
export function getStreamUrl(filepath) {
  if (isNativeApp()) {
    // Convierte rutas locales de Android a la URL interna segura del WebView
    return Capacitor.convertFileSrc(filepath);
  }
  const server = getStoredServerUrl();
  return `${server}/api/stream?path=${encodeURIComponent(filepath)}`;
}

/**
 * Normaliza texto para comparaciones insensibles a mayúsculas, acentos y signos
 */
export function normalizeText(str) {
  if (!str) return '';
  return str
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Detecta si una canción de búsqueda ya está presente en la biblioteca del dispositivo
 */
export function findMatchingTrack(searchItem, libraryTracks) {
  if (!searchItem || !libraryTracks || !libraryTracks.length) return null;
  const normTitle = normalizeText(searchItem.title);
  const normArtist = normalizeText(searchItem.artist);

  return libraryTracks.find(t => {
    const trackTitle = normalizeText(t.title);
    const trackArtist = normalizeText(t.artist);
    
    // Si los títulos coinciden exacta o cercanamente
    const titleMatch = (
      normTitle && trackTitle && (
        normTitle === trackTitle || 
        normTitle.includes(trackTitle) || 
        trackTitle.includes(normTitle)
      )
    );
    
    if (!titleMatch) return false;

    // Si además el artista coincide o no está especificado
    if (!normArtist || !trackArtist) return true;
    return normArtist.includes(trackArtist) || trackArtist.includes(normArtist);
  }) || null;
}

/**
 * Solicita permisos nativos de audio en el dispositivo si es necesario
 */
export async function requestAudioPermissions() {
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.requestPermissions) {
    try {
      return await Capacitor.Plugins.NativeDownloader.requestPermissions();
    } catch (e) {
      console.warn('Permisos de audio:', e);
    }
  }
  return null;
}

/**
 * Verifica si la app posee permiso total de almacenamiento (MANAGE_EXTERNAL_STORAGE)
 */
export async function checkStoragePermission() {
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.checkStoragePermission) {
    try {
      const res = await Capacitor.Plugins.NativeDownloader.checkStoragePermission();
      return res.hasPermission !== false;
    } catch (e) {
      console.warn('Error verificando permisos de almacenamiento:', e);
    }
  }
  return true;
}

/**
 * Solicita acceso total a todos los archivos abriendo Ajustes de Android
 */
export async function requestStoragePermission() {
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.requestStoragePermission) {
    try {
      return await Capacitor.Plugins.NativeDownloader.requestStoragePermission();
    } catch (e) {
      console.warn('Error solicitando permisos de almacenamiento:', e);
    }
  }
}

/**
 * Extrae una URL limpia de un texto compartido (ej: "Escucha canción https://...")
 */
export function extractCleanUrl(text) {
  if (!text) return null;
  const match = text.match(/https?:\/\/[^\s]+/i);
  if (!match) return null;
  let url = match[0];
  url = url.replace(/([?&])list=RD[^&]*/g, '');
  url = url.replace(/([?&])si=[^&]*/g, '');
  url = url.replace(/([?&])feature=[^&]*/g, '');
  if (url.includes('?&')) url = url.replace('?&', '?');
  if (url.endsWith('?') || url.endsWith('&')) url = url.slice(0, -1);
  return url;
}

/**
 * Resuelve metadatos completos y oficiales de un enlace de YouTube o YouTube Music
 */
export async function resolveTrackFromUrl(url) {
  if (!url) return null;
  const clean = extractCleanUrl(url) || url.trim();

  // 1. Intento nativo en Android (sin restricciones CORS)
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.resolveUrlMetadata) {
    try {
      const res = await Capacitor.Plugins.NativeDownloader.resolveUrlMetadata({ url: clean });
      if (res && res.title) {
        return {
          id: res.id || String(Date.now()),
          title: res.title,
          artist: res.artist || 'YouTube Music',
          album: res.album || 'Sencillos',
          duration: res.duration || 0,
          duration_str: res.duration_str || formatDuration(res.duration),
          cover: res.cover || 'https://music.youtube.com/img/favicon_144.png',
          previewUrl: null,
          url: res.url || clean,
          source: 'url_resolved'
        };
      }
    } catch (e) {
      console.warn('resolveUrlMetadata nativo falló:', e);
    }
  }

  // 2. Intento oEmbed directo en cliente
  try {
    let oembedTarget = clean;
    if (oembedTarget.includes('music.youtube.com')) {
      oembedTarget = oembedTarget.replace('music.youtube.com', 'www.youtube.com');
    }
    const oembedUrl = `https://www.youtube.com/oembed?url=${encodeURIComponent(oembedTarget)}&format=json`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 4000);
    const resp = await fetch(oembedUrl, { signal: controller.signal });
    clearTimeout(timer);

    if (resp.ok) {
      const data = await resp.json();
      const rawTitle = data.title || '';
      const rawAuthor = data.author_name || '';
      const thumb = data.thumbnail_url || '';

      let cleanTitle = rawTitle
        .replace(/&amp;/g, '&')
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'")
        .replace(/\s*\|\s*(official|video|audio|music video|letra|lyric|en vivo|live|remastered).*$/i, '')
        .replace(/[\(\[\{]\s*(official\s*(music\s*)?video|video\s*oficial|audio\s*oficial|official\s*audio|lyric\s*video|visualizer|video\s*clip|en\s*vivo|live|4k\s*remaster|letra|remix|hd|4k|mv)[^\)\]\}]*[\)\]\}]/gi, '')
        .replace(/["״'”]+/g, '')
        .trim();

      let artist = rawAuthor.replace(/\s*-\s*Topic$/i, '').replace(/(vevo|official|channel)$/i, '').trim();
      let title = cleanTitle;

      const delimiters = [' - ', ' \u2013 ', ' \u2014 ', ' || ', ' | ', ' : ', ' • ', ' · '];
      for (const d of delimiters) {
        if (cleanTitle.includes(d)) {
          const parts = cleanTitle.split(d);
          if (parts[0]?.trim() && parts[1]?.trim()) {
            artist = parts[0].trim();
            title = parts.slice(1).join(d).trim();
            break;
          }
        }
      }

      let officialCover = null;
      let officialAlbum = 'Sencillos';
      let officialDuration = 0;

      try {
        const itunesUrl = `https://itunes.apple.com/search?term=${encodeURIComponent(`${artist} ${title}`)}&entity=song&limit=5`;
        const itRes = await fetch(itunesUrl);
        if (itRes.ok) {
          const itData = await itRes.json();
          if (itData.results && itData.results.length > 0) {
            const tWords = title.toLowerCase().replace(/[^a-z0-9áéíóúñ]/g, ' ').split(/\s+/).filter(w => w.length > 2);
            for (const cand of itData.results) {
              const candNorm = (cand.trackName || '').toLowerCase();
              if (tWords.length === 0 || tWords.some(w => candNorm.includes(w))) {
                title = cand.trackName || title;
                artist = cand.artistName || artist;
                officialAlbum = cand.collectionName || officialAlbum;
                officialCover = (cand.artworkUrl100 || '').replace('100x100bb', '1200x1200bb').replace('60x60bb', '1200x1200bb');
                officialDuration = (cand.trackTimeMillis || 0) / 1000;
                break;
              }
            }
          }
        }
      } catch (err) {}

      return {
        id: String(Date.now()),
        title: title,
        artist: artist,
        album: officialAlbum,
        duration: officialDuration,
        duration_str: formatDuration(officialDuration),
        cover: officialCover || thumb || 'https://music.youtube.com/img/favicon_144.png',
        previewUrl: null,
        url: clean,
        source: 'url_resolved'
      };
    }
  } catch (e) {
    console.warn('oEmbed directo falló:', e);
  }

  return null;
}

/**
 * Abre el inicio de sesión nativo de YouTube en un WebView seguro
 */
export async function openYouTubeLogin() {
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.openYouTubeLogin) {
    try {
      return await Capacitor.Plugins.NativeDownloader.openYouTubeLogin();
    } catch (e) {
      console.warn('Error en openYouTubeLogin:', e);
      throw e;
    }
  }
  return { completed: false };
}

/**
 * Consulta el estado actual de las cookies de YouTube en el dispositivo
 */
export async function getCookieStatus() {
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.getCookieStatus) {
    try {
      return await Capacitor.Plugins.NativeDownloader.getCookieStatus();
    } catch (e) {
      console.warn('Error en getCookieStatus:', e);
    }
  }
  return { hasCookies: false, size: 0, lastModified: 0 };
}

/**
 * Guarda texto sin formato en formato Netscape de cookies.txt
 */
export async function saveCookiesText(content) {
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.saveCookiesText) {
    try {
      return await Capacitor.Plugins.NativeDownloader.saveCookiesText({ content });
    } catch (e) {
      console.warn('Error en saveCookiesText:', e);
      throw e;
    }
  }
  return { success: false };
}

/**
 * Elimina las cookies guardadas y borra la sesión
 */
export async function clearCookies() {
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.clearCookies) {
    try {
      return await Capacitor.Plugins.NativeDownloader.clearCookies();
    } catch (e) {
      console.warn('Error en clearCookies:', e);
    }
  }
  return { cleared: true };
}

/**
 * Actualiza el binario de yt-dlp a la última versión disponible desde GitHub
 */
export async function updateEngine() {
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.updateEngine) {
    try {
      return await Capacitor.Plugins.NativeDownloader.updateEngine();
    } catch (e) {
      console.warn('Error actualizando motor yt-dlp:', e);
      throw e;
    }
  }
  return { success: false, version: 'No disponible' };
}

/**
 * Limpia términos de búsqueda para matching de alta precisión en iTunes y Deezer
 */
function cleanQueryTerm(term) {
  if (!term) return '';
  return term
    .replace(/\(.*?(official|video|audio|remix|feat|ft|letra|lyric|prod).*?\)/gi, '')
    .replace(/\[.*?(official|video|audio|remix|feat|ft|letra|lyric|prod).*?\]/gi, '')
    .replace(/^[0-9]+[\s._-]+/, '')
    .replace(/[/\\:*?"<>|]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Busca carátula oficial en Apple / iTunes (1200x1200px)
 */
async function searchItunesCover(artist, title, isAlbum = false) {
  const cleanA = cleanQueryTerm(artist);
  const cleanT = cleanQueryTerm(title);
  const query = `${cleanA} ${cleanT}`.trim();
  if (!query) return null;

  const entity = isAlbum ? 'album' : 'song';
  const url = `https://itunes.apple.com/search?term=${encodeURIComponent(query)}&entity=${entity}&limit=5`;
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 4000);
    const res = await fetch(url, { signal: controller.signal });
    clearTimeout(timer);

    if (res.ok) {
      const data = await res.json();
      if (data.results && data.results.length > 0) {
        const tWords = (title || '').toLowerCase().replace(/[^a-z0-9áéíóúñ]/g, ' ').split(/\s+/).filter(w => w.length > 2);
        for (const item of data.results) {
          const cand = isAlbum ? (item.collectionName || '') : (item.trackName || '');
          const candNorm = cand.toLowerCase();
          if (tWords.length === 0 || tWords.some(w => candNorm.includes(w))) {
            const art = item.artworkUrl100 || '';
            if (art) {
              return art.replace('100x100bb', '1200x1200bb').replace('60x60bb', '1200x1200bb');
            }
          }
        }
      }
    }
  } catch (e) {}
  return null;
}

/**
 * Busca carátula en Deezer API como respaldo de alta resolución (1000x1000px)
 */
async function searchDeezerCover(artist, title, isAlbum = false) {
  const cleanA = cleanQueryTerm(artist);
  const cleanT = cleanQueryTerm(title);
  const query = `${cleanA} ${cleanT}`.trim();
  if (!query) return null;

  const endpoint = isAlbum
    ? `https://api.deezer.com/search/album?q=${encodeURIComponent(query)}&limit=5`
    : `https://api.deezer.com/search?q=${encodeURIComponent(query)}&limit=5`;

  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 4000);
    const res = await fetch(endpoint, { signal: controller.signal });
    clearTimeout(timer);

    if (res.ok) {
      const data = await res.json();
      const items = data.data || [];
      if (items.length > 0) {
        const tWords = (title || '').toLowerCase().replace(/[^a-z0-9áéíóúñ]/g, ' ').split(/\s+/).filter(w => w.length > 2);
        for (const item of items) {
          const cand = isAlbum ? (item.title || '') : (item.title || '');
          const candNorm = cand.toLowerCase();
          if (tWords.length === 0 || tWords.some(w => candNorm.includes(w))) {
            if (isAlbum && item.cover_xl) {
              return item.cover_xl;
            } else if (item.album && item.album.cover_xl) {
              return item.album.cover_xl;
            } else if (item.cover_xl) {
              return item.cover_xl;
            }
          }
        }
      }
    }
  } catch (e) {}
  return null;
}

/**
 * Encuentra la mejor carátula cuadrada oficial combinando Apple y Deezer
 */
export async function findBestOfficialCover(artist, title, album, isAlbum = false) {
  // En app nativa, el método Java busca en iTunes y Deezer directamente sin restricciones de CORS
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.searchCover) {
    try {
      const res = await Capacitor.Plugins.NativeDownloader.searchCover({ artist, title, album, isAlbum });
      if (res && res.cover) {
        return res.cover;
      }
    } catch (e) {
      console.warn('searchCover nativo falló, recurriendo a web:', e);
    }
  }

  let cover = await searchItunesCover(artist, isAlbum ? album : title, isAlbum);
  if (cover) return cover;

  if (isAlbum && title) {
    cover = await searchItunesCover(artist, title, false);
    if (cover) return cover;
  }

  cover = await searchDeezerCover(artist, isAlbum ? album : title, isAlbum);
  if (cover) return cover;

  if (isAlbum && title) {
    cover = await searchDeezerCover(artist, title, false);
    if (cover) return cover;
  }

  return null;
}

/**
 * Busca múltiples candidatos de carátulas para el selector visual manual
 */
export async function apiSearchCoverCandidates(artist, title, album = '') {
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.searchCoverCandidates) {
    try {
      const res = await Capacitor.Plugins.NativeDownloader.searchCoverCandidates({ artist, title, album });
      if (res && res.candidates && res.candidates.length > 0) {
        return res.candidates;
      }
    } catch (e) {
      console.warn('searchCoverCandidates nativo fallo:', e);
    }
  }

  const list = [];
  try {
    const q = encodeURIComponent(`${artist} ${title}`.trim());
    const [itRes, dzRes] = await Promise.allSettled([
      fetch(`https://itunes.apple.com/search?term=${q}&entity=song&limit=4`),
      fetch(`https://api.deezer.com/search/track?q=${q}&limit=4`)
    ]);
    if (itRes.status === 'fulfilled' && itRes.value.ok) {
      const itData = await itRes.value.json();
      (itData.results || []).forEach(item => {
        if (item.artworkUrl100) {
          list.push({
            title: item.trackName || title,
            artist: item.artistName || artist,
            album: item.collectionName || '',
            cover: item.artworkUrl100.replace('/100x100bb.jpg', '/1200x1200bb.jpg').replace('/100x100bb.png', '/1200x1200bb.png'),
            source: 'Apple Music'
          });
        }
      });
    }
    if (dzRes.status === 'fulfilled' && dzRes.value.ok) {
      const dzData = await dzRes.value.json();
      (dzData.data || []).forEach(item => {
        const c = item.album?.cover_xl || item.cover_xl || item.album?.cover_big;
        if (c) {
          list.push({
            title: item.title || title,
            artist: item.artist?.name || artist,
            album: item.album?.title || '',
            cover: c,
            source: 'Deezer'
          });
        }
      });
    }
  } catch (e) {}
  return list;
}

/**
 * Obtiene sugerencias de búsqueda en tiempo real
 */
export async function apiGetSearchSuggestions(query) {
  if (!query || !query.trim()) return [];
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.getSearchSuggestions) {
    try {
      const res = await Capacitor.Plugins.NativeDownloader.getSearchSuggestions({ query: query.trim() });
      if (res && res.suggestions) {
        return res.suggestions;
      }
    } catch (e) {}
  }
  return [];
}

/**
 * Actualiza la carátula de una pista individual
 */
export async function apiUpdateTrackCover(path, artist, title, album, coverUrl) {
  if (!isNativeApp() || !Capacitor.Plugins.NativeDownloader?.updateTrackCover) {
    return { success: false };
  }
  return await Capacitor.Plugins.NativeDownloader.updateTrackCover({
    path,
    artist,
    title,
    album,
    coverUrl
  });
}

/**
 * Sincroniza todas las carátulas oficiales de la biblioteca local
 */
export async function syncLibraryCovers(tracks, onProgress, abortSignal) {
  if (!isNativeApp() || !tracks || tracks.length === 0) {
    return { updated: 0, total: 0 };
  }

  const albumGroups = new Map();
  for (const t of tracks) {
    if (!t.path) continue;
    const dir = t.path.substring(0, t.path.lastIndexOf('/'));
    if (!albumGroups.has(dir)) {
      albumGroups.set(dir, []);
    }
    albumGroups.get(dir).push(t);
  }

  const totalGroups = albumGroups.size;
  let currentGroup = 0;
  let updatedCount = 0;

  for (const [dir, groupTracks] of albumGroups.entries()) {
    if (abortSignal?.aborted) break;
    currentGroup++;

    const sample = groupTracks[0];
    const artist = sample.artist || '';
    const album = sample.album || sample.folder || '';
    const isSingleFolder = /sencillo|single|musica|biblioteca|download/i.test(album);

    onProgress?.({
      current: currentGroup,
      total: totalGroups,
      artist,
      item: isSingleFolder ? (sample.title || 'Sencillo') : album,
      percent: Math.round((currentGroup / totalGroups) * 100)
    });

    try {
      if (isSingleFolder) {
        for (const t of groupTracks) {
          if (abortSignal?.aborted) break;
          const coverUrl = await findBestOfficialCover(t.artist, t.title, t.album, false);
          if (coverUrl) {
            await Capacitor.Plugins.NativeDownloader.updateTrackCover({
              path: t.path,
              artist: t.artist,
              title: t.title,
              album: t.album,
              coverUrl
            });
            updatedCount++;
          }
        }
      } else {
        let coverUrl = await findBestOfficialCover(artist, sample.title, album, true);
        if (coverUrl) {
          await Capacitor.Plugins.NativeDownloader.updateTrackCover({
            path: dir,
            artist,
            album,
            coverUrl
          });
          updatedCount += groupTracks.length;
        } else {
          for (const t of groupTracks) {
            if (abortSignal?.aborted) break;
            const singleCover = await findBestOfficialCover(t.artist, t.title, t.album, false);
            if (singleCover) {
              await Capacitor.Plugins.NativeDownloader.updateTrackCover({
                path: t.path,
                artist: t.artist,
                title: t.title,
                album: t.album,
                coverUrl: singleCover
              });
              updatedCount++;
            }
          }
        }
      }
    } catch (e) {
      console.warn('Error sincronizando carpeta:', dir, e);
    }
  }

  return { updated: updatedCount, total: tracks.length };
}

/**
 * Parsea texto de letras en formato LRC a un array ordenado de líneas con timestamps en segundos
 */
export function parseLrcLyrics(lrcText) {
  if (!lrcText) return [];
  const lines = lrcText.split('\n');
  const result = [];
  const timeRegex = /\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]/g;

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    if (/^\[(ti|ar|al|by|offset|length|re|ve):/i.test(trimmed)) continue;

    let match;
    const timestamps = [];
    timeRegex.lastIndex = 0;
    while ((match = timeRegex.exec(trimmed)) !== null) {
      const minutes = parseInt(match[1], 10);
      const seconds = parseInt(match[2], 10);
      const millis = match[3] ? parseFloat('0.' + match[3]) : 0;
      timestamps.push(minutes * 60 + seconds + millis);
    }

    const text = trimmed.replace(timeRegex, '').trim();
    if (timestamps.length > 0) {
      for (const time of timestamps) {
        result.push({ time, text });
      }
    } else if (text) {
      result.push({ time: -1, text });
    }
  }

  result.sort((a, b) => a.time - b.time);
  return result;
}

/**
 * Obtiene las letras de una canción (desde archivo local .lrc o mediante LRCLIB)
 */
export async function fetchTrackLyrics(track) {
  if (!track) return null;

  // 1. Si es app nativa, consultar plugin (lee .lrc local o descarga en segundo plano)
  if (isNativeApp() && Capacitor.Plugins.NativeDownloader?.getLyrics) {
    try {
      const res = await Capacitor.Plugins.NativeDownloader.getLyrics({
        path: track.path || '',
        artist: track.artist || '',
        title: track.title || ''
      });
      if (res && (res.syncedLyrics || res.plainLyrics)) {
        return {
          syncedLyrics: res.syncedLyrics || '',
          plainLyrics: res.plainLyrics || '',
          parsed: parseLrcLyrics(res.syncedLyrics || res.plainLyrics || ''),
          source: res.source || 'native'
        };
      }
    } catch (e) {
      console.warn('getLyrics nativo falló:', e);
    }
  }

  // 2. Consulta directa a LRCLIB en cliente / web
  const artist = cleanQueryTerm(track.artist || '');
  const title = cleanQueryTerm(track.title || '');
  if (!title) return null;

  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    const getUrl = `https://lrclib.net/api/get?artist_name=${encodeURIComponent(artist)}&track_name=${encodeURIComponent(title)}`;
    const res = await fetch(getUrl, { signal: controller.signal });
    clearTimeout(timer);

    if (res.ok) {
      const data = await res.json();
      const synced = data.syncedLyrics || '';
      const plain = data.plainLyrics || '';
      if (synced || plain) {
        return {
          syncedLyrics: synced,
          plainLyrics: plain,
          parsed: parseLrcLyrics(synced || plain),
          source: 'lrclib'
        };
      }
    }
  } catch (e) {}

  // Intento de búsqueda general
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    const sUrl = `https://lrclib.net/api/search?q=${encodeURIComponent(`${artist} ${title}`)}`;
    const res = await fetch(sUrl, { signal: controller.signal });
    clearTimeout(timer);

    if (res.ok) {
      const list = await res.json();
      if (Array.isArray(list) && list.length > 0) {
        const first = list[0];
        const synced = first.syncedLyrics || '';
        const plain = first.plainLyrics || '';
        if (synced || plain) {
          return {
            syncedLyrics: synced,
            plainLyrics: plain,
            parsed: parseLrcLyrics(synced || plain),
            source: 'lrclib_search'
          };
        }
      }
    }
  } catch (e) {}

  return null;
}

/**
 * Obtiene la configuración de carpetas y escaneo de biblioteca
 */
export async function getLibraryConfig() {
  if (isNativeApp() && window.Capacitor?.Plugins?.NativeDownloader?.getLibraryConfig) {
    try {
      return await window.Capacitor.Plugins.NativeDownloader.getLibraryConfig();
    } catch (e) {
      console.warn('Error obteniendo config de biblioteca:', e);
    }
  }
  return {
    musicDirectory: '/storage/emulated/0/Music/Biblioteca',
    customFolders: [],
    scanMode: 'selected',
    includeDownloads: false,
    ignoreWhatsApp: true,
    ignoreRecordings: true,
    minDurationSec: 30,
    detectedFolders: [
      { path: '/storage/emulated/0/Music/Biblioteca', name: 'Biblioteca YT Music', desc: 'Carpeta organizada por Artista/Álbum', isDefault: true, audioCount: 0 }
    ]
  };
}

/**
 * Guarda la configuración de carpetas y filtros de biblioteca
 */
export async function setLibraryConfig(config) {
  if (isNativeApp() && window.Capacitor?.Plugins?.NativeDownloader?.setLibraryConfig) {
    try {
      return await window.Capacitor.Plugins.NativeDownloader.setLibraryConfig(config);
    } catch (e) {
      console.warn('Error guardando config de biblioteca:', e);
      throw e;
    }
  }
  return { success: true, musicDirectory: config.musicDirectory };
}

/**
 * Lista subdirectorios en una ruta específica para el explorador de carpetas
 */
export async function listDirectories(path = '') {
  if (isNativeApp() && window.Capacitor?.Plugins?.NativeDownloader?.listDirectories) {
    try {
      return await window.Capacitor.Plugins.NativeDownloader.listDirectories({ path });
    } catch (e) {
      console.warn('Error listando directorios:', e);
    }
  }
  return { currentPath: path || '/storage/emulated/0', parentPath: null, directories: [] };
}

