# Manual Definitivo de Reconstrucción y Mantenimiento
# YouTube Music Pro (Android Nativo & Termux Suite)

Este documento contiene la guía técnica paso a paso para reconstruir, compilar, depurar y desplegar la aplicación completa desde cero en cualquier momento.

---

## 1. Arquitectura General del Proyecto

El proyecto consta de dos modalidades operativas complementarias:

1. **Aplicación Nativa de Android (`YT-Music-Pro.apk`)**:
   - **Frontend**: SPA construida con React 18, Vite y Tailwind CSS, ubicada en `mobile_app/frontend`.
   - **Contenedor Móvil**: Capacitor 6 sincronizado con la plataforma Android nativa en `mobile_app/frontend/android`.
   - **Plugin Nativo Java**: [NativeDownloaderPlugin.java](file:///home/emi/Documentos/yt-music-termux/mobile_app/frontend/android/app/src/main/java/com/emilio/ytmusic/NativeDownloaderPlugin.java), que gestiona el motor de descargas offline, escaneo de almacenamiento interno (`/storage/emulated/0/Music/Biblioteca`), similitud estricta de carátulas (Apple Music / Deezer), letras sincronizadas (.lrc) y comunicación con el MediaStore del sistema operativo.
   - **Motor DSP de Audio**: [audioDsp.js](file:///home/emi/Documentos/yt-music-termux/mobile_app/frontend/src/audioDsp.js), implementado con Web Audio API (Ecualizador de 5 bandas, Bass Booster y 8 perfiles de sonido).

2. **Suite CLI y Servidor en Python (Termux / PC)**:
   - [yt_downloader.py](file:///home/emi/Documentos/yt-music-termux/yt_downloader.py): Script autónomo para descargas de alta calidad vía CLI o menú "Compartir" de Android en Termux.
   - [mobile_app/server.py](file:///home/emi/Documentos/yt-music-termux/mobile_app/server.py): Servidor HTTP local con soporte para modo web sin APK.

---

## 2. Requisitos y Entorno de Desarrollo

Para compilar la aplicación en un entorno Linux, se requieren los siguientes componentes:

| Herramienta | Versión Recomendada | Ruta Habitual / Variable de Entorno |
|---|---|---|
| **Java Development Kit** | OpenJDK 17 o JetBrains JDK 17 | `export JAVA_HOME="/home/emi/.jdk/jdk-17.0.12+7"` |
| **Android SDK** | Plataformas 34 / 35, Build-Tools 34.0.0+ | `export ANDROID_HOME="/home/emi/Android/Sdk"` |
| **Node.js y npm** | Node v18.x o v20.x, npm v9+ | Sistema estándar (`node -v`, `npm -v`) |
| **Android Platform Tools** | ADB actualizado | `/home/emi/Android/Sdk/platform-tools/adb` |
| **Python** | Python 3.10+ (yt-dlp, mutagen) | Sistema estándar (`python3`) |

> [!IMPORTANT]
> **Compatibilidad de Java**: Gradle 8 y los plugins generados por Capacitor requieren compilar con **Java 17**. Si el sistema intenta compilar con Java 21 sin parchear, fallará. El script de compilación incluye una sustitución automática para forzar `VERSION_17`.

---

## 3. Estructura de Archivos Clave

```text
yt-music-termux/
├── build_apk.sh                         # Script maestro de compilación automatizada
├── install_via_adb.sh                   # Script para instalación directa vía USB con ADB
├── YT-Music-Pro.apk                     # Archivo APK final generado listo para instalar
├── yt_downloader.py                     # Motor de descargas en Python para terminal / Termux
├── setup.sh                             # Configuración inicial para entornos Termux
├── mobile_app/
│   ├── frontend/
│   │   ├── package.json                 # Dependencias React, Lucide, Capacitor y Tailwind
│   │   ├── vite.config.js               # Configuración de empaquetado Vite
│   │   ├── capacitor.config.json        # Configuración de app (appId: com.emilio.ytmusic)
│   │   ├── src/
│   │   │   ├── App.jsx                  # Vista raíz, biblioteca, filtros, mini-reproductor
│   │   │   ├── audioDsp.js              # Motor Web Audio API (5 bandas EQ, Bass Boost)
│   │   │   ├── apiClient.js             # Conector híbrido (Plugin Nativo Capacitor o Fallback)
│   │   │   ├── index.css                # Estilos Glassmorphism optimizados para GPU
│   │   │   └── components/
│   │   │       ├── NowPlayingSheet.jsx  # Reproductor expandido con gestos y letras
│   │   │       ├── MiniProgressBar.jsx # Barra mini con requestAnimationFrame (sin re-renders)
│   │   │       ├── EqualizerModal.jsx   # Interfaz gráfica de 5 bandas y presets
│   │   │       ├── CoverPickerModal.jsx # Selector visual de carátulas (Apple / Deezer)
│   │   │       ├── PlaylistModal.jsx    # Administrador de listas de reproducción
│   │   │       └── FolderPickerModal.jsx # Selector de ubicación de música y filtros de almacenamiento
│   │   └── android/                     # Proyecto nativo Gradle generado por Capacitor
│   │       ├── gradlew                  # Wrapper de Gradle para compilar
│   │       └── app/src/main/
│   │           ├── AndroidManifest.xml  # Permisos nativos de almacenamiento y audio
│   │           └── java/com/emilio/ytmusic/
│   │               ├── MainActivity.java
│   │               └── NativeDownloaderPlugin.java # Plugin nativo principal
```

---

## 4. Proceso de Compilación Paso a Paso

### Método Automático (Recomendado)

Desde la raíz del repositorio (`/home/emi/Documentos/yt-music-termux`), simplemente ejecuta:

```bash
./build_apk.sh
```

El script se encarga de:
1. Detectar y exportar `JAVA_HOME` (JDK 17) y `ANDROID_HOME`.
2. Instalar dependencias de Node.js si falta `node_modules`.
3. Compilar el frontend web optimizado con `npm run build`.
4. Sincronizar el build web con el contenedor nativo mediante `npx cap sync android`.
5. Parchear las directivas de Java a `VERSION_17` en los archivos `build.gradle`.
6. Configurar `sdk.dir` en `android/local.properties`.
7. Compilar el APK con `./gradlew assembleDebug`.
8. Copiar el binario resultante a la raíz como `YT-Music-Pro.apk`.

---

### Método Manual (Paso a Paso)

Si necesitas ejecutar cada etapa manualmente para depuración:

#### Paso 1: Configurar Variables de Entorno
```bash
export JAVA_HOME="/home/emi/.jdk/jdk-17.0.12+7"
export ANDROID_HOME="/home/emi/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

#### Paso 2: Compilar el Frontend Web
```bash
cd /home/emi/Documentos/yt-music-termux/mobile_app/frontend
npm install
npm run build
```
Los archivos optimizados se generarán en `mobile_app/frontend/dist`.

#### Paso 3: Sincronizar con Capacitor Android
```bash
npx cap sync android
```

#### Paso 4: Asegurar Compatibilidad con Java 17
```bash
sed -i 's/VERSION_21/VERSION_17/g' android/app/build.gradle 2>/dev/null || true
sed -i 's/VERSION_21/VERSION_17/g' android/app/capacitor.build.gradle 2>/dev/null || true
sed -i 's/VERSION_21/VERSION_17/g' android/capacitor-cordova-android-plugins/build.gradle 2>/dev/null || true
sed -i 's/VERSION_21/VERSION_17/g' node_modules/@capacitor/android/capacitor/build.gradle 2>/dev/null || true
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
```

#### Paso 5: Compilar el APK con Gradle
```bash
cd android
chmod +x gradlew
./gradlew assembleDebug -Dorg.gradle.java.home="$JAVA_HOME"
```

#### Paso 6: Ubicar el APK Resultante
El archivo generado se localiza en:
`mobile_app/frontend/android/app/build/outputs/apk/debug/app-debug.apk`

Copiar a la raíz:
```bash
cp app/build/outputs/apk/debug/app-debug.apk /home/emi/Documentos/yt-music-termux/YT-Music-Pro.apk
```

---

## 5. Instalación y Despliegue en el Teléfono

### Opción A: Instalación Directa por Cable USB (ADB)

1. Conectar el teléfono mediante cable USB y verificar autorización:
   ```bash
   /home/emi/Android/Sdk/platform-tools/adb devices
   ```
2. Instalar el APK:
   ```bash
   /home/emi/Android/Sdk/platform-tools/adb install -r /home/emi/Documentos/yt-music-termux/YT-Music-Pro.apk
   ```
3. Iniciar la aplicación en el dispositivo:
   ```bash
   /home/emi/Android/Sdk/platform-tools/adb shell am start -n com.emilio.ytmusic/.MainActivity
   ```

*(Alternativamente, puedes ejecutar directamente `./install_via_adb.sh`).*

### Opción B: Descarga Inalámbrica vía Servidor HTTP Local

1. En la PC, levantar un servidor local en el puerto 8080:
   ```bash
   cd /home/emi/Documentos/yt-music-termux
   python3 -m http.server 8080
   ```
2. Obtener la IP local de tu PC en la red Wi-Fi:
   ```bash
   hostname -I | awk '{print $1}'
   ```
3. En el navegador del teléfono (conectado a la misma red Wi-Fi), abrir:
   `http://<IP_DE_LA_PC>:8080/YT-Music-Pro.apk`
4. Descargar e instalar directamente desde el administrador de archivos del móvil.

---

## 6. Lógica Crítica del Sistema y Reglas de Mantenimiento

### 1. Sistema de Carátulas Ocultas y Protección de Galería (.covers/ con .nomedia)
- **Problema previo**: Al actualizar carátulas o descargar música, se guardaban archivos `.jpg` sueltos en las carpetas públicas de música (`cover.jpg` o `<cancion>.jpg`) y se invocaba `MediaScannerConnection.scanFile`, lo que provocaba que la Galería de fotos de Android (Google Photos, Galería MIUI/HyperOS, Samsung Gallery) indexara cientos de álbumes con fotos de carátulas sueltas.
- **Solución definitiva implementada**:
  - **Almacén centralizado y oculto**: Todas las carátulas se almacenan exclusivamente en `/storage/emulated/0/Music/Biblioteca/.covers/`.
  - **Fichero `.nomedia`**: Se crea automáticamente un archivo `.nomedia` en `.covers/`, lo que ordena a Android ignorar por completo las imágenes para la Galería del teléfono.
  - **Hash determinista**: Cada canción y álbum tiene su carátula asociada mediante MD5 de su ruta física (`<md5>.jpg` y `album_<md5>.jpg`).
  - **Incrustación en el archivo**: Al descargar o actualizar una carátula, se incrusta directamente en los metadatos de audio (Vorbis en `.opus`, ID3 APIC en `.mp3`, covr en `.m4a`, Picture en `.flac`) mediante `mutagen`.
  - **Purga de MediaStore**: Cualquier imagen suelta residual es eliminada del disco y purgada de `MediaStore.Images.Media` mediante `purgeImageFromGallery`.
  - **Escaneo selectivo**: `MediaScannerConnection.scanFile` solo se invoca para archivos de audio y letras `.lrc`, jamás para imágenes.
  - Para cambiar una carátula errónea: Menú contextual (3 puntos) -> "Cambiar carátula".

### 2. Rendimiento y Cero Lag en Android WebView
- **Desacoplamiento de timers de audio**: El evento `onTimeUpdate` de la etiqueta `<audio>` **no** debe actualizar el estado global de React en `App.jsx`. El progreso del mini-reproductor se maneja en [MiniProgressBar.jsx](file:///home/emi/Documentos/yt-music-termux/mobile_app/frontend/src/components/MiniProgressBar.jsx) directamente mediante el DOM (`requestAnimationFrame`).
- **Animaciones en GPU**: Las barras del ecualizador utilizan `transform: scaleY(...)` con `transform-origin: bottom` para evitar reflows de CPU.

### 3. Restricción Estricta de Estilo
- **Cero emojis**: La aplicación mantiene una línea visual seria y sobria tipo Apple Music. Todos los indicadores visuales deben usar iconos vectoriales de la librería `lucide-react` (ej. `Heart`, `ListMusic`, `Sliders`, `Download`, `Search`).

### 4. Selector de Ubicación de Música y Exclusión Estricta de Audios de WhatsApp
- **Problema previo**: En Android 10-14, el proveedor de contenidos `MediaStore` etiqueta de manera predeterminada los mensajes de voz de WhatsApp (`.opus`), audios de Telegram y notas de grabadora como "música", mostrándolos en la biblioteca del usuario.
- **Solución definitiva implementada**:
  - **Selector visual nativo ([FolderPickerModal.jsx](file:///home/emi/Documentos/yt-music-termux/mobile_app/frontend/src/components/FolderPickerModal.jsx))**: Permite al usuario designar libremente su carpeta principal de música (donde se escanean las canciones y se guardan las nuevas descargas y carátulas), así como agregar carpetas secundarias de almacenamiento interno o tarjeta SD.
  - **Filtro de exclusión heurística en Java (`shouldIgnoreAudio`)**: Bloquea rutas que contengan `whatsapp`, `com.whatsapp`, `telegram`, `voice notes`, `opus/`, `aud-`, `ptt-`, `sound_recorder`, `recordings`, `call_rec`, `notifications`, `ringtones` o `alarms`.
  - **Filtro de clips breves**: Descarta automáticamente archivos de audio menores a 30 segundos o menores a 100 KB que no formen parte de la carpeta principal de música.
  - **Modo de escaneo `selected` (Predeterminado)**: Escanea a nivel de sistema de archivos únicamente las carpetas elegidas por el usuario, eliminando de raíz cualquier infiltración de notas de voz con tiempos de carga inferiores a 50 milisegundos.

---

## 7. Solución de Problemas Frecuentes (Troubleshooting)

| Síntoma | Causa Probable | Solución |
|---|---|---|
| `Unsupported class file major version 65` | Gradle ejecutado con Java 21 en lugar de Java 17 | Exportar `export JAVA_HOME="/home/emi/.jdk/jdk-17.0.12+7"` y volver a compilar. |
| `adb: device unauthorized` | El teléfono no ha aceptado la clave RSA de depuración USB | Mirar la pantalla del teléfono, marcar "Permitir siempre" y pulsar Aceptar. |
| `INSTALL_FAILED_USER_RESTRICTED` | Sistema Xiaomi/HyperOS bloquea instalación USB | En "Opciones de Desarrollador", habilitar "Instalar vía USB" (o usar el método HTTP local). |
| Pantalla negra en captura ADB | El teléfono tiene la pantalla apagada o en Always-on Display | Ejecutar `/home/emi/Android/Sdk/platform-tools/adb shell cmd power wakeup 0` y desbloquear manualmente. |
| Canciones no aparecen en la biblioteca | Permiso de almacenamiento no otorgado o carpeta vacía | Abrir la app, conceder permisos de archivos cuando los solicite, o copiar las canciones a `/storage/emulated/0/Music/Biblioteca`. |
