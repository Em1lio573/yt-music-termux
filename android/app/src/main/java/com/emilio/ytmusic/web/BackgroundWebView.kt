package com.emilio.ytmusic.web

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * WebView optimizado para reproducción de audio continua en segundo plano y con pantalla apagada.
 * Evita que Chromium congele el motor JS o pause el elemento <video> cuando la Activity pierde visibilidad.
 */
class BackgroundWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    override fun onWindowVisibilityChanged(visibility: Int) {
        // Reportar siempre VISIBLE a Chromium para evitar la suspensión automática del audio en segundo plano
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}
