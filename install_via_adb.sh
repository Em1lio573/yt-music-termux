#!/bin/bash
export PATH="/home/emi/Android/Sdk/platform-tools:$PATH"
DIR_ACTUAL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="$DIR_ACTUAL/YT-Music-Pro.apk"

echo -e "\033[96m==========================================================\033[0m"
echo -e "\033[96m   Instalador USB Directo ADB - YouTube Music Pro         \033[0m"
echo -e "\033[96m==========================================================\033[0m"
echo "Buscando dispositivo Android conectado por cable USB..."

while true; do
    DEV_STATE=$(adb devices | grep -v "List" | grep "device$" | awk '{print $1}' | head -n 1)
    UNAUTH=$(adb devices | grep -v "List" | grep "unauthorized$" | awk '{print $1}' | head -n 1)

    if [ -n "$UNAUTH" ]; then
        echo -e "\033[93m⚠️ Dispositivo detectado ($UNAUTH) pero NO AUTORIZADO.\033[0m"
        echo "Mira la pantalla de tu teléfono y pulsa 'Permitir siempre la depuración USB'."
        sleep 2
        continue
    fi

    if [ -n "$DEV_STATE" ]; then
        echo -e "\033[92m✔ ¡Dispositivo Android conectado y autorizado: $DEV_STATE!\033[0m"
        MODEL=$(adb -s "$DEV_STATE" shell getprop ro.product.model 2>/dev/null || echo "Android")
        echo "Modelo detectado: $MODEL"
        echo -e "\033[93mInstalando YT-Music-Pro.apk directamente...\033[0m"
        
        # Intentar instalación directa
        INSTALL_OUT=$(adb -s "$DEV_STATE" install -r "$APK" 2>&1)
        echo "$INSTALL_OUT"

        if echo "$INSTALL_OUT" | grep -qi "Success"; then
            echo -e "\033[92m==========================================================\033[0m"
            echo -e "\033[92m       ¡APP INSTALADA CON ÉXITO EN TU TELÉFONO!           \033[0m"
            echo -e "\033[92m==========================================================\033[0m"
            echo "Iniciando YouTube Music Pro en tu pantalla..."
            adb -s "$DEV_STATE" shell monkey -p com.emilio.ytmusic -c android.intent.category.LAUNCHER 1 2>/dev/null || true
            exit 0
        else
            echo -e "\033[93mInstalación directa restringida por el sistema del teléfono.\033[0m"
            echo "Copiando archivo directamente a Descargas de tu teléfono..."
            adb -s "$DEV_STATE" push "$APK" /sdcard/Download/YT-Music-Pro.apk
            echo -e "\033[92m✔ Archivo copiado a: /sdcard/Download/YT-Music-Pro.apk\033[0m"
            echo "Abriendo instalador en la pantalla de tu móvil..."
            adb -s "$DEV_STATE" shell am start -a android.intent.action.VIEW -d "file:///sdcard/Download/YT-Music-Pro.apk" -t "application/vnd.android.package-archive" 2>/dev/null || true
            exit 0
        fi
    fi

    sleep 1
done
