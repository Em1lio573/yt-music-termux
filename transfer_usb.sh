#!/bin/bash
# Script para transferir e instalar el APK vía USB en tu teléfono Android

DIR_ACTUAL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_FILE="$DIR_ACTUAL/YT-Music-Pro.apk"
ADB="/home/emi/Android/Sdk/platform-tools/adb"

if [ ! -f "$APK_FILE" ]; then
    echo -e "\033[91mNo se encontró $APK_FILE. Compilando primero...\033[0m"
    bash "$DIR_ACTUAL/build_apk.sh"
fi

echo -e "\033[95m\033[1m=== Transferencia / Instalación de APK vía USB ===\033[0m"
echo "Buscando teléfono conectado por USB..."

# 1. Intentar vía ADB (Android Debug Bridge)
DEVICE_ID=$($ADB devices | grep -v "List of devices" | grep -w "device" | awk '{print $1}' | head -n 1)
UNAUTHORIZED=$($ADB devices | grep "unauthorized")

if [ -n "$DEVICE_ID" ]; then
    echo -e "\033[92m✔ Dispositivo Android detectado vía ADB: $DEVICE_ID\033[0m"
    
    echo -e "\033[93m1. Copiando archivo APK a la carpeta Descargas del teléfono (/sdcard/Download/)...\033[0m"
    $ADB push "$APK_FILE" /sdcard/Download/YT-Music-Pro.apk
    
    echo -e "\033[93m2. Instalando APK directamente en el teléfono...\033[0m"
    $ADB install -r "$APK_FILE" && echo -e "\033[92m✔ ¡App instalada con éxito en tu teléfono!\033[0m" || echo -e "\033[93mNota: Si la instalación falló por permisos de Android, puedes abrir el archivo copiado en tu carpeta de Descargas del móvil.\033[0m"
    
    echo ""
    echo -e "\033[92m==========================================================\033[0m"
    echo -e "\033[92m       ¡TRANSFERENCIA POR USB COMPLETADA!                 \033[0m"
    echo -e "\033[92m==========================================================\033[0m"
    echo "El archivo APK está en tu teléfono en: /sdcard/Download/YT-Music-Pro.apk"
    exit 0
elif [ -n "$UNAUTHORIZED" ]; then
    echo -e "\033[93m⚠ Dispositivo detectado pero requiere autorización.\033[0m"
    echo "Desbloquea tu teléfono y toca 'Permitir depuración por USB' (Aceptar)."
    exit 1
fi

# 2. Intentar vía MTP (Almacenamiento USB / GVFS)
MTP_DIR=$(ls -d /run/user/$(id -u)/gvfs/mtp* 2>/dev/null | head -n 1)
if [ -n "$MTP_DIR" ]; then
    echo -e "\033[92m✔ Teléfono detectado en modo transferencia de archivos (MTP): $MTP_DIR\033[0m"
    DOWNLOAD_DIR=$(find "$MTP_DIR" -maxdepth 3 -type d -iname "Download" 2>/dev/null | head -n 1)
    if [ -z "$DOWNLOAD_DIR" ]; then
        DOWNLOAD_DIR="$MTP_DIR"
    fi
    echo "Copiando APK a: $DOWNLOAD_DIR"
    cp "$APK_FILE" "$DOWNLOAD_DIR/YT-Music-Pro.apk"
    echo -e "\033[92m✔ Archivo copiado exitosamente al teléfono en la carpeta Descargas.\033[0m"
    exit 0
fi

# Si no se detectó por ningún medio:
echo -e "\033[91m❌ No se detectó ningún teléfono conectado por USB.\033[0m"
echo ""
echo -e "\033[96mPor favor, realiza lo siguiente en tu teléfono:\033[0m"
echo "1. Conecta el cable USB de tu teléfono a la computadora."
echo "2. En la notificación que aparece en tu teléfono ('Cargando por USB'):"
echo "   👉 Selecciona 'Transferencia de archivos (MTP)'"
echo "   👉 O activa 'Depuración por USB' en Opciones de desarrollador."
echo "3. Vuelve a ejecutar este script:"
echo "   bash transfer_usb.sh"
echo ""
exit 1
