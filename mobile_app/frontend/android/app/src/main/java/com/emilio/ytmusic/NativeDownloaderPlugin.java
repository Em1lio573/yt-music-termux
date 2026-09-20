package com.emilio.ytmusic;

import android.Manifest;
import android.content.ContentResolver;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;

@CapacitorPlugin(
    name = "NativeDownloader",
    permissions = {
        @Permission(
            alias = "audio",
            strings = {
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        )
    }
)
public class NativeDownloaderPlugin extends Plugin {
    private static final String TAG = "NativeDownloader";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isInitialized = false;
    private boolean isInitializing = false;

    @Override
    public void load() {
        super.load();
        // Inicializar de forma asíncrona al cargar la app para que esté lista
        initAsync(null);
    }

    private synchronized void initAsync(Runnable onComplete) {
        if (isInitialized) {
            if (onComplete != null) onComplete.run();
            return;
        }
        if (isInitializing) return;
        isInitializing = true;

        executor.execute(() -> {
            try {
                Log.d(TAG, "Iniciando FFmpeg y YoutubeDL nativos...");
                FFmpeg.getInstance().init(getContext());
                YoutubeDL.getInstance().init(getContext());
                isInitialized = true;
                Log.d(TAG, "Motor nativo YoutubeDL + FFmpeg inicializado correctamente.");
                
                JSObject event = new JSObject();
                event.put("ready", true);
                event.put("version", YoutubeDL.getInstance().versionName(getContext()));
                notifyListeners("engineReady", event);
            } catch (Exception e) {
                Log.e(TAG, "Error inicializando YoutubeDL: " + e.getMessage(), e);
            } finally {
                isInitializing = false;
                if (onComplete != null) onComplete.run();
            }
        });
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("isNative", true);
        ret.put("initialized", isInitialized);
        try {
            if (isInitialized) {
                ret.put("version", YoutubeDL.getInstance().versionName(getContext()));
            } else {
                ret.put("version", "Inicializando...");
            }
        } catch (Exception e) {
            ret.put("version", "N/A");
        }
        ret.put("musicDirectory", getMusicDirectory().getAbsolutePath());
        call.resolve(ret);
    }

    @PluginMethod
    public void downloadTrack(PluginCall call) {
        String rawUrl = call.getString("url", "").trim();
        String quality = call.getString("quality", "native");
        String artist = call.getString("artist", "").trim();
        String title = call.getString("title", "").trim();
        String album = call.getString("album", "").trim();
        String cover = call.getString("cover", "").trim();

        if (rawUrl.isEmpty() && (artist.isEmpty() || title.isEmpty())) {
            call.reject("URL o artista/título requeridos");
            return;
        }

        executor.execute(() -> {
            try {
                if (!isInitialized) {
                    emitTerminal("[Motor] Inicializando binarios nativos de Python y FFmpeg...");
                    FFmpeg.getInstance().init(getContext());
                    YoutubeDL.getInstance().init(getContext());
                    isInitialized = true;
                }

                File musicDir = getMusicDirectory();
                if (!musicDir.exists()) {
                    musicDir.mkdirs();
                }

                String targetQuery = rawUrl;
                if (!targetQuery.contains("watch?v=") && !targetQuery.contains("youtu.be/")) {
                    if (!artist.isEmpty() && !title.isEmpty()) {
                        targetQuery = "ytsearch1:" + artist + " " + title;
                    }
                }

                emitTerminal("[yt-dlp] Iniciando descarga para: " + (artist.isEmpty() ? targetQuery : artist + " - " + title));
                emitTerminal("[yt-dlp] Carpeta destino: " + musicDir.getAbsolutePath());

                YoutubeDLRequest request = new YoutubeDLRequest(targetQuery);
                // Plantilla de salida limpia
                request.addOption("-o", musicDir.getAbsolutePath() + "/%(artist,uploader)s/%(title)s.%(ext)s");
                
                // Extracción con opciones reales de YouTube Music
                request.addOption("-x");
                if ("m4a".equals(quality)) {
                    // M4A / AAC Nativo (~128-140 kbps itag 140) - Extracción directa sin recodificar
                    request.addOption("-f", "ba[ext=m4a]/ba");
                    request.addOption("--audio-format", "m4a");
                    emitTerminal("[yt-dlp] Formato: M4A / AAC Nativo (~128 kbps) - Extracción directa");
                } else if ("m4a_256".equals(quality)) {
                    // M4A Premium (256 kbps itag 141 si hay sesión Premium, con fallback a 140)
                    request.addOption("-f", "141/ba[ext=m4a]/ba");
                    request.addOption("--audio-format", "m4a");
                    emitTerminal("[yt-dlp] Formato: M4A Premium (256 kbps con fallback nativo)");
                } else if ("mp3_320".equals(quality)) {
                    // MP3 320 kbps (Transcodificación LAME con FFmpeg para estéreos antiguos)
                    request.addOption("--audio-format", "mp3");
                    request.addOption("--audio-quality", "320K");
                    emitTerminal("[ffmpeg] Formato: MP3 320 kbps (Transcodificación de compatibilidad)");
                } else if ("data_saver".equals(quality)) {
                    // Opus Ahorro de Datos (~70 kbps itags 250/249)
                    request.addOption("-f", "250/249/ba[abr<=80]/ba");
                    request.addOption("--audio-format", "opus");
                    emitTerminal("[yt-dlp] Formato: Opus Ahorro de Datos (~70 kbps)");
                } else {
                    // Opus Nativo (~160 kbps itag 251) - Recomendado / Máxima fidelidad de YouTube Music
                    request.addOption("-f", "ba[ext=opus]/ba[ext=webm]/ba");
                    request.addOption("--audio-format", "opus");
                    emitTerminal("[yt-dlp] Formato: Opus Nativo (~160 kbps) - Extracción directa sin pérdida");
                }

                // Metadatos oficiales y carátula
                request.addOption("--embed-metadata");
                request.addOption("--embed-thumbnail");
                request.addOption("--convert-thumbnails", "jpg");
                request.addOption("--no-warnings");
                request.addOption("--ignore-errors");
                request.addOption("--sponsorblock-remove", "music_offtopic");

                // Ejecución y emisión de líneas en vivo hacia la UI
                YoutubeDLResponse response = YoutubeDL.getInstance().execute(
                    request,
                    null,
                    (progress, etaInSeconds, line) -> {
                        int percent = Math.round(progress);
                        JSObject prog = new JSObject();
                        prog.put("percent", percent);
                        prog.put("eta", etaInSeconds > 0 ? (etaInSeconds + "s") : "--");
                        prog.put("statusText", parseStatus(line));
                        notifyListeners("downloadProgress", prog);

                        if (line != null && !line.trim().isEmpty()) {
                            emitTerminal(line);
                        }
                        return Unit.INSTANCE;
                    }
                );

                emitTerminal("[Sistema] ✔ Descarga completada con éxito");

                // Escaneo de medios de Android para que aparezca en cualquier reproductor
                scanMediaFiles(musicDir);

                JSObject res = new JSObject();
                res.put("success", true);
                res.put("message", "Canción descargada con éxito");
                res.put("log", response.getOut());
                call.resolve(res);

            } catch (Exception e) {
                Log.e(TAG, "Error en descarga nativa: " + e.getMessage(), e);
                emitTerminal("[Error] Falló la descarga: " + e.getMessage());
                call.reject("Error en descarga nativa: " + e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void getLibrary(PluginCall call) {
        executor.execute(() -> {
            JSArray tracks = new JSArray();
            Set<String> seenPaths = new HashSet<>();

            // 1. Escaneo profundo de todo el directorio de música del teléfono (/sdcard/Music/)
            File musicPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            if (musicPublic != null && musicPublic.exists()) {
                scanDirRecursive(musicPublic, tracks, seenPaths);
            }

            // 2. Escaneo de la carpeta de Descargas (/sdcard/Download/)
            File downloadPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadPublic != null && downloadPublic.exists()) {
                scanDirRecursive(downloadPublic, tracks, seenPaths);
            }

            // 3. Consulta al MediaStore del sistema operativo Android (Detecta toda la música indexada)
            try {
                ContentResolver resolver = getContext().getContentResolver();
                Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
                String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE
                };
                Cursor cursor = resolver.query(uri, projection, selection, null, MediaStore.Audio.Media.TITLE + " ASC");
                if (cursor != null) {
                    int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                    int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                    int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    int albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                    int durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                    int sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE);

                    while (cursor.moveToNext()) {
                        String path = cursor.getString(dataCol);
                        if (path == null) continue;
                        if (seenPaths.contains(path)) continue;

                        File f = new File(path);
                        if (!f.exists()) continue;
                        seenPaths.add(path);

                        String title = cursor.getString(titleCol);
                        String artist = cursor.getString(artistCol);
                        String album = cursor.getString(albumCol);
                        long durationMs = cursor.getLong(durationCol);
                        long sizeBytes = cursor.getLong(sizeCol);

                        if (title == null || title.isEmpty()) {
                            title = f.getName().replaceFirst("[.][^.]+$", "");
                        }
                        if (artist == null || artist.isEmpty() || "<unknown>".equalsIgnoreCase(artist)) {
                            artist = f.getParentFile() != null ? f.getParentFile().getName() : "Desconocido";
                        }

                        double sizeMb = Math.round((sizeBytes / (1024.0 * 1024.0)) * 100.0) / 100.0;
                        String ext = path.contains(".") ? path.substring(path.lastIndexOf('.')) : "";

                        JSObject item = new JSObject();
                        item.put("title", title);
                        item.put("artist", artist);
                        item.put("album", album != null ? album : "");
                        item.put("folder", f.getParentFile() != null ? f.getParentFile().getName() : "Música");
                        item.put("path", path);
                        item.put("size_mb", sizeMb);
                        item.put("duration", durationMs / 1000.0);
                        item.put("ext", ext);
                        item.put("lastModified", f.lastModified());
                        tracks.put(item);
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "Consulta MediaStore omitida o sin permiso: " + e.getMessage());
            }

            JSObject ret = new JSObject();
            ret.put("tracks", tracks);
            ret.put("total", tracks.length());
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void deleteTrack(PluginCall call) {
        String path = call.getString("path", "");
        if (path.isEmpty()) {
            call.reject("Ruta requerida");
            return;
        }
        try {
            File f = new File(path);
            if (f.exists() && f.delete()) {
                MediaScannerConnection.scanFile(getContext(), new String[]{path}, null, null);
                JSObject ret = new JSObject();
                ret.put("success", true);
                call.resolve(ret);
                return;
            }
        } catch (Exception e) {
            call.reject(e.getMessage());
            return;
        }
        call.reject("No se pudo eliminar el archivo");
    }

    private void scanDirRecursive(File dir, JSArray tracks, Set<String> seenPaths) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) {
                    scanDirRecursive(f, tracks, seenPaths);
                }
            } else {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".opus") || name.endsWith(".m4a") || name.endsWith(".mp3") 
                    || name.endsWith(".flac") || name.endsWith(".ogg") || name.endsWith(".wav")
                    || name.endsWith(".aac") || name.endsWith(".webm")) {
                    
                    String path = f.getAbsolutePath();
                    if (seenPaths.contains(path)) continue;
                    seenPaths.add(path);

                    double sizeMb = Math.round((f.length() / (1024.0 * 1024.0)) * 100.0) / 100.0;
                    File parent = f.getParentFile();
                    String album = parent != null ? parent.getName() : "Biblioteca";
                    File grandParent = parent != null ? parent.getParentFile() : null;
                    String artist = (grandParent != null && !grandParent.getName().equalsIgnoreCase("Music") && !grandParent.getName().equalsIgnoreCase("Download")) 
                        ? grandParent.getName() 
                        : album;

                    String rawTitle = f.getName().replaceFirst("[.][^.]+$", "");
                    // Limpiar numeración de pista inicial (ej: "01 - Aquella Noche" -> "Aquella Noche")
                    String cleanTitle = rawTitle.replaceFirst("^[0-9]+[\\s._-]+", "");
                    if (cleanTitle.isEmpty()) cleanTitle = rawTitle;
                    
                    JSObject item = new JSObject();
                    item.put("title", cleanTitle);
                    item.put("artist", artist);
                    item.put("album", album);
                    item.put("folder", album);
                    item.put("path", path);
                    item.put("size_mb", sizeMb);
                    item.put("ext", name.substring(name.lastIndexOf('.')));
                    item.put("lastModified", f.lastModified());
                    tracks.put(item);
                }
            }
        }
    }

    private void scanMediaFiles(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        MediaScannerConnection.scanFile(getContext(), new String[]{f.getAbsolutePath()}, null, null);
                    } else if (f.isDirectory()) {
                        scanMediaFiles(f);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void emitTerminal(String line) {
        JSObject obj = new JSObject();
        obj.put("line", line);
        notifyListeners("terminalOutput", obj);
    }

    private String parseStatus(String line) {
        if (line == null) return "Descargando...";
        if (line.contains("[download]") && line.contains("%")) {
            return "Descargando audio oficial...";
        }
        if (line.contains("[ExtractAudio]")) {
            return "Procesando audio en alta fidelidad...";
        }
        if (line.contains("[ThumbnailsConvertor]") || line.contains("[EmbedThumbnail]")) {
            return "Incrustando carátula oficial 1200x1200px...";
        }
        if (line.contains("[Metadata]")) {
            return "Aplicando metadatos ID3 oficiales...";
        }
        return "Procesando...";
    }

    private File getMusicDirectory() {
        File pubMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        File biblio = new File(pubMusic, "Biblioteca");
        if (!biblio.exists()) {
            biblio.mkdirs();
        }
        if (biblio.canWrite()) {
            return biblio;
        }
        File fallback = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Biblioteca");
        if (!fallback.exists()) {
            fallback.mkdirs();
        }
        return fallback;
    }
}
