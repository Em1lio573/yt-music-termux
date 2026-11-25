import React, { useState, useEffect } from 'react';
import { X, Search, Check, Image as ImageIcon, Loader2, Sparkles, Link2, WifiOff } from 'lucide-react';
import { apiSearchCoverCandidates, apiUpdateTrackCover } from '../apiClient';

export default function CoverPickerModal({ track, isOpen, onClose, onCoverUpdated }) {
  const [candidates, setCandidates] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [customUrl, setCustomUrl] = useState('');
  const [selectedCover, setSelectedCover] = useState(null);
  const [isApplying, setIsApplying] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  useEffect(() => {
    if (isOpen && track) {
      const initialQuery = `${track.artist || ''} ${track.title || ''}`.trim();
      setSearchQuery(initialQuery);
      setCustomUrl('');
      setSelectedCover(null);
      setErrorMsg('');
      loadCandidates(track.artist || '', track.title || '', track.album || '');
    }
  }, [isOpen, track]);

  const loadCandidates = async (artist, title, album) => {
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      setErrorMsg('Sin conexión a internet. Conéctate a una red Wi-Fi o datos para buscar carátulas en Apple Music o Deezer.');
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    setErrorMsg('');
    try {
      const list = await apiSearchCoverCandidates(artist, title, album);
      setCandidates(list);
      if (list.length === 0) {
        setErrorMsg('No se encontraron carátulas oficiales con esos términos.');
      }
    } catch (e) {
      setErrorMsg('Error buscando carátulas.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleSearchSubmit = (e) => {
    e.preventDefault();
    if (!searchQuery.trim()) return;
    loadCandidates('', searchQuery.trim(), '');
  };

  const handleApplyCover = async (coverUrl, candItem = null) => {
    if (!coverUrl || isApplying || !track?.path) return;
    setIsApplying(true);
    setSelectedCover(coverUrl);

    if (window.navigator?.vibrate) {
      window.navigator.vibrate(12);
    }

    try {
      const artist = candItem?.artist || track.artist || '';
      const title = candItem?.title || track.title || '';
      const album = candItem?.album || track.album || '';

      const res = await apiUpdateTrackCover(track.path, artist, title, album, coverUrl);
      if (res && (res.success || res.coverPath)) {
        const finalCover = (res.coverPath && window.Capacitor) ? `${Capacitor.convertFileSrc(res.coverPath)}?t=${Date.now()}` : coverUrl;
        onCoverUpdated?.(finalCover, track.path);
        setTimeout(() => {
          onClose();
        }, 300);
      } else {
        setErrorMsg('No se pudo guardar la carátula en el dispositivo.');
      }
    } catch (e) {
      setErrorMsg('Error al actualizar la carátula: ' + (e?.message || 'Fallo desconocido'));
    } finally {
      setIsApplying(false);
    }
  };

  if (!isOpen || !track) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div 
        className="w-full max-w-lg max-h-[90vh] flex flex-col apple-glass-heavy rounded-t-3xl sm:rounded-3xl border border-white/10 shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Cabecera modal */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/10">
          <div className="flex items-center space-x-2.5">
            <div className="w-8 h-8 rounded-xl bg-rose-500/10 flex items-center justify-center text-rose-400">
              <Sparkles className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-white tracking-wide">Cambiar Carátula</h3>
              <p className="text-[11px] text-zinc-400 truncate max-w-[240px]">{track.title}</p>
            </div>
          </div>
          <button 
            onClick={onClose}
            className="p-1.5 rounded-full text-zinc-400 hover:text-white bg-white/5 active:scale-95 transition-transform"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Buscador de carátulas */}
        <form onSubmit={handleSearchSubmit} className="p-4 border-b border-white/5">
          <div className="relative flex items-center">
            <Search className="w-4 h-4 text-zinc-400 absolute left-3 pointer-events-none" />
            <input 
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Buscar por canción o artista..."
              className="w-full bg-zinc-900/80 border border-white/10 rounded-xl pl-9 pr-24 py-2.5 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-rose-500/50"
            />
            <button 
              type="submit"
              disabled={isLoading}
              className="absolute right-1.5 px-3 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 text-xs font-medium text-white transition-colors disabled:opacity-50"
            >
              {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : 'Buscar'}
            </button>
          </div>
        </form>

        {/* Contenido / Cuadrícula de carátulas */}
        <div className="flex-1 overflow-y-auto p-4 no-scrollbar space-y-4 min-h-[220px]">
          {isLoading ? (
            <div className="flex flex-col items-center justify-center py-12 text-zinc-400 space-y-3">
              <Loader2 className="w-6 h-6 animate-spin text-rose-500" />
              <p className="text-xs">Buscando másteres en Apple Music y Deezer...</p>
            </div>
          ) : candidates.length > 0 ? (
            <div className="grid grid-cols-2 gap-3">
              {candidates.map((cand, idx) => {
                const isCurrent = selectedCover === cand.cover;
                return (
                  <div 
                    key={idx}
                    onClick={() => handleApplyCover(cand.cover, cand)}
                    className={`group relative rounded-2xl overflow-hidden border p-2 bg-zinc-900/60 transition-all cursor-pointer active:scale-95 ${
                      isCurrent 
                        ? 'border-rose-500 ring-2 ring-rose-500/30' 
                        : 'border-white/10 hover:border-white/20'
                    }`}
                  >
                    <div className="relative aspect-square rounded-xl overflow-hidden bg-zinc-800 mb-2">
                      <img 
                        src={cand.cover} 
                        alt="" 
                        className="w-full h-full object-cover" 
                        loading="lazy"
                      />
                      {isApplying && selectedCover === cand.cover && (
                        <div className="absolute inset-0 bg-black/60 flex items-center justify-center">
                          <Loader2 className="w-6 h-6 text-white animate-spin" />
                        </div>
                      )}
                      <span className="absolute top-1.5 left-1.5 px-1.5 py-0.5 rounded-md bg-black/60 backdrop-blur-md text-[9px] font-medium text-zinc-300">
                        {cand.source}
                      </span>
                    </div>
                    <p className="text-xs font-medium text-white truncate">{cand.title}</p>
                    <p className="text-[11px] text-zinc-400 truncate">{cand.artist}</p>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="text-center py-10 text-zinc-500 space-y-2">
              <ImageIcon className="w-8 h-8 mx-auto stroke-[1.5]" />
              <p className="text-xs">{errorMsg || 'Sin resultados'}</p>
            </div>
          )}

          {/* Opción de URL directa */}
          <div className="pt-2 border-t border-white/5">
            <p className="text-[11px] text-zinc-400 mb-2 font-medium">O pegar enlace directo de imagen:</p>
            <div className="flex items-center space-x-2">
              <div className="relative flex-1">
                <Link2 className="w-3.5 h-3.5 text-zinc-500 absolute left-3 top-1/2 -translate-y-1/2" />
                <input 
                  type="url"
                  value={customUrl}
                  onChange={(e) => setCustomUrl(e.target.value)}
                  placeholder="https://ejemplo.com/portada.jpg"
                  className="w-full bg-zinc-900/60 border border-white/10 rounded-xl pl-8 pr-3 py-2 text-xs text-white placeholder-zinc-600 focus:outline-none focus:border-rose-500/50"
                />
              </div>
              <button 
                type="button"
                disabled={!customUrl.trim() || isApplying}
                onClick={() => handleApplyCover(customUrl.trim())}
                className="px-3.5 py-2 rounded-xl bg-rose-500 hover:bg-rose-600 text-xs font-semibold text-white transition-colors disabled:opacity-40"
              >
                Aplicar
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
