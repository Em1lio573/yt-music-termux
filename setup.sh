#!/bin/bash

echo -e "\033[96m=== Iniciando Configuración del Descargador ===\033[0m"

# 1. Dar permisos de almacenamiento
echo -e "\033[93mSolicitando acceso al almacenamiento...\033[0m"
echo "Por favor, acepta la ventana emergente si aparece."
termux-setup-storage
sleep 2

# 2. Instalar paquetes necesarios
echo -e "\033[93mActualizando e instalando dependencias (Python, FFmpeg)...\033[0m"
pkg update -y && pkg upgrade -y
pkg install python ffmpeg termux-api -y

# 3. Instalar yt-dlp
echo -e "\033[93mInstalando librería de descarga (yt-dlp)...\033[0m"
pip install yt-dlp --upgrade

# 4. Configurar el script para el menú Compartir
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
