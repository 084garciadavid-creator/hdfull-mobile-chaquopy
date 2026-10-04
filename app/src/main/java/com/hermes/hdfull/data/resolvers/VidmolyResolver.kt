package com.hermes.hdfull.data.resolvers

import java.util.regex.Pattern

/**
 * Resolutor para Vidmoly (vidmoly.me).
 * Portado de plugin.video.alfa/servers/vidmoly.py.
 * Extrae {file:"..."} con label del HTML. Sin ofuscación.
 */
class VidmolyResolver : VideoResolver {
    override val name = "Vidmoly"

    override fun matches(url: String): Boolean =
        hostOf(url).contains("vidmoly")

    override suspend fun resolve(url: String): ResolvedVideo? {
        val html = httpGet(url, referer = url) ?: return null

        // Patrón 1: {file:"..."} ... label: "..."
        // Patrón 2: { file: '...' } ... label: "..."
        val patterns = listOf(
            """\{file:"([^"]+)"\}.*?label:\s*"([^"]+)"""",
            """\{\s*file:\s*'([^']+)'\s*\}[^$]+label:\s*"([^"]+)""""
        )
        for (p in patterns) {
            val m = Pattern.compile(p, Pattern.DOTALL).matcher(html)
            if (m.find()) {
                var videoUrl = m.group(1)
                // Alfa añade el referer como sufijo Kodi; aquí lo pasamos en headers
                return ResolvedVideo(videoUrl, referer = url)
            }
        }
        return null
    }
}
