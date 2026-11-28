package dev.catalogo.ui

import androidx.compose.foundation.layout.statusBarsPadding

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.material3.Surface

import androidx.compose.material.icons.filled.Close

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.Article
import dev.catalogo.core.Catalog
import dev.catalogo.core.Ledger
import dev.catalogo.core.CatalogState
import dev.catalogo.core.Sku
import dev.catalogo.core.Stage

/** List filter: everything, still to upload, one account's articles, or sold in Vinted. */
private sealed interface Filter {
    data object All : Filter
    data class InStage(val stage: Stage) : Filter
    data class Account(val accountId: String) : Filter
}

private fun Article.soldInVinted() = vinted.values.any { it.status == "sold" }

@Composable
fun ArticleListScreen(vm: MainViewModel, p: CatalogState) {
    val appState by vm.state.collectAsStateWithLifecycle()
    val covers by vm.articleCovers.collectAsStateWithLifecycle()
    val accountPhotos by vm.accountPhotos.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<Filter>(Filter.All) }
    // Long press: select several to change their status at once.
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var bulk by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    val allAccounts = Catalog.allAccounts(p)
    // Articles in a sale in progress are in Ventas, not here.
    val active = p.articles.filter { it.inStock }.sortedByDescending { it.number }
    val shown = active.filter { a ->
        when (val f = filter) {
            Filter.All -> true
            is Filter.InStage -> Catalog.stage(p, a, p.mainAccount) == f.stage
            is Filter.Account -> f.accountId in a.targets
        }
    }
    Scaffold(
        topBar = {
            if (selected.isNotEmpty()) {
                val all = shown.isNotEmpty() && shown.all { it.number in selected }
                SelectionBar(
                    count = selected.size,
                    all = all,
                    onAll = { selected = if (all) emptySet() else shown.map { it.number }.toSet() },
                    onClose = { selected = emptySet() },
                )
            } else {
                Header("Artículos", if (active.size == 1) "1 en catálogo" else "${active.size} en catálogo") {
                    IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, contentDescription = "Ajustes") }
                }
            }
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    FilledTonalButton(
                        onClick = { bulk = true },
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("Cambiar estado", maxLines = 1) }
                }
            } else MainTabs(vm, Screen.Home)
        },
        floatingActionButton = {
            if (selected.isEmpty()) FloatingActionButton(
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 2.dp)) {
                    item { Choice("Todos", filter == Filter.All) { filter = Filter.All } }
                    // One filter per status that has articles, in the order they move.
                    val byStage = active.groupingBy { Catalog.stage(p, it, p.mainAccount) }.eachCount()
                    for (st in Stage.entries) {
                        val n = byStage[st] ?: 0
                        if (n > 0) item(key = st.name) { Choice("${st.label} ($n)", filter == Filter.InStage(st)) { filter = Filter.InStage(st) } }
                    }
                    if (allAccounts.size > 1) {
                        items(allAccounts, key = { it.id }) { d ->
                            val n = active.count { d.id in it.targets }
                            Choice("${d.label} ($n)", filter == Filter.Account(d.id)) { filter = Filter.Account(d.id) }
                        }
                    }
                }
            }
            if (shown.isEmpty()) item(key = "none") { Muted("Ningún artículo", Modifier.padding(start = 4.dp, top = 16.dp)) }
            items(shown, key = { it.number }) { a ->
                val n = a.number
                ArticleRow(
                    vm, p, a, covers[n], accountPhotos.values.sumOf { it[n] ?: 0 }, Modifier.animateItem(),
                    selected = n in selected,
                    onClick = { if (selected.isEmpty()) vm.go(Screen.Article(n)) else selected = if (n in selected) selected - n else selected + n },
                    onLongClick = { selected = if (n in selected) selected - n else selected + n },
                )
            }
        }
    }
    if (bulk) {
        StageManyDialog(vm, selected.toList(), onDismiss = { bulk = false }, onDone = { selected = emptySet() })
    }
}

@Composable
private fun ArticleRow(
    vm: MainViewModel,
    p: CatalogState,
    a: Article,
    cover: android.net.Uri?,
    sending: Int,
    modifier: Modifier,
    selected: Boolean = false,
    onClick: () -> Unit = { vm.go(Screen.Article(a.number)) },
    onLongClick: (() -> Unit)? = null,
) {
    // Short and readable: brand and model («Levi's 512»), or brand and garment when there is
    // no model. The long Vinted title is in the article page.
    val title = a.listing?.let { l ->
        val what = l.model.ifBlank { dev.catalogo.core.Garments.type(l.type)?.label.orEmpty().lowercase() }
        listOf(l.brand, what).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
    }
    val allAccounts = Catalog.allAccounts(p)
    val favourites = a.vinted.values.sumOf { it.favourites ?: 0 }
    Tile(modifier, onClick = onClick, onLongClick = onLongClick, selected = selected) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Cover(cover, size = 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Sku.number(a.number, p.digits), style = Mono.copy(fontSize = 18.sp))
                    Ledger.lot(p, a.lotId)?.let { Pill(it.name) }
                    if (a.soldInVinted()) Pill("Vendido en Vinted", strong = true)
                    if (a.relist != null) Pill("Resubir")
                }
                Text(
                    title ?: "Sin datos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (title == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Each chosen account with how far the article is there: usually one line.
                val targets = allAccounts.filter { it.id in a.targets }.ifEmpty { if (allAccounts.size == 1) allAccounts else emptyList() }
                if (targets.isEmpty()) Muted("Sin cuenta elegida", maxLines = 1)
                for (d in targets) AccountLine(d.label, Catalog.stage(p, a, d))
                val extra = listOfNotNull(
                    if (favourites > 0) "♥ $favourites" else null,
                    if (sending > 0) "$sending fotos por enviar" else null,
                )
                if (extra.isNotEmpty()) Muted(extra.joinToString(GAP), maxLines = 1)
            }
        }
    }
}

// ---------- Ficha del artículo ----------

/** Compact bar while several articles are selected: count, select all, close. */
@Composable
private fun SelectionBar(count: Int, all: Boolean, onAll: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cancelar") }
        Text(
            if (count == 1) "1 seleccionado" else "$count seleccionados",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        TextButton(onClick = onAll) { Text(if (all) "Ninguno" else "Todos", maxLines = 1) }
    }
}
