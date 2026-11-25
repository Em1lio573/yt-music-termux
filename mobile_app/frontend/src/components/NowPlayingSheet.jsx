import React, { useState, useRef, useEffect, useMemo } from 'react';
import {
  ChevronDown, Play, Pause, SkipBack, SkipForward, Shuffle, Repeat, Repeat1,
  Mic2, ListMusic, Volume1, Volume2, VolumeX, Moon, RefreshCw,
  Music, X, SlidersHorizontal, Trash2, Heart, Sliders
} from 'lucide-react';
import EqualizerModal from './EqualizerModal';

export default function NowPlayingSheet({
  currentTrack,
  isPlaying,
  audioRef,
  audioDuration = 0,
  trackLyrics,
  isLoadingLyrics,
  queue = [],
  queueIndex = 0,
  shuffle = false,
  repeatMode = 'all', // 'off' | 'all' | 'one'
  volume = 1,
  isMuted = false,
  playbackRate = 1,
  sleepTimer = null,
  activeTab = 'artwork', // 'artwork' | 'lyrics' | 'queue'
  isFavorite = false,
  onToggleFavorite,
  onTabChange,
  onClose,
  onTogglePlay,
  onNext,
  onPrev,
  onSeek,
  onSeekToLyric,
  onToggleShuffle,
  onCycleRepeat,
  onVolumeChange,
  onToggleMute,
  onChangePlaybackRate,
  onSetSleepTimer,
  onSelectTrackFromQueue,
  onRemoveTrackFromQueue,
  onClearQueue,
  onReloadLyrics,
  formatDuration
}) {
  const [showSleepModal, setShowSleepModal] = useState(false);
  const [showSpeedModal, setShowSpeedModal] = useState(false);
  const [showEqualizerModal, setShowEqualizerModal] = useState(false);
  const [isScrubbing, setIsScrubbing] = useState(false);
  const [scrubValue, setScrubValue] = useState(0);

  // Estado de tiempo local desacoplado para no sobre-renderizar App.jsx
  const [localTime, setLocalTime] = useState(0);
  const [localDuration, setLocalDuration] = useState(audioDuration || 0);

  const lyricsContainerRef = useRef(null);
  const activeLineRef = useRef(null);

  // Gesto táctil vertical para deslizar hacia abajo y cerrar estilo iOS
  const [dragOffset, setDragOffset] = useState(0);
  const touchStartY = useRef(0);

  const handleTouchStart = (e) => {
    touchStartY.current = e.touches[0].clientY;
  };

  const handleTouchMove = (e) => {
    const diff = e.touches[0].clientY - touchStartY.current;
    if (diff > 0) {
      setDragOffset(diff);
    } else {
      // Resistencia elástica Apple (rubber-banding) hacia arriba
      setDragOffset(diff * 0.25);
    }
  };

  const handleTouchEnd = () => {
    if (dragOffset > 90) {
      if (window.navigator?.vibrate) window.navigator.vibrate(10);
      onClose();
    }
    setDragOffset(0);
  };

  // Gesto táctil horizontal en la carátula (Apple Music Swipe to Skip)
  const [artworkSwipeX, setArtworkSwipeX] = useState(0);
  const [isSwipingArtwork, setIsSwipingArtwork] = useState(false);
  const swipeStartX = useRef(0);
  const swipeStartY = useRef(0);

  const handleArtworkTouchStart = (e) => {
    swipeStartX.current = e.touches[0].clientX;
    swipeStartY.current = e.touches[0].clientY;
    setIsSwipingArtwork(true);
  };

  const handleArtworkTouchMove = (e) => {
    if (!isSwipingArtwork) return;
    const deltaX = e.touches[0].clientX - swipeStartX.current;
    const deltaY = e.touches[0].clientY - swipeStartY.current;
    if (Math.abs(deltaX) > Math.abs(deltaY)) {
      setArtworkSwipeX(deltaX * 0.45); // Resistencia elástica
    }
  };

  const handleArtworkTouchEnd = () => {
    if (!isSwipingArtwork) return;
    setIsSwipingArtwork(false);
    if (artworkSwipeX < -50) {
      onNext();
    } else if (artworkSwipeX > 50) {
      onPrev();
    }
    setArtworkSwipeX(0);
  };

  // Suscripción de alta frecuencia directamente al audioRef
  useEffect(() => {
    const audio = audioRef?.current;
    if (!audio) return;

    setLocalTime(audio.currentTime || 0);
    if (audio.duration && !isNaN(audio.duration)) {
      setLocalDuration(audio.duration);
    }

    const onTime = () => {
      if (!isScrubbing) {
        setLocalTime(audio.currentTime || 0);
      }
    };

    const onMeta = () => {
      if (audio.duration && !isNaN(audio.duration)) {
        setLocalDuration(audio.duration);
      }
    };

    audio.addEventListener('timeupdate', onTime);
    audio.addEventListener('loadedmetadata', onMeta);
    return () => {
      audio.removeEventListener('timeupdate', onTime);
      audio.removeEventListener('loadedmetadata', onMeta);
    };
  }, [audioRef, isScrubbing]);

  // Cálculo local del verso activo para Karaoke
  const activeLyricIndex = useMemo(() => {
    if (!trackLyrics?.parsed || trackLyrics.parsed.length === 0) return -1;
    const list = trackLyrics.parsed.filter(l => l.time >= 0);
    if (list.length === 0) return -1;
    let idx = -1;
    const current = isScrubbing ? scrubValue : localTime;
    for (let i = 0; i < list.length; i++) {
      if (current >= list[i].time) {
        idx = i;
      } else {
        break;
      }
    }
    return idx;
  }, [localTime, isScrubbing, scrubValue, trackLyrics]);

  // Auto-scroll suave de letra activa al centro de la pantalla
  useEffect(() => {
    if (activeTab === 'lyrics' && activeLyricIndex >= 0 && activeLineRef.current) {
      activeLineRef.current.scrollIntoView({
        behavior: 'smooth',
        block: 'center'
      });
    }
  }, [activeLyricIndex, activeTab]);

  const audioFormatBadge = useMemo(() => {
    if (!currentTrack || !currentTrack.path) return 'AUDIO HD';
    const p = currentTrack.path.toLowerCase();
    if (p.endsWith('.opus')) return 'OPUS • MÁSTER NATIVO';
    if (p.endsWith('.m4a') || p.endsWith('.aac')) return 'AAC • HI-FI';
    if (p.endsWith('.flac')) return 'FLAC • LOSSLESS';
    if (p.endsWith('.mp3')) return 'MP3 • 320 KBPS';
    return 'AUDIO HD';
  }, [currentTrack?.path]);

  const currentDisplayTime = isScrubbing ? scrubValue : localTime;
  const effectiveDuration = localDuration || audioDuration || 0;

  const remainingTimeStr = useMemo(() => {
    if (!effectiveDuration || effectiveDuration <= 0) return '--:--';
    const rem = Math.max(0, effectiveDuration - currentDisplayTime);
    return `-${formatDuration(rem)}`;
  }, [effectiveDuration, currentDisplayTime, formatDuration]);

  return (
    <div
      style={{
        paddingTop: 'calc(var(--sat, 0px) + 0.5rem)',
        paddingBottom: 'calc(var(--sab, 0px) + 0.75rem)',
        transform: dragOffset > 0 ? `translateY(${dragOffset}px)` : 'none',
        transition: dragOffset === 0 ? 'transform 0.25s cubic-bezier(0.2, 0.9, 0.3, 1)' : 'none'
      }}
      className="fixed inset-0 z-50 flex flex-col bg-zinc-950 text-white select-none overflow-hidden animate-fade-in"
    >
      {/* Fondo ambiental reflectivo dinámico acelerado por GPU */}
      {currentTrack?.cover && (
        <div
          className="absolute inset-0 opacity-20 pointer-events-none scale-125 blur-2xl bg-cover bg-center transition-all duration-700 ease-out"
          style={{ 
            backgroundImage: `url(${currentTrack.cover})`,
            transform: 'translateZ(0)',
            willChange: 'opacity, transform'
          }}
        />
      )}
      <div className="absolute inset-0 bg-gradient-to-b from-zinc-950/70 via-zinc-950/90 to-zinc-950 pointer-events-none" />

      {/* TOP HEADER con tirador de arrastre */}
      <div
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        className="relative z-20 px-5 pt-1 pb-2 flex flex-col cursor-grab active:cursor-grabbing"
      >
        <div className="w-10 h-1 bg-white/25 hover:bg-white/40 rounded-full mx-auto mb-2 transition-colors" />

        <div className="flex items-center justify-between">
          <button
            onClick={onClose}
            className="p-2 -ml-2 rounded-full text-zinc-400 hover:text-white bg-white/5 active:scale-95 transition-all"
            title="Minimizar reproductor"
          >
            <ChevronDown className="w-6 h-6" />
          </button>

          <div className="text-center min-w-0 max-w-[60%]">
            <span className="text-[10px] font-semibold tracking-wider uppercase text-zinc-400 block truncate">
              {currentTrack?.folder || 'YouTube Music'}
            </span>
            <p className="text-xs font-semibold text-white truncate">
              {currentTrack?.album || currentTrack?.artist || 'Reproduciendo'}
            </p>
          </div>

          <div className="flex items-center gap-1 -mr-2">
            <button
              onClick={() => setShowEqualizerModal(true)}
              className="p-2 rounded-full text-zinc-400 hover:text-white bg-white/5 active:scale-95 transition-all"
              title="Ecualizador DSP"
            >
              <Sliders className="w-4 h-4" />
            </button>

            <button
              onClick={() => setShowSleepModal(true)}
              className={`p-2 rounded-full active:scale-95 transition-all ${
                sleepTimer 
                  ? 'bg-rose-500/20 text-rose-300 border border-rose-500/30' 
                  : 'text-zinc-400 hover:text-white bg-white/5'
              }`}
              title="Temporizador de apagado"
            >
              {sleepTimer ? (
                <span className="text-[10px] font-bold font-mono px-1">
                  {sleepTimer.mode === 'track' ? 'Fin' : `${sleepTimer.minutesLeft}m`}
                </span>
              ) : (
                <Moon className="w-4 h-4" />
              )}
            </button>

            <button
              onClick={() => setShowSpeedModal(true)}
              className="px-2 py-1 rounded-full text-[11px] font-semibold font-mono text-zinc-400 hover:text-white bg-white/5 active:scale-95 transition-all"
              title="Velocidad de reproducción"
            >
              {playbackRate}x
            </button>
          </div>
        </div>

        {/* SELECTOR DE VISTAS SEGMENTADO ESTILO APPLE */}
        <div className="flex items-center justify-center gap-1 mt-2.5 p-1 bg-white/5 rounded-2xl border border-white/5 max-w-xs mx-auto w-full">
          <button
            onClick={() => onTabChange('artwork')}
            className={`flex-1 py-1.5 px-3 rounded-xl text-xs font-semibold transition-all apple-press flex items-center justify-center gap-1.5 ${
              activeTab === 'artwork'
                ? 'bg-white text-black shadow-md'
                : 'text-zinc-400 hover:text-white'
            }`}
          >
            <Music className="w-3.5 h-3.5" />
            <span>Portada</span>
          </button>

          <button
            onClick={() => onTabChange('lyrics')}
            className={`flex-1 py-1.5 px-3 rounded-xl text-xs font-semibold transition-all apple-press flex items-center justify-center gap-1.5 ${
              activeTab === 'lyrics'
                ? 'bg-white text-black shadow-md'
                : 'text-zinc-400 hover:text-white'
            }`}
          >
            <Mic2 className="w-3.5 h-3.5" />
            <span>Letras</span>
          </button>

          <button
            onClick={() => onTabChange('queue')}
            className={`flex-1 py-1.5 px-3 rounded-xl text-xs font-semibold transition-all apple-press flex items-center justify-center gap-1.5 ${
              activeTab === 'queue'
                ? 'bg-white text-black shadow-md'
                : 'text-zinc-400 hover:text-white'
            }`}
          >
            <ListMusic className="w-3.5 h-3.5" />
            <span>Cola {queue.length > 0 && `(${queue.length})`}</span>
          </button>
        </div>
      </div>

      {/* CONTENIDO PRINCIPAL: 3 VISTAS (PORTADA / LETRAS / COLA) */}
      <div className="relative z-10 flex-1 overflow-hidden flex flex-col px-6">
        
        {/* VISTA 1: PORTADA & DETALLES (Con gesto táctil deslizante Apple Music) */}
        {activeTab === 'artwork' && (
          <div className="flex-1 flex flex-col justify-center items-center py-2 animate-fade-in">
            {/* Contenedor interactivo con gesto horizontal swipe */}
            <div
              onTouchStart={handleArtworkTouchStart}
              onTouchMove={handleArtworkTouchMove}
              onTouchEnd={handleArtworkTouchEnd}
              style={{
                transform: artworkSwipeX !== 0 
                  ? `translateX(${artworkSwipeX}px) rotate(${artworkSwipeX * 0.03}deg)` 
                  : undefined,
                transition: isSwipingArtwork ? 'none' : 'transform 0.35s cubic-bezier(0.2, 0.9, 0.3, 1)',
                willChange: 'transform'
              }}
              className="relative w-full max-w-[310px] aspect-square flex items-center justify-center my-auto cursor-grab active:cursor-grabbing select-none"
            >
              <div
                className={`w-full h-full rounded-[28px] overflow-hidden bg-zinc-900 border border-white/10 transition-all duration-500 ease-out flex items-center justify-center ${
                  isPlaying
                    ? 'scale-100 shadow-[0_25px_60px_rgba(0,0,0,0.85)] brightness-105'
                    : 'scale-[0.88] shadow-lg brightness-90 opacity-85'
                }`}
              >
                {currentTrack?.cover ? (
                  <img
                    src={currentTrack.cover}
                    alt=""
                    className="w-full h-full object-cover select-none pointer-events-none"
                    loading="eager"
                  />
                ) : (
                  <div className="w-full h-full bg-gradient-to-tr from-rose-600 to-pink-500 flex items-center justify-center text-white">
                    <Music className="w-16 h-16" />
                  </div>
                )}
              </div>
            </div>

            {/* Metadatos principales de la pista y formato de audio */}
            <div className="w-full mt-auto mb-2 text-left">
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <h2 className="text-xl font-bold text-white tracking-tight truncate">
                    {currentTrack?.title || 'Sin Título'}
                  </h2>
                  <p className="text-sm font-medium text-zinc-400 truncate mt-0.5">
                    {currentTrack?.artist || 'Artista Desconocido'}
                  </p>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <button
                    onClick={() => onToggleFavorite?.(currentTrack)}
                    className="p-2 rounded-full active:scale-90 transition-transform"
                    title={isFavorite ? 'Quitar de favoritos' : 'Marcar como favorito'}
                  >
                    <Heart className={`w-5 h-5 transition-colors ${isFavorite ? 'text-rose-500 fill-rose-500' : 'text-zinc-400 hover:text-white'}`} />
                  </button>
                  <span className="text-[10px] font-bold font-mono tracking-wider px-2.5 py-1 rounded-full bg-white/10 text-zinc-300 border border-white/10 uppercase">
                    {audioFormatBadge}
                  </span>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* VISTA 2: LETRAS SINCRONIZADAS KARAOKE CON MÁSCARA GRADIENTE */}
        {activeTab === 'lyrics' && (
          <div className="flex-1 flex flex-col overflow-hidden animate-fade-in py-1">
            <div className="flex items-center justify-between pb-2 border-b border-white/5 text-xs text-zinc-400">
              <div className="flex items-center gap-2">
                <span className="font-semibold text-white truncate max-w-[200px]">
                  {currentTrack?.title}
                </span>
                {trackLyrics?.source && (
                  <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-rose-500/20 text-rose-300 border border-rose-500/30">
                    {trackLyrics.source === 'native' ? 'LRC Local' : 'LRCLIB Sincronizado'}
                  </span>
                )}
              </div>
              <button
                onClick={onReloadLyrics}
                disabled={isLoadingLyrics}
                className="p-1.5 rounded-full hover:text-white bg-white/5 active:scale-95 transition-all"
                title="Recargar letras"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${isLoadingLyrics ? 'animate-spin text-rose-400' : ''}`} />
              </button>
            </div>

            <div
              ref={lyricsContainerRef}
              className="flex-1 overflow-y-auto py-6 space-y-4 scroll-smooth no-scrollbar lyrics-mask"
            >
              {isLoadingLyrics ? (
                <div className="h-full flex flex-col items-center justify-center text-center text-zinc-400 py-16 space-y-3">
                  <div className="w-12 h-12 rounded-full bg-rose-500/15 border border-rose-500/30 flex items-center justify-center animate-pulse">
                    <Mic2 className="w-6 h-6 text-rose-400" />
                  </div>
                  <p className="text-sm font-semibold text-white">Sincronizando letras karaoke...</p>
                  <p className="text-xs text-zinc-500">Consultando LRCLIB y metadatos locales</p>
                </div>
              ) : trackLyrics?.parsed && trackLyrics.parsed.length > 0 ? (
                <div className="space-y-4 py-8">
                  {trackLyrics.parsed.map((line, idx) => {
                    const hasTime = line.time >= 0;
                    const isActive = idx === activeLyricIndex;
                    const isPassed = activeLyricIndex !== -1 && idx < activeLyricIndex;

                    return (
                      <div
                        key={idx}
                        ref={isActive ? activeLineRef : null}
                        onClick={() => {
                          if (hasTime) onSeekToLyric(line.time);
                        }}
                        className={`cursor-pointer transition-all duration-300 ease-out select-none py-2 px-3 rounded-2xl ${
                          isActive
                            ? 'text-white text-2xl font-bold tracking-tight scale-[1.03] origin-left drop-shadow-[0_0_20px_rgba(255,255,255,0.7)] bg-white/5 border border-white/10'
                            : isPassed
                              ? 'text-zinc-400 text-lg font-medium opacity-45 hover:opacity-85'
                              : 'text-zinc-500 text-lg font-medium opacity-30 hover:opacity-75'
                        }`}
                      >
                        <p className="leading-snug">{line.text || '• • •'}</p>
                      </div>
                    );
                  })}
                </div>
              ) : trackLyrics?.plainLyrics ? (
                <div className="py-6 px-2 text-zinc-300 text-base leading-relaxed whitespace-pre-line font-medium space-y-2">
                  {trackLyrics.plainLyrics}
                </div>
              ) : (
                <div className="h-full flex flex-col items-center justify-center text-center text-zinc-500 py-16 space-y-3">
                  <div className="w-12 h-12 rounded-full bg-zinc-900 border border-white/10 flex items-center justify-center">
                    <Mic2 className="w-6 h-6 text-zinc-600" />
                  </div>
                  <p className="text-sm font-semibold text-zinc-300">Sin letras para esta pista</p>
                  <p className="text-xs text-zinc-500 max-w-xs">
                    No se encontraron letras LRC sincronizadas en la base de datos de LRCLIB.
                  </p>
                  <button
                    onClick={onReloadLyrics}
                    className="px-4 py-2 rounded-xl bg-white/10 hover:bg-white/15 text-xs font-semibold text-white apple-press border border-white/10 flex items-center gap-2"
                  >
                    <RefreshCw className="w-3.5 h-3.5" />
                    <span>Reintentar</span>
                  </button>
                </div>
              )}
            </div>
          </div>
        )}

        {/* VISTA 3: COLA DE REPRODUCCIÓN (UP NEXT) */}
        {activeTab === 'queue' && (
          <div className="flex-1 flex flex-col overflow-hidden animate-fade-in py-1">
            <div className="flex items-center justify-between pb-2 border-b border-white/5 text-xs text-zinc-400">
              <span className="font-semibold text-white">
                A continuación ({queue.length} pistas)
              </span>
              <div className="flex items-center gap-2">
                <button
                  onClick={onToggleShuffle}
                  className={`px-2 py-1 rounded-lg border text-[11px] font-medium transition-all ${
                    shuffle ? 'bg-rose-500/20 border-rose-500/40 text-rose-300' : 'bg-white/5 border-white/10 text-zinc-400'
                  }`}
                >
                  Mezclar
                </button>
                {queue.length > 1 && (
                  <button
                    onClick={onClearQueue}
                    className="p-1 rounded-lg text-zinc-500 hover:text-red-400 transition-colors"
                    title="Limpiar cola"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            </div>

            <div className="flex-1 overflow-y-auto py-2 space-y-1.5 no-scrollbar">
              {queue.map((track, idx) => {
                const isCurrent = idx === queueIndex;
                return (
                  <div
                    key={track.path || idx}
                    onClick={() => onSelectTrackFromQueue(track, idx)}
                    className={`p-2.5 rounded-2xl flex items-center justify-between gap-3 cursor-pointer apple-press transition-all ${
                      isCurrent
                        ? 'bg-rose-500/20 border border-rose-500/40 shadow-sm'
                        : 'bg-white/5 hover:bg-white/10 border border-white/5'
                    }`}
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <div className="w-10 h-10 rounded-xl overflow-hidden bg-zinc-800 shrink-0 relative flex items-center justify-center">
                        {track.cover ? (
                          <img src={track.cover} alt="" className="w-full h-full object-cover" loading="lazy" decoding="async" />
                        ) : (
                          <Music className="w-5 h-5 text-zinc-500" />
                        )}
                        {isCurrent && isPlaying && (
                          <div className="absolute inset-0 bg-black/50 flex items-center justify-center gap-0.5">
                            <span className="w-1 bg-rose-400 rounded-full animate-music-bar-1" />
                            <span className="w-1 bg-rose-400 rounded-full animate-music-bar-2" />
                            <span className="w-1 bg-rose-400 rounded-full animate-music-bar-3" />
                          </div>
                        )}
                      </div>
                      <div className="min-w-0">
                        <h4 className={`text-xs font-semibold truncate ${isCurrent ? 'text-rose-300' : 'text-white'}`}>
                          {track.title}
                        </h4>
                        <p className="text-[11px] text-zinc-400 truncate">
                          {track.artist || 'Artista'}
                        </p>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 shrink-0">
                      {track.duration_str && (
                        <span className="text-[11px] font-mono text-zinc-500">
                          {track.duration_str}
                        </span>
                      )}
                      {!isCurrent && (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            onRemoveTrackFromQueue(idx);
                          }}
                          className="p-1 rounded-full text-zinc-500 hover:text-white"
                          title="Quitar de la cola"
                        >
                          <X className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        )}

      </div>

      {/* BARRA INFERIOR DE TRANSPORTE, SCRUBBER Y CONTROLES */}
      <div className="relative z-20 px-6 pt-2 pb-2 bg-zinc-950/95 backdrop-blur-xl border-t border-white/10 space-y-3 shrink-0">
        
        {/* Scrubber de alta precisión a 60 FPS sin latencia */}
        <div className="space-y-1">
          <div className="relative py-1 flex items-center">
            <input
              type="range"
              min="0"
              max={effectiveDuration || 1}
              step="0.1"
              value={currentDisplayTime}
              onMouseDown={() => {
                setIsScrubbing(true);
                setScrubValue(localTime);
              }}
              onTouchStart={() => {
                setIsScrubbing(true);
                setScrubValue(localTime);
              }}
              onChange={(e) => {
                const val = parseFloat(e.target.value);
                setScrubValue(val);
              }}
              onMouseUp={(e) => {
                const val = parseFloat(e.target.value);
                setIsScrubbing(false);
                setLocalTime(val);
                onSeek(val);
              }}
              onTouchEnd={() => {
                setIsScrubbing(false);
                setLocalTime(scrubValue);
                onSeek(scrubValue);
              }}
              className="w-full h-1.5 bg-white/20 rounded-lg appearance-none cursor-pointer accent-white"
            />
          </div>
          <div className="flex items-center justify-between text-[11px] font-mono text-zinc-400 px-0.5 select-none">
            <span>{formatDuration(currentDisplayTime)}</span>
            <span>{remainingTimeStr}</span>
          </div>
        </div>

        {/* Controles Principales de Transporte (Apple Music) */}
        <div className="flex items-center justify-between max-w-sm mx-auto py-1">
          <button
            onClick={onToggleShuffle}
            className={`p-2.5 rounded-full active:scale-90 transition-all flex flex-col items-center gap-0.5 ${
              shuffle ? 'text-rose-400' : 'text-zinc-400 hover:text-white'
            }`}
            title="Reproducción aleatoria"
          >
            <Shuffle className="w-5 h-5" />
            {shuffle && <span className="w-1 h-1 bg-rose-400 rounded-full" />}
          </button>

          <button
            onClick={onPrev}
            className="p-3 text-white hover:text-rose-300 active:scale-90 transition-all"
            title="Pista anterior"
          >
            <SkipBack className="w-7 h-7 fill-current" />
          </button>

          <button
            onClick={onTogglePlay}
            className="w-16 h-16 rounded-full bg-white text-black flex items-center justify-center shadow-2xl shadow-white/20 active:scale-95 transition-all"
            title={isPlaying ? 'Pausar' : 'Reproducir'}
          >
            {isPlaying ? (
              <Pause className="w-7 h-7 fill-current" />
            ) : (
              <Play className="w-7 h-7 fill-current ml-1" />
            )}
          </button>

          <button
            onClick={onNext}
            className="p-3 text-white hover:text-rose-300 active:scale-90 transition-all"
            title="Siguiente pista"
          >
            <SkipForward className="w-7 h-7 fill-current" />
          </button>

          <button
            onClick={onCycleRepeat}
            className={`p-2.5 rounded-full active:scale-90 transition-all flex flex-col items-center gap-0.5 ${
              repeatMode !== 'off' ? 'text-rose-400' : 'text-zinc-400 hover:text-white'
            }`}
            title={`Modo de repetición: ${repeatMode}`}
          >
            {repeatMode === 'one' ? (
              <Repeat1 className="w-5 h-5" />
            ) : (
              <Repeat className="w-5 h-5" />
            )}
            {repeatMode === 'all' && <span className="w-1 h-1 bg-rose-400 rounded-full" />}
          </button>
        </div>

        {/* Control de Volumen y Silencio */}
        <div className="flex items-center gap-3 max-w-xs mx-auto pt-1">
          <button
            onClick={onToggleMute}
            className="text-zinc-400 hover:text-white active:scale-90 transition-all"
            title={isMuted ? 'Desactivar silencio' : 'Silenciar'}
          >
            {isMuted || volume === 0 ? (
              <VolumeX className="w-4 h-4 text-zinc-500" />
            ) : (
              <Volume1 className="w-4 h-4" />
            )}
          </button>

          <input
            type="range"
            min="0"
            max="1"
            step="0.02"
            value={isMuted ? 0 : volume}
            onChange={(e) => onVolumeChange(parseFloat(e.target.value))}
            className="w-full h-1 bg-white/20 rounded-lg appearance-none cursor-pointer accent-white"
          />

          <button
            onClick={() => onVolumeChange(1)}
            className="text-zinc-400 hover:text-white active:scale-90 transition-all"
            title="Volumen máximo"
          >
            <Volume2 className="w-4 h-4" />
          </button>
        </div>

      </div>

      {/* MODAL: TEMPORIZADOR DE APAGADO */}
      {showSleepModal && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-md flex items-end sm:items-center justify-center p-4 animate-fade-in">
          <div className="w-full max-w-sm bg-zinc-900 border border-white/15 rounded-3xl p-5 shadow-2xl space-y-4">
            <div className="flex items-center justify-between border-b border-white/10 pb-3">
              <div className="flex items-center gap-2">
                <Moon className="w-5 h-5 text-rose-400" />
                <h3 className="text-sm font-bold text-white">Temporizador de Sueño</h3>
              </div>
              <button
                onClick={() => setShowSleepModal(false)}
                className="p-1 rounded-full text-zinc-400 hover:text-white"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="grid grid-cols-2 gap-2 text-xs">
              {[
                { label: '15 minutos', val: 15 },
                { label: '30 minutos', val: 30 },
                { label: '45 minutos', val: 45 },
                { label: '60 minutos', val: 60 },
                { label: 'Al terminar tema', val: 'track' }
              ].map((opt) => (
                <button
                  key={opt.val}
                  onClick={() => {
                    onSetSleepTimer(opt.val);
                    setShowSleepModal(false);
                  }}
                  className={`p-3 rounded-xl border text-left font-medium apple-press transition-all ${
                    sleepTimer?.mode === opt.val || sleepTimer?.minutesLeft === opt.val
                      ? 'bg-rose-500/20 border-rose-500/50 text-rose-300'
                      : 'bg-white/5 border-white/5 text-zinc-300 hover:text-white'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
              {sleepTimer && (
                <button
                  onClick={() => {
                    onSetSleepTimer(null);
                    setShowSleepModal(false);
                  }}
                  className="col-span-2 p-2.5 rounded-xl bg-red-500/15 border border-red-500/30 text-red-300 font-semibold apple-press text-center"
                >
                  Desactivar Temporizador
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* MODAL: VELOCIDAD DE REPRODUCCIÓN */}
      {showSpeedModal && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-md flex items-end sm:items-center justify-center p-4 animate-fade-in">
          <div className="w-full max-w-xs bg-zinc-900 border border-white/15 rounded-3xl p-5 shadow-2xl space-y-4">
            <div className="flex items-center justify-between border-b border-white/10 pb-3">
              <div className="flex items-center gap-2">
                <SlidersHorizontal className="w-5 h-5 text-rose-400" />
                <h3 className="text-sm font-bold text-white">Velocidad de Audio</h3>
              </div>
              <button
                onClick={() => setShowSpeedModal(false)}
                className="p-1 rounded-full text-zinc-400 hover:text-white"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="grid grid-cols-3 gap-2 text-xs font-mono">
              {[0.8, 1.0, 1.25, 1.5, 1.75, 2.0].map((rate) => (
                <button
                  key={rate}
                  onClick={() => {
                    onChangePlaybackRate(rate);
                    setShowSpeedModal(false);
                  }}
                  className={`p-3 rounded-xl border text-center font-bold apple-press transition-all ${
                    playbackRate === rate
                      ? 'bg-rose-500/20 border-rose-500/50 text-rose-300'
                      : 'bg-white/5 border-white/5 text-zinc-300 hover:text-white'
                  }`}
                >
                  {rate}x
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* MODAL: ECUALIZADOR DSP DE 5 BANDAS */}
      <EqualizerModal
        isOpen={showEqualizerModal}
        onClose={() => setShowEqualizerModal(false)}
      />

    </div>
  );
}
