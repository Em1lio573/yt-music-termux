#!/bin/bash

echo -e "\033[96m=== Iniciando Configuración del Descargador ===\033[0m"

# 1. Actualizar e instalar paquetes necesarios
echo -e "\033[93mActualizando e instalando dependencias (Python, FFmpeg)...\033[0m"
pkg update -y && pkg upgrade -y
pkg install python ffmpeg termux-api -y

# 2. Instalar librerías Python necesarias
echo -e "\033[93mInstalando librerías Python (yt-dlp, mutagen)...\033[0m"
pip install yt-dlp mutagen --upgrade

# 3. Configurar el script para el menú Compartir
echo -e "\033[93mConfigurando enlace con YouTube...\033[0m"
mkdir -p ~/bin

# Movemos el archivo python creado a la carpeta bin con el nombre exacto 'termux-url-opener'
cp yt_downloader.py ~/bin/termux-url-opener

# Hacemos el archivo ejecutable
chmod +x ~/bin/termux-url-opener

echo -e "\033[92m"
echo "========================================"
echo "      ¡INSTALACIÓN COMPLETADA!          "
echo "========================================"
echo -e "\033[0m"
echo "CÓMO USARLO:"
echo "1. Abre la app de YouTube."
echo "2. Busca una canción o playlist."
echo "3. Toca 'Compartir'."
echo "4. Selecciona 'Termux' en la lista de apps."
echo "5. La descarga comenzará automáticamente."
echo ""
echo "📂 Las canciones se guardarán en: ~/storage/music/"
echo "📋 Historial de descargas: ~/.historial_descargas_youtube.txt"
echo ""
