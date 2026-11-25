#!/bin/bash
# Script maestro de compilación nativa (Rust Core + Kotlin Jetpack Compose)
# Genera el APK nativo de YouTube Music Pro

set -e

DIR_ACTUAL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Configurar JAVA_HOME si no está definido o es inválido
if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    if [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
    elif [ -d "/home/emi/.jdk/jdk-17.0.12+7" ]; then
        export JAVA_HOME="/home/emi/.jdk/jdk-17.0.12+7"
    fi
fi

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/emi/Android/Sdk}}"
export PATH="$JAVA_HOME/bin:$PATH"

echo -e "\033[96m=========================================================="
echo "   Compilando YouTube Music Pro (Rust Core + Kotlin Nativo)"
echo -e "==========================================================\033[0m"
echo "Java: $(javac -version)"
echo "Android SDK: $ANDROID_HOME"
echo "Cargo: $(cargo --version)"
echo "Cargo-NDK: $(cargo ndk --version)"

# 1. Compilar núcleo Rust y generar librerías .so con cargo-ndk
echo -e "\n\033[93m1. Compilando núcleo en Rust para arquitecturas Android (arm64, x86_64)...\033[0m"
cd "$DIR_ACTUAL/core-rust"
cargo ndk -t arm64-v8a -t x86_64 -o "$DIR_ACTUAL/android/app/src/main/jniLibs" build --release

# 2. Generar bindings actualizados de UniFFI para Kotlin
echo -e "\n\033[93m2. Generando bindings de UniFFI en Kotlin...\033[0m"
cargo build --lib
./target/debug/uniffi-bindgen generate --library target/debug/libytmusic_core.so --language kotlin --out-dir "$DIR_ACTUAL/android/app/src/main/java/com/emilio/ytmusic/core/rust"

# 3. Compilar APK con Gradle (Kotlin + Jetpack Compose)
echo -e "\n\033[93m3. Compilando APK nativo de Android con Gradle...\033[0m"
cd "$DIR_ACTUAL/android"
chmod +x gradlew
./gradlew assembleDebug -Dorg.gradle.java.home="$JAVA_HOME"

APK_ORIGEN="app/build/outputs/apk/debug/app-debug.apk"
APK_DESTINO="$DIR_ACTUAL/YT-Music-Pro.apk"

if [ -f "$APK_ORIGEN" ]; then
    cp "$APK_ORIGEN" "$APK_DESTINO"
    echo -e "\n\033[92m=========================================================="
    echo "       ¡APK NATIVO RUST + KOTLIN COMPILADO CON ÉXITO!      "
    echo "=========================================================="
    echo -e "\033[0m"
    echo "📱 Archivo APK listo: $APK_DESTINO"
    echo "   Tamaño: $(ls -lh "$APK_DESTINO" | awk '{print $5}')"
    echo ""
    echo "Puedes instalarlo mediante:"
    echo "   adb install -r $APK_DESTINO"
else
    echo -e "\033[91mError: No se encontró el APK generado en $APK_ORIGEN\033[0m"
    exit 1
fi
