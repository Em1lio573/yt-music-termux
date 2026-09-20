#!/bin/bash
export PATH="/home/emi/Android/Sdk/platform-tools:$PATH"
DIR_ACTUAL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="$DIR_ACTUAL/YT-Music-Pro.apk"

echo -e "\033[96m=== Instalador Automático vía USB Debug (ADB) ===\033[0m"
echo "Esperando a que conectes tu teléfono con Depuración por USB activada..."

# Esperar a que ADB detecte el dispositivo
adb wait-for-device

echo -e "\033[92m✔ ¡Dispositivo detectado!\033[0m"
adb devices -l

echo -e "\033[93mInstalando YT-Music-Pro.apk...\033[0m"
if adb install -r "$APK"; then
    echo -e "\033[92m==========================================================\033[0m"
    echo -e "\033[92m       ¡APP INSTALADA CON ÉXITO EN TU TELÉFONO!           \033[0m"
    echo -e "\033[92m==========================================================\033[0m"
    echo "Iniciando la app en tu teléfono..."
    adb shell monkey -p com.emilio.ytmusic -c android.intent.category.LAUNCHER 1 2>/dev/null || true
else
    echo -e "\033[93mCopiando archivo a /sdcard/Download/...\033[0m"
    adb push "$APK" /sdcard/Download/YT-Music-Pro.apk
    echo "El archivo APK se ha copiado a tu carpeta de Descargas del teléfono."
fi
