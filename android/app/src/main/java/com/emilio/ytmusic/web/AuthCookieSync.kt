package com.emilio.ytmusic.web

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import uniffi.ytmusic_core.ytmHasSession
import uniffi.ytmusic_core.ytmSaveCookieHeader

object AuthCookieSync {

    private const val TAG = "AuthCookieSync"
    private const val PREFS_NAME = "ytmusic_auth_prefs"
    private const val KEY_COOKIES = "saved_cookies_header"

    /**
     * Extrae las cookies actuales de YouTube Music desde el CookieManager del WebView,
     * las persiste en SharedPreferences y las sincroniza con el núcleo nativo de Rust.
     */
    fun syncFromWebView(context: Context): Boolean {
        val cookieManager = CookieManager.getInstance()
        val targets = listOf(
            "https://music.youtube.com",
            "https://www.youtube.com",
            "https://youtube.com",
            "https://accounts.google.com",
            "https://.youtube.com",
            "https://.google.com"
        )

        // Combinar y deduplicar cookies de todos los dominios clave de YouTube y Google
        val cookieMap = mutableMapOf<String, String>()
        for (target in targets) {
            val raw = cookieManager.getCookie(target) ?: continue
            for (part in raw.split(';')) {
                val item = part.trim()
                if (item.isEmpty()) continue
                val eqIdx = item.indexOf('=')
                if (eqIdx > 0) {
                    val key = item.substring(0, eqIdx).trim()
                    val value = item.substring(eqIdx + 1).trim()
                    if (key.isNotEmpty()) {
                        cookieMap[key] = value
                    }
                }
            }
        }

        if (cookieMap.isEmpty()) {
            return false
        }

        val combinedCookies = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

        // 1. Guardar en SharedPreferences para persistencia entre reinicios de la aplicación
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COOKIES, combinedCookies).apply()

        // 2. Transmitir al Rust Core
        return try {
            val count = ytmSaveCookieHeader(combinedCookies)
            val hasSession = ytmHasSession()
            Log.i(TAG, "Sincronizadas $count cookies con Rust Core. ¿Sesión activa?: $hasSession")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando cookies a Rust Core: ${e.message}", e)
            false
        }
    }

    /**
     * Carga las cookies guardadas al iniciar la aplicación y las alimenta a Rust.
     */
    fun restoreSavedCookies(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_COOKIES, null)
        if (!saved.isNullOrBlank()) {
            return try {
                ytmSaveCookieHeader(saved)
                Log.i(TAG, "Cookies restauradas exitosamente desde preferencias")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Fallo al restaurar cookies en Rust: ${e.message}", e)
                false
            }
        }
        return false
    }

    /**
     * Retorna verdadero si hay una sesión activa de Google autenticada en Rust o en el WebView.
     */
    fun isAuthenticated(context: Context): Boolean {
        try {
            if (ytmHasSession()) return true
        } catch (_: Exception) {}

        val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: ""
        return cookies.contains("SAPISID") || cookies.contains("LOGIN_INFO") || cookies.contains("__Secure-3PSID")
    }
}
