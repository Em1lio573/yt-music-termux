import React, { useState, useEffect } from 'react';
import { X, Sliders, Volume2, RotateCcw } from 'lucide-react';
import { audioDsp, EQ_PRESETS } from '../audioDsp';

const BAND_LABELS = [
  { freq: '60 Hz', name: 'Sub-Graves' },
  { freq: '230 Hz', name: 'Graves' },
  { freq: '910 Hz', name: 'Medios' },
  { freq: '3.6 kHz', name: 'Presencia' },
  { freq: '14 kHz', name: 'Agudos' },
];

export default function EqualizerModal({ isOpen, onClose }) {
  const [eqState, setEqState] = useState(() => audioDsp.getState());

  useEffect(() => {
    if (isOpen) {
      setEqState(audioDsp.getState());
    }
  }, [isOpen]);

  const handleToggleEnable = (e) => {
    const newVal = e.target.checked;
    audioDsp.setEnabled(newVal);
    setEqState(audioDsp.getState());
    if (window.navigator?.vibrate) window.navigator.vibrate(10);
  };

  const handleBandChange = (index, value) => {
    const val = parseFloat(value);
    audioDsp.setBandGain(index, val);
    setEqState(audioDsp.getState());
  };

  const handleBassChange = (value) => {
    const val = parseFloat(value);
    audioDsp.setBassBoost(val);
    setEqState(audioDsp.getState());
  };

  const handlePresetSelect = (presetKey) => {
    audioDsp.setPreset(presetKey);
    setEqState(audioDsp.getState());
    if (window.navigator?.vibrate) window.navigator.vibrate(10);
  };

  const handleReset = () => {
    audioDsp.setPreset('flat');
    setEqState(audioDsp.getState());
    if (window.navigator?.vibrate) window.navigator.vibrate(10);
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div 
        className="w-full max-w-lg max-h-[92vh] flex flex-col apple-glass-heavy rounded-t-3xl sm:rounded-3xl border border-white/10 shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Cabecera */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-white/10">
          <div className="flex items-center space-x-2.5">
            <div className="w-8 h-8 rounded-xl bg-rose-500/10 flex items-center justify-center text-rose-400">
              <Sliders className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-semibold text-white tracking-wide">Ecualizador de Audio</h3>
              <p className="text-[11px] text-zinc-400">Fidelidad de estudio y DSP de 5 bandas</p>
            </div>
          </div>

          <div className="flex items-center space-x-3">
            {/* Interruptor de activación */}
            <label className="relative inline-flex items-center cursor-pointer">
              <input 
                type="checkbox" 
                checked={eqState.enabled} 
                onChange={handleToggleEnable}
                className="sr-only peer" 
              />
              <div className="w-9 h-5 bg-zinc-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-rose-500"></div>
            </label>

            <button 
              onClick={onClose}
              className="p-1.5 rounded-full text-zinc-400 hover:text-white bg-white/5 active:scale-95 transition-transform"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Contenedor desplazable */}
        <div className="flex-1 overflow-y-auto p-5 no-scrollbar space-y-6">
          {/* Selector de Presets */}
          <div>
            <div className="flex items-center justify-between mb-2.5">
              <span className="text-xs font-semibold text-zinc-300 uppercase tracking-wider">Ajustes Predefinidos</span>
              <button 
                onClick={handleReset}
                className="flex items-center space-x-1 text-[11px] text-zinc-400 hover:text-white transition-colors"
              >
                <RotateCcw className="w-3 h-3" />
                <span>Restablecer</span>
              </button>
            </div>

            <div className="flex items-center space-x-2 overflow-x-auto no-scrollbar pb-1">
              {Object.entries(EQ_PRESETS).map(([key, p]) => {
                const isSelected = eqState.preset === key;
                return (
                  <button
                    key={key}
                    onClick={() => handlePresetSelect(key)}
                    className={`px-3 py-1.5 rounded-xl text-xs font-medium whitespace-nowrap transition-all active:scale-95 ${
                      isSelected 
                        ? 'bg-rose-500 text-white shadow-lg shadow-rose-500/20' 
                        : 'bg-white/5 text-zinc-400 hover:text-white hover:bg-white/10'
                    }`}
                  >
                    {p.name}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Faders Verticales de 5 Bandas */}
          <div className={`p-4 rounded-2xl bg-zinc-900/60 border border-white/5 transition-opacity ${eqState.enabled ? 'opacity-100' : 'opacity-40 pointer-events-none'}`}>
            <div className="grid grid-cols-5 gap-2 text-center">
              {BAND_LABELS.map((band, idx) => {
                const gain = eqState.gains[idx] || 0;
                return (
                  <div key={idx} className="flex flex-col items-center space-y-2">
                    <span className="text-[10px] font-mono text-zinc-400">
                      {gain > 0 ? `+${gain}` : gain} dB
                    </span>

                    {/* Fader vertical */}
                    <div className="relative h-40 w-8 flex items-center justify-center">
                      {/* Línea de fondo con marca de centro */}
                      <div className="absolute w-1 h-full bg-zinc-800 rounded-full"></div>
                      <div className="absolute w-2.5 h-[1px] bg-zinc-600 top-1/2 -translate-y-1/2"></div>

                      <input 
                        type="range"
                        min="-12"
                        max="12"
                        step="1"
                        value={gain}
                        onChange={(e) => handleBandChange(idx, e.target.value)}
                        className="w-40 h-8 -rotate-90 origin-center accent-rose-500 cursor-pointer bg-transparent appearance-none"
                      />
                    </div>

                    <div className="pt-1">
                      <p className="text-[11px] font-semibold text-white">{band.freq}</p>
                      <p className="text-[9px] text-zinc-500 truncate">{band.name}</p>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Refuerzo de Graves (Bass Boost) */}
          <div className={`p-4 rounded-2xl bg-zinc-900/60 border border-white/5 space-y-2 transition-opacity ${eqState.enabled ? 'opacity-100' : 'opacity-40 pointer-events-none'}`}>
            <div className="flex items-center justify-between">
              <div className="flex items-center space-x-2 text-xs font-semibold text-zinc-300">
                <Volume2 className="w-4 h-4 text-rose-400" />
                <span>Refuerzo de Graves (Bass Boost)</span>
              </div>
              <span className="text-xs font-mono text-rose-400 font-semibold">{eqState.bassBoost} / 10</span>
            </div>

            <input 
              type="range"
              min="0"
              max="10"
              step="1"
              value={eqState.bassBoost}
              onChange={(e) => handleBassChange(e.target.value)}
              className="w-full h-1.5 bg-zinc-800 rounded-lg appearance-none cursor-pointer accent-rose-500"
            />
          </div>
        </div>
      </div>
    </div>
  );
}
