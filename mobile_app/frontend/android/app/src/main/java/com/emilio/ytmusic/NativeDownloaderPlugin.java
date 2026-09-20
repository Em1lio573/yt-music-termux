package com.emilio.ytmusic;

import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;

@CapacitorPlugin(name = "NativeDownloader")
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
                
                // Extracción de audio con ffmpeg nativo
                request.addOption("-x");
                if ("m4a".equals(quality)) {
                    request.addOption("--audio-format", "m4a");
                    request.addOption("--audio-quality", "256K");
                    emitTerminal("[ffmpeg] Modo de audio: M4A / AAC (~256 kbps)");
                } else if ("mp3_320".equals(quality)) {
                    request.addOption("--audio-format", "mp3");
                    request.addOption("--audio-quality", "320K");
                    emitTerminal("[ffmpeg] Modo de audio: MP3 320 kbps (CBR)");
                } else if ("mp3_v0".equals(quality)) {
                    request.addOption("--audio-format", "mp3");
                    request.addOption("--audio-quality", "0");
                    emitTerminal("[ffmpeg] Modo de audio: MP3 VBR V0 (~245 kbps)");
                } else if ("flac".equals(quality)) {
                    request.addOption("--audio-format", "flac");
                    emitTerminal("[ffmpeg] Modo de audio: FLAC Lossless");
                } else {
                    // Nativo sin recodificación
                    request.addOption("--audio-format", "best");
                    emitTerminal("[ffmpeg] Modo de audio: Original Nativo (Opus ~160k / AAC ~128k)");
                }

                // Metadatos oficiales y carátula
                request.addOption("--embed-metadata");
                request.addOption("--embed-thumbnail");
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
            File musicDir = getMusicDirectory();
            JSArray tracks = new JSArray();

            if (musicDir.exists()) {
                scanDirRecursive(musicDir, musicDir, tracks);
            }

            JSObject ret = new JSObject();
            ret.put("tracks", tracks);
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

    private void scanDirRecursive(File root, File dir, JSArray tracks) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        for (File f : files) {
            if (f.isDirectory()) {
                scanDirRecursive(root, f, tracks);
            } else {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".opus") || name.endsWith(".m4a") || name.endsWith(".mp3") 
                    || name.endsWith(".flac") || name.endsWith(".ogg") || name.endsWith(".wav")) {
                    
                    double sizeMb = Math.round((f.length() / (1024.0 * 1024.0)) * 100.0) / 100.0;
                    String parentName = f.getParentFile() != null ? f.getParentFile().getName() : "Biblioteca";
                    String title = f.getName().replaceFirst("[.][^.]+$", "");
                    
                    JSObject item = new JSObject();
                    item.put("title", title);
                    item.put("artist", parentName);
                    item.put("folder", parentName);
                    item.put("path", f.getAbsolutePath());
                    item.put("size_mb", sizeMb);
                    item.put("ext", name.substring(name.lastIndexOf('.')));
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
