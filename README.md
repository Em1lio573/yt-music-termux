# 🎵 Termux YouTube Music Downloader (Pro)

Un descargador avanzado para Termux enfocado en **música de estudio en alta fidelidad real**, integración nativa con el menú "Compartir" de Android, base de datos de duración oficial y organización inteligente de biblioteca.

---

## ✨ Características Principales

### 🎯 Prioridad YouTube Music ("YT Music First")
- **Adiós a los videoclips con ruidos o diálogos**: Cuando compartes un video musical desde YouTube, el script extrae los metadatos y localiza automáticamente la **pista oficial de estudio en YouTube Music** (*Topic Tracks / Provided to YouTube*).
- **Audio de estudio puro**: Descarga la versión original del máster musical sin intros habladas, sketches de video ni créditos de película.

### ⏱️ Base de Datos de Duración Oficial (iTunes / Apple Music)
- Consulta en milisegundos la base de datos oficial para validar la **duración exacta de la canción en estudio** (en segundos) y obtener la carátula oficial en alta resolución (1200x1200px).
- Compara la duración del video de YouTube con la oficial; si hay una diferencia notable por contenido extra, prioriza la pista de YouTube Music.

### 🛡️ Respaldo con SponsorBlock (`music_offtopic`)
- Si compartes un concierto en vivo, cover o tema underground que no existe en YouTube Music, el descargador utiliza **SponsorBlock** para recortar con precisión milimétrica las partes no musicales (intros, silencios y charlas).

### 🎧 Calidad de Audio Real (Sin Bitrates Falsos)
YouTube almacena su audio en **Opus (~160kbps)** y **AAC (~128kbps)**. A diferencia de otros scripts que fingen "320kbps" re-comprimiendo destructivamente el audio y triplicando el tamaño del archivo, este script ofrece:
- **Original Nativo (Predeterminado)**: Extracción directa sin recodificación. Preserva el 100% de la calidad original del master de YouTube en Opus/M4A con 0 pérdida generacional y descarga ultra rápida.
- **M4A / AAC Universal (~256 kbps)**: Estándar para iOS, Android y autorradios.
- **MP3 320 kbps (CBR)**: Transcodificación de alta compatibilidad para reproductores antiguos.
- **MP3 VBR V0 (~245 kbps)**: Máxima calidad perceptiva en formato MP3 con peso optimizado.
- **FLAC Lossless**: Contenedor sin compresión destructiva.

### 📂 Organización Inteligente de Biblioteca
Elimina de raíz las molestas carpetas de *"Álbum Desconocido"*:
- **Singles / Canciones sueltas**: `~/storage/music/Biblioteca/<Artista>/Singles/<Canción>.<ext>`
- **Álbumes oficiales**: `~/storage/music/Biblioteca/<Artista>/<Álbum>/<01 - Canción>.<ext>`
- **Playlists completas**: `~/storage/music/Biblioteca/Playlists/<Nombre Playlist>/<01 - Artista - Canción>.<ext>`
- **Limpieza de títulos**: Elimina automáticamente etiquetas como *(Official Video)*, *[Audio Oficial]*, *(Lyric Video)*, *[4K]*, *(Remastered)*, etc.

### ⚡ Cuenta Regresiva al Compartir
- Al compartir desde YouTube a Termux, una cuenta regresiva de **4 segundos** inicia la descarga automáticamente en la calidad predeterminada.
- Si tocas cualquier tecla durante esos 4 segundos, se abre el selector rápido para elegir otro formato o ajustar configuraciones.

### 💻 Menú Interactivo de Gestión
Al ejecutar `python3 yt_downloader.py` sin argumentos:
1. **Descargar por enlace** (Canciones o Playlists completas).
2. **Búsqueda directa en YouTube Music**: Busca canciones y descárgalas sin abrir YouTube.
3. **Gestor de Historial**: Inspecciona canciones descargadas o elimina una entrada para permitir su re-descarga.
4. **Configuración (`config.json`)**: Modifica calidades por defecto, tiempos de espera, carpetas y filtros.
5. **Gestor de Cookies**: Importa y gestiona cookies para evitar errores 429 de YouTube.
6. **Actualización Automática**: Actualiza `yt-dlp` a la última versión disponible.

### 📱 Integración con Android
- **Indexación automática**: Llama a `termux-media-scan` para que los reproductores (Poweramp, Musicolet, Retro Music, VLC, etc.) detecten las canciones al instante.
- **Notificaciones**: Muestra una notificación del sistema con `termux-notification` al concluir.

---

## 🚀 Instalación Rápida

1. **Abre Termux** y clona el repositorio:
   ```bash
   git clone https://github.com/Em1lio573/yt-music-termux.git
   cd yt-music-termux
   ```

2. **Ejecuta el configurador**:
   ```bash
   bash setup.sh
   ```

3. **¡Listo!** Abre YouTube o YouTube Music, busca cualquier canción, toca **Compartir**, selecciona **Termux** y la descarga comenzará automáticamente.

> [!TIP]
> Gracias al nuevo lanzador dinámico, cualquier actualización futura que recibas con `git pull` dentro de la carpeta del proyecto se aplicará automáticamente al menú Compartir de YouTube sin tener que reinstalar.

---

## 🛠️ Requisitos
El script de instalación (`setup.sh`) configura todo automáticamente, pero utiliza:
- `python` (Python 3.10+)
- `ffmpeg` (para extracción y metadatos)
- `termux-api` (para indexación y notificaciones)
- `yt-dlp` (motor de descarga)
- `mutagen` (gestor de metadatos multiformato ID3, MP4, Vorbis)

---

## 📊 Comparativa de Calidad Real

| Formato | Bitrate Real | Pérdida de Transcodificación | Velocidad | Tamaño | Compatibilidad |
|---|---|---|---|---|---|
| **Original Nativo** | ~160k Opus / ~128k AAC | **0% (Direct Copy)** | ⚡ Instantánea | 🪶 ~3-4 MB | Android 5.0+, VLC, Poweramp |
| **M4A (AAC)** | ~256 kbps | Mínima | ⚡ Rápida | 🪶 ~5-6 MB | Universal (iOS, Android, PC) |
| **MP3 320k** | 320 kbps (CBR) | Recompresión a MP3 | 🕒 Normal | 📦 ~9-11 MB | Reproductores de coche antiguos |
| **MP3 V0** | ~245 kbps (VBR) | Recompresión a MP3 | 🕒 Normal | 📦 ~7-8 MB | Reproductores MP3 estándar |
| **FLAC** | Lossless | Sin compresión con pérdida | 🕒 Normal | 📦 ~25-35 MB | Audiófilos / Hi-Res |

---

## 📁 Estructura de Archivos y Rutas

| Elemento | Ruta |
|---|---|
| 🎵 Biblioteca de Música | `~/storage/music/Biblioteca/` |
| ⚙️ Archivo de Configuración | `~/.config/yt-music-termux/config.json` |
| 📋 Historial de Descargas | `~/.historial_descargas_youtube.txt` |
| 🍪 Cookies de YouTube | `~/.cookies.txt` |
| 🚀 Lanzador de Compartir | `~/bin/termux-url-opener` |

---

## 📄 Licencia
Proyecto libre y de código abierto bajo licencia MIT. Úsalo, mejóralo y compártelo.
