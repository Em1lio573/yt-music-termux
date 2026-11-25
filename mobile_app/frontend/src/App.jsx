import React, { useState, useEffect, useRef, useMemo } from 'react';
import { 
  Search, Download, Music, Settings, History, Check, Play, Pause, 
  RefreshCw, Folder, Trash2, ShieldCheck, Sparkles, Sliders, ExternalLink,
  Volume2, AlertCircle, ArrowDownCircle, HardDrive, Radio, Terminal as TerminalIcon,
  ChevronDown, ChevronUp, Cpu, Smartphone, Disc, Filter, CheckCircle2,
  UserCheck, FileText, X, Lock, Mic2, RotateCcw, RotateCw, SkipBack, SkipForward,
  ClipboardPaste, Flame, SlidersHorizontal, Clock, Heart, MoreVertical, ListMusic, ListPlus,
  WifiOff, Wifi
} from 'lucide-react';
import {
  isNativeApp,
  getEngineStatus,
  getStoredServerUrl,
  setStoredServerUrl,
  testServerConnection,
  formatDuration,
  searchMusic,
  requestDownload,
  subscribeDownloadEvents,
  fetchLibrary as apiFetchLibrary,
  deleteTrack as apiDeleteTrack,
  fetchHistory as apiFetchHistory,
  addHistoryEntry,
  deleteHistory as apiDeleteHistory,
  getStreamUrl,
  findMatchingTrack,
  requestAudioPermissions,
  checkStoragePermission,
  requestStoragePermission,
  extractCleanUrl,
  openYouTubeLogin,
  getCookieStatus,
  saveCookiesText,
  clearCookies,
  updateEngine,
  syncLibraryCovers,
  resolveTrackFromUrl,
  fetchTrackLyrics,
  parseLrcLyrics,
  apiGetSearchSuggestions
} from './apiClient';
import NowPlayingSheet from './components/NowPlayingSheet';
import MiniProgressBar from './components/MiniProgressBar';
import CoverPickerModal from './components/CoverPickerModal';
import PlaylistModal from './components/PlaylistModal';
import FolderPickerModal from './components/FolderPickerModal';
import DownloadQueueModal from './components/DownloadQueueModal';
import { audioDsp } from './audioDsp';

const CALIDADES = [
  { id: 'native', nombre: 'Opus Nativo', badge: '~160 kbps VBR', desc: 'Máster oficial sin recodificar (Máxima fidelidad real de YouTube Music)', recommended: true },
  { id: 'm4a', nombre: 'M4A / AAC', badge: '~128 kbps', desc: 'Nativo sin recodificar (Universal Apple, Android y estéreos modernos)' },
  { id: 'm4a_256', nombre: 'M4A Premium', badge: '256 kbps', desc: 'Audio de alta fidelidad (Requiere cuenta YouTube Music Premium)' },
  { id: 'mp3_320', nombre: 'MP3 320k', badge: 'Transcodificado', desc: 'Para estéreos de auto antiguos que no leen Opus ni M4A' },
  { id: 'data_saver', nombre: 'Ahorro de Datos', badge: 'Opus ~70k', desc: 'Mínimo consumo de espacio de almacenamiento en el teléfono' }
];

