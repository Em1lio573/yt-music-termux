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

  // Intento 2: Backend /api/search (si estuviera disponible)
  if (!isNativeApp()) {
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

  return [];
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
      return res.tracks || [];
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
