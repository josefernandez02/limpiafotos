package dev.catalogo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.Article
import dev.catalogo.core.Catalog
import dev.catalogo.core.Ledger
import dev.catalogo.core.Money
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sku

private fun openCamera(context: Context, vm: MainViewModel) {
    try {
        context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        vm.say("No se encontró la cámara")
    }
}

private fun photos(n: Int) = if (n == 1) "1 foto" else "$n fotos"

// ---------- Artículos (tab) ----------

/** List filter: everything, still to upload, one account's articles, or sold in Vinted. */
private sealed interface Filter {
    data object All : Filter
    data object ToUpload : Filter
    data class Account(val deviceId: String) : Filter
    data object SoldInVinted : Filter
    data object Review : Filter
}

private fun Article.soldInVinted() = vinted.values.any { it.status == "sold" }

@Composable
fun ParentHome(vm: MainViewModel, p: ParentState) {
    val appState by vm.state.collectAsStateWithLifecycle()
    val covers by vm.articleCovers.collectAsStateWithLifecycle()
    val outbox by vm.outbox.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<Filter>(Filter.All) }
    val devices = Catalog.devices(p)
    // Sold or deleted articles stay only until the other phones confirm; they are not shown.
    val active = p.articles.filter { !it.released }.sortedByDescending { it.number }
    val pending = p.articles.size - active.size
    val shown = active.filter { a ->
        when (val f = filter) {
            Filter.All -> true
            Filter.ToUpload -> a.toUpload
            is Filter.Account -> f.deviceId in a.targets
            Filter.SoldInVinted -> a.soldInVinted()
            Filter.Review -> a.desk?.needsUser == true
        }
    }
    Scaffold(
        topBar = {
            Header("Artículos", if (active.size == 1) "1 en catálogo" else "${active.size} en catálogo") {
                if (devices.any { it.vintedId.isNotBlank() }) {
                    TextButton(onClick = { vm.go(Screen.Vinted) }) { Text("Vinted") }
                }
                IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, contentDescription = "Ajustes") }
            }
        },
        bottomBar = { MainTabs(vm, Screen.Home) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { vm.go(Screen.Editor(null)) },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Default.Add, contentDescription = "Nuevo artículo") }
        },
    ) { padding ->
        if (active.isEmpty()) {
            Empty("Pulsa + para crear tu primer artículo", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (appState.pc != null) item(key = "pc") { PcCard(vm, p) }
            item(key = "filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 2.dp)) {
                    item { Choice("Todos", filter == Filter.All) { filter = Filter.All } }
                    val review = active.count { it.desk?.needsUser == true }
                    if (review > 0) item { Choice("Revisar ($review)", filter == Filter.Review) { filter = Filter.Review } }
                    val todo = active.count { it.toUpload }
                    item { Choice("Por subir ($todo)", filter == Filter.ToUpload) { filter = Filter.ToUpload } }
                    if (devices.size > 1) {
                        items(devices, key = { it.id }) { d ->
                            val n = active.count { d.id in it.targets }
                            Choice("${d.label} ($n)", filter == Filter.Account(d.id)) { filter = Filter.Account(d.id) }
                        }
                    }
                    val sold = active.count { it.soldInVinted() }
                    if (sold > 0) item { Choice("Vendido en Vinted ($sold)", filter == Filter.SoldInVinted) { filter = Filter.SoldInVinted } }
                }
            }
            val toSend = outbox.values.sumOf { it.values.sum() }
            if (pending > 0 || toSend > 0) {
                item(key = "pending") {
                    Tile(onClick = { vm.tab(Screen.Connect) }) {
                        Row2(
                            left = {
                                Muted(
                                    listOfNotNull(
                                        if (toSend > 0) "$toSend fotos por enviar" else null,
                                        if (pending > 0) "$pending por quitar en otros móviles" else null,
                                    ).joinToString(", "),
                                )
                            },
                            right = { Text("Conectar", style = MaterialTheme.typography.labelLarge) },
                        )
                    }
                }
            }
            if (shown.isEmpty()) item(key = "none") { Muted("Ningún artículo", Modifier.padding(start = 4.dp, top = 16.dp)) }
            items(shown, key = { it.number }) { a ->
                ArticleRow(vm, p, a, covers[a.number], outbox.values.sumOf { it[a.number] ?: 0 }, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun ArticleRow(vm: MainViewModel, p: ParentState, a: Article, cover: android.net.Uri?, sending: Int, modifier: Modifier) {
    // Until the AI writes the title, the row shows what was typed instead of a provisional title.
    val title = Catalog.freshAi(p, a.number)?.titles?.get(p.self.id)
        ?: a.listing?.let { l ->
            listOf(if (l.noBrand) "Sin marca" else l.brand, l.model, l.size).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
        }
    val devices = Catalog.devices(p)
    val favourites = a.vinted.values.sumOf { it.favourites ?: 0 }
    Tile(modifier, onClick = { vm.go(Screen.Article(a.number)) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Cover(cover, size = 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Sku.number(a.number, p.digits), style = Mono.copy(fontSize = 18.sp))
                    Ledger.lot(p, a.lotId)?.let { Pill(it.name) }
                    if (a.soldInVinted()) Pill("Vendido en Vinted", strong = true)
                }
                Text(
                    title ?: "Sin datos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (title == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Each chosen account with how far the article is there: usually one line.
                val targets = devices.filter { it.id in a.targets }.ifEmpty { if (devices.size == 1) devices else emptyList() }
                if (targets.isEmpty()) Muted("Sin cuenta elegida", maxLines = 1)
                for (d in targets) AccountLine(d.label, accountStatus(p, a, d))
                val extra = listOfNotNull(
                    if (favourites > 0) "♥ $favourites" else null,
                    if (sending > 0) "$sending fotos por enviar" else null,
                )
                if (extra.isNotEmpty()) Muted(extra.joinToString(GAP), maxLines = 1)
            }
            IconButton(onClick = { vm.go(Screen.Editor(a.number)) }) {
                Icon(Icons.Default.Edit, contentDescription = "Editar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------- Ficha del artículo ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArticleScreen(vm: MainViewModel, p: ParentState, number: Int) {
    val context = LocalContext.current
    val counts by vm.counts.collectAsStateWithLifecycle()
    val outbox by vm.outbox.collectAsStateWithLifecycle()
    val article = Catalog.article(p, number)
    var selling by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var soldBy by remember(article?.vinted) {
        mutableStateOf(article?.vinted?.entries?.firstOrNull { it.value.status == "sold" }?.key ?: p.self.id)
    }

    Scaffold(
        topBar = {
            Bar("", onBack = { vm.back() }) {
                if (article != null && !article.released) {
                    IconButton(onClick = { vm.go(Screen.Editor(number)) }) { Icon(Icons.Default.Edit, contentDescription = "Editar") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, contentDescription = "Eliminar") }
                }
            }
        },
        bottomBar = {
            if (article != null && !article.released) {
                BottomActions {
                    OutlinedButton(
                        onClick = { selling = true },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { Text("Vendido", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
    ) { padding ->
        if (article == null || article.released) {
            Empty("Artículo vendido", Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.padding(start = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(Sku.number(number, p.digits), style = Mono.copy(fontSize = 34.sp))
                Ledger.lot(p, article.lotId)?.let { Pill(it.name) }
                Ledger.costOf(p, article)?.let { Pill("Coste ${Money.format(it)}") }
            }

            article.vinted.entries.firstOrNull { it.value.status == "sold" }?.let { (deviceId, item) ->
                Tile {
                    Text("Vendido en Vinted", style = MaterialTheme.typography.titleMedium)
                    Muted(listOfNotNull(Catalog.device(p, deviceId)?.label, item.price?.let(Money::format)).joinToString(GAP))
                    FilledTonalButton(
                        onClick = { selling = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Registrar la venta") }
                }
            }

            Section("Vinted")
            val appState by vm.state.collectAsStateWithLifecycle()
            VintedCard(vm, p, article, appState, outbox.mapValues { it.value[number] ?: 0 })

            // Photos are always taken here; other accounts get a copy when chosen.
            val ownSku = Sku.format(p.self.prefix, number, p.digits)
            val ownCount = counts[ownSku] ?: 0
            val waitingAll = outbox.values.sumOf { it[number] ?: 0 }
            val strip by produceState(emptyList<android.net.Uri>(), ownSku, ownCount, waitingAll) {
                value = vm.albumPhotos(ownSku).ifEmpty { vm.aiPhotos(number) }
            }
            Section("Fotos")
            Tile {
                if (strip.isEmpty()) Muted("Sin fotos") else PhotoStrip(strip, onOpen = { vm.openViewer(strip, it) })
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { vm.startSession(number, p.self.id) { openCamera(context, vm) } },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { OneLine(if (ownCount == 0) "Hacer fotos" else "Añadir fotos") }
                    FilledTonalButton(
                        onClick = { vm.go(Screen.Photos(number, p.self.id)) },
                        enabled = ownCount > 0,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { OneLine("Ordenar y recortar") }
                }
            }

            Section("Anuncio")
            ListingPreview(p, article, onEdit = { vm.go(Screen.Editor(number)) })
            Spacer(Modifier.height(8.dp))
        }
    }

    if (deleting) {
        Confirm(
            title = "Eliminar ${Sku.number(number, p.digits)}",
            text = "Se borran sus fotos y su anuncio en todos los móviles, sin contar como venta.",
            confirm = "Eliminar",
            destructive = true,
            onConfirm = { vm.deleteArticle(number) },
            onDismiss = { deleting = false },
        )
    }

    if (selling && article != null) {
        val cost = Ledger.costOf(p, article)
        SellDialog(
            title = "Vender ${Sku.number(number, p.digits)}",
            initialPrice = Money.input(article.vinted.values.firstOrNull { it.status == "sold" }?.price),
            initialCost = Money.input(cost),
            costHint = Ledger.lot(p, article.lotId)?.let { "Coste por prenda de ${it.name}" },
            onDismiss = { selling = false },
            onConfirm = { price, c -> vm.sellOnParent(number, price, c, soldBy) },
            extra = if (Catalog.devices(p).size > 1) {
                {
                    Muted("Vendido en")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (d in Catalog.devices(p)) Choice(d.label, soldBy == d.id) { soldBy = d.id }
                    }
                }
            } else null,
        )
    }
}

@Composable
fun SessionScreen(vm: MainViewModel, p: ParentState) {
    val context = LocalContext.current
    val session = p.session ?: return
    val device = Catalog.device(p, session.deviceId)
    val sku = Sku.format(device?.prefix ?: "", session.number, p.digits)
    var confirmCancel by remember { mutableStateOf(false) }
    val withMedia = rememberWithPermissions(Perms.media, onDenied = { vm.say("Permite el acceso a las fotos") }) { vm.finishSession() }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Muted(device?.name ?: "")
            Text(sku, style = Mono.copy(fontSize = 40.sp), modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Haz las fotos y edítalas si quieres.\nLuego vuelve y pulsa Terminar.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            BigButton("Abrir cámara", onClick = { openCamera(context, vm) })
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = withMedia,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Terminar") }
            TextButton(onClick = { confirmCancel = true }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Cancelar", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmCancel) {
        Confirm(
            title = "Cancelar",
            text = "Las fotos hechas se quedan en la galería sin tocar.",
            confirm = "Cancelar sesión",
            onConfirm = { vm.cancelSession() },
            onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
fun ReviewScreen(vm: MainViewModel, p: ParentState) {
    val photos by vm.review.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val session = p.session
    val device = session?.let { Catalog.device(p, it.deviceId) }
    val title = if (session != null && device != null) Sku.format(device.prefix, session.number, p.digits) else ""
    val goBack: () -> Unit = { vm.back() }

    Scaffold(
        topBar = { Bar(title, onBack = if (progress == null) goBack else null) },
        bottomBar = {
            BottomActions {
                val current = progress
                if (current != null) {
                    Muted("Guardando ${current.first} de ${current.second}", Modifier.padding(bottom = 8.dp))
                    LinearProgressIndicator(
                        progress = { current.first.toFloat() / current.second.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    BigButton("Guardar ${selected.size}", onClick = { vm.saveReview() }, enabled = selected.isNotEmpty())
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(photos, key = { it.toString() }) { uri ->
                val on = uri in selected
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(shape)
                        .border(2.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background, shape)
                        .clickable(enabled = progress == null) { vm.toggle(uri) },
                ) {
                    Thumb(uri, Modifier.fillMaxSize())
                    if (on) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                    } else {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f)))
                    }
                }
            }
        }
    }
}
