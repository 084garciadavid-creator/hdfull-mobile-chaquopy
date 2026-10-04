package com.hermes.hdfull.data

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Puente local para reproductores externos que no aceptan cabeceras por Intent.
 * PVD solo ve http://127.0.0.1:<puerto>/video; las cabeceras se aplican aquí.
 */
object LocalVideoProxy {
    private const val USER_AGENT = HdfullClient.UA
    private const val MAX_HEADER_BYTES = 32 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder()
            .cookieJar(HdfullClient.sharedCookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var endpoint: String? = null

    data class Handle(val url: String, val stop: () -> Unit)

    /** Inicia/reutiliza el servidor y devuelve una URL local para PVD. */
    fun open(sourceUrl: String, referer: String? = null, headers: Map<String, String> = emptyMap()): Handle? {
        if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) return null
        return try {
            val socket = ensureServer()
            val encoded = encode(Source(sourceUrl, referer, headers))
            Handle("http://127.0.0.1:${socket.localPort}/video?u=$encoded") { }
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureServer(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        synchronized(this) {
            server?.takeIf { !it.isClosed }?.let { return it }
            val created = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = created
            endpoint = "127.0.0.1:${created.localPort}"
            scope.launch {
                while (!created.isClosed) {
                    try {
                        val socket = created.accept()
                        launch { serve(socket) }
                    } catch (_: Exception) {
                        if (!created.isClosed) continue
                    }
                }
            }
            return created
        }
    }

    private data class Source(val url: String, val referer: String?, val headers: Map<String, String>)

    private fun encode(source: Source): String {
        // El token contiene solo la URL; las cabeceras se mantienen en el primer request.
        // Las listas HLS derivadas reutilizan el referer y el User-Agent del request raíz.
        val value = listOf(source.url, source.referer ?: "", source.headers.entries.joinToString("\u001f") { "${it.key}\u001e${it.value}" })
        return Base64.encodeToString(value.joinToString("\u001d").toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun decode(token: String): Source? {
        return try {
            val parts = String(Base64.decode(token, Base64.URL_SAFE), Charsets.UTF_8).split("\u001d", limit = 3)
            if (parts.isEmpty() || parts[0].isBlank()) return null
            val headers = if (parts.size < 3 || parts[2].isBlank()) emptyMap() else parts[2].split("\u001f").mapNotNull {
                val p = it.split("\u001e", limit = 2)
                if (p.size == 2) p[0] to p[1] else null
            }.toMap()
            Source(parts[0], parts.getOrNull(1)?.ifBlank { null }, headers)
        } catch (_: Exception) { null }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 70_000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            val request = readRequest(input) ?: return
            val path = request.first
            val headers = request.second
            val token = path.substringAfter("u=", "").substringBefore('&')
            val source = decode(token) ?: return writeError(output, 400, "URL no válida")
            android.util.Log.d("VideoProxy", "=== Petición PVD ===")
            android.util.Log.d("VideoProxy", "URL final: ${source.url}")
            android.util.Log.d("VideoProxy", "Referer: ${source.referer ?: "(ninguno)"}")
            val range = headers.entries.firstOrNull { it.key.equals("range", true) }?.value
            if (range != null) android.util.Log.d("VideoProxy", "Range solicitado: $range")
            val requestBuilder = Request.Builder().url(source.url)
                .header("User-Agent", source.headers["User-Agent"] ?: USER_AGENT)
            source.referer?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Referer", it) }
            source.headers.forEach { (key, value) ->
                if (!key.equals("Host", true) && !key.equals("Range", true)) requestBuilder.header(key, value)
            }
            range?.let { requestBuilder.header("Range", it) }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    android.util.Log.e("VideoProxy", "Origen devolvió ${response.code} para ${source.url}")
                    return writeError(output, response.code, "Origen devolvió ${response.code}")
                }
                android.util.Log.d("VideoProxy", "Respuesta origen: HTTP ${response.code}")
                val contentType = response.header("Content-Type") ?: "application/octet-stream"
                val isM3u8 = contentType.contains("mpegurl", true) || source.url.contains(".m3u8", true)
                android.util.Log.d("VideoProxy", "Tipo: ${if (isM3u8) "M3U8 (playlist)" else "MP4/directo"} ($contentType)")
                val bodyBytes = if (isM3u8) {
                    android.util.Log.d("VideoProxy", "Reescribiendo playlist M3U8...")
                    val text = response.body?.string() ?: ""
                    rewritePlaylist(text, source)
                } else null
                val body = bodyBytes?.toByteArray(Charsets.UTF_8)
                val status = if (response.code == 206) "206 Partial Content" else "200 OK"
                android.util.Log.d("VideoProxy", "Enviando a PVD: HTTP $status")
                output.write("HTTP/1.1 $status\r\n".toByteArray())
                output.write("Content-Type: $contentType\r\n".toByteArray())
                output.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                output.write("Connection: close\r\n".toByteArray())
                if (body != null) {
                    output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray())
                    output.write(body)
                } else {
                    response.header("Content-Range")?.let { output.write("Content-Range: $it\r\n".toByteArray()) }
                    response.header("Accept-Ranges")?.let { output.write("Accept-Ranges: $it\r\n".toByteArray()) }
                    response.header("Content-Length")?.let { output.write("Content-Length: $it\r\n".toByteArray()) }
                    output.write("\r\n".toByteArray())
                    response.body?.byteStream()?.use { it.copyTo(output) }
                }
                output.flush()
            }
        }
    }

