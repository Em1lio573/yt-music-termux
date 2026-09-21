package com.emilio.ytmusic;

import android.Manifest;
import android.content.ContentResolver;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
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

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

                // Actualizar yt-dlp a la última versión disponible para evitar bloqueos y HTTP 403 Forbidden
                try {
                    Log.d(TAG, "Buscando actualizaciones de yt-dlp...");
                    YoutubeDL.getInstance().updateYoutubeDL(getContext(), YoutubeDL.UpdateChannel._STABLE);
                    Log.d(TAG, "yt-dlp actualizado a la versión más reciente.");
                } catch (Exception updateEx) {
                    Log.w(TAG, "Omitiendo actualización de yt-dlp: " + updateEx.getMessage());
                }
                
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
        boolean hasStorage = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasStorage = Environment.isExternalStorageManager();
        }
        ret.put("hasFullStorage", hasStorage);
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
    public void checkStoragePermission(PluginCall call) {
        JSObject ret = new JSObject();
        boolean hasPermission = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasPermission = Environment.isExternalStorageManager();
        }
        ret.put("hasPermission", hasPermission);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestStoragePermission(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(intent);
                }
            }
            JSObject ret = new JSObject();
            ret.put("requested", true);
            call.resolve(ret);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                JSObject ret = new JSObject();
                ret.put("requested", true);
                call.resolve(ret);
            } catch (Exception ex) {
                call.reject("Error abriendo pantalla de permisos: " + ex.getMessage());
            }
        }
    }

    private File getCookiesFile() {
        return new File(getContext().getFilesDir(), "youtube_cookies.txt");
    }

    private synchronized void saveNetscapeCookies(String rawCookies, String domain) {
        if (rawCookies == null || rawCookies.trim().isEmpty()) return;
        try {
            File cookieFile = getCookiesFile();
            StringBuilder sb = new StringBuilder();
            sb.append("# Netscape HTTP Cookie File\n");
            sb.append("# Generated by YouTube Music Pro\n\n");

            long expiry = (System.currentTimeMillis() / 1000L) + (365 * 24 * 3600);
            String[] pairs = rawCookies.split(";");
            for (String pair : pairs) {
                String trimmed = pair.trim();
                if (trimmed.isEmpty()) continue;
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    String name = trimmed.substring(0, eq).trim();
                    String val = trimmed.substring(eq + 1).trim();
                    sb.append(domain).append("\tTRUE\t/\tTRUE\t")
                      .append(expiry).append("\t")
                      .append(name).append("\t")
                      .append(val).append("\n");
                }
            }
            try (FileWriter fw = new FileWriter(cookieFile, false)) {
                fw.write(sb.toString());
            }
            Log.d(TAG, "Cookies guardadas con éxito en: " + cookieFile.getAbsolutePath() + " (" + cookieFile.length() + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "Error escribiendo archivo de cookies: " + e.getMessage(), e);
        }
    }

    private void extractAndSaveCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            String ytc = cm.getCookie("https://www.youtube.com");
            String mytc = cm.getCookie("https://music.youtube.com");
            String combined = (ytc != null ? ytc : "") + "; " + (mytc != null ? mytc : "");
            if (!combined.trim().isEmpty()) {
                saveNetscapeCookies(combined, ".youtube.com");
            }
        } catch (Exception ex) {
            Log.w(TAG, "Error extrayendo cookies: " + ex.getMessage());
        }
    }

    @PluginMethod
    public void getCookieStatus(PluginCall call) {
        File cookieFile = getCookiesFile();
        boolean exists = cookieFile.exists() && cookieFile.length() > 20;
        JSObject ret = new JSObject();
        ret.put("hasCookies", exists);
        ret.put("size", exists ? cookieFile.length() : 0);
        ret.put("lastModified", exists ? cookieFile.lastModified() : 0);
        call.resolve(ret);
    }

    @PluginMethod
    public void saveCookiesText(PluginCall call) {
        String content = call.getString("content", "");
        if (content == null || content.trim().isEmpty()) {
            call.reject("El contenido de cookies está vacío.");
            return;
        }
        try {
            File cookieFile = getCookiesFile();
            try (FileWriter fw = new FileWriter(cookieFile, false)) {
                fw.write(content.trim() + "\n");
            }
            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("size", cookieFile.length());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Error guardando cookies: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void clearCookies(PluginCall call) {
        try {
            File cookieFile = getCookiesFile();
            if (cookieFile.exists()) {
                cookieFile.delete();
            }
            getActivity().runOnUiThread(() -> {
                try {
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                } catch (Exception ignored) {}
            });
            JSObject ret = new JSObject();
            ret.put("cleared", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Error borrando cookies: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void openYouTubeLogin(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                Context context = getActivity();
                Dialog dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);

                LinearLayout layout = new LinearLayout(context);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setBackgroundColor(0xFF0F0F11);

                // Barra superior estilo Apple
                LinearLayout header = new LinearLayout(context);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setPadding(40, 56, 40, 32);
                header.setBackgroundColor(0xFF1C1C1E);
                header.setGravity(Gravity.CENTER_VERTICAL);

                TextView titleView = new TextView(context);
                titleView.setText("Iniciar Sesión en YouTube");
                titleView.setTextColor(0xFFFFFFFF);
                titleView.setTextSize(16);
                titleView.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                titleView.setLayoutParams(titleParams);

                Button closeBtn = new Button(context);
                closeBtn.setText("Listo");
                closeBtn.setTextColor(0xFFFF375F);
                closeBtn.setBackgroundColor(0x00000000);
                closeBtn.setTextSize(15);
                closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
                closeBtn.setOnClickListener(v -> {
                    extractAndSaveCookies();
                    dialog.dismiss();
                    JSObject ret = new JSObject();
                    ret.put("completed", true);
                    call.resolve(ret);
                });

                header.addView(titleView);
                header.addView(closeBtn);
                layout.addView(header);

                // WebView de inicio de sesión de Google / YouTube
                WebView webView = new WebView(context);
                webView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ));

                WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36");

                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setAcceptThirdPartyCookies(webView, true);

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        String cookies = cookieManager.getCookie(url);
                        if (cookies != null && (cookies.contains("LOGIN_INFO") || cookies.contains("SID") || cookies.contains("SSID"))) {
                            try {
                                saveNetscapeCookies(cookies, ".youtube.com");
                                String musicCookies = cookieManager.getCookie("https://music.youtube.com");
                                if (musicCookies != null) {
                                    saveNetscapeCookies(musicCookies, ".youtube.com");
                                }
                                Log.d(TAG, "Cookies de sesión autenticadas capturadas con éxito");
                            } catch (Exception ignored) {}
                        }
                    }
                });

                webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://m.youtube.com");
                layout.addView(webView);

                dialog.setContentView(layout);
                dialog.setOnDismissListener(d -> {
                    extractAndSaveCookies();
                });
                dialog.show();

            } catch (Exception e) {
                Log.e(TAG, "Error abriendo login WebView: " + e.getMessage(), e);
                call.reject("No se pudo abrir el inicio de sesión: " + e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void searchMusic(PluginCall call) {
        String query = call.getString("query", "").trim();
        if (query.isEmpty()) {
            call.reject("Consulta de búsqueda requerida");
            return;
        }

        executor.execute(() -> {
            try {
                if (!isInitialized) {
                    FFmpeg.getInstance().init(getContext());
                    YoutubeDL.getInstance().init(getContext());
                    isInitialized = true;
                }

                YoutubeDLRequest request = new YoutubeDLRequest("ytsearch10:" + query);
                request.addOption("--dump-single-json");
                request.addOption("--flat-playlist");
                request.addOption("--no-warnings");
                request.addOption("--ignore-errors");

                YoutubeDLResponse response = YoutubeDL.getInstance().execute(request);
                String jsonStr = response.getOut();
                
                JSArray results = new JSArray();
                if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                    JSONObject root = new JSONObject(jsonStr);
                    JSONArray entries = root.optJSONArray("entries");
                    if (entries != null) {
                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject e = entries.getJSONObject(i);
                            String id = e.optString("id", "");
                            String title = e.optString("title", "");
                            String uploader = e.optString("uploader", "YouTube Music");
                            if (uploader.endsWith(" - Topic")) {
                                uploader = uploader.replace(" - Topic", "").trim();
                            }
                            double duration = e.optDouble("duration", 0.0);
                            String durationStr = formatDuration(duration);
                            
                            String cover = "https://music.youtube.com/img/favicon_144.png";
                            JSONArray thumbs = e.optJSONArray("thumbnails");
                            if (thumbs != null && thumbs.length() > 0) {
                                cover = thumbs.getJSONObject(thumbs.length() - 1).optString("url", cover);
                            }

                            JSObject item = new JSObject();
                            item.put("id", id.isEmpty() ? "ytm_" + i : id);
                            item.put("title", title);
                            item.put("artist", uploader);
                            item.put("album", "YouTube Music");
                            item.put("duration", duration);
                            item.put("duration_str", durationStr);
                            item.put("cover", cover);
                            item.put("url", "https://music.youtube.com/watch?v=" + id);
                            item.put("source", "ytm_native");
                            results.put(item);
                        }
                    }
                }

                JSObject ret = new JSObject();
                ret.put("results", results);
                call.resolve(ret);
            } catch (Exception e) {
                Log.e(TAG, "Error en búsqueda nativa: " + e.getMessage(), e);
                JSObject ret = new JSObject();
                ret.put("results", new JSArray());
                call.resolve(ret);
            }
        });
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
            File tempDir = null;
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

                // 1. Sanitizar URL y enlaces compartidos desde apps de YouTube / YouTube Music
                String cleanUrl = rawUrl != null ? rawUrl.trim() : "";
                Matcher urlMatcher = Pattern.compile("https?://[^\\s]+").matcher(cleanUrl);
                if (urlMatcher.find()) {
                    cleanUrl = urlMatcher.group(0);
                }

                // Normalizar music.youtube.com a www.youtube.com para usar el cliente Android y evitar 403 Forbidden
                cleanUrl = cleanUrl.replace("music.youtube.com", "www.youtube.com");

                boolean isDirectTrack = cleanUrl.contains("watch?v=") || cleanUrl.contains("youtu.be/");
                boolean isPlaylist = cleanUrl.contains("playlist?list=");
                boolean isSingleTrack = isDirectTrack;

                if (cleanUrl.contains("watch?v=")) {
                    cleanUrl = cleanUrl.replaceAll("([?&])list=RD[^&]*", "");
                    cleanUrl = cleanUrl.replaceAll("([?&])si=[^&]*", "");
                    cleanUrl = cleanUrl.replaceAll("&feature=[^&]*", "");
                    if (cleanUrl.contains("?&")) cleanUrl = cleanUrl.replace("?&", "?");
                } else if (cleanUrl.contains("youtu.be/")) {
                    cleanUrl = cleanUrl.replaceAll("([?&])si=[^&]*", "");
                }

                String targetQuery;
                if (isDirectTrack || isPlaylist) {
                    targetQuery = cleanUrl;
                } else if (!artist.isEmpty() && !title.isEmpty()) {
                    targetQuery = "ytsearch1:" + artist + " " + title;
                    isSingleTrack = true;
                } else if (!title.isEmpty()) {
                    targetQuery = "ytsearch1:" + title;
                    isSingleTrack = true;
                } else if (!cleanUrl.isEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                    targetQuery = "ytsearch1:" + cleanUrl;
                    isSingleTrack = true;
                } else {
                    targetQuery = cleanUrl;
                }

                // 2. Aislar descarga y conversiones en el directorio de caché privado para evitar [Errno 1]
                File cacheBase = getContext().getCacheDir();
                tempDir = new File(cacheBase, "ytm_dl_" + System.currentTimeMillis());
                if (!tempDir.exists()) {
                    tempDir.mkdirs();
                }

                emitTerminal("[yt-dlp] Iniciando descarga para: " + (artist.isEmpty() ? targetQuery : artist + " - " + title));
                emitTerminal("[Motor] Conectando mediante cliente Android oficial");

                YoutubeDLRequest request = new YoutubeDLRequest(targetQuery);
                request.addOption("-P", "home:" + tempDir.getAbsolutePath());
                request.addOption("-P", "temp:" + tempDir.getAbsolutePath());
                request.addOption("-o", "%(artist,uploader)s - %(title)s.%(ext)s");

                // Inyectar cookies de sesión autenticada si existen para evitar bloqueo anti-bot
                File cookieFile = getCookiesFile();
                if (cookieFile.exists() && cookieFile.length() > 20) {
                    request.addOption("--cookies", cookieFile.getAbsolutePath());
                    emitTerminal("[Seguridad] ✔ Sesión autenticada con cookies activa");
                }

                // Parámetros críticos contra HTTP Error 403: Forbidden y rate-limits
                request.addOption("--extractor-args", "youtube:player_client=android,web");
                request.addOption("--user-agent", "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36");
                request.addOption("--no-check-certificates");
                request.addOption("--force-ipv4");

                if (isSingleTrack) {
                    request.addOption("--no-playlist");
                }
                
                // Extracción con opciones reales de YouTube Music
                request.addOption("-x");
                if ("m4a".equals(quality)) {
                    request.addOption("-f", "ba[ext=m4a]/ba");
                    request.addOption("--audio-format", "m4a");
                    emitTerminal("[yt-dlp] Formato: M4A / AAC Nativo (~128 kbps) - Extracción directa");
                } else if ("m4a_256".equals(quality)) {
                    request.addOption("-f", "141/ba[ext=m4a]/ba");
                    request.addOption("--audio-format", "m4a");
                    emitTerminal("[yt-dlp] Formato: M4A Premium (256 kbps con fallback nativo)");
                } else if ("mp3_320".equals(quality)) {
                    request.addOption("--audio-format", "mp3");
                    request.addOption("--audio-quality", "320K");
                    emitTerminal("[ffmpeg] Formato: MP3 320 kbps (Transcodificación de compatibilidad)");
                } else if ("data_saver".equals(quality)) {
                    request.addOption("-f", "250/249/ba[abr<=80]/ba");
                    request.addOption("--audio-format", "opus");
                    emitTerminal("[yt-dlp] Formato: Opus Ahorro de Datos (~70 kbps)");
                } else {
                    request.addOption("-f", "ba[ext=opus]/ba[ext=webm]/ba[ext=m4a]/bestaudio/best");
                    request.addOption("--audio-format", "opus");
                    emitTerminal("[yt-dlp] Formato: Opus Nativo (~160 kbps) con fallback automático");
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

                // 3. Localizar archivo de audio descargado y moverlo a la Biblioteca organizada
                File[] audioFiles = tempDir.listFiles((dir, name) -> {
                    String n = name.toLowerCase();
                    return n.endsWith(".opus") || n.endsWith(".m4a") || n.endsWith(".mp3") 
                        || n.endsWith(".flac") || n.endsWith(".ogg") || n.endsWith(".wav");
                });

                if (audioFiles == null || audioFiles.length == 0) {
                    throw new Exception("No se generó ningún archivo de audio descargado (0 items extraídos).");
                }

                for (File audioFile : audioFiles) {
                        String finalArtist = !artist.isEmpty() ? sanitizeDirName(artist) : "YouTube Music";
                        String finalAlbum = !album.isEmpty() ? sanitizeDirName(album) : "Sencillos";

                        if (artist.isEmpty()) {
                            String nameNoExt = audioFile.getName().replaceFirst("[.][^.]+$", "");
                            if (nameNoExt.contains(" - ")) {
                                String[] parts = nameNoExt.split(" - ", 2);
                                finalArtist = sanitizeDirName(parts[0]);
                            }
                        }

                        File targetArtistDir = new File(musicDir, finalArtist);
                        File targetAlbumDir = new File(targetArtistDir, finalAlbum);
                        targetAlbumDir.mkdirs();

                        File finalDest = new File(targetAlbumDir, audioFile.getName());
                        boolean moved = audioFile.renameTo(finalDest);
                        if (!moved) {
                            copyFile(audioFile, finalDest);
                            audioFile.delete();
                        }

                        MediaScannerConnection.scanFile(getContext(), new String[]{finalDest.getAbsolutePath()}, null, null);
                    }

                emitTerminal("[Sistema] ✔ Descarga completada y organizada en tu biblioteca");
                scanMediaFiles(musicDir);

                JSObject res = new JSObject();
                res.put("success", true);
                res.put("message", "Canción descargada con éxito");
                res.put("log", response.getOut());
                call.resolve(res);

            } catch (Exception e) {
                String rawMsg = e.getMessage() != null ? e.getMessage() : "Error desconocido";
                Log.e(TAG, "Error en descarga nativa: " + rawMsg, e);

                if (rawMsg.contains("bot") || rawMsg.contains("Sign in") || rawMsg.contains("LOGIN_REQUIRED") || rawMsg.contains("0 items")) {
                    emitTerminal("[Alerta] YouTube requiere verificación antibot en tu red actual.");
                    emitTerminal("[Solución] 1) Inicia sesión en Ajustes > Conectar YouTube, o 2) Activa Datos Móviles.");
                    call.reject("YouTube requiere verificación antibot en tu red. Ve a Ajustes > Conectar cuenta de YouTube o activa tus Datos Móviles.", e);
                } else {
                    emitTerminal("[Error] Falló la descarga: " + rawMsg);
                    call.reject("Error en descarga nativa: " + rawMsg, e);
                }
            } finally {
                if (tempDir != null && tempDir.exists()) {
                    deleteDirectoryRecursive(tempDir);
                }
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
            if (f.exists()) {
                f.delete();
            }

            // Purgar de MediaStore para evitar que quede como pista fantasma
            try {
                ContentResolver resolver = getContext().getContentResolver();
                Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                resolver.delete(uri, MediaStore.Audio.Media.DATA + " = ?", new String[]{path});
            } catch (Exception ex) {
                Log.w(TAG, "Error purgando de MediaStore: " + ex.getMessage());
            }

            MediaScannerConnection.scanFile(getContext(), new String[]{path}, null, null);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
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

    private void copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copiando archivo: " + e.getMessage());
        }
    }

    private void deleteDirectoryRecursive(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectoryRecursive(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    private String sanitizeDirName(String name) {
        if (name == null || name.trim().isEmpty()) return "Desconocido";
        String clean = name.replaceAll("[/\\\\:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
        return clean.isEmpty() ? "Desconocido" : clean;
    }

    private String formatDuration(double sec) {
        if (sec <= 0) return "0:00";
        int totalSec = (int) Math.round(sec);
        int m = totalSec / 60;
        int s = totalSec % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
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
