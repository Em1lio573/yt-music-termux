/**
 * Módulo de DSP y Ecualizador de Audio (Web Audio API)
 * Proporciona un ecualizador de 5 bandas, Bass Boost y presets profesionales
 * para una experiencia de audio de alta fidelidad estilo Apple Music.
 */

const EQ_FREQUENCIES = [60, 230, 910, 3600, 14000];
const EQ_TYPES = ['lowshelf', 'peaking', 'peaking', 'peaking', 'highshelf'];

export const EQ_PRESETS = {
  flat: { name: 'Plano', gains: [0, 0, 0, 0, 0], bass: 0 },
  bass_boost: { name: 'Refuerzo de Graves', gains: [7, 5, 0, 1, 2], bass: 4 },
  rock: { name: 'Rock', gains: [5, 3, -1, 3, 5], bass: 2 },
  pop: { name: 'Pop', gains: [-1, 2, 4, 2, -1], bass: 1 },
  electronic: { name: 'Electrónica', gains: [6, 4, 0, 3, 4], bass: 3 },
  acoustic: { name: 'Acústico', gains: [3, 2, 1, 3, 3], bass: 0 },
  vocal: { name: 'Voz / Podcast', gains: [-3, 1, 4, 3, -1], bass: 0 },
  jazz: { name: 'Jazz', gains: [3, 1, 2, 2, 3], bass: 1 },
};

class AudioDspManager {
  constructor() {
    this.audioCtx = null;
    this.sourceNode = null;
    this.filters = [];
    this.bassNode = null;
    this.outGain = null;
    this.audioElement = null;
    this.initialized = false;

    // Cargar estado inicial persistido
    this.enabled = localStorage.getItem('ytm_eq_enabled') !== 'false';
    this.currentPreset = localStorage.getItem('ytm_eq_preset') || 'flat';
    
    try {
      const savedGains = localStorage.getItem('ytm_eq_gains');
      this.gains = savedGains ? JSON.parse(savedGains) : [0, 0, 0, 0, 0];
    } catch (e) {
      this.gains = [0, 0, 0, 0, 0];
    }

    try {
      const savedBass = localStorage.getItem('ytm_eq_bass');
      this.bassBoost = savedBass ? parseFloat(savedBass) : 0;
    } catch (e) {
      this.bassBoost = 0;
    }
  }

  attach(audioElement) {
    if (!audioElement || this.audioElement === audioElement) return;
    this.audioElement = audioElement;

    const setupContext = () => {
      if (this.initialized) {
        if (this.audioCtx && this.audioCtx.state === 'suspended') {
          this.audioCtx.resume().catch(() => {});
        }
        return;
      }

      try {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (!AudioContextClass) return;

        this.audioCtx = new AudioContextClass();
        this.sourceNode = this.audioCtx.createMediaElementSource(this.audioElement);

        // Crear los 5 filtros de banda
        this.filters = EQ_FREQUENCIES.map((freq, idx) => {
          const filter = this.audioCtx.createBiquadFilter();
          filter.type = EQ_TYPES[idx];
          filter.frequency.value = freq;
          if (EQ_TYPES[idx] === 'peaking') {
            filter.Q.value = 1.0;
          }
          filter.gain.value = this.enabled ? this.gains[idx] : 0;
          return filter;
        });

        // Nodo para Bass Booster adicional
        this.bassNode = this.audioCtx.createBiquadFilter();
        this.bassNode.type = 'lowshelf';
        this.bassNode.frequency.value = 80;
        this.bassNode.gain.value = this.enabled ? this.bassBoost * 1.5 : 0;

        // Nodo de ganancia de salida
        this.outGain = this.audioCtx.createGain();
        this.outGain.gain.value = 1.0;

        // Conectar cadena: source -> filter0 -> filter1 -> filter2 -> filter3 -> filter4 -> bassNode -> outGain -> destination
        let prev = this.sourceNode;
        for (const f of this.filters) {
          prev.connect(f);
          prev = f;
        }
        prev.connect(this.bassNode);
        this.bassNode.connect(this.outGain);
        this.outGain.connect(this.audioCtx.destination);

        this.initialized = true;

        if (this.audioCtx.state === 'suspended') {
          this.audioCtx.resume().catch(() => {});
        }
      } catch (err) {
        console.warn('AudioDspManager attach error:', err);
      }
    };

    // Inicializar con interacción del usuario para evitar políticas de autoplay
    const onPlayOrClick = () => {
      setupContext();
      window.removeEventListener('click', onPlayOrClick);
      this.audioElement?.removeEventListener('play', onPlayOrClick);
    };

    window.addEventListener('click', onPlayOrClick, { once: true });
    this.audioElement.addEventListener('play', onPlayOrClick, { once: true });
  }

