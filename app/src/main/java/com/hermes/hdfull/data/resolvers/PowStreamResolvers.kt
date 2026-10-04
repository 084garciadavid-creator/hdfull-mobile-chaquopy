package com.hermes.hdfull.data.resolvers

import java.util.regex.Pattern

/**
 * Desempaquetador de Dean Edwards packer: eval(function(p,a,c,k,e,d){...}).
 * Extraído de MixdropResolver para reutilizar en Powvideo/Streamplay.
 */
internal fun jsUnpack(html: String): String? {
    return try {
        val m = Pattern.compile(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            Pattern.DOTALL
        ).matcher(html)
        if (!m.find()) return null
        var p = m.group(1)
        val a = m.group(2).toInt()
        val c = m.group(3).toInt()
        val k = m.group(4).split("|")
        fun baseN(n: Int, base: Int): String {
            if (n == 0) return "0"
            val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var num = n
            var s = ""
            while (num > 0) { s = digits[num % base] + s; num /= base }
            return s
        }
        for (i in c - 1 downTo 0) {
            val word = if (i < k.size) k[i] else ""
            if (word.isNotEmpty()) {
                p = p.replace(Regex("\\b${baseN(i, a)}\\b"), word)
            }
        }
        p.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
    } catch (e: Exception) {
        null
    }
}

/**
 * Resolutor para Powvideo (powvideo.org).
 * Portado de plugin.video.alfa/servers/powvideo.py.
 * Desempaqueta el JS y extrae la URL del mp4.
 * Nota: Alfa aplica decode_video_url ofuscado; se intenta sin él primero.
 */
class PowvideoResolver : VideoResolver {
    override val name = "Powvideo"

    override fun matches(url: String): Boolean =
        hostOf(url).contains("powvideo")

    override suspend fun resolve(url: String): ResolvedVideo? {
        val host = hostOf(url).ifBlank { return null }
        val referer = url.replace("iframe", "preview")
        val html = httpGet(url, referer = referer) ?: return null

        if (html == "File was deleted" || html.isBlank()) return null
        if ("function(p,a,c,k,e," !in html) return null

        // Extraer el bloque packed y desempaquetar
        val packed = Pattern.compile(
            """<script type=["']text/javascript["']>(eval.*?)</script>""",
            Pattern.DOTALL
        ).matcher(html)
        if (!packed.find()) return null

        val unpacked = jsUnpack(packed.group(1)) ?: jsUnpack(html) ?: return null

        // Patrón de Alfa: (?:src):\\'([^\\]+.mp4)\\'
        val m = Pattern.compile("""(?:src):\\'([^\\]+.mp4)\\'""").matcher(unpacked)
        if (m.find()) {
            var videoUrl = m.group(1)
            // Normalizar
            if (videoUrl.startsWith("//")) videoUrl = "https:$videoUrl"
            return ResolvedVideo(videoUrl, referer = url)
        }

        // Fallback: buscar mp4 directo en el desempaquetado
        val direct = Pattern.compile("""(https?://[^"'\s\\]+\.mp4[^"'\s\\]*)""").matcher(unpacked)
        if (direct.find()) {
            return ResolvedVideo(direct.group(1), referer = url)
        }
        return null
    }
}

/**
 * Resolutor para Streamplay (streamplay.to).
 * Portado de plugin.video.alfa/servers/streamplay.py.
 */
class StreamplayResolver : VideoResolver {
    override val name = "Streamplay"

    override fun matches(url: String): Boolean =
        hostOf(url).contains("streamplay")

    override suspend fun resolve(url: String): ResolvedVideo? {
        val host = hostOf(url).ifBlank { return null }
        val referer = url.replace("player-", "embed-")
        val html = httpGet(url, referer = referer) ?: return null

        if ("File was deleted" in html || "Not Found" in html) return null
        if ("Video is processing now" in html) return null

        val packed = Pattern.compile(
            """<script type=["']text/javascript["']>(eval.*?)</script>""",
            Pattern.DOTALL
        ).matcher(html)
        if (!packed.find()) return null

        val unpacked = jsUnpack(packed.group(1)) ?: jsUnpack(html) ?: return null

        // Patrón de Alfa: sources=([...])
        val sourcesM = Pattern.compile("""sources=(\[[^\]]+\])""").matcher(unpacked)
        if (sourcesM.find()) {
            val sourcesStr = sourcesM.group(1)
            // Extraer URLs del array (pueden estar entre comillas simples o dobles)
            val urlM = Pattern.compile("""["'](https?://[^"']+)["']""").matcher(sourcesStr)
            val candidates = mutableListOf<String>()
            while (urlM.find()) {
                val u = urlM.group(1)
                if (!u.endsWith(".mpd")) candidates.add(u)
            }
            // Preferir la de mayor calidad (última suele ser mejor, o buscar m3u8)
            val m3u8 = candidates.firstOrNull { it.contains("m3u8") }
            val best = m3u8 ?: candidates.lastOrNull() ?: candidates.firstOrNull()
            if (best != null) {
                return ResolvedVideo(best, referer = url)
            }
        }

        // Fallback: mp4/m3u8 directo
        val direct = Pattern.compile("""(https?://[^"'\s\\]+\.(?:mp4|m3u8)[^"'\s\\]*)""").matcher(unpacked)
        if (direct.find()) {
            return ResolvedVideo(direct.group(1), referer = url)
        }
        return null
    }
}
