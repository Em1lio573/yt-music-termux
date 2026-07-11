#!/data/data/com.termux/files/usr/bin/python
import sys
import os
import time
import re
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
    from yt_dlp.postprocessor import PostProcessor
except ImportError:
    instalar_ytdlp()
    import yt_dlp
    from yt_dlp.postprocessor import PostProcessor

try:
    from mutagen.mp3 import MP3
    from mutagen.id3 import ID3, TIT2, TPE1, TALB, APIC
except ImportError:
    pass

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
    if not nombre:
        return "Desconocido"
    caracteres_invalidos = ['/', '\\', ':', '*', '?', '"', '<', '>', '|']
    for char in caracteres_invalidos:
        nombre = nombre.replace(char, '')
    return nombre.strip() or "Desconocido"

def extraer_artista_principal(info):
    """Extrae solo el artista principal (el primero en la lista o el creador principal)"""
    # Intentar obtener artista directo
    artista = info.get('artist')
    if artista:
        # Separadores comunes: comas, punto y coma, '&', 'feat', 'ft', 'featuring'
        separadores = r',|;| & |&| feat\.? | ft\.? | featuring '
        partes = re.split(separadores, artista, flags=re.IGNORECASE)
        artista_principal = partes[0].strip()
        return sanitizar_nombre(artista_principal)
    
    # Fallback a uploader
    uploader = info.get('uploader')
    if uploader:
        # Limpiar cosas comunes de YouTube como " - Topic" o "VEVO"
        uploader_clean = re.sub(r'\s*-\s*Topic$', '', uploader, flags=re.IGNORECASE)
        uploader_clean = re.sub(r'\s*VEVO$', '', uploader_clean, flags=re.IGNORECASE)
        return sanitizar_nombre(uploader_clean)
    
    return "Artista Desconocido"

def obtener_todos_artistas(info):
    """Obtiene todos los artistas para los metadatos"""
    artista = info.get('artist')
    if artista:
        return artista
    
    uploader = info.get('uploader')
    if uploader:
        return uploader
    
    return "Artista Desconocido"

class MetadataPreProcessor(PostProcessor):
    def run(self, info):
        artista_principal = extraer_artista_principal(info)
        album = sanitizar_nombre(info.get('album') or 'Álbum Desconocido')
        titulo = sanitizar_nombre(info.get('title') or info.get('track') or 'Desconocido')
        
        info['custom_artist'] = artista_principal
        info['custom_album'] = album
        info['custom_title'] = titulo
        return [], info

