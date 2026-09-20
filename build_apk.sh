#!/bin/bash
# Script para compilar el APK nativo de Android (YouTube Music Pro)

set -e

DIR_ACTUAL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Detectar JDK 17 si no está configurado
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/home/emi/.jdk/jdk-17.0.12+7" ]; then
        export JAVA_HOME="/home/emi/.jdk/jdk-17.0.12+7"
    elif [ -d "/home/emi/StudioProjects/KALA_Terminal/sdk/jdk" ]; then
        export JAVA_HOME="/home/emi/StudioProjects/KALA_Terminal/sdk/jdk"
    fi
fi

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/emi/Android/Sdk}}"
export PATH="$JAVA_HOME/bin:$PATH"

echo -e "\033[96m=== Compilando APK Nativo de Android (Apple Design) ===\033[0m"
echo "Java: $(javac -version)"
echo "Android SDK: $ANDROID_HOME"

cd "$DIR_ACTUAL/mobile_app/frontend"

if [ ! -d "node_modules" ]; then
    echo -e "\033[93mInstalando dependencias de Node.js...\033[0m"
    npm install
fi

echo -e "\033[93m1. Compilando interfaz web (Vite)...\033[0m"
npm run build

echo -e "\033[93m2. Sincronizando plataforma Android (Capacitor)...\033[0m"
npx cap sync android

# Asegurar compatibilidad con Java 17 en archivos generados por Capacitor
sed -i 's/VERSION_21/VERSION_17/g' android/app/capacitor.build.gradle 2>/dev/null || true
sed -i 's/VERSION_21/VERSION_17/g' android/capacitor-cordova-android-plugins/build.gradle 2>/dev/null || true
sed -i 's/VERSION_21/VERSION_17/g' node_modules/@capacitor/android/capacitor/build.gradle 2>/dev/null || true

echo -e "\033[93m3. Compilando APK con Gradle...\033[0m"
cd android
chmod +x gradlew
./gradlew assembleDebug

APK_ORIGEN="app/build/outputs/apk/debug/app-debug.apk"
APK_DESTINO="$DIR_ACTUAL/YT-Music-Pro.apk"

if [ -f "$APK_ORIGEN" ]; then
    cp "$APK_ORIGEN" "$APK_DESTINO"
    echo -e "\033[92m"
    echo "=========================================================="
    echo "       ¡APK NATIVO COMPILADO EXITOSAMENTE!                "
    echo "=========================================================="
    echo -e "\033[0m"
    echo "📱 Archivo APK listo: $APK_DESTINO"
    echo "   Tamaño: $(ls -lh "$APK_DESTINO" | awk '{print $5}')"
    echo ""
    echo "Puedes transferir 'YT-Music-Pro.apk' a tu teléfono Android"
    echo "e instalarlo directamente como cualquier app nativa."
else
    echo -e "\033[91mError: No se encontró el APK generado en $APK_ORIGEN\033[0m"
    exit 1
fi
