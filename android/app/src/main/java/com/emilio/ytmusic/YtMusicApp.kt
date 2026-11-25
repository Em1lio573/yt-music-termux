package com.emilio.ytmusic

import android.app.Application
import android.util.Log
import com.emilio.ytmusic.web.AuthCookieSync

class YtMusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("ytmusic_core")
            Log.i("YtMusicApp", "Rust core library libytmusic_core.so cargada exitosamente")
            AuthCookieSync.restoreSavedCookies(this)
        } catch (e: UnsatisfiedLinkError) {
            Log.e("YtMusicApp", "Error cargando libytmusic_core.so: ${e.message}", e)
        }
    }
}
