package com.example.gdbviewer.convert

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Puente hacia una página HTML local (assets/gdal/index.html) que cargaría gdal3.js
 * (GDAL compilado a WebAssembly) para convertir un .gdb a .gpkg 100% en el dispositivo,
 * sin subir nada a ningún servidor y sin costo.
 *
 * ESTADO HONESTO: esto es un prototipo de arquitectura, NO una función lista para usar.
 * Para que funcione de verdad faltan dos piezas reales (detalladas también en el HTML):
 * 1. Los binarios de gdal3.js (~30 MB) no están incluidos — hay que descargarlos del
 *    paquete npm "gdal3.js" y copiarlos a app/src/main/assets/gdal/.
 * 2. La descompresión en JavaScript del .gdb (que es una carpeta con varios archivos,
 *    no un solo archivo) antes de pasarlo a Gdal.open() todavía no está escrita.
 *
 * No hay forma de probar nada de esto en el entorno donde se generó este proyecto (no se
 * pueden descargar los binarios de gdal3.js ni correr un WebView aquí), así que antes de
 * usarlo hace falta completar los dos puntos de arriba y probarlo en un dispositivo real.
 *
 * Uso previsto una vez completado:
 *   val bridge = GdalWebViewBridge(context) { gpkgBytes, error ->
 *       if (gpkgBytes != null) { /* guardar gpkgBytes como archivo .gpkg */ }
 *       else { /* mostrar error */ }
 *   }
 *   bridge.convertGdbZip(zipBytesDelGdb, "miArchivo.gdb.zip")
 */
class GdalWebViewBridge(
    context: Context,
    private val onResult: (gpkgBytes: ByteArray?, error: String?) -> Unit
) {
    @SuppressLint("SetJavaScriptEnabled")
    private val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        addJavascriptInterface(JsBridge(), "AndroidBridge")
        webViewClient = WebViewClient()
        loadUrl("file:///android_asset/gdal/index.html")
    }

    fun convertGdbZip(zipBytes: ByteArray, fileName: String) {
        val base64 = Base64.encodeToString(zipBytes, Base64.NO_WRAP)
        val safeFileName = fileName.replace("'", "\\'")
        webView.evaluateJavascript(
            "convertGdbZipToGpkgSafe('$base64', '$safeFileName');",
            null
        )
    }

    fun destroy() {
        webView.destroy()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onConversionComplete(base64Gpkg: String) {
            val bytes = Base64.decode(base64Gpkg, Base64.NO_WRAP)
            onResult(bytes, null)
        }

        @JavascriptInterface
        fun onConversionError(message: String) {
            onResult(null, message)
        }
    }
}
