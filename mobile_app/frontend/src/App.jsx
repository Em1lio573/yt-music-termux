import React, { useState, useEffect, useRef, useMemo } from 'react';
import { 
  Search, Download, Music, Settings, History, Check, Play, Pause, 
  RefreshCw, Folder, Trash2, ShieldCheck, Sparkles, Sliders, ExternalLink,
  Volume2, AlertCircle, ArrowDownCircle, HardDrive, Radio, Terminal as TerminalIcon,
  ChevronDown, ChevronUp, Cpu, Smartphone, Disc, Filter, CheckCircle2
} from 'lucide-react';
import {
  isNativeApp,
  getEngineStatus,
  getStoredServerUrl,
  setStoredServerUrl,
  testServerConnection,
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
  requestAudioPermissions
} from './apiClient';

const CALIDADES = [
  { id: 'native', nombre: 'Opus Nativo', badge: '~160 kbps VBR', desc: 'Máster oficial sin recodificar (Máxima fidelidad real de YouTube Music)', icon: '🎵', recommended: true },
  { id: 'm4a', nombre: 'M4A / AAC', badge: '~128 kbps', desc: 'Nativo sin recodificar (Universal Apple, Android y estéreos modernos)', icon: '🎧' },
  { id: 'm4a_256', nombre: 'M4A Premium', badge: '256 kbps', desc: 'Audio de alta fidelidad (Requiere cuenta YouTube Music Premium)', icon: '✨' },
  { id: 'mp3_320', nombre: 'MP3 320k', badge: 'Transcodificado', desc: 'Para estéreos de auto antiguos que no leen Opus ni M4A', icon: '💿' },
  { id: 'data_saver', nombre: 'Ahorro de Datos', badge: 'Opus ~70k', desc: 'Mínimo consumo de espacio de almacenamiento en el teléfono', icon: '⚡' }
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
  
  // Estado de descarga activa y terminal en vivo
  const [downloadProgress, setDownloadProgress] = useState(null);
  const [downloadSuccessMessage, setDownloadSuccessMessage] = useState('');
  const [terminalLines, setTerminalLines] = useState([]);
  const [showTerminal, setShowTerminal] = useState(false);
  const terminalEndRef = useRef(null);
  
  // Biblioteca y Reproductor
  const [libraryTracks, setLibraryTracks] = useState([]);
  const [historyList, setHistoryList] = useState([]);
  const [currentTrack, setCurrentTrack] = useState(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [audioDuration, setAudioDuration] = useState(0);
  const [audioCurrentTime, setAudioCurrentTime] = useState(0);
  const audioRef = useRef(null);

  // Reproductor de vista previa (30s) para resultados de búsqueda
  const [previewTrackId, setPreviewTrackId] = useState(null);
  const previewAudioRef = useRef(new Audio());

  // Inicialización
  useEffect(() => {
    initEngine();
    loadLibraryData();

    // Escuchar eventos de progreso y terminal en vivo
    const unsubscribe = subscribeDownloadEvents(
      (data) => {
        if (data.percent !== undefined) {
          setDownloadProgress(prev => ({
            ...prev,
            percent: data.percent,
            eta: data.eta || '--',
            speed: data.speed || '',
            statusText: data.statusText || 'Procesando descarga...'
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

  // Auto-scroll para la terminal en vivo
  useEffect(() => {
    if (showTerminal && terminalEndRef.current) {
      terminalEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [terminalLines, showTerminal]);

  const initEngine = async () => {
    const status = await getEngineStatus();
    setEngineInfo(status);
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

  // Filtrado en vivo de biblioteca local
  const filteredLibraryTracks = useMemo(() => {
    if (!librarySearchQuery.trim()) return libraryTracks;
    const q = librarySearchQuery.toLowerCase().trim();
    return libraryTracks.filter(t => 
      (t.title && t.title.toLowerCase().includes(q)) ||
      (t.artist && t.artist.toLowerCase().includes(q)) ||
      (t.album && t.album.toLowerCase().includes(q)) ||
      (t.folder && t.folder.toLowerCase().includes(q))
    );
  }, [libraryTracks, librarySearchQuery]);

  // Almacenamiento total calculado
  const totalLibrarySizeMb = useMemo(() => {
    const sum = libraryTracks.reduce((acc, t) => acc + (Number(t.size_mb) || 0), 0);
    return Math.round(sum * 10) / 10;
  }, [libraryTracks]);

  // Buscar canciones (directo en iTunes API)
  const handleSearch = async (e) => {
    if (e) e.preventDefault();
    const term = query.trim();
    if (!term) return;

    if (term.startsWith('http://') || term.startsWith('https://')) {
      handleDownloadItem({ url: term, title: 'Enlace directo de YouTube', artist: '' });
      return;
    }

    setIsSearching(true);
    if (previewAudioRef.current) {
      previewAudioRef.current.pause();
      setPreviewTrackId(null);
    }

    try {
      const results = await searchMusic(term);
      setSearchResults(results);
    } catch (err) {
      console.error('Error buscando:', err);
    } finally {
      setIsSearching(false);
    }
  };

  // Reproducir o pausar vista previa de 30 segundos
  const togglePreview = (item, e) => {
    e.stopPropagation();
    if (!item.previewUrl) return;

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

  // Iniciar descarga autónoma en el teléfono
  const handleDownloadItem = async (item) => {
    const songTitle = item.artist ? `${item.artist} - ${item.title}` : (item.title || 'Iniciando...');
    
    setDownloadProgress({
      percent: 0,
      speed: 'Iniciando motor...',
      eta: '--',
      title: songTitle,
      statusText: 'Preparando descarga en alta fidelidad...'
    });
    setTerminalLines([
      `[Motor] Iniciando descarga local autónoma`,
      `[Tema] ${songTitle}`,
      `[Calidad] ${selectedQuality.toUpperCase()}`
    ]);
    setShowTerminal(true);

    try {
      const res = await requestDownload(item, selectedQuality);
      addHistoryEntry(songTitle);

      setDownloadProgress(null);
      setDownloadSuccessMessage(`✔ ¡Descargado! ${songTitle}`);
      setTimeout(() => setDownloadSuccessMessage(''), 6000);
      loadLibraryData();

      setTerminalLines(prev => [
        ...prev,
        `[Sistema] ✔ Archivo guardado y registrado en el reproductor de Android`
      ]);
    } catch (err) {
      setDownloadProgress(null);
      setTerminalLines(prev => [...prev, `[Error] ${err.message}`]);
      alert('Error en descarga: ' + err.message);
    }
  };

  // Control de reproducción de biblioteca local
  const playTrack = (track) => {
    if (previewAudioRef.current) {
      previewAudioRef.current.pause();
      setPreviewTrackId(null);
    }

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
          audioRef.current.play().catch(console.warn);
        }
      }, 50);
    }
  };

  return (
    <div 
      style={{ paddingBottom: 'calc(var(--sab, 0px) + 6.5rem)' }}
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
          {engineInfo.isNative ? (
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

      {/* Banner de descarga exitosa */}
      {downloadSuccessMessage && (
        <div className="mx-4 mt-3 p-3 rounded-2xl bg-emerald-500/15 border border-emerald-500/30 text-emerald-300 text-xs flex items-center gap-2 shadow-lg animate-fade-in">
          <Check className="w-4 h-4 text-emerald-400 shrink-0" />
          <span className="font-medium truncate">{downloadSuccessMessage}</span>
        </div>
      )}

      {/* Tarjeta de progreso de descarga activa estilo Apple */}
      {downloadProgress && (
        <div className="mx-4 mt-3 p-4 rounded-2xl apple-card border border-rose-500/30 shadow-xl shadow-rose-500/10 space-y-2.5 animate-fade-in">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold text-white flex items-center gap-2 truncate">
              <RefreshCw className="w-3.5 h-3.5 text-rose-400 animate-spin shrink-0" />
              <span className="truncate">{downloadProgress.title}</span>
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
            <span>{downloadProgress.statusText || 'Descargando audio oficial de estudio...'}</span>
            <span>ETA: {downloadProgress.eta}</span>
          </div>

          {/* Botón para abrir / cerrar la Terminal en Vivo */}
          <button
            onClick={() => setShowTerminal(!showTerminal)}
            className="w-full mt-1 py-1.5 px-2.5 rounded-xl bg-white/5 hover:bg-white/10 border border-white/5 text-[11px] text-zinc-300 flex items-center justify-between apple-press transition-all"
          >
            <span className="flex items-center gap-1.5 font-mono">
              <TerminalIcon className="w-3 h-3 text-emerald-400" />
              Terminal de Descarga en Vivo
            </span>
            {showTerminal ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
          </button>

          {/* Consola / Terminal en Vivo tipo macOS/Termux */}
          {showTerminal && (
            <div className="mt-2 p-2.5 rounded-xl bg-zinc-950 border border-zinc-800 font-mono text-[10px] text-emerald-400/90 max-h-36 overflow-y-auto space-y-1 select-text scrollbar-thin shadow-inner animate-fade-in">
              <div className="flex items-center gap-1.5 pb-1 mb-1 border-b border-white/5 text-[9px] text-zinc-500">
                <span className="w-2 h-2 rounded-full bg-red-500/80"></span>
                <span className="w-2 h-2 rounded-full bg-yellow-500/80"></span>
                <span className="w-2 h-2 rounded-full bg-green-500/80"></span>
                <span className="ml-1 text-zinc-400">android@yt-music-termux ~ yt-dlp & ffmpeg</span>
              </div>
              {terminalLines.map((line, idx) => (
                <div key={idx} className="break-all leading-tight opacity-90">
                  {line}
                </div>
              ))}
              <div ref={terminalEndRef} />
            </div>
          )}
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
                placeholder="Busca canción, artista o pega un enlace..."
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
                        cal.recommended ? 'col-span-2' : ''
                      } ${
                        isSelected 
                          ? 'bg-rose-500/15 border-rose-500/50 shadow-lg shadow-rose-500/10' 
                          : 'bg-zinc-900/60 border-white/5 hover:border-white/10'
                      }`}
                    >
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-xs font-semibold text-white flex items-center gap-1.5">
                          <span>{cal.icon}</span> {cal.nombre}
                          {cal.recommended && (
                            <span className="text-[9px] font-semibold px-1.5 py-0.5 rounded-full bg-rose-500/20 text-rose-300 border border-rose-500/30">
                              Recomendado
                            </span>
                          )}
                        </span>
                        {isSelected && <Check className="w-3.5 h-3.5 text-rose-400" />}
                      </div>
                      <div className="flex items-center justify-between gap-1">
                        <span className="text-[10px] font-mono px-1.5 py-0.5 rounded-md bg-white/5 text-zinc-300 shrink-0">
                          {cal.badge}
                        </span>
                        <span className="text-[10px] text-zinc-400 truncate">
                          {cal.desc}
                        </span>
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Resultados de Búsqueda */}
            {searchResults.length > 0 && (
              <div className="space-y-2 pt-2">
                <div className="flex items-center justify-between">
                  <h3 className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
                    Temas Oficiales Encontrados ({searchResults.length})
                  </h3>
                  <span className="text-[10px] text-zinc-500">Portadas oficiales 1200px</span>
                </div>
                <div className="space-y-2">
                  {searchResults.map((item) => {
                    const localMatch = findMatchingTrack(item, libraryTracks);
                    return (
                      <div
                        key={item.id}
                        className={`p-3 rounded-2xl apple-card flex items-center justify-between gap-3 border transition-all ${
                          localMatch ? 'border-emerald-500/30 bg-emerald-950/10' : 'border-white/5 hover:border-white/10'
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
                              <span className="text-[11px] font-mono text-zinc-500">
                                ⏱ {item.duration_str}
                              </span>
                              {localMatch && (
                                <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 flex items-center gap-1 border border-emerald-500/30">
                                  <CheckCircle2 className="w-2.5 h-2.5" /> En tu dispositivo
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

            {/* Placeholder vacío cuando no hay búsqueda */}
            {searchResults.length === 0 && !isSearching && (
              <div className="py-12 flex flex-col items-center justify-center text-center text-zinc-500">
                <div className="w-16 h-16 rounded-full bg-zinc-900 border border-white/5 flex items-center justify-center mb-3">
                  <Sparkles className="w-7 h-7 text-rose-400/80" />
                </div>
                <h3 className="text-sm font-semibold text-zinc-300">Descargas 100% en tu Teléfono</h3>
                <p className="text-xs text-zinc-500 max-w-xs mt-1">
                  Descarga en la calle con datos móviles o Wi-Fi sin depender de tu PC. Audio oficial y carátula 1200x1200px incrustada.
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
                <h2 className="text-lg font-bold text-white tracking-tight">Tu Música en el Dispositivo</h2>
                <p className="text-xs text-zinc-400">
                  {filteredLibraryTracks.length} {librarySearchQuery ? 'encontradas' : 'canciones reconocidas'} • {totalLibrarySizeMb > 1024 ? (totalLibrarySizeMb / 1024).toFixed(1) + ' GB' : totalLibrarySizeMb + ' MB'}
                </p>
              </div>
              <button 
                onClick={loadLibraryData}
                disabled={isScanningLibrary}
                className="px-3 py-1.5 rounded-xl bg-zinc-900 border border-white/10 text-xs font-medium text-zinc-300 flex items-center gap-1.5 active:scale-95 shadow-sm"
                title="Escanear todo el almacenamiento del teléfono"
              >
                <RefreshCw className={`w-3.5 h-3.5 text-rose-400 ${isScanningLibrary ? 'animate-spin' : ''}`} />
                <span>{isScanningLibrary ? 'Escaneando...' : 'Escanear'}</span>
              </button>
            </div>

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
                          <p className="text-xs text-zinc-400 truncate">{track.artist || 'Artista'} • {track.folder || 'Música'}</p>
                        </div>
                      </div>
                      <div className="flex items-center gap-2 shrink-0">
                        <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-white/5 text-zinc-400 uppercase">
                          {track.ext ? track.ext.replace('.', '') : 'mp3'}
                        </span>
                        <span className="text-[11px] font-mono text-zinc-500">
                          {track.size_mb} MB
                        </span>
                        <button
                          onClick={async (e) => {
                            e.stopPropagation();
                            if (confirm(`¿Eliminar ${track.title}?`)) {
                              await apiDeleteTrack(track);
                              loadLibraryData();
                            }
                          }}
                          className="p-1.5 rounded-lg text-zinc-500 hover:text-rose-400 active:scale-90"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
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
                  <p>• <span className="text-zinc-200">Tecnología:</span> Python + yt-dlp + FFmpeg compilados en ARM64</p>
                  <p>• <span className="text-zinc-200">Almacenamiento:</span> <span className="font-mono text-[11px] text-zinc-300">/sdcard/Music/</span></p>
                  <p>• <span className="text-zinc-200">SponsorBlock:</span> Activado automáticamente</p>
                  <p>• <span className="text-zinc-200">Carátulas:</span> 1200×1200 px HD incrustadas en el archivo</p>
                </div>
              </div>
            </div>

            {/* Tarjeta de Reconocimiento de Música del Dispositivo */}
            <div className="space-y-2">
              <span className="text-xs font-semibold text-zinc-400 uppercase tracking-wider px-1">
                Música en el Dispositivo
              </span>
              <div className="p-4 rounded-2xl apple-card border border-white/10 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Disc className="w-4 h-4 text-emerald-400" />
                    <span className="text-xs font-semibold text-white">Reconocimiento Integral</span>
                  </div>
                  <span className="text-[11px] font-mono text-emerald-400 font-semibold px-2 py-0.5 rounded-full bg-emerald-500/20">
                    {libraryTracks.length} canciones
                  </span>
                </div>
                <p className="text-xs text-zinc-400">
                  Detecta automáticamente todos los archivos de música en /sdcard/Music/ y en la biblioteca MediaStore de Android.
                </p>
                <button
                  onClick={loadLibraryData}
                  disabled={isScanningLibrary}
                  className="w-full py-2.5 px-3 rounded-xl bg-white/10 hover:bg-white/15 text-white text-xs font-semibold flex items-center justify-center gap-2 apple-press border border-white/10 transition-all"
                >
                  <RefreshCw className={`w-3.5 h-3.5 text-rose-400 ${isScanningLibrary ? 'animate-spin' : ''}`} />
                  <span>{isScanningLibrary ? 'Escaneando archivos del teléfono...' : 'Re-escanear todo el dispositivo'}</span>
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

      {/* Mini-reproductor Flotante estilo iOS cuando hay canción sonando */}
      {currentTrack && (
        <div 
          style={{ bottom: 'calc(var(--sab, 0px) + 4.8rem)' }}
          className="fixed left-4 right-4 z-50 p-2.5 rounded-2xl apple-glass-heavy border border-white/20 shadow-float flex items-center justify-between gap-3 animate-fade-in"
        >
          <audio 
            ref={audioRef}
            src={getStreamUrl(currentTrack.path)}
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

    </div>
  );
}
