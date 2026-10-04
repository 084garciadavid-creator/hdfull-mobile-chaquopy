package com.hermes.hdfull.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Reproductor externo PVD (Pur Video Downloader, Asize Soft),
 * el mismo que usa DixMax (paquete com.asizesoft.pvp.android).
 */
object PvdPlayer {
    const val PACKAGE = "com.asizesoft.pvp.android"
    const val ACTIVITY = "com.asizesoft.pvp.android.activities.RemoteVideoPlayer"
    private const val PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=$PACKAGE"

    /** true si PVD está instalado. */
    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Abre la ficha de Play Store de PVD para instalarlo. */
    fun openPlayStore(context: Context) {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(PLAY_STORE_URL)
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: navegador
            try {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(PLAY_STORE_URL)
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            } catch (e2: Exception) { }
        }
    }

    /**
     * Reproduce [videoUrl] en PVD mediante Intent explícito.
     * La URL normalmente será del proxy local (http://127.0.0.1:...).
     * Devuelve false si PVD no está instalado.
     */
    fun play(
        context: Context,
        videoUrl: String,
        title: String = "",
        referer: String? = null,
        userAgent: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Boolean {
        if (!isInstalled(context)) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName(PACKAGE, ACTIVITY)
                setDataAndType(Uri.parse(videoUrl), "video/*")
                if (title.isNotBlank()) {
                    putExtra("title", title)
                    putExtra("name", title)
                }
                // Headers que necesitan powvideo/streamplay y similares
                if (!referer.isNullOrBlank()) {
                    putExtra("referer", referer)
                    putExtra("Referer", referer)
                    putExtra(android.content.Intent.EXTRA_REFERRER, Uri.parse(referer))
                }
                if (!userAgent.isNullOrBlank()) {
                    putExtra("user-agent", userAgent)
                    putExtra("User-Agent", userAgent)
                }
                // Headers adicionales como Bundle
                if (headers.isNotEmpty()) {
                    val bundle = android.os.Bundle()
                    headers.forEach { (k, v) -> bundle.putString(k, v) }
                    putExtra("headers", bundle)
                }
                // Extras que DixMax envía (compatibilidad con PVD)
                putExtra("apiBaseUrl", referer ?: "")
                putExtra("playNetworkMode", 1)
                putExtra("autoPlay", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // Fallback: intent genérico dirigido al paquete, con headers
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)).apply {
                    setPackage(PACKAGE)
                    if (!referer.isNullOrBlank()) {
                        putExtra("referer", referer)
                        putExtra(android.content.Intent.EXTRA_REFERRER, Uri.parse(referer))
                    }
                    if (!userAgent.isNullOrBlank()) putExtra("user-agent", userAgent)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
