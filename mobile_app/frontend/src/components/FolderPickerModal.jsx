import React, { useState, useEffect } from 'react';
import { 
  X, Folder, FolderPlus, FolderCheck, Check, Trash2, ChevronRight, 
  ChevronLeft, ShieldCheck, Clock, ArrowDownCircle, HardDrive, RefreshCw, 
  Plus, Music, MicOff, Sliders, AlertCircle
} from 'lucide-react';
import { getLibraryConfig, setLibraryConfig, listDirectories } from '../apiClient';

export default function FolderPickerModal({ isOpen, onClose, onConfigSaved }) {
  const [config, setConfig] = useState(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [activeTab, setActiveTab] = useState('settings'); // 'settings' | 'browser'

  // Estado del explorador de carpetas
  const [currentPath, setCurrentPath] = useState('/storage/emulated/0');
  const [parentPath, setParentPath] = useState(null);
  const [dirList, setDirList] = useState([]);
  const [isBrowsing, setIsBrowsing] = useState(false);
  const [manualPath, setManualPath] = useState('');
  const [showManualInput, setShowManualInput] = useState(false);
  const [statusMsg, setStatusMsg] = useState('');

  useEffect(() => {
    if (isOpen) {
      loadConfig();
    }
  }, [isOpen]);

  const loadConfig = async () => {
    setIsLoading(true);
    try {
      const cfg = await getLibraryConfig();
      setConfig(cfg);
      if (cfg.musicDirectory) {
        setCurrentPath(cfg.musicDirectory);
      }
    } catch (e) {
      console.error('Error cargando configuración:', e);
    } finally {
      setIsLoading(false);
    }
  };

  const loadDirectory = async (targetPath) => {
    setIsBrowsing(true);
    try {
      const res = await listDirectories(targetPath);
      setCurrentPath(res.currentPath || targetPath);
      setParentPath(res.parentPath || null);
      setDirList(res.directories || []);
    } catch (e) {
      console.error('Error navegando directorios:', e);
    } finally {
      setIsBrowsing(false);
    }
  };

  const handleOpenBrowser = (startPath) => {
    setActiveTab('browser');
    loadDirectory(startPath || config?.musicDirectory || '/storage/emulated/0/Music');
  };

  const handleSetPrimaryDirectory = (path) => {
    if (window.navigator?.vibrate) window.navigator.vibrate(12);
    setConfig(prev => ({
      ...prev,
      musicDirectory: path
    }));
    setStatusMsg(`Carpeta principal asignada: ${path.split('/').pop()}`);
    setTimeout(() => setStatusMsg(''), 2500);
  };

  const handleAddCustomFolder = (path) => {
    if (window.navigator?.vibrate) window.navigator.vibrate(12);
    if (!config.customFolders.includes(path) && path !== config.musicDirectory) {
      setConfig(prev => ({
        ...prev,
        customFolders: [...prev.customFolders, path]
      }));
      setStatusMsg(`Carpeta agregada: ${path.split('/').pop()}`);
      setTimeout(() => setStatusMsg(''), 2500);
    }
  };

  const handleRemoveCustomFolder = (path) => {
    if (window.navigator?.vibrate) window.navigator.vibrate(12);
    setConfig(prev => ({
      ...prev,
      customFolders: prev.customFolders.filter(f => f !== path)
    }));
  };

  const handleSave = async () => {
    if (isSaving || !config) return;
    setIsSaving(true);
    if (window.navigator?.vibrate) window.navigator.vibrate(20);

    try {
      await setLibraryConfig(config);
      onConfigSaved?.(config);
      onClose();
    } catch (e) {
      alert('Error guardando configuración de carpetas: ' + e.message);
    } finally {
      setIsSaving(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div 
        className="w-full max-w-lg max-h-[92vh] flex flex-col apple-glass-heavy rounded-t-3xl sm:rounded-3xl border border-white/10 shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Cabecera Apple Style */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/10">
          <div className="flex items-center space-x-2.5">
            <div className="w-8 h-8 rounded-xl bg-rose-500/10 flex items-center justify-center text-rose-400">
              <Folder className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-white tracking-wide">Ubicación de Música</h3>
              <p className="text-[11px] text-zinc-400">Elige dónde buscar y guardar tus canciones</p>
            </div>
          </div>
          <button 
            onClick={onClose}
            className="p-1.5 rounded-full text-zinc-400 hover:text-white bg-white/5 active:scale-95 transition-transform"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Notificación de estado temporal */}
        {statusMsg && (
          <div className="px-4 py-2 bg-rose-500/20 border-b border-rose-500/30 text-rose-300 text-xs font-medium flex items-center gap-2 animate-fade-in">
            <Check className="w-3.5 h-3.5" />
            <span>{statusMsg}</span>
          </div>
        )}

        {/* Segmented Control (Pestañas) */}
        <div className="p-3 border-b border-white/5 bg-zinc-950/40">
          <div className="flex rounded-xl bg-white/5 p-1 border border-white/5">
            <button
              onClick={() => setActiveTab('settings')}
              className={`flex-1 py-1.5 text-xs font-medium rounded-lg transition-all ${
                activeTab === 'settings' 
                  ? 'bg-rose-600 text-white shadow-md' 
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              Carpetas y Filtros
            </button>
            <button
              onClick={() => handleOpenBrowser(config?.musicDirectory)}
              className={`flex-1 py-1.5 text-xs font-medium rounded-lg transition-all flex items-center justify-center gap-1.5 ${
                activeTab === 'browser' 
                  ? 'bg-rose-600 text-white shadow-md' 
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              <HardDrive className="w-3.5 h-3.5" />
              <span>Explorar Disco</span>
            </button>
          </div>
        </div>

        {/* Contenido Modal */}
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          {isLoading ? (
            <div className="py-12 flex flex-col items-center justify-center text-zinc-500 space-y-2">
              <RefreshCw className="w-6 h-6 animate-spin text-rose-500" />
              <span className="text-xs">Cargando carpetas de almacenamiento...</span>
            </div>
          ) : activeTab === 'settings' ? (
            <>
              {/* Tarjeta Carpeta Principal Activa */}
              <div className="space-y-1.5">
                <span className="text-[11px] font-semibold text-zinc-400 uppercase tracking-wider px-1">
                  Carpeta Principal de Música
                </span>
                <div className="p-3.5 rounded-2xl bg-white/5 border border-white/10 space-y-2.5">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <div className="w-7 h-7 rounded-lg bg-emerald-500/20 text-emerald-400 flex items-center justify-center">
                        <FolderCheck className="w-4 h-4" />
                      </div>
                      <span className="text-xs font-semibold text-white">Almacén de Biblioteca y Descargas</span>
                    </div>
                    <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400">
                      Activa
                    </span>
                  </div>
                  <p className="font-mono text-xs text-zinc-300 bg-black/40 p-2 rounded-xl border border-white/5 break-all">
                    {config?.musicDirectory || '/storage/emulated/0/Music/Biblioteca'}
                  </p>
                  <p className="text-[11px] text-zinc-400 leading-relaxed">
                    Aquí se guardan tus nuevas descargas y las carátulas oficiales protegidas con .nomedia para no aparecer en la galería.
                  </p>
                  <button
                    onClick={() => handleOpenBrowser(config?.musicDirectory)}
                    className="w-full py-2 px-3 rounded-xl bg-white/10 hover:bg-white/15 text-white text-xs font-medium flex items-center justify-center gap-1.5 active:scale-[0.99] transition-transform border border-white/10"
                  >
                    <Folder className="w-3.5 h-3.5 text-rose-400" />
                    <span>Cambiar carpeta principal...</span>
                  </button>
                </div>
              </div>

              {/* Ubicaciones Rápidas Recomendadas */}
              <div className="space-y-1.5">
                <span className="text-[11px] font-semibold text-zinc-400 uppercase tracking-wider px-1">
                  Ubicaciones Recomendadas en el Teléfono
                </span>
                <div className="rounded-2xl bg-white/5 border border-white/10 divide-y divide-white/5 overflow-hidden">
                  {(config?.detectedFolders || []).map((fol) => {
                    const isSelected = config?.musicDirectory === fol.path;
                    return (
                      <div
                        key={fol.path}
                        onClick={() => handleSetPrimaryDirectory(fol.path)}
                        className={`p-3.5 flex items-center justify-between cursor-pointer transition-colors ${
                          isSelected ? 'bg-rose-500/10' : 'hover:bg-white/5'
                        }`}
                      >
                        <div className="flex items-center gap-3">
                          <div className={`w-8 h-8 rounded-xl flex items-center justify-center ${
                            isSelected ? 'bg-rose-500/20 text-rose-400' : 'bg-white/5 text-zinc-400'
                          }`}>
                            <Music className="w-4 h-4" />
                          </div>
                          <div>
                            <div className="flex items-center gap-2">
                              <span className="text-xs font-semibold text-white">{fol.name}</span>
                              {fol.isDefault && (
                                <span className="text-[9px] font-medium px-1.5 py-0.2 rounded bg-rose-500/20 text-rose-300">
                                  Recomendada
                                </span>
                              )}
                            </div>
                            <p className="text-[11px] font-mono text-zinc-400 truncate max-w-[210px] sm:max-w-xs">
                              {fol.path}
                            </p>
                          </div>
                        </div>
                        <div className="flex items-center gap-2">
                          {fol.audioCount > 0 && (
                            <span className="text-[10px] font-mono text-zinc-400 px-2 py-0.5 rounded-full bg-white/5">
                              {fol.audioCount} temas
                            </span>
                          )}
                          <div className={`w-5 h-5 rounded-full flex items-center justify-center border ${
                            isSelected ? 'bg-rose-600 border-rose-500 text-white' : 'border-zinc-600'
                          }`}>
                            {isSelected && <Check className="w-3 h-3 stroke-[3]" />}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>

              {/* Carpetas Adicionales del Usuario */}
              <div className="space-y-1.5">
                <div className="flex items-center justify-between px-1">
                  <span className="text-[11px] font-semibold text-zinc-400 uppercase tracking-wider">
                    Carpetas Adicionales de Música
                  </span>
                  <button
                    onClick={() => handleOpenBrowser('/storage/emulated/0')}
                    className="text-xs text-rose-400 hover:text-rose-300 flex items-center gap-1 font-medium"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    <span>Añadir</span>
                  </button>
                </div>

                {config?.customFolders?.length === 0 ? (
                  <div className="p-4 rounded-2xl bg-white/5 border border-white/5 text-center text-xs text-zinc-500">
                    No has añadido carpetas secundarias. Puedes añadir cualquier carpeta de tu almacenamiento interno o tarjeta SD.
                  </div>
                ) : (
                  <div className="rounded-2xl bg-white/5 border border-white/10 divide-y divide-white/5 overflow-hidden">
                    {config.customFolders.map(folder => (
                      <div key={folder} className="p-3 flex items-center justify-between">
                        <div className="flex items-center gap-2.5 overflow-hidden pr-2">
                          <Folder className="w-4 h-4 text-zinc-400 shrink-0" />
                          <span className="font-mono text-xs text-zinc-200 truncate">{folder}</span>
                        </div>
                        <button
                          onClick={() => handleRemoveCustomFolder(folder)}
                          className="p-1.5 rounded-lg text-zinc-400 hover:text-rose-400 hover:bg-rose-500/10 transition-colors"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Modo de Escaneo */}
              <div className="space-y-1.5">
                <span className="text-[11px] font-semibold text-zinc-400 uppercase tracking-wider px-1">
                  Alcance del Escaneo
                </span>
                <div className="rounded-2xl bg-white/5 border border-white/10 divide-y divide-white/5 overflow-hidden">
                  <div
                    onClick={() => {
                      if (window.navigator?.vibrate) window.navigator.vibrate(10);
                      setConfig(prev => ({ ...prev, scanMode: 'selected' }));
                    }}
                    className={`p-3.5 flex items-center justify-between cursor-pointer ${
                      config?.scanMode === 'selected' ? 'bg-rose-500/10' : 'hover:bg-white/5'
                    }`}
                  >
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-semibold text-white">Solo carpetas seleccionadas</span>
                        <span className="text-[9px] font-medium px-1.5 py-0.2 rounded bg-emerald-500/20 text-emerald-400">
                          Recomendado
                        </span>
                      </div>
                      <p className="text-[11px] text-zinc-400">Escaneo ultrarrápido sin mezclar audios externos ni notas de voz</p>
                    </div>
                    <div className={`w-5 h-5 rounded-full flex items-center justify-center border ${
                      config?.scanMode === 'selected' ? 'bg-rose-600 border-rose-500 text-white' : 'border-zinc-600'
                    }`}>
                      {config?.scanMode === 'selected' && <Check className="w-3 h-3 stroke-[3]" />}
                    </div>
                  </div>

                  <div
                    onClick={() => {
                      if (window.navigator?.vibrate) window.navigator.vibrate(10);
                      setConfig(prev => ({ ...prev, scanMode: 'all_music' }));
                    }}
                    className={`p-3.5 flex items-center justify-between cursor-pointer ${
                      config?.scanMode === 'all_music' ? 'bg-rose-500/10' : 'hover:bg-white/5'
                    }`}
                  >
                    <div>
                      <span className="text-xs font-semibold text-white">Toda la carpeta Música (/sdcard/Music/)</span>
                      <p className="text-[11px] text-zinc-400">Escanea todas las carpetas dentro del directorio estándar de música</p>
                    </div>
                    <div className={`w-5 h-5 rounded-full flex items-center justify-center border ${
                      config?.scanMode === 'all_music' ? 'bg-rose-600 border-rose-500 text-white' : 'border-zinc-600'
                    }`}>
                      {config?.scanMode === 'all_music' && <Check className="w-3 h-3 stroke-[3]" />}
                    </div>
                  </div>

                  <div
                    onClick={() => {
                      if (window.navigator?.vibrate) window.navigator.vibrate(10);
                      setConfig(prev => ({ ...prev, scanMode: 'device' }));
                    }}
                    className={`p-3.5 flex items-center justify-between cursor-pointer ${
                      config?.scanMode === 'device' ? 'bg-rose-500/10' : 'hover:bg-white/5'
                    }`}
                  >
                    <div>
                      <span className="text-xs font-semibold text-white">Todo el dispositivo (MediaStore filtrado)</span>
                      <p className="text-[11px] text-zinc-400">Busca en todo el teléfono excluyendo audios de WhatsApp y notas de voz</p>
                    </div>
                    <div className={`w-5 h-5 rounded-full flex items-center justify-center border ${
                      config?.scanMode === 'device' ? 'bg-rose-600 border-rose-500 text-white' : 'border-zinc-600'
                    }`}>
                      {config?.scanMode === 'device' && <Check className="w-3 h-3 stroke-[3]" />}
                    </div>
                  </div>
                </div>
              </div>

              {/* Filtros de Limpieza (Switches Apple Style) */}
              <div className="space-y-1.5">
                <span className="text-[11px] font-semibold text-zinc-400 uppercase tracking-wider px-1">
                  Filtros de Limpieza Automática
                </span>
                <div className="rounded-2xl bg-white/5 border border-white/10 divide-y divide-white/5 overflow-hidden">
                  
                  {/* Switch WhatsApp / Telegram */}
                  <div className="p-3.5 flex items-center justify-between">
                    <div className="flex items-center gap-3 pr-2">
                      <div className="w-8 h-8 rounded-xl bg-emerald-500/10 text-emerald-400 flex items-center justify-center shrink-0">
                        <ShieldCheck className="w-4 h-4" />
                      </div>
                      <div>
                        <span className="text-xs font-semibold text-white">Ignorar audios de WhatsApp y Telegram</span>
                        <p className="text-[11px] text-zinc-400">Oculta notas de voz, mensajes de audio y carpetas PTT</p>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        if (window.navigator?.vibrate) window.navigator.vibrate(10);
                        setConfig(prev => ({ ...prev, ignoreWhatsApp: !prev.ignoreWhatsApp }));
                      }}
                      className={`w-11 h-6 flex items-center rounded-full p-1 transition-colors duration-200 ease-in-out shrink-0 ${
                        config?.ignoreWhatsApp ? 'bg-rose-600' : 'bg-zinc-700'
                      }`}
                    >
                      <div className={`bg-white w-4 h-4 rounded-full shadow-md transform transition-transform duration-200 ease-in-out ${
                        config?.ignoreWhatsApp ? 'translate-x-5' : 'translate-x-0'
                      }`} />
                    </button>
                  </div>

                  {/* Switch Grabaciones y Llamadas */}
                  <div className="p-3.5 flex items-center justify-between">
                    <div className="flex items-center gap-3 pr-2">
                      <div className="w-8 h-8 rounded-xl bg-amber-500/10 text-amber-400 flex items-center justify-center shrink-0">
                        <MicOff className="w-4 h-4" />
                      </div>
                      <div>
                        <span className="text-xs font-semibold text-white">Ignorar grabaciones de llamadas y voz</span>
                        <p className="text-[11px] text-zinc-400">Oculta grabaciones de MIUI, SoundRecorder y llamadas</p>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        if (window.navigator?.vibrate) window.navigator.vibrate(10);
                        setConfig(prev => ({ ...prev, ignoreRecordings: !prev.ignoreRecordings }));
                      }}
                      className={`w-11 h-6 flex items-center rounded-full p-1 transition-colors duration-200 ease-in-out shrink-0 ${
                        config?.ignoreRecordings ? 'bg-rose-600' : 'bg-zinc-700'
                      }`}
                    >
                      <div className={`bg-white w-4 h-4 rounded-full shadow-md transform transition-transform duration-200 ease-in-out ${
                        config?.ignoreRecordings ? 'translate-x-5' : 'translate-x-0'
                      }`} />
                    </button>
                  </div>

                  {/* Switch Duración Mínima */}
                  <div className="p-3.5 flex items-center justify-between">
                    <div className="flex items-center gap-3 pr-2">
                      <div className="w-8 h-8 rounded-xl bg-purple-500/10 text-purple-400 flex items-center justify-center shrink-0">
                        <Clock className="w-4 h-4" />
                      </div>
                      <div>
                        <span className="text-xs font-semibold text-white">Filtrar audios cortos (menos de 30s)</span>
                        <p className="text-[11px] text-zinc-400">Descarta tonos de notificación y efectos de sonido</p>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        if (window.navigator?.vibrate) window.navigator.vibrate(10);
                        setConfig(prev => ({ ...prev, minDurationSec: prev.minDurationSec > 0 ? 0 : 30 }));
                      }}
                      className={`w-11 h-6 flex items-center rounded-full p-1 transition-colors duration-200 ease-in-out shrink-0 ${
                        config?.minDurationSec > 0 ? 'bg-rose-600' : 'bg-zinc-700'
                      }`}
                    >
                      <div className={`bg-white w-4 h-4 rounded-full shadow-md transform transition-transform duration-200 ease-in-out ${
                        config?.minDurationSec > 0 ? 'translate-x-5' : 'translate-x-0'
                      }`} />
                    </button>
                  </div>

                  {/* Switch Carpeta Descargas */}
                  <div className="p-3.5 flex items-center justify-between">
                    <div className="flex items-center gap-3 pr-2">
                      <div className="w-8 h-8 rounded-xl bg-blue-500/10 text-blue-400 flex items-center justify-center shrink-0">
                        <ArrowDownCircle className="w-4 h-4" />
                      </div>
                      <div>
                        <span className="text-xs font-semibold text-white">Incluir carpeta Descargas (/Download)</span>
                        <p className="text-[11px] text-zinc-400">Escanea audios sueltos descargados desde el navegador</p>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        if (window.navigator?.vibrate) window.navigator.vibrate(10);
                        setConfig(prev => ({ ...prev, includeDownloads: !prev.includeDownloads }));
                      }}
                      className={`w-11 h-6 flex items-center rounded-full p-1 transition-colors duration-200 ease-in-out shrink-0 ${
                        config?.includeDownloads ? 'bg-rose-600' : 'bg-zinc-700'
                      }`}
                    >
                      <div className={`bg-white w-4 h-4 rounded-full shadow-md transform transition-transform duration-200 ease-in-out ${
                        config?.includeDownloads ? 'translate-x-5' : 'translate-x-0'
                      }`} />
                    </button>
                  </div>

                </div>
              </div>
            </>
          ) : (
            /* Vista del Explorador de Almacenamiento */
            <div className="space-y-3">
              {/* Barra de navegación de ruta */}
              <div className="p-3 rounded-2xl bg-white/5 border border-white/10 space-y-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2 overflow-hidden">
                    {parentPath && (
                      <button
                        onClick={() => loadDirectory(parentPath)}
                        className="p-1 rounded-lg bg-white/10 text-zinc-300 hover:text-white"
                      >
                        <ChevronLeft className="w-4 h-4" />
                      </button>
                    )}
                    <span className="text-xs font-semibold text-white truncate font-mono">
                      {currentPath.replace('/storage/emulated/0', 'Almacenamiento')}
                    </span>
                  </div>
                  <button
                    onClick={() => setShowManualInput(!showManualInput)}
                    className="text-[11px] text-rose-400 hover:text-rose-300 font-medium"
                  >
                    {showManualInput ? 'Ocultar ruta' : 'Escribir ruta'}
                  </button>
                </div>

                {showManualInput && (
                  <div className="pt-2 flex gap-2">
                    <input
                      type="text"
                      value={manualPath}
                      onChange={(e) => setManualPath(e.target.value)}
                      placeholder="/storage/emulated/0/Music"
                      className="flex-1 bg-black/60 border border-white/10 rounded-xl px-3 py-1.5 text-xs text-white placeholder-zinc-500 font-mono"
                    />
                    <button
                      onClick={() => {
                        if (manualPath.trim()) loadDirectory(manualPath.trim());
                      }}
                      className="px-3 py-1.5 rounded-xl bg-white/10 hover:bg-white/15 text-white text-xs font-medium"
                    >
                      Ir
                    </button>
                  </div>
                )}

                {/* Acciones sobre la carpeta actual */}
                <div className="flex gap-2 pt-1">
                  <button
                    onClick={() => handleSetPrimaryDirectory(currentPath)}
                    className="flex-1 py-2 px-2.5 rounded-xl bg-rose-600 hover:bg-rose-500 text-white text-xs font-semibold flex items-center justify-center gap-1.5 shadow-md active:scale-[0.99] transition-transform"
                  >
                    <FolderCheck className="w-3.5 h-3.5" />
                    <span>Usar como Principal</span>
                  </button>
                  <button
                    onClick={() => handleAddCustomFolder(currentPath)}
                    className="py-2 px-3 rounded-xl bg-white/10 hover:bg-white/15 text-zinc-200 text-xs font-medium flex items-center justify-center gap-1.5 active:scale-[0.99] transition-transform border border-white/10"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    <span>Añadir secundaria</span>
                  </button>
                </div>
              </div>

              {/* Lista de subcarpetas */}
              <div className="rounded-2xl bg-white/5 border border-white/10 divide-y divide-white/5 overflow-hidden">
                {isBrowsing ? (
                  <div className="py-8 flex items-center justify-center text-zinc-500 gap-2">
                    <RefreshCw className="w-4 h-4 animate-spin text-rose-500" />
                    <span className="text-xs">Leyendo carpetas...</span>
                  </div>
                ) : dirList.length === 0 ? (
                  <div className="py-8 text-center text-zinc-500 text-xs">
                    No hay subcarpetas en este directorio.
                  </div>
                ) : (
                  dirList.map(item => (
                    <div
                      key={item.path}
                      onClick={() => loadDirectory(item.path)}
                      className="p-3.5 flex items-center justify-between cursor-pointer hover:bg-white/5 transition-colors"
                    >
                      <div className="flex items-center gap-3 overflow-hidden pr-2">
                        <Folder className="w-4 h-4 text-rose-400 shrink-0" />
                        <span className="text-xs font-medium text-white truncate">{item.name}</span>
                      </div>
                      <div className="flex items-center gap-2 shrink-0">
                        {item.audioCount > 0 && (
                          <span className="text-[10px] font-mono text-emerald-400 bg-emerald-500/10 px-2 py-0.5 rounded-full">
                            {item.audioCount} temas
                          </span>
                        )}
                        <ChevronRight className="w-4 h-4 text-zinc-500" />
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}
        </div>

        {/* Barra Inferior con Botón de Guardado */}
        <div className="p-4 border-t border-white/10 bg-zinc-950/80 backdrop-blur-xl flex items-center justify-between gap-3">
          <button
            onClick={onClose}
            className="py-2.5 px-4 rounded-xl text-xs font-semibold text-zinc-400 hover:text-white bg-white/5 hover:bg-white/10 active:scale-95 transition-all"
          >
            Cancelar
          </button>
          <button
            onClick={handleSave}
            disabled={isSaving}
            className="flex-1 py-2.5 px-4 rounded-xl bg-gradient-to-r from-rose-600 to-pink-600 hover:from-rose-500 hover:to-pink-500 text-white text-xs font-semibold flex items-center justify-center gap-2 shadow-lg active:scale-[0.99] transition-all disabled:opacity-50"
          >
            {isSaving ? (
              <>
                <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                <span>Aplicando cambios...</span>
              </>
            ) : (
              <>
                <Check className="w-3.5 h-3.5" />
                <span>Guardar y Re-escanear Biblioteca</span>
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
}
