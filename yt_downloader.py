#!/data/data/com.termux/files/usr/bin/python
import sys
import os
import time
from pathlib import Path

# Configuración de Colores
class Colores:
    HEADER = '\033[95m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    GREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'

def instalar_ytdlp():
    print(f"{Colores.WARNING}Verificando dependencias...{Colores.ENDC}")
    os.system("pip install yt-dlp mutagen --quiet")

try:
    import yt_dlp
except ImportError:
    instalar_ytdlp()
    import yt_dlp

# Variable global para saber si se descargó algo
hubo_descarga = False

def progress_hook(d):
    global hubo_descarga
    if d['status'] == 'downloading':
        hubo_descarga = True
        percent = d.get('_percent_str', '0%').replace('%','')
        speed = d.get('_speed_str', 'N/A')
        eta = d.get('_eta_str', 'N/A')
        sys.stdout.write(f"\r{Colores.CYAN}⬇ Bajando: {Colores.BOLD}{percent}% {Colores.ENDC}| Vel: {speed} | ETA: {eta}")
        sys.stdout.flush()
    elif d['status'] == 'finished':
        print(f"\n{Colores.GREEN}✔ Descargado. Procesando audio HD...{Colores.ENDC}")

def sanitizar_nombre(nombre):
    """Elimina caracteres inválidos en nombres de carpetas"""
    caracteres_invalidos = ['/', '\\', ':', '*', '?', '"', '<', '>', '|']
    for char in caracteres_invalidos:
        nombre = nombre.replace(char, '')
    return nombre.strip()

def descargar_musica(url):
    global hubo_descarga
    hubo_descarga = False
    
    # Ruta base de descarga - Biblioteca
    base_path = '/data/data/com.termux/files/home/storage/music'
    biblioteca_path = os.path.join(base_path, 'Biblioteca')
    
    # Crear carpeta Biblioteca si no existe
    os.makedirs(biblioteca_path, exist_ok=True)
    
    # Ruta del archivo de historial (evita duplicados)
    archivo_historial = '/data/data/com.termux/files/home/.historial_descargas_youtube.txt'
    
    print(f"\n{Colores.HEADER}{Colores.BOLD}=== YouTube Music Downloader (Smart) ==={Colores.ENDC}")
    print(f"{Colores.BLUE}Origen: {url}{Colores.ENDC}")

    # Template de descarga: Biblioteca/Artista/Álbum/Canción
    download_template = os.path.join(biblioteca_path, 
        '%(artist)s/%(album)s/%(title)s.%(ext)s')

    ydl_opts = {
        # --- FORMATO DE AUDIO ---
        'format': 'bestaudio/best',
        'outtmpl': download_template,
        'writethumbnail': True,
        'noplaylist': False,
        
        # --- EXTRACCIÓN DE METADATOS ---
        'postprocessor_args': ['-metadata', 'artist=%(artist)s', '-metadata', 'album=%(album)s'],
        'writeinfojson': False,
        
        # --- SECCIÓN ANTI-DUPLICADOS ---
        'download_archive': archivo_historial,
        'ignoreerrors': True,
        
        # -------- FIX PARA EL BLOQUEO 429 DE YOUTUBE --------
        'extractor_args': {
            'youtube': {
                'player_client': ['android', 'web'],
                'player_skip': ['webpage', 'configs'],
            }
        },
        # --- REINTENTOS Y TIMEOUTS (Optimizado para Termux) ---
        'retries': 10,
        'fragment_retries': 10,
        'skip_unavailable_fragments': True,
        'socket_timeout': 30,
        'socket_connect_timeout': 30,
        'http_chunk_size': 10485760,
        
        # --- DESCARGA EFICIENTE EN TERMUX ---
        'concurrent_fragment_downloads': 1,
        'quiet': False,
        'no_warnings': False,
        'ratelimit': 500000,

        'postprocessors': [
            {
                'key': 'FFmpegExtractAudio',
                'preferredcodec': 'mp3',
                'preferredquality': '320',
            },
            {
                'key': 'EmbedThumbnail',
            },
            {
                'key': 'FFmpegMetadata',
                'add_metadata': True,
            }
        ],
        'postprocessor_args': [
            '-ac', '2', 
            '-metadata', 'comment=Descargado con Termux'
        ],
        'progress_hooks': [progress_hook],
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            # Extraemos info básica primero
            info = ydl.extract_info(url, download=False)
            
            titulo_general = info.get('title', 'Desconocido')
            artista = sanitizar_nombre(info.get('artist', 'Artista Desconocido'))
            album = sanitizar_nombre(info.get('album', 'Álbum Desconocido'))
            es_playlist = 'entries' in info
            
            if es_playlist:
                print(f"Playlist detectada: {Colores.BOLD}{titulo_general}{Colores.ENDC}")
                print(f"{Colores.WARNING}Organizando en carpetas por artista/álbum...{Colores.ENDC}")
            else:
                print(f"Canción: {Colores.BOLD}{titulo_general}{Colores.ENDC}")
                print(f"👤 Artista: {Colores.BOLD}{artista}{Colores.ENDC}")
                print(f"💿 Álbum: {Colores.BOLD}{album}{Colores.ENDC}")

            print("-" * 40)
            
            # Ejecutar la descarga
            ydl.download([url])
            
        if hubo_descarga:
            print(f"\n{Colores.GREEN}{Colores.BOLD}¡Proceso Completado!{Colores.ENDC}")
            print(f"📂 Ubicación: {biblioteca_path}/{artista}/{album}/")
        else:
            print(f"\n{Colores.WARNING}Información: No se descargaron archivos nuevos.{Colores.ENDC}")
            print("Las canciones ya existían en tu historial de descargas.")

        time.sleep(2)

    except Exception as e:
        print(f"\n{Colores.FAIL}Error: {str(e)}{Colores.ENDC}")
        input("\nPresiona Enter para salir...")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        descargar_musica(sys.argv[1])
    else:
        print(f"{Colores.HEADER}Modo Manual (Smart){Colores.ENDC}")
        url_input = input("Pega el link aquí: ")
        if url_input:
            descargar_musica(url_input)
