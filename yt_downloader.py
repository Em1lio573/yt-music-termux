#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Termux YouTube Music Downloader (Pro)
Descargador de audio en alta fidelidad real con prioridad en YouTube Music,
base de datos de duración oficial (iTunes API), SponsorBlock, organización inteligente
y gestión interactiva.
"""

import sys
import os
import time
import re
import json
import urllib.request
import urllib.parse
from pathlib import Path

# --- COLORES TERMINAL ---
class Colores:
    HEADER = '\033[95m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    GREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'
    DIM = '\033[2m'

# --- VERIFICACIÓN DE DEPENDENCIAS ---
def instalar_dependencias():
    print(f"{Colores.WARNING}Instalando dependencias necesarias (yt-dlp, mutagen)...{Colores.ENDC}")
    os.system("pip install yt-dlp mutagen --upgrade --quiet")

try:
    import yt_dlp
    from yt_dlp.postprocessor import PostProcessor
except ImportError:
    instalar_dependencias()
    import yt_dlp
    from yt_dlp.postprocessor import PostProcessor

try:
    import mutagen
except ImportError:
    pass

# --- GESTOR DE CONFIGURACIÓN ---
CONFIG_DIR = Path.home() / ".config" / "yt-music-termux"
CONFIG_FILE = CONFIG_DIR / "config.json"

CALIDADES = {
    "native": {
        "nombre": "Opus Nativo (~160 kbps VBR - Máster sin recodificar)",
        "desc": "Máxima fidelidad real de YouTube Music por extracción directa sin pérdida.",
        "codec": "opus",
        "format": "bestaudio[ext=opus]/bestaudio[ext=webm]/bestaudio/best",
        "ext": "opus"
    },
    "m4a": {
        "nombre": "M4A / AAC Nativo (~128-140 kbps - Universal)",
        "desc": "Extracción directa AAC compatible con Apple, Android y estéreos modernos.",
        "codec": "m4a",
        "format": "bestaudio[ext=m4a]/bestaudio/best",
        "ext": "m4a"
    },
    "m4a_256": {
        "nombre": "M4A Premium (256 kbps - Alta Fidelidad)",
        "desc": "Máxima calidad de YouTube Music Premium (itag 141 con fallback nativo).",
        "codec": "m4a",
        "format": "141/bestaudio[ext=m4a]/bestaudio/best",
        "ext": "m4a"
    },
    "mp3_320": {
        "nombre": "MP3 320 kbps (Transcodificado - Compatibilidad)",
        "desc": "Transcodificación LAME con FFmpeg para estéreos antiguos de automóvil.",
        "codec": "mp3",
        "format": "bestaudio/best",
        "quality": "320",
        "ext": "mp3"
    },
    "data_saver": {
        "nombre": "Opus Ahorro de Datos (~70 kbps)",
        "desc": "Mínimo consumo de espacio en disco y datos móviles (itags 250/249).",
        "codec": "opus",
        "format": "250/249/bestaudio[abr<=80]/bestaudio/best",
        "ext": "opus"
    }
}

def obtener_directorio_musica_defecto():
    termux_music = Path("/data/data/com.termux/files/home/storage/music")
    if termux_music.exists():
        return str(termux_music / "Biblioteca")
    pc_music = Path.home() / "Music" / "Biblioteca"
    return str(pc_music)

CONFIG_DEFECTO = {
    "default_quality": "native",
    "countdown_seconds": 4,
    "prefer_ytmusic": True,
    "verify_duration": True,
    "sponsorblock_offtopic": True,
    "clean_titles": True,
    "music_directory": obtener_directorio_musica_defecto(),
    "notifications": True,
    "media_scan": True,
    "archive_history": True
}

class ConfigManager:
    def __init__(self):
        self.config = CONFIG_DEFECTO.copy()
        self.cargar()

    def cargar(self):
        if CONFIG_FILE.exists():
            try:
                with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                    datos = json.load(f)
                    self.config.update(datos)
            except Exception as e:
                print(f"{Colores.WARNING}Error leyendo config: {e}{Colores.ENDC}")
        else:
            self.guardar()

    def guardar(self):
        try:
            CONFIG_DIR.mkdir(parents=True, exist_ok=True)
            with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
                json.dump(self.config, f, indent=4, ensure_ascii=False)
        except Exception as e:
            print(f"{Colores.WARNING}Error guardando config: {e}{Colores.ENDC}")

    def get(self, key, fallback=None):
        return self.config.get(key, fallback)

    def set(self, key, value):
        self.config[key] = value
        self.guardar()

cfg = ConfigManager()

# --- RUTAS GLOBALES ---
RUTA_HISTORIAL = Path.home() / ".historial_descargas_youtube.txt"
RUTA_COOKIES = Path.home() / ".cookies.txt"

# --- UTILIDADES DE TEXTO Y LIMPIEZA ---
def sanitizar_nombre(nombre):
    """Elimina caracteres inválidos en sistemas de archivos (Android/FAT32/Linux)"""
    if not nombre:
        return "Desconocido"
    caracteres_invalidos = ['/', '\\', ':', '*', '?', '"', '<', '>', '|']
    for char in caracteres_invalidos:
        nombre = nombre.replace(char, '')
    # Normalizar espacios múltiples
    nombre = re.sub(r'\s+', ' ', nombre)
    # Quitar puntos finales y espacios que causan errores en Android
    return nombre.strip(' .') or "Desconocido"

def limpiar_titulo(raw_title):
    """
    Limpia títulos de YouTube eliminando coletillas típicas de videoclips
    y separa 'Artista - Canción' si está en el título.
    """
    if not raw_title:
        return None, "Desconocido"
    
    # Patrón exhaustivo para capturar:
    # (Official Video), [Official Audio], (Lyric Video), [4K], (Remastered), (Video Oficial), etc.
    patron = r'[\(\[]\s*(?:(?:official|oficial|video|audio|lyric|lyrics|letra|videoclip|clip|music\s*video|4k|hd|remastered|visualizer|live|en\s*vivo|full\s*album)[\s\-_/|]*)+[\)\]]'
    t = re.sub(patron, '', raw_title, flags=re.IGNORECASE)
    t = re.sub(r'\s+', ' ', t).strip(' -')
    
    artist = None
    title = t
    if ' - ' in t:
        partes = t.split(' - ', 1)
        artist = partes[0].strip()
        title = partes[1].strip()
        title = re.sub(patron, '', title, flags=re.IGNORECASE).strip(' -')
    
    return artist, title

def extraer_artista_principal(info):
    """Extrae el artista principal para organizar las carpetas"""
    artista = info.get('artist')
    if artista:
        separadores = r',|;| & |&| feat\.? | ft\.? | featuring '
        partes = re.split(separadores, artista, flags=re.IGNORECASE)
        return sanitizar_nombre(partes[0].strip())
    
    # Si no hay tag de artista, intentar extraer del título limpio
    art_tit, _ = limpiar_titulo(info.get('title') or '')
    if art_tit:
        return sanitizar_nombre(art_tit)
    
    uploader = info.get('uploader')
    if uploader:
        uploader_clean = re.sub(r'\s*-\s*Topic$', '', uploader, flags=re.IGNORECASE)
        uploader_clean = re.sub(r'\s*VEVO$', '', uploader_clean, flags=re.IGNORECASE)
        return sanitizar_nombre(uploader_clean)
    
    return "Artista Desconocido"

def obtener_todos_artistas(info):
    """Obtiene todos los artistas para las etiquetas de metadatos"""
    artista = info.get('artist')
    if artista:
        return artista
    art_tit, _ = limpiar_titulo(info.get('title') or '')
    if art_tit:
        return art_tit
    uploader = info.get('uploader')
    if uploader:
        uploader = re.sub(r'\s*-\s*Topic$', '', uploader, flags=re.IGNORECASE)
        return re.sub(r'\s*VEVO$', '', uploader, flags=re.IGNORECASE)
    return "Artista Desconocido"

# --- BASE DE DATOS DE DURACIÓN OFICIAL (ITUNES / APPLE MUSIC) ---
def obtener_duracion_oficial(query):
    """
    Consulta la API pública de iTunes para obtener la duración oficial exacta
    de la pista en estudio (en segundos) y metadatos oficiales del álbum.
    """
    url = f"https://itunes.apple.com/search?term={urllib.parse.quote(query)}&entity=song&limit=1"
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=3.5) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            if data.get('resultCount', 0) > 0:
                s = data['results'][0]
                millis = s.get('trackTimeMillis', 0)
                art_url = s.get('artworkUrl100', '')
                if art_url:
                    art_url = art_url.replace('100x100bb', '1200x1200bb')
                return {
                    'artist': s.get('artistName'),
                    'title': s.get('trackName'),
                    'album': s.get('collectionName'),
                    'duration': millis / 1000.0,
                    'cover_url': art_url,
                    'release_date': s.get('releaseDate', '')[:10]
                }
    except Exception:
        pass
    return None

# --- RESOLVEDOR DE YOUTUBE MUSIC (TOPIC TRACKS) ---
def resolver_ytmusic(artist, title, dur_esperada=None):
    """
    Busca en YouTube Music la versión oficial de estudio ('Topic track' / 'Provided to YouTube')
    y selecciona la que mejor coincida con la duración esperada.
    """
    q = f"{artist} {title}"
    search_url = f"https://music.youtube.com/search?q={urllib.parse.quote(q)}"
    
    ydl_opts = {
        'quiet': True,
        'extract_flat': True,
        'ignoreerrors': True,
        'cookiefile': str(RUTA_COOKIES) if RUTA_COOKIES.exists() else None
    }
    
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(search_url, download=False)
            candidatos = []
            for e in info.get('entries') or []:
                if not e:
                    continue
                url = e.get('url') or e.get('webpage_url')
                if url and ('watch?v=' in url):
                    candidatos.append(e)
            
            if not candidatos:
                return None
            
            # Si tenemos duración esperada, buscar la pista con menor diferencia
            if dur_esperada and dur_esperada > 0:
                mejor_candidato = None
                menor_diff = 999999.0
                for c in candidatos[:6]:
                    dur = c.get('duration')
                    if dur:
                        diff = abs(dur - dur_esperada)
                        if diff < menor_diff:
                            menor_diff = diff
                            mejor_candidato = c
                
                # Tolerancia de hasta 8 segundos respecto al máster de estudio
                if mejor_candidato and menor_diff <= 8.0:
                    return mejor_candidato
            
            # Si no hay duración o no coincidió, el primer resultado de YT Music suele ser el tema oficial
            return candidatos[0]
            
    except Exception:
        pass
    return None

# --- PREPROCESADOR DE METADATOS Y RUTAS INTELIGENTES ---
class MetadataPreProcessor(PostProcessor):
    def __init__(self, es_playlist=False, playlist_title=None, metadata_oficial=None):
        super().__init__()
        self.es_playlist = es_playlist
        self.playlist_title = playlist_title
        self.metadata_oficial = metadata_oficial or {}

    def run(self, info):
        # 1. Artista y Título
        artista_raw = self.metadata_oficial.get('artist') or extraer_artista_principal(info)
        artista_principal = sanitizar_nombre(artista_raw)
        todos_artistas = self.metadata_oficial.get('artist') or obtener_todos_artistas(info)
        
        _, tit_limpio = limpiar_titulo(info.get('title') or '')
        titulo_raw = self.metadata_oficial.get('title') or tit_limpio or info.get('track') or 'Desconocido'
        titulo = sanitizar_nombre(titulo_raw)
        
        # 2. Álbum
        album_orig = self.metadata_oficial.get('album') or info.get('album')
        es_album_valido = bool(album_orig and album_orig.strip().lower() not in ['desconocido', 'álbum desconocido', 'unknown album'])
        
        # 3. Número de Pista
        track_num = info.get('playlist_index') or info.get('track_number')
        
        # 4. Organización Inteligente de Carpetas:
        if self.es_playlist and self.playlist_title:
            # Playlists: Biblioteca/Playlists/<Nombre Playlist>/<01 - Artista - Título>
            carpeta = os.path.join('Playlists', sanitizar_nombre(self.playlist_title))
            prefijo = f"{track_num:02d} - " if track_num else ""
            nombre_archivo = sanitizar_nombre(f"{prefijo}{artista_principal} - {titulo}")
        elif es_album_valido:
            # Álbum: Biblioteca/<Artista>/<Álbum>/<01 - Título>
            carpeta = os.path.join(artista_principal, sanitizar_nombre(album_orig))
            prefijo = f"{track_num:02d} - " if track_num else ""
            nombre_archivo = sanitizar_nombre(f"{prefijo}{titulo}")
        else:
            # Singles / Canciones sueltas: Biblioteca/<Artista>/Singles/<Título>
            carpeta = os.path.join(artista_principal, 'Singles')
            nombre_archivo = sanitizar_nombre(titulo)
        
        info['custom_dir'] = carpeta
        info['custom_filename'] = nombre_archivo
        info['custom_artist'] = artista_principal
        info['custom_all_artists'] = todos_artistas
        info['custom_album'] = album_orig or 'Single'
        info['custom_title'] = titulo
        
        # Inyectar portada oficial en alta definición (1200x1200px) si está disponible
        if self.metadata_oficial and self.metadata_oficial.get('cover_url'):
            info['thumbnail'] = self.metadata_oficial['cover_url']
            info['thumbnails'] = [{'url': self.metadata_oficial['cover_url'], 'id': 'cover_hd'}]
        
        return [], info

# --- INTEGRACIÓN CON ANDROID (TERMUX-API) ---
def ejecutar_media_scan(ruta_archivo):
    """Notifica al MediaScanner de Android para que la canción aparezca de inmediato en reproductores"""
    try:
        import subprocess
        subprocess.run(["termux-media-scan", ruta_archivo], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass

def enviar_notificacion(titulo, mensaje):
    """Muestra una notificación en la barra de Android usando Termux:API"""
    try:
        import subprocess
        subprocess.run([
            "termux-notification",
            "--title", titulo,
            "--content", mensaje,
            "--icon", "music_note",
            "--priority", "high"
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass

class MediaScanPostProcessor(PostProcessor):
    def run(self, info):
        filepath = info.get('filepath')
        if filepath:
            ejecutar_media_scan(filepath)
        return [], info

# --- ACTUALIZACIÓN DE ETIQUETAS MULTIFORMATO ---
def actualizar_metadatos_archivo(ruta_archivo, info):
    """Actualiza metadatos de forma universal usando mutagen (MP3, M4A, Opus, FLAC)"""
    try:
        import mutagen
        audio = mutagen.File(ruta_archivo, easy=True)
        if audio is not None:
            titulo = info.get('custom_title') or info.get('title')
            artistas = info.get('custom_all_artists') or info.get('artist')
            album = info.get('custom_album')
            track = info.get('playlist_index') or info.get('track_number')
            
            if titulo:
                audio['title'] = [titulo]
            if artistas:
                audio['artist'] = [artistas]
            if album:
                audio['album'] = [album]
            if track:
                audio['tracknumber'] = [str(track)]
            
            audio.save()
    except Exception:
        pass

# --- PROGRESS HOOK ---
hubo_descarga = False
def progress_hook(d):
    global hubo_descarga
    if d['status'] == 'downloading':
        hubo_descarga = True
        percent = d.get('_percent_str', '0%').replace('%', '').strip()
        speed = d.get('_speed_str', 'N/A')
        eta = d.get('_eta_str', 'N/A')
        sys.stdout.write(f"\r{Colores.CYAN}⬇ Descargando: {Colores.BOLD}{percent}%{Colores.ENDC} | Vel: {speed} | ETA: {eta} ")
        sys.stdout.flush()
    elif d['status'] == 'finished':
        print(f"\n{Colores.GREEN}✔ Descarga completada. Procesando metadatos y carátula...{Colores.ENDC}")

# --- CUENTA REGRESIVA INTERACTIVA ---
def cuenta_regresiva(segundos, calidad_clave):
    """
    Cuenta regresiva no bloqueante. Si el usuario presiona una tecla,
    se detiene y permite cambiar la calidad de descarga.
    """
    calidad_info = CALIDADES.get(calidad_clave, CALIDADES['native'])
    
    if segundos <= 0:
        return calidad_clave
    
    # Si stdin no es una terminal (ej. piping), retornar calidad por defecto
    if not sys.stdin.isatty():
        return calidad_clave

    import select
    import termios
    import tty

    fd = sys.stdin.fileno()
    old_settings = termios.tcgetattr(fd)
    tecla_pulsada = False
    
    print(f"\n{Colores.CYAN}⚡ Formato: {Colores.BOLD}{calidad_info['nombre']}{Colores.ENDC}")
    print(f"{Colores.DIM}   {calidad_info['desc']}{Colores.ENDC}")
    
    try:
        tty.setcbreak(fd)
        for restante in range(segundos, 0, -1):
            sys.stdout.write(f"\r{Colores.WARNING}⏳ Iniciando descarga en {Colores.BOLD}{restante}s{Colores.ENDC} {Colores.WARNING}(Presiona cualquier tecla para cambiar calidad)...{Colores.ENDC} ")
            sys.stdout.flush()
            r, _, _ = select.select([sys.stdin], [], [], 1.0)
            if r:
                sys.stdin.read(1)
                tecla_pulsada = True
                break
        sys.stdout.write("\r" + " " * 80 + "\r")
        sys.stdout.flush()
    except Exception:
        tecla_pulsada = False
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)

    if tecla_pulsada:
        return menu_seleccion_calidad(calidad_clave)
    
    return calidad_clave

def menu_seleccion_calidad(calidad_actual):
    """Menú interactivo para cambiar la calidad de descarga"""
    print(f"\n{Colores.HEADER}{Colores.BOLD}=== Selecciona la Calidad de Audio ==={Colores.ENDC}")
    keys = list(CALIDADES.keys())
    for idx, k in enumerate(keys, 1):
        actual = f" {Colores.GREEN}[ACTUAL]{Colores.ENDC}" if k == calidad_actual else ""
        print(f"{Colores.CYAN}[{idx}]{Colores.ENDC} {CALIDADES[k]['nombre']}{actual}")
        print(f"    {Colores.DIM}{CALIDADES[k]['desc']}{Colores.ENDC}")
    print(f"{Colores.CYAN}[6]{Colores.ENDC} Guardar la selección como predeterminada")
    
    eleccion = input(f"\n{Colores.BLUE}Opción (1-6) [Enter para mantener actual]: {Colores.ENDC}").strip()
    
    if eleccion in ['1', '2', '3', '4', '5']:
        return keys[int(eleccion) - 1]
    elif eleccion == '6':
        sub_op = input(f"{Colores.BLUE}¿Qué calidad deseas por defecto? (1-5): {Colores.ENDC}").strip()
        if sub_op in ['1', '2', '3', '4', '5']:
            nueva_cal = keys[int(sub_op) - 1]
            cfg.set("default_quality", nueva_cal)
            print(f"{Colores.GREEN}✔ Calidad '{CALIDADES[nueva_cal]['nombre']}' guardada por defecto.{Colores.ENDC}")
            return nueva_cal
    
    return calidad_actual

# --- CONSTRUCTOR DE OPCIONES YT-DLP ---
def construir_ydl_opts(calidad_clave, template_salida, usar_sponsorblock=False):
    calidad = CALIDADES.get(calidad_clave, CALIDADES['native'])
    
    postprocessors = []
    
    # 1. SponsorBlock (si se descarga un videoclip)
    if usar_sponsorblock and cfg.get("sponsorblock_offtopic", True):
        postprocessors.append({'key': 'SponsorBlock', 'categories': ['music_offtopic']})
        postprocessors.append({'key': 'ModifyChapters', 'remove_sponsor_segments': ['music_offtopic']})
    
    # 2. Extracción / Transcodificación de audio
    extract_audio_pp = {
        'key': 'FFmpegExtractAudio',
        'preferredcodec': calidad['codec'],
    }
    if 'quality' in calidad:
        extract_audio_pp['preferredquality'] = calidad['quality']
    postprocessors.append(extract_audio_pp)
    
    # 3. Metadatos
    postprocessors.append({
        'key': 'FFmpegMetadata',
        'add_metadata': True
    })
    
    # 4. Conversor de Miniaturas y Embebedor
    postprocessors.append({
        'key': 'FFmpegThumbnailsConvertor',
        'format': 'jpg'
    })
    postprocessors.append({
        'key': 'EmbedThumbnail'
    })
    
    opts = {
        'format': calidad['format'],
        'outtmpl': template_salida,
        'writethumbnail': True,
        'noplaylist': False,
        'cookiefile': str(RUTA_COOKIES) if RUTA_COOKIES.exists() else None,
        'http_headers': {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        },
        'download_archive': str(RUTA_HISTORIAL) if cfg.get("archive_history", True) else None,
        'ignoreerrors': True,
        'extractor_args': {
            'youtube': {
                'player_client': ['android', 'web', 'ios'],
                'player_skip': ['webpage'],
                'html5_player': None,
            }
        },
        'retries': 15,
        'fragment_retries': 15,
        'skip_unavailable_fragments': True,
        'socket_timeout': 30,
        'postprocessors': postprocessors,
        'progress_hooks': [progress_hook],
        'quiet': False,
        'no_warnings': True,
    }
    
    return opts

# --- PIPELINE PRINCIPAL DE DESCARGA ---
def descargar_musica(url, calidad_solicitada=None, es_compartido=False, metadata_oficial=None):
    global hubo_descarga
    hubo_descarga = False
    
    base_path = Path(os.path.expanduser(cfg.get("music_directory", obtener_directorio_musica_defecto())))
    base_path.mkdir(parents=True, exist_ok=True)
    
    print(f"\n{Colores.HEADER}{Colores.BOLD}=== YouTube Music Downloader ==={Colores.ENDC}")
    print(f"{Colores.BLUE}🔗 Enlace: {url}{Colores.ENDC}")
    
    # 1. Determinar calidad
    calidad_clave = calidad_solicitada or cfg.get("default_quality", "native")
    if es_compartido:
        segundos = cfg.get("countdown_seconds", 4)
        calidad_clave = cuenta_regresiva(segundos, calidad_clave)
    
    calidad_info = CALIDADES.get(calidad_clave, CALIDADES['native'])
    print(f"{Colores.CYAN}🎧 Modo de audio: {Colores.BOLD}{calidad_info['nombre']}{Colores.ENDC}")
    
    # 2. Análisis preliminar de la URL
    print(f"{Colores.WARNING}Analizando fuente y metadatos...{Colores.ENDC}")
    
    url_descarga = url
    metadata_oficial = dict(metadata_oficial) if metadata_oficial else {}
    es_playlist = False
    usar_sponsorblock = False
    
    ydl_check_opts = {
        'quiet': True,
        'extract_flat': True,
        'cookiefile': str(RUTA_COOKIES) if RUTA_COOKIES.exists() else None
    }
    
    try:
        with yt_dlp.YoutubeDL(ydl_check_opts) as ydl:
            info_pre = ydl.extract_info(url, download=False)
            if not info_pre:
                print(f"{Colores.FAIL}No se pudo obtener información del enlace.{Colores.ENDC}")
                return False
            
            es_playlist = 'entries' in info_pre
            titulo_pre = info_pre.get('title') or ''
            duracion_video = info_pre.get('duration') or 0
    except Exception as e:
        print(f"{Colores.FAIL}Error al consultar información: {e}{Colores.ENDC}")
        return False
    
    # 3. Detección Inteligente de YouTube Music ("YT Music First")
    if not es_playlist and cfg.get("prefer_ytmusic", True):
        # Si ya contamos con metadatos oficiales (ej. provistos por la app / iTunes)
        if metadata_oficial and metadata_oficial.get('title') and metadata_oficial.get('artist'):
            print(f"{Colores.GREEN}✔ Metadatos oficiales integrados:{Colores.ENDC} {metadata_oficial['artist']} - {metadata_oficial['title']}")
            if not ('watch?v=' in url_descarga or 'youtu.be/' in url_descarga):
                pista_ytm = resolver_ytmusic(metadata_oficial['artist'], metadata_oficial['title'], metadata_oficial.get('duration'))
                if pista_ytm and pista_ytm.get('url'):
                    print(f"{Colores.GREEN}🎯 Pista oficial de YouTube Music localizada: {Colores.BOLD}{pista_ytm.get('title')}{Colores.ENDC}")
                    url_descarga = pista_ytm.get('url')
        # Si la URL ya es de music.youtube.com, ya es la versión de estudio
        elif 'music.youtube.com' in url:
            print(f"{Colores.GREEN}✔ Enlace nativo de YouTube Music detectado.{Colores.ENDC}")
            # Si no hay metadatos oficiales aún, intentar obtener carátula HD de iTunes para la pista
            if not metadata_oficial:
                art_detectado, tit_detectado = limpiar_titulo(titulo_pre)
                query_busqueda = f"{art_detectado or ''} {tit_detectado}".strip()
                datos_itunes = obtener_duracion_oficial(query_busqueda) if query_busqueda else None
                if datos_itunes:
                    metadata_oficial = datos_itunes
        else:
            # Es un video de YouTube estándar. Buscar artista/título y duración oficial.
            art_detectado, tit_detectado = limpiar_titulo(titulo_pre)
            query_busqueda = f"{art_detectado or ''} {tit_detectado}".strip()
            
            print(f"🔍 Verificando base de datos oficial para: {Colores.BOLD}{query_busqueda}{Colores.ENDC}")
            datos_oficiales = obtener_duracion_oficial(query_busqueda) if cfg.get("verify_duration", True) else None
            
            if datos_oficiales:
                dur_oficial = datos_oficiales['duration']
                mins = int(dur_oficial // 60)
                segs = int(dur_oficial % 60)
                print(f"{Colores.GREEN}✔ Tema oficial encontrado:{Colores.ENDC} {datos_oficiales['artist']} - {datos_oficiales['title']}")
                print(f"  ⏱ Duración oficial de estudio: {mins}:{segs:02d} ({dur_oficial:.1f}s)")
                if duracion_video > 0:
                    diff = abs(duracion_video - dur_oficial)
                    if diff > 10:
                        print(f"  {Colores.WARNING}⚠ El video compartido dura {duracion_video}s (diferencia de {diff:.1f}s por intro/sketches){Colores.ENDC}")
                
                metadata_oficial = datos_oficiales
                
                # Buscar la pista oficial de estudio en YouTube Music
                pista_ytm = resolver_ytmusic(datos_oficiales['artist'], datos_oficiales['title'], dur_oficial)
                if pista_ytm and pista_ytm.get('url'):
                    print(f"{Colores.GREEN}🎯 Pista oficial de YouTube Music localizada: {Colores.BOLD}{pista_ytm.get('title')}{Colores.ENDC}")
                    url_descarga = pista_ytm.get('url')
                else:
                    usar_sponsorblock = True
                    print(f"{Colores.WARNING}No se encontró pista Topic en YT Music. Usando SponsorBlock para recortar partes no musicales.{Colores.ENDC}")
            else:
                usar_sponsorblock = True
    
    # 4. Configurar plantilla y ejecutar descarga
    template_salida = os.path.join(str(base_path), '%(custom_dir)s/%(custom_filename)s.%(ext)s')
    ydl_opts = construir_ydl_opts(calidad_clave, template_salida, usar_sponsorblock=usar_sponsorblock)
    
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.add_post_processor(
                MetadataPreProcessor(
                    es_playlist=es_playlist,
                    playlist_title=titulo_pre if es_playlist else None,
                    metadata_oficial=metadata_oficial
                ),
                when='pre_process'
            )
            ydl.add_post_processor(MediaScanPostProcessor(), when='post_process')
            
            print(f"\n{Colores.WARNING}Iniciando descarga...{Colores.ENDC}")
            ydl.download([url_descarga])
            
        if hubo_descarga:
            print(f"\n{Colores.GREEN}{Colores.BOLD}✔ ¡Descarga y procesamiento completados!{Colores.ENDC}")
            print(f"📂 Guardado en: {base_path}")
            if cfg.get("notifications", True):
                enviar_notificacion("Música Descargada", f"Se descargó en calidad {calidad_info['nombre'].split()[0]}")
        else:
            print(f"\n{Colores.WARNING}Información: No se descargaron pistas nuevas (ya estaban en el historial).{Colores.ENDC}")
            print(f"{Colores.DIM}Para re-descargar, elimina la canción desde el Gestor de Historial.{Colores.ENDC}")
            
        return True
        
    except yt_dlp.utils.DownloadError as e:
        if "Sign in to confirm" in str(e) or "bot" in str(e).lower() or "429" in str(e):
            print(f"\n{Colores.FAIL}Error de Autenticación / 429 de YouTube{Colores.ENDC}")
            print(f"{Colores.WARNING}YouTube ha solicitado cookies de inicio de sesión.{Colores.ENDC}")
            if menu_cookies_prompt():
                return descargar_musica(url, calidad_solicitada, es_compartido=False)
        else:
            print(f"\n{Colores.FAIL}Error durante la descarga: {e}{Colores.ENDC}")
        return False
    except Exception as e:
        print(f"\n{Colores.FAIL}Error imprevisto: {e}{Colores.ENDC}")
        return False
    finally:
        if es_compartido:
            time.sleep(1.5)

# --- MENÚ DE COOKIES ---
def menu_cookies_prompt():
    print(f"\n{Colores.CYAN}¿Deseas importar cookies ahora? (s/n): {Colores.ENDC}", end="")
    op = input().strip().lower()
    if op == 's':
        return exportar_cookies()
    return False

def exportar_cookies():
    print(f"\n{Colores.HEADER}=== Importar Cookies de YouTube ==={Colores.ENDC}")
    print(f"{Colores.BLUE}Pasos para exportar tus cookies:{Colores.ENDC}")
    print("1. En tu navegador, ve a https://www.youtube.com y asegúrate de haber iniciado sesión.")
    print("2. Usa una extensión como 'Get cookies.txt LOCALLY' (Chrome) o 'cookies.txt' (Firefox).")
    print("3. Exporta las cookies en formato Netscape.")
    print(f"\n{Colores.CYAN}Pega el contenido del archivo de cookies a continuación:{Colores.ENDC}")
    print("(Presiona Ctrl+D en Linux/Termux cuando termines)")
    print("-" * 60)
    
    lineas = []
    try:
        while True:
            linea = input()
            lineas.append(linea)
    except EOFError:
        pass
    
    contenido = "\n".join(lineas).strip()
    if not contenido:
        print(f"{Colores.FAIL}No se ingresó ningún contenido.{Colores.ENDC}")
        return False
    
    try:
        with open(RUTA_COOKIES, 'w', encoding='utf-8') as f:
            f.write(contenido)
        print(f"{Colores.GREEN}✔ Cookies guardadas con éxito en {RUTA_COOKIES}{Colores.ENDC}")
        return True
    except Exception as e:
        print(f"{Colores.FAIL}Error guardando cookies: {e}{Colores.ENDC}")
        return False

# --- BÚSQUEDA DIRECTA EN YOUTUBE MUSIC ---
def menu_buscar_cancion():
    print(f"\n{Colores.HEADER}{Colores.BOLD}=== Búsqueda en YouTube Music ==={Colores.ENDC}")
    q = input(f"{Colores.BLUE}Escribe el nombre de la canción o artista: {Colores.ENDC}").strip()
    if not q:
        return
    
    print(f"\n{Colores.WARNING}Buscando temas en YouTube Music...{Colores.ENDC}")
    search_url = f"https://music.youtube.com/search?q={urllib.parse.quote(q)}"
    ydl_opts = {
        'quiet': True,
        'extract_flat': True,
        'ignoreerrors': True,
        'cookiefile': str(RUTA_COOKIES) if RUTA_COOKIES.exists() else None
    }
    
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(search_url, download=False)
        resultados = []
        for e in info.get('entries') or []:
            if e and e.get('url') and 'watch?v=' in e.get('url'):
                resultados.append(e)
                if len(resultados) >= 5:
                    break
    
    if not resultados:
        print(f"{Colores.FAIL}No se encontraron resultados en YouTube Music.{Colores.ENDC}")
        return
    
    print(f"\n{Colores.GREEN}Resultados encontrados:{Colores.ENDC}")
    for idx, r in enumerate(resultados, 1):
        dur = r.get('duration')
        dur_str = f"{int(dur // 60)}:{int(dur % 60):02d}" if dur else "N/A"
        print(f"{Colores.CYAN}[{idx}]{Colores.ENDC} {r.get('title')} {Colores.DIM}({dur_str}){Colores.ENDC}")
    
    elegido = input(f"\n{Colores.BLUE}Selecciona un tema para descargar (1-{len(resultados)}) [0 para cancelar]: {Colores.ENDC}").strip()
    if elegido.isdigit() and 1 <= int(elegido) <= len(resultados):
        url_tema = resultados[int(elegido) - 1].get('url')
        descargar_musica(url_tema, es_compartido=False)

# --- GESTOR DE HISTORIAL ---
def menu_gestionar_historial():
    print(f"\n{Colores.HEADER}{Colores.BOLD}=== Gestor de Historial de Descargas ==={Colores.ENDC}")
    if not RUTA_HISTORIAL.exists():
        print(f"{Colores.WARNING}El historial está vacío.{Colores.ENDC}")
        return
    
    with open(RUTA_HISTORIAL, 'r', encoding='utf-8') as f:
        lineas = [l.strip() for l in f.readlines() if l.strip()]
    
    total = len(lineas)
    print(f"📊 Canciones registradas en el historial: {Colores.BOLD}{total}{Colores.ENDC}")
    print(f"\n{Colores.CYAN}[1]{Colores.ENDC} Ver los últimos 15 IDs descargados")
    print(f"{Colores.CYAN}[2]{Colores.ENDC} Buscar un ID en el historial")
    print(f"{Colores.CYAN}[3]{Colores.ENDC} Eliminar un ID específico (para re-descargar)")
    print(f"{Colores.CYAN}[4]{Colores.ENDC} Vaciar todo el historial")
    print(f"{Colores.CYAN}[0]{Colores.ENDC} Volver")
    
    op = input(f"\n{Colores.BLUE}Opción: {Colores.ENDC}").strip()
    if op == '1':
        print(f"\n{Colores.GREEN}Últimas descargas:{Colores.ENDC}")
        for l in lineas[-15:]:
            print(f"  • {l}")
    elif op == '2':
        termino = input("Escribe el ID a buscar: ").strip()
        coincidencias = [l for l in lineas if termino in l]
        print(f"Encontrados: {len(coincidencias)}")
        for c in coincidencias:
            print(f"  • {c}")
    elif op == '3':
        termino = input("Escribe el ID a eliminar del historial: ").strip()
        nuevas = [l for l in lineas if termino not in l]
        if len(nuevas) < len(lineas):
            with open(RUTA_HISTORIAL, 'w', encoding='utf-8') as f:
                f.write("\n".join(nuevas) + "\n")
            print(f"{Colores.GREEN}✔ ID eliminado. Ahora podrás re-descargar esa canción.{Colores.ENDC}")
        else:
            print(f"{Colores.WARNING}No se encontró ese ID en el historial.{Colores.ENDC}")
    elif op == '4':
        confirm = input(f"{Colores.FAIL}¿Estás seguro de vaciar el historial? (s/n): {Colores.ENDC}").strip().lower()
        if confirm == 's':
            RUTA_HISTORIAL.unlink(missing_ok=True)
            print(f"{Colores.GREEN}✔ Historial vaciado por completo.{Colores.ENDC}")

# --- MENÚ DE CONFIGURACIÓN ---
def menu_configuracion():
    while True:
        print(f"\n{Colores.HEADER}{Colores.BOLD}=== Configuración del Downloader ==={Colores.ENDC}")
        cal_actual = cfg.get("default_quality", "native")
        print(f"{Colores.CYAN}[1]{Colores.ENDC} Calidad por defecto: {Colores.BOLD}{CALIDADES[cal_actual]['nombre']}{Colores.ENDC}")
        print(f"{Colores.CYAN}[2]{Colores.ENDC} Cuenta regresiva al compartir: {Colores.BOLD}{cfg.get('countdown_seconds')}s{Colores.ENDC}")
        print(f"{Colores.CYAN}[3]{Colores.ENDC} Prioridad YouTube Music: {Colores.BOLD}{'Activado' if cfg.get('prefer_ytmusic') else 'Desactivado'}{Colores.ENDC}")
        print(f"{Colores.CYAN}[4]{Colores.ENDC} Verificar duración oficial (iTunes): {Colores.BOLD}{'Activado' if cfg.get('verify_duration') else 'Desactivado'}{Colores.ENDC}")
        print(f"{Colores.CYAN}[5]{Colores.ENDC} SponsorBlock (recortar partes no musicales): {Colores.BOLD}{'Activado' if cfg.get('sponsorblock_offtopic') else 'Desactivado'}{Colores.ENDC}")
        print(f"{Colores.CYAN}[6]{Colores.ENDC} Carpeta de destino: {Colores.BOLD}{cfg.get('music_directory')}{Colores.ENDC}")
        print(f"{Colores.CYAN}[7]{Colores.ENDC} Notificaciones Android: {Colores.BOLD}{'Activado' if cfg.get('notifications') else 'Desactivado'}{Colores.ENDC}")
        print(f"{Colores.CYAN}[0]{Colores.ENDC} Volver al menú principal")
        
        op = input(f"\n{Colores.BLUE}Selecciona una opción para modificar: {Colores.ENDC}").strip()
        if op == '1':
            nueva = menu_seleccion_calidad(cal_actual)
            cfg.set("default_quality", nueva)
        elif op == '2':
            seg = input("Introduce los segundos de espera al compartir (0 para inmediato): ").strip()
            if seg.isdigit():
                cfg.set("countdown_seconds", int(seg))
        elif op == '3':
            cfg.set("prefer_ytmusic", not cfg.get("prefer_ytmusic"))
        elif op == '4':
            cfg.set("verify_duration", not cfg.get("verify_duration"))
        elif op == '5':
            cfg.set("sponsorblock_offtopic", not cfg.get("sponsorblock_offtopic"))
        elif op == '6':
            nueva_ruta = input("Introduce la ruta absoluta para la música: ").strip()
            if nueva_ruta:
                cfg.set("music_directory", nueva_ruta)
        elif op == '7':
            cfg.set("notifications", not cfg.get("notifications"))
        elif op == '0':
            break

# --- MENÚ PRINCIPAL INTERACTIVO ---
def menu_principal():
    while True:
        print(f"\n{Colores.HEADER}{Colores.BOLD}╔═══════════════════════════════════════════════════╗{Colores.ENDC}")
        print(f"{Colores.HEADER}{Colores.BOLD}║       🎵 Termux YouTube Music Downloader Pro      ║{Colores.ENDC}")
        print(f"{Colores.HEADER}{Colores.BOLD}╚═══════════════════════════════════════════════════╝{Colores.ENDC}")
        print(f"{Colores.CYAN}[1]{Colores.ENDC} Descargar por enlace (Canción o Playlist)")
        print(f"{Colores.CYAN}[2]{Colores.ENDC} Buscar canción en YouTube Music")
        print(f"{Colores.CYAN}[3]{Colores.ENDC} Gestión de Historial de descargas")
        print(f"{Colores.CYAN}[4]{Colores.ENDC} Configuración (Calidad, Carpetas, Filtros)")
        print(f"{Colores.CYAN}[5]{Colores.ENDC} Gestión de Cookies de YouTube")
        print(f"{Colores.CYAN}[6]{Colores.ENDC} Actualizar yt-dlp y dependencias")
        print(f"{Colores.CYAN}[0]{Colores.ENDC} Salir")
        
        op = input(f"\n{Colores.BLUE}Selecciona una opción (0-6): {Colores.ENDC}").strip()
        
        if op == '1':
            url = input(f"\n{Colores.BLUE}Pega el enlace de YouTube o YouTube Music: {Colores.ENDC}").strip()
            if url:
                descargar_musica(url, es_compartido=False)
        elif op == '2':
            menu_buscar_cancion()
        elif op == '3':
            menu_gestionar_historial()
        elif op == '4':
            menu_configuracion()
        elif op == '5':
            print(f"\nEstado actual de cookies: {'✔ Activas' if RUTA_COOKIES.exists() else '❌ No configuradas'}")
            print("[1] Importar / Pegar nuevas cookies")
            print("[2] Eliminar cookies guardadas")
            sub = input("Opción: ").strip()
            if sub == '1':
                exportar_cookies()
            elif sub == '2':
                RUTA_COOKIES.unlink(missing_ok=True)
                print(f"{Colores.GREEN}Cookies eliminadas.{Colores.ENDC}")
        elif op == '6':
            instalar_dependencias()
            print(f"{Colores.GREEN}✔ Dependencias actualizadas.{Colores.ENDC}")
        elif op == '0':
            print(f"\n{Colores.CYAN}¡Hasta pronto! Disfruta tu música en alta calidad.{Colores.ENDC}")
            break

# --- ENTRY POINT ---
if __name__ == "__main__":
    if len(sys.argv) > 1:
        enlace = sys.argv[1]
        descargar_musica(enlace, es_compartido=True)
    else:
        menu_principal()
