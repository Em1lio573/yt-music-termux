package com.emilio.ytmusic.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import coil.compose.AsyncImage
import com.emilio.ytmusic.ui.theme.*
import com.emilio.ytmusic.viewmodel.LibraryViewModel
import com.emilio.ytmusic.viewmodel.PlayerViewModel
import com.emilio.ytmusic.web.AdBlocker
import com.emilio.ytmusic.web.AuthCookieSync
import com.emilio.ytmusic.web.BackgroundWebView
import com.emilio.ytmusic.web.WebMusicBridge
import com.emilio.ytmusic.web.WebMusicDownloader
import com.emilio.ytmusic.web.WebTrack
import java.lang.ref.WeakReference

private val WEB_DOWNLOAD_QUALITIES = listOf(
    "native" to "Opus Nativo (~160k • Recomendado)",
    "m4a" to "M4A AAC (128k / 256k Premium)"
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebMusicScreen(
    playerViewModel: PlayerViewModel? = null,
    libraryViewModel: LibraryViewModel? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isAuthenticated by remember { mutableStateOf(AuthCookieSync.isAuthenticated(context)) }
    var showQualityDialog by remember { mutableStateOf(false) }

    val downloaderState by WebMusicDownloader.state.collectAsState()
    val currentTrack = downloaderState.currentTrack
    val activeDownload = downloaderState.activeDownload

    // Efecto para mostrar mensajes de estado
    LaunchedEffect(downloaderState.statusMessage) {
        downloaderState.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            WebMusicDownloader.clearStatusMessage()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Barra Superior Glassmorphism de Navegación, Estado y Acciones
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xF218181B),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Controles de navegación Web
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { webViewInstance?.goBack() },
                            enabled = canGoBack,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Atrás",
                                tint = if (canGoBack) TextPrimary else TextMuted
                            )
                        }

                        IconButton(
                            onClick = { webViewInstance?.goForward() },
                            enabled = canGoForward,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Adelante",
                                tint = if (canGoForward) TextPrimary else TextMuted
                            )
                        }

                        IconButton(
                            onClick = { webViewInstance?.reload() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Recargar",
                                tint = TextSecondary
                            )
                        }
                    }

                    // Selector de calidad compacto en cabecera
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0x26FFFFFF),
                        modifier = Modifier
                            .clickable { showQualityDialog = true }
                            .applePressEffect()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (downloaderState.selectedQuality) {
                                    "m4a", "m4a_256" -> "M4A"
                                    else -> "Opus"
                                },
                                color = AppleRose,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Calidad",
                                tint = AppleRose,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    // Botón Especial de Descarga Rápida en Cabecera
                    Button(
                        onClick = {
                            val track = currentTrack
                            if (track != null) {
                                WebMusicDownloader.startDownload(context, track, coroutineScope) {
                                    libraryViewModel?.refreshLibrary()
                                }
                            } else {
                                webViewInstance?.let { wv ->
                                    WebMusicDownloader.queryCurrentTrackFromWebView(wv) { detected ->
                                        if (detected != null) {
                                            WebMusicDownloader.startDownload(context, detected, coroutineScope) {
                                                libraryViewModel?.refreshLibrary()
                                            }
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Toca reproducir en cualquier canción de YouTube Music para descargarla.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentTrack != null) AppleRose else Color(0x33FA2D48)
                        ),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .applePressEffect()
                    ) {
                        Icon(
                            imageVector = when (activeDownload?.state) {
                                "completed" -> Icons.Default.CheckCircle
                                "downloading", "tagging" -> Icons.Default.Downloading
                                else -> Icons.Default.Download
                            },
                            contentDescription = "Descargar",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when (activeDownload?.state) {
                                "completed" -> "¡Listo!"
                                "downloading" -> "${activeDownload.progressPercent.toInt()}%"
                                "tagging" -> "Tags..."
                                else -> "Descargar"
                            },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Botón de sincronización de cuenta / perfil
                    IconButton(
                        onClick = {
                            val ok = AuthCookieSync.syncFromWebView(context)
                            isAuthenticated = AuthCookieSync.isAuthenticated(context)
                            if (ok && isAuthenticated) {
                                Toast.makeText(
                                    context,
                                    "¡Cuenta y recomendaciones sincronizadas con la app!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else if (ok) {
                                Toast.makeText(
                                    context,
                                    "Inicia sesión en YouTube Music para sincronizar tus Me Gusta.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(context, "No se detectaron cookies.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (isAuthenticated) Icons.Default.AccountCircle else Icons.Default.Sync,
                            contentDescription = "Sincronizar",
                            tint = if (isAuthenticated) Color(0xFF30D158) else AppleRose,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Barra de progreso de carga de página
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    color = AppleRose,
                    trackColor = Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            // Barra de progreso de descarga activa en la cabecera
            if (activeDownload != null && (activeDownload.state == "downloading" || activeDownload.state == "tagging")) {
                LinearProgressIndicator(
                    progress = { (activeDownload.progressPercent / 100f).coerceIn(0f, 1f) },
                    color = AppleRose,
                    trackColor = Color(0x33FFFFFF),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            // 2. Navegador WebView optimizado y persistente para reproducción en segundo plano
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    val cached = WebMusicDownloader.cachedWebView
                    if (cached != null) {
                        (cached.parent as? ViewGroup)?.removeView(cached)
                        canGoBack = cached.canGoBack()
                        canGoForward = cached.canGoForward()
                        webViewInstance = cached
                        WebMusicDownloader.activeWebView = WeakReference(cached)
                        return@AndroidView cached
                    }

                    BackgroundWebView(ctx.applicationContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // Aceleración por hardware dedicada para fluidez de reproducción multimedia
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)

                        // Registrar caché persistente y referencia para coordinación de audio
                        WebMusicDownloader.cachedWebView = this
                        WebMusicDownloader.activeWebView = WeakReference(this)
                        WebMusicDownloader.appContext = ctx.applicationContext

                        // Configuración de Cookies
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        // Configuración de WebSettings optimizada para carga rápida y multimedia
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            setSupportMultipleWindows(false)
                            javaScriptCanOpenWindowsAutomatically = false
                            builtInZoomControls = false
                            displayZoomControls = false
                            saveFormData = false
                            setGeolocationEnabled(false)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                offscreenPreRaster = true
                            }

                            // User-Agent de Chrome Mobile estándar
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                        }

                        // Conectar Puente Nativo para Detección de Pistas y Servicio en Segundo Plano
                        addJavascriptInterface(
                            WebMusicBridge(
                                onTrackDetected = { track ->
                                    WebMusicDownloader.updateCurrentTrack(track)
                                },
                                onWebPlayStateChanged = { isPlaying ->
                                    if (isPlaying) {
                                        // Apagar inmediatamente el reproductor offline si suena la música web
                                        playerViewModel?.pause()
                                    }
                                    WebMusicDownloader.updateWebPlayState(ctx.applicationContext, isPlaying)
                                },
                                onWebPositionChanged = { posMs, durMs ->
                                    WebMusicDownloader.updateWebPosition(ctx.applicationContext, posMs, durMs)
                                }
                            ),
                            "AndroidBridge"
                        )

                        // Inyección en tiempo de carga inicial al estilo Brave Shields
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            try {
                                WebViewCompat.addDocumentStartJavaScript(
                                    this,
                                    AdBlocker.BRAVE_SHIELDS_JS,
                                    setOf("https://music.youtube.com", "https://*.youtube.com")
                                )
                                WebViewCompat.addDocumentStartJavaScript(
                                    this,
                                    WebMusicDownloader.TRACK_OBSERVER_JS,
                                    setOf("https://music.youtube.com", "https://*.youtube.com")
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        // Cliente Web con Bloqueador
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                                if (AdBlocker.isAdUrl(url)) {
                                    return AdBlocker.createEmptyResponse()
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val host = uri.host?.lowercase() ?: ""
                                val urlStr = uri.toString().lowercase()
                                val scheme = uri.scheme?.lowercase() ?: ""

                                // Bloquear esquemas que no sean HTTP/HTTPS (intent://, market://, etc.)
                                if (scheme != "http" && scheme != "https") {
                                    return true
                                }

                                // Bloquear cualquier intento de abrir Play Store o enlaces de descarga de apps
                                if (urlStr.contains("play.google.com") || urlStr.contains("market://") || (urlStr.contains("youtube.music") && !host.endsWith("youtube.com")) || urlStr.contains("apple.com")) {
                                    return true
                                }

                                val isAllowed = host.endsWith("youtube.com") ||
                                        host.endsWith("google.com") ||
                                        host.endsWith("gstatic.com") ||
                                        host.endsWith("googleusercontent.com")

                                return !isAllowed
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                // Re-inyección en cambios de ruta SPA
                                view?.evaluateJavascript(AdBlocker.BRAVE_SHIELDS_JS, null)
                                // Sincronización automática de sesión en tiempo real
                                AuthCookieSync.syncFromWebView(ctx)
                                isAuthenticated = AuthCookieSync.isAuthenticated(ctx)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true

                                view?.evaluateJavascript(AdBlocker.BRAVE_SHIELDS_JS, null)
                                view?.evaluateJavascript(WebMusicDownloader.TRACK_OBSERVER_JS, null)

                                // Sincronización automática de cookies
                                AuthCookieSync.syncFromWebView(ctx)
                                isAuthenticated = AuthCookieSync.isAuthenticated(ctx)
                            }
                        }

                        // WebChromeClient para progreso
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                loadProgress = newProgress
                            }
                        }

                        loadUrl("https://music.youtube.com")
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                    WebMusicDownloader.activeWebView = WeakReference(webView)
                }
            )
        }
    }

    // Diálogo de Selección de Calidad de Descarga
    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = {
                Text(
                    text = "Calidad de Descarga",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    WEB_DOWNLOAD_QUALITIES.forEach { (codecKey, label) ->
                        val isSelected = downloaderState.selectedQuality == codecKey
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    WebMusicDownloader.setQuality(codecKey)
                                    showQualityDialog = false
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) Color(0x33FA2D48) else Color(0x1AFFFFFF)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        WebMusicDownloader.setQuality(codecKey)
                                        showQualityDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = AppleRose,
                                        unselectedColor = TextSecondary
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    color = if (isSelected) AppleRose else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text("Cerrar", color = AppleRose)
                }
            },
            containerColor = Color(0xFF1C1C1E),
            shape = RoundedCornerShape(20.dp)
        )
    }
}
