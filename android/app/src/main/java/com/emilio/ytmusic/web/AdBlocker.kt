package com.emilio.ytmusic.web

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {

    // Lista exhaustiva de redes y servidores externos de publicidad y telemetría (Brave Shields + EasyList)
    private val BLOCKED_HOSTS = hashSetOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "pagead2.googlesyndication.com",
        "adservice.google",
        "stats.g.doubleclick.net",
        "ad.doubleclick.net",
        "adclick.g.doubleclick.net",
        "google-analytics.com",
        "googletagmanager.com",
        "fls-na.amazon-adsystem.com",
        "amazon-adsystem.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "adnxs.com",
        "rubiconproject.com",
        "scorecardresearch.com"
    )

    /**
     * Verifica si una URL solicitada pertenece a una red publicitaria o telemetría pesada
     */
    fun isAdUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()

        // NUNCA interceptar streaming de música en googlevideo.com
        if (lowerUrl.contains("googlevideo.com")) {
            return false
        }

        // Bloquear rutas puras de anuncios en YouTube para acelerar carga inicial
        if (lowerUrl.contains("youtube.com")) {
            return lowerUrl.contains("/pagead/") ||
                lowerUrl.contains("/api/stats/ads") ||
                lowerUrl.contains("/ptracking")
        }

        // Filtrar dominios conocidos de publicidad y analíticas de terceros
        for (host in BLOCKED_HOSTS) {
            if (lowerUrl.contains(host)) {
                return true
            }
        }

        return false
    }

    /**
     * Respuesta vacía HTTP 200 para cancelar la carga de publicidad de forma segura
     */
    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "*"
            ),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * Reglas CSS de Brave Shields / uBlock Origin para YouTube Music
     */
    val AD_HIDE_CSS: String = """
        #player-ads,
        .ytp-ad-module,
        .ytp-ad-overlay-container,
        .ytp-ad-message-container,
        .ytp-ad-player-overlay,
        .ytp-ad-player-overlay-flyout-cta,
        .ytp-ad-text,
        .ytp-ad-preview-container,
        .ytp-ad-skip-button-container,
        .ytp-ad-skip-button-slot,
        .ytp-ad-button,
        button.ytp-ad-skip-button-text,
        ytmusic-mealbar-promo-renderer,
        ytm-mealbar-promo-renderer,
        ytd-mealbar-promo-renderer,
        ytmusic-app-promo-renderer,
        ytmusic-upsell-dialog-renderer,
        ytmusic-interstitial-promo-renderer,
        ytm-app-promo-modal,
        ytm-app-install-sheet,
        ytm-smart-app-banner,
        ytm-mobile-top-banner-renderer,
        ytmusic-banner-promo-renderer,
        ytmusic-ad-slot-renderer,
        ytmusic-mealbar-promo-renderer[dialog],
        tp-yt-paper-dialog:has(ytmusic-mealbar-promo-renderer),
        tp-yt-paper-dialog:has(ytm-mealbar-promo-renderer),
        tp-yt-paper-dialog:has(ytmusic-app-promo-renderer),
        tp-yt-paper-dialog:has(ytmusic-upsell-dialog-renderer),
        tp-yt-paper-dialog:has(#dismiss-button),
        body:has(tp-yt-paper-dialog[opened]:has(#dismiss-button)) tp-yt-iron-overlay-backdrop,
        ytd-promoted-sparkles-web-renderer,
        ytd-banner-promo-renderer,
        .masthead-ad-control,
        ytmusic-player-bar .yt-spec-button-shape-next--overlay,
        #merch-shelf,
        yt-mealbar-promo-renderer,
        ytd-statement-banner-renderer {
            display: none !important;
            visibility: hidden !important;
            width: 0 !important;
            height: 0 !important;
            opacity: 0 !important;
            pointer-events: none !important;
        }
    """.trimIndent()

    /**
     * Scriptlet limpio y seguro al estilo uBlock Origin:
     * 1. Elimina 'adPlacements', 'adSlots' y 'playerAds' de las respuestas JSON de YouTube sin tocar 'playbackTracking' ni el MediaSource buffer.
     * 2. Desactiva banderas de anuncios en ytcfg.
     * 3. Hace click en el botón de saltar anuncio si llegara a aparecer.
     * 4. Descarta automáticamente el modal de "Abrir en la app" pulsando "Ahora no".
     * 5. NO modifica video.currentTime ni video.playbackRate para no interferir con el adelantado de canciones.
     */
    val BRAVE_SHIELDS_JS: String by lazy {
        val cleanCss = AD_HIDE_CSS.replace("\n", " ").replace("'", "\\'")
        """
        (function() {
            'use strict';
            if (window.__ytm_brave_adblock_active) return;
            window.__ytm_brave_adblock_active = true;

            // 0. Habilitar reproducción en segundo plano (Page Visibility API Override)
            try {
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });

                const stopVisibility = function(e) {
                    e.stopImmediatePropagation();
                };
                document.addEventListener('visibilitychange', stopVisibility, true);
                document.addEventListener('webkitvisibilitychange', stopVisibility, true);
                window.addEventListener('visibilitychange', stopVisibility, true);
                window.addEventListener('pagehide', stopVisibility, true);
                window.addEventListener('blur', stopVisibility, true);
            } catch (e) {}

            // Inyectar CSS anti-anuncios de inmediato
            function injectStyle() {
                try {
                    const style = document.createElement('style');
                    style.type = 'text/css';
                    style.textContent = '$cleanCss';
                    (document.head || document.documentElement).appendChild(style);
                } catch (e) {}
            }
            injectStyle();
            document.addEventListener('DOMContentLoaded', injectStyle);

            // Sanitizador JSON conforme a la regla json-prune oficial de uBlock Origin
            function sanitizePlayerObj(obj) {
                if (!obj || typeof obj !== 'object') return obj;
                try {
                    if (obj.adPlacements) delete obj.adPlacements;
                    if (obj.adSlots) delete obj.adSlots;
                    if (obj.playerAds) delete obj.playerAds;
                    if (obj.adBreakHeartbeatParams) delete obj.adBreakHeartbeatParams;
                } catch (e) {}
                return obj;
            }

            // 1. Interceptar ytInitialPlayerResponse inline
            try {
                let _initialPlayer = window.ytInitialPlayerResponse;
                Object.defineProperty(window, 'ytInitialPlayerResponse', {
                    get: function() { return _initialPlayer; },
                    set: function(val) { _initialPlayer = sanitizePlayerObj(val); },
                    configurable: true
                });
            } catch (e) {}

            // 2. Interceptar Fetch API exclusivamente para sanitizar /player y /next (NUNCA /browse para no romper la carga de la página)
            try {
                const origFetch = window.fetch;
                window.fetch = async function(...args) {
                    const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');

                    if (url.includes('/pagead/') || url.includes('/api/stats/ads')) {
                        return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
                    }

                    const response = await origFetch.apply(this, args);

                    if (url.includes('/youtubei/v1/player') || url.includes('/youtubei/v1/next')) {
                        try {
                            const clone = response.clone();
                            const json = await clone.json();
                            sanitizePlayerObj(json);
                            const body = JSON.stringify(json);
                            const newHeaders = new Headers(response.headers);
                            newHeaders.delete('content-encoding');
                            newHeaders.delete('content-length');
                            return new Response(body, {
                                status: response.status,
                                statusText: response.statusText,
                                headers: newHeaders
                            });
                        } catch (err) {}
                    }
                    return response;
                };
            } catch (e) {}

            // 3. Desactivar flags de publicidad y promoción de app en ytcfg
            function applyFlags(exp) {
                if (!exp || typeof exp !== 'object') return;
                exp.web_enable_ab_mweb_companion_ads = false;
                exp.web_enable_ab_companion_ads = false;
                exp.html5_ad_timeout_ms = 0;
                exp.enable_music_mweb_app_bar_app_install_entry = false;
                exp.enable_music_mweb_profile_menu_app_install_entry = false;
                exp.enable_music_mweb_side_nav_app_install_entry = false;
                exp.web_enable_app_promo_mealbar = false;
                exp.web_enable_open_in_app = false;
                exp.mweb_enable_app_promo = false;
                exp.music_web_enable_paygated_upsell = false;
            }

            function disableAdFlags() {
                try {
                    if (window.ytcfg && window.ytcfg.data_) {
                        applyFlags(window.ytcfg.data_.EXPERIMENT_FLAGS);
                    }
                    if (window.ytcfg && typeof window.ytcfg.set === 'function' && !window.ytcfg.__ytm_flags_patched) {
                        window.ytcfg.__ytm_flags_patched = true;
                        const origSet = window.ytcfg.set;
                        window.ytcfg.set = function(k) {
                            if (typeof k === 'object' && k !== null && k.EXPERIMENT_FLAGS) {
                                applyFlags(k.EXPERIMENT_FLAGS);
                            }
                            return origSet.apply(this, arguments);
                        };
                    }
                } catch (e) {}
            }
            disableAdFlags();
            document.addEventListener('DOMContentLoaded', disableAdFlags);

            // 4. Salto de anuncios y auto-click instantáneo (0ms) en "Ahora no" sin provocar parpadeos
            function dismissPromosAndSkip() {
                try {
                    // A. Saltar anuncio de video si aparece
                    const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-slot button, button.ytp-ad-skip-button-text');
                    if (skipBtn) {
                        skipBtn.click();
                    }

                    // B. Descartar automáticamente el banner de "Abrir en la app" pulsando su botón "Ahora no" (#dismiss-button)
                    const promo = document.querySelector('ytmusic-mealbar-promo-renderer, ytm-mealbar-promo-renderer, ytd-mealbar-promo-renderer, ytmusic-app-promo-renderer, ytmusic-upsell-dialog-renderer, ytm-app-promo-modal, tp-yt-paper-dialog');
                    if (promo) {
                        const dismissBtn = promo.querySelector('#dismiss-button a, #dismiss-button button, #dismiss-button tp-yt-paper-button, #dismiss-button');
                        if (dismissBtn) {
                            dismissBtn.click();
                        } else {
                            const btns = promo.querySelectorAll('button, a, tp-yt-paper-button, yt-button-renderer, yt-button-shape');
                            for (let i = 0; i < btns.length; i++) {
                                const txt = (btns[i].textContent || '').trim().toLowerCase();
                                if (txt === 'ahora no' || txt === 'not now' || txt === 'no gracias' || txt === 'no, gracias') {
                                    btns[i].click();
                                    break;
                                }
                            }
                        }
                    }
                } catch (e) {}
            }

            // MutationObserver instantáneo para reaccionar antes del primer frame si aparece cualquier promo
            try {
                const observer = new MutationObserver(function(mutations) {
                    for (let i = 0; i < mutations.length; i++) {
                        const added = mutations[i].addedNodes;
                        for (let j = 0; j < added.length; j++) {
                            const n = added[j];
                            if (n.nodeType === 1) {
                                const tag = n.tagName;
                                if (tag === 'YTMUSIC-MEALBAR-PROMO-RENDERER' ||
                                    tag === 'YTM-MEALBAR-PROMO-RENDERER' ||
                                    tag === 'YTD-MEALBAR-PROMO-RENDERER' ||
                                    tag === 'YTMUSIC-APP-PROMO-RENDERER' ||
                                    tag === 'YTMUSIC-UPSELL-DIALOG-RENDERER' ||
                                    tag === 'YTM-APP-PROMO-MODAL' ||
                                    tag === 'TP-YT-PAPER-DIALOG') {
                                    dismissPromosAndSkip();
                                    return;
                                }
                            }
                        }
                    }
                });
                observer.observe(document.documentElement, { childList: true, subtree: true });
            } catch (e) {}

            setInterval(dismissPromosAndSkip, 800);
            document.addEventListener('DOMContentLoaded', dismissPromosAndSkip);
        })();
    """.trimIndent()
    }
}
