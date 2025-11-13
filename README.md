## 🎵 Termux YouTube Music Downloader
Un script automatizado para Termux que descarga música de YouTube en Alta Calidad (320kbps), con carátulas, metadatos y conversión a MP3 estéreo. Se integra nativamente con el menú "Compartir" de Android.
✨ Características
 * 🚀 Integración con Android: Funciona desde el botón "Compartir" de la app de YouTube.
 * 🎧 Alta Fidelidad: Descargas forzadas a MP3 320kbps y Estéreo.
 * 🖼️ Metadatos Completos: Agrega automáticamente Artista, Título y Carátula (Cover Art) al archivo.
 * 📂 Organización: Guarda los archivos directamente en la carpeta Music del dispositivo.
 * 🧠 Modo Inteligente: Evita descargar canciones duplicadas (especial para Playlists).
 * 📋 Soporte de Playlists: Descarga listas completas con un solo clic.
📱 Instalación Rápida
 * Abre Termux.
 * Clona este repositorio:
   git clone [](https://github.com/Em1lio573/yt-music-termux)
cd TU_REPO

 * Ejecuta el instalador:
   chmod +x setup.sh
   ./setup.sh

 * ¡Listo! Ahora ve a YouTube, selecciona una canción, dale a Compartir y elige Termux.
🛠️ Requisitos
El script de instalación (setup.sh) se encarga de todo, pero para referencia, utiliza:
 * Python 3
 * FFmpeg
 * yt-dlp
 * Mutagen
 * Termux:API (App recomendada para mejor integración)
📝 Notas
Las descargas se guardan en /storage/emulated/0/Music/.
El historial de descargas (para evitar duplicados) se guarda en ~/.historial_descargas_youtube.txt.
