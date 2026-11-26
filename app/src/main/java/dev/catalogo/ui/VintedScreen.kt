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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.ParentState
import dev.catalogo.core.SalesSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** Between two requests to Vinted: asking quickly gets «too many requests». */
private const val PAUSE_MS = 1_300L

private fun isVinted(host: String) = host == "vinted.es" || host.endsWith(".vinted.es")

/**
 * Vinted's own web page inside the app. It is visible so any login or check
 * Vinted asks for can be done by hand; the sales are then read with the same
 * requests Vinted's pages make, nothing else. With [auto] the synchronisation
 * starts as soon as the page is ready.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VintedScreen(vm: MainViewModel, p: ParentState, auto: Boolean = false) {
    val check by vm.vinted.collectAsStateWithLifecycle()
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    val bridge = remember { Bridge() }

    var started by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Looks up what one order was; see the file itself.
    val orderScript = remember { context.assets.open("vinted_order.js").bufferedReader().use { it.readText() } }

    DisposableEffect(Unit) {
        onDispose {
            vm.leaveVinted()
            bridge.waiting.values.forEach { it.complete(0 to "") }
            web?.destroy()
            web = null
        }
    }

    /** Runs [call] (an expression giving a promise of [status, body]) in the Vinted page. */
    suspend fun ask(call: String, timeout: Long): Pair<Int, String> {
        val view = web ?: return 0 to ""
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<Pair<Int, String>>()
        bridge.waiting[id] = answer
        val js = "$call.then(a=>CatalogoBridge.done('$id',a[0],a[1])).catch(e=>CatalogoBridge.done('$id',0,String(e)))"
        withContext(Dispatchers.Main) {
            val host = runCatching { java.net.URI(view.url ?: "").host.orEmpty() }.getOrDefault("")
            if (isVinted(host)) view.evaluateJavascript(js, null) else answer.complete(0 to "")
        }
        return withTimeoutOrNull(timeout) { answer.await() } ?: (0 to "").also { bridge.waiting.remove(id) }
    }

    val page = remember {
        object : SalesSync.Page {
            override suspend fun get(path: String) = ask(
                "fetch(${JSONObject.quote(path)},{credentials:'include',headers:{'Accept':'application/json'}})" +
                    ".then(r=>r.text().then(t=>[r.status,t]))",
                30_000,
            )

            override suspend fun deal(order: String): Pair<Int, String> {
                // The number goes into the script: digits only.
                if (order.isEmpty() || !order.all(Char::isDigit)) return 0 to ""
                return ask("(function(){$orderScript\nreturn catalogoOrder('$order');})()", 60_000)
            }

            override suspend fun pause() = delay(PAUSE_MS)
        }
    }

    LaunchedEffect(ready) {
        if (auto && ready && !started) {
            started = true
            vm.syncSales(page)
        }
    }

    Scaffold(
        topBar = { Bar("Ventas de Vinted", onBack = { vm.back() }) },
        bottomBar = {
            BottomActions {
                val c = check
                if (c != null) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(bottom = 4.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (line in c.lines) Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (c.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                BigButton(
                    when {
                        !ready -> "Cargando Vinted…"
                        check?.running == true -> "Sincronizando…"
                        else -> "Sincronizar ventas"
                    },
                    enabled = ready && check?.running != true,
                    onClick = { vm.syncSales(page) },
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
                            ready = isVinted(runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault(""))
                        }
                    }
                    loadUrl(HOME)
                    web = this
                }
            },
        )
    }
}
