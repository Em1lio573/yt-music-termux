/**
 * API Client para YouTube Music Pro Móvil (Apple Design)
 * Gestiona conexiones al backend local (Termux / USB Debug / Wi-Fi LAN)
 * y búsqueda directa en alta fidelidad con fallback automático.
 */

const STORAGE_KEY = 'ytm_backend_url';
const DEFAULT_URL = 'http://127.0.0.1:8000';
const LAN_FALLBACK_URL = 'http://192.168.100.35:8000';

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
 * Comprueba si un servidor específico responde
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
 * Auto-detecta el servidor activo:
 * 1. URL guardada por el usuario
 * 2. 127.0.0.1:8000 (Termux o ADB reverse USB)
 * 3. 192.168.100.35:8000 (PC en la red local)
 */
export async function autoDetectServer() {
  const candidates = [
    getStoredServerUrl(),
    DEFAULT_URL,
    LAN_FALLBACK_URL
  ];
  // Eliminar duplicados
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
 * 1. Consulta la API de iTunes directamente en el cliente (0 latencia, portadas 1200x1200px, 0 dependencias).
 * 2. Si falla, consulta al backend en /api/search.
 */
export async function searchMusic(query) {
  const q = (query || '').trim();
  if (!q) return [];

  // Intento 1: iTunes API directa
  try {
    const itunesUrl = `https://itunes.apple.com/search?term=${encodeURIComponent(q)}&entity=song&limit=25`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 4000);
    const res = await fetch(itunesUrl, { signal: controller.signal });
    clearTimeout(timer);

    if (res.ok) {
      const data = await res.json();
      if (data.results && data.results.length > 0) {
        return data.results.map(item => {
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
            url: `https://music.youtube.com/search?q=${encodeURIComponent((item.artistName || '') + ' ' + (item.trackName || ''))}`,
            source: 'itunes'
          };
        });
      }
    }
  } catch (err) {
    console.warn('Fallo búsqueda directa de iTunes, intentando backend:', err);
  }

  // Intento 2: Backend /api/search
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

  return [];
}

/**
 * Enviar orden de descarga al backend
 */
export async function requestDownload(song, quality = 'native') {
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
 * Obtener biblioteca de temas descargados
 */
export async function fetchLibrary() {
  const server = getStoredServerUrl();
  const res = await fetch(`${server}/api/library`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return data.tracks || [];
}

/**
 * Obtener historial
 */
export async function fetchHistory() {
  const server = getStoredServerUrl();
  const res = await fetch(`${server}/api/history`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const data = await res.json();
  return data.history || [];
}

/**
 * Eliminar elemento del historial
 */
export async function deleteHistory(id) {
  const server = getStoredServerUrl();
  const res = await fetch(`${server}/api/history/${encodeURIComponent(id)}`, {
    method: 'DELETE'
  });
  return res.ok;
}

/**
 * Cargar configuración
 */
export async function fetchConfig() {
  const server = getStoredServerUrl();
  const res = await fetch(`${server}/api/config`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return await res.json();
}

/**
 * Guardar configuración
 */
export async function saveConfig(cfg) {
  const server = getStoredServerUrl();
  const res = await fetch(`${server}/api/config`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cfg)
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return await res.json();
}

/**
 * Conectar a Server-Sent Events (SSE) para progreso
 */
export function connectProgressSSE(onMessage, onError) {
  const server = getStoredServerUrl();
  let sse = null;
  try {
    sse = new EventSource(`${server}/api/progress`);
    sse.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (onMessage) onMessage(data);
      } catch (err) {
        console.error('Error parseando SSE data:', err);
      }
    };
    sse.onerror = (err) => {
      if (onError) onError(err);
    };
  } catch (e) {
    if (onError) onError(e);
  }
  return sse;
}

/**
 * Genera la URL para reproducir audio vía HTTP Range
 */
export function getStreamUrl(filepath) {
  const server = getStoredServerUrl();
  return `${server}/api/stream?path=${encodeURIComponent(filepath)}`;
}
