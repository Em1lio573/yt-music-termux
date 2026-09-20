#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Lanzador de YouTube Music Pro App Móvil (Apple Design)
Inicia el servidor local, detecta la IP de la red WiFi y abre la interfaz.
"""

import sys
import os
import socket
import subprocess
from pathlib import Path

# Añadir directorio raíz
DIR_RAIZ = Path(__file__).parent.parent
sys.path.insert(0, str(DIR_RAIZ))

from mobile_app.server import run_server

def obtener_ip_local():
    """Detecta la IP local de la red WiFi para conectar el móvil"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def abrir_en_navegador(url):
    """Abre la URL en Android Termux o Linux"""
    try:
        # En Termux
        subprocess.run(["termux-open-url", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return
    except Exception:
        pass

    try:
        # En Linux de escritorio
        subprocess.run(["xdg-open", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return
    except Exception:
        pass

def main():
    puerto = 8000
    ip_local = obtener_ip_local()
    url_local = f"http://localhost:{puerto}"
    url_red = f"http://{ip_local}:{puerto}"

    print("\033[95m\033[1m╔════════════════════════════════════════════════════════════╗\033[0m")
    print("\033[95m\033[1m║       🎵 YouTube Music Pro - Mobile App (Apple Design)     ║\033[0m")
    print("\033[95m\033[1m╚════════════════════════════════════════════════════════════╝\033[0m")
    print("")
    print(f"📱 \033[92m\033[1mEn tu teléfono móvil o navegador:\033[0m")
    print(f"   👉 Abre: \033[96m\033[1m{url_local}\033[0m (en este dispositivo)")
    if ip_local != "127.0.0.1":
        print(f"   👉 O en la misma red WiFi: \033[93m\033[1m{url_red}\033[0m")
    print("")
    print("✨ \033[94mConsejo PWA en Android / iOS:\033[0m")
    print("   Toca el menú del navegador y selecciona \033[1m'Instalar aplicación'\033[0m o")
    print("   \033[1m'Añadir a pantalla de inicio'\033[0m para usarla a pantalla completa")
    print("   como una app nativa de Apple.")
    print("")
    print("\033[90mPresiona Ctrl+C para detener el servidor.\033[0m")
    print("-" * 60)

    # Intentar abrir la app automáticamente
    abrir_en_navegador(url_local)

    # Iniciar servidor
    run_server(port=puerto, host="0.0.0.0")

if __name__ == "__main__":
    main()