export default function App() {
  const [activeTab, setActiveTab] = useState('explore'); // explore, library, history, settings
  const [query, setQuery] = useState('');
  const [selectedQuality, setSelectedQuality] = useState('native');
  const [searchResults, setSearchResults] = useState([]);
  const [isSearching, setIsSearching] = useState(false);
  const [isScanningLibrary, setIsScanningLibrary] = useState(false);
  const [librarySearchQuery, setLibrarySearchQuery] = useState('');
  
  // Estado del motor de descargas
  const [engineInfo, setEngineInfo] = useState({ isNative: false, initialized: false, version: '' });

  // Estado de conectividad de red (Online / Offline)
  const [isOnline, setIsOnline] = useState(() => typeof navigator !== 'undefined' ? navigator.onLine : true);
  const [showRestoredToast, setShowRestoredToast] = useState(false);
  const [isCheckingConnection, setIsCheckingConnection] = useState(false);
  const [offlineAttemptNotice, setOfflineAttemptNotice] = useState(false);
  
  // Estado de cola de descargas múltiples y terminal en vivo
  const [downloadQueue, setDownloadQueue] = useState([]);
  const [showQueueModal, setShowQueueModal] = useState(false);
  const isProcessingQueueRef = useRef(false);
  const [downloadSuccessMessage, setDownloadSuccessMessage] = useState('');
  const [terminalLines, setTerminalLines] = useState([]);
  const [showTerminal, setShowTerminal] = useState(false);
  const terminalContainerRef = useRef(null);
  const [showQualityDropdown, setShowQualityDropdown] = useState(false);

  const activeDownload = useMemo(() => {
    return downloadQueue.find(q => q.status === 'downloading') || 
           downloadQueue.find(q => q.status === 'queued') ||
           null;
  }, [downloadQueue]);

  const queuedDownloadsCount = useMemo(() => {
    return downloadQueue.filter(q => q.status === 'queued').length;
  }, [downloadQueue]);
  
  // Biblioteca y Reproductor Pro Apple Music
  const [libraryTracks, setLibraryTracks] = useState([]);
  const [historyList, setHistoryList] = useState([]);
  const [currentTrack, setCurrentTrack] = useState(() => {
    try {
      const saved = localStorage.getItem('ytm_last_track');
      return saved ? JSON.parse(saved) : null;
    } catch (e) {
      return null;
    }
  });
  const [isPlaying, setIsPlaying] = useState(false);
  const [audioDuration, setAudioDuration] = useState(0);
  const audioRef = useRef(null);

  // Cola de reproducción, modos y controles de audio
  const [queue, setQueue] = useState([]);
  const [queueIndex, setQueueIndex] = useState(0);
  const [shuffle, setShuffle] = useState(false);
  const [repeatMode, setRepeatMode] = useState('all'); // 'off' | 'all' | 'one'
  const [volume, setVolume] = useState(() => {
    try {
      const v = localStorage.getItem('ytm_volume');
      return v !== null ? parseFloat(v) : 1;
    } catch (e) {
      return 1;
    }
  });
  const [isMuted, setIsMuted] = useState(false);
  const [playbackRate, setPlaybackRate] = useState(1);
  const [sleepTimer, setSleepTimer] = useState(null); // { minutesLeft: number, mode: 'timer'|'track' }

  // Persistir última canción escuchada
  useEffect(() => {
    if (currentTrack) {
      try {
        localStorage.setItem('ytm_last_track', JSON.stringify({
          title: currentTrack.title,
          artist: currentTrack.artist,
          album: currentTrack.album,
          folder: currentTrack.folder,
          cover: currentTrack.cover,
          path: currentTrack.path,
          duration_str: currentTrack.duration_str
        }));
      } catch (e) {}
    }
  }, [currentTrack]);

  // Pantalla completa Now Playing estilo Apple Music (Portada, Letras, Cola)
  const [showNowPlayingSheet, setShowNowPlayingSheet] = useState(false);
  const [nowPlayingTab, setNowPlayingTab] = useState('artwork'); // 'artwork' | 'lyrics' | 'queue'
  const [trackLyrics, setTrackLyrics] = useState(null);
  const [isLoadingLyrics, setIsLoadingLyrics] = useState(false);

  // Permiso de almacenamiento completo y filtros de biblioteca
  const [hasStoragePermission, setHasStoragePermission] = useState(true);
  const [selectedArtistFilter, setSelectedArtistFilter] = useState('ALL');
  const [librarySortBy, setLibrarySortBy] = useState('title'); // 'title' | 'artist' | 'recent'

  // Autenticación de YouTube (Solución anti-bot / cookies)
  const [cookieInfo, setCookieInfo] = useState({ hasCookies: false, size: 0 });
  const [showCookieModal, setShowCookieModal] = useState(false);
  const [cookiesInputText, setCookiesInputText] = useState('');
  const [isAuthenticating, setIsAuthenticating] = useState(false);

  // Reproductor de vista previa (30s) para resultados de búsqueda
  const [previewTrackId, setPreviewTrackId] = useState(null);
  const previewAudioRef = useRef(new Audio());

  // Sincronización masiva de carátulas oficiales 1200x1200px
  const [isSyncingCovers, setIsSyncingCovers] = useState(false);
  const [syncProgress, setSyncProgress] = useState({ current: 0, total: 0, artist: '', item: '', percent: 0 });
  const [syncAbortController, setSyncAbortController] = useState(null);
  const [syncSuccessMsg, setSyncSuccessMsg] = useState('');
  const [urlResolvingStatus, setUrlResolvingStatus] = useState('');

  // Favoritos y Listas de Reproducción personalizadas
  const [favorites, setFavorites] = useState(() => {
    try {
      const saved = localStorage.getItem('ytm_favorites');
      return saved ? JSON.parse(saved) : [];
    } catch (e) {
      return [];
    }
  });
  const [playlists, setPlaylists] = useState(() => {
    try {
      const saved = localStorage.getItem('ytm_playlists');
      return saved ? JSON.parse(saved) : [];
    } catch (e) {
      return [];
    }
  });
  const [selectedPlaylistFilter, setSelectedPlaylistFilter] = useState('all'); // 'all' | 'favorites' | playlistId

  // Modales de Carátula, Playlists y Carpetas
  const [showCoverPicker, setShowCoverPicker] = useState(false);
  const [coverPickerTrack, setCoverPickerTrack] = useState(null);
  const [showPlaylistModal, setShowPlaylistModal] = useState(false);
  const [playlistModalTrack, setPlaylistModalTrack] = useState(null);
  const [showFolderModal, setShowFolderModal] = useState(false);
  const [trackMenuOpen, setTrackMenuOpen] = useState(null); // track.path

  // Autocompletado predictivo en Explorar
  const [searchSuggestions, setSearchSuggestions] = useState([]);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const suggestionsTimerRef = useRef(null);

  // Gesto táctil horizontal en mini-reproductor
  const miniSwipeStartX = useRef(0);

  // Gestor para gestos de retroceso del sistema (Android Back Gestures)
  const backStateRef = useRef({});
  backStateRef.current = {
    showNowPlayingSheet,
    showCoverPicker,
    showPlaylistModal,
    showFolderModal,
    showQueueModal,
    showCookieModal,
    showTerminal,
    trackMenuOpen,
    showSuggestions,
    activeTab,
    query,
    searchResults
  };

  useEffect(() => {
    window.handleSystemBack = () => {
      const s = backStateRef.current;
      if (s.showNowPlayingSheet) {
        setShowNowPlayingSheet(false);
        return true;
      }
      if (s.showCoverPicker) {
        setShowCoverPicker(false);
        setCoverPickerTrack(null);
        return true;
      }
      if (s.showQueueModal) {
        setShowQueueModal(false);
        return true;
      }
      if (s.showFolderModal) {
        setShowFolderModal(false);
        return true;
      }
      if (s.showPlaylistModal) {
        setShowPlaylistModal(false);
        setPlaylistModalTrack(null);
        return true;
      }
      if (s.showCookieModal) {
        setShowCookieModal(false);
        return true;
      }
      if (s.showTerminal) {
        setShowTerminal(false);
        return true;
      }
      if (s.trackMenuOpen) {
        setTrackMenuOpen(null);
        return true;
      }
      if (s.showSuggestions) {
        setShowSuggestions(false);
        return true;
      }
      if (s.activeTab !== 'explore') {
        setActiveTab('explore');
        return true;
      }
      if (s.query || (s.searchResults && s.searchResults.length > 0)) {
        setQuery('');
        setSearchResults([]);
        return true;
      }
      return false;
    };

    return () => {
      window.handleSystemBack = null;
    };
  }, []);

  // Inicialización
  useEffect(() => {
    initEngine();
    loadLibraryData();
    loadCookieInfo();

    // Escuchar eventos de progreso y terminal en vivo
    const unsubscribe = subscribeDownloadEvents(
      (data) => {
        if (data.percent !== undefined) {
          setDownloadQueue(prev => prev.map(q => {
            if (q.status === 'downloading') {
              return {
                ...q,
                percent: data.percent,
                eta: data.eta || (q.eta || '--'),
                speed: data.speed !== undefined && data.speed !== '' ? data.speed : (q.speed || ''),
                statusText: data.statusText || q.statusText,
                stage: data.stage || q.stage
              };
            }
            return q;
          }));
        }
      },
      (line) => {
        setTerminalLines(prev => [...prev.slice(-100), line]);
      }
    );

    const previewAudio = previewAudioRef.current;
    const handlePreviewEnded = () => setPreviewTrackId(null);
    previewAudio.addEventListener('ended', handlePreviewEnded);

    return () => {
      if (unsubscribe) unsubscribe();
      previewAudio.removeEventListener('ended', handlePreviewEnded);
      previewAudio.pause();
    };
  }, []);

  // Detección reactiva de conexión a Internet (Online / Offline)
  useEffect(() => {
    let timer = null;
    const handleOnline = () => {
      setIsOnline(true);
      setShowRestoredToast(true);
      timer = setTimeout(() => setShowRestoredToast(false), 3500);
    };
    const handleOffline = () => {
      setIsOnline(false);
      setShowRestoredToast(false);
    };

    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);

    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      setIsOnline(false);
    }

    return () => {
      if (timer) clearTimeout(timer);
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  // Comprobar estado real de red con solicitud liviana
  const checkConnectionManually = async () => {
    setIsCheckingConnection(true);
    try {
      if (typeof navigator !== 'undefined' && !navigator.onLine) {
        setIsOnline(false);
        setIsCheckingConnection(false);
        return;
      }
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 3000);
      try {
        await fetch('https://www.google.com/generate_204', { mode: 'no-cors', signal: controller.signal, cache: 'no-store' });
        setIsOnline(true);
        setShowRestoredToast(true);
        setTimeout(() => setShowRestoredToast(false), 3500);
      } catch (err) {
        setIsOnline(false);
      } finally {
        clearTimeout(timeoutId);
      }
    } catch (e) {
      setIsOnline(false);
    } finally {
      setIsCheckingConnection(false);
    }
  };

  // Auto-scroll interno para la consola técnica SIN mover la ventana del usuario
  useEffect(() => {
    if (showTerminal && terminalContainerRef.current) {
      terminalContainerRef.current.scrollTop = terminalContainerRef.current.scrollHeight;
    }
  }, [terminalLines, showTerminal]);

  const loadCookieInfo = async () => {
    const st = await getCookieStatus();
    setCookieInfo(st || { hasCookies: false, size: 0 });
  };

  const handleOpenLogin = async () => {
    setIsAuthenticating(true);
    try {
      await openYouTubeLogin();
      await loadCookieInfo();
    } catch (err) {
      console.warn('Login completado o cerrado:', err);
    } finally {
      setIsAuthenticating(false);
      await loadCookieInfo();
    }
  };

  const handleSaveCookiesPasted = async () => {
    if (!cookiesInputText.trim()) return;
    try {
      await saveCookiesText(cookiesInputText);
      setShowCookieModal(false);
      setCookiesInputText('');
      await loadCookieInfo();
      alert('¡Cookies guardadas correctamente! Las descargas ahora están autenticadas.');
    } catch (err) {
      alert('Error guardando cookies: ' + err.message);
    }
  };

  const handleClearCookies = async () => {
    if (!confirm('¿Deseas desconectar tu cuenta y eliminar las cookies de YouTube?')) return;
    await clearCookies();
    await loadCookieInfo();
  };

  const [isUpdatingEngine, setIsUpdatingEngine] = useState(false);

  const handleUpdateEngine = async () => {
    setIsUpdatingEngine(true);
    try {
      const res = await updateEngine();
      alert(`Motor yt-dlp actualizado con éxito a la versión ${res.version || 'reciente'}`);
      await initEngine();
    } catch (err) {
      alert(`Error actualizando motor yt-dlp: ${err.message}`);
    } finally {
      setIsUpdatingEngine(false);
    }
  };

  const initEngine = async () => {
    const status = await getEngineStatus();
    setEngineInfo(status);
    const hasPerm = await checkStoragePermission();
    setHasStoragePermission(hasPerm);
    loadCookieInfo();
  };

  const handleRequestStoragePermission = async () => {
    await requestStoragePermission();
    setTimeout(async () => {
      const hasPerm = await checkStoragePermission();
      setHasStoragePermission(hasPerm);
    }, 1500);
  };

  const loadLibraryData = async () => {
    setIsScanningLibrary(true);
    try {
      await requestAudioPermissions();
      const tracks = await apiFetchLibrary();
      setLibraryTracks(tracks);
    } catch (e) {
      console.warn('Error cargando biblioteca:', e);
    } finally {
      setIsScanningLibrary(false);
    }
    try {
      const hist = await apiFetchHistory();
      setHistoryList(hist);
    } catch (e) {
      console.warn('Error cargando historial:', e);
    }
  };

  // Sincronizar carátulas oficiales para toda la biblioteca
  const handleSyncCovers = async () => {
    if (isSyncingCovers || libraryTracks.length === 0) return;

    if (isNativeApp()) {
      const hasPerm = await checkStoragePermission();
      if (!hasPerm) {
        alert('Se requiere permiso de acceso a todos los archivos para guardar las carátulas en tu música. Por favor autorízalo en los ajustes.');
        await requestStoragePermission();
        return;
      }
    }

    const controller = new AbortController();
    setSyncAbortController(controller);
    setIsSyncingCovers(true);
    setSyncProgress({ current: 0, total: 0, artist: '', item: '', percent: 0 });
    setSyncSuccessMsg('');

    try {
      const res = await syncLibraryCovers(
        libraryTracks,
        (p) => setSyncProgress(p),
        controller.signal
      );
      if (!controller.signal.aborted) {
        setSyncSuccessMsg(`Sincronización finalizada: se actualizaron ${res.updated} carátulas.`);
        await loadLibraryData();
        setTimeout(() => setSyncSuccessMsg(''), 8000);
      }
    } catch (err) {
      console.warn('Error sincronizando carátulas:', err);
    } finally {
      setIsSyncingCovers(false);
      setSyncAbortController(null);
    }
  };

  const cancelSyncCovers = () => {
    if (syncAbortController) {
      syncAbortController.abort();
      setIsSyncingCovers(false);
      setSyncAbortController(null);
    }
  };

  // Extracción dinámica de artistas con conteo de canciones
  const artistStats = useMemo(() => {
    const map = {};
    libraryTracks.forEach(t => {
      const a = (t.artist || 'Desconocido').trim();
      map[a] = (map[a] || 0) + 1;
    });
    return Object.entries(map)
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
  }, [libraryTracks]);

  // Filtrado y ordenamiento dinámico de biblioteca local
  const filteredLibraryTracks = useMemo(() => {
    let list = [...libraryTracks];

    if (selectedPlaylistFilter === 'favorites') {
      const favSet = new Set(favorites);
      list = list.filter(t => favSet.has(t.path));
    } else if (selectedPlaylistFilter !== 'all') {
      const pl = playlists.find(p => p.id === selectedPlaylistFilter);
      if (pl && pl.tracks) {
        const plSet = new Set(pl.tracks.map(t => t.path));
        list = list.filter(t => plSet.has(t.path));
      }
    }

    if (selectedArtistFilter !== 'ALL') {
      list = list.filter(t => (t.artist || '').trim().toLowerCase() === selectedArtistFilter.toLowerCase());
    }

    if (librarySearchQuery.trim()) {
      const q = librarySearchQuery.toLowerCase().trim();
      list = list.filter(t => 
        (t.title && t.title.toLowerCase().includes(q)) ||
        (t.artist && t.artist.toLowerCase().includes(q)) ||
        (t.album && t.album.toLowerCase().includes(q)) ||
        (t.folder && t.folder.toLowerCase().includes(q))
      );
    }

    if (librarySortBy === 'title') {
      list.sort((a, b) => (a.title || '').localeCompare(b.title || ''));
    } else if (librarySortBy === 'artist') {
      list.sort((a, b) => (a.artist || '').localeCompare(b.artist || '') || (a.title || '').localeCompare(b.title || ''));
    } else if (librarySortBy === 'recent') {
      list.sort((a, b) => (b.lastModified || 0) - (a.lastModified || 0));
    }

    return list;
  }, [libraryTracks, selectedPlaylistFilter, favorites, playlists, selectedArtistFilter, librarySearchQuery, librarySortBy]);

  // Alternar favoritos con persistencia y respuesta háptica
  const toggleFavorite = (track, e) => {
    if (e) e.stopPropagation();
    if (!track?.path) return;
    if (window.navigator?.vibrate) window.navigator.vibrate(10);
    setFavorites(prev => {
      const exists = prev.includes(track.path);
      const updated = exists ? prev.filter(p => p !== track.path) : [...prev, track.path];
      try {
        localStorage.setItem('ytm_favorites', JSON.stringify(updated));
      } catch (err) {}
      return updated;
    });
  };

  // Gestión de Listas de Reproducción
  const handleCreatePlaylist = (name) => {
    const newPl = {
      id: 'pl_' + Date.now(),
      name,
      tracks: [],
      createdAt: Date.now()
    };
    setPlaylists(prev => {
      const updated = [...prev, newPl];
      try { localStorage.setItem('ytm_playlists', JSON.stringify(updated)); } catch (e) {}
      return updated;
    });
    if (window.navigator?.vibrate) window.navigator.vibrate(10);
  };

  const handleAddToPlaylist = (playlistId, track) => {
    setPlaylists(prev => {
      const updated = prev.map(pl => {
        if (pl.id === playlistId) {
          const exists = pl.tracks.some(t => t.path === track.path);
          if (!exists) {
            return { ...pl, tracks: [...pl.tracks, track] };
          }
        }
        return pl;
      });
      try { localStorage.setItem('ytm_playlists', JSON.stringify(updated)); } catch (e) {}
      return updated;
    });
    if (window.navigator?.vibrate) window.navigator.vibrate(10);
  };

  const handleDeletePlaylist = (playlistId) => {
    setPlaylists(prev => {
      const updated = prev.filter(pl => pl.id !== playlistId);
      try { localStorage.setItem('ytm_playlists', JSON.stringify(updated)); } catch (e) {}
      return updated;
    });
    if (selectedPlaylistFilter === playlistId) setSelectedPlaylistFilter('all');
  };

  const handlePlayPlaylist = (playlist) => {
    if (!playlist.tracks || playlist.tracks.length === 0) return;
    setQueue(playlist.tracks);
    setQueueIndex(0);
    playTrack(playlist.tracks[0], playlist.tracks);
  };

  // Actualización inmediata de carátula en memoria
  const handleCoverUpdated = (newCoverUrl, trackPath) => {
    setLibraryTracks(prev => prev.map(t => {
      if (t.path === trackPath) {
        return { ...t, cover: newCoverUrl, coverPath: newCoverUrl };
      }
      return t;
    }));
    if (currentTrack?.path === trackPath) {
      setCurrentTrack(prev => ({ ...prev, cover: newCoverUrl }));
    }
  };

  // Almacenamiento total calculado
  const totalLibrarySizeMb = useMemo(() => {
    const sum = libraryTracks.reduce((acc, t) => acc + (Number(t.size_mb) || 0), 0);
    return Math.round(sum * 10) / 10;
  }, [libraryTracks]);

  // Resolución automática de enlaces con estado visual
  const autoResolveUrl = async (rawText) => {
    const cleanUrl = extractCleanUrl(rawText);
    if (!cleanUrl) return null;

    if (!isOnline && typeof navigator !== 'undefined' && !navigator.onLine) {
      setOfflineAttemptNotice(true);
      setTimeout(() => setOfflineAttemptNotice(false), 4000);
      return null;
    }

    setIsSearching(true);
    setUrlResolvingStatus('Identificando canción y carátula oficial...');
    try {
      const resolved = await resolveTrackFromUrl(cleanUrl);
      if (resolved) {
        setSearchResults([resolved]);
        return resolved;
      }
    } catch (err) {
      console.warn('Error resolviendo enlace:', err);
    } finally {
      setIsSearching(false);
      setUrlResolvingStatus('');
    }
    return null;
  };

  const handleQueryChange = (e) => {
    const val = e.target.value;
    setQuery(val);
    const cleanUrl = extractCleanUrl(val);
    if (cleanUrl) {
      autoResolveUrl(cleanUrl);
      setShowSuggestions(false);
    } else if (val.trim().length >= 2) {
      if (suggestionsTimerRef.current) clearTimeout(suggestionsTimerRef.current);
      suggestionsTimerRef.current = setTimeout(async () => {
        const sugs = await apiGetSearchSuggestions(val);
        setSearchSuggestions(sugs);
        setShowSuggestions(sugs.length > 0);
      }, 150);
    } else {
      setShowSuggestions(false);
    }
  };

  // Resolver automáticamente metadatos en cuanto el usuario pega un enlace en el input
  const handlePaste = (e) => {
    const pasted = e.clipboardData?.getData('text') || '';
    const cleanUrl = extractCleanUrl(pasted);
    if (cleanUrl) {
      autoResolveUrl(cleanUrl);
    }
  };

  // Buscar canciones (directo en iTunes API + YouTube Music Nativo)
  const executeSearchQuery = async (rawQuery) => {
    const raw = (rawQuery || '').trim();
    if (!raw) return;

    if (!isOnline && typeof navigator !== 'undefined' && !navigator.onLine) {
      setOfflineAttemptNotice(true);
      setTimeout(() => setOfflineAttemptNotice(false), 4000);
      return;
    }

    // Detectar si el texto contiene una URL (YouTube / YouTube Music)
    const cleanUrl = extractCleanUrl(raw);
    if (cleanUrl) {
      setIsSearching(true);
      try {
        let targetTrack = searchResults.find(r => r.url === cleanUrl || r.url?.includes(cleanUrl));
        if (!targetTrack) {
          targetTrack = await autoResolveUrl(cleanUrl);
        }
        if (targetTrack) {
          handleDownloadItem(targetTrack);
        } else {
          handleDownloadItem({ url: cleanUrl, title: 'Descarga YouTube', artist: 'YouTube' });
        }
      } catch (err) {
        console.error('Error identificando enlace:', err);
        handleDownloadItem({ url: cleanUrl, title: 'Descarga YouTube', artist: 'YouTube' });
      } finally {
        setIsSearching(false);
      }
      return;
    }

    setIsSearching(true);
    if (previewAudioRef.current) {
      previewAudioRef.current.pause();
      setPreviewTrackId(null);
    }

    try {
      const results = await searchMusic(raw);
      setSearchResults(results);
    } catch (err) {
      console.error('Error buscando:', err);
    } finally {
      setIsSearching(false);
    }
  };

  const handleSearch = (e) => {
    if (e) e.preventDefault();
    executeSearchQuery(query);
  };

  const handlePasteFromClipboard = async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text && text.trim()) {
        const val = text.trim();
        setQuery(val);
        const cleanUrl = extractCleanUrl(val);
        if (cleanUrl) {
          autoResolveUrl(cleanUrl);
        } else {
          executeSearchQuery(val);
        }
      }
    } catch (err) {
      console.warn('No se pudo leer el portapapeles:', err);
    }
  };

  // Eliminación instantánea y optimista (0 ms)
  const handleDeleteTrack = async (track, e) => {
    if (e) e.stopPropagation();
    if (!confirm(`¿Eliminar "${track.title}" de tu dispositivo?`)) return;

    // 1. Desaparece de inmediato de la pantalla (optimistic update)
    setLibraryTracks(prev => prev.filter(t => t.path !== track.path));

    // 2. Detener reproducción si era la que estaba sonando
    if (currentTrack && currentTrack.path === track.path) {
      if (audioRef.current) {
        audioRef.current.pause();
      }
      setIsPlaying(false);
      setCurrentTrack(null);
    }

    // 3. Purgar físicamente del almacenamiento y MediaStore en segundo plano
    try {
      await apiDeleteTrack(track);
    } catch (err) {
      console.error('Error purgando pista:', err);
    }
  };

  // Reproducir o pausar vista previa de 30 segundos
  const togglePreview = (item, e) => {
    e.stopPropagation();
    if (!item.previewUrl) return;

    if (!isOnline && typeof navigator !== 'undefined' && !navigator.onLine) {
      setOfflineAttemptNotice(true);
      setTimeout(() => setOfflineAttemptNotice(false), 4000);
      return;
    }

    if (previewTrackId === item.id) {
      previewAudioRef.current.pause();
      setPreviewTrackId(null);
    } else {
      if (audioRef.current && isPlaying) {
        audioRef.current.pause();
        setIsPlaying(false);
      }
      previewAudioRef.current.src = item.previewUrl;
      previewAudioRef.current.play().catch(console.warn);
      setPreviewTrackId(item.id);
    }
  };

  // Motor de procesamiento secuencial de la cola de descargas
  const processNextQueueItem = async (queueItem) => {
    if (isProcessingQueueRef.current) return;
    isProcessingQueueRef.current = true;

    const { id, item, quality } = queueItem;
    const songTitle = item.title || 'Tema';
    const artistName = item.artist || '';
    const fullDisplayName = artistName ? `${artistName} - ${songTitle}` : songTitle;

    setDownloadQueue(prev => prev.map(q => 
      q.id === id ? { ...q, status: 'downloading', stage: 'init', statusText: 'Iniciando descarga en alta fidelidad...', percent: 0 } : q
    ));

    setTerminalLines(prev => [
      ...prev,
      `[Cola] Iniciando descarga: ${fullDisplayName} (${quality.toUpperCase()})`
    ]);

    try {
      await requestDownload(item, quality);
      addHistoryEntry(fullDisplayName);
      loadLibraryData();

      setDownloadQueue(prev => prev.map(q => 
        q.id === id ? { 
          ...q, 
          status: 'done', 
          percent: 100, 
          stage: 'done', 
          statusText: '¡Descarga completada y guardada!' 
        } : q
      ));

      setDownloadSuccessMessage(`Descarga completada: ${fullDisplayName}`);
      setTimeout(() => setDownloadSuccessMessage(''), 4500);

      setTerminalLines(prev => [
        ...prev,
        `[Cola] Éxito: ${fullDisplayName} guardado en el teléfono`
      ]);
    } catch (err) {
      const msg = err.message || 'Error desconocido';
      setTerminalLines(prev => [...prev, `[Cola Error] ${fullDisplayName}: ${msg}`]);

      setDownloadQueue(prev => prev.map(q => 
        q.id === id ? { 
          ...q, 
          status: 'error', 
          stage: 'error', 
          statusText: 'Error: ' + msg,
          errorMsg: msg 
        } : q
      ));

      if (msg.includes('antibot') || msg.includes('bot') || msg.includes('verificación') || msg.includes('Sign in')) {
        const wantsLogin = confirm('YouTube requiere verificación antibot en tu red Wi-Fi actual.\n\n¿Deseas iniciar sesión en Ajustes para descargar con cookies?');
        if (wantsLogin) {
          handleOpenLogin();
        }
      }
    } finally {
      isProcessingQueueRef.current = false;
      // Desencadenar la comprobación del siguiente ítem pendiente en la cola
      setDownloadQueue(prev => [...prev]);
    }
  };

  // Procesar automáticamente el siguiente ítem queued en la cola
  useEffect(() => {
    if (isProcessingQueueRef.current) return;
    const nextItem = downloadQueue.find(q => q.status === 'queued');
    if (nextItem) {
      processNextQueueItem(nextItem);
    }
  }, [downloadQueue]);

  // Añadir una o múltiples canciones a la cola de descargas
  const handleDownloadItem = (item) => {
    if (!isOnline && typeof navigator !== 'undefined' && !navigator.onLine) {
      setOfflineAttemptNotice(true);
      setTimeout(() => setOfflineAttemptNotice(false), 4000);
      return;
    }

    const songTitle = item.title || 'Tema';
    const artistName = item.artist || '';
    const fullDisplayName = artistName ? `${artistName} - ${songTitle}` : songTitle;
    const downloadId = item.id || item.url || (fullDisplayName + '_' + Date.now());

    // Si ya existe en la cola activa, abrir la vista de la cola
    const existing = downloadQueue.find(q => q.id === downloadId || (q.item?.url && item.url && q.item.url === item.url));
    if (existing && (existing.status === 'queued' || existing.status === 'downloading')) {
      setShowQueueModal(true);
      return;
    }

    if (window.navigator?.vibrate) window.navigator.vibrate(12);

    const newEntry = {
      id: downloadId,
      item: { ...item },
      quality: selectedQuality,
      status: 'queued',
      percent: 0,
      speed: '',
      eta: '--',
      stage: 'queued',
      statusText: 'En espera en la cola...',
      errorMsg: null,
      addedAt: Date.now()
    };

    setDownloadQueue(prev => [...prev, newEntry]);
    setDownloadSuccessMessage(`Añadida a la cola: ${fullDisplayName}`);
    setTimeout(() => setDownloadSuccessMessage(''), 3000);
  };

  const handleRemoveQueueItem = (id) => {
    if (window.navigator?.vibrate) window.navigator.vibrate(10);
    setDownloadQueue(prev => prev.filter(q => q.id !== id));
  };

  const handleClearCompletedQueue = () => {
    if (window.navigator?.vibrate) window.navigator.vibrate(10);
    setDownloadQueue(prev => prev.filter(q => q.status !== 'done'));
  };

  const handleRetryQueueItem = (id) => {
    if (window.navigator?.vibrate) window.navigator.vibrate(12);
    setDownloadQueue(prev => prev.map(q => 
      q.id === id ? { ...q, status: 'queued', stage: 'queued', percent: 0, errorMsg: null, statusText: 'Reintentando...' } : q
    ));
  };

  const handleDownloadAllResults = () => {
    if (!searchResults || searchResults.length === 0) return;
    let addedCount = 0;
    searchResults.forEach(item => {
      const alreadyInQueue = downloadQueue.some(q => 
        (q.status === 'queued' || q.status === 'downloading') && 
        (q.id === item.id || (q.item?.url && item.url && q.item.url === item.url))
      );
      const alreadyInLib = findMatchingTrack(item, libraryTracks);
      if (!alreadyInQueue && !alreadyInLib) {
        handleDownloadItem(item);
        addedCount++;
      }
    });
    if (addedCount > 0) {
      setShowQueueModal(true);
    }
  };

  // Helper para sincronizar posición en Android MediaSession
  const updateMediaSessionPosition = (current, total, rate = 1) => {
    if (!('mediaSession' in navigator) || !('setPositionState' in navigator.mediaSession)) return;
    if (!total || isNaN(total) || total <= 0) return;
    try {
      navigator.mediaSession.setPositionState({
        duration: total,
        playbackRate: rate,
        position: Math.min(Math.max(0, current), total)
      });
    } catch (e) {}
  };

  const loadLyricsForTrack = async (track) => {
    if (!track) {
      setTrackLyrics(null);
      return;
    }
    setIsLoadingLyrics(true);
    try {
      const res = await fetchTrackLyrics(track);
      setTrackLyrics(res);
    } catch (e) {
      console.warn('Error obteniendo letras:', e);
      setTrackLyrics(null);
    } finally {
      setIsLoadingLyrics(false);
    }
  };

  const playSingleTrack = (track) => {
    setCurrentTrack(track);
    setIsPlaying(true);
    loadLyricsForTrack(track);

    audioDsp.ensureResumed();

    setTimeout(() => {
      if (audioRef.current) {
        audioDsp.attach(audioRef.current);
        audioRef.current.src = getStreamUrl(track.path);
        audioRef.current.playbackRate = playbackRate;
        audioRef.current.volume = isMuted ? 0 : volume;
        audioRef.current.play().catch(console.warn);
      }
    }, 40);
  };

  // Iniciar reproducción y configurar la cola de reproducción
  const playTrack = (track, customQueue = null) => {
    if (previewAudioRef.current) {
      previewAudioRef.current.pause();
      setPreviewTrackId(null);
    }

    if (currentTrack && currentTrack.path === track.path) {
      if (isPlaying) {
        if (audioRef.current) audioRef.current.pause();
        setIsPlaying(false);
      } else {
        if (audioRef.current) audioRef.current.play().catch(console.warn);
        setIsPlaying(true);
      }
      return;
    }

    let newQueue = customQueue;
    if (!newQueue || newQueue.length === 0) {
      if (libraryTracks && libraryTracks.length > 0) {
        newQueue = libraryTracks;
      } else {
        newQueue = [track];
      }
    }
    setQueue(newQueue);

    let idx = newQueue.findIndex(t => t.path === track.path);
    if (idx === -1) {
      newQueue = [track, ...newQueue];
      setQueue(newQueue);
      idx = 0;
    }
    setQueueIndex(idx);

    playSingleTrack(track);
  };

  const handleNextTrack = () => {
    if (!currentTrack || queue.length === 0) return;

    if (repeatMode === 'one') {
      if (audioRef.current) {
        audioRef.current.currentTime = 0;
        audioRef.current.play().catch(console.warn);
        setIsPlaying(true);
      }
      return;
    }

    if (shuffle) {
      if (queue.length === 1) {
        if (audioRef.current) {
          audioRef.current.currentTime = 0;
          audioRef.current.play().catch(console.warn);
        }
        return;
      }
      let nextIdx = Math.floor(Math.random() * queue.length);
      if (nextIdx === queueIndex && queue.length > 1) {
        nextIdx = (nextIdx + 1) % queue.length;
      }
      setQueueIndex(nextIdx);
      playSingleTrack(queue[nextIdx]);
      return;
    }

    const nextIdx = queueIndex + 1;
    if (nextIdx < queue.length) {
      setQueueIndex(nextIdx);
      playSingleTrack(queue[nextIdx]);
    } else {
      if (repeatMode === 'all') {
        setQueueIndex(0);
        playSingleTrack(queue[0]);
      } else {
        setIsPlaying(false);
      }
    }
  };

  const handlePrevTrack = () => {
    if (!currentTrack || queue.length === 0) return;

    // Si pasaron más de 3s de canción, reiniciar tema actual
    if (audioRef.current && audioRef.current.currentTime > 3) {
      audioRef.current.currentTime = 0;
      updateMediaSessionPosition(0, audioDuration, playbackRate);
      return;
    }

    if (shuffle) {
      let prevIdx = Math.floor(Math.random() * queue.length);
      if (prevIdx === queueIndex && queue.length > 1) {
        prevIdx = (prevIdx - 1 + queue.length) % queue.length;
      }
      setQueueIndex(prevIdx);
      playSingleTrack(queue[prevIdx]);
      return;
    }

    const prevIdx = queueIndex - 1;
    if (prevIdx >= 0) {
      setQueueIndex(prevIdx);
      playSingleTrack(queue[prevIdx]);
    } else {
      if (repeatMode === 'all') {
        const lastIdx = queue.length - 1;
        setQueueIndex(lastIdx);
        playSingleTrack(queue[lastIdx]);
      } else {
        if (audioRef.current) {
          audioRef.current.currentTime = 0;
          updateMediaSessionPosition(0, audioDuration, playbackRate);
        }
      }
    }
  };

  const handleTogglePlay = () => {
    if (!audioRef.current) return;
    if (isPlaying) {
      audioRef.current.pause();
      setIsPlaying(false);
    } else {
      audioRef.current.play().catch(console.warn);
      setIsPlaying(true);
    }
  };

  const handleSeek = (seconds) => {
    if (audioRef.current) {
      audioRef.current.currentTime = seconds;
      updateMediaSessionPosition(seconds, audioDuration, playbackRate);
    }
  };

  const handleSeekToLyric = (time) => {
    if (audioRef.current && time >= 0) {
      audioRef.current.currentTime = time;
      if (!isPlaying) {
        audioRef.current.play().catch(console.warn);
        setIsPlaying(true);
      }
      updateMediaSessionPosition(time, audioDuration, playbackRate);
    }
  };

  const handleToggleShuffle = () => {
    setShuffle(prev => !prev);
  };

  const handleCycleRepeat = () => {
    setRepeatMode(prev => prev === 'off' ? 'all' : prev === 'all' ? 'one' : 'off');
  };

  const handleVolumeChange = (vol) => {
    setVolume(vol);
    setIsMuted(vol === 0);
    try {
      localStorage.setItem('ytm_volume', vol.toString());
    } catch (e) {}
    if (audioRef.current) {
      audioRef.current.volume = vol;
      audioRef.current.muted = vol === 0;
    }
  };

  const handleToggleMute = () => {
    if (isMuted) {
      setIsMuted(false);
      const restoreVol = volume > 0 ? volume : 0.8;
      setVolume(restoreVol);
      if (audioRef.current) {
        audioRef.current.volume = restoreVol;
        audioRef.current.muted = false;
      }
    } else {
      setIsMuted(true);
      if (audioRef.current) {
        audioRef.current.muted = true;
      }
    }
  };

  const handleChangePlaybackRate = (rate) => {
    setPlaybackRate(rate);
    if (audioRef.current) {
      audioRef.current.playbackRate = rate;
      updateMediaSessionPosition(audioRef.current.currentTime, audioDuration, rate);
    }
  };

  const handleSetSleepTimer = (val) => {
    if (!val) {
      setSleepTimer(null);
    } else if (val === 'track') {
      setSleepTimer({ minutesLeft: 0, mode: 'track' });
    } else {
      setSleepTimer({ minutesLeft: val, mode: 'timer' });
    }
  };

  const handleSelectTrackFromQueue = (track, idx) => {
    setQueueIndex(idx);
    playSingleTrack(track);
  };

  const handleRemoveTrackFromQueue = (idx) => {
    setQueue(prev => {
      const copy = [...prev];
      copy.splice(idx, 1);
      return copy;
    });
    if (idx < queueIndex) {
      setQueueIndex(prev => prev - 1);
    }
  };

  const handleClearQueue = () => {
    if (currentTrack) {
      setQueue([currentTrack]);
      setQueueIndex(0);
    } else {
      setQueue([]);
      setQueueIndex(0);
    }
  };

  // Integración nativa con Android MediaSession (Notificación y Pantalla de Bloqueo)
  useEffect(() => {
    if (!('mediaSession' in navigator) || !currentTrack) return;
    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: currentTrack.title || 'Música',
        artist: currentTrack.artist || 'Desconocido',
        album: currentTrack.album || currentTrack.folder || 'YouTube Music',
        artwork: currentTrack.cover ? [
          { src: currentTrack.cover, sizes: '512x512', type: 'image/jpeg' },
          { src: currentTrack.cover, sizes: '1200x1200', type: 'image/jpeg' }
        ] : []
      });
    } catch (e) {
      console.warn('Error configurando MediaMetadata:', e);
    }
  }, [currentTrack]);

  useEffect(() => {
    if (!('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.playbackState = isPlaying ? 'playing' : 'paused';
    } catch (e) {}
  }, [isPlaying]);

  useEffect(() => {
    if (!('mediaSession' in navigator)) return;

    const actionHandlers = {
      play: handleTogglePlay,
      pause: handleTogglePlay,
      previoustrack: handlePrevTrack,
      nexttrack: handleNextTrack,
      seekto: (details) => {
        if (details.seekTime !== undefined) handleSeek(details.seekTime);
      },
      seekforward: (details) => {
        const step = details.seekOffset || 10;
        const cur = audioRef.current ? audioRef.current.currentTime : 0;
        handleSeek(Math.min(audioDuration || 0, cur + step));
      },
      seekbackward: (details) => {
        const step = details.seekOffset || 10;
        const cur = audioRef.current ? audioRef.current.currentTime : 0;
        handleSeek(Math.max(0, cur - step));
      }
    };

    for (const [act, fn] of Object.entries(actionHandlers)) {
      try {
        navigator.mediaSession.setActionHandler(act, fn);
      } catch (e) {}
    }

    return () => {
      for (const act of Object.keys(actionHandlers)) {
        try {
          navigator.mediaSession.setActionHandler(act, null);
        } catch (e) {}
      }
    };
  }, [currentTrack, queue, queueIndex, isPlaying, audioDuration, playbackRate, repeatMode, shuffle]);

  // Temporizador de sueño (Sleep timer)
  useEffect(() => {
    if (!sleepTimer || sleepTimer.mode === 'track') return;
    const timerInterval = setInterval(() => {
      setSleepTimer(prev => {
        if (!prev || prev.mode !== 'timer') return null;
        if (prev.minutesLeft <= 1) {
          if (audioRef.current) {
            audioRef.current.pause();
          }
          setIsPlaying(false);
          return null;
        }
        return { ...prev, minutesLeft: prev.minutesLeft - 1 };
      });
    }, 60000);

    return () => clearInterval(timerInterval);
  }, [sleepTimer]);

  return (
    <div 
      style={{ paddingBottom: currentTrack ? 'calc(var(--sab, 0px) + 12.5rem)' : 'calc(var(--sab, 0px) + 7rem)' }}
      className="flex flex-col min-h-screen bg-black text-white font-sf select-none"
    >
      
      {/* Barra de estado y Top Bar translúcida adaptada al notch y safe-areas */}
      <header 
        style={{ paddingTop: 'calc(var(--sat, 0px) + 0.75rem)' }}
        className="sticky top-0 z-40 apple-glass light-edge px-4 pb-3 flex items-center justify-between transition-all"
      >
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-rose-600 to-pink-500 flex items-center justify-center shadow-lg shadow-rose-600/30">
            <Music className="w-4 h-4 text-white" />
          </div>
          <div>
            <h1 className="text-sm font-semibold tracking-tight text-white flex items-center gap-1.5">
              YT Music Pro
              <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-rose-500/20 text-rose-400 font-medium border border-rose-500/30">
                100% Local
              </span>
            </h1>
            <p className="text-[11px] text-zinc-400">Audio oficial de estudio sin recodificar</p>
          </div>
        </div>

        {/* Indicador de estado del motor embebido */}
        <div 
          onClick={() => setActiveTab('settings')}
          className="flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-full bg-white/5 border border-white/10 cursor-pointer active:scale-95 transition-all"
        >
          {!isOnline ? (
            <>
              <span className="w-2 h-2 rounded-full bg-amber-500 animate-pulse"></span>
              <span className="text-[11px] text-amber-400 font-medium flex items-center gap-1">
                <WifiOff className="w-3 h-3" /> Offline
              </span>
            </>
          ) : engineInfo.isNative ? (
            <>
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
              <span className="text-[11px] text-emerald-400 font-medium flex items-center gap-1">
                <Smartphone className="w-3 h-3" /> Autónomo
              </span>
            </>
          ) : engineInfo.online ? (
            <>
              <span className="w-2 h-2 rounded-full bg-emerald-500"></span>
              <span className="text-[11px] text-emerald-400 font-medium">Servidor PC</span>
            </>
          ) : (
            <>
              <span className="w-2 h-2 rounded-full bg-amber-500"></span>
              <span className="text-[11px] text-amber-400 font-medium">Búsqueda Directa</span>
            </>
          )}
        </div>
      </header>

      {/* Banner Sin Conexión a Internet (Apple Design Glass) */}
      {!isOnline && (
        <div className="mx-4 mt-3 px-3.5 py-2.5 rounded-2xl apple-glass border border-amber-500/25 bg-amber-500/10 flex items-center justify-between gap-3 shadow-lg shadow-black/40 animate-fade-in transition-all">
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="w-8 h-8 rounded-xl bg-amber-500/20 text-amber-400 flex items-center justify-center shrink-0">
              <WifiOff className="w-4 h-4" />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-1.5">
                <span className="text-xs font-bold text-amber-200">Sin conexión a internet</span>
                <span className="text-[9px] font-semibold px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300 uppercase tracking-wider">
                  Modo Offline
                </span>
              </div>
              <p className="text-[11px] text-amber-300/80 truncate mt-0.5">
                Música local disponible. Las descargas requieren conexión.
              </p>
            </div>
          </div>
          {activeTab === 'explore' ? (
            <button
              type="button"
              onClick={() => setActiveTab('library')}
              className="px-3 py-1.5 rounded-xl bg-amber-500/20 hover:bg-amber-500/30 active:scale-95 text-amber-200 border border-amber-500/30 text-xs font-semibold shrink-0 transition-all"
            >
              Biblioteca
            </button>
          ) : (
            <button
              type="button"
              onClick={checkConnectionManually}
              className="p-2 rounded-xl bg-amber-500/20 hover:bg-amber-500/30 active:scale-95 text-amber-300 border border-amber-500/30 shrink-0 transition-all"
              title="Comprobar conexión"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${isCheckingConnection ? 'animate-spin' : ''}`} />
            </button>
          )}
        </div>
      )}

      {/* Notificación de Conexión Restablecida */}
      {showRestoredToast && (
        <div className="mx-4 mt-3 px-3.5 py-2.5 rounded-2xl apple-glass border border-emerald-500/25 bg-emerald-500/10 flex items-center gap-2.5 shadow-lg shadow-black/40 animate-fade-in transition-all">
          <div className="w-7 h-7 rounded-xl bg-emerald-500/20 text-emerald-400 flex items-center justify-center shrink-0">
            <Wifi className="w-4 h-4" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs font-semibold text-emerald-200">Conexión restablecida</p>
            <p className="text-[11px] text-emerald-300/80">Búsqueda y descargas en línea disponibles nuevamente.</p>
          </div>
        </div>
      )}

      {/* Banner de descarga exitosa */}
      {downloadSuccessMessage && (
        <div className="mx-4 mt-3 p-3 rounded-2xl bg-emerald-500/15 border border-emerald-500/30 text-emerald-300 text-xs flex items-center gap-2 shadow-lg animate-fade-in">
          <Check className="w-4 h-4 text-emerald-400 shrink-0" />
          <span className="font-medium truncate">{downloadSuccessMessage}</span>
        </div>
      )}



      {/* HUD flotante de descarga activa y cola estilo Dynamic Island de Apple */}
      {activeDownload && (
        <div 
          onClick={() => setShowQueueModal(true)}
          className="mx-4 mt-3 rounded-3xl apple-glass-heavy border border-rose-500/30 p-3.5 shadow-2xl shadow-rose-600/15 animate-fade-in transition-all cursor-pointer"
        >
          <div className="flex items-center gap-3">
            {/* Miniatura / Carátula con spinner o check */}
            <div className="relative w-12 h-12 rounded-2xl overflow-hidden bg-zinc-800 shrink-0 shadow-md border border-white/10 flex items-center justify-center">
              {activeDownload.item?.cover ? (
                <img src={activeDownload.item.cover} alt="" className="w-full h-full object-cover" />
              ) : (
                <div className="w-full h-full bg-gradient-to-tr from-rose-600 to-pink-500 flex items-center justify-center text-white">
                  <Music className="w-5 h-5" />
                </div>
              )}
              {activeDownload.stage !== 'done' ? (
                <div className="absolute inset-0 bg-black/40 backdrop-blur-[1px] flex items-center justify-center">
                  <RefreshCw className="w-5 h-5 text-white animate-spin" />
                </div>
              ) : (
                <div className="absolute inset-0 bg-emerald-600/70 backdrop-blur-[1px] flex items-center justify-center">
                  <Check className="w-6 h-6 text-white stroke-[3]" />
                </div>
              )}
            </div>

            {/* Información del tema y progreso numérico */}
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-2">
                <h4 className="text-xs font-bold text-white truncate">
                  {activeDownload.item?.title || 'Descargando...'}
                </h4>
                <div className="flex items-center gap-1.5 shrink-0">
                  {queuedDownloadsCount > 0 && (
                    <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-rose-500/20 text-rose-300 font-bold border border-rose-500/30 flex items-center gap-1">
                      <Clock className="w-2.5 h-2.5" />
                      +{queuedDownloadsCount} en cola
                    </span>
                  )}
                  <span className="text-xs font-mono font-extrabold text-rose-400">
                    {activeDownload.percent || 0}%
                  </span>
                </div>
              </div>
              <p className="text-[11px] text-zinc-400 truncate">
                {activeDownload.item?.artist || 'YouTube Music'}
              </p>

              {/* Indicadores de etapa y velocidad */}
              <div className="flex items-center justify-between text-[10px] text-zinc-400 mt-1">
                <span className="text-rose-300 font-medium truncate max-w-[65%]">
                  {activeDownload.statusText || 'Procesando...'}
                </span>
                <span className="font-mono text-zinc-400 shrink-0 flex items-center gap-1.5">
                  {activeDownload.speed && <span>{activeDownload.speed}</span>}
                  {activeDownload.eta && activeDownload.eta !== '--' && (
                    <span className="flex items-center gap-1">
                      <Clock className="w-2.5 h-2.5 text-zinc-500" />
                      {activeDownload.eta}
                    </span>
                  )}
                </span>
              </div>
            </div>
          </div>

          {/* Barra de progreso fluida estilo iOS */}
          <div className="w-full h-2 rounded-full bg-white/10 overflow-hidden relative mt-2.5">
            <div 
              className={`h-full rounded-full transition-all duration-300 ease-out ${
                activeDownload.stage === 'done' 
                  ? 'bg-gradient-to-r from-emerald-500 to-teal-400' 
                  : 'bg-gradient-to-r from-rose-600 via-pink-500 to-amber-400'
              }`}
              style={{ width: `${activeDownload.percent || 0}%` }}
            />
          </div>

          {/* Botones de acción del HUD: Ver cola completa y consola */}
          <div className="flex items-center justify-between mt-2.5 pt-2 border-t border-white/5" onClick={(e) => e.stopPropagation()}>
            <button
              type="button"
              onClick={() => setShowQueueModal(true)}
              className="text-[11px] text-rose-300 hover:text-white flex items-center gap-1.5 py-1 px-2.5 rounded-xl bg-rose-500/15 hover:bg-rose-500/25 border border-rose-500/25 active:scale-95 transition-all apple-press font-medium"
            >
              <ListMusic className="w-3.5 h-3.5 text-rose-400" />
              <span>Ver cola ({downloadQueue.length})</span>
            </button>

            <button
              type="button"
              onClick={() => setShowTerminal(!showTerminal)}
              className="text-[10px] text-zinc-400 hover:text-white flex items-center gap-1 py-1 px-2 rounded-lg bg-white/5 active:scale-95 transition-all"
            >
              <TerminalIcon className="w-3 h-3 text-emerald-400" />
              <span>{showTerminal ? 'Ocultar consola' : 'Consola'}</span>
              {showTerminal ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
            </button>
          </div>

          {/* Consola técnica opcional SIN auto-scroll que mueva la ventana */}
          {showTerminal && (
            <div 
              ref={terminalContainerRef}
              className="mt-2 p-2.5 rounded-2xl bg-black/90 border border-white/10 font-mono text-[10px] text-emerald-400/90 max-h-36 overflow-y-auto space-y-1 select-text scrollbar-thin shadow-inner animate-fade-in"
            >
              <div className="flex items-center gap-1.5 pb-1 mb-1 border-b border-white/10 text-[9px] text-zinc-500">
                <span className="w-2 h-2 rounded-full bg-red-500/80"></span>
                <span className="w-2 h-2 rounded-full bg-yellow-500/80"></span>
                <span className="w-2 h-2 rounded-full bg-green-500/80"></span>
                <span className="ml-1 text-zinc-400">yt-dlp engine output</span>
              </div>
              {terminalLines.map((line, idx) => (
                <div key={idx} className="break-all leading-tight opacity-90">
                  {line}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* VISTAS SEGÚN TAB ACTIVO */}
      <main className="flex-1 px-4 pt-3">
        
        {/* --- TAB 1: EXPLORAR / DESCARGAR --- */}
        {activeTab === 'explore' && (
          <div className="space-y-3.5">
            
            {/* Aviso no intrusivo si el usuario intenta buscar o descargar sin conexión */}
            {offlineAttemptNotice && (
              <div className="p-3 rounded-2xl bg-amber-500/15 border border-amber-500/30 text-amber-200 text-xs flex items-center justify-between gap-2 shadow-lg animate-fade-in">
                <div className="flex items-center gap-2 min-w-0">
                  <AlertCircle className="w-4 h-4 text-amber-400 shrink-0" />
                  <span className="truncate">Sin internet para buscar o descargar canciones nuevas.</span>
                </div>
                <button
                  type="button"
                  onClick={() => setActiveTab('library')}
                  className="px-2.5 py-1 rounded-xl bg-amber-500/25 hover:bg-amber-500/35 text-amber-200 text-[11px] font-semibold shrink-0"
                >
                  Ver local
                </button>
              </div>
            )}

            {/* Input de búsqueda o enlace con botón Pegar y Limpiar */}
            <form onSubmit={handleSearch} className="relative flex items-center gap-2">
              <div className="relative flex-1">
                <input
                  type="text"
                  value={query}
                  onChange={handleQueryChange}
                  onPaste={handlePaste}
                  placeholder="Busca canción, artista o pega un enlace..."
                  className="w-full py-3.5 pl-11 pr-20 rounded-2xl bg-zinc-900/90 border border-white/10 text-sm text-white placeholder-zinc-500 focus:outline-none focus:border-rose-500/50 focus:ring-2 focus:ring-rose-500/20 shadow-inner"
                />
                <Search className="w-5 h-5 text-zinc-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                <div className="absolute right-2.5 top-1/2 -translate-y-1/2 flex items-center gap-1">
                  {query && (
                    <button
                      type="button"
                      onClick={() => setQuery('')}
                      className="p-1 rounded-full text-zinc-400 hover:text-white bg-white/10"
                      title="Limpiar búsqueda"
                    >
                      <X className="w-3.5 h-3.5" />
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={handlePasteFromClipboard}
                    className="p-1.5 rounded-xl text-zinc-400 hover:text-rose-400 bg-white/5 active:scale-90 border border-white/5"
                    title="Pegar enlace del portapapeles"
                  >
                    <ClipboardPaste className="w-4 h-4" />
                  </button>
                </div>

                {/* Panel de sugerencias predictivas estilo Apple Glass */}
                {showSuggestions && searchSuggestions.length > 0 && (
                  <div className="absolute top-full left-0 right-0 mt-1.5 z-40 apple-glass-heavy rounded-2xl border border-white/10 shadow-2xl overflow-hidden py-1">
                    {searchSuggestions.map((sug, idx) => (
                      <div
                        key={idx}
                        onClick={() => {
                          setQuery(sug);
                          setShowSuggestions(false);
                          executeSearchQuery(sug);
                        }}
                        className="px-3.5 py-2.5 text-xs text-zinc-300 hover:text-white hover:bg-white/10 flex items-center space-x-2.5 cursor-pointer transition-colors"
                      >
                        <Search className="w-3.5 h-3.5 text-zinc-500 shrink-0" />
                        <span className="truncate">{sug}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <button
                type="submit"
                disabled={isSearching}
                className="px-4 py-3.5 rounded-2xl bg-rose-500 hover:bg-rose-600 active:scale-95 text-xs font-semibold text-white shadow-md shadow-rose-500/25 transition-all shrink-0 flex items-center justify-center min-w-[70px]"
              >
                {isSearching ? <RefreshCw className="w-4 h-4 animate-spin" /> : 'Buscar'}
              </button>
            </form>

            {/* Chips de exploración rápida */}
            <div className="flex items-center gap-2 overflow-x-auto pb-1 -mx-4 px-4 no-scrollbar scroll-smooth">
              {[
                { label: 'Tendencias', q: 'Top Éxitos' },
                { label: 'Pop', q: 'Pop Hits' },
                { label: 'Rock', q: 'Rock Clásico' },
                { label: 'Lo-Fi', q: 'Lo-Fi Beats' },
                { label: 'Electrónica', q: 'Electronic Dance' },
                { label: 'Reggaeton', q: 'Reggaeton Hits' },
                { label: 'Acústico', q: 'Acoustic' }
              ].map(chip => (
                <button
                  key={chip.label}
                  type="button"
                  onClick={() => {
                    setQuery(chip.q);
                    executeSearchQuery(chip.q);
                  }}
                  className="px-3.5 py-1.5 rounded-full bg-white/5 hover:bg-white/10 border border-white/10 text-xs text-zinc-300 font-medium whitespace-nowrap active:scale-95 transition-all apple-press"
                >
                  {chip.label}
                </button>
              ))}
            </div>

            {/* Estado de detección inteligente de enlace */}
            {urlResolvingStatus && (
              <div className="flex items-center gap-2.5 px-4 py-2.5 rounded-2xl bg-rose-500/10 border border-rose-500/20 text-xs text-rose-300 animate-pulse">
                <RefreshCw className="w-4 h-4 animate-spin text-rose-400 shrink-0" />
                <span className="font-medium">{urlResolvingStatus}</span>
              </div>
            )}

            {/* Acceso rápido a Formato de Descarga */}
            <div className="flex items-center justify-between px-1 text-xs text-zinc-400">
              <span className="text-[11px] font-medium">Formato de descarga</span>
              <button
                type="button"
                onClick={() => setActiveTab('settings')}
                className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-white/5 hover:bg-white/10 border border-white/10 text-[11px] text-zinc-300 font-medium active:scale-95 transition-all apple-press"
                title="Configurar en Ajustes"
              >
                <SlidersHorizontal className="w-3 h-3 text-rose-400" />
                <span className="text-white font-semibold">{CALIDADES.find(c => c.id === selectedQuality)?.nombre}</span>
                <span className="text-[9px] font-mono text-zinc-400 bg-white/5 px-1 py-0.5 rounded">
                  {CALIDADES.find(c => c.id === selectedQuality)?.badge}
                </span>
              </button>
            </div>

            {/* Resultados de Búsqueda */}
            {searchResults.length > 0 && (
              <div className="space-y-2 pt-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <h3 className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
                      Temas Oficiales ({searchResults.length})
                    </h3>
                    {(queuedDownloadsCount > 0 || activeDownload) && (
                      <button
                        onClick={() => setShowQueueModal(true)}
                        className="px-2 py-0.5 rounded-full bg-rose-500/20 text-rose-300 border border-rose-500/30 text-[10px] font-semibold flex items-center gap-1 apple-press"
                      >
                        <ListMusic className="w-2.5 h-2.5" />
                        <span>Cola ({queuedDownloadsCount + (activeDownload ? 1 : 0)})</span>
                      </button>
                    )}
                  </div>
                  <button
                    onClick={handleDownloadAllResults}
                    className="px-2.5 py-1 rounded-xl bg-white/10 hover:bg-white/15 text-zinc-200 border border-white/10 text-[11px] font-semibold flex items-center gap-1.5 apple-press transition-all"
                    title="Descargar todos los temas que aún no tienes"
                  >
                    <Download className="w-3 h-3 text-rose-400" />
                    <span>Descargar todas</span>
                  </button>
                </div>
                <div className="space-y-2">
                  {searchResults.map((item) => {
                    const localMatch = findMatchingTrack(item, libraryTracks);
                    const queueEntry = downloadQueue.find(q => 
                      q.id === item.id || 
                      (q.item?.url && item.url && q.item.url === item.url) ||
                      (q.item?.id && item.id && q.item.id === item.id) ||
                      (q.item?.title && item.title && q.item.title.toLowerCase() === item.title.toLowerCase())
                    );
                    const isItemDownloading = queueEntry && queueEntry.status === 'downloading';
                    const isItemQueued = queueEntry && queueEntry.status === 'queued';
                    return (
                      <div
                        key={item.id}
                        className={`p-3 rounded-2xl apple-card flex items-center justify-between gap-3 border transition-all ${
                          localMatch 
                            ? 'border-emerald-500/30 bg-emerald-950/10' 
                            : isItemDownloading
                              ? 'border-rose-500/40 bg-rose-950/15'
                              : isItemQueued
                                ? 'border-amber-500/30 bg-amber-950/10'
                                : 'border-white/5 hover:border-white/10'
                        }`}
                      >
                        <div className="flex items-center gap-3 min-w-0">
                          <div className="relative group shrink-0">
                            <img 
                              src={item.cover} 
                              alt="Cover"
                              className="w-12 h-12 rounded-xl object-cover shadow-md bg-zinc-800"
                              loading="lazy"
                            />
                            {item.previewUrl && (
                              <button
                                onClick={(e) => togglePreview(item, e)}
                                className="absolute inset-0 bg-black/40 rounded-xl flex items-center justify-center opacity-90 active:scale-95"
                                title="Escuchar muestra de 30 segundos"
                              >
                                {previewTrackId === item.id ? (
                                  <Pause className="w-4 h-4 text-white fill-current" />
                                ) : (
                                  <Play className="w-4 h-4 text-white fill-current ml-0.5" />
                                )}
                              </button>
                            )}
                          </div>
                          <div className="min-w-0">
                            <h4 className="text-sm font-semibold text-white truncate">
                              {item.title}
                            </h4>
                            <p className="text-xs text-zinc-400 truncate">
                              {item.artist} {item.album ? `• ${item.album}` : ''}
                            </p>
                            <div className="flex items-center gap-2 mt-0.5">
                              <span className="text-[11px] font-mono text-zinc-500 flex items-center gap-1">
                                <Clock className="w-2.5 h-2.5 text-zinc-500" /> {item.duration_str}
                              </span>
                              {localMatch && (
                                <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 flex items-center gap-1 border border-emerald-500/30">
                                  <CheckCircle2 className="w-2.5 h-2.5" /> En tu dispositivo
                                </span>
                              )}
                              {!localMatch && isItemQueued && (
                                <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-amber-500/20 text-amber-300 flex items-center gap-1 border border-amber-500/30">
                                  <Clock className="w-2.5 h-2.5" /> En cola
                                </span>
                              )}
                              {!localMatch && isItemDownloading && (
                                <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-rose-500/20 text-rose-300 flex items-center gap-1 border border-rose-500/30">
                                  <RefreshCw className="w-2.5 h-2.5 animate-spin" /> Descargando ({queueEntry.percent}%)
                                </span>
                              )}
                            </div>
                          </div>
                        </div>

                        {localMatch ? (
                          <div className="flex items-center gap-1.5 shrink-0">
                            <button
                              onClick={() => playTrack(localMatch)}
                              className="px-3 py-2 rounded-xl bg-emerald-500/25 hover:bg-emerald-500/35 text-emerald-300 border border-emerald-500/40 text-xs font-semibold flex items-center gap-1.5 apple-press shadow-sm"
                              title="Reproducir desde tu teléfono"
                            >
                              <Play className="w-3.5 h-3.5 fill-current" />
                              <span>Oír</span>
                            </button>
                            <button
                              onClick={() => handleDownloadItem(item)}
                              className="p-2 rounded-xl bg-white/5 hover:bg-white/10 text-zinc-400 hover:text-white border border-white/5 apple-press"
                              title="Volver a descargar"
                            >
                              <Download className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        ) : isItemDownloading ? (
                          <button
                            onClick={() => setShowQueueModal(true)}
                            className="px-3 py-2 rounded-xl bg-rose-500/25 border border-rose-500/40 text-rose-300 text-xs font-semibold flex items-center gap-1.5 shrink-0 apple-press animate-pulse"
                            title="Ver en cola de descargas"
                          >
                            <RefreshCw className="w-3.5 h-3.5 animate-spin text-rose-400" />
                            <span>{queueEntry.percent}%</span>
                          </button>
                        ) : isItemQueued ? (
                          <button
                            onClick={() => setShowQueueModal(true)}
                            className="px-3 py-2 rounded-xl bg-amber-500/20 border border-amber-500/30 text-amber-300 text-xs font-semibold flex items-center gap-1.5 shrink-0 apple-press"
                            title="Ver cola de descargas"
                          >
                            <Clock className="w-3.5 h-3.5" />
                            <span>En cola</span>
                          </button>
                        ) : (
                          <button
                            onClick={() => handleDownloadItem(item)}
                            className="px-3.5 py-2 rounded-xl bg-white/10 hover:bg-rose-500 hover:text-white text-zinc-200 text-xs font-semibold flex items-center gap-1.5 apple-press shrink-0 border border-white/10"
                          >
                            <Download className="w-3.5 h-3.5" />
                            <span>Bajar</span>
                          </button>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Estado inicial / sugerencias de búsqueda y recientes */}
            {searchResults.length === 0 && !isSearching && (
              <div className="py-6 space-y-5">
                {/* Historial rápido de búsquedas */}
                {historyList && historyList.length > 0 && (
                  <div className="space-y-2">
                    <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider px-1">
                      Búsquedas Recientes
                    </span>
                    <div className="flex flex-wrap gap-2">
                      {historyList.slice(0, 6).map((item, idx) => (
                        <button
                          key={idx}
                          type="button"
                          onClick={() => {
                            setQuery(item);
                            executeSearchQuery(item);
                          }}
                          className="px-3 py-1.5 rounded-xl bg-zinc-900/80 hover:bg-zinc-800 border border-white/10 text-xs text-zinc-300 flex items-center gap-1.5 active:scale-95 transition-all apple-press truncate max-w-xs"
                        >
                          <History className="w-3 h-3 text-zinc-500" />
                          <span className="truncate">{item}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {/* Estado sin conexión en Explorar */}
                {!isOnline && (
                  <div className="p-4 rounded-3xl apple-glass border border-amber-500/20 bg-amber-500/5 space-y-2.5 text-center animate-fade-in">
                    <div className="w-10 h-10 mx-auto rounded-2xl bg-amber-500/15 text-amber-400 flex items-center justify-center">
                      <WifiOff className="w-5 h-5" />
                    </div>
                    <div>
                      <h4 className="text-xs font-bold text-white">Búsqueda en línea en pausa</h4>
                      <p className="text-[11px] text-zinc-400 max-w-xs mx-auto mt-0.5 leading-relaxed">
                        Conéctate a una red Wi-Fi o datos móviles para buscar o descargar música. Tu colección guardada sigue 100% disponible.
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => setActiveTab('library')}
                      className="px-4 py-2 rounded-xl bg-white/10 hover:bg-white/15 active:scale-95 text-white text-xs font-medium border border-white/10 inline-flex items-center gap-1.5 transition-all apple-press"
                    >
                      <Music className="w-3.5 h-3.5 text-rose-400" />
                      <span>Escuchar música local ({libraryTracks.length})</span>
                    </button>
                  </div>
                )}

                {/* Tarjeta de bienvenida y características Apple Music */}
                <div className="p-5 rounded-3xl apple-card border border-white/10 space-y-3 text-center">
                  <div className="w-14 h-14 mx-auto rounded-2xl bg-gradient-to-tr from-rose-600 to-pink-500 flex items-center justify-center shadow-lg shadow-rose-600/30 text-white">
                    <Sparkles className="w-7 h-7" />
                  </div>
                  <div>
                    <h3 className="text-sm font-bold text-white">Música 100% en tu Teléfono</h3>
                    <p className="text-xs text-zinc-400 max-w-xs mx-auto mt-1 leading-relaxed">
                      Busca cualquier tema o pega un link de YouTube. Las canciones se descargan con calidad de máster, carátula HD 1200px y letras sincronizadas karaoke.
                    </p>
                  </div>
                  <div className="grid grid-cols-3 gap-2 pt-2 border-t border-white/5 text-[11px] text-zinc-400">
                    <div className="p-2 rounded-xl bg-white/5 flex flex-col items-center gap-1.5 text-center">
                      <Music className="w-4 h-4 text-rose-400" />
                      <span className="font-semibold text-white">Opus Nativo</span>
                      <span className="text-[9px] text-zinc-500">Sin recodificar</span>
                    </div>
                    <div className="p-2 rounded-xl bg-white/5 flex flex-col items-center gap-1.5 text-center">
                      <Disc className="w-4 h-4 text-rose-400" />
                      <span className="font-semibold text-white">Carátula HD</span>
                      <span className="text-[9px] text-zinc-500">Apple 1200px</span>
                    </div>
                    <div className="p-2 rounded-xl bg-white/5 flex flex-col items-center gap-1.5 text-center">
                      <Mic2 className="w-4 h-4 text-rose-400" />
                      <span className="font-semibold text-white">Karaoke</span>
                      <span className="text-[9px] text-zinc-500">Letras LRC</span>
                    </div>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* --- TAB 2: BIBLIOTECA & REPRODUCTOR --- */}
        {activeTab === 'library' && (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-2">
              <div className="min-w-0">
                <h2 className="text-lg font-bold text-white tracking-tight">Tu Música en el Dispositivo</h2>
                <p className="text-xs text-zinc-400 truncate">
                  {filteredLibraryTracks.length} {librarySearchQuery ? 'encontradas' : 'canciones reconocidas'} • {totalLibrarySizeMb > 1024 ? (totalLibrarySizeMb / 1024).toFixed(1) + ' GB' : totalLibrarySizeMb + ' MB'}
                </p>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <button 
                  onClick={loadLibraryData}
                  disabled={isScanningLibrary || isSyncingCovers}
                  className="p-2 rounded-xl bg-zinc-900 border border-white/10 text-zinc-300 hover:text-white flex items-center justify-center active:scale-95 shadow-sm apple-press"
                  title="Re-escanear biblioteca"
                >
                  <RefreshCw className={`w-3.5 h-3.5 text-zinc-400 ${isScanningLibrary ? 'animate-spin text-rose-400' : ''}`} />
                </button>
              </div>
            </div>

            {/* Indicador sutil de sincronización en curso (gestión en Ajustes) */}
            {isSyncingCovers && (
              <div 
                onClick={() => setActiveTab('settings')}
                className="p-2.5 rounded-2xl apple-glass border border-rose-500/30 flex items-center justify-between text-xs text-rose-300 cursor-pointer animate-fade-in apple-press"
              >
                <div className="flex items-center gap-2 truncate">
                  <Sparkles className="w-3.5 h-3.5 text-rose-400 animate-spin shrink-0" />
                  <span className="truncate">{syncProgress.artist ? `${syncProgress.artist} • ${syncProgress.item}` : 'Sincronizando carátulas...'}</span>
                </div>
                <span className="font-mono text-[11px] text-rose-400 shrink-0 ml-2">
                  {syncProgress.percent}% • Ver en Ajustes
                </span>
              </div>
            )}

            {/* Buscador de canciones en la biblioteca local */}
            <div className="relative">
              <Search className="w-4 h-4 text-zinc-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
              <input
                type="text"
                value={librarySearchQuery}
                onChange={(e) => setLibrarySearchQuery(e.target.value)}
                placeholder="Buscar por canción, artista o carpeta..."
                className="w-full py-2.5 pl-10 pr-20 rounded-xl bg-zinc-900/80 border border-white/10 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-rose-500/50"
              />
              {librarySearchQuery && (
                <button
                  onClick={() => setLibrarySearchQuery('')}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-[11px] text-zinc-400 hover:text-white px-1.5 py-0.5 rounded bg-white/10"
                >
                  Limpiar
                </button>
              )}
            </div>

            {/* Filtros rápidos: Todo / Favoritos / Listas de Reproducción */}
            <div className="flex items-center gap-1.5 overflow-x-auto pb-1 -mx-4 px-4 scroll-smooth no-scrollbar">
              <button
                onClick={() => setSelectedPlaylistFilter('all')}
                className={`px-3 py-1.5 rounded-xl text-xs font-semibold shrink-0 transition-all apple-press ${
                  selectedPlaylistFilter === 'all'
                    ? 'bg-white text-black shadow-md'
                    : 'bg-white/5 hover:bg-white/10 text-zinc-300 border border-white/10'
                }`}
              >
                Todas ({libraryTracks.length})
              </button>

              <button
                onClick={() => setSelectedPlaylistFilter(selectedPlaylistFilter === 'favorites' ? 'all' : 'favorites')}
                className={`px-3 py-1.5 rounded-xl text-xs font-semibold shrink-0 transition-all apple-press flex items-center gap-1.5 ${
                  selectedPlaylistFilter === 'favorites'
                    ? 'bg-rose-500 text-white shadow-md shadow-rose-500/30'
                    : 'bg-white/5 hover:bg-white/10 text-zinc-300 border border-white/10'
                }`}
              >
                <Heart className={`w-3.5 h-3.5 ${selectedPlaylistFilter === 'favorites' ? 'fill-current' : 'text-rose-400'}`} />
                <span>Favoritos ({favorites.length})</span>
              </button>

              <button
                onClick={() => {
                  setPlaylistModalTrack(null);
                  setShowPlaylistModal(true);
                }}
                className="px-3 py-1.5 rounded-xl text-xs font-semibold shrink-0 transition-all apple-press flex items-center gap-1.5 bg-white/5 hover:bg-white/10 text-zinc-300 border border-white/10"
              >
                <ListMusic className="w-3.5 h-3.5 text-zinc-400" />
                <span>Listas ({playlists.length})</span>
              </button>

              {playlists.map((pl) => (
                <button
                  key={pl.id}
                  onClick={() => setSelectedPlaylistFilter(selectedPlaylistFilter === pl.id ? 'all' : pl.id)}
                  className={`px-3 py-1.5 rounded-xl text-xs font-medium shrink-0 transition-all apple-press flex items-center gap-1 ${
                    selectedPlaylistFilter === pl.id
                      ? 'bg-rose-500 text-white shadow-md shadow-rose-500/30'
                      : 'bg-white/5 hover:bg-white/10 text-zinc-300 border border-white/10'
                  }`}
                >
                  <ListMusic className="w-3 h-3" />
                  <span>{pl.name} ({pl.tracks?.length || 0})</span>
                </button>
              ))}
            </div>

            {/* Chips deslizables de artistas estilo Apple Glass */}
            {artistStats.length > 1 && (
              <div className="flex items-center gap-1.5 overflow-x-auto pb-1 -mx-4 px-4 scroll-smooth no-scrollbar">
                <button
                  onClick={() => setSelectedArtistFilter('ALL')}
                  className={`px-3 py-1.5 rounded-xl text-xs font-semibold shrink-0 transition-all apple-press ${
                    selectedArtistFilter === 'ALL'
                      ? 'bg-rose-500 text-white shadow-md shadow-rose-500/30'
                      : 'bg-white/5 hover:bg-white/10 text-zinc-300 border border-white/10'
                  }`}
                >
                  Todos ({libraryTracks.length})
                </button>
                {artistStats.map(({ name, count }) => (
                  <button
                    key={name}
                    onClick={() => setSelectedArtistFilter(selectedArtistFilter === name ? 'ALL' : name)}
                    className={`px-3 py-1.5 rounded-xl text-xs font-medium shrink-0 transition-all apple-press ${
                      selectedArtistFilter === name
                        ? 'bg-rose-500 text-white shadow-md shadow-rose-500/30'
                        : 'bg-white/5 hover:bg-white/10 text-zinc-300 border border-white/10'
                    }`}
                  >
                    {name} ({count})
                  </button>
                ))}
              </div>
            )}

            {/* Barra de opciones y ordenamiento */}
            <div className="flex items-center justify-between text-xs px-1 text-zinc-400">
              <span className="text-[11px] truncate">
                {selectedArtistFilter !== 'ALL' ? `Filtrado por: ${selectedArtistFilter}` : `${filteredLibraryTracks.length} canciones`}
              </span>
              <div className="flex items-center gap-2 shrink-0">
                <button
                  onClick={() => setLibrarySortBy(prev => prev === 'title' ? 'artist' : prev === 'artist' ? 'recent' : 'title')}
                  className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-white/5 hover:bg-white/10 border border-white/10 text-[11px] text-zinc-300 active:scale-95 transition-all"
                  title="Cambiar criterio de orden"
                >
                  <Filter className="w-3 h-3 text-rose-400" />
                  <span>
                    {librarySortBy === 'title' ? 'A-Z Título' : librarySortBy === 'artist' ? 'A-Z Artista' : 'Recientes'}
                  </span>
                </button>
              </div>
            </div>

            {filteredLibraryTracks.length === 0 ? (
              <div className="py-16 text-center text-zinc-500">
                <Music className="w-10 h-10 mx-auto mb-2 text-zinc-600" />
                <p className="text-sm">
                  {librarySearchQuery ? `No se encontraron resultados para "${librarySearchQuery}"` : 'No se encontraron canciones en el teléfono'}
                </p>
                <p className="text-xs text-zinc-600 mt-1">
                  Pulsa "Escanear" para actualizar la música de tu dispositivo
                </p>
              </div>
            ) : (
              <div className="space-y-2">
                {filteredLibraryTracks.map((track, i) => {
                  const isCurrent = currentTrack && currentTrack.path === track.path;
                  return (
                    <div
                      key={track.path || i}
                      onClick={() => playTrack(track, filteredLibraryTracks)}
                      className={`p-3 rounded-2xl border flex items-center justify-between gap-3 cursor-pointer apple-press transition-all ${
                        isCurrent 
                          ? 'bg-rose-500/20 border-rose-500/40 shadow-sm' 
                          : 'bg-zinc-900/60 border-white/5 hover:border-white/10'
                      }`}
                    >
                      <div className="flex items-center gap-3 min-w-0">
                        <div className="w-10 h-10 rounded-xl overflow-hidden bg-zinc-800 shrink-0 relative flex items-center justify-center">
                          {track.cover ? (
                            <img src={track.cover} alt="" className="w-full h-full object-cover" loading="lazy" decoding="async" />
                          ) : (
                            <div className={`w-full h-full flex items-center justify-center ${
                              isCurrent ? 'bg-rose-500 text-white shadow-md shadow-rose-500/40' : 'bg-zinc-800 text-zinc-400'
                            }`}>
                              {isCurrent && isPlaying ? (
                                <Pause className="w-5 h-5 fill-current" />
                              ) : (
                                <Play className="w-5 h-5 fill-current ml-0.5" />
                              )}
                            </div>
                          )}
                          {isCurrent && isPlaying && (
                            <div className="absolute inset-0 bg-black/55 flex items-center justify-center gap-0.5 pointer-events-none">
                              <span className="w-1 bg-white rounded-full animate-music-bar-1" />
                              <span className="w-1 bg-white rounded-full animate-music-bar-2" />
                              <span className="w-1 bg-white rounded-full animate-music-bar-3" />
                            </div>
                          )}
                        </div>
                        <div className="min-w-0">
                          <h4 className="text-sm font-semibold text-white truncate">{track.title}</h4>
                          <p className="text-xs text-zinc-400 truncate flex items-center gap-1.5">
                            <span>{track.artist || 'Artista'} • {track.folder || 'Música'}</span>
                            {track.hasLyrics && (
                              <span className="text-[9px] font-semibold px-1.5 py-0.5 rounded bg-rose-500/20 text-rose-300 border border-rose-500/30 flex items-center gap-0.5" title="Letras sincronizadas disponibles">
                                <Mic2 className="w-2.5 h-2.5" /> Letra
                              </span>
                            )}
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center gap-1 shrink-0">
                        <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-white/5 text-zinc-400 uppercase">
                          {track.ext ? track.ext.replace('.', '') : 'mp3'}
                        </span>
                        
                        {/* Botón de Favorito */}
                        <button
                          onClick={(e) => toggleFavorite(track, e)}
                          className="p-1.5 rounded-lg active:scale-90 transition-transform"
                          title={favorites.includes(track.path) ? 'Quitar de favoritos' : 'Marcar como favorito'}
                        >
                          <Heart className={`w-4 h-4 transition-colors ${favorites.includes(track.path) ? 'text-rose-500 fill-rose-500' : 'text-zinc-500 hover:text-zinc-300'}`} />
                        </button>

                        {/* Menú de opciones */}
                        <div className="relative">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setTrackMenuOpen(trackMenuOpen === track.path ? null : track.path);
                            }}
                            className="p-1.5 rounded-lg text-zinc-400 hover:text-white active:scale-90 transition-colors"
                            title="Más opciones"
                          >
                            <MoreVertical className="w-4 h-4" />
                          </button>

                          {trackMenuOpen === track.path && (
                            <div 
                              className="absolute right-0 top-full mt-1 z-30 w-44 py-1.5 apple-glass-heavy rounded-2xl border border-white/10 shadow-2xl space-y-0.5 animate-fade-in"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <button
                                onClick={() => {
                                  setTrackMenuOpen(null);
                                  setCoverPickerTrack(track);
                                  setShowCoverPicker(true);
                                }}
                                className="w-full text-left px-3 py-2 text-xs text-zinc-300 hover:text-white hover:bg-white/10 flex items-center space-x-2 transition-colors"
                              >
                                <Sparkles className="w-3.5 h-3.5 text-rose-400" />
                                <span>Cambiar carátula</span>
                              </button>

                              <button
                                onClick={() => {
                                  setTrackMenuOpen(null);
                                  setPlaylistModalTrack(track);
                                  setShowPlaylistModal(true);
                                }}
                                className="w-full text-left px-3 py-2 text-xs text-zinc-300 hover:text-white hover:bg-white/10 flex items-center space-x-2 transition-colors"
                              >
                                <ListPlus className="w-3.5 h-3.5 text-zinc-400" />
                                <span>Agregar a lista...</span>
                              </button>

                              <button
                                onClick={(e) => {
                                  setTrackMenuOpen(null);
                                  handleDeleteTrack(track, e);
                                }}
                                className="w-full text-left px-3 py-2 text-xs text-rose-400 hover:bg-rose-500/10 flex items-center space-x-2 transition-colors"
                              >
                                <Trash2 className="w-3.5 h-3.5" />
                                <span>Eliminar</span>
                              </button>
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}

        {/* --- TAB 3: HISTORIAL --- */}
        {activeTab === 'history' && (
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-lg font-bold text-white tracking-tight">Historial de Descargas</h2>
                <p className="text-xs text-zinc-400">{historyList.length} canciones registradas</p>
              </div>
              <button 
                onClick={loadLibraryData}
                className="p-2 rounded-xl bg-zinc-900 border border-white/10 text-zinc-400 active:scale-95"
              >
                <RefreshCw className="w-4 h-4" />
              </button>
            </div>

            {historyList.length === 0 ? (
              <div className="py-16 text-center text-zinc-500">
                <History className="w-10 h-10 mx-auto mb-2 text-zinc-600" />
                <p className="text-sm">El historial está vacío</p>
              </div>
            ) : (
              <div className="space-y-2">
                {historyList.map((item, idx) => (
                  <div 
                    key={idx}
                    className="p-3 rounded-2xl bg-zinc-900/60 border border-white/5 flex items-center justify-between text-xs"
                  >
                    <span className="font-mono text-zinc-300 truncate max-w-[80%]">
                      {item}
                    </span>
                    <button
                      onClick={async () => {
                        await apiDeleteHistory(item);
                        loadLibraryData();
                      }}
                      className="p-1.5 rounded-lg text-zinc-500 hover:text-rose-400 active:scale-90"
                      title="Eliminar del historial"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* --- TAB 4: AJUSTES (APPLE STYLE) --- */}
        {activeTab === 'settings' && (
          <div className="space-y-5 pb-8">
            <div>
              <h2 className="text-lg font-bold text-white tracking-tight">Ajustes</h2>
              <p className="text-xs text-zinc-400">Configuración del motor de descargas nativo</p>
            </div>

            {/* Tarjeta de Información del Motor Nativo */}
            <div className="space-y-2">
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider px-1">
                Motor de Ejecución
              </span>
              <div className="p-4 rounded-2xl apple-card border border-white/10 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Cpu className="w-4 h-4 text-rose-400" />
                    <span className="text-xs font-semibold text-white">Motor Nativo Embebido</span>
                  </div>
                  <span className="text-[11px] font-medium px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400">
                    100% Autónomo
                  </span>
                </div>

                <div className="space-y-1.5 text-xs text-zinc-400">
                  <p>• <span className="text-zinc-200">Versión yt-dlp:</span> <span className="font-mono text-[11px] text-zinc-300">{engineInfo.version || 'Cargando motor...'}</span></p>
                  <p>• <span className="text-zinc-200">Tecnología:</span> Python + FFmpeg compilados en ARM64</p>
                  <p>• <span className="text-zinc-200">Almacenamiento:</span> <span className="font-mono text-[11px] text-zinc-300">/sdcard/Music/</span></p>
                  <p>• <span className="text-zinc-200">SponsorBlock:</span> Activado automáticamente</p>
                  <p>• <span className="text-zinc-200">Carátulas:</span> 1200×1200 px HD incrustadas</p>
                </div>

                <button
                  onClick={handleUpdateEngine}
                  disabled={isUpdatingEngine}
                  className="w-full py-2 px-3 rounded-xl bg-white/5 hover:bg-white/10 text-zinc-300 text-xs font-medium flex items-center justify-center gap-2 apple-press border border-white/10 transition-all disabled:opacity-50"
                >
                  <RefreshCw className={`w-3.5 h-3.5 text-zinc-400 ${isUpdatingEngine ? 'animate-spin' : ''}`} />
                  <span>{isUpdatingEngine ? 'Actualizando motor yt-dlp...' : 'Actualizar motor yt-dlp a última versión'}</span>
                </button>
              </div>
            </div>

            {/* Tarjeta de Cuenta y Autenticación de YouTube (Solución Anti-Bot) */}
            <div className="space-y-2">
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider px-1">
                Cuenta y Autenticación
              </span>
              <div className="p-4 rounded-2xl apple-card border border-white/10 space-y-3.5">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <ShieldCheck className="w-4 h-4 text-rose-400" />
                    <span className="text-xs font-semibold text-white">Sesión de YouTube (Cookies)</span>
                  </div>
                  {cookieInfo.hasCookies ? (
                    <span className="text-[11px] font-semibold px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 flex items-center gap-1">
                      <Check className="w-3 h-3" /> Conectada ({Math.round((cookieInfo.size || 0) / 1024 * 10) / 10} KB)
                    </span>
                  ) : (
                    <span className="text-[11px] font-medium px-2 py-0.5 rounded-full bg-amber-500/20 text-amber-300">
                      Sin cuenta
                    </span>
                  )}
                </div>

                <p className="text-xs text-zinc-400 leading-relaxed">
                  {cookieInfo.hasCookies
                    ? 'Tu sesión está autenticada. YouTube permite descargas ilimitadas sin desafíos anti-bot ni bloqueos de red.'
                    : 'Si tu red Wi-Fi descarga muchas canciones, YouTube puede activar su protección anti-bot temporal. Inicia sesión con tu cuenta de Google o pega tus cookies para descargar siempre al 100%.'}
                </p>

                <div className="flex flex-col gap-2 pt-1">
                  <button
                    onClick={handleOpenLogin}
                    disabled={isAuthenticating}
                    className="w-full py-2.5 px-3 rounded-xl bg-gradient-to-r from-rose-600 to-pink-600 hover:from-rose-500 hover:to-pink-500 text-white text-xs font-semibold flex items-center justify-center gap-2 apple-press shadow-md transition-all disabled:opacity-50"
                  >
                    <UserCheck className="w-4 h-4" />
                    <span>{cookieInfo.hasCookies ? 'Renovar Sesión de YouTube' : 'Iniciar Sesión en YouTube'}</span>
                  </button>

                  <div className="flex gap-2">
                    <button
                      onClick={() => setShowCookieModal(true)}
                      className="flex-1 py-2 px-3 rounded-xl bg-white/5 hover:bg-white/10 text-zinc-300 text-xs font-medium flex items-center justify-center gap-1.5 apple-press border border-white/10 transition-all"
                    >
                      <FileText className="w-3.5 h-3.5 text-zinc-400" />
                      <span>Pegar cookies.txt</span>
                    </button>
                    {cookieInfo.hasCookies && (
                      <button
                        onClick={handleClearCookies}
                        className="py-2 px-3 rounded-xl bg-rose-500/10 hover:bg-rose-500/20 text-rose-300 text-xs font-medium flex items-center justify-center gap-1.5 apple-press border border-rose-500/20 transition-all"
                      >
                        <Trash2 className="w-3.5 h-3.5 text-rose-400" />
                        <span>Desconectar</span>
                      </button>
                    )}
                  </div>
                </div>
              </div>
            </div>

            {/* Tarjeta de Reconocimiento y Ubicación de Música del Dispositivo */}
            <div className="space-y-2">
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider px-1">
                Ubicación de Música y Biblioteca
              </span>
              <div className="p-4 rounded-2xl apple-card border border-white/10 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Folder className="w-4 h-4 text-emerald-400" />
                    <span className="text-xs font-semibold text-white">Carpetas y Filtros</span>
                  </div>
                  <span className="text-[11px] font-mono text-emerald-400 font-semibold px-2 py-0.5 rounded-full bg-emerald-500/20">
                    {libraryTracks.length} canciones
                  </span>
                </div>
                <p className="text-xs text-zinc-400 leading-relaxed">
                  Configura en qué carpetas buscar canciones y dónde guardar las nuevas descargas. Excluye automáticamente audios de WhatsApp, notas de voz y llamadas grabadas.
                </p>
                <div className="flex flex-col gap-2 pt-1">
                  <button
                    onClick={() => setShowFolderModal(true)}
                    className="w-full py-2.5 px-3 rounded-xl bg-rose-500/10 hover:bg-rose-500/20 text-rose-300 text-xs font-semibold flex items-center justify-center gap-2 apple-press border border-rose-500/30 transition-all shadow-sm"
                  >
                    <Folder className="w-3.5 h-3.5 text-rose-400" />
                    <span>Elegir ubicación de música...</span>
                  </button>
                  <button
                    onClick={loadLibraryData}
                    disabled={isScanningLibrary}
                    className="w-full py-2.5 px-3 rounded-xl bg-white/10 hover:bg-white/15 text-white text-xs font-semibold flex items-center justify-center gap-2 apple-press border border-white/10 transition-all"
                  >
                    <RefreshCw className={`w-3.5 h-3.5 text-zinc-400 ${isScanningLibrary ? 'animate-spin text-rose-400' : ''}`} />
                    <span>{isScanningLibrary ? 'Escaneando biblioteca...' : 'Re-escanear biblioteca'}</span>
                  </button>
                </div>
              </div>
            </div>

            {/* Tarjeta de Sincronización de Carátulas Oficiales */}
            <div className="space-y-2">
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider px-1">
                Carátulas y Metadatos
              </span>
              <div className="p-4 rounded-2xl apple-card border border-white/10 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Sparkles className="w-4 h-4 text-rose-400" />
                    <span className="text-xs font-semibold text-white">Carátulas Oficiales HD</span>
                  </div>
                  <span className="text-[11px] font-mono text-rose-400 font-semibold px-2 py-0.5 rounded-full bg-rose-500/20">
                    1200×1200 px
                  </span>
                </div>
                <p className="text-xs text-zinc-400 leading-relaxed">
                  Busca e incrusta las carátulas oficiales de Apple Music y Deezer en máxima fidelidad para toda tu biblioteca. Se guardan con archivo .nomedia para no aparecer en tu galería de fotos.
                </p>

                {/* Banner de progreso si está sincronizando actualmente */}
                {isSyncingCovers && (
                  <div className="p-3.5 rounded-2xl apple-glass-heavy border border-rose-500/30 shadow-lg space-y-2.5 animate-fade-in">
                    <div className="flex items-center justify-between text-xs">
                      <div className="flex items-center gap-2">
                        <Sparkles className="w-4 h-4 text-rose-400 animate-spin" />
                        <span className="font-semibold text-white">Sincronizando carátulas oficiales...</span>
                      </div>
                      <button 
                        onClick={cancelSyncCovers}
                        className="text-[11px] text-zinc-400 hover:text-rose-400 px-2.5 py-0.5 rounded-lg bg-white/5 active:scale-95 transition-all"
                      >
                        Detener
                      </button>
                    </div>

                    <div className="flex items-center justify-between text-[11px] text-zinc-400">
                      <span className="truncate max-w-[70%] text-zinc-200 font-medium">
                        {syncProgress.artist ? `${syncProgress.artist} • ${syncProgress.item}` : 'Buscando másteres oficiales...'}
                      </span>
                      <span className="font-mono text-rose-400">
                        {syncProgress.current} / {syncProgress.total} ({syncProgress.percent}%)
                      </span>
                    </div>

                    <div className="w-full h-1.5 bg-white/10 rounded-full overflow-hidden">
                      <div 
                        className="h-full bg-gradient-to-r from-rose-600 to-pink-500 transition-all duration-300"
                        style={{ width: `${syncProgress.percent}%` }}
                      />
                    </div>
                  </div>
                )}

                {syncSuccessMsg && (
                  <div className="p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-300 text-xs flex items-center gap-2 animate-fade-in">
                    <Check className="w-4 h-4 text-emerald-400 shrink-0" />
                    <span>{syncSuccessMsg}</span>
                  </div>
                )}

                <button
                  onClick={handleSyncCovers}
                  disabled={isSyncingCovers || libraryTracks.length === 0}
                  className="w-full py-2.5 px-3 rounded-xl bg-gradient-to-r from-rose-600 to-pink-600 hover:from-rose-500 hover:to-pink-500 text-white text-xs font-semibold flex items-center justify-center gap-2 apple-press shadow-md transition-all disabled:opacity-50"
                >
                  <Sparkles className={`w-3.5 h-3.5 text-white ${isSyncingCovers ? 'animate-spin' : ''}`} />
                  <span>{isSyncingCovers ? 'Sincronizando carátulas...' : 'Sincronizar Todas las Carátulas'}</span>
                </button>
              </div>
            </div>

            {/* Calidad por defecto */}
            <div className="space-y-2">
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider px-1">
                Formato Preferido
              </span>
              <div className="rounded-2xl apple-card border border-white/10 divide-y divide-white/5 overflow-hidden">
                {CALIDADES.map(cal => (
                  <div 
                    key={cal.id} 
                    onClick={() => setSelectedQuality(cal.id)}
                    className="p-3.5 flex items-center justify-between cursor-pointer hover:bg-white/5"
                  >
                    <div>
                      <div className="flex items-center gap-2">
                        <h4 className="text-sm font-semibold text-white">{cal.nombre}</h4>
                        <span className="text-[10px] font-mono text-zinc-400 bg-white/5 px-1.5 py-0.5 rounded">
                          {cal.badge}
                        </span>
                      </div>
                      <p className="text-xs text-zinc-400">{cal.desc}</p>
                    </div>
                    {selectedQuality === cal.id && <Check className="w-4 h-4 text-rose-400" />}
                  </div>
                ))}
              </div>
            </div>

          </div>
        )}
      </main>

      {/* Elemento de Audio Nativo HTML5 */}
      <audio 
        ref={audioRef}
        src={currentTrack ? getStreamUrl(currentTrack.path) : undefined}
        onLoadedMetadata={() => {
          if (audioRef.current) {
            setAudioDuration(audioRef.current.duration);
            updateMediaSessionPosition(audioRef.current.currentTime, audioRef.current.duration, playbackRate);
          }
        }}
        onEnded={() => {
          if (sleepTimer && sleepTimer.mode === 'track') {
            setIsPlaying(false);
            setSleepTimer(null);
            return;
          }
          handleNextTrack();
        }}
      />

      {/* Mini-reproductor Flotante estilo Apple Music cuando hay canción activa */}
      {currentTrack && (
        <div 
          style={{ bottom: 'calc(var(--sab, 0px) + 4.8rem)' }}
          onTouchStart={(e) => {
            miniSwipeStartX.current = e.touches[0].clientX;
          }}
          onTouchEnd={(e) => {
            const diff = e.changedTouches[0].clientX - miniSwipeStartX.current;
            if (diff < -50) {
              if (window.navigator?.vibrate) window.navigator.vibrate(10);
              handleNextTrack();
            } else if (diff > 50) {
              if (window.navigator?.vibrate) window.navigator.vibrate(10);
              handlePrevTrack();
            }
          }}
          className="fixed left-4 right-4 z-50 rounded-2xl apple-glass-heavy border border-white/20 shadow-float overflow-hidden animate-fade-in transition-all select-none"
        >
          {/* Micro-barra de progreso desacoplada de 60 FPS sin re-renderizar la app */}
          <MiniProgressBar audioRef={audioRef} isPlaying={isPlaying} />

          <div className="p-2.5 flex items-center justify-between gap-3">
            <div 
              onClick={() => {
                setNowPlayingTab('artwork');
                setShowNowPlayingSheet(true);
              }}
              className="flex items-center gap-2.5 min-w-0 flex-1 cursor-pointer select-none"
            >
              <div className="w-11 h-11 rounded-xl overflow-hidden bg-zinc-800 shrink-0 shadow-md relative flex items-center justify-center">
                {currentTrack.cover ? (
                  <img src={currentTrack.cover} alt="" className="w-full h-full object-cover" />
                ) : (
                  <div className="w-full h-full bg-gradient-to-tr from-rose-600 to-pink-500 flex items-center justify-center text-white">
                    <Music className="w-5 h-5" />
                  </div>
                )}
                {isPlaying && (
                  <div className="absolute inset-0 bg-black/45 flex items-center justify-center gap-0.5 pointer-events-none">
                    <span className="w-1 bg-white rounded-full animate-music-bar-1" />
                    <span className="w-1 bg-white rounded-full animate-music-bar-2" />
                    <span className="w-1 bg-white rounded-full animate-music-bar-3" />
                  </div>
                )}
              </div>

              <div className="min-w-0 flex-1">
                <h4 className="text-xs font-semibold text-white truncate">{currentTrack.title}</h4>
                <p className="text-[10px] text-zinc-400 truncate flex items-center gap-1.5 mt-0.5">
                  <span className="truncate">{currentTrack.artist || 'Reproduciendo'}</span>
                  {trackLyrics && (
                    <span className="text-[9px] px-1 py-0.5 rounded bg-rose-500/20 text-rose-300 font-medium border border-rose-500/30 shrink-0 flex items-center gap-0.5">
                      <Mic2 className="w-2.5 h-2.5" /> Letra
                    </span>
                  )}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-1 shrink-0">
              <button
                onClick={(e) => toggleFavorite(currentTrack, e)}
                className="p-2 text-zinc-400 hover:text-white active:scale-90 transition-all"
                title={favorites.includes(currentTrack.path) ? 'Quitar de favoritos' : 'Marcar como favorito'}
              >
                <Heart className={`w-4 h-4 transition-colors ${favorites.includes(currentTrack.path) ? 'text-rose-500 fill-rose-500' : 'text-zinc-400'}`} />
              </button>

              <button
                onClick={() => {
                  setNowPlayingTab('lyrics');
                  setShowNowPlayingSheet(true);
                }}
                className={`p-2 rounded-xl border apple-press transition-all ${
                  trackLyrics 
                    ? 'bg-rose-500/20 border-rose-500/40 text-rose-300' 
                    : 'bg-white/5 border-white/10 text-zinc-400 hover:text-white'
                }`}
                title="Abrir letras karaoke"
              >
                <Mic2 className="w-4 h-4" />
              </button>

              <button
                onClick={handlePrevTrack}
                className="p-2 text-zinc-300 hover:text-white active:scale-90 transition-all"
                title="Pista anterior"
              >
                <SkipBack className="w-4 h-4 fill-current" />
              </button>

              <button
                onClick={handleTogglePlay}
                className="w-9 h-9 rounded-full bg-white text-black flex items-center justify-center active:scale-95 shadow-md"
                title={isPlaying ? 'Pausar' : 'Reproducir'}
              >
                {isPlaying ? (
                  <Pause className="w-4 h-4 fill-current" />
                ) : (
                  <Play className="w-4 h-4 fill-current ml-0.5" />
                )}
              </button>

              <button
                onClick={handleNextTrack}
                className="p-2 text-zinc-300 hover:text-white active:scale-90 transition-all"
                title="Siguiente pista"
              >
                <SkipForward className="w-4 h-4 fill-current" />
              </button>
            </div>
          </div>
        </div>
      )}

      {/* TAB BAR INFERIOR TRANSLÚCIDA ESTILO APPLE (iOS HIG) RESPETANDO ÁREA SEGURA */}
      <nav 
        style={{ paddingBottom: 'calc(var(--sab, 0px) + 0.5rem)' }}
        className="fixed bottom-0 left-0 right-0 z-40 apple-glass-heavy light-edge px-4 pt-2 flex items-center justify-around"
      >
        <button
          onClick={() => setActiveTab('explore')}
          className={`flex flex-col items-center gap-1 py-1 px-3 rounded-xl transition-all apple-press ${
            activeTab === 'explore' ? 'text-rose-500' : 'text-zinc-400 hover:text-zinc-200'
          }`}
        >
          <Search className="w-5 h-5" />
          <span className="text-[10px] font-medium">Explorar</span>
        </button>

        <button
          onClick={() => setActiveTab('library')}
          className={`flex flex-col items-center gap-1 py-1 px-3 rounded-xl transition-all apple-press ${
            activeTab === 'library' ? 'text-rose-500' : 'text-zinc-400 hover:text-zinc-200'
          }`}
        >
          <Music className="w-5 h-5" />
          <span className="text-[10px] font-medium">Biblioteca</span>
        </button>

        <button
          onClick={() => setActiveTab('history')}
          className={`flex flex-col items-center gap-1 py-1 px-3 rounded-xl transition-all apple-press ${
            activeTab === 'history' ? 'text-rose-500' : 'text-zinc-400 hover:text-zinc-200'
          }`}
        >
          <History className="w-5 h-5" />
          <span className="text-[10px] font-medium">Historial</span>
        </button>

        <button
          onClick={() => setActiveTab('settings')}
          className={`flex flex-col items-center gap-1 py-1 px-3 rounded-xl transition-all apple-press ${
            activeTab === 'settings' ? 'text-rose-500' : 'text-zinc-400 hover:text-zinc-200'
          }`}
        >
          <Settings className="w-5 h-5" />
          <span className="text-[10px] font-medium">Ajustes</span>
        </button>
      </nav>

      {/* Modal para Pegar cookies.txt estilo iOS */}
      {showCookieModal && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-md flex items-center justify-center p-4 animate-fade-in">
          <div className="w-full max-w-md bg-zinc-900 border border-white/15 rounded-3xl p-5 shadow-2xl space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <FileText className="w-4 h-4 text-rose-400" />
                <h3 className="text-sm font-bold text-white">Pegar cookies.txt de YouTube</h3>
              </div>
              <button
                onClick={() => setShowCookieModal(false)}
                className="p-1 rounded-full text-zinc-400 hover:text-white"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <p className="text-xs text-zinc-400 leading-normal">
              Pega aquí tus cookies en formato Netscape (exportadas desde extensiones como &quot;Get cookies.txt LOCALLY&quot; o generadas en tu navegador).
            </p>
            <textarea
              value={cookiesInputText}
              onChange={(e) => setCookiesInputText(e.target.value)}
              placeholder="# Netscape HTTP Cookie File&#10;.youtube.com&#9;TRUE&#9;/&#9;TRUE&#9;2147483647&#9;SID&#9;..."
              className="w-full h-44 p-3 rounded-2xl bg-zinc-950 border border-white/10 text-zinc-200 text-xs font-mono resize-none focus:outline-none focus:border-rose-500/50"
            />
            <div className="flex gap-2">
              <button
                onClick={() => setShowCookieModal(false)}
                className="flex-1 py-2.5 rounded-xl bg-white/5 hover:bg-white/10 text-zinc-300 text-xs font-semibold apple-press border border-white/10"
              >
                Cancelar
              </button>
              <button
                onClick={handleSaveCookiesPasted}
                disabled={!cookiesInputText.trim()}
                className="flex-1 py-2.5 rounded-xl bg-rose-600 hover:bg-rose-500 disabled:opacity-40 text-white text-xs font-semibold apple-press shadow-md transition-all"
              >
                Guardar Cookies
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Reproductor Now Playing Completo & Letras Sincronizadas estilo Apple Music iOS 18 */}
      {showNowPlayingSheet && currentTrack && (
        <NowPlayingSheet
          currentTrack={currentTrack}
          isPlaying={isPlaying}
          audioRef={audioRef}
          audioDuration={audioDuration}
          trackLyrics={trackLyrics}
          isLoadingLyrics={isLoadingLyrics}
          queue={queue}
          queueIndex={queueIndex}
          shuffle={shuffle}
          repeatMode={repeatMode}
          volume={volume}
          isMuted={isMuted}
          playbackRate={playbackRate}
          sleepTimer={sleepTimer}
          activeTab={nowPlayingTab}
          isFavorite={Boolean(currentTrack && favorites.includes(currentTrack.path))}
          onToggleFavorite={toggleFavorite}
          onTabChange={setNowPlayingTab}
          onClose={() => setShowNowPlayingSheet(false)}
          onTogglePlay={handleTogglePlay}
          onNext={handleNextTrack}
          onPrev={handlePrevTrack}
          onSeek={handleSeek}
          onSeekToLyric={handleSeekToLyric}
          onToggleShuffle={handleToggleShuffle}
          onCycleRepeat={handleCycleRepeat}
          onVolumeChange={handleVolumeChange}
          onToggleMute={handleToggleMute}
          onChangePlaybackRate={handleChangePlaybackRate}
          onSetSleepTimer={handleSetSleepTimer}
          onSelectTrackFromQueue={handleSelectTrackFromQueue}
          onRemoveTrackFromQueue={handleRemoveTrackFromQueue}
          onClearQueue={handleClearQueue}
          onReloadLyrics={() => loadLyricsForTrack(currentTrack)}
          formatDuration={formatDuration}
        />
      )}

      {/* Modal de selección visual de carátulas oficiales en HD */}
      <CoverPickerModal
        track={coverPickerTrack}
        isOpen={showCoverPicker}
        onClose={() => {
          setShowCoverPicker(false);
          setCoverPickerTrack(null);
        }}
        onCoverUpdated={handleCoverUpdated}
      />

      {/* Modal de gestión de Listas de Reproducción */}
      <PlaylistModal
        isOpen={showPlaylistModal}
        onClose={() => {
          setShowPlaylistModal(false);
          setPlaylistModalTrack(null);
        }}
        playlists={playlists}
        onCreatePlaylist={handleCreatePlaylist}
        onAddToPlaylist={handleAddToPlaylist}
        onPlayPlaylist={handlePlayPlaylist}
        onDeletePlaylist={handleDeletePlaylist}
        trackToAdd={playlistModalTrack}
      />

      {/* Modal de selección de ubicación de música y filtros de almacenamiento */}
      <FolderPickerModal
        isOpen={showFolderModal}
        onClose={() => setShowFolderModal(false)}
        onConfigSaved={() => {
          loadLibraryData();
        }}
      />

      {/* Modal de gestión de Cola de Descargas Múltiples */}
      <DownloadQueueModal
        isOpen={showQueueModal}
        onClose={() => setShowQueueModal(false)}
        queue={downloadQueue}
        onRemoveItem={handleRemoveQueueItem}
        onClearCompleted={handleClearCompletedQueue}
        onRetryItem={handleRetryQueueItem}
        onPlayTrack={(item) => playTrack(findMatchingTrack(item, libraryTracks) || item)}
        terminalLines={terminalLines}
        showTerminal={showTerminal}
        onToggleTerminal={() => setShowTerminal(!showTerminal)}
        terminalContainerRef={terminalContainerRef}
      />

    </div>
  );
}
