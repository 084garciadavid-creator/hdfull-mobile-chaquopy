package com.hermes.hdfull.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hermes.hdfull.data.HdfullClient
import com.hermes.hdfull.data.LocalVideoProxy
import com.hermes.hdfull.data.PvdPlayer
import com.hermes.hdfull.data.SessionManager
import com.hermes.hdfull.data.VideoLink
import com.hermes.hdfull.data.WebViewLinkExtractor
import com.hermes.hdfull.data.WebViewVideoInterceptor
import com.hermes.hdfull.data.resolvers.ResolverRegistry
import kotlinx.coroutines.launch

/**
 * Pantalla de enlaces: primero intenta extracción nativa; si la lista viene
 * cifrada, usa un WebView invisible donde la propia página de HDFull descifra
 * los enlaces con su JavaScript. El WebView nunca es visible (0x0).
 * Los enlaces se muestran en UI 100% nativa.
 */
@Composable
fun LinksScreen(
    session: SessionManager,
    url: String,
    title: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val host = session.host
    var links by remember { mutableStateOf<List<VideoLink>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Buscando enlaces…") }
    var showPvdDialog by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf("") }
    // embedUrl resuelta por servidor (para no resolver dos veces)
    val embedCache = remember { mutableMapOf<String, String>() }

    LaunchedEffect(url) {
        try {
            status = "Buscando enlaces…"
            // 1. Intento nativo rápido (por si la estructura viniera en el HTML)
            var servers = HdfullClient.serverLinks(host, url)
            // 2. Si no hay nada, WebView invisible: la propia página descifra los enlaces
            if (servers.isEmpty()) {
                status = "Descifrando enlaces…"
                val fullUrl = HdfullClient.resolveUrl(host, url)
                when (val r = WebViewLinkExtractor.extractWithStatus(context, fullUrl)) {
                    is WebViewLinkExtractor.ExtractResult.Success -> {
                        servers = r.links.map { s ->
                            val href = if (s.extUrl.startsWith("http")) s.extUrl
                            else host.trimEnd('/') + "/" + s.extUrl.trimStart('/')
                            s.copy(extUrl = href)
                        }
                    }
                    is WebViewLinkExtractor.ExtractResult.CloudflareBlocked -> {
                        loading = false
                        status = "HDFull pide verificación de seguridad (Cloudflare)"
                        return@LaunchedEffect
                    }
                    is WebViewLinkExtractor.ExtractResult.NoSession -> {
                        loading = false
                        status = "Sesión caducada: vuelve a iniciar sesión"
                        return@LaunchedEffect
                    }
                    is WebViewLinkExtractor.ExtractResult.Timeout -> {
                        loading = false
                        status = "Tiempo agotado esperando los enlaces"
                        return@LaunchedEffect
                    }
                    is WebViewLinkExtractor.ExtractResult.Error -> {
                        loading = false
                        status = "Error: ${r.message}"
                        return@LaunchedEffect
                    }
                }
            }
            if (servers.isEmpty()) {
                loading = false
                status = "No se encontraron enlaces"
                return@LaunchedEffect
            }
            val out = mutableListOf<VideoLink>()
            for (s in servers) {
                val label = buildString {
                    if (s.language.isNotBlank()) append(s.language)
                    if (s.server.isNotBlank()) { if (isNotEmpty()) append(" · "); append(s.server) }
                    if (s.quality.isNotBlank()) { if (isNotEmpty()) append(" · "); append(s.quality) }
                }.ifBlank { s.server.ifBlank { "Ver" } }
                // Guardamos la extUrl; se resuelve al pulsar
                out.add(VideoLink(s.extUrl, label, s.language, s.quality))
                // Pre-cache del servidor para mostrarlo
                embedCache[s.extUrl] = s.server
            }
            links = out.distinctBy { it.url }
            loading = false
        } catch (e: Exception) {
            loading = false
            status = "Error: ${e.message}"
        }
    }

    fun playLink(link: VideoLink) {
        scope.launch {
            status = "Resolviendo ${link.url.substringAfter("://").substringBefore("/")}…"
            loading = true
            try {
                // 1. Resolver /ext/... -> URL del embed
                val embedUrl = HdfullClient.resolveExtUrl(link.url)
                if (embedUrl.isNullOrBlank()) {
                    status = "No se pudo obtener el reproductor"
                    loading = false
                    return@launch
                }
                // 2. Resolver embed -> URL directa con resolutores nativos
                var videoUrl: String? = null
                var videoReferer: String? = null
                var videoHeaders: Map<String, String> = emptyMap()
                val resolved = ResolverRegistry.resolve(embedUrl)
                if (resolved != null && WebViewVideoInterceptor.isDirectVideoUrl(resolved.url)) {
                    videoUrl = resolved.url
                    videoReferer = resolved.referer
                    videoHeaders = resolved.headers
                    android.util.Log.d("HDFull", "Resolutor OK: ${resolved.url}")
                    android.util.Log.d("HDFull", "Tipo: ${if (resolved.url.contains(".m3u8")) "M3U8" else "MP4/directo"}")
                } else {
                    android.util.Log.d("HDFull", "Resolutor falló para $embedUrl, usando WebView")
                }
                // 3. Fallback: interceptar con WebView invisible
                if (videoUrl == null) {
                    status = "Extrayendo vídeo…"
                    val intercepted = WebViewVideoInterceptor.interceptDetailed(context, embedUrl)
                    videoUrl = intercepted?.url
                    videoReferer = intercepted?.referer ?: embedUrl
                    videoHeaders = intercepted?.headers ?: emptyMap()
                    if (!intercepted?.cookieHeader.isNullOrBlank()) {
                        videoHeaders = videoHeaders + ("Cookie" to intercepted!!.cookieHeader!!)
                    }
                    // El WebView puede haber recibido cookies de Cloudflare o del host.
                    // Se copian al CookieJar que utiliza el proxy local.
                    HdfullClient.importWebViewCookies(embedUrl)
                    if (!videoUrl.isNullOrBlank()) HdfullClient.importWebViewCookies(videoUrl!!)
                }
                loading = false
                if (videoUrl.isNullOrBlank()) {
                    status = "No se pudo extraer el vídeo de este servidor"
                    return@launch
                }
                // PVD no garantiza soporte para headers enviados por Intent. El proxy
                // local los aplica y entrega a PVD una URL reproducible por HTTP.
                val proxy = LocalVideoProxy.open(
                    sourceUrl = videoUrl,
                    referer = videoReferer,
                    headers = videoHeaders + ("User-Agent" to HdfullClient.UA)
                )
                val playerUrl = proxy?.url ?: videoUrl
                // 4. Estilo DixMax: reproducir en PVD externo vía proxy local
                if (PvdPlayer.isInstalled(context)) {
                    val ok = PvdPlayer.play(context, playerUrl, title)
                    if (!ok) status = "No se pudo abrir PVD"
                } else {
                    // Pedir instalación de PVD como hace DixMax
                    showPvdDialog = true
                    pendingUrl = videoUrl
                }
            } catch (e: Exception) {
                loading = false
                status = "Error: ${e.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().background(BgBlack)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Gold)
            }
            Text(
                title.ifBlank { "Enlaces" },
                color = TextWhite,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Gold)
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = TextGrey)
                }
                links.isEmpty() -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(status, color = TextGrey)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = Gold)
                    ) { Text("Volver", color = androidx.compose.ui.graphics.Color.Black) }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(links) { link ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(BgCard)
                                .clickable { playLink(link) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Gold,
                                modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(link.label, color = TextWhite, style = MaterialTheme.typography.bodyLarge)
                                val srv = embedCache[link.url] ?: ""
                                if (srv.isNotBlank()) {
                                    Text(srv, color = TextGrey, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Diálogo estilo DixMax: instalar PVD para reproducir
    if (showPvdDialog) {
        AlertDialog(
            onDismissRequest = { showPvdDialog = false },
            title = { Text("Reproductor necesario", color = TextWhite) },
            text = {
                Text(
                    "Para poder reproducir de forma fluida necesitas instalar un reproductor compatible.",
                    color = TextGrey
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPvdDialog = false
                        PvdPlayer.openPlayStore(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold)
                ) { Text("INSTALAR PVD", color = androidx.compose.ui.graphics.Color.Black) }
            },
            dismissButton = {
                TextButton(onClick = { showPvdDialog = false }) {
                    Text("Cancelar", color = Gold)
                }
            },
            containerColor = BgCard
        )
    }
}
