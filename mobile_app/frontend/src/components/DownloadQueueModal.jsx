import React from 'react';
import { 
  X, Download, Check, RefreshCw, Trash2, Play, Clock, 
  AlertCircle, ListMusic, Terminal as TerminalIcon, ChevronDown, 
  ChevronUp, RotateCw, Music, CheckCircle2
} from 'lucide-react';

export default function DownloadQueueModal({
  isOpen,
  onClose,
  queue = [],
  onRemoveItem,
  onClearCompleted,
  onRetryItem,
  onPlayTrack,
  terminalLines = [],
  showTerminal,
  onToggleTerminal,
  terminalContainerRef
}) {
  if (!isOpen) return null;

  const activeItem = queue.find(q => q.status === 'downloading');
  const queuedItems = queue.filter(q => q.status === 'queued');
  const completedItems = queue.filter(q => q.status === 'done');
  const errorItems = queue.filter(q => q.status === 'error');

  const totalActive = (activeItem ? 1 : 0) + queuedItems.length;

  return (
    <div 
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-black/80 backdrop-blur-md animate-fade-in"
      onClick={onClose}
    >
      <div 
        className="w-full max-w-lg max-h-[90vh] flex flex-col apple-glass-heavy rounded-t-3xl sm:rounded-3xl border border-white/10 shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
        style={{ paddingBottom: 'calc(var(--sab, 0px) + 0.75rem)' }}
      >
        {/* Cabecera estilo Apple Glass */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/10 shrink-0">
          <div className="flex items-center space-x-2.5">
            <div className="w-8 h-8 rounded-xl bg-rose-500/10 flex items-center justify-center text-rose-400">
              <ListMusic className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-white tracking-wide">Cola de Descargas</h3>
              <p className="text-[11px] text-zinc-400">
                {totalActive > 0 ? `${totalActive} pendientes` : 'Sin tareas activas'} • {completedItems.length} completadas
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {completedItems.length > 0 && (
              <button
                type="button"
                onClick={onClearCompleted}
                className="px-2.5 py-1 rounded-xl bg-white/5 hover:bg-white/10 text-zinc-400 hover:text-zinc-200 text-[11px] font-medium border border-white/5 active:scale-95 transition-all"
                title="Limpiar canciones completadas de la lista"
              >
                Limpiar listas
              </button>
            )}
            <button 
              type="button"
              onClick={onClose}
              className="p-1.5 rounded-full hover:bg-white/10 text-zinc-400 hover:text-white transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Contenido scrolleable */}
        <div className="flex-1 overflow-y-auto p-4 space-y-4 scrollbar-thin">
          {queue.length === 0 ? (
            <div className="py-16 text-center text-zinc-500 space-y-2">
              <Download className="w-10 h-10 mx-auto text-zinc-600 stroke-[1.5]" />
              <p className="text-sm font-medium text-zinc-400">La cola de descargas está vacía</p>
              <p className="text-xs text-zinc-600 max-w-xs mx-auto">
                Busca cualquier canción en Explorar y pulsa "Bajar" en varias pistas para descargarlas en secuencia.
              </p>
            </div>
          ) : (
            <>
              {/* 1. TAREA EN DESCARGA ACTIVA */}
              {activeItem && (
                <div className="space-y-2">
                  <span className="text-[11px] font-semibold text-rose-400 uppercase tracking-wider px-1 flex items-center gap-1.5">
                    <RefreshCw className="w-3 h-3 animate-spin" /> Descargando ahora
                  </span>
                  <div className="p-3.5 rounded-2xl apple-card border border-rose-500/30 bg-rose-950/15 space-y-3 shadow-md shadow-rose-950/20">
                    <div className="flex items-center gap-3">
                      <div className="relative w-12 h-12 rounded-xl overflow-hidden bg-zinc-800 shrink-0 shadow-md border border-white/10 flex items-center justify-center">
                        {activeItem.item?.cover ? (
                          <img src={activeItem.item.cover} alt="" className="w-full h-full object-cover" />
                        ) : (
                          <div className="w-full h-full bg-gradient-to-tr from-rose-600 to-pink-500 flex items-center justify-center text-white">
                            <Music className="w-5 h-5" />
                          </div>
                        )}
                        <div className="absolute inset-0 bg-black/40 backdrop-blur-[1px] flex items-center justify-center">
                          <RefreshCw className="w-5 h-5 text-white animate-spin" />
                        </div>
                      </div>

                      <div className="min-w-0 flex-1">
                        <div className="flex items-center justify-between gap-2">
                          <h4 className="text-xs font-bold text-white truncate">
                            {activeItem.item?.title || 'Descargando...'}
                          </h4>
                          <span className="text-xs font-mono font-extrabold text-rose-400 shrink-0">
                            {activeItem.percent || 0}%
                          </span>
                        </div>
                        <p className="text-[11px] text-zinc-400 truncate">
                          {activeItem.item?.artist || 'YouTube Music'}
                        </p>
                        <div className="flex items-center justify-between text-[10px] text-zinc-400 mt-1">
                          <span className="text-rose-300 font-medium truncate max-w-[65%]">
                            {activeItem.statusText || 'Procesando...'}
                          </span>
                          <span className="font-mono text-zinc-400 shrink-0 flex items-center gap-1.5">
                            {activeItem.speed && <span>{activeItem.speed}</span>}
                            {activeItem.eta && activeItem.eta !== '--' && (
                              <span className="flex items-center gap-1">
                                <Clock className="w-2.5 h-2.5 text-zinc-500" />
                                {activeItem.eta}
                              </span>
                            )}
                          </span>
                        </div>
                      </div>
                    </div>

                    {/* Barra de progreso fluida */}
                    <div className="w-full h-2 rounded-full bg-white/10 overflow-hidden relative">
                      <div 
                        className="h-full rounded-full bg-gradient-to-r from-rose-600 via-pink-500 to-amber-400 transition-all duration-300 ease-out"
                        style={{ width: `${activeItem.percent || 0}%` }}
                      />
                    </div>
                  </div>
                </div>
              )}

              {/* 2. CANCIONES EN COLA DE ESPERA */}
              {queuedItems.length > 0 && (
                <div className="space-y-2">
                  <span className="text-[11px] font-semibold text-zinc-400 uppercase tracking-wider px-1 flex items-center justify-between">
                    <span>En espera en la cola ({queuedItems.length})</span>
                    <span className="text-[10px] text-zinc-500 font-normal">Descarga secuencial automática</span>
                  </span>
                  <div className="space-y-1.5">
                    {queuedItems.map((qItem, idx) => (
                      <div 
                        key={qItem.id}
                        className="p-2.5 rounded-2xl bg-zinc-900/60 border border-white/5 flex items-center justify-between gap-3 transition-all"
                      >
                        <div className="flex items-center gap-2.5 min-w-0">
                          <span className="w-5 text-center font-mono text-[11px] text-zinc-500 font-semibold shrink-0">
                            #{idx + 1}
                          </span>
                          <div className="w-10 h-10 rounded-xl overflow-hidden bg-zinc-800 shrink-0 relative flex items-center justify-center">
                            {qItem.item?.cover ? (
                              <img src={qItem.item.cover} alt="" className="w-full h-full object-cover" />
                            ) : (
                              <div className="w-full h-full bg-zinc-700 flex items-center justify-center text-zinc-400">
                                <Music className="w-4 h-4" />
                              </div>
                            )}
                          </div>
                          <div className="min-w-0">
                            <h5 className="text-xs font-semibold text-white truncate">
                              {qItem.item?.title}
                            </h5>
                            <p className="text-[10px] text-zinc-400 truncate">
                              {qItem.item?.artist || 'Artista'}
                            </p>
                          </div>
                        </div>

                        <div className="flex items-center gap-1 shrink-0">
                          <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-white/5 text-zinc-400 border border-white/5 flex items-center gap-1">
                            <Clock className="w-2.5 h-2.5 text-zinc-500" /> En espera
                          </span>
                          <button
                            type="button"
                            onClick={() => onRemoveItem(qItem.id)}
                            className="p-1.5 rounded-lg text-zinc-500 hover:text-rose-400 hover:bg-white/5 active:scale-90 transition-all"
                            title="Quitar de la cola"
                          >
                            <X className="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* 3. CANCIONES COMPLETADAS */}
              {completedItems.length > 0 && (
                <div className="space-y-2">
                  <span className="text-[11px] font-semibold text-emerald-400 uppercase tracking-wider px-1">
                    Completadas ({completedItems.length})
                  </span>
                  <div className="space-y-1.5">
                    {completedItems.map((cItem) => (
                      <div 
                        key={cItem.id}
                        className="p-2.5 rounded-2xl bg-emerald-950/10 border border-emerald-500/20 flex items-center justify-between gap-3"
                      >
                        <div className="flex items-center gap-2.5 min-w-0">
                          <div className="w-9 h-9 rounded-xl overflow-hidden bg-zinc-800 shrink-0 relative flex items-center justify-center">
                            {cItem.item?.cover ? (
                              <img src={cItem.item.cover} alt="" className="w-full h-full object-cover" />
                            ) : (
                              <div className="w-full h-full bg-emerald-900/40 flex items-center justify-center text-emerald-300">
                                <Music className="w-4 h-4" />
                              </div>
                            )}
                            <div className="absolute inset-0 bg-emerald-600/60 backdrop-blur-[1px] flex items-center justify-center">
                              <Check className="w-4 h-4 text-white stroke-[3]" />
                            </div>
                          </div>
                          <div className="min-w-0">
                            <h5 className="text-xs font-semibold text-white truncate">
                              {cItem.item?.title}
                            </h5>
                            <p className="text-[10px] text-zinc-400 truncate">
                              {cItem.item?.artist || 'Guardada en biblioteca'}
                            </p>
                          </div>
                        </div>

                        <div className="flex items-center gap-1 shrink-0">
                          <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 flex items-center gap-1">
                            <CheckCircle2 className="w-2.5 h-2.5" /> Listo
                          </span>
                          {onPlayTrack && (
                            <button
                              type="button"
                              onClick={() => {
                                onPlayTrack(cItem.item);
                                onClose();
                              }}
                              className="p-1.5 rounded-lg bg-emerald-500/20 text-emerald-300 hover:bg-emerald-500/30 active:scale-90 transition-all"
                              title="Reproducir ahora"
                            >
                              <Play className="w-3.5 h-3.5 fill-current" />
                            </button>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* 4. CANCIONES CON ERROR */}
              {errorItems.length > 0 && (
                <div className="space-y-2">
                  <span className="text-[11px] font-semibold text-rose-400 uppercase tracking-wider px-1">
                    Fallidas ({errorItems.length})
                  </span>
                  <div className="space-y-1.5">
                    {errorItems.map((eItem) => (
                      <div 
                        key={eItem.id}
                        className="p-2.5 rounded-2xl bg-rose-950/15 border border-rose-500/30 flex items-center justify-between gap-3"
                      >
                        <div className="flex items-center gap-2.5 min-w-0">
                          <div className="w-9 h-9 rounded-xl bg-rose-500/20 text-rose-400 flex items-center justify-center shrink-0">
                            <AlertCircle className="w-4 h-4" />
                          </div>
                          <div className="min-w-0">
                            <h5 className="text-xs font-semibold text-white truncate">
                              {eItem.item?.title}
                            </h5>
                            <p className="text-[10px] text-rose-300 truncate">
                              {eItem.errorMsg || 'Fallo en la descarga'}
                            </p>
                          </div>
                        </div>

                        <div className="flex items-center gap-1 shrink-0">
                          <button
                            type="button"
                            onClick={() => onRetryItem(eItem.id)}
                            className="px-2.5 py-1 rounded-lg bg-rose-500/20 hover:bg-rose-500/30 text-rose-300 text-[11px] font-medium flex items-center gap-1 active:scale-95 transition-all"
                            title="Reintentar descarga"
                          >
                            <RotateCw className="w-3 h-3" /> Reintentar
                          </button>
                          <button
                            type="button"
                            onClick={() => onRemoveItem(eItem.id)}
                            className="p-1.5 rounded-lg text-zinc-500 hover:text-white"
                          >
                            <X className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Consola técnica */}
              <div className="pt-2 border-t border-white/5 space-y-2">
                <button
                  type="button"
                  onClick={onToggleTerminal}
                  className="w-full text-[11px] text-zinc-400 hover:text-white flex items-center justify-between py-2 px-3 rounded-xl bg-white/5 active:scale-95 transition-all"
                >
                  <span className="flex items-center gap-1.5">
                    <TerminalIcon className="w-3.5 h-3.5 text-emerald-400" />
                    <span>Registro técnico en vivo (yt-dlp)</span>
                  </span>
                  {showTerminal ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
                </button>

                {showTerminal && (
                  <div 
                    ref={terminalContainerRef}
                    className="p-2.5 rounded-2xl bg-black/95 border border-white/10 font-mono text-[10px] text-emerald-400/90 max-h-40 overflow-y-auto space-y-1 select-text scrollbar-thin shadow-inner animate-fade-in"
                  >
                    <div className="flex items-center gap-1.5 pb-1 mb-1 border-b border-white/10 text-[9px] text-zinc-500">
                      <span className="w-2 h-2 rounded-full bg-red-500/80"></span>
                      <span className="w-2 h-2 rounded-full bg-yellow-500/80"></span>
                      <span className="w-2 h-2 rounded-full bg-green-500/80"></span>
                      <span className="ml-1 text-zinc-400 font-sans">Consola yt-dlp nativo</span>
                    </div>
                    {terminalLines.length === 0 ? (
                      <div className="text-zinc-600 italic">Esperando salida de descarga...</div>
                    ) : (
                      terminalLines.map((line, idx) => (
                        <div key={idx} className="break-all whitespace-pre-wrap leading-tight">
                          {line}
                        </div>
                      ))
                    )}
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
