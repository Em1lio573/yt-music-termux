## 🎵 Termux YouTube Music Downloader

Un script automatizado para Termux que descarga música de YouTube en Alta Calidad (320kbps), con carátulas, metadatos y conversión a MP3 estéreo. Se integra nativamente con el menú "Compartir" de la app de YouTube.

### ✨ Características

- 🚀 **Integración con Android**: Funciona desde el botón "Compartir" de la app de YouTube.
- 🎧 **Alta Fidelidad**: Descargas forzadas a MP3 320kbps y Estéreo.
- 🖼️ **Metadatos Completos**: Agrega automáticamente Artista, Título y Carátula (Cover Art) al archivo.
- 📂 **Organización**: Guarda los archivos directamente en la carpeta Music del dispositivo.
- 🧠 **Modo Inteligente**: Evita descargar canciones duplicadas (especial para Playlists).
- 📋 **Soporte de Playlists**: Descarga listas completas con un solo clic.
- 🔄 **Anti-Bloqueo 429**: Implementa cliente Android de YouTube para evitar restricciones.
- ⚡ **Reintentos Automáticos**: 10 reintentos configurables con timeouts optimizados.

### 📱 Instalación Rápida

1. **Abre Termux**

2. **Clona este repositorio:**
```bash
git clone https://github.com/Em1lio573/yt-music-termux.git
cd yt-music-termux
```

3. **Ejecuta el instalador:**
```bash
bash setup.sh
```

4. **¡Listo!** Ahora ve a YouTube, selecciona una canción, dale a Compartir y elige Termux.

### 🛠️ Requisitos

El script de instalación (`setup.sh`) se encarga de todo automáticamente, pero para referencia, utiliza:

- **Python 3**
- **FFmpeg** (para conversión de audio)
- **yt-dlp** (descargador de YouTube mejorado)
- **mutagen** (gestión de metadatos)
- **Termux:API** (recomendado para mejor integración)

### 🔧 Configuración Técnica

El script incluye optimizaciones especiales para Termux:

- **Fix para Error 429**: Alterna entre cliente Android y web de YouTube
- **Gestión de Fragmentos**: Descarga de 1 fragmento concurrente (evita sobrecargas)
- **Límite de Velocidad**: 500 KB/s (previene bloqueos)
- **Reintentos Inteligentes**: Hasta 10 reintentos con timeout de 30 segundos
- **Chunks Optimizados**: 10 MB por chunk (ideal para conexiones lentas)

### 📝 Ubicaciones Importantes

| Elemento | Ruta |
|----------|------|
| 🎵 Descargas | `/storage/emulated/0/Music/` (alias: `~/storage/music/`) |
| 📋 Historial | `~/.historial_descargas_youtube.txt` |
| 🔧 Script Principal | `~/bin/termux-url-opener` |

### 🚀 Uso

#### **Método 1: Desde YouTube (Recomendado)**
1. Abre YouTube
2. Busca una canción o playlist
3. Toca el botón **Compartir**
4. Selecciona **Termux** de la lista

#### **Método 2: Desde Terminal Manualmente**
```bash
# Canción individual
python ~/bin/termux-url-opener "https://www.youtube.com/watch?v=VIDEO_ID"

# Playlist
python ~/bin/termux-url-opener "https://www.youtube.com/playlist?list=PLxxxxxx"

# Modo interactivo
python ~/bin/termux-url-opener
# Se te pedirá que pegues el link
```

### 🐛 Troubleshooting

#### ❌ "Error: python: No such file or directory"
```bash
pkg install python -y
pip install yt-dlp mutagen --upgrade
```

#### ❌ "Error 429 Too Many Requests"
✅ El script ya incluye el fix automático. Si persiste:
```bash
# Actualiza yt-dlp a la versión más reciente
pip install yt-dlp --upgrade
```

#### ❌ "FFmpeg not found"
```bash
pkg install ffmpeg -y
```

#### ❌ "Permission denied when creating ~/bin/termux-url-opener"
```bash
mkdir -p ~/bin
chmod 755 ~/bin
bash setup.sh
```

#### ❌ "Las descargas son lentas o se cuelgan"
El script ya incluye optimizaciones. Intenta:
```bash
# Reducir límite de velocidad (aún más lento pero más estable)
# Edita la línea en yt_downloader.py:
# 'ratelimit': 250000,  # 250KB/s en lugar de 500KB/s
```

#### ✅ "¿Cómo verifico que se descargó correctamente?"
```bash
ls -lah ~/storage/music/
# Deberías ver los archivos MP3 con la carátula embedida
```

### 📊 Características Detalladas

| Característica | Valor |
|---|---|
| Codec de Audio | MP3 |
| Bitrate | 320 kbps |
| Canales | Estéreo (2) |
| Formato de Contenedor | MP3 |
| Metadatos | ID3v2 (Título, Artista, Carátula) |
| Carátula Embedida | ✅ Sí |
| Anti-Duplicados | ✅ Sí (historial) |
| Soporte de Playlists | ✅ Sí |
| Reintentos Automáticos | ✅ Sí (10 intentos) |

### 📄 Licencia

Este proyecto es de código abierto. Úsalo libremente y comparte mejoras.

### 💡 Consejos

- **Para playlists grandes**: Ejecuta el script en la noche. Las playlists pueden tardar horas.
- **Conexión lenta**: El script automáticamente reduce velocidad y usa chunks pequeños.
- **Ahorrar espacio**: Borra el archivo `.historial_descargas_youtube.txt` si quieres re-descargar canciones.
- **Mejor calidad**: El script ya descarga la mejor calidad disponible (bestaudio).

### 🤝 Contribuciones

¿Encontraste un bug? ¿Tienes una mejora?
Abre un [issue](https://github.com/Em1lio573/yt-music-termux/issues) o [pull request](https://github.com/Em1lio573/yt-music-termux/pulls).

---

**Última actualización**: 2026-07-09 | **Versión**: 2.0 (Optimizada)