def ejecutar_media_scan(ruta_archivo):
    """Llama a termux-media-scan de forma silenciosa para que Android indexe el archivo"""
    try:
        import subprocess
        subprocess.run(["termux-media-scan", ruta_archivo], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass

class MediaScanPostProcessor(PostProcessor):
    def run(self, info):
        filepath = info.get('filepath')
        if filepath:
            ejecutar_media_scan(filepath)
        return [], info

def exportar_cookies_desde_navegador():
    """Guía al usuario para exportar cookies"""
    print(f"\n{Colores.HEADER}=== Exportar Cookies de YouTube ==={Colores.ENDC}")
    print(f"{Colores.BLUE}Sigue estos pasos:{Colores.ENDC}")
    print("1. En tu PC/Mac, abre: https://www.youtube.com")
    print("2. Inicia sesión si no lo has hecho")
    print("3. Instala una extensión para exportar cookies:")
    print("   - Chrome: 'Get cookies.txt LOCALLY'")
    print("   - Firefox: 'Open With > cookies.txt'")
    print("4. Exporta las cookies en formato Netscape")
    print("5. Copia el contenido del archivo")
    print(f"\n{Colores.CYAN}Ahora pega el contenido de las cookies:{Colores.ENDC}")
    print("(Presiona Ctrl+D cuando termines en Linux/Mac o Ctrl+Z en Windows)")
    print("-" * 60)
    
    # Recopilar entrada multilinea
    lineas = []
    try:
        while True:
            linea = input()
            lineas.append(linea)
    except EOFError:
        pass
    
    contenido_cookies = "\n".join(lineas)
    
    if not contenido_cookies.strip():
        print(f"{Colores.FAIL}No se ingresó contenido de cookies{Colores.ENDC}")
        return False
    
    # Guardar cookies
    cookies_path = '/data/data/com.termux/files/home/.cookies.txt'
    try:
        with open(cookies_path, 'w') as f:
            f.write(contenido_cookies)
        print(f"{Colores.GREEN}✔ Cookies guardadas en: {cookies_path}{Colores.ENDC}")
        return True
    except Exception as e:
        print(f"{Colores.FAIL}Error al guardar cookies: {str(e)}{Colores.ENDC}")
        return False

def actualizar_metadatos_mp3(ruta_archivo, info):
    """Actualiza los metadatos del MP3 con información completa"""
    try:
        import mutagen
        from mutagen.mp3 import MP3
        from mutagen.id3 import ID3, TIT2, TPE1, TALB, COMM
        
        audio = MP3(ruta_archivo)
        
        if audio.tags is None:
            audio.add_tags()
        
        tags = audio.tags
        
        # Título
        titulo = info.get('title', 'Desconocido')
        tags['TIT2'] = TIT2(encoding=3, text=[titulo])
        
        # Artistas (TODOS, no solo el principal)
        todos_artistas = obtener_todos_artistas(info)
        tags['TPE1'] = TPE1(encoding=3, text=[todos_artistas])
        
        # Álbum
        album = info.get('album', 'Álbum Desconocido')
        tags['TALB'] = TALB(encoding=3, text=[album])
        
        # Comentario
        tags['COMM'] = COMM(encoding=3, lang='eng', desc='', text=['Descargado con Termux YouTube Music Downloader'])
        
        tags.save()
        print(f"{Colores.GREEN}✔ Metadatos actualizados:{Colores.ENDC}")
        print(f"  Título: {titulo}")
        print(f"  Artistas: {todos_artistas}")
        print(f"  Álbum: {album}")
        
    except Exception as e:
        print(f"{Colores.WARNING}⚠ No se pudieron actualizar los metadatos: {str(e)}{Colores.ENDC}")

def descargar_musica(url, reintentar=False):
    global hubo_descarga
    hubo_descarga = False
    
    # Ruta base de descarga - Biblioteca
    base_path = '/data/data/com.termux/files/home/storage/music'
    biblioteca_path = os.path.join(base_path, 'Biblioteca')
    
    # Crear carpeta Biblioteca si no existe
    os.makedirs(biblioteca_path, exist_ok=True)
    
    # Ruta del archivo de historial (evita duplicados)
    archivo_historial = '/data/data/com.termux/files/home/.historial_descargas_youtube.txt'
    
    # Ruta para cookies (si existen)
    cookies_path = '/data/data/com.termux/files/home/.cookies.txt'
    
    if not reintentar:
        print(f"\n{Colores.HEADER}{Colores.BOLD}=== YouTube Music Downloader (Smart) ==={Colores.ENDC}")
    else:
        print(f"\n{Colores.HEADER}{Colores.BOLD}=== Reintentando Descarga ==={Colores.ENDC}")
    
    print(f"{Colores.BLUE}Origen: {url}{Colores.ENDC}")

    # Template de descarga: Biblioteca/Artista Principal/Álbum/Canción
    # Solo usamos el artista principal en la carpeta (inyectado por el preprocesador)
    download_template = os.path.join(biblioteca_path, 
        '%(custom_artist)s/%(custom_album)s/%(custom_title)s.%(ext)s')

    ydl_opts = {
        # --- FORMATO DE AUDIO ---
        'format': 'bestaudio/best',
        'outtmpl': download_template,
        'writethumbnail': True,
        'noplaylist': False,
        
        # --- MANEJO DE COOKIES Y AUTENTICACIÓN ---
        'cookiefile': cookies_path if os.path.exists(cookies_path) else None,
        'http_headers': {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        },
        
        # --- SECCIÓN ANTI-DUPLICADOS ---
        'download_archive': archivo_historial,
        'ignoreerrors': True,
        
        # -------- FIX PARA EL BLOQUEO 429 DE YOUTUBE --------
        'extractor_args': {
            'youtube': {
                'player_client': ['android', 'web', 'ios'],
                'player_skip': ['webpage'],
                'html5_player': None,
            }
        },
        # --- REINTENTOS Y TIMEOUTS (Optimizado para Termux) ---
        'retries': 15,
        'fragment_retries': 15,
        'skip_unavailable_fragments': True,
        'socket_timeout': 30,
        'socket_connect_timeout': 30,
        'http_chunk_size': 10485760,
        
        # --- DESCARGA EFICIENTE EN TERMUX ---
        'concurrent_fragment_downloads': 1,
        'quiet': False,
        'no_warnings': False,
        'ratelimit': 500000,
        'throttled_rate': 100000,

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
            # Añadir preprocesador de metadatos para personalizar rutas
            ydl.add_post_processor(MetadataPreProcessor(), when='pre_process')
            
            # Añadir postprocesador para notificar a la base de datos de Android
            ydl.add_post_processor(MediaScanPostProcessor(), when='post_process')
            
            # Extraemos info básica primero (sin descargar)
            print(f"{Colores.WARNING}Extrayendo información...{Colores.ENDC}")
            info = ydl.extract_info(url, download=False)
            
            # Manejo seguro de metadatos (ya procesados)
            titulo_general = info.get('title') or 'Desconocido'
            artista_principal = info.get('custom_artist') or extraer_artista_principal(info)
            todos_artistas = obtener_todos_artistas(info)
            album = info.get('custom_album') or sanitizar_nombre(info.get('album') or 'Álbum Desconocido')
            es_playlist = 'entries' in info
            
            if es_playlist:
                print(f"Playlist detectada: {Colores.BOLD}{titulo_general}{Colores.ENDC}")
                print(f"{Colores.WARNING}Organizando en carpetas por artista/álbum...{Colores.ENDC}")
            else:
                print(f"Canción: {Colores.BOLD}{titulo_general}{Colores.ENDC}")
                print(f"👤 Artista Principal: {Colores.BOLD}{artista_principal}{Colores.ENDC}")
                if todos_artistas != artista_principal:
                    print(f"👥 Todos los Artistas: {Colores.BOLD}{todos_artistas}{Colores.ENDC}")
                print(f"💿 Álbum: {Colores.BOLD}{album}{Colores.ENDC}")

            print("-" * 40)
            
            # Ejecutar la descarga
            ydl.download([url])
            
        if hubo_descarga:
            print(f"\n{Colores.GREEN}{Colores.BOLD}¡Proceso Completado!{Colores.ENDC}")
            print(f"📂 Ubicación: {biblioteca_path}/{artista_principal}/{album}/")
            print(f"👥 Metadatos: Incluyen todos los artistas ({todos_artistas})")
        else:
            print(f"\n{Colores.WARNING}Información: No se descargaron archivos nuevos.{Colores.ENDC}")
            print("Las canciones ya existían en tu historial de descargas.")

        time.sleep(2)
        return True

    except yt_dlp.utils.DownloadError as e:
        if "Sign in to confirm" in str(e) or "bot" in str(e).lower():
            print(f"\n{Colores.FAIL}Error de Autenticación de YouTube{Colores.ENDC}")
            print(f"{Colores.WARNING}YouTube requiere autenticación.{Colores.ENDC}")
            
            print(f"\n{Colores.CYAN}Opciones disponibles:{Colores.ENDC}")
            print("1. Exportar cookies manualmente (recomendado)")
            print("2. Intentar con otro cliente")
            print("3. Cancelar")
            
            opcion = input(f"\n{Colores.BLUE}¿Qué deseas hacer? (1/2/3): {Colores.ENDC}").strip()
            
            if opcion == "1":
                if exportar_cookies_desde_navegador():
                    print(f"\n{Colores.GREEN}Reintentando descarga con cookies...{Colores.ENDC}")
                    time.sleep(2)
                    return descargar_musica(url, reintentar=True)
                else:
                    print(f"{Colores.FAIL}No se pudieron guardar las cookies{Colores.ENDC}")
            elif opcion == "2":
                print(f"{Colores.WARNING}Intenta actualizar yt-dlp: pip install yt-dlp --upgrade{Colores.ENDC}")
            
            return False
        else:
            print(f"\n{Colores.FAIL}Error de Descarga: {str(e)}{Colores.ENDC}")
            return False
            
    except Exception as e:
        print(f"\n{Colores.FAIL}Error: {str(e)}{Colores.ENDC}")
        import traceback
        traceback.print_exc()
        return False
    
    finally:
        input(f"\n{Colores.BLUE}Presiona Enter para salir...{Colores.ENDC}")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        descargar_musica(sys.argv[1])
    else:
        print(f"{Colores.HEADER}Modo Manual (Smart){Colores.ENDC}")
        url_input = input("Pega el link aquí: ")
        if url_input:
            descargar_musica(url_input)
