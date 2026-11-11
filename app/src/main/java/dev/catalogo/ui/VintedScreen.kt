package dev.catalogo.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sku
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val HOME = "https://www.vinted.es/"
private val LOGIN_HOSTS = listOf("vinted.es", "vinted.com", "google.com", "apple.com", "facebook.com")

/** Receives the answers of requests made by the Vinted page. */
private class Bridge {
    val waiting = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()

    @JavascriptInterface
    fun done(id: String, status: Int, body: String) {
        waiting.remove(id)?.complete(status to body)
    }
}

/**
 * Vinted's own web page inside the app. It is visible so any login or check
 * Vinted asks for can be done by hand; the wardrobes are then read with the
 * same request the profile page makes, nothing else.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VintedScreen(vm: MainViewModel, p: ParentState) {
    val check by vm.vinted.collectAsStateWithLifecycle()
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    val bridge = remember { Bridge() }

    DisposableEffect(Unit) {
        onDispose {
            bridge.waiting.values.forEach { it.complete(0 to "") }
            web?.destroy()
            vm.clearVinted()
        }
    }

    suspend fun fetch(path: String): Pair<Int, String> {
        val view = web ?: return 0 to ""
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<Pair<Int, String>>()
        bridge.waiting[id] = answer
        val js = "fetch(${JSONObject.quote(path)},{credentials:'include',headers:{'Accept':'application/json'}})" +
            ".then(r=>r.text().then(t=>CatalogoBridge.done('$id',r.status,t)))" +
            ".catch(e=>CatalogoBridge.done('$id',0,String(e)))"
        withContext(Dispatchers.Main) {
            val host = runCatching { java.net.URI(view.url ?: "").host.orEmpty() }.getOrDefault("")
            if (host.endsWith("vinted.es")) view.evaluateJavascript(js, null) else answer.complete(0 to "")
        }
        return withTimeoutOrNull(30_000) { answer.await() } ?: (0 to "").also { bridge.waiting.remove(id) }
    }

    Scaffold(
        topBar = { Bar("Vinted", onBack = { vm.back() }) },
        bottomBar = {
            BottomActions {
                val c = check
                if (c != null) {
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (line in c.lines) Text(line, style = MaterialTheme.typography.bodyMedium)
                        if (c.sold.isNotEmpty()) {
                            Text(
                                "Vendidos en Vinted: " + c.sold.joinToString(", ") { Sku.number(it, p.digits) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (c.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                BigButton(
                    if (ready) "Comprobar cuentas" else "Cargando Vinted…",
                    enabled = ready && check?.running != true,
                    onClick = { vm.checkVinted { path -> fetch(path) } },
                )
            }
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(bridge, "CatalogoBridge")
                    webViewClient = object : WebViewClient() {
                        // Only Vinted pages (and its login providers) open here; anything else is ignored.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val host = request.url.host.orEmpty()
                            return LOGIN_HOSTS.none { host == it || host.endsWith(".$it") }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            ready = runCatching { java.net.URI(url).host.orEmpty().endsWith("vinted.es") }.getOrDefault(false)
                        }
                    }
                    loadUrl(HOME)
                    web = this
                }
            },
        )
    }
}
