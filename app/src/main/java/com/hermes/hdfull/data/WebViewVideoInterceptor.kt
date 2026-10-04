package com.hermes.hdfull.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Intercepta la URL directa de vídeo cargando el embed en un WebView invisible.
 * Observa las peticiones de red en busca de .mp4, .m3u8, .mpd, etc.
 * Sigue el mismo patrón probado que WebViewLinkExtractor.
 */
object WebViewVideoInterceptor {

    data class InterceptedVideo(
        val url: String,
        val referer: String?,
        val headers: Map<String, String>,
        val cookieHeader: String?
    )

    private val VIDEO_EXTENSIONS = listOf(
        ".mp4", ".m3u8", ".mpd", ".webm", ".mkv"
    )

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun intercept(
        context: Context,
        embedUrl: String,
        timeoutMs: Long = 15000
    ): String? = interceptDetailed(context, embedUrl, timeoutMs)?.url

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun interceptDetailed(
        context: Context,
        embedUrl: String,
        timeoutMs: Long = 15000
    ): InterceptedVideo? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var webView: WebView? = null
            val handler = Handler(Looper.getMainLooper())
            var finished = false

            fun finish(video: InterceptedVideo?) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (e: Exception) { /* ignore */ }
                webView = null
                if (cont.isActive) cont.resume(video)
            }

            // Timeout global
            handler.postDelayed({ finish(null) }, timeoutMs)

            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    // Invisible: nunca se adjunta a la jerarquía de vistas
                }

                val wv = webView!!

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val req = request ?: return null
                        val url = req.url?.toString() ?: return null
                        val lower = url.lowercase()
                        if (VIDEO_EXTENSIONS.any { lower.contains(it) }) {
                            // Evitar falsos positivos
                            if (!lower.contains(".js") && !lower.contains(".css") &&
                                !lower.contains(".png") && !lower.contains(".jpg") &&
                                !lower.contains(".gif") && !lower.contains(".svg") &&
                                !lower.contains(".woff") && !lower.contains(".ttf")) {
                                val captured = req.requestHeaders.toMutableMap().apply {
                                    remove("Host")
                                    remove("Cookie")
                                }
                                val referer = req.requestHeaders.entries.firstOrNull {
                                    it.key.equals("Referer", true)
                                }?.value ?: embedUrl
                                handler.post {
                                    if (!finished) {
                                        val cookies = CookieManager.getInstance().getCookie(url)
                                        val allHeaders = captured.toMutableMap()
                                        allHeaders["Referer"] = referer
                                        allHeaders.putIfAbsent("User-Agent", wv.settings.userAgentString)
                                        finish(InterceptedVideo(url, referer, allHeaders, cookies))
                                    }
                                }
                            }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Intentar extraer src de tags video después de que cargue la página
                        handler.postDelayed({
                            if (finished) return@postDelayed
                            try {
                                wv.evaluateJavascript(
                                    """(function(){
                                        var vids=document.querySelectorAll('video');
                                        for(var v of vids){
                                            if(v.src && v.src.length>10) return v.src;
                                            var srcs=v.querySelectorAll('source');
                                            for(var s of srcs){ if(s.src && s.src.length>10) return s.src; }
                                        }
                                        return '';
                                    })();"""
                                ) { result ->
                                    val clean = result?.trim('"')?.trim()
                                    if (!clean.isNullOrBlank() && clean != "null" && clean.length > 10 &&
                                        VIDEO_EXTENSIONS.any { clean.lowercase().contains(it) }) {
                                        val cookies = CookieManager.getInstance().getCookie(clean)
                                        finish(
                                            InterceptedVideo(
                                                clean,
                                                embedUrl,
                                                mapOf(
                                                    "Referer" to embedUrl,
                                                    "User-Agent" to wv.settings.userAgentString
                                                ),
                                                cookies
                                            )
                                        )
                                    }
                                }
                            } catch (e: Exception) { /* ignore */ }
                        }, 3000)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        // No terminar aquí; el timeout manda
                    }
                }

                wv.loadUrl(embedUrl)
            } catch (e: Exception) {
                finish(null)
            }

            cont.invokeOnCancellation {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    /** true si la URL parece un vídeo directo. */
    fun isDirectVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return VIDEO_EXTENSIONS.any { lower.contains(it) } ||
               lower.contains("googlevideo.com") ||
               lower.contains("/hls/") ||
               lower.contains("/dash/")
    }
}
