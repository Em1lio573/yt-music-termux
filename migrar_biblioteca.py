#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Script de Migración Masiva de Biblioteca a YouTube Music (Opus Nativo 160k)
Descarga las canciones del catálogo en su versión oficial de estudio de YouTube Music,
incrusta carátulas oficiales en 1200x1200px, etiquetas ID3 y organiza todo por Artista Principal
en el teléfono (/sdcard/Music/Biblioteca/<Artista Principal>/<Álbum>/<Título>.opus).
"""

import os
import sys
import json
import time
import re
import urllib.request
import urllib.parse
import subprocess
import base64
from pathlib import Path

from mutagen.oggopus import OggOpus
from mutagen.flac import Picture

ADB_BIN = "/home/emi/Android/Sdk/platform-tools/adb"
CATALOGO_FILE = "catalogo_368_canciones.json"
ARCHIVE_FILE = "migracion_archive.txt"
STAGING_DIR = "/tmp/ytm_staging"

def sanitize(val):
    if not val:
        return "Desconocido"
    # Elimina caracteres no permitidos en nombres de archivos/carpetas
    clean = re.sub(r'[\\/*?:"<>|]', '', str(val))
    clean = re.sub(r'\s+', ' ', clean).strip(' .')
    return clean or "Desconocido"

def load_archive():
    if not os.path.exists(ARCHIVE_FILE):
        return set()
    with open(ARCHIVE_FILE, 'r', encoding='utf-8') as f:
        return set(line.strip() for line in f if line.strip())

def save_to_archive(item_id):
    with open(ARCHIVE_FILE, 'a', encoding='utf-8') as f:
        f.write(f"{item_id}\n")

def get_itunes_cover(artist, title):
    try:
        q = f"{artist} {title}"
        url = f"https://itunes.apple.com/search?term={urllib.parse.quote(q)}&media=music&entity=song&limit=1"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            if data.get('resultCount', 0) > 0:
                art = data['results'][0].get('artworkUrl100', '')
                if art:
                    return art.replace('100x100bb', '1200x1200bb')
    except Exception:
        pass
    return None

def resolve_ytm_url(artist, title):
    """Busca en YouTube Music la pista oficial (Provided to YouTube / Topic track)"""
    search_url = f"https://music.youtube.com/search?q={urllib.parse.quote(artist + ' ' + title)}"
    cmd = [
        'yt-dlp',
        '--js-runtimes', 'node',
        '--flat-playlist',
        '--dump-single-json',
        search_url
    ]
    try:
        res = subprocess.check_output(cmd, stderr=subprocess.DEVNULL, timeout=20).decode('utf-8')
        data = json.loads(res)
        for e in data.get('entries', []):
            if e and e.get('url') and 'watch?v=' in e.get('url'):
                return e.get('url')
    except Exception:
        pass
    # Fallback a búsqueda directa
    return f"ytsearch1:{artist} {title} audio"

def process_track(item, index, total):
    item_id = str(item['id'])
    title = item['title']
    artist = item['artist']
    main_artist = item['main_artist']
    album = item.get('album') or 'Single'
    if album.lower() in ['unknown', 'desconocido', '<unknown>']:
        album = 'Single'

    clean_main_artist = sanitize(main_artist)
    clean_album = sanitize(album)
    clean_title = sanitize(title)

    print(f"\n[{index}/{total}] ({int(index/total*100)}%) 🎵 {main_artist} - {title}")
    print(f"       Álbum: {clean_album} | Colaboradores: {artist}")

    # 1. Localizar máster en YouTube Music
    ytm_url = resolve_ytm_url(main_artist, title)
    print(f"       ↳ Pista oficial: {ytm_url}")

    # 2. Descargar en staging local con formato Opus 160k
    local_dir = os.path.join(STAGING_DIR, clean_main_artist, clean_album)
    os.makedirs(local_dir, exist_ok=True)
    temp_out = os.path.join(local_dir, f"{clean_title}.%(ext)s")
    final_opus = os.path.join(local_dir, f"{clean_title}.opus")

    if os.path.exists(final_opus):
        os.remove(final_opus)

    cmd_dl = [
        'yt-dlp',
        '--js-runtimes', 'node',
        '-f', 'ba[ext=opus]/ba[ext=webm]/ba',
        '-x', '--audio-format', 'opus',
        '--no-warnings',
        '--quiet',
        '-o', temp_out,
        ytm_url
    ]

    dl_start = time.time()
    download_ok = False
    try:
        subprocess.run(cmd_dl, check=True, timeout=60)
        download_ok = True
    except Exception as e:
        print(f"       ⚠️ Aviso: fallo en enlace directo, reintentando con búsqueda alternativa...")
        fallback_query = f"ytsearch1:{main_artist} {title} audio"
        cmd_fallback = [
            'yt-dlp',
            '--js-runtimes', 'node',
            '-f', 'ba[ext=opus]/ba[ext=webm]/ba',
            '-x', '--audio-format', 'opus',
            '--no-warnings',
            '--quiet',
            '-o', temp_out,
            fallback_query
        ]
        try:
            subprocess.run(cmd_fallback, check=True, timeout=60)
            download_ok = True
        except Exception as e2:
            print(f"       ❌ Error en reintento: {e2}")
            return False

    if not os.path.exists(final_opus):
        # A veces yt-dlp puede nombrar con alguna pequeña variación si el template no resolvió exacto
        found = list(Path(local_dir).glob("*.opus"))
        if found:
            os.rename(str(found[0]), final_opus)
        else:
            print(f"       ❌ No se encontró el archivo .opus generado.")
            return False

    dl_time = round(time.time() - dl_start, 1)

    # 3. Incrustar metadatos oficiales y carátula HD 1200px
    try:
        audio = OggOpus(final_opus)
        audio['title'] = [title]
        audio['artist'] = [artist] # Todos los artistas en los tags
        audio['album'] = [album]
        audio['albumartist'] = [main_artist]
        
        cover_url = get_itunes_cover(main_artist, title)
        if cover_url:
            req = urllib.request.Request(cover_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=8) as img_resp:
                img_data = img_resp.read()
                pic = Picture()
                pic.data = img_data
                pic.type = 3
                pic.mime = 'image/jpeg'
                pic.desc = 'Cover'
                audio['metadata_block_picture'] = [base64.b64encode(pic.write()).decode('ascii')]
        audio.save()
    except Exception as ex:
        print(f"       ⚠️ Aviso en metadatos: {ex}")

    file_size_mb = round(os.path.getsize(final_opus) / (1024.0 * 1024.0), 2)

    # 4. Transferir vía ADB por USB al teléfono
    phone_dest_dir = f"/sdcard/Music/Biblioteca/{clean_main_artist}/{clean_album}"
    phone_dest_file = f"{phone_dest_dir}/{clean_title}.opus"

    try:
        # Crear directorio remoto
        subprocess.run([ADB_BIN, 'shell', f'mkdir -p "{phone_dest_dir}"'], check=True, stdout=subprocess.DEVNULL)
        # Push del archivo
        subprocess.run([ADB_BIN, 'push', final_opus, phone_dest_file], check=True, stdout=subprocess.DEVNULL)
        # Escaneo de medios en Android
        subprocess.run([ADB_BIN, 'shell', f'am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://{phone_dest_file}"'], check=True, stdout=subprocess.DEVNULL)
    except Exception as e:
        print(f"       ❌ Error enviando por ADB: {e}")
        return False

    # 5. Limpieza local del staging
    try:
        os.remove(final_opus)
    except Exception:
        pass

    # 6. Registrar en el archivo de completados
    save_to_archive(item_id)
    print(f"       ✔ Completado en {dl_time}s ({file_size_mb} MB) -> Teléfono: {clean_main_artist}/{clean_album}/{clean_title}.opus")
    return True

def main():
    if not os.path.exists(CATALOGO_FILE):
        print(f"Error: No se encontró {CATALOGO_FILE}")
        sys.exit(1)

    with open(CATALOGO_FILE, 'r', encoding='utf-8') as f:
        catalog = json.load(f)

    archive = load_archive()
    total = len(catalog)
    pendientes = [x for x in catalog if str(x['id']) not in archive]

    print("=" * 65)
    print("      MIGRACIÓN MASIVA A YOUTUBE MUSIC (OPUS NATIVO 160K)")
    print("=" * 65)
    print(f"Total canciones en catálogo: {total}")
    print(f"Ya descargadas previamente: {len(archive)}")
    print(f"Pendientes por descargar:   {len(pendientes)}")
    print("=" * 65)

    if not pendientes:
        print("¡Todas las canciones ya han sido descargadas y transferidas!")
        sys.exit(0)

    exitos = 0
    fallos = 0
    start_time = time.time()

    for item in pendientes:
        idx = item['id']
        ok = process_track(item, idx, total)
        if ok:
            exitos += 1
        else:
            fallos += 1
        # Breve pausa para evitar saturar rate limits de YouTube
        time.sleep(0.4)

    total_time = round((time.time() - start_time) / 60.0, 1)
    print("\n" + "=" * 65)
    print("           RESUMEN DE LA MIGRACIÓN")
    print("=" * 65)
    print(f"Tiempo total: {total_time} minutos")
    print(f"Descargas exitosas: {exitos}")
    print(f"Descargas fallidas: {fallos}")
    print(f"Total en tu teléfono: {len(load_archive())} / {total}")
    print("=" * 65)

if __name__ == "__main__":
    main()
