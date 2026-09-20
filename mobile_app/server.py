#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mobile App Backend Server for Termux YouTube Music Pro
Servidor HTTP multi-hilo en Python estándar (sin dependencias de pip)
que sirve la PWA móvil y expone endpoints REST y SSE para descargas y reproducción.
"""

import sys
import os
import json
import urllib.parse
import urllib.request
import threading
import queue
import mimetypes
from pathlib import Path
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler

# Importar el motor de yt_downloader.py
sys.path.insert(0, str(Path(__file__).parent.parent))
from yt_downloader import (
    cfg, CALIDADES, RUTA_HISTORIAL, RUTA_COOKIES,
    limpiar_titulo, sanitizar_nombre, obtener_duracion_oficial,
    resolver_ytmusic, descargar_musica, progress_hook
)

# Cola global para eventos de progreso en tiempo real (SSE)
progress_listeners = []
progress_lock = threading.Lock()

def broadcast_sse(event_data):
    """Envía un evento a todos los clientes SSE conectados"""
    msg = f"data: {json.dumps(event_data)}\n\n"
    with progress_lock:
        for q in list(progress_listeners):
            try:
                q.put_nowait(msg)
            except Exception:
                progress_listeners.remove(q)

# Gancho de progreso conectado al backend móvil
def mobile_progress_hook(d):
    # Ejecutar también el hook original de consola
    progress_hook(d)
    
    if d['status'] == 'downloading':
        percent = d.get('_percent_str', '0%').replace('%', '').strip()
        speed = d.get('_speed_str', 'N/A')
        eta = d.get('_eta_str', 'N/A')
        filename = os.path.basename(d.get('filename', ''))
        broadcast_sse({
            'status': 'downloading',
            'percent': percent,
            'speed': speed,
            'eta': eta,
            'title': filename
        })
    elif d['status'] == 'finished':
        broadcast_sse({
            'status': 'processing',
            'message': 'Descarga completa. Procesando carátula HD y metadatos...'
        })

class MobileAppHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        # Directorio de los archivos compilados del frontend (dist)
        dist_dir = Path(__file__).parent / "dist"
        super().__init__(*args, directory=str(dist_dir) if dist_dir.exists() else str(Path(__file__).parent), **kwargs)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        # 1. API: Server-Sent Events (SSE) para progreso en tiempo real
        if path == '/api/progress':
            self.handle_sse()
            return

        # 2. API: Configuración
        if path == '/api/config':
            self.send_json(cfg.config)
            return

        # 3. API: Historial
        if path == '/api/history':
            history = []
            if RUTA_HISTORIAL.exists():
                with open(RUTA_HISTORIAL, 'r', encoding='utf-8') as f:
                    history = [l.strip() for l in f if l.strip()]
            self.send_json({'history': history})
            return

        # 4. API: Biblioteca de música descargada
        if path == '/api/library':
            self.handle_library()
            return

        # 5. API: Streaming de audio con HTTP Range para el reproductor
        if path == '/api/stream':
            self.handle_stream(query)
            return

        # 6. API: Búsqueda en YouTube Music & iTunes
        if path == '/api/search':
            self.handle_search(query)
            return

        # 7. Servir Frontend estático (con fallback SPA a index.html)
        dist_dir = Path(__file__).parent / "dist"
        file_path = dist_dir / path.lstrip('/')
        if not file_path.exists() or file_path.is_dir():
            index_path = dist_dir / "index.html"
            if index_path.exists():
                self.serve_file(index_path, "text/html")
                return

        super().do_GET()

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Range, Authorization')
        self.send_header('Access-Control-Max-Age', '86400')
        self.end_headers()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        # 1. API: Iniciar Descarga
        if path == '/api/download':
            self.handle_download_post()
            return

        # 2. API: Guardar Configuración
        if path == '/api/config':
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length).decode('utf-8')
            try:
                new_cfg = json.loads(body)
                for k, v in new_cfg.items():
                    cfg.set(k, v)
                self.send_json({'success': True, 'config': cfg.config})
            except Exception as e:
                self.send_json({'success': False, 'error': str(e)}, status=400)
            return

        self.send_error(404, "Endpoint not found")

    def do_DELETE(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        # Eliminar entrada del historial: /api/history/<item_id>
        if path.startswith('/api/history/'):
            item_id = urllib.parse.unquote(path[len('/api/history/'):])
            if RUTA_HISTORIAL.exists():
                with open(RUTA_HISTORIAL, 'r', encoding='utf-8') as f:
                    lines = [l.strip() for l in f if l.strip()]
                new_lines = [l for l in lines if item_id not in l]
                with open(RUTA_HISTORIAL, 'w', encoding='utf-8') as f:
                    f.write("\n".join(new_lines) + ("\n" if new_lines else ""))
                self.send_json({'success': True})
                return
            self.send_json({'success': False, 'error': 'No history file'}, status=404)
            return

        self.send_error(404)

    # --- HANDLERS ESPECÍFICOS ---

    def handle_sse(self):
        """Mantiene abierta una conexión SSE para enviar progreso en streaming"""
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'keep-alive')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

        client_queue = queue.Queue()
        with progress_lock:
            progress_listeners.append(client_queue)

        try:
            while True:
                try:
                    msg = client_queue.get(timeout=20)
                    self.wfile.write(msg.encode('utf-8'))
                    self.wfile.flush()
                except queue.Empty:
                    # Keep-alive heartbeat
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
        except Exception:
            pass
        finally:
            with progress_lock:
                if client_queue in progress_listeners:
                    progress_listeners.remove(client_queue)

    def handle_search(self, query):
        q = query.get('q', [''])[0].strip()
        if not q:
            self.send_json({'results': []})
            return

        results = []

        # 1. Consulta primero a la base de datos oficial de iTunes (milisegundos, portada 1200x1200px)
        itunes_data = obtener_duracion_oficial(q)
        if itunes_data:
            dur = itunes_data['duration']
            dur_str = f"{int(dur // 60)}:{int(dur % 60):02d}"
            results.append({
                'title': itunes_data['title'],
                'artist': itunes_data['artist'],
                'album': itunes_data['album'],
                'duration': dur,
                'duration_str': dur_str,
                'cover': itunes_data['cover_url'],
                'url': f"https://music.youtube.com/search?q={urllib.parse.quote(itunes_data['artist'] + ' ' + itunes_data['title'])}"
            })

        # 2. Búsqueda de pistas en YouTube Music (Topic tracks / Provided to YouTube)
        try:
            import yt_dlp
            search_url = f"https://music.youtube.com/search?q={urllib.parse.quote(q)}"
            ydl_opts = {
                'quiet': True,
                'extract_flat': True,
                'ignoreerrors': True,
                'cookiefile': str(RUTA_COOKIES) if RUTA_COOKIES.exists() else None
            }
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(search_url, download=False)
                for e in (info.get('entries') or []):
                    if not e:
                        continue
                    url = e.get('url') or e.get('webpage_url')
                    if url and 'watch?v=' in url:
                        dur = e.get('duration')
                        dur_str = f"{int(dur // 60)}:{int(dur % 60):02d}" if dur else "Oficial"
                        title = e.get('title')
                        uploader = e.get('uploader') or e.get('channel') or 'YouTube Music'
                        # Evitar duplicado exacto del primer resultado
                        if not any(r['title'].lower() == title.lower() for r in results):
                            results.append({
                                'title': title,
                                'artist': uploader,
                                'album': 'Sencillo / Álbum',
                                'duration': dur or 0,
                                'duration_str': dur_str,
                                'cover': e.get('thumbnail') or 'https://music.youtube.com/img/favicon_144.png',
                                'url': url
                            })
                    if len(results) >= 8:
                        break
        except Exception as e:
            print(f"Error en búsqueda de backend: {e}")

        self.send_json({'results': results})

    def handle_download_post(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode('utf-8')
        try:
            data = json.loads(body)
            url = data.get('url', '').strip()
            artist = data.get('artist', '').strip()
            title = data.get('title', '').strip()
            album = data.get('album', '').strip()
            duration = float(data.get('duration', 0) or 0)
            cover = data.get('cover', '').strip()
            calidad = data.get('quality', cfg.get('default_quality', 'native'))
            
            if not url and not (artist and title):
                self.send_json({'success': False, 'message': 'URL o canción requeridos'}, status=400)
                return

            metadata_oficial = {}
            if artist or title:
                metadata_oficial = {
                    'artist': artist,
                    'title': title,
                    'album': album or 'Single',
                    'duration': duration,
                    'cover_url': cover
                }

            # Ejecutar la descarga en un hilo secundario sin bloquear el servidor
            def worker():
                try:
                    target_url = url
                    # Si la URL no es un video directo (watch?v= o youtu.be/), resolver a la pista oficial en YouTube Music
                    if not ('watch?v=' in target_url or 'youtu.be/' in target_url):
                        broadcast_sse({
                            'status': 'downloading',
                            'percent': '0',
                            'speed': 'Localizando...',
                            'eta': '--',
                            'title': f'Localizando máster oficial: {artist} - {title}' if (artist and title) else 'Buscando en YouTube Music...'
                        })
                        pista_ytm = None
                        if artist and title:
                            pista_ytm = resolver_ytmusic(artist, title, dur_esperada=duration)
                        
                        if pista_ytm and pista_ytm.get('url'):
                            target_url = pista_ytm.get('url')
                        elif artist and title:
                            target_url = f"ytsearch1:{artist} {title}"
                        elif target_url.startswith('https://music.youtube.com/search?'):
                            parsed_u = urllib.parse.urlparse(target_url)
                            q_val = urllib.parse.parse_qs(parsed_u.query).get('q', [''])[0]
                            target_url = f"ytsearch1:{q_val}" if q_val else target_url

                    broadcast_sse({
                        'status': 'downloading',
                        'percent': '0',
                        'speed': 'Conectando...',
                        'eta': '--',
                        'title': f'Descargando audio: {artist} - {title}' if (artist and title) else 'Iniciando descarga...'
                    })

                    # Reemplazamos temporalmente el hook por el mobile hook
                    import yt_downloader
                    orig_hook = yt_downloader.progress_hook
                    yt_downloader.progress_hook = mobile_progress_hook
                    
                    exito = yt_downloader.descargar_musica(
                        target_url,
                        calidad_solicitada=calidad,
                        es_compartido=False,
                        metadata_oficial=metadata_oficial
                    )
                    
                    yt_downloader.progress_hook = orig_hook
                    
                    if exito:
                        broadcast_sse({
                            'status': 'completed',
                            'title': f'{artist} - {title}' if (artist and title) else 'Canción descargada con éxito'
                        })
                    else:
                        broadcast_sse({
                            'status': 'error',
                            'message': 'No se pudo completar la descarga. Revisa el enlace o la conexión.'
                        })
                except Exception as ex:
                    broadcast_sse({
                        'status': 'error',
                        'message': str(ex)
                    })

            t = threading.Thread(target=worker, daemon=True)
            t.start()

            self.send_json({'success': True, 'message': 'Descarga iniciada en segundo plano'})
        except Exception as e:
            self.send_json({'success': False, 'message': str(e)}, status=500)

    def handle_library(self):
        lib_dir = Path(os.path.expanduser(cfg.get('music_directory', '~/Music/Biblioteca')))
        tracks = []
        if lib_dir.exists():
            for root, _, files in os.walk(lib_dir):
                for f in sorted(files):
                    if f.endswith(('.opus', '.m4a', '.mp3', '.flac', '.ogg', '.wav')):
                        full_p = Path(root) / f
                        size_mb = round(full_p.stat().st_size / (1024 * 1024), 2)
                        rel_p = full_p.relative_to(lib_dir)
                        parts = rel_p.parts
                        artist = parts[0] if len(parts) > 1 else 'Desconocido'
                        folder = parts[1] if len(parts) > 2 else (parts[0] if len(parts) > 1 else 'Biblioteca')
                        tracks.append({
                            'title': full_p.stem,
                            'artist': artist,
                            'folder': folder,
                            'path': str(full_p),
                            'size_mb': size_mb,
                            'ext': full_p.suffix
                        })
        self.send_json({'tracks': tracks})

    def handle_stream(self, query):
        """Soporta streaming de archivos de audio locales con HTTP 206 Partial Content (Range)"""
        filepath_str = query.get('path', [''])[0]
        if not filepath_str or not os.path.exists(filepath_str):
            self.send_error(404, "Audio file not found")
            return

        file_path = Path(filepath_str)
        file_size = file_path.stat().st_size
        mime_type, _ = mimetypes.guess_type(filepath_str)
        if not mime_type:
            mime_type = 'audio/mpeg'

        range_header = self.headers.get('Range', None)
        if not range_header:
            # Respuesta completa 200 OK
            self.send_response(200)
            self.send_header('Content-Type', mime_type)
            self.send_header('Content-Length', str(file_size))
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS')
            self.send_header('Access-Control-Allow-Headers', 'Range, Content-Type')
            self.end_headers()
            with open(file_path, 'rb') as f:
                self.copyfile(f, self.wfile)
            return

        # Respuesta parcial 206 Partial Content
        try:
            bytes_range = range_header.strip().replace('bytes=', '')
            start_str, end_str = bytes_range.split('-')
            start = int(start_str) if start_str else 0
            end = int(end_str) if end_str else file_size - 1
            if end >= file_size:
                end = file_size - 1
            length = end - start + 1

            self.send_response(206)
            self.send_header('Content-Type', mime_type)
            self.send_header('Content-Range', f"bytes {start}-{end}/{file_size}")
            self.send_header('Content-Length', str(length))
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS')
            self.send_header('Access-Control-Allow-Headers', 'Range, Content-Type')
            self.end_headers()

            with open(file_path, 'rb') as f:
                f.seek(start)
                remaining = length
                chunk_size = 64 * 1024
                while remaining > 0:
                    chunk = f.read(min(remaining, chunk_size))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except Exception:
            pass

    def send_json(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Range, Authorization')
        self.end_headers()
        self.wfile.write(body)

    def serve_file(self, path, mime):
        try:
            with open(path, 'rb') as f:
                content = f.read()
            self.send_response(200)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except Exception:
            self.send_error(404)

def run_server(port=8000, host="0.0.0.0"):
    server = ThreadingHTTPServer((host, port), MobileAppHandler)
    print(f"\n🚀 Servidor de la App Móvil iniciado en http://localhost:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServidor detenido.")
        server.server_close()

if __name__ == "__main__":
    run_server()
