package com.emilio.ytmusic;

import android.Manifest;
import android.content.ContentResolver;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.media.MediaMetadataRetriever;
import android.content.Intent;
import android.content.SharedPreferences;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.MessageDigest;

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

                // Migrar y limpiar carátulas sueltas para que no aparezcan en la Galería, y extraer carátulas incrustadas a .covers/
                try {
                    File musicDir = getMusicDirectory();
                    if (musicDir != null && musicDir.exists()) {
                        migrateAndCleanLooseCovers(musicDir);
                        cleanAllOrphanedDirectories(musicDir);
                        extractAllEmbeddedCovers(musicDir);
                    }
                } catch (Exception covEx) {
                    Log.w(TAG, "Error en inicialización de carátulas: " + covEx.getMessage());
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

    private synchronized boolean extractAndSaveAllCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.flush();

            Map<String, Map<String, String>> domainCookies = new LinkedHashMap<>();
            domainCookies.put(".youtube.com", new LinkedHashMap<>());
            domainCookies.put(".google.com", new LinkedHashMap<>());

            String[] googleUrls = new String[]{
                "https://accounts.google.com",
                "https://google.com",
                "https://www.google.com",
                "https://myaccount.google.com"
            };

            String[] youtubeUrls = new String[]{
                "https://m.youtube.com",
                "https://youtube.com",
                "https://www.youtube.com",
                "https://music.youtube.com"
            };

            for (String url : googleUrls) {
                String raw = cm.getCookie(url);
                if (raw != null && !raw.trim().isEmpty()) {
                    parseCookiePairs(raw, domainCookies.get(".google.com"));
                }
            }

            for (String url : youtubeUrls) {
                String raw = cm.getCookie(url);
                if (raw != null && !raw.trim().isEmpty()) {
                    parseCookiePairs(raw, domainCookies.get(".youtube.com"));
                }
            }

            Map<String, String> googleMap = domainCookies.get(".google.com");
            Map<String, String> ytMap = domainCookies.get(".youtube.com");

            // Si hay tokens de sesión en Google, replicarlos también en .youtube.com para autenticar yt-dlp completamente
            String[] criticalKeys = new String[]{"SID", "HSID", "SSID", "APISID", "SAPISID", "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID"};
            for (String key : criticalKeys) {
                if (googleMap.containsKey(key) && !ytMap.containsKey(key)) {
                    ytMap.put(key, googleMap.get(key));
                }
            }

            int totalCookies = googleMap.size() + ytMap.size();
            if (totalCookies == 0) {
                Log.w(TAG, "extractAndSaveAllCookies: No se encontraron cookies en CookieManager.");
                return false;
            }

            File cookieFile = getCookiesFile();
            StringBuilder sb = new StringBuilder();
            sb.append("# Netscape HTTP Cookie File\n");
            sb.append("# Generated by YouTube Music Pro\n");
            sb.append("# Authenticated session\n\n");

            long expiry = (System.currentTimeMillis() / 1000L) + (365 * 24 * 3600); // 1 año de vigencia

            for (Map.Entry<String, Map<String, String>> entry : domainCookies.entrySet()) {
                String domain = entry.getKey();
                for (Map.Entry<String, String> c : entry.getValue().entrySet()) {
                    sb.append(domain).append("\tTRUE\t/\tTRUE\t")
                      .append(expiry).append("\t")
                      .append(c.getKey()).append("\t")
                      .append(c.getValue()).append("\n");
                }
            }

            try (FileWriter fw = new FileWriter(cookieFile, false)) {
                fw.write(sb.toString());
            }

            boolean hasAuth = ytMap.containsKey("LOGIN_INFO") || 
                              googleMap.containsKey("SID") || 
                              googleMap.containsKey("SSID") || 
                              ytMap.containsKey("SID");

            Log.i(TAG, "[OK] Cookies Netscape guardadas con éxito: " + cookieFile.getAbsolutePath() + 
                       " (" + cookieFile.length() + " bytes, " + totalCookies + " cookies, hasAuth=" + hasAuth + ")");
            return hasAuth;
        } catch (Exception ex) {
            Log.e(TAG, "Error en extractAndSaveAllCookies: " + ex.getMessage(), ex);
            return false;
        }
    }

    private void parseCookiePairs(String raw, Map<String, String> targetMap) {
        if (raw == null || targetMap == null) return;
        String[] pairs = raw.split(";");
        for (String pair : pairs) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String name = trimmed.substring(0, eq).trim();
                String val = trimmed.substring(eq + 1).trim();
                if (!name.isEmpty() && !val.isEmpty()) {
                    targetMap.put(name, val);
                }
            }
        }
    }

    @PluginMethod
    public void getCookieStatus(PluginCall call) {
        File cookieFile = getCookiesFile();
        boolean exists = cookieFile.exists() && cookieFile.length() > 50;
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
    public void updateEngine(PluginCall call) {
        executor.execute(() -> {
            try {
                if (!isInitialized) {
                    FFmpeg.getInstance().init(getContext());
                    YoutubeDL.getInstance().init(getContext());
                    isInitialized = true;
                }
                YoutubeDL.UpdateStatus status = YoutubeDL.getInstance().updateYoutubeDL(getContext(), YoutubeDL.UpdateChannel._STABLE);
                String version = YoutubeDL.getInstance().versionName(getContext());
                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("status", status != null ? status.name() : "UPDATED");
                ret.put("version", version);
                call.resolve(ret);
            } catch (Exception e) {
                Log.e(TAG, "Error actualizando yt-dlp: " + e.getMessage(), e);
                call.reject("Error actualizando motor yt-dlp: " + e.getMessage(), e);
            }
        });
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

                LinearLayout titlesLayout = new LinearLayout(context);
                titlesLayout.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                titlesLayout.setLayoutParams(titleParams);

                TextView titleView = new TextView(context);
                titleView.setText("Iniciar Sesión en YouTube");
                titleView.setTextColor(0xFFFFFFFF);
                titleView.setTextSize(16);
                titleView.setTypeface(Typeface.DEFAULT_BOLD);

                TextView subtitleView = new TextView(context);
                subtitleView.setText("Inicia sesión con tu cuenta de Google / YouTube");
                subtitleView.setTextColor(0xFF8E8E93);
                subtitleView.setTextSize(12);

                titlesLayout.addView(titleView);
                titlesLayout.addView(subtitleView);

                Button closeBtn = new Button(context);
                closeBtn.setText("Listo");
                closeBtn.setTextColor(0xFFFF375F);
                closeBtn.setBackgroundColor(0x00000000);
                closeBtn.setTextSize(15);
                closeBtn.setTypeface(Typeface.DEFAULT_BOLD);

                header.addView(titlesLayout);
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
                        cookieManager.flush();
                        boolean hasAuth = extractAndSaveAllCookies();
                        if (hasAuth) {
                            subtitleView.setText("Sesión detectada con éxito. Toca 'Listo'");
                            subtitleView.setTextColor(0xFF34C759);
                        }
                    }
                });

                closeBtn.setOnClickListener(v -> {
                    cookieManager.flush();
                    boolean hasAuth = extractAndSaveAllCookies();
                    File cookieFile = getCookiesFile();
                    dialog.dismiss();
                    JSObject ret = new JSObject();
                    ret.put("completed", true);
                    ret.put("hasAuth", hasAuth);
                    ret.put("size", cookieFile.exists() ? cookieFile.length() : 0);
                    call.resolve(ret);
                });

                webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://m.youtube.com");
                layout.addView(webView);

                dialog.setContentView(layout);
                dialog.setOnDismissListener(d -> {
                    cookieManager.flush();
                    extractAndSaveAllCookies();
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
        String inArtist = call.getString("artist", "").trim();
        String inTitle = call.getString("title", "").trim();
        String inAlbum = call.getString("album", "").trim();
        String inCover = call.getString("cover", "").trim();

        if (rawUrl.isEmpty() && (inArtist.isEmpty() || inTitle.isEmpty())) {
            call.reject("URL o artista/título requeridos");
            return;
        }

        executor.execute(() -> {
            String artist = inArtist;
            String title = inTitle;
            String album = inAlbum;
            String cover = inCover;
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
                boolean hasArtistAndTitle = !artist.isEmpty() && !title.isEmpty();

                if (isDirectTrack || isPlaylist) {
                    targetQuery = cleanUrl;
                } else if (hasArtistAndTitle) {
                    // Priorizar la pista de audio oficial / máster de estudio en lugar de videoclips
                    targetQuery = "ytsearch1:" + artist + " " + title + " audio";
                    isSingleTrack = true;
                } else if (!title.isEmpty()) {
                    targetQuery = "ytsearch1:" + title + " audio";
                    isSingleTrack = true;
                } else if (!cleanUrl.isEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                    targetQuery = "ytsearch1:" + cleanUrl + " audio";
                    isSingleTrack = true;
                } else {
                    targetQuery = cleanUrl;
                }

                // 1.1 Si es un enlace directo o los metadatos vienen vacíos/genéricos, resolverlos automáticamente
                if (isDirectTrack || (cleanUrl.startsWith("http") && (artist.isEmpty() || title.isEmpty() || title.contains("Enlace directo") || title.contains("Descarga YouTube")))) {
                    try {
                        emitTerminal("[Metadatos] Analizando enlace y consultando metadatos oficiales...");
                        JSObject meta = getMetadataForUrl(cleanUrl);
                        if (meta != null) {
                            String rArtist = meta.getString("artist");
                            String rTitle = meta.getString("title");
                            String rAlbum = meta.getString("album");
                            String rCover = meta.getString("cover");
                            if (rArtist != null && !rArtist.isEmpty() && !rArtist.equals("YouTube")) artist = rArtist;
                            if (rTitle != null && !rTitle.isEmpty() && !rTitle.contains("Tema YouTube")) title = rTitle;
                            if (rAlbum != null && !rAlbum.isEmpty()) album = rAlbum;
                            if (cover.isEmpty() && rCover != null && !rCover.isEmpty()) cover = rCover;
                            hasArtistAndTitle = !artist.isEmpty() && !title.isEmpty();
                            emitTerminal("[Metadatos] [OK] Identificado: " + artist + " - " + title + " (Álbum: " + album + ")");
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "Error resolviendo metadatos en downloadTrack: " + ex.getMessage());
                    }
                }

                // 2. Aislar descarga y conversiones en el directorio de caché privado para evitar [Errno 1]
                File cacheBase = getContext().getCacheDir();
                tempDir = new File(cacheBase, "ytm_dl_" + System.currentTimeMillis());
                if (!tempDir.exists()) {
                    tempDir.mkdirs();
                }

                // Descargar carátula cuadrada oficial 1200x1200px si fue provista por el buscador (Apple / iTunes)
                File coverFile = null;
                if (!cover.isEmpty() && (cover.startsWith("http://") || cover.startsWith("https://"))) {
                    try {
                        coverFile = new File(tempDir, "official_cover.jpg");
                        downloadImage(cover, coverFile);
                        if (coverFile.exists() && coverFile.length() > 0) {
                            emitTerminal("[Carátula] [OK] Carátula oficial 1:1 HD descargada (" + (coverFile.length() / 1024) + " KB)");
                        } else {
                            coverFile = null;
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "No se pudo descargar carátula oficial: " + ex.getMessage());
                        emitTerminal("[Aviso] Carátula oficial no disponible, se usará carátula alternativa");
                        coverFile = null;
                    }
                }

                emitTerminal("[yt-dlp] Iniciando descarga para: " + (artist.isEmpty() ? targetQuery : artist + " - " + title));
                emitTerminal("[Motor] Conectando mediante cliente Android oficial");

                YoutubeDLRequest request = new YoutubeDLRequest(targetQuery);
                request.addOption("-P", "home:" + tempDir.getAbsolutePath());
                request.addOption("-P", "temp:" + tempDir.getAbsolutePath());
                request.addOption("--write-info-json");

                if (hasArtistAndTitle) {
                    String cleanName = sanitizeDirName(artist) + " - " + sanitizeDirName(title);
                    request.addOption("-o", cleanName + ".%(ext)s");
                } else {
                    request.addOption("-o", "%(title)s.%(ext)s");
                }

                // Inyectar cookies de sesión autenticada si existen para evitar bloqueo anti-bot
                File cookieFile = getCookiesFile();
                if (cookieFile.exists() && cookieFile.length() > 50) {
                    request.addOption("--cookies", cookieFile.getAbsolutePath());
                    emitTerminal("[Seguridad] [OK] Sesión autenticada con cookies activa (" + cookieFile.length() + " bytes)");
                } else {
                    emitTerminal("[Aviso] Descargando en modo invitado (sin cookies de sesión)");
                }

                // Parámetros críticos contra HTTP Error 403: Forbidden y rate-limits
                request.addOption("--extractor-args", "youtube:player_client=android,web");
                request.addOption("--downloader-args", "ffmpeg_i:-reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 5");
                request.addOption("--no-check-certificates");
                request.addOption("--force-ipv4");

                if (isSingleTrack) {
                    request.addOption("--no-playlist");
                }
                
                // Extracción con opciones reales de YouTube Music y fallback universal (/b/best) para garantizar descarga continua
                request.addOption("-x");
                if ("m4a".equals(quality)) {
                    request.addOption("-f", "ba[ext=m4a]/ba/b/best");
                    request.addOption("--audio-format", "m4a");
                    emitTerminal("[yt-dlp] Formato: M4A / AAC Nativo (~128 kbps) - Extracción directa");
                } else if ("m4a_256".equals(quality)) {
                    request.addOption("-f", "141/ba[ext=m4a]/ba/b/best");
                    request.addOption("--audio-format", "m4a");
                    emitTerminal("[yt-dlp] Formato: M4A Premium (256 kbps con fallback nativo)");
                } else if ("mp3_320".equals(quality)) {
                    request.addOption("-f", "ba/b/best");
                    request.addOption("--audio-format", "mp3");
                    request.addOption("--audio-quality", "320K");
                    emitTerminal("[ffmpeg] Formato: MP3 320 kbps (Transcodificación de compatibilidad)");
                } else if ("data_saver".equals(quality)) {
                    request.addOption("-f", "250/249/ba[abr<=80]/ba/b/best");
                    request.addOption("--audio-format", "opus");
                    emitTerminal("[yt-dlp] Formato: Opus Ahorro de Datos (~70 kbps)");
                } else {
                    request.addOption("-f", "ba[ext=opus]/ba[ext=webm]/ba[ext=m4a]/bestaudio/b/best");
                    request.addOption("--audio-format", "opus");
                    emitTerminal("[yt-dlp] Formato: Opus Nativo (~160 kbps) con fallback automático");
                }

                // Metadatos oficiales y carátula
                request.addOption("--embed-metadata");
                if (coverFile == null) {
                    // Si no hubo carátula oficial de Apple/iTunes, usar miniatura de YouTube de respaldo
                    request.addOption("--embed-thumbnail");
                    request.addOption("--convert-thumbnails", "jpg");
                }
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
                        prog.put("percent", Math.min(percent, 88));
                        prog.put("eta", etaInSeconds > 0 ? (etaInSeconds + "s") : "--");
                        prog.put("statusText", parseStatus(line));
                        prog.put("speed", parseSpeed(line));
                        prog.put("stage", "audio");
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

                // Si la carátula aún no se descargó, intentar recuperarla desde el info.json de yt-dlp o buscarla
                if (coverFile == null) {
                    File[] jsonFiles = tempDir.listFiles((d, n) -> n.toLowerCase().endsWith(".info.json"));
                    if (jsonFiles != null && jsonFiles.length > 0) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(jsonFiles[0]), StandardCharsets.UTF_8))) {
                            StringBuilder sb = new StringBuilder();
                            String l;
                            while ((l = reader.readLine()) != null) sb.append(l);
                            JSONObject infoObj = new JSONObject(sb.toString());
                            String infoThumb = infoObj.optString("thumbnail", "");
                            String infoArtist = infoObj.optString("artist", infoObj.optString("uploader", ""));
                            String infoTrack = infoObj.optString("track", infoObj.optString("title", ""));
                            String infoAlbum = infoObj.optString("album", "");

                            if (artist.isEmpty() || "YouTube Music".equalsIgnoreCase(artist)) {
                                artist = !infoArtist.isEmpty() ? infoArtist.replace(" - Topic", "").trim() : "Artista";
                            }
                            if (title.isEmpty() || title.contains("Enlace directo") || title.contains("Descarga YouTube")) {
                                title = !infoTrack.isEmpty() ? infoTrack : cleanYouTubeTitle(infoObj.optString("title", ""));
                            }
                            if (album.isEmpty() || album.equals("Sencillos")) {
                                if (!infoAlbum.isEmpty()) album = infoAlbum;
                            }

                            String itunesCover = findBestCoverJava(artist, title, album, false);
                            String coverUrlToUse = (itunesCover != null && !itunesCover.isEmpty()) ? itunesCover : infoThumb;
                            if (!coverUrlToUse.isEmpty()) {
                                coverFile = new File(tempDir, "recovered_cover.jpg");
                                downloadImage(coverUrlToUse, coverFile);
                                if (coverFile.exists() && coverFile.length() > 0) {
                                    emitTerminal("[Carátula] [OK] Carátula oficial 1:1 HD asociada");
                                }
                            }
                        } catch (Exception ex) {
                            Log.w(TAG, "Error recuperando carátula de info.json: " + ex.getMessage());
                        }
                    }
                }

                JSObject progCover = new JSObject();
                progCover.put("percent", 90);
                progCover.put("eta", "--");
                progCover.put("statusText", "Incrustando carátula oficial 1200px...");
                progCover.put("speed", "");
                progCover.put("stage", "cover");
                notifyListeners("downloadProgress", progCover);

                // Buscar letras oficiales sincronizadas en LRCLIB
                String lrcContent = null;
                try {
                    emitTerminal("[Letras] Buscando letra sincronizada oficial en LRCLIB...");
                    JSObject progLyrics = new JSObject();
                    progLyrics.put("percent", 94);
                    progLyrics.put("eta", "--");
                    progLyrics.put("statusText", "Sincronizando letras oficiales LRC...");
                    progLyrics.put("speed", "");
                    progLyrics.put("stage", "lyrics");
                    notifyListeners("downloadProgress", progLyrics);

                    JSObject lyricsObj = fetchLyricsLRCLIB(artist, title);
                    if (lyricsObj != null) {
                        String synced = lyricsObj.optString("syncedLyrics", "");
                        String plain = lyricsObj.optString("plainLyrics", "");
                        lrcContent = !synced.isEmpty() ? synced : plain;
                        if (!lrcContent.isEmpty()) {
                            emitTerminal("[Letras] [OK] Letra oficial encontrada (" + (!synced.isEmpty() ? "sincronizada karaoke" : "texto plano") + ")");
                        }
                    } else {
                        emitTerminal("[Letras] Letra no disponible en el repositorio");
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "Error buscando letra en downloadTrack: " + ex.getMessage());
                }

                for (File audioFile : audioFiles) {
                    // Incrustar etiquetas limpias, portada cuadrada y letra con Mutagen
                    tagAudioFile(audioFile, artist, title, album, coverFile, lrcContent);

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
                    if (!targetAlbumDir.exists()) {
                        targetAlbumDir.mkdirs();
                    }

                    String baseName = audioFile.getName().replaceFirst("[.][^.]+$", "");

                    File finalDest = new File(targetAlbumDir, audioFile.getName());
                    boolean moved = audioFile.renameTo(finalDest);
                    if (!moved) {
                        copyFile(audioFile, finalDest);
                        audioFile.delete();
                    }

                    // Guardar carátula exclusivamente en el almacén oculto .covers/ con .nomedia (NUNCA como imagen suelta en la galería)
                    if (coverFile != null && coverFile.exists()) {
                        File trackCover = getCoverFileForTrack(finalDest);
                        if (trackCover != null) {
                            copyFile(coverFile, trackCover);
                        }

                        File albumCover = getCoverFileForAlbum(targetAlbumDir);
                        if (albumCover != null && (!albumCover.exists() || albumCover.length() == 0)) {
                            copyFile(coverFile, albumCover);
                        }
                    }

                    // Guardar archivo .lrc en la carpeta junto a la canción para reproductores del sistema
                    if (lrcContent != null && !lrcContent.trim().isEmpty()) {
                        File targetLrc = new File(targetAlbumDir, baseName + ".lrc");
                        try (FileOutputStream fos = new FileOutputStream(targetLrc)) {
                            fos.write(lrcContent.getBytes(StandardCharsets.UTF_8));
                            MediaScannerConnection.scanFile(getContext(), new String[]{targetLrc.getAbsolutePath()}, null, null);
                        } catch (Exception ex) {
                            Log.w(TAG, "Error guardando archivo .lrc: " + ex.getMessage());
                        }
                    }

                    MediaScannerConnection.scanFile(getContext(), new String[]{finalDest.getAbsolutePath()}, null, null);
                }

                emitTerminal("[Sistema] [OK] Descarga completada y organizada en tu biblioteca");
                scanMediaFiles(musicDir);

                JSObject progDone = new JSObject();
                progDone.put("percent", 100);
                progDone.put("eta", "0s");
                progDone.put("statusText", "¡Completado! Guardado en tu biblioteca");
                progDone.put("speed", "");
                progDone.put("stage", "done");
                notifyListeners("downloadProgress", progDone);

                JSObject res = new JSObject();
                res.put("success", true);
                res.put("message", "Canción descargada con éxito");
                res.put("log", response.getOut());
                call.resolve(res);

            } catch (Exception e) {
                String rawMsg = e.getMessage() != null ? e.getMessage() : "Error desconocido";
                Log.e(TAG, "Error en descarga nativa: " + rawMsg, e);

                if (rawMsg.contains("bot") || rawMsg.contains("Sign in") || rawMsg.contains("LOGIN_REQUIRED") || rawMsg.contains("0 items")) {
                    emitTerminal("[Alerta Antibot] YouTube ha solicitado verificación en tu conexión.");
                    emitTerminal("[Solución 1] Ve a Ajustes > Iniciar Sesión en YouTube para renovar tus cookies.");
                    emitTerminal("[Solución 2] O apaga el Wi-Fi y usa Datos Móviles para descargar con nueva IP.");
                    call.reject("YouTube requiere verificación antibot en tu conexión actual. Inicia sesión en Ajustes > Iniciar Sesión en YouTube o activa tus Datos Móviles.", e);
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

            SharedPreferences prefs = getContext().getSharedPreferences("ytmusic_prefs", Context.MODE_PRIVATE);
            String scanMode = prefs.getString("scan_mode", "selected");
            boolean includeDownloads = prefs.getBoolean("include_downloads", false);
            boolean ignoreWhatsApp = prefs.getBoolean("ignore_whatsapp", true);
            boolean ignoreRecordings = prefs.getBoolean("ignore_recordings", true);
            int minDurationSec = prefs.getInt("min_duration_sec", 30);

            // 0. Migrar y limpiar imágenes sueltas para que no aparezcan en la Galería
            File musicDir = getMusicDirectory();
            if (musicDir != null && musicDir.exists()) {
                migrateAndCleanLooseCovers(musicDir);
                cleanAllOrphanedDirectories(musicDir);
                extractAllEmbeddedCovers(musicDir);
            }

            // 1. Escaneo según el modo configurado
            if ("selected".equals(scanMode)) {
                // Modo recomendado y seguro: Escanea únicamente la carpeta elegida por el usuario
                if (musicDir != null && musicDir.exists()) {
                    scanDirRecursive(musicDir, tracks, seenPaths, ignoreWhatsApp, ignoreRecordings, minDurationSec);
                }

                // Escaneo de carpetas personalizadas adicionales
                String customJson = prefs.getString("custom_music_folders", "[]");
                try {
                    JSONArray arr = new JSONArray(customJson);
                    for (int i = 0; i < arr.length(); i++) {
                        String cPath = arr.getString(i);
                        File cf = new File(cPath);
                        if (cf.exists() && cf.isDirectory()) {
                            scanDirRecursive(cf, tracks, seenPaths, ignoreWhatsApp, ignoreRecordings, minDurationSec);
                        }
                    }
                } catch (Exception ignored) {}

                if (includeDownloads) {
                    File downloadPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (downloadPublic != null && downloadPublic.exists()) {
                        scanDirRecursive(downloadPublic, tracks, seenPaths, ignoreWhatsApp, ignoreRecordings, minDurationSec);
                    }
                }

            } else if ("all_music".equals(scanMode)) {
                // Escaneo de todo /sdcard/Music/
                File musicPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                if (musicPublic != null && musicPublic.exists()) {
                    scanDirRecursive(musicPublic, tracks, seenPaths, ignoreWhatsApp, ignoreRecordings, minDurationSec);
                }

                String customJson = prefs.getString("custom_music_folders", "[]");
                try {
                    JSONArray arr = new JSONArray(customJson);
                    for (int i = 0; i < arr.length(); i++) {
                        String cPath = arr.getString(i);
                        File cf = new File(cPath);
                        if (cf.exists() && cf.isDirectory()) {
                            scanDirRecursive(cf, tracks, seenPaths, ignoreWhatsApp, ignoreRecordings, minDurationSec);
                        }
                    }
                } catch (Exception ignored) {}

                if (includeDownloads) {
                    File downloadPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (downloadPublic != null && downloadPublic.exists()) {
                        scanDirRecursive(downloadPublic, tracks, seenPaths, ignoreWhatsApp, ignoreRecordings, minDurationSec);
                    }
                }

            } else {
                // Modo "device": Escaneo completo con MediaStore y filtros estrictos
                File musicPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                if (musicPublic != null && musicPublic.exists()) {
                    scanDirRecursive(musicPublic, tracks, seenPaths, ignoreWhatsApp, ignoreRecordings, minDurationSec);
                }

                String customJson = prefs.getString("custom_music_folders", "[]");
                try {
                    JSONArray arr = new JSONArray(customJson);
                    for (int i = 0; i < arr.length(); i++) {
                        File cf = new File(arr.getString(i));
                        if (cf.exists() && cf.isDirectory()) {
                            scanDirRecursive(cf, tracks, seenPaths, ignoreWhatsApp, ignoreRecordings, minDurationSec);
                        }
                    }
                } catch (Exception ignored) {}

                if (includeDownloads) {
                    File downloadPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (downloadPublic != null && downloadPublic.exists()) {
                        scanDirRecursive(downloadPublic, tracks, seenPaths, ignoreWhatsApp, ignoreRecordings, minDurationSec);
                    }
                }

                // Consulta al MediaStore del sistema operativo Android con filtros estrictos
                try {
                    ContentResolver resolver = getContext().getContentResolver();
                    Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    
                    StringBuilder sel = new StringBuilder(MediaStore.Audio.Media.IS_MUSIC + " != 0");
                    if (ignoreWhatsApp) {
                        sel.append(" AND ").append(MediaStore.Audio.Media.DATA).append(" NOT LIKE '%whatsapp%'")
                           .append(" AND ").append(MediaStore.Audio.Media.DATA).append(" NOT LIKE '%WhatsApp%'")
                           .append(" AND ").append(MediaStore.Audio.Media.DATA).append(" NOT LIKE '%telegram%'")
                           .append(" AND ").append(MediaStore.Audio.Media.DATA).append(" NOT LIKE '%Telegram%'");
                    }
                    if (ignoreRecordings) {
                        sel.append(" AND ").append(MediaStore.Audio.Media.DATA).append(" NOT LIKE '%sound_recorder%'")
                           .append(" AND ").append(MediaStore.Audio.Media.DATA).append(" NOT LIKE '%Recordings%'")
                           .append(" AND ").append(MediaStore.Audio.Media.DATA).append(" NOT LIKE '%CallRecord%'");
                    }
                    if (minDurationSec > 0) {
                        sel.append(" AND ").append(MediaStore.Audio.Media.DURATION).append(" >= ").append(minDurationSec * 1000L);
                    }

                    String[] projection = {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.SIZE
                    };
                    Cursor cursor = resolver.query(uri, projection, sel.toString(), null, MediaStore.Audio.Media.TITLE + " ASC");
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

                            long durationMs = cursor.getLong(durationCol);
                            long sizeBytes = cursor.getLong(sizeCol);
                            if (shouldIgnoreAudio(path, sizeBytes, durationMs, ignoreWhatsApp, ignoreRecordings, minDurationSec)) {
                                continue;
                            }

                            File f = new File(path);
                            if (!f.exists()) continue;
                            seenPaths.add(path);

                            String title = cursor.getString(titleCol);
                            String artist = cursor.getString(artistCol);
                            String album = cursor.getString(albumCol);

                        if (title == null || title.isEmpty()) {
                            title = f.getName().replaceFirst("[.][^.]+$", "");
                        }
                        if (artist == null || artist.isEmpty() || "<unknown>".equalsIgnoreCase(artist)) {
                            artist = f.getParentFile() != null ? f.getParentFile().getName() : "Desconocido";
                        }

                        double sizeMb = Math.round((sizeBytes / (1024.0 * 1024.0)) * 100.0) / 100.0;
                        String ext = path.contains(".") ? path.substring(path.lastIndexOf('.')) : "";

                        File parent = f.getParentFile();
                        String coverPath = "";
                        File trackCover = getCoverFileForTrack(f);
                        if (trackCover != null && trackCover.exists() && trackCover.length() > 0) {
                            coverPath = trackCover.getAbsolutePath();
                        } else {
                            File albumCover = parent != null ? getCoverFileForAlbum(parent) : null;
                            if (albumCover != null && albumCover.exists() && albumCover.length() > 0) {
                                coverPath = albumCover.getAbsolutePath();
                            } else if (trackCover != null) {
                                try {
                                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                                    mmr.setDataSource(f.getAbsolutePath());
                                    byte[] art = mmr.getEmbeddedPicture();
                                    mmr.release();
                                    if (art != null && art.length > 0) {
                                        try (FileOutputStream fos = new FileOutputStream(trackCover)) {
                                            fos.write(art);
                                        }
                                        coverPath = trackCover.getAbsolutePath();
                                        if (albumCover != null && (!albumCover.exists() || albumCover.length() == 0)) {
                                            copyFile(trackCover, albumCover);
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }

                        String lrcPath = "";
                        int dotIdx = path.lastIndexOf('.');
                        if (dotIdx > 0) {
                            File lrcFile = new File(path.substring(0, dotIdx) + ".lrc");
                            if (lrcFile.exists() && lrcFile.length() > 0) {
                                lrcPath = lrcFile.getAbsolutePath();
                            }
                        }

                        JSObject item = new JSObject();
                        item.put("title", title);
                        item.put("artist", artist);
                        item.put("album", album != null ? album : "");
                        item.put("folder", parent != null ? parent.getName() : "Música");
                        item.put("path", path);
                        item.put("size_mb", sizeMb);
                        item.put("duration", durationMs / 1000.0);
                        item.put("coverPath", coverPath);
                        item.put("lrcPath", lrcPath);
                        item.put("hasLyrics", !lrcPath.isEmpty());
                        item.put("ext", ext);
                        item.put("lastModified", f.lastModified());
                        tracks.put(item);
                    }
                    cursor.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "Consulta MediaStore omitida o sin permiso: " + e.getMessage());
            }
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
            File parentDir = f.getParentFile();
            if (f.exists()) {
                f.delete();
            }

            // Eliminar carátula dedicada en .covers/
            File trackCover = getCoverFileForTrack(f);
            if (trackCover != null && trackCover.exists()) {
                trackCover.delete();
            }

            // Eliminar imagen suelta antigua si existía en la carpeta
            if (parentDir != null) {
                String bName = f.getName().replaceFirst("[.][^.]+$", "");
                File oldTrackCover = new File(parentDir, bName + ".jpg");
                if (oldTrackCover.exists()) {
                    oldTrackCover.delete();
                    purgeImageFromGallery(oldTrackCover);
                }
            }

            // Eliminar archivo .lrc asociado si existe
            String lrcPath = "";
            int dotIdx = path.lastIndexOf('.');
            if (dotIdx > 0) {
                lrcPath = path.substring(0, dotIdx) + ".lrc";
                File lrcFile = new File(lrcPath);
                if (lrcFile.exists()) {
                    lrcFile.delete();
                }
            }

            // Purgar de MediaStore para evitar que quede como pista fantasma
            try {
                ContentResolver resolver = getContext().getContentResolver();
                Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                resolver.delete(uri, MediaStore.Audio.Media.DATA + " = ?", new String[]{path});
                if (!lrcPath.isEmpty()) {
                    resolver.delete(uri, MediaStore.Audio.Media.DATA + " = ?", new String[]{lrcPath});
                }
            } catch (Exception ex) {
                Log.w(TAG, "Error purgando de MediaStore: " + ex.getMessage());
            }

            if (!lrcPath.isEmpty()) {
                MediaScannerConnection.scanFile(getContext(), new String[]{path, lrcPath}, null, null);
            } else {
                MediaScannerConnection.scanFile(getContext(), new String[]{path}, null, null);
            }

            // Limpiar carpetas huérfanas (que hayan quedado sin canciones de audio, solo con cover)
            cleanOrphanedDirectories(parentDir);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void updateTrackCover(PluginCall call) {
        String path = call.getString("path", "");
        String artist = call.getString("artist", "");
        String title = call.getString("title", "");
        String album = call.getString("album", "");
        String coverUrl = call.getString("coverUrl", "");

        if (path == null || path.trim().isEmpty()) {
            call.reject("Ruta requerida");
            return;
        }

        executor.execute(() -> {
            File tempDir = null;
            try {
                File target = new File(path);
                if (!target.exists()) {
                    call.reject("Archivo o carpeta no encontrado: " + path);
                    return;
                }

                File cacheBase = getContext().getCacheDir();
                tempDir = new File(cacheBase, "ytm_cov_" + System.currentTimeMillis());
                if (!tempDir.exists()) {
                    tempDir.mkdirs();
                }

                File coverFile = null;
                if (coverUrl != null && !coverUrl.trim().isEmpty() && (coverUrl.startsWith("http://") || coverUrl.startsWith("https://"))) {
                    coverFile = new File(tempDir, "cover.jpg");
                    downloadImage(coverUrl.trim(), coverFile);
                }

                if (coverFile == null || !coverFile.exists() || coverFile.length() == 0) {
                    call.reject("No se pudo descargar la carátula");
                    return;
                }

                if (target.isDirectory()) {
                    // Guardar en la carpeta oculta .covers/ con .nomedia
                    File albumCover = getCoverFileForAlbum(target);
                    if (albumCover != null) {
                        copyFile(coverFile, albumCover);
                    }

                    // Borrar cualquier imagen suelta visible que pudiera existir en la carpeta
                    File[] looseImgs = target.listFiles((d, name) -> {
                        String n = name.toLowerCase();
                        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
                    });
                    if (looseImgs != null) {
                        for (File limg : looseImgs) {
                            limg.delete();
                            purgeImageFromGallery(limg);
                        }
                    }

                    // Incrustar la carátula en cada archivo de audio de la carpeta
                    File[] audioFiles = target.listFiles((d, name) -> {
                        String n = name.toLowerCase();
                        return n.endsWith(".opus") || n.endsWith(".m4a") || n.endsWith(".mp3") 
                            || n.endsWith(".flac") || n.endsWith(".ogg") || n.endsWith(".wav");
                    });

                    if (audioFiles != null) {
                        for (File af : audioFiles) {
                            File tempAudio = new File(tempDir, af.getName());
                            copyFile(af, tempAudio);
                            tagAudioFile(tempAudio, artist, "", album, coverFile);
                            copyFile(tempAudio, af);
                            tempAudio.delete();

                            File tc = getCoverFileForTrack(af);
                            if (tc != null) {
                                copyFile(coverFile, tc);
                            }

                            String bName = af.getName().replaceFirst("[.][^.]+$", "");
                            File oldTrackCover = new File(target, bName + ".jpg");
                            if (oldTrackCover.exists()) {
                                oldTrackCover.delete();
                                purgeImageFromGallery(oldTrackCover);
                            }

                            MediaScannerConnection.scanFile(getContext(), new String[]{af.getAbsolutePath()}, null, null);
                        }
                    }

                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("path", path);
                    ret.put("coverPath", albumCover != null ? albumCover.getAbsolutePath() : "");
                    call.resolve(ret);
                    return;
                } else {
                    // Actualizar una canción individual
                    File tempAudio = new File(tempDir, target.getName());
                    copyFile(target, tempAudio);
                    tagAudioFile(tempAudio, artist, title, album, coverFile);
                    copyFile(tempAudio, target);

                    // Guardar carátula exclusivamente en la carpeta oculta .covers/
                    File trackCover = getCoverFileForTrack(target);
                    if (trackCover != null) {
                        copyFile(coverFile, trackCover);
                    }
                    String newCoverPath = trackCover != null ? trackCover.getAbsolutePath() : "";

                    File parent = target.getParentFile();
                    if (parent != null && parent.exists()) {
                        if (!parent.getName().equalsIgnoreCase("Sencillos")) {
                            File albumCover = getCoverFileForAlbum(parent);
                            if (albumCover != null) {
                                copyFile(coverFile, albumCover);
                            }
                        }

                        // Borrar cualquier imagen suelta anterior en la carpeta de música
                        String bName = target.getName().replaceFirst("[.][^.]+$", "");
                        File oldTrackCover = new File(parent, bName + ".jpg");
                        if (oldTrackCover.exists()) {
                            oldTrackCover.delete();
                            purgeImageFromGallery(oldTrackCover);
                        }
                        File oldCover = new File(parent, "cover.jpg");
                        if (oldCover.exists()) {
                            oldCover.delete();
                            purgeImageFromGallery(oldCover);
                        }
                        File oldFolder = new File(parent, "folder.jpg");
                        if (oldFolder.exists()) {
                            oldFolder.delete();
                            purgeImageFromGallery(oldFolder);
                        }
                    }

                    MediaScannerConnection.scanFile(getContext(), new String[]{target.getAbsolutePath()}, null, null);

                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("path", path);
                    ret.put("coverPath", newCoverPath);
                    call.resolve(ret);
                    return;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error en updateTrackCover: " + e.getMessage(), e);
                call.reject("Error actualizando carátula: " + e.getMessage(), e);
            } finally {
                if (tempDir != null && tempDir.exists()) {
                    deleteDirectoryRecursive(tempDir);
                }
            }
        });
    }

    @PluginMethod
    public void getLyrics(PluginCall call) {
        String path = call.getString("path", "");
        String artist = call.getString("artist", "");
        String title = call.getString("title", "");

        executor.execute(() -> {
            try {
                // 1. Si se proveyó una ruta en el almacenamiento, verificar si ya existe archivo .lrc local
                if (path != null && !path.trim().isEmpty()) {
                    int dotIdx = path.lastIndexOf('.');
                    if (dotIdx > 0) {
                        File lrcFile = new File(path.substring(0, dotIdx) + ".lrc");
                        if (lrcFile.exists() && lrcFile.length() > 0) {
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(lrcFile), StandardCharsets.UTF_8))) {
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    sb.append(line).append("\n");
                                }
                                String content = sb.toString().trim();
                                if (!content.isEmpty()) {
                                    JSObject ret = new JSObject();
                                    ret.put("syncedLyrics", content.contains("[0") ? content : "");
                                    ret.put("plainLyrics", content);
                                    ret.put("source", "local");
                                    call.resolve(ret);
                                    return;
                                }
                            }
                        }
                    }
                }

                // 2. Buscar en línea con LRCLIB
                JSObject online = fetchLyricsLRCLIB(artist, title);
                if (online != null) {
                    // Guardar en disco para próximas reproducciones 100% offline
                    if (path != null && !path.trim().isEmpty()) {
                        int dotIdx = path.lastIndexOf('.');
                        if (dotIdx > 0) {
                            File lrcFile = new File(path.substring(0, dotIdx) + ".lrc");
                            String toSave = online.optString("syncedLyrics", "");
                            if (toSave.isEmpty()) toSave = online.optString("plainLyrics", "");
                            if (!toSave.isEmpty() && lrcFile.getParentFile() != null && lrcFile.getParentFile().exists()) {
                                try (FileOutputStream fos = new FileOutputStream(lrcFile)) {
                                    fos.write(toSave.getBytes(StandardCharsets.UTF_8));
                                    MediaScannerConnection.scanFile(getContext(), new String[]{lrcFile.getAbsolutePath()}, null, null);
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    call.resolve(online);
                    return;
                }

                JSObject notFound = new JSObject();
                notFound.put("syncedLyrics", "");
                notFound.put("plainLyrics", "");
                notFound.put("notFound", true);
                call.resolve(notFound);
            } catch (Exception e) {
                Log.e(TAG, "Error en getLyrics: " + e.getMessage(), e);
                JSObject err = new JSObject();
                err.put("error", e.getMessage());
                call.resolve(err);
            }
        });
    }

    public JSObject fetchLyricsLRCLIB(String artist, String title) {
        try {
            String cleanA = cleanTerm(artist);
            String cleanT = cleanTerm(title);
            if (cleanT.isEmpty()) return null;

            // 1. Intento por track_name + artist_name exacto
            String url1 = "https://lrclib.net/api/get?artist_name=" + URLEncoder.encode(cleanA, "UTF-8") 
                        + "&track_name=" + URLEncoder.encode(cleanT, "UTF-8");
            String json1 = httpGet(url1);
            if (json1 != null && !json1.trim().isEmpty() && !json1.contains("\"error\":")) {
                JSONObject obj = new JSONObject(json1);
                String synced = obj.optString("syncedLyrics", "");
                String plain = obj.optString("plainLyrics", "");
                if (!synced.isEmpty() || !plain.isEmpty()) {
                    JSObject ret = new JSObject();
                    ret.put("syncedLyrics", synced);
                    ret.put("plainLyrics", plain);
                    ret.put("source", "lrclib_get");
                    return ret;
                }
            }

            // 2. Intento por búsqueda general si el endpoint directo no lo encontró
            String query = (cleanA + " " + cleanT).trim();
            String url2 = "https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8");
            String json2 = httpGet(url2);
            if (json2 != null && !json2.trim().isEmpty() && json2.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(json2);
                if (arr.length() > 0) {
                    List<String> tWords = getWords(cleanT);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        String candT = item.optString("trackName", "");
                        String candNorm = normalizeWord(candT);
                        if (tWords.isEmpty() || containsAnyWord(candNorm, tWords)) {
                            String synced = item.optString("syncedLyrics", "");
                            String plain = item.optString("plainLyrics", "");
                            if (!synced.isEmpty() || !plain.isEmpty()) {
                                JSObject ret = new JSObject();
                                ret.put("syncedLyrics", synced);
                                ret.put("plainLyrics", plain);
                                ret.put("source", "lrclib_search");
                                return ret;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchLyricsLRCLIB error: " + e.getMessage());
        }
        return null;
    }

    @PluginMethod
    public void resolveUrlMetadata(PluginCall call) {
        String rawUrl = call.getString("url", "").trim();
        if (rawUrl.isEmpty()) {
            call.reject("URL requerida");
            return;
        }

        executor.execute(() -> {
            try {
                JSObject meta = getMetadataForUrl(rawUrl);
                call.resolve(meta);
            } catch (Exception e) {
                Log.e(TAG, "Error resolviendo metadata de URL: " + e.getMessage(), e);
                call.reject("Error resolviendo metadatos: " + e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void searchCover(PluginCall call) {
        String artist = call.getString("artist", "");
        String title = call.getString("title", "");
        String album = call.getString("album", "");
        boolean isAlbum = call.getBoolean("isAlbum", false);

        executor.execute(() -> {
            String cover = findBestCoverJava(artist, title, album, isAlbum);
            JSObject ret = new JSObject();
            ret.put("cover", cover);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void searchCoverCandidates(PluginCall call) {
        String artist = call.getString("artist", "");
        String title = call.getString("title", "");
        String album = call.getString("album", "");

        executor.execute(() -> {
            JSArray candidates = new JSArray();
            Set<String> seenCovers = new HashSet<>();

            // 1. Apple Music / iTunes
            try {
                String q = cleanTerm(artist + " " + title).trim();
                if (!q.isEmpty()) {
                    String url = "https://itunes.apple.com/search?term=" + URLEncoder.encode(q, "UTF-8") + "&entity=song&limit=6";
                    String json = httpGet(url);
                    if (json != null) {
                        JSONObject obj = new JSONObject(json);
                        JSONArray results = obj.optJSONArray("results");
                        if (results != null) {
                            for (int i = 0; i < results.length(); i++) {
                                JSONObject item = results.getJSONObject(i);
                                String art = item.optString("artworkUrl100", "");
                                if (!art.isEmpty()) {
                                    String highRes = art.replace("/100x100bb.jpg", "/1200x1200bb.jpg")
                                                        .replace("/100x100bb.png", "/1200x1200bb.png")
                                                        .replace("/60x60bb.jpg", "/1200x1200bb.jpg");
                                    if (!seenCovers.contains(highRes)) {
                                        seenCovers.add(highRes);
                                        JSObject cand = new JSObject();
                                        cand.put("title", item.optString("trackName", ""));
                                        cand.put("artist", item.optString("artistName", ""));
                                        cand.put("album", item.optString("collectionName", ""));
                                        cand.put("cover", highRes);
                                        cand.put("source", "Apple Music");
                                        candidates.put(cand);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "searchCoverCandidates itunes error: " + e.getMessage());
            }

            // 2. Deezer
            try {
                String q = cleanTerm(artist + " " + title).trim();
                if (!q.isEmpty()) {
                    String url = "https://api.deezer.com/search/track?q=" + URLEncoder.encode(q, "UTF-8") + "&limit=6";
                    String json = httpGet(url);
                    if (json != null) {
                        JSONObject obj = new JSONObject(json);
                        JSONArray data = obj.optJSONArray("data");
                        if (data != null) {
                            for (int i = 0; i < data.length(); i++) {
                                JSONObject item = data.getJSONObject(i);
                                String cover = item.optString("cover_xl", "");
                                if (cover.isEmpty()) cover = item.optString("cover_big", "");
                                if (cover.isEmpty()) {
                                    JSONObject alb = item.optJSONObject("album");
                                    if (alb != null) {
                                        cover = alb.optString("cover_xl", alb.optString("cover_big", ""));
                                    }
                                }
                                if (!cover.isEmpty() && !seenCovers.contains(cover)) {
                                    seenCovers.add(cover);
                                    JSObject cand = new JSObject();
                                    cand.put("title", item.optString("title", ""));
                                    JSONObject artObj = item.optJSONObject("artist");
                                    cand.put("artist", artObj != null ? artObj.optString("name", "") : "");
                                    JSONObject albObj = item.optJSONObject("album");
                                    cand.put("album", albObj != null ? albObj.optString("title", "") : "");
                                    cand.put("cover", cover);
                                    cand.put("source", "Deezer");
                                    candidates.put(cand);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "searchCoverCandidates deezer error: " + e.getMessage());
            }

            JSObject ret = new JSObject();
            ret.put("candidates", candidates);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void getSearchSuggestions(PluginCall call) {
        String q = call.getString("query", "").trim();
        if (q.isEmpty()) {
            JSObject ret = new JSObject();
            ret.put("suggestions", new JSArray());
            call.resolve(ret);
            return;
        }

        executor.execute(() -> {
            JSArray arr = new JSArray();
            try {
                String url = "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&q=" + URLEncoder.encode(q, "UTF-8");
                String raw = httpGet(url);
                if (raw != null) {
                    int start = raw.indexOf('(');
                    int end = raw.lastIndexOf(')');
                    if (start >= 0 && end > start) {
                        String jsonStr = raw.substring(start + 1, end);
                        JSONArray root = new JSONArray(jsonStr);
                        if (root.length() > 1) {
                            JSONArray items = root.getJSONArray(1);
                            for (int i = 0; i < Math.min(items.length(), 8); i++) {
                                JSONArray it = items.getJSONArray(i);
                                String s = it.getString(0);
                                if (s != null && !s.trim().isEmpty()) {
                                    arr.put(s.trim());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "getSearchSuggestions error: " + e.getMessage());
            }
            JSObject ret = new JSObject();
            ret.put("suggestions", arr);
            call.resolve(ret);
        });
    }

    public JSObject getMetadataForUrl(String rawUrl) {
        JSObject ret = new JSObject();
        String videoId = extractVideoId(rawUrl);
        String cleanUrl = rawUrl != null ? rawUrl.trim() : "";
        Matcher urlMatcher = Pattern.compile("https?://[^\\s]+").matcher(cleanUrl);
        if (urlMatcher.find()) {
            cleanUrl = urlMatcher.group(0);
        }
        cleanUrl = cleanUrl.replace("music.youtube.com", "www.youtube.com");

        String rawTitle = "";
        String rawAuthor = "";
        String thumbnail = "";
        double duration = 0.0;

        // 1. Intentar con YouTube oEmbed (super rápido, ~100ms)
        if (!videoId.isEmpty()) {
            try {
                String oembedUrl = "https://www.youtube.com/oembed?url=" + URLEncoder.encode("https://www.youtube.com/watch?v=" + videoId, "UTF-8") + "&format=json";
                String jsonStr = httpGet(oembedUrl);
                if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                    JSONObject oembed = new JSONObject(jsonStr);
                    rawTitle = oembed.optString("title", "");
                    rawAuthor = oembed.optString("author_name", "");
                    thumbnail = oembed.optString("thumbnail_url", "");
                }
            } catch (Exception e) {
                Log.w(TAG, "oEmbed fallo: " + e.getMessage());
            }
        }

        // 2. Si oEmbed no funcionó, consultar con yt-dlp dump-json
        if (rawTitle.isEmpty()) {
            try {
                if (!isInitialized) {
                    FFmpeg.getInstance().init(getContext());
                    YoutubeDL.getInstance().init(getContext());
                    isInitialized = true;
                }
                YoutubeDLRequest request = new YoutubeDLRequest(cleanUrl);
                request.addOption("--dump-single-json");
                request.addOption("--flat-playlist");
                request.addOption("--no-warnings");
                request.addOption("--ignore-errors");
                File cookieFile = getCookiesFile();
                if (cookieFile.exists() && cookieFile.length() > 50) {
                    request.addOption("--cookies", cookieFile.getAbsolutePath());
                }
                YoutubeDLResponse response = YoutubeDL.getInstance().execute(request);
                String out = response.getOut();
                if (out != null && !out.trim().isEmpty()) {
                    JSONObject obj = new JSONObject(out);
                    rawTitle = obj.optString("title", "");
                    rawAuthor = obj.optString("uploader", obj.optString("channel", ""));
                    duration = obj.optDouble("duration", 0.0);
                    thumbnail = obj.optString("thumbnail", "");
                    String track = obj.optString("track", "");
                    String artist = obj.optString("artist", "");
                    if (!track.isEmpty()) rawTitle = track;
                    if (!artist.isEmpty()) rawAuthor = artist;
                }
            } catch (Exception e) {
                Log.w(TAG, "yt-dlp dump-json fallo: " + e.getMessage());
            }
        }

        if (rawTitle.isEmpty()) {
            rawTitle = "Tema YouTube";
        }

        // 3. Limpiar y separar Artista y Título
        String cleaned = cleanYouTubeTitle(rawTitle);
        String[] parsed = parseArtistAndTitle(cleaned, rawAuthor);
        String artistCandidate = parsed[0];
        String titleCandidate = parsed[1];
        String albumCandidate = "Sencillos";

        // 4. Buscar coincidencia exacta en Apple Music / iTunes
        JSObject officialMatch = findOfficialTrackMeta(artistCandidate, titleCandidate);
        String finalTitle = officialMatch != null ? officialMatch.getString("title") : titleCandidate;
        String finalArtist = officialMatch != null ? officialMatch.getString("artist") : (artistCandidate.isEmpty() ? "YouTube" : artistCandidate);
        String finalAlbum = officialMatch != null ? officialMatch.getString("album") : albumCandidate;
        String finalCover = officialMatch != null ? officialMatch.getString("cover") : "";
        if (officialMatch != null && duration <= 0) {
            duration = officialMatch.optDouble("duration", 0.0);
        }

        if (finalCover == null || finalCover.isEmpty()) {
            // Fallback: Buscar carátula en Deezer
            String deezerCover = searchDeezerJava(finalArtist, finalTitle, false);
            if (deezerCover != null && !deezerCover.isEmpty()) {
                finalCover = deezerCover;
            } else if (!thumbnail.isEmpty()) {
                finalCover = thumbnail;
            } else if (!videoId.isEmpty()) {
                finalCover = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
            }
        }

        ret.put("id", !videoId.isEmpty() ? videoId : "yt_" + System.currentTimeMillis());
        ret.put("title", finalTitle);
        ret.put("artist", finalArtist);
        ret.put("album", finalAlbum);
        ret.put("cover", finalCover);
        ret.put("duration", duration);
        ret.put("duration_str", formatDuration(duration));
        ret.put("url", cleanUrl);
        ret.put("source", "url_resolved");
        return ret;
    }

    private String extractVideoId(String url) {
        if (url == null) return "";
        Matcher m = Pattern.compile("(?:v=|youtu\\.be/|shorts/)([a-zA-Z0-9_-]{11})").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    private String cleanYouTubeTitle(String rawTitle) {
        if (rawTitle == null) return "";
        String t = rawTitle.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
        t = t.replaceAll("(?i)\\s*\\|\\s*(official|video|audio|music video|letra|lyric|en vivo|live|remastered).*$", "");
        t = t.replaceAll("(?i)[\\(\\[\\{]\\s*(official\\s*(music\\s*)?video|video\\s*oficial|audio\\s*oficial|official\\s*audio|lyric\\s*video|visualizer|video\\s*clip|en\\s*vivo|live|4k\\s*remaster|letra|remix|hd|4k|mv)[^\\)\\]\\}]*[\\)\\]\\}]", "");
        t = t.replaceAll("[\"״'”]+", "");
        return t.trim();
    }

    private String[] parseArtistAndTitle(String cleanTitle, String uploader) {
        String artist = "";
        String title = cleanTitle;
        String[] delimiters = new String[]{" - ", " \u2013 ", " \u2014 ", " || ", " | ", " : ", " • ", " · "};
        for (String d : delimiters) {
            if (cleanTitle.contains(d)) {
                String[] parts = cleanTitle.split(Pattern.quote(d), 2);
                String p0 = parts[0].trim();
                String p1 = parts[1].trim();
                if (!p0.isEmpty() && !p1.isEmpty()) {
                    artist = p0;
                    title = p1;
                    break;
                }
            }
        }
        if (artist.isEmpty()) {
            artist = uploader != null ? uploader.replace(" - Topic", "").trim() : "";
            artist = artist.replaceAll("(?i)(vevo|official|channel)", "").trim();
        }
        return new String[]{artist, title};
    }

    private String normalizeWord(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9áéíóúñ]", "");
    }

    private List<String> getWords(String s) {
        List<String> list = new ArrayList<>();
        if (s == null) return list;
        for (String w : s.split("\\s+")) {
            String norm = normalizeWord(w);
            if (norm.length() > 2) {
                list.add(norm);
            }
        }
        return list;
    }

    private boolean containsAllWords(String target, List<String> words) {
        if (target == null || words == null || words.isEmpty()) return false;
        for (String w : words) {
            if (!target.contains(w)) return false;
        }
        return true;
    }

    private boolean containsAnyWord(String target, List<String> words) {
        if (target == null || words == null || words.isEmpty()) return false;
        for (String w : words) {
            if (target.contains(w)) return true;
        }
        return false;
    }

    private double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        String n1 = normalizeWord(s1);
        String n2 = normalizeWord(s2);
        if (n1.isEmpty() || n2.isEmpty()) return 0.0;
        if (n1.equals(n2)) return 1.0;
        if (n1.contains(n2) || n2.contains(n1)) {
            return (double) Math.min(n1.length(), n2.length()) / Math.max(n1.length(), n2.length());
        }
        List<String> w1 = getWords(s1);
        List<String> w2 = getWords(s2);
        if (w1.isEmpty() || w2.isEmpty()) return 0.0;
        int common = 0;
        for (String w : w1) {
            if (w2.contains(w)) common++;
        }
        return (2.0 * common) / (w1.size() + w2.size());
    }

    private boolean isArtistMatch(String qArtist, String candArtist) {
        if (qArtist == null || qArtist.trim().isEmpty()) return true;
        String cleanQ = qArtist.toLowerCase().replaceAll("(?i)(topic|vevo|official|audio|channel)", "").trim();
        if (cleanQ.isEmpty() || cleanQ.equalsIgnoreCase("youtube music") || cleanQ.equalsIgnoreCase("desconocido") || cleanQ.equalsIgnoreCase("biblioteca")) return true;
        
        String normQ = normalizeWord(cleanQ);
        String normC = normalizeWord(candArtist);
        if (normQ.contains(normC) || normC.contains(normQ)) return true;
        
        List<String> qWords = getWords(cleanQ);
        List<String> cWords = getWords(candArtist);
        for (String qw : qWords) {
            if (qw.length() >= 3 && cWords.contains(qw)) return true;
        }
        return false;
    }

    private JSObject findOfficialTrackMeta(String artist, String title) {
        try {
            String q = cleanTerm(artist + " " + title).trim();
            if (q.isEmpty()) return null;

            // 1. Apple Music / iTunes
            String url = "https://itunes.apple.com/search?term=" + URLEncoder.encode(q, "UTF-8") + "&entity=song&limit=5";
            String json = httpGet(url);
            if (json != null && !json.trim().isEmpty()) {
                JSONObject root = new JSONObject(json);
                JSONArray results = root.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    List<String> tWords = getWords(title);

                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.getJSONObject(i);
                        String candTitle = item.optString("trackName", "");
                        String candArtist = item.optString("artistName", "");

                        if (!isArtistMatch(artist, candArtist)) continue;

                        double titleSim = calculateSimilarity(title, candTitle);
                        if (titleSim >= 0.50 || (!tWords.isEmpty() && containsAllWords(normalizeWord(candTitle), tWords))) {
                            JSObject res = new JSObject();
                            res.put("title", candTitle);
                            res.put("artist", candArtist);
                            res.put("album", item.optString("collectionName", "Sencillos"));
                            String art = item.optString("artworkUrl100", "");
                            if (!art.isEmpty()) {
                                art = art.replace("/100x100bb.jpg", "/1200x1200bb.jpg")
                                         .replace("/100x100bb.png", "/1200x1200bb.png")
                                         .replace("/60x60bb.jpg", "/1200x1200bb.jpg");
                            }
                            res.put("cover", art);
                            res.put("duration", item.optDouble("trackTimeMillis", 0.0) / 1000.0);
                            return res;
                        }
                    }
                }
            }

            // 2. Deezer API (si iTunes no validó)
            String deezerUrl = "https://api.deezer.com/search/track?q=" + URLEncoder.encode(q, "UTF-8") + "&limit=5";
            String dJson = httpGet(deezerUrl);
            if (dJson != null && !dJson.trim().isEmpty()) {
                JSONObject dRoot = new JSONObject(dJson);
                JSONArray data = dRoot.optJSONArray("data");
                if (data != null && data.length() > 0) {
                    List<String> tWords = getWords(title);
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.getJSONObject(i);
                        String candTitle = item.optString("title", "");
                        JSONObject artObj = item.optJSONObject("artist");
                        String candArtist = artObj != null ? artObj.optString("name", "") : "";

                        if (!isArtistMatch(artist, candArtist)) continue;

                        double titleSim = calculateSimilarity(title, candTitle);
                        if (titleSim >= 0.50 || (!tWords.isEmpty() && containsAllWords(normalizeWord(candTitle), tWords))) {
                            JSObject res = new JSObject();
                            res.put("title", candTitle);
                            res.put("artist", !candArtist.isEmpty() ? candArtist : artist);
                            JSONObject albObj = item.optJSONObject("album");
                            res.put("album", albObj != null ? albObj.optString("title", "Sencillos") : "Sencillos");
                            String cover = "";
                            if (albObj != null) {
                                cover = albObj.optString("cover_xl", albObj.optString("cover_big", ""));
                            }
                            if (cover.isEmpty()) {
                                cover = item.optString("cover_xl", item.optString("cover_big", ""));
                            }
                            res.put("cover", cover);
                            res.put("duration", item.optDouble("duration", 0.0));
                            return res;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findOfficialTrackMeta error: " + e.getMessage());
        }
        return null;
    }

    private String findBestCoverJava(String artist, String title, String album, boolean isAlbum) {
        String queryTarget = isAlbum ? (album != null && !album.isEmpty() ? album : title) : title;
        if (queryTarget == null) queryTarget = "";

        // 1. Apple / iTunes 1200x1200
        String itunes = searchItunesJava(artist, queryTarget, isAlbum);
        if (itunes != null && !itunes.isEmpty()) return itunes;

        if (isAlbum && title != null && !title.isEmpty()) {
            itunes = searchItunesJava(artist, title, false);
            if (itunes != null && !itunes.isEmpty()) return itunes;
        }

        // 2. Deezer 1000x1000
        String deezer = searchDeezerJava(artist, queryTarget, isAlbum);
        if (deezer != null && !deezer.isEmpty()) return deezer;

        if (isAlbum && title != null && !title.isEmpty()) {
            deezer = searchDeezerJava(artist, title, false);
            if (deezer != null && !deezer.isEmpty()) return deezer;
        }

        // 3. Fallback solo con queryTarget si artista falló
        if (!artist.isEmpty()) {
            itunes = searchItunesJava("", queryTarget, isAlbum);
            if (itunes != null && !itunes.isEmpty()) return itunes;
            deezer = searchDeezerJava("", queryTarget, isAlbum);
            if (deezer != null && !deezer.isEmpty()) return deezer;
        }

        return null;
    }

    private String cleanTerm(String str) {
        if (str == null) return "";
        return str.replaceAll("(?i)\\s*\\(?(feat\\.?|ft\\.?|with|prod\\.?)[^\\)]*\\)?", "")
                  .replaceAll("[\\[\\]\\(\\)\\{\\}]", " ")
                  .replaceAll("[_\\-#|/\\\\]+", " ")
                  .replaceAll("^[0-9]+[\\s._-]+", "")
                  .replaceAll("\\s+", " ")
                  .trim();
    }

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0");
            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "httpGet fallo para " + urlStr + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private String searchItunesJava(String artist, String title, boolean isAlbum) {
        try {
            String q = cleanTerm(artist + " " + title).trim();
            if (q.isEmpty()) return null;
            String entity = isAlbum ? "album" : "song";
            String url = "https://itunes.apple.com/search?term=" + URLEncoder.encode(q, "UTF-8") + "&entity=" + entity + "&limit=5";
            String json = httpGet(url);
            if (json != null) {
                JSONObject obj = new JSONObject(json);
                JSONArray results = obj.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    List<String> tWords = getWords(title);
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.getJSONObject(i);
                        String cand = isAlbum ? item.optString("collectionName", "") : item.optString("trackName", "");
                        String candArtist = item.optString("artistName", "");

                        if (!isArtistMatch(artist, candArtist)) continue;

                        double titleSim = calculateSimilarity(title, cand);
                        if (titleSim >= 0.50 || (!tWords.isEmpty() && containsAllWords(normalizeWord(cand), tWords))) {
                            String art = item.optString("artworkUrl100", "");
                            if (!art.isEmpty()) {
                                return art.replace("/100x100bb.jpg", "/1200x1200bb.jpg")
                                          .replace("/100x100bb.png", "/1200x1200bb.png")
                                          .replace("/60x60bb.jpg", "/1200x1200bb.jpg");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "searchItunesJava error: " + e.getMessage());
        }
        return null;
    }

    private String searchDeezerJava(String artist, String title, boolean isAlbum) {
        try {
            String q = cleanTerm(artist + " " + title).trim();
            if (q.isEmpty()) return null;
            String type = isAlbum ? "album" : "track";
            String url = "https://api.deezer.com/search/" + type + "?q=" + URLEncoder.encode(q, "UTF-8") + "&limit=5";
            String json = httpGet(url);
            if (json != null) {
                JSONObject obj = new JSONObject(json);
                JSONArray data = obj.optJSONArray("data");
                if (data != null && data.length() > 0) {
                    List<String> tWords = getWords(title);
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.getJSONObject(i);
                        String cand = item.optString("title", "");
                        JSONObject artObj = item.optJSONObject("artist");
                        String candArtist = artObj != null ? artObj.optString("name", "") : "";

                        if (!isArtistMatch(artist, candArtist)) continue;

                        double titleSim = calculateSimilarity(title, cand);
                        if (titleSim >= 0.50 || (!tWords.isEmpty() && containsAllWords(normalizeWord(cand), tWords))) {
                            String cover = item.optString("cover_xl", "");
                            if (cover.isEmpty()) cover = item.optString("cover_big", "");
                            if (cover.isEmpty()) {
                                JSONObject alb = item.optJSONObject("album");
                                if (alb != null) {
                                    cover = alb.optString("cover_xl", alb.optString("cover_big", ""));
                                }
                            }
                            if (!cover.isEmpty()) return cover;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "searchDeezerJava error: " + e.getMessage());
        }
        return null;
    }

    private void scanDirRecursive(File dir, JSArray tracks, Set<String> seenPaths) {
        scanDirRecursive(dir, tracks, seenPaths, true, true, 30);
    }

    private void scanDirRecursive(File dir, JSArray tracks, Set<String> seenPaths, boolean ignoreWhatsApp, boolean ignoreRecordings, int minDurationSec) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        for (File f : files) {
            if (f.isDirectory()) {
                String dName = f.getName().toLowerCase();
                if (dName.startsWith(".")) continue;
                if (dName.equals("android") && !f.getAbsolutePath().contains("Music")) continue;
                if (ignoreWhatsApp && (dName.contains("whatsapp") || dName.contains("telegram") || dName.contains("voice note"))) {
                    continue;
                }
                if (ignoreRecordings && (dName.contains("sound_recorder") || dName.contains("recordings") || dName.contains("call_rec") || dName.contains("callrec"))) {
                    continue;
                }
                scanDirRecursive(f, tracks, seenPaths, ignoreWhatsApp, ignoreRecordings, minDurationSec);
            } else {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".opus") || name.endsWith(".m4a") || name.endsWith(".mp3") 
                    || name.endsWith(".flac") || name.endsWith(".ogg") || name.endsWith(".wav")
                    || name.endsWith(".aac") || name.endsWith(".webm")) {
                    
                    String path = f.getAbsolutePath();
                    if (seenPaths.contains(path)) continue;

                    if (shouldIgnoreAudio(path, f.length(), 0, ignoreWhatsApp, ignoreRecordings, minDurationSec)) {
                        continue;
                    }
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
                    
                    String coverPath = "";
                    File trackCover = getCoverFileForTrack(f);
                    if (trackCover != null && trackCover.exists() && trackCover.length() > 0) {
                        coverPath = trackCover.getAbsolutePath();
                    } else {
                        File albumCover = parent != null ? getCoverFileForAlbum(parent) : null;
                        if (albumCover != null && albumCover.exists() && albumCover.length() > 0) {
                            coverPath = albumCover.getAbsolutePath();
                        } else if (trackCover != null) {
                            try {
                                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                                mmr.setDataSource(f.getAbsolutePath());
                                byte[] art = mmr.getEmbeddedPicture();
                                mmr.release();
                                if (art != null && art.length > 0) {
                                    try (FileOutputStream fos = new FileOutputStream(trackCover)) {
                                        fos.write(art);
                                    }
                                    coverPath = trackCover.getAbsolutePath();
                                    if (albumCover != null && (!albumCover.exists() || albumCover.length() == 0)) {
                                        copyFile(trackCover, albumCover);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    String lrcPath = "";
                    int dotIdx = path.lastIndexOf('.');
                    if (dotIdx > 0) {
                        File lrcFile = new File(path.substring(0, dotIdx) + ".lrc");
                        if (lrcFile.exists() && lrcFile.length() > 0) {
                            lrcPath = lrcFile.getAbsolutePath();
                        }
                    }

                    JSObject item = new JSObject();
                    item.put("title", cleanTitle);
                    item.put("artist", artist);
                    item.put("album", album);
                    item.put("folder", album);
                    item.put("path", path);
                    item.put("size_mb", sizeMb);
                    item.put("coverPath", coverPath);
                    item.put("lrcPath", lrcPath);
                    item.put("hasLyrics", !lrcPath.isEmpty());
                    item.put("ext", name.substring(name.lastIndexOf('.')));
                    item.put("lastModified", f.lastModified());
                    tracks.put(item);
                }
            }
        }
    }

    private void scanMediaFiles(File dir) {
        try {
            if (dir == null || !dir.exists() || dir.getName().startsWith(".")) return;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        String name = f.getName().toLowerCase();
                        // ÚNICAMENTE indexar archivos de audio en MediaScanner. NUNCA imágenes.
                        if (name.endsWith(".opus") || name.endsWith(".m4a") || name.endsWith(".mp3") 
                            || name.endsWith(".flac") || name.endsWith(".ogg") || name.endsWith(".wav")) {
                            MediaScannerConnection.scanFile(getContext(), new String[]{f.getAbsolutePath()}, null, null);
                        }
                    } else if (f.isDirectory() && !f.getName().startsWith(".")) {
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

    private String parseSpeed(String line) {
        if (line == null) return "";
        int atIdx = line.indexOf(" at ");
        if (atIdx != -1) {
            int etaIdx = line.indexOf(" ETA ", atIdx);
            if (etaIdx != -1) {
                return line.substring(atIdx + 4, etaIdx).trim();
            } else {
                return line.substring(atIdx + 4).trim();
            }
        }
        return "";
    }

    private void copyFile(File src, File dst) {
        try {
            if (dst.getParentFile() != null && !dst.getParentFile().exists()) {
                dst.getParentFile().mkdirs();
            }
            if (dst.exists()) {
                dst.delete();
            }
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copiando archivo: " + dst.getAbsolutePath() + ": " + e.getMessage());
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

    private boolean hasAudioFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isDirectory()) {
                if (hasAudioFiles(file)) return true;
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".opus") || name.endsWith(".m4a") || name.endsWith(".mp3") 
                    || name.endsWith(".flac") || name.endsWith(".ogg") || name.endsWith(".wav")
                    || name.endsWith(".aac") || name.endsWith(".webm")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void cleanOrphanedDirectories(File startDir) {
        if (startDir == null || !startDir.exists()) return;
        File musicRoot = getMusicDirectory();
        File current = startDir;

        while (current != null && current.exists() && !current.equals(musicRoot)) {
            String currAbs = current.getAbsolutePath();
            String rootAbs = musicRoot.getAbsolutePath();
            if (currAbs.equals(rootAbs) || !currAbs.startsWith(rootAbs)) {
                break;
            }

            // Si este directorio no tiene canciones de audio (quedó huérfano con cover.jpg o vacío)
            if (!hasAudioFiles(current)) {
                File toDelete = current;
                current = current.getParentFile();
                deleteDirectoryRecursive(toDelete);
                try {
                    MediaScannerConnection.scanFile(getContext(), new String[]{toDelete.getAbsolutePath()}, null, null);
                } catch (Exception ignored) {}
            } else {
                break;
            }
        }
    }

    private void cleanAllOrphanedDirectories(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File musicRoot = getMusicDirectory();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    cleanAllOrphanedDirectories(child);
                    if (!child.equals(musicRoot) && !child.getName().startsWith(".") && !hasAudioFiles(child)) {
                        deleteDirectoryRecursive(child);
                        try {
                            MediaScannerConnection.scanFile(getContext(), new String[]{child.getAbsolutePath()}, null, null);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    private synchronized File getCoversDirectory() {
        File musicDir = getMusicDirectory();
        File coversDir = new File(musicDir, ".covers");
        if (!coversDir.exists()) {
            coversDir.mkdirs();
        }
        File nomedia = new File(coversDir, ".nomedia");
        if (!nomedia.exists()) {
            try {
                nomedia.createNewFile();
            } catch (Exception ignored) {}
        }
        return coversDir;
    }

    private String hashString(String input) {
        if (input == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private File getCoverFileForTrack(File audioFile) {
        if (audioFile == null) return null;
        File coversDir = getCoversDirectory();
        String key = hashString(audioFile.getAbsolutePath());
        return new File(coversDir, key + ".jpg");
    }

    private File getCoverFileForAlbum(File albumDir) {
        if (albumDir == null) return null;
        File coversDir = getCoversDirectory();
        String key = "album_" + hashString(albumDir.getAbsolutePath());
        return new File(coversDir, key + ".jpg");
    }

    /**
     * Purga de la base de datos de MediaStore y de la galería cualquier imagen suelta
     * que se encuentre en las carpetas de música públicas.
     */
    private void purgeImageFromGallery(File imageFile) {
        if (imageFile == null) return;
        try {
            String path = imageFile.getAbsolutePath();
            try {
                ContentResolver resolver = getContext().getContentResolver();
                resolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media.DATA + " = ?",
                    new String[]{path}
                );
            } catch (Exception ignored) {}

            if (!imageFile.exists()) {
                MediaScannerConnection.scanFile(getContext(), new String[]{path}, null, null);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Limpia y migra automáticamente todas las imágenes sueltas de la biblioteca hacia .covers/
     * para que no aparezcan en la Galería del teléfono.
     */
    private void migrateAndCleanLooseCovers(File rootDir) {
        if (rootDir == null || !rootDir.exists()) return;
        try {
            // Eliminar masivamente registros de imágenes de MediaStore que estén en la biblioteca
            try {
                ContentResolver resolver = getContext().getContentResolver();
                resolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media.DATA + " LIKE ?",
                    new String[]{rootDir.getAbsolutePath() + "/%"}
                );
            } catch (Exception ignored) {}

            cleanLooseCoversRecursive(rootDir);
        } catch (Exception e) {
            Log.w(TAG, "Error en migrateAndCleanLooseCovers: " + e.getMessage());
        }
    }

    private void cleanLooseCoversRecursive(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        if (dir.getName().startsWith(".")) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                cleanLooseCoversRecursive(f);
            } else {
                String nameLower = f.getName().toLowerCase();
                if (nameLower.endsWith(".jpg") || nameLower.endsWith(".jpeg") || nameLower.endsWith(".png")) {
                    try {
                        File parent = f.getParentFile();
                        String baseName = f.getName().replaceFirst("[.][^.]+$", "");
                        
                        if (nameLower.equals("cover.jpg") || nameLower.equals("cover.png") || nameLower.equals("folder.jpg")) {
                            File targetCover = getCoverFileForAlbum(parent);
                            if (targetCover != null && (!targetCover.exists() || targetCover.length() == 0)) {
                                copyFile(f, targetCover);
                            }
                        } else {
                            File[] audioFiles = parent != null ? parent.listFiles((d, n) -> {
                                String bn = n.replaceFirst("[.][^.]+$", "");
                                return bn.equalsIgnoreCase(baseName);
                            }) : null;

                            if (audioFiles != null && audioFiles.length > 0) {
                                for (File af : audioFiles) {
                                    String an = af.getName().toLowerCase();
                                    if (an.endsWith(".opus") || an.endsWith(".m4a") || an.endsWith(".mp3") || an.endsWith(".flac")) {
                                        File targetCover = getCoverFileForTrack(af);
                                        if (targetCover != null && (!targetCover.exists() || targetCover.length() == 0)) {
                                            copyFile(f, targetCover);
                                        }
                                    }
                                }
                            } else {
                                File targetCover = getCoverFileForAlbum(parent);
                                if (targetCover != null && (!targetCover.exists() || targetCover.length() == 0)) {
                                    copyFile(f, targetCover);
                                }
                            }
                        }

                        String loosePath = f.getAbsolutePath();
                        boolean deleted = f.delete();
                        if (deleted) {
                            purgeImageFromGallery(new File(loosePath));
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "Error migrando imagen suelta: " + f.getName());
                    }
                }
            }
        }
    }

    private String sanitizeDirName(String name) {
        if (name == null || name.trim().isEmpty()) return "Desconocido";
        String clean = name.replaceAll("[/\\\\:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
        return clean.isEmpty() ? "Desconocido" : clean;
    }

    private void downloadImage(String imageUrl, File destination) throws Exception {
        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.connect();
        int respCode = conn.getResponseCode();
        if (respCode >= 400) {
            throw new IOException("HTTP " + respCode + " al descargar portada");
        }
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    private void tagAudioFile(File audioFile, String artist, String title, String album, File coverFile) {
        tagAudioFile(audioFile, artist, title, album, coverFile, null);
    }

    private void tagAudioFile(File audioFile, String artist, String title, String album, File coverFile, String lyrics) {
        if (audioFile == null || !audioFile.exists()) return;
        File scriptFile = null;
        File lyricsTemp = null;
        try {
            Context ctx = getContext();
            File baseDir = new File(ctx.getNoBackupFilesDir(), "youtubedl-android");
            File pythonDir = new File(baseDir, "packages/python");
            File ffmpegDir = new File(baseDir, "packages/ffmpeg");
            File pythonBin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libpython.so");

            if (!pythonBin.exists() || !pythonDir.exists()) {
                Log.w(TAG, "tagAudioFile: libpython.so o packages/python no disponible");
                return;
            }

            String pythonHome = new File(pythonDir, "usr").getAbsolutePath();
            String pythonLib = new File(pythonHome, "lib").getAbsolutePath();
            String ffmpegLib = new File(ffmpegDir, "usr/lib").getAbsolutePath();
            String nativeLib = ctx.getApplicationInfo().nativeLibraryDir;
            String ldLibraryPath = nativeLib + ":" + pythonLib + ":" + ffmpegLib;
            String pythonPath = pythonLib + "/python3.12:" + pythonLib + "/python3.12/site-packages";

            String pyScript =
                "import sys, os, base64\n" +
                "from mutagen.oggopus import OggOpus\n" +
                "from mutagen.mp3 import MP3\n" +
                "from mutagen.id3 import ID3, APIC, TIT2, TPE1, TALB, TPE2, ID3NoHeaderError, USLT\n" +
                "from mutagen.mp4 import MP4, MP4Cover\n" +
                "from mutagen.flac import FLAC, Picture\n" +
                "\n" +
                "audio_path = sys.argv[1]\n" +
                "title = sys.argv[2] if len(sys.argv) > 2 else ''\n" +
                "artist = sys.argv[3] if len(sys.argv) > 3 else ''\n" +
                "album = sys.argv[4] if len(sys.argv) > 4 else ''\n" +
                "cover_path = sys.argv[5] if len(sys.argv) > 5 else ''\n" +
                "lyrics_path = sys.argv[6] if len(sys.argv) > 6 else ''\n" +
                "\n" +
                "if not os.path.exists(audio_path):\n" +
                "    sys.exit(0)\n" +
                "\n" +
                "ext = os.path.splitext(audio_path)[1].lower()\n" +
                "cover_data = None\n" +
                "if cover_path and os.path.exists(cover_path) and os.path.getsize(cover_path) > 0:\n" +
                "    try:\n" +
                "        with open(cover_path, 'rb') as f:\n" +
                "            cover_data = f.read()\n" +
                "    except Exception:\n" +
                "        cover_data = None\n" +
                "\n" +
                "lyrics_data = None\n" +
                "if lyrics_path and os.path.exists(lyrics_path) and os.path.getsize(lyrics_path) > 0:\n" +
                "    try:\n" +
                "        with open(lyrics_path, 'r', encoding='utf-8', errors='ignore') as lf:\n" +
                "            lyrics_data = lf.read()\n" +
                "    except Exception:\n" +
                "        lyrics_data = None\n" +
                "\n" +
                "if ext == '.opus':\n" +
                "    try:\n" +
                "        audio = OggOpus(audio_path)\n" +
                "        if title: audio['title'] = [title]\n" +
                "        if artist:\n" +
                "            audio['artist'] = [artist]\n" +
                "            audio['albumartist'] = [artist]\n" +
                "        if album: audio['album'] = [album]\n" +
                "        if lyrics_data: audio['lyrics'] = [lyrics_data]\n" +
                "        if cover_data:\n" +
                "            pic = Picture()\n" +
                "            pic.type = 3\n" +
                "            pic.mime = 'image/jpeg'\n" +
                "            pic.data = cover_data\n" +
                "            pic.desc = 'Front Cover'\n" +
                "            audio['metadata_block_picture'] = [base64.b64encode(pic.write()).decode('ascii')]\n" +
                "        audio.save()\n" +
                "    except Exception as e:\n" +
                "        print('Error opus:', e)\n" +
                "\n" +
                "elif ext == '.m4a':\n" +
                "    try:\n" +
                "        audio = MP4(audio_path)\n" +
                "        if title: audio['\\xa9nam'] = [title]\n" +
                "        if artist:\n" +
                "            audio['\\xa9ART'] = [artist]\n" +
                "            audio['aART'] = [artist]\n" +
                "        if album: audio['\\xa9alb'] = [album]\n" +
                "        if lyrics_data: audio['\\xa9lyr'] = [lyrics_data]\n" +
                "        if cover_data:\n" +
                "            audio['covr'] = [MP4Cover(cover_data, imageformat=MP4Cover.FORMAT_JPEG)]\n" +
                "        audio.save()\n" +
                "    except Exception as e:\n" +
                "        print('Error m4a:', e)\n" +
                "\n" +
                "elif ext == '.mp3':\n" +
                "    try:\n" +
                "        try:\n" +
                "            tags = ID3(audio_path)\n" +
                "        except ID3NoHeaderError:\n" +
                "            tags = ID3()\n" +
                "        if title: tags['TIT2'] = TIT2(encoding=3, text=title)\n" +
                "        if artist:\n" +
                "            tags['TPE1'] = TPE1(encoding=3, text=artist)\n" +
                "            tags['TPE2'] = TPE2(encoding=3, text=artist)\n" +
                "        if album: tags['TALB'] = TALB(encoding=3, text=album)\n" +
                "        if lyrics_data:\n" +
                "            tags['USLT'] = USLT(encoding=3, lang='eng', desc='', text=lyrics_data)\n" +
                "        if cover_data:\n" +
                "            tags['APIC'] = APIC(encoding=3, mime='image/jpeg', type=3, desc='Cover', data=cover_data)\n" +
                "        tags.save(audio_path)\n" +
                "    except Exception as e:\n" +
                "        print('Error mp3:', e)\n" +
                "\n" +
                "elif ext == '.flac':\n" +
                "    try:\n" +
                "        audio = FLAC(audio_path)\n" +
                "        if title: audio['title'] = [title]\n" +
                "        if artist: audio['artist'] = [artist]\n" +
                "        if album: audio['album'] = [album]\n" +
                "        if lyrics_data: audio['lyrics'] = [lyrics_data]\n" +
                "        if cover_data:\n" +
                "            pic = Picture()\n" +
                "            pic.type = 3\n" +
                "            pic.mime = 'image/jpeg'\n" +
                "            pic.data = cover_data\n" +
                "            pic.desc = 'Front Cover'\n" +
                "            audio.clear_pictures()\n" +
                "            audio.add_picture(pic)\n" +
                "        audio.save()\n" +
                "    except Exception as e:\n" +
                "        print('Error flac:', e)\n";

            scriptFile = new File(audioFile.getParentFile(), "tagger.py");
            try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                fos.write(pyScript.getBytes(StandardCharsets.UTF_8));
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(pythonBin.getAbsolutePath());
            cmd.add(scriptFile.getAbsolutePath());
            cmd.add(audioFile.getAbsolutePath());
            cmd.add(title != null ? title : "");
            cmd.add(artist != null ? artist : "");
            cmd.add(album != null ? album : "");
            if (coverFile != null && coverFile.exists()) {
                cmd.add(coverFile.getAbsolutePath());
            } else {
                cmd.add("");
            }
            if (lyrics != null && !lyrics.trim().isEmpty()) {
                lyricsTemp = new File(audioFile.getParentFile(), "lyrics_" + System.currentTimeMillis() + ".tmp");
                try (FileOutputStream lfos = new FileOutputStream(lyricsTemp)) {
                    lfos.write(lyrics.getBytes(StandardCharsets.UTF_8));
                }
                cmd.add(lyricsTemp.getAbsolutePath());
            } else {
                cmd.add("");
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", ldLibraryPath);
            env.put("PYTHONHOME", pythonHome);
            env.put("PYTHONPATH", pythonPath);
            env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());

            Process p = pb.start();
            int exitCode = p.waitFor();

            if (exitCode == 0) {
                emitTerminal("[Metadatos] [OK] Carátula oficial y etiquetas incrustadas en el archivo");
            } else {
                Log.w(TAG, "tagAudioFile finalizó con código: " + exitCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error en tagAudioFile: " + e.getMessage(), e);
        } finally {
            if (scriptFile != null && scriptFile.exists()) {
                scriptFile.delete();
            }
            if (lyricsTemp != null && lyricsTemp.exists()) {
                lyricsTemp.delete();
            }
        }
    }

    private void extractAllEmbeddedCovers(File musicDir) {
        if (musicDir == null || !musicDir.exists()) return;
        File scriptFile = null;
        try {
            Context ctx = getContext();
            File baseDir = new File(ctx.getNoBackupFilesDir(), "youtubedl-android");
            File pythonDir = new File(baseDir, "packages/python");
            File ffmpegDir = new File(baseDir, "packages/ffmpeg");
            File pythonBin = new File(ctx.getApplicationInfo().nativeLibraryDir, "libpython.so");

            if (!pythonBin.exists() || !pythonDir.exists()) {
                return;
            }

            File coversDir = getCoversDirectory();
            if (!coversDir.exists()) {
                coversDir.mkdirs();
            }

            String pythonHome = new File(pythonDir, "usr").getAbsolutePath();
            String pythonLib = new File(pythonHome, "lib").getAbsolutePath();
            String ffmpegLib = new File(ffmpegDir, "usr/lib").getAbsolutePath();
            String nativeLib = ctx.getApplicationInfo().nativeLibraryDir;
            String ldLibraryPath = nativeLib + ":" + pythonLib + ":" + ffmpegLib;
            String pythonPath = pythonLib + "/python3.12:" + pythonLib + "/python3.12/site-packages";

            String pyScript =
                "import sys, os, hashlib, base64\n" +
                "from mutagen.oggopus import OggOpus\n" +
                "from mutagen.mp3 import MP3\n" +
                "from mutagen.id3 import ID3\n" +
                "from mutagen.mp4 import MP4\n" +
                "from mutagen.flac import FLAC, Picture\n" +
                "\n" +
                "music_dir = sys.argv[1]\n" +
                "covers_dir = sys.argv[2]\n" +
                "\n" +
                "def get_hash(path):\n" +
                "    return hashlib.md5(path.encode('utf-8')).hexdigest()\n" +
                "\n" +
                "for root, dirs, files in os.walk(music_dir):\n" +
                "    if '.covers' in root or os.path.basename(root).startswith('.'):\n" +
                "        continue\n" +
                "    album_cover_target = os.path.join(covers_dir, 'album_' + get_hash(root) + '.jpg')\n" +
                "    album_has_cover = os.path.exists(album_cover_target) and os.path.getsize(album_cover_target) > 0\n" +
                "    for file in files:\n" +
                "        ext = os.path.splitext(file)[1].lower()\n" +
                "        if ext not in ('.opus', '.m4a', '.mp3', '.flac', '.ogg'):\n" +
                "            continue\n" +
                "        audio_path = os.path.join(root, file)\n" +
                "        track_target = os.path.join(covers_dir, get_hash(audio_path) + '.jpg')\n" +
                "        if os.path.exists(track_target) and os.path.getsize(track_target) > 0:\n" +
                "            continue\n" +
                "        try:\n" +
                "            cov_data = None\n" +
                "            if ext in ('.opus', '.ogg'):\n" +
                "                audio = OggOpus(audio_path)\n" +
                "                pics = audio.get('metadata_block_picture', [])\n" +
                "                if pics:\n" +
                "                    pic = Picture(base64.b64decode(pics[0]))\n" +
                "                    cov_data = pic.data\n" +
                "            elif ext == '.m4a':\n" +
                "                audio = MP4(audio_path)\n" +
                "                covs = audio.get('covr', [])\n" +
                "                if covs:\n" +
                "                    cov_data = bytes(covs[0])\n" +
                "            elif ext == '.mp3':\n" +
                "                tags = ID3(audio_path)\n" +
                "                apics = tags.getall('APIC')\n" +
                "                if apics:\n" +
                "                    cov_data = apics[0].data\n" +
                "            elif ext == '.flac':\n" +
                "                audio = FLAC(audio_path)\n" +
                "                if audio.pictures:\n" +
                "                    cov_data = audio.pictures[0].data\n" +
                "\n" +
                "            if cov_data:\n" +
                "                with open(track_target, 'wb') as f:\n" +
                "                    f.write(cov_data)\n" +
                "                if not album_has_cover:\n" +
                "                    with open(album_cover_target, 'wb') as f:\n" +
                "                        f.write(cov_data)\n" +
                "                    album_has_cover = True\n" +
                "        except Exception:\n" +
                "            pass\n";

            scriptFile = new File(ctx.getCacheDir(), "extract_covers.py");
            try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                fos.write(pyScript.getBytes(StandardCharsets.UTF_8));
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(pythonBin.getAbsolutePath());
            cmd.add(scriptFile.getAbsolutePath());
            cmd.add(musicDir.getAbsolutePath());
            cmd.add(coversDir.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", ldLibraryPath);
            env.put("PYTHONHOME", pythonHome);
            env.put("PYTHONPATH", pythonPath);
            env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());

            Process p = pb.start();
            p.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "extractAllEmbeddedCovers: " + e.getMessage());
        } finally {
            if (scriptFile != null && scriptFile.exists()) {
                scriptFile.delete();
            }
        }
    }

    private String formatDuration(double sec) {
        if (sec <= 0) return "0:00";
        int totalSec = (int) Math.round(sec);
        int m = totalSec / 60;
        int s = totalSec % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    private boolean shouldIgnoreAudio(String path, long sizeBytes, long durationMs, boolean ignoreWhatsApp, boolean ignoreRecordings, int minDurationSec) {
        if (path == null || path.trim().isEmpty()) return true;
        String lower = path.toLowerCase();

        // 1. Filtro WhatsApp / Telegram / Mensajería
        if (ignoreWhatsApp) {
            if (lower.contains("whatsapp") || lower.contains("com.whatsapp")
                || lower.contains("telegram") || lower.contains("com.telegram")
                || lower.contains("voice notes") || lower.contains("voice_notes")
                || lower.contains("whatsapp audio") || lower.contains("opus/")
                || lower.matches(".*\\baud-\\d+.*") || lower.matches(".*\\bptt-\\d+.*")) {
                return true;
            }
        }

        // 2. Filtro Grabadoras y Llamadas
        if (ignoreRecordings) {
            if (lower.contains("sound_recorder") || lower.contains("soundrecorder")
                || lower.contains("recordings") || lower.contains("/recording/")
                || lower.contains("call_rec") || lower.contains("callrecord")
                || lower.contains("voice_recorder") || lower.contains("audiorecorder")) {
                return true;
            }
        }

        // 3. Filtro Tonos, Notificaciones y Alarmas del sistema
        if (lower.contains("/notifications/") || lower.contains("/ringtones/") 
            || lower.contains("/alarms/") || lower.contains("/ui/") || lower.contains("/system/media/")) {
            return true;
        }

        // 4. Filtro por duración mínima (audios menores a N segundos)
        File mainMusic = getMusicDirectory();
        boolean isInMainMusic = mainMusic != null && path.startsWith(mainMusic.getAbsolutePath());

        if (minDurationSec > 0 && durationMs > 0 && durationMs < (minDurationSec * 1000L)) {
            if (!isInMainMusic || durationMs < 5000L) {
                return true;
            }
        }

        // Si el tamaño es diminuto (< 100 KB) fuera de la biblioteca principal
        if (!isInMainMusic && sizeBytes > 0 && sizeBytes < 100 * 1024L) {
            return true;
        }

        return false;
    }

    private int countAudioFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return 0;
        File[] list = dir.listFiles((d, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".opus") || n.endsWith(".m4a") || n.endsWith(".mp3") 
                || n.endsWith(".flac") || n.endsWith(".ogg") || n.endsWith(".wav");
        });
        return list != null ? list.length : 0;
    }

    private int countAudioRecursive(File dir, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || maxDepth < 0) return 0;
        int count = 0;
        File[] list = dir.listFiles();
        if (list == null) return 0;
        for (File f : list) {
            if (f.isFile()) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".opus") || n.endsWith(".m4a") || n.endsWith(".mp3") 
                    || n.endsWith(".flac") || n.endsWith(".ogg") || n.endsWith(".wav")) {
                    count++;
                }
            } else if (f.isDirectory() && !f.getName().startsWith(".") && maxDepth > 0) {
                count += countAudioRecursive(f, maxDepth - 1);
            }
        }
        return count;
    }

    @PluginMethod
    public void getLibraryConfig(PluginCall call) {
        try {
            SharedPreferences prefs = getContext().getSharedPreferences("ytmusic_prefs", Context.MODE_PRIVATE);
            JSObject ret = new JSObject();
            ret.put("musicDirectory", getMusicDirectory().getAbsolutePath());

            String customFoldersJson = prefs.getString("custom_music_folders", "[]");
            try {
                ret.put("customFolders", new JSArray(customFoldersJson));
            } catch (Exception e) {
                ret.put("customFolders", new JSArray());
            }

            ret.put("scanMode", prefs.getString("scan_mode", "selected"));
            ret.put("includeDownloads", prefs.getBoolean("include_downloads", false));
            ret.put("ignoreWhatsApp", prefs.getBoolean("ignore_whatsapp", true));
            ret.put("ignoreRecordings", prefs.getBoolean("ignore_recordings", true));
            ret.put("minDurationSec", prefs.getInt("min_duration_sec", 30));

            JSArray detected = new JSArray();

            File pubMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            File biblio = new File(pubMusic, "Biblioteca");
            if (biblio.exists()) {
                JSObject bObj = new JSObject();
                bObj.put("path", biblio.getAbsolutePath());
                bObj.put("name", "Biblioteca YT Music");
                bObj.put("desc", "Carpeta organizada por Artista/Álbum");
                bObj.put("isDefault", true);
                bObj.put("audioCount", countAudioRecursive(biblio, 2));
                detected.put(bObj);
            }

            if (pubMusic.exists()) {
                JSObject mObj = new JSObject();
                mObj.put("path", pubMusic.getAbsolutePath());
                mObj.put("name", "Música (General)");
                mObj.put("desc", "Todo el directorio /sdcard/Music");
                mObj.put("isDefault", false);
                mObj.put("audioCount", countAudioRecursive(pubMusic, 2));
                detected.put(mObj);
            }

            File pubDown = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (pubDown.exists()) {
                JSObject dObj = new JSObject();
                dObj.put("path", pubDown.getAbsolutePath());
                dObj.put("name", "Descargas");
                dObj.put("desc", "Directorio /sdcard/Download");
                dObj.put("isDefault", false);
                dObj.put("audioCount", countAudioFiles(pubDown));
                detected.put(dObj);
            }

            ret.put("detectedFolders", detected);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Error obteniendo configuración de biblioteca: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void setLibraryConfig(PluginCall call) {
        try {
            SharedPreferences.Editor editor = getContext().getSharedPreferences("ytmusic_prefs", Context.MODE_PRIVATE).edit();

            String musicDir = call.getString("musicDirectory", null);
            if (musicDir != null && !musicDir.trim().isEmpty()) {
                File f = new File(musicDir.trim());
                if (!f.exists()) f.mkdirs();
                if (f.exists() && f.isDirectory()) {
                    editor.putString("music_directory", f.getAbsolutePath());
                }
            }

            JSArray customFolders = call.getArray("customFolders", null);
            if (customFolders != null) {
                editor.putString("custom_music_folders", customFolders.toString());
            }

            String scanMode = call.getString("scanMode", null);
            if (scanMode != null) {
                editor.putString("scan_mode", scanMode);
            }

            if (call.getData().has("includeDownloads")) {
                editor.putBoolean("include_downloads", call.getBoolean("includeDownloads", false));
            }
            if (call.getData().has("ignoreWhatsApp")) {
                editor.putBoolean("ignore_whatsapp", call.getBoolean("ignoreWhatsApp", true));
            }
            if (call.getData().has("ignoreRecordings")) {
                editor.putBoolean("ignore_recordings", call.getBoolean("ignoreRecordings", true));
            }
            if (call.getData().has("minDurationSec")) {
                editor.putInt("min_duration_sec", call.getInt("minDurationSec", 30));
            }

            editor.apply();

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("musicDirectory", getMusicDirectory().getAbsolutePath());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Error guardando configuración de biblioteca: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void listDirectories(PluginCall call) {
        String reqPath = call.getString("path", "");
        File currentDir;
        if (reqPath == null || reqPath.trim().isEmpty()) {
            currentDir = Environment.getExternalStorageDirectory(); // /storage/emulated/0
        } else {
            currentDir = new File(reqPath.trim());
        }

        if (!currentDir.exists() || !currentDir.isDirectory()) {
            currentDir = Environment.getExternalStorageDirectory();
        }

        JSObject ret = new JSObject();
        ret.put("currentPath", currentDir.getAbsolutePath());

        File parent = currentDir.getParentFile();
        boolean hasParent = parent != null && parent.canRead() && !parent.getAbsolutePath().equals("/") && !parent.getAbsolutePath().equals("/storage");
        ret.put("parentPath", hasParent ? parent.getAbsolutePath() : null);

        JSArray dirsArray = new JSArray();
        File[] files = currentDir.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                if (f.isDirectory() && !f.getName().startsWith(".")) {
                    JSObject dirObj = new JSObject();
                    dirObj.put("name", f.getName());
                    dirObj.put("path", f.getAbsolutePath());
                    File[] sub = f.listFiles(File::isDirectory);
                    dirObj.put("hasSubdirs", sub != null && sub.length > 0);
                    int audioCount = countAudioFiles(f);
                    dirObj.put("audioCount", audioCount);
                    dirsArray.put(dirObj);
                }
            }
        }
        ret.put("directories", dirsArray);
        call.resolve(ret);
    }

    private File getMusicDirectory() {
        try {
            SharedPreferences prefs = getContext().getSharedPreferences("ytmusic_prefs", Context.MODE_PRIVATE);
            String customPath = prefs.getString("music_directory", null);
            if (customPath != null && !customPath.trim().isEmpty()) {
                File customDir = new File(customPath.trim());
                if (!customDir.exists()) {
                    customDir.mkdirs();
                }
                if (customDir.exists() && customDir.isDirectory()) {
                    return customDir;
                }
            }
        } catch (Exception ignored) {}

        File pubMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        File biblio = new File(pubMusic, "Biblioteca");
        if (!biblio.exists()) {
            biblio.mkdirs();
        }
        if (biblio.exists()) {
            return biblio;
        }
        File fallback = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Biblioteca");
        if (!fallback.exists()) {
            fallback.mkdirs();
        }
        return fallback;
    }
}