    private fun rewritePlaylist(text: String, parent: Source): String {
        val port = server?.localPort ?: 0
        return text.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                // Reescribir URIs en EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA
                trimmed.startsWith("#EXT-X-KEY:") ||
                trimmed.startsWith("#EXT-X-MAP:") ||
                trimmed.startsWith("#EXT-X-MEDIA:") -> {
                    rewriteUriAttribute(line, parent, port)
                }
                trimmed.startsWith("#") -> line
                else -> {
                    // Segmento .ts, .m4s o sub-playlist
                    val resolved = try {
                        parent.url.toHttpUrlOrNull()?.resolve(trimmed)?.toString() ?: trimmed
                    } catch (_: Exception) { trimmed }
                    android.util.Log.d("VideoProxy", "Segmento: $trimmed -> proxy")
                    val token = encode(Source(resolved, parent.referer, parent.headers))
                    "http://127.0.0.1:$port/resource?u=$token"
                }
            }
        }
    }

    /** Reescribe URI="..." en líneas EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA */
    private fun rewriteUriAttribute(line: String, parent: Source, port: Int): String {
        val uriPattern = Regex("""URI="([^"]+)"""")
        return uriPattern.replace(line) { match ->
            val originalUri = match.groupValues[1]
            val resolved = try {
                parent.url.toHttpUrlOrNull()?.resolve(originalUri)?.toString() ?: originalUri
            } catch (_: Exception) { originalUri }
            android.util.Log.d("VideoProxy", "URI attr: $originalUri -> proxy")
            val token = encode(Source(resolved, parent.referer, parent.headers))
            """URI="http://127.0.0.1:$port/resource?u=$token""""
        }
    }

    private fun readRequest(input: BufferedInputStream): Pair<String, Map<String, String>>? {
        val bytes = java.io.ByteArrayOutputStream()
        val marker = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (bytes.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            bytes.write(b)
            matched = if (b == marker[matched].toInt()) matched + 1 else 0
            if (matched == marker.size) break
        }
        val lines = bytes.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val target = lines.firstOrNull()?.split(' ')?.getOrNull(1) ?: return null
        val parsed = lines.drop(1).mapNotNull {
            val i = it.indexOf(':')
            if (i > 0) it.substring(0, i).trim() to it.substring(i + 1).trim() else null
        }.let { entries ->
            java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
                entries.forEach { (key, value) -> put(key, value) }
            }
        }
        return target to parsed
    }

    private fun writeError(output: BufferedOutputStream, code: Int, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 $code Error\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }
}