  ensureResumed() {
    if (this.audioCtx && this.audioCtx.state === 'suspended') {
      this.audioCtx.resume().catch(() => {});
    }
  }

  setEnabled(enabled) {
    this.enabled = enabled;
    try {
      localStorage.setItem('ytm_eq_enabled', enabled ? 'true' : 'false');
    } catch (e) {}

    this.filters.forEach((filter, idx) => {
      if (filter) {
        filter.gain.setTargetAtTime(enabled ? this.gains[idx] : 0, this.audioCtx?.currentTime || 0, 0.05);
      }
    });

    if (this.bassNode) {
      this.bassNode.gain.setTargetAtTime(enabled ? this.bassBoost * 1.5 : 0, this.audioCtx?.currentTime || 0, 0.05);
    }
  }

  setBandGain(bandIndex, gainDb) {
    if (bandIndex < 0 || bandIndex >= this.gains.length) return;
    this.gains[bandIndex] = gainDb;
    this.currentPreset = 'custom';

    try {
      localStorage.setItem('ytm_eq_gains', JSON.stringify(this.gains));
      localStorage.setItem('ytm_eq_preset', 'custom');
    } catch (e) {}

    if (this.enabled && this.filters[bandIndex] && this.audioCtx) {
      this.filters[bandIndex].gain.setTargetAtTime(gainDb, this.audioCtx.currentTime, 0.03);
    }
  }

  setBassBoost(level) {
    this.bassBoost = Math.max(0, Math.min(10, level));
    try {
      localStorage.setItem('ytm_eq_bass', this.bassBoost.toString());
    } catch (e) {}

    if (this.enabled && this.bassNode && this.audioCtx) {
      this.bassNode.gain.setTargetAtTime(this.bassBoost * 1.5, this.audioCtx.currentTime, 0.05);
    }
  }

  setPreset(presetKey) {
    const p = EQ_PRESETS[presetKey];
    if (!p) return;

    this.currentPreset = presetKey;
    this.gains = [...p.gains];
    this.bassBoost = p.bass;

    try {
      localStorage.setItem('ytm_eq_preset', presetKey);
      localStorage.setItem('ytm_eq_gains', JSON.stringify(this.gains));
      localStorage.setItem('ytm_eq_bass', this.bassBoost.toString());
    } catch (e) {}

    this.filters.forEach((filter, idx) => {
      if (filter && this.audioCtx) {
        filter.gain.setTargetAtTime(this.enabled ? this.gains[idx] : 0, this.audioCtx.currentTime, 0.05);
      }
    });

    if (this.bassNode && this.audioCtx) {
      this.bassNode.gain.setTargetAtTime(this.enabled ? this.bassBoost * 1.5 : 0, this.audioCtx.currentTime, 0.05);
    }
  }

  getState() {
    return {
      enabled: this.enabled,
      preset: this.currentPreset,
      gains: [...this.gains],
      bassBoost: this.bassBoost,
      frequencies: [...EQ_FREQUENCIES],
    };
  }
}

export const audioDsp = new AudioDspManager();
