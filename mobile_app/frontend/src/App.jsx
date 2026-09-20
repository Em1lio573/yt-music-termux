import React, { useState, useEffect, useRef } from 'react';
import { 
  Search, Download, Music, Settings, History, Check, Play, Pause, 
  RefreshCw, Folder, Trash2, ShieldCheck, Sparkles, Sliders, ExternalLink,
  Volume2, AlertCircle, ArrowDownCircle, HardDrive, Radio
} from 'lucide-react';

const CALIDADES = [
  { id: 'native', nombre: 'Original Nativo', badge: 'Opus ~160k', desc: 'Máster real sin pérdida de compresión', icon: '🎵' },
  { id: 'm4a', nombre: 'M4A / AAC', badge: '256 kbps', desc: 'Estándar de alta fidelidad universal', icon: '🎧' },
  { id: 'mp3_320', nombre: 'MP3 320k', badge: '320 kbps CBR', desc: 'Máxima compatibilidad con reproductores', icon: '💿' },
  { id: 'mp3_v0', nombre: 'MP3 V0', badge: 'VBR ~245k', desc: 'Balance perfecto entre tamaño y calidad', icon: '⚡' },
  { id: 'flac', nombre: 'FLAC Lossless', badge: 'Sin pérdida', desc: 'Audio sin compresión destructiva', icon: '🎼' }
];

