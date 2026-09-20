#!/bin/bash

echo -e "\033[96m=== Configuración de YouTube Music Downloader Pro para Termux ===\033[0m"

# 1. Actualizar e instalar paquetes necesarios del sistema
echo -e "\033[93mActualizando repositorios e instalando paquetes (Python, FFmpeg, Termux:API)...\033[0m"
pkg update -y && pkg upgrade -y
pkg install python ffmpeg termux-api -y

# 2. Instalar librerías Python necesarias
echo -e "\033[93mInstalando y actualizando librerías Python (yt-dlp, mutagen)...\033[0m"
pip install yt-dlp mutagen --upgrade

# 3. Configurar el script para el menú Compartir de Android
echo -e "\033[93mConfigurando integración con menú Compartir de YouTube (termux-url-opener)...\033[0m"
mkdir -p ~/bin

DIR_ACTUAL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_PYTHON="$DIR_ACTUAL/yt_downloader.py"

chmod +x "$SCRIPT_PYTHON"

# Crear lanzador dinámico para que git pull actualice automáticamente el script
cat << EOF > ~/bin/termux-url-opener
#!/bin/bash
REPO_SCRIPT="$SCRIPT_PYTHON"
if [ -f "\$REPO_SCRIPT" ]; then
    exec python3 "\$REPO_SCRIPT" "\$@"
elif [ -f "\$HOME/yt-music-termux/yt_downloader.py" ]; then
    exec python3 "\$HOME/yt-music-termux/yt_downloader.py" "\$@"
elif [ -f "\$HOME/Documentos/yt-music-termux/yt_downloader.py" ]; then
    exec python3 "\$HOME/Documentos/yt-music-termux/yt_downloader.py" "\$@"
else
    exec python3 ~/bin/yt_downloader_core.py "\$@"
fi
EOF

# También copiamos una copia de respaldo en bin por seguridad
cp "$SCRIPT_PYTHON" ~/bin/yt_downloader_core.py

# Crear lanzador para la App Móvil (Apple Design)
SCRIPT_MOBILE="$DIR_ACTUAL/mobile_app/start_app.py"
cat << EOF > ~/bin/music-app
#!/bin/bash
MOBILE_SCRIPT="$SCRIPT_MOBILE"
if [ -f "\$MOBILE_SCRIPT" ]; then
    exec python3 "\$MOBILE_SCRIPT" "\$@"
elif [ -f "\$HOME/yt-music-termux/mobile_app/start_app.py" ]; then
    exec python3 "\$HOME/yt-music-termux/mobile_app/start_app.py" "\$@"
elif [ -f "\$HOME/Documentos/yt-music-termux/mobile_app/start_app.py" ]; then
    exec python3 "\$HOME/Documentos/yt-music-termux/mobile_app/start_app.py" "\$@"
fi
EOF

# Dar permisos de ejecución
chmod +x ~/bin/termux-url-opener
chmod +x ~/bin/yt_downloader_core.py
chmod +x ~/bin/music-app

echo -e "\033[92m"
echo "=========================================================="
echo "    ¡INSTALACIÓN DE YOUTUBE MUSIC PRO COMPLETADA!         "
echo "=========================================================="
echo -e "\033[0m"
echo "🎯 CÓMO USARLO:"
echo "1. Abre la app de YouTube o YouTube Music."
echo "2. Selecciona cualquier canción, video o playlist."
echo "3. Toca 'Compartir' -> 'Termux'."
echo "4. El descargador buscará la pista oficial en YouTube Music,"
echo "   verificará la duración con la base de datos de estudio y"
echo "   descargará el audio en alta fidelidad real."
echo ""
echo "💻 MODO MANUAL Y GESTIÓN:"
echo "   Ejecuta: python3 $SCRIPT_PYTHON"
echo "   (Para buscar música, cambiar calidades, gestionar historial y cookies)"
echo ""
echo "📂 Las canciones se organizan automáticamente en:"
echo "   • Canciones sueltas -> ~/storage/music/Biblioteca/<Artista>/Singles/"
echo "   • Álbumes completos -> ~/storage/music/Biblioteca/<Artista>/<Álbum>/"
echo "   • Playlists         -> ~/storage/music/Biblioteca/Playlists/<Nombre>/"
echo ""
