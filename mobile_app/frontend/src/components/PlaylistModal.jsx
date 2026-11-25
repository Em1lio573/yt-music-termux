import React, { useState } from 'react';
import { X, Plus, ListMusic, Music, Trash2, Play } from 'lucide-react';

export default function PlaylistModal({
  isOpen,
  onClose,
  playlists = [],
  onCreatePlaylist,
  onAddToPlaylist,
  onPlayPlaylist,
  onDeletePlaylist,
  trackToAdd = null
}) {
  const [newPlaylistName, setNewPlaylistName] = useState('');
  const [isCreating, setIsCreating] = useState(false);

  if (!isOpen) return null;

  const handleCreateSubmit = (e) => {
    e.preventDefault();
    if (!newPlaylistName.trim()) return;
    onCreatePlaylist(newPlaylistName.trim());
    setNewPlaylistName('');
    setIsCreating(false);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div 
        className="w-full max-w-md max-h-[85vh] flex flex-col apple-glass-heavy rounded-t-3xl sm:rounded-3xl border border-white/10 shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/10">
          <div className="flex items-center space-x-2.5">
            <div className="w-8 h-8 rounded-xl bg-rose-500/10 flex items-center justify-center text-rose-400">
              <ListMusic className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-white tracking-wide">
                {trackToAdd ? 'Agregar a Lista' : 'Listas de Reproducción'}
              </h3>
              <p className="text-[11px] text-zinc-400">
                {trackToAdd ? trackToAdd.title : `${playlists.length} listas creadas`}
              </p>
            </div>
          </div>

          <button 
            onClick={onClose}
            className="p-1.5 rounded-full text-zinc-400 hover:text-white bg-white/5 active:scale-95 transition-transform"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Formulario para nueva lista */}
        <div className="p-4 border-b border-white/5">
          {isCreating ? (
            <form onSubmit={handleCreateSubmit} className="flex items-center space-x-2">
              <input 
                type="text"
                autoFocus
                value={newPlaylistName}
                onChange={(e) => setNewPlaylistName(e.target.value)}
                placeholder="Nombre de la lista..."
                className="flex-1 bg-zinc-900/80 border border-white/10 rounded-xl px-3.5 py-2 text-xs text-white placeholder-zinc-500 focus:outline-none focus:border-rose-500/50"
              />
              <button
                type="submit"
                className="px-3.5 py-2 rounded-xl bg-rose-500 hover:bg-rose-600 text-xs font-semibold text-white transition-colors"
              >
                Crear
              </button>
              <button
                type="button"
                onClick={() => setIsCreating(false)}
                className="px-3 py-2 rounded-xl bg-white/5 hover:bg-white/10 text-xs text-zinc-400 transition-colors"
              >
                Cancelar
              </button>
            </form>
          ) : (
            <button
              onClick={() => setIsCreating(true)}
              className="w-full flex items-center justify-center space-x-2 py-2.5 rounded-xl border border-dashed border-white/15 hover:border-white/30 text-xs font-medium text-zinc-300 hover:text-white transition-all bg-white/[0.02]"
            >
              <Plus className="w-4 h-4 text-rose-400" />
              <span>Nueva Lista de Reproducción</span>
            </button>
          )}
        </div>

        {/* Lista de Playlists */}
        <div className="flex-1 overflow-y-auto p-4 no-scrollbar space-y-2 min-h-[180px]">
          {playlists.length === 0 ? (
            <div className="text-center py-10 text-zinc-500 space-y-2">
              <Music className="w-8 h-8 mx-auto stroke-[1.5]" />
              <p className="text-xs">No tienes listas aún. Crea una para organizar tu música.</p>
            </div>
          ) : (
            playlists.map((pl) => {
              const count = pl.tracks ? pl.tracks.length : 0;
              const hasTrack = trackToAdd && pl.tracks && pl.tracks.some(t => t.path === trackToAdd.path);

              return (
                <div 
                  key={pl.id}
                  className="flex items-center justify-between p-3 rounded-2xl bg-zinc-900/50 border border-white/5 hover:border-white/15 transition-all group"
                >
                  <div 
                    className="flex items-center space-x-3 flex-1 min-w-0 cursor-pointer"
                    onClick={() => {
                      if (trackToAdd) {
                        onAddToPlaylist(pl.id, trackToAdd);
                        onClose();
                      } else if (onPlayPlaylist && count > 0) {
                        onPlayPlaylist(pl);
                        onClose();
                      }
                    }}
                  >
                    <div className="w-10 h-10 rounded-xl bg-zinc-800 flex items-center justify-center text-zinc-400 group-hover:text-rose-400 transition-colors">
                      <ListMusic className="w-5 h-5" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="text-xs font-semibold text-white truncate">{pl.name}</p>
                      <p className="text-[11px] text-zinc-400 font-mono">{count} canciones</p>
                    </div>
                  </div>

                  <div className="flex items-center space-x-2">
                    {trackToAdd ? (
                      <button
                        onClick={() => {
                          onAddToPlaylist(pl.id, trackToAdd);
                          onClose();
                        }}
                        disabled={hasTrack}
                        className={`px-3 py-1.5 rounded-xl text-xs font-medium transition-all ${
                          hasTrack 
                            ? 'bg-white/5 text-zinc-500 cursor-default' 
                            : 'bg-rose-500 hover:bg-rose-600 text-white active:scale-95'
                        }`}
                      >
                        {hasTrack ? 'Agregada' : 'Agregar'}
                      </button>
                    ) : (
                      <>
                        {count > 0 && onPlayPlaylist && (
                          <button
                            onClick={() => {
                              onPlayPlaylist(pl);
                              onClose();
                            }}
                            className="p-2 rounded-xl bg-white/5 hover:bg-white/10 text-zinc-300 hover:text-white transition-colors"
                            title="Reproducir lista"
                          >
                            <Play className="w-3.5 h-3.5 fill-current" />
                          </button>
                        )}
                        {onDeletePlaylist && (
                          <button
                            onClick={() => onDeletePlaylist(pl.id)}
                            className="p-2 rounded-xl text-zinc-500 hover:text-rose-400 transition-colors"
                            title="Eliminar lista"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        )}
                      </>
                    )}
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