export default function App() {
  const [activeTab, setActiveTab] = useState('explore'); // explore, library, history, settings
  const [query, setQuery] = useState('');
  const [urlInput, setUrlInput] = useState('');
  const [selectedQuality, setSelectedQuality] = useState('native');
  const [searchResults, setSearchResults] = useState([]);
  const [isSearching, setIsSearching] = useState(false);
  const [config, setConfig] = useState(null);
  
  // Estado de descarga activa
  const [downloadProgress, setDownloadProgress] = useState(null);
  const [downloadSuccessMessage, setDownloadSuccessMessage] = useState('');
  
  // Biblioteca y Reproductor
  const [libraryTracks, setLibraryTracks] = useState([]);
  const [historyList, setHistoryList] = useState([]);
  const [currentTrack, setCurrentTrack] = useState(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [audioDuration, setAudioDuration] = useState(0);
  const [audioCurrentTime, setAudioCurrentTime] = useState(0);
  const audioRef = useRef(null);

  // Cargar configuración e historial al montar
  useEffect(() => {
    fetchConfig();
    fetchLibrary();
    fetchHistory();

    // Escuchar eventos SSE para progreso de descarga en vivo
    const eventSource = new EventSource('/api/progress');
    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (data.status === 'downloading') {
          setDownloadProgress({
            percent: data.percent || '0',
            speed: data.speed || '',
            eta: data.eta || '',
            title: data.title || 'Descargando pista...'
          });
        } else if (data.status === 'processing') {
          setDownloadProgress(prev => ({
            ...prev,
            statusText: data.message || 'Procesando carátula y metadatos...'
          }));
        } else if (data.status === 'completed') {
          setDownloadProgress(null);
          setDownloadSuccessMessage(data.title ? `✔ ¡Descargado! ${data.title}` : '✔ ¡Descarga completada!');
          setTimeout(() => setDownloadSuccessMessage(''), 5000);
          fetchLibrary();
          fetchHistory();
        } else if (data.status === 'error') {
          setDownloadProgress(null);
          alert(data.message || 'Error en la descarga');
        }
      } catch (err) {
        console.error('Error procesando SSE:', err);
      }
    };

    return () => eventSource.close();
  }, []);

  const fetchConfig = async () => {
    try {
      const res = await fetch('/api/config');
      const data = await res.json();
      setConfig(data);
      if (data.default_quality) {
        setSelectedQuality(data.default_quality);
      }
    } catch (err) {
      console.error('Error cargando config:', err);
    }
  };

  const updateConfig = async (newCfg) => {
    setConfig(newCfg);
    try {
      await fetch('/api/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newCfg)
      });
    } catch (err) {
      console.error('Error guardando config:', err);
    }
  };

  const fetchLibrary = async () => {
    try {
      const res = await fetch('/api/library');
      const data = await res.json();
      setLibraryTracks(data.tracks || []);
    } catch (err) {
      console.error('Error cargando biblioteca:', err);
    }
  };

  const fetchHistory = async () => {
    try {
      const res = await fetch('/api/history');
      const data = await res.json();
      setHistoryList(data.history || []);
    } catch (err) {
      console.error('Error cargando historial:', err);
    }
  };

  // Buscar en YouTube Music / iTunes
  const handleSearch = async (e) => {
    if (e) e.preventDefault();
    const term = query.trim() || urlInput.trim();
    if (!term) return;

    // Si es una URL directa, iniciar descarga directa
    if (term.startsWith('http://') || term.startsWith('https://')) {
      handleDownloadUrl(term);
      return;
    }

    setIsSearching(true);
    try {
      const res = await fetch(`/api/search?q=${encodeURIComponent(term)}`);
      const data = await res.json();
      setSearchResults(data.results || []);
    } catch (err) {
      console.error('Error en búsqueda:', err);
    } finally {
      setIsSearching(false);
    }
  };

  // Iniciar descarga por URL o tema
  const handleDownloadUrl = async (url, explicitTitle = null) => {
    setDownloadProgress({
      percent: '0',
      speed: 'Iniciando...',
      eta: '--',
      title: explicitTitle || 'Conectando con YouTube Music...'
    });

    try {
      const res = await fetch('/api/download', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          url: url,
          quality: selectedQuality
        })
      });
      const data = await res.json();
      if (!data.success) {
        setDownloadProgress(null);
        alert(data.message || 'Error al iniciar descarga');
      }
    } catch (err) {
      setDownloadProgress(null);
      alert('Error de conexión con el motor de descargas');
    }
  };

  // Control de reproducción de audio integrado
  const playTrack = (track) => {
    if (currentTrack && currentTrack.path === track.path) {
      if (isPlaying) {
        audioRef.current.pause();
        setIsPlaying(false);
      } else {
        audioRef.current.play();
        setIsPlaying(true);
      }
    } else {
      setCurrentTrack(track);
      setIsPlaying(true);
      setTimeout(() => {
        if (audioRef.current) {
          audioRef.current.play();
        }
      }, 50);
    }
  };

  return (
    <div className="flex flex-col min-h-screen bg-black text-white font-sf pb-24 select-none">
      
      {/* Barra de estado y Top Bar translúcida estilo iOS */}
      <header className="sticky top-0 z-40 apple-glass light-edge px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-rose-600 to-pink-500 flex items-center justify-center shadow-lg shadow-rose-600/30">
            <Music className="w-4 h-4 text-white" />
          </div>
          <div>
            <h1 className="text-sm font-semibold tracking-tight text-white flex items-center gap-1.5">
              YouTube Music Pro
              <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-rose-500/20 text-rose-400 font-medium border border-rose-500/30">
                Termux
              </span>
            </h1>
            <p className="text-[11px] text-zinc-400">Audio de estudio sin recodificación</p>
          </div>
        </div>

        <div className="flex items-center gap-2 text-xs text-zinc-400">
          <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
          <span className="font-mono text-[11px]">Local Engine</span>
        </div>
      </header>

      {/* Banner de descarga exitosa */}
      {downloadSuccessMessage && (
        <div className="mx-4 mt-3 p-3 rounded-2xl bg-emerald-500/15 border border-emerald-500/30 text-emerald-300 text-xs flex items-center gap-2 shadow-lg animate-fade-in">
          <Check className="w-4 h-4 text-emerald-400 shrink-0" />
          <span className="font-medium truncate">{downloadSuccessMessage}</span>
        </div>
      )}

      {/* Tarjeta de descarga activa flotante con micro-progreso */}
      {downloadProgress && (
        <div className="mx-4 mt-3 p-3.5 rounded-2xl apple-glass border border-white/15 shadow-float flex flex-col gap-2 animate-pulse-slow">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-white truncate max-w-[70%]">
              {downloadProgress.title}
            </span>
            <span className="font-mono font-bold text-rose-400">
              {downloadProgress.percent}%
            </span>
          </div>

          <div className="w-full h-2 rounded-full bg-zinc-800 overflow-hidden relative">
            <div 
              className="h-full bg-gradient-to-r from-rose-500 to-pink-500 rounded-full transition-all duration-300 ease-out"
              style={{ width: `${downloadProgress.percent}%` }}
            />
          </div>

          <div className="flex items-center justify-between text-[11px] text-zinc-400">
            <span>{downloadProgress.statusText || 'Descargando audio oficial...'}</span>
            <span>Vel: {downloadProgress.speed} | ETA: {downloadProgress.eta}</span>
          </div>
        </div>
      )}

      {/* VISTAS SEGÚN TAB ACTIVO */}
      <main className="flex-1 px-4 pt-3">
        
        {/* --- TAB 1: EXPLORAR / DESCARGAR --- */}
        {activeTab === 'explore' && (
          <div className="space-y-4">
            
            {/* Input de búsqueda o enlace */}
            <form onSubmit={handleSearch} className="relative">
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Busca canción, artista o pega un enlace de YouTube..."
                className="w-full py-3.5 pl-11 pr-24 rounded-2xl bg-zinc-900/90 border border-white/10 text-sm text-white placeholder-zinc-500 focus:outline-none focus:border-rose-500/50 focus:ring-2 focus:ring-rose-500/20 shadow-inner"
              />
              <Search className="w-5 h-5 text-zinc-400 absolute left-3.5 top-3.5" />
              <button
                type="submit"
                disabled={isSearching}
                className="absolute right-2 top-2 px-3.5 py-1.5 rounded-xl bg-rose-500 hover:bg-rose-600 active:scale-95 text-xs font-semibold text-white shadow-md shadow-rose-500/20 transition-all"
              >
                {isSearching ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : 'Buscar'}
              </button>
            </form>

            {/* Selector de Calidad Real estilo Chips de Apple */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
                  Calidad de Descarga
                </span>
                <span className="text-[11px] text-rose-400 font-medium">
                  {CALIDADES.find(c => c.id === selectedQuality)?.desc}
                </span>
              </div>
              <div className="grid grid-cols-2 gap-2">
                {CALIDADES.map(cal => {
                  const isSelected = selectedQuality === cal.id;
                  return (
                    <button
                      key={cal.id}
                      onClick={() => setSelectedQuality(cal.id)}
                      className={`p-2.5 rounded-2xl border text-left transition-all apple-press ${
                        isSelected 
                          ? 'bg-rose-500/15 border-rose-500/50 shadow-lg shadow-rose-500/10' 
                          : 'bg-zinc-900/60 border-white/5 hover:border-white/10'
                      }`}
                    >
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-xs font-semibold text-white flex items-center gap-1.5">
                          <span>{cal.icon}</span> {cal.nombre}
                        </span>
                        {isSelected && <Check className="w-3.5 h-3.5 text-rose-400" />}
                      </div>
                      <span className="text-[10px] font-mono px-1.5 py-0.5 rounded-md bg-white/5 text-zinc-300">
                        {cal.badge}
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Resultados de Búsqueda */}
            {searchResults.length > 0 && (
              <div className="space-y-2 pt-2">
                <h3 className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
                  Temas de Estudio Oficiales
                </h3>
                <div className="space-y-2">
                  {searchResults.map((item, idx) => (
                    <div
                      key={idx}
                      className="p-3 rounded-2xl apple-card flex items-center justify-between gap-3 border border-white/5 hover:border-white/10 transition-all"
                    >
                      <div className="flex items-center gap-3 min-w-0">
                        <img 
                          src={item.cover || 'https://music.youtube.com/img/favicon_144.png'} 
                          alt="Cover"
                          className="w-12 h-12 rounded-xl object-cover shadow-md shrink-0 bg-zinc-800"
                        />
                        <div className="min-w-0">
                          <h4 className="text-sm font-semibold text-white truncate">
                            {item.title}
                          </h4>
                          <p className="text-xs text-zinc-400 truncate">
                            {item.artist} {item.album ? `• ${item.album}` : ''}
                          </p>
                          <span className="text-[11px] font-mono text-zinc-500">
                            ⏱ {item.duration_str || 'Oficial'}
                          </span>
                        </div>
                      </div>

                      <button
                        onClick={() => handleDownloadUrl(item.url, `${item.artist} - ${item.title}`)}
                        className="px-3 py-2 rounded-xl bg-white/10 hover:bg-rose-500 hover:text-white text-zinc-200 text-xs font-semibold flex items-center gap-1.5 apple-press shrink-0 border border-white/10"
                      >
                        <Download className="w-3.5 h-3.5" />
                        <span>Bajar</span>
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Placeholder vacío cuando no hay búsqueda */}
            {searchResults.length === 0 && (
              <div className="py-12 flex flex-col items-center justify-center text-center text-zinc-500">
                <div className="w-16 h-16 rounded-full bg-zinc-900 border border-white/5 flex items-center justify-center mb-3">
                  <Sparkles className="w-7 h-7 text-rose-400/80" />
                </div>
                <h3 className="text-sm font-semibold text-zinc-300">Descargas en Alta Fidelidad</h3>
                <p className="text-xs text-zinc-500 max-w-xs mt-1">
                  Pega un enlace de YouTube o busca un tema para descargar la canción 100% completa con metadatos oficiales de estudio.
                </p>
              </div>
            )}
          </div>
        )}

        {/* --- TAB 2: BIBLIOTECA & REPRODUCTOR --- */}
        {activeTab === 'library' && (
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-lg font-bold text-white tracking-tight">Tu Biblioteca</h2>
                <p className="text-xs text-zinc-400">{libraryTracks.length} canciones descargadas</p>
              </div>
              <button 
                onClick={fetchLibrary}
                className="p-2 rounded-xl bg-zinc-900 border border-white/10 text-zinc-400 active:scale-95"
              >
                <RefreshCw className="w-4 h-4" />
              </button>
            </div>

            {libraryTracks.length === 0 ? (
              <div className="py-16 text-center text-zinc-500">
                <Music className="w-10 h-10 mx-auto mb-2 text-zinc-600" />
                <p className="text-sm">Aún no hay canciones descargadas</p>
              </div>
            ) : (
              <div className="space-y-2">
                {libraryTracks.map((track, i) => {
                  const isCurrent = currentTrack && currentTrack.path === track.path;
                  return (
                    <div
                      key={i}
                      onClick={() => playTrack(track)}
                      className={`p-3 rounded-2xl border flex items-center justify-between gap-3 cursor-pointer apple-press transition-all ${
                        isCurrent 
                          ? 'bg-rose-500/20 border-rose-500/40' 
                          : 'bg-zinc-900/60 border-white/5 hover:border-white/10'
                      }`}
                    >
                      <div className="flex items-center gap-3 min-w-0">
                        <div className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${
                          isCurrent ? 'bg-rose-500 text-white shadow-md shadow-rose-500/40' : 'bg-zinc-800 text-zinc-400'
                        }`}>
                          {isCurrent && isPlaying ? (
                            <Pause className="w-5 h-5 fill-current" />
                          ) : (
                            <Play className="w-5 h-5 fill-current ml-0.5" />
                          )}
                        </div>
                        <div className="min-w-0">
                          <h4 className="text-sm font-semibold text-white truncate">{track.title}</h4>
                          <p className="text-xs text-zinc-400 truncate">{track.artist || 'Artista'} • {track.folder}</p>
                        </div>
                      </div>
                      <span className="text-[11px] font-mono text-zinc-500 shrink-0">
                        {track.size_mb} MB
                      </span>
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
                <p className="text-xs text-zinc-400">{historyList.length} descargas registradas</p>
              </div>
              <button 
                onClick={fetchHistory}
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
                        await fetch(`/api/history/${encodeURIComponent(item)}`, { method: 'DELETE' });
                        fetchHistory();
                      }}
                      className="p-1.5 rounded-lg text-zinc-500 hover:text-rose-400 active:scale-90"
                      title="Eliminar para permitir re-descargar"
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
        {activeTab === 'settings' && config && (
          <div className="space-y-5 pb-8">
            <div>
              <h2 className="text-lg font-bold text-white tracking-tight">Ajustes</h2>
              <p className="text-xs text-zinc-400">Preferencias del motor de descargas en Termux</p>
            </div>

            {/* Grupo de opciones: Motor de Audio */}
            <div className="space-y-2">
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider px-1">
                Motor de Audio
              </span>
              <div className="rounded-2xl apple-card border border-white/10 divide-y divide-white/5 overflow-hidden">
                <div className="p-3.5 flex items-center justify-between">
                  <div>
                    <h4 className="text-sm font-semibold text-white">Prioridad YouTube Music</h4>
                    <p className="text-xs text-zinc-400">Descarga máster de estudio en lugar de videoclips</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={config.prefer_ytmusic}
                    onChange={(e) => updateConfig({ ...config, prefer_ytmusic: e.target.checked })}
                    className="w-5 h-5 accent-rose-500 rounded cursor-pointer"
                  />
                </div>

                <div className="p-3.5 flex items-center justify-between">
                  <div>
                    <h4 className="text-sm font-semibold text-white">Base de Datos de Duración (iTunes)</h4>
                    <p className="text-xs text-zinc-400">Compara duración real para filtrar intros y sketches</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={config.verify_duration}
                    onChange={(e) => updateConfig({ ...config, verify_duration: e.target.checked })}
                    className="w-5 h-5 accent-rose-500 rounded cursor-pointer"
                  />
                </div>

                <div className="p-3.5 flex items-center justify-between">
                  <div>
                    <h4 className="text-sm font-semibold text-white">SponsorBlock (Non-Music Cut)</h4>
                    <p className="text-xs text-zinc-400">Recorta partes habladas en videos musicales</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={config.sponsorblock_offtopic}
                    onChange={(e) => updateConfig({ ...config, sponsorblock_offtopic: e.target.checked })}
                    className="w-5 h-5 accent-rose-500 rounded cursor-pointer"
                  />
                </div>

                <div className="p-3.5 flex items-center justify-between">
                  <div>
                    <h4 className="text-sm font-semibold text-white">Limpiar Títulos</h4>
                    <p className="text-xs text-zinc-400">Remueve etiquetas como "(Official Video)"</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={config.clean_titles}
                    onChange={(e) => updateConfig({ ...config, clean_titles: e.target.checked })}
                    className="w-5 h-5 accent-rose-500 rounded cursor-pointer"
                  />
                </div>
              </div>
            </div>

            {/* Ubicación de Almacenamiento */}
            <div className="space-y-2">
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider px-1">
                Ubicación de Almacenamiento
              </span>
              <div className="p-3.5 rounded-2xl apple-card border border-white/10 space-y-2">
                <div className="flex items-center gap-2 text-xs font-semibold text-zinc-300">
                  <Folder className="w-4 h-4 text-rose-400" />
                  <span>Carpeta de Biblioteca</span>
                </div>
                <input
                  type="text"
                  value={config.music_directory}
                  onChange={(e) => updateConfig({ ...config, music_directory: e.target.value })}
                  className="w-full p-2.5 rounded-xl bg-zinc-900 border border-white/10 text-xs font-mono text-zinc-200 focus:outline-none focus:border-rose-500"
                />
              </div>
            </div>

            {/* Integración con Android */}
            <div className="space-y-2">
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider px-1">
                Integración de Sistema Android
              </span>
              <div className="rounded-2xl apple-card border border-white/10 divide-y divide-white/5 overflow-hidden">
                <div className="p-3.5 flex items-center justify-between">
                  <div>
                    <h4 className="text-sm font-semibold text-white">Indexación Automática</h4>
                    <p className="text-xs text-zinc-400">termux-media-scan para reproductores locales</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={config.media_scan}
                    onChange={(e) => updateConfig({ ...config, media_scan: e.target.checked })}
                    className="w-5 h-5 accent-rose-500 rounded cursor-pointer"
                  />
                </div>

                <div className="p-3.5 flex items-center justify-between">
                  <div>
                    <h4 className="text-sm font-semibold text-white">Notificaciones del Sistema</h4>
                    <p className="text-xs text-zinc-400">termux-notification al completar descargas</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={config.notifications}
                    onChange={(e) => updateConfig({ ...config, notifications: e.target.checked })}
                    className="w-5 h-5 accent-rose-500 rounded cursor-pointer"
                  />
                </div>
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Mini-reproductor Flotante estilo iOS cuando hay canción sonando */}
      {currentTrack && (
        <div className="fixed bottom-20 left-4 right-4 z-50 p-2.5 rounded-2xl apple-glass-heavy border border-white/20 shadow-float flex items-center justify-between gap-3 animate-fade-in">
          <audio 
            ref={audioRef}
            src={`/api/stream?path=${encodeURIComponent(currentTrack.path)}`}
            onTimeUpdate={() => setAudioCurrentTime(audioRef.current ? audioRef.current.currentTime : 0)}
            onLoadedMetadata={() => setAudioDuration(audioRef.current ? audioRef.current.duration : 0)}
            onEnded={() => setIsPlaying(false)}
          />
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-rose-600 to-pink-500 flex items-center justify-center text-white shrink-0 shadow-md">
              <Music className="w-5 h-5" />
            </div>
            <div className="min-w-0">
              <h4 className="text-xs font-semibold text-white truncate">{currentTrack.title}</h4>
              <p className="text-[10px] text-zinc-400 truncate">{currentTrack.artist || 'Reproduciendo en móvil'}</p>
            </div>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              onClick={() => {
                if (isPlaying) {
                  audioRef.current.pause();
                  setIsPlaying(false);
                } else {
                  audioRef.current.play();
                  setIsPlaying(true);
                }
              }}
              className="w-8 h-8 rounded-full bg-white text-black flex items-center justify-center active:scale-95 shadow-md"
            >
              {isPlaying ? <Pause className="w-4 h-4 fill-current" /> : <Play className="w-4 h-4 fill-current ml-0.5" />}
            </button>
          </div>
        </div>
      )}

      {/* TAB BAR INFERIOR TRANSLÚCIDA ESTILO APPLE (iOS HIG) */}
      <nav className="fixed bottom-0 left-0 right-0 z-40 apple-glass-heavy light-edge px-4 py-2 flex items-center justify-around">
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

    </div>
  );
}
