package com.hermes.hdfull.data

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Puente Kotlin -> Python (Chaquopy) hacia el resolver original de Alfa.
 *
 * Usa app/src/main/python/alfa_bridge.py::resolve_video_url(url, page_html, mode)
 * que a su vez llama a alfaresolver.decode_video_url() del módulo original.
 *
 * Si el módulo no carga (bytecode Python 2 en Python 3), el error se propaga
 * con el mensaje exacto; NO se sustituye por regex.
 */
object AlfaPythonResolver {
    private const val TAG = "AlfaPython"
    private var initialized = false
    private var initError: String? = null

    @Synchronized
    fun init(context: Context): Boolean {
        if (initialized) return initError == null
        return try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            // Diagnóstico del módulo
            val py = Python.getInstance()
            val bridge = py.getModule("alfa_bridge")
            val info = bridge.callAttr("module_info").asMap()
            Log.d(TAG, "alfa_bridge info: $info")
            val loaded = info["loaded"]?.toString() == "true"
            if (!loaded) {
                initError = "alfa_bridge.module_info: $info"
                Log.e(TAG, initError!!)
            }
            initialized = true
            loaded
        } catch (e: Exception) {
            initError = "Fallo al iniciar Python/Chaquopy: ${e.message}"
            Log.e(TAG, initError!!, e)
            initialized = true
            false
        }
    }

    /**
     * Aplica decode_video_url() de Alfa.
     * @return URL decodificada o null si falla (el error queda en Log).
     */
    fun decodeVideoUrl(context: Context, url: String, pageHtml: String, mode: Int = 2): String? {
        if (!init(context)) {
            Log.e(TAG, "Python no inicializado: $initError")
            return null
        }
        return try {
            Log.d(TAG, "decode_video_url ANTES: $url")
            val py = Python.getInstance()
            val bridge = py.getModule("alfa_bridge")
            val result = bridge.callAttr("resolve_video_url", url, pageHtml, mode)
            val decoded = result?.toString()?.takeIf { it.isNotBlank() && it != "None" }
            Log.d(TAG, "decode_video_url DESPUÉS: $decoded")
            decoded
        } catch (e: Exception) {
            Log.e(TAG, "decode_video_url ERROR: ${e.message}", e)
            null
        }
    }

    fun getInitError(): String? = initError
}
