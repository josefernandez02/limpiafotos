package dev.catalogo.ui.articles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.catalog.Ledger
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.Money
import dev.catalogo.core.model.Sku
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.Screen
import dev.catalogo.ui.common.Bar
import dev.catalogo.ui.common.BottomActions
import dev.catalogo.ui.common.Choice
import dev.catalogo.ui.common.Confirm
import dev.catalogo.ui.common.Empty
import dev.catalogo.ui.common.GAP
import dev.catalogo.ui.common.ListingPreview
import dev.catalogo.ui.common.Mono
import dev.catalogo.ui.common.Muted
import dev.catalogo.ui.common.OneLine
import dev.catalogo.ui.common.PhotoStrip
import dev.catalogo.ui.common.Pill
import dev.catalogo.ui.common.Section
import dev.catalogo.ui.common.Tile
import dev.catalogo.ui.common.VintedCard
import dev.catalogo.ui.photos.openCamera
import dev.catalogo.ui.sales.HeldArticle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArticleScreen(vm: MainViewModel, p: CatalogState, number: Int) {
    val context = LocalContext.current
    val counts by vm.counts.collectAsStateWithLifecycle()
    val accountPhotos by vm.accountPhotos.collectAsStateWithLifecycle()
    val article = Catalog.article(p, number)
    var selling by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var relisting by remember { mutableStateOf(false) }
    var soldBy by remember(article?.vinted) {
        mutableStateOf(article?.vinted?.entries?.firstOrNull { it.value.status == "sold" }?.key ?: p.mainAccount.id)
    }

    if (article != null && article.held) {
        HeldArticle(vm, p, article)
        return
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { relisting = true },
                            modifier = Modifier.weight(1f).height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Resubir") }
                        OutlinedButton(
                            onClick = { selling = true },
                            modifier = Modifier.weight(1f).height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Vendido", color = MaterialTheme.colorScheme.error) }
                    }
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

            if (article.relist != null) {
                Tile {
                    Text("Resubida en curso", style = MaterialTheme.typography.titleMedium)
                    Muted("Haz las fotos nuevas y prepara el borrador: la IA cambiará el título y los hashtags (hasta entonces no se envía nada). El resto de datos se queda; puedes editarlos.")
                    Muted("Recuerda borrar el anuncio antiguo en Vinted.")
                }
            }

            article.vinted.entries.firstOrNull { it.value.status == "sold" }?.let { (accountId, item) ->
                Tile {
                    Text("Vendido en Vinted", style = MaterialTheme.typography.titleMedium)
                    Muted(listOfNotNull(Catalog.findAccount(p, accountId)?.label, item.price?.let(Money::format)).joinToString(GAP))
                    Muted("No hace falta marcarla: el PC la apunta al leer tus ventas.")
                }
            }

            Section("Vinted")
            val appState by vm.state.collectAsStateWithLifecycle()
            VintedCard(vm, p, article, appState, accountPhotos.mapValues { it.value[number] ?: 0 })

            // Photos are always taken here; other accounts get a copy when chosen.
            val ownSku = Sku.format(p.mainAccount.prefix, number, p.digits)
            val ownCount = counts[ownSku] ?: 0
            val waitingAll = accountPhotos.values.sumOf { it[number] ?: 0 }
            val strip by produceState(emptyList<android.net.Uri>(), ownSku, ownCount, waitingAll) {
                value = vm.albumPhotos(ownSku).ifEmpty { vm.aiPhotos(number) }
            }
            Section("Fotos")
            Tile {
                if (strip.isEmpty()) Muted("Sin fotos") else PhotoStrip(strip, onOpen = { vm.openViewer(strip, it) })
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { vm.startSession(number, p.mainAccount.id) { openCamera(context, vm) } },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { OneLine(if (ownCount == 0) "Hacer fotos" else "Añadir fotos") }
                    FilledTonalButton(
                        onClick = { vm.go(Screen.Photos(number, p.mainAccount.id)) },
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

    if (relisting) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { relisting = false },
            title = { Text("Resubir ${Sku.number(number, p.digits)}") },
            text = {
                Column {
                    Text(
                        "Se borran sus fotos (Android te pedirá permiso para las de la galería) y vuelve a «Por subir». Se conserva el resto de datos. " +
                            "Con las fotos nuevas, la IA escribirá otro título y otros hashtags, y no se envía hasta que cambien. El anuncio antiguo de Vinted lo borras tú.",
                    )
                    Text(
                        "¿Ya hiciste las fotos nuevas sin pulsar Resubir? Esta opción hace lo mismo pero NO borra nada: se quedan las fotos que tiene ahora.",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { relisting = false; vm.relist(number, keepPhotos = true) }) {
                        Text("Resubir con las fotos que ya tiene")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { relisting = false; vm.relist(number) }) {
                    Text("Resubir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { relisting = false }) { Text("Cancelar") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
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
            onConfirm = { price, c, paid, free -> vm.registerSale(number, price, c, soldBy, paid, free) },
            extra = if (Catalog.allAccounts(p).size > 1) {
                {
                    Muted("Vendido en")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (d in Catalog.allAccounts(p)) Choice(d.label, soldBy == d.id) { soldBy = d.id }
                    }
                }
            } else null,
        )
    }
}
