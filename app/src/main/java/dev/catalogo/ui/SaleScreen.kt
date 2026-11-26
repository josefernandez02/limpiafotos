package dev.catalogo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
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
import dev.catalogo.core.Sale
import dev.catalogo.core.SaleStatus
import dev.catalogo.core.Sales
import dev.catalogo.core.Sku
import dev.catalogo.core.Stats

private enum class Ask { CANCEL, COMPLETE, ACCEPT, RELEASE, UNPAY }

/** One sale: what it was, where it is, and what can still be done with it. */
@Composable
fun SaleScreen(vm: MainViewModel, p: ParentState, id: String) {
    val sale = Sales.sale(p, id)
    var ask by remember { mutableStateOf<Ask?>(null) }
    var assigning by remember { mutableStateOf(false) }
    val links = LocalUriHandler.current

    Scaffold(
        topBar = { Bar("Venta", onBack = { vm.back() }) },
        bottomBar = {
            if (sale != null) SaleActions(vm, p, sale, onAsk = { ask = it }, onAssign = { assigning = true })
        },
    ) { padding ->
        if (sale == null) {
            Empty("Esta venta ya no existe", Modifier.padding(padding))
            return@Scaffold
        }
        val article = Sales.article(p, sale)
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SaleImage(vm, p, sale, Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(22.dp)), edge = 1080)
            Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Money.format(sale.price), style = Mono.copy(fontSize = 36.sp), modifier = Modifier.weight(1f), maxLines = 1)
                SaleChip(sale)
            }
            Text(saleTitle(sale), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))

            Tile {
                val (title, text) = explain(p, sale, article)
                Text(title, style = MaterialTheme.typography.titleSmall, color = saleTone(sale).first)
                Muted(text)
                if (sale.detail.isNotBlank()) Muted("En Vinted: ${sale.detail}", Modifier.padding(top = 4.dp))
            }

            Tile {
                Fact("Código", saleCode(p, sale) ?: "Sin código")
                val cost = sale.cost
                Fact("Coste", cost?.let(Money::format) ?: "—")
                if (sale.status != SaleStatus.CANCELLED) Fact("Beneficio", cost?.let { Money.format(sale.price - it) } ?: "—")
                Fact("Vendida", longDate(sale.soldAt))
                sale.closedAt?.let { Fact(if (sale.status == SaleStatus.CANCELLED) "Anulada" else "Completada", longDate(it)) }
                Stats.daysToSell(sale)?.let { Fact("Tardó en venderse", if (it == 1) "1 día" else "$it días") }
                Catalog.device(p, sale.deviceId)?.let { Fact("Cuenta", it.label) }
                Ledger.lot(p, sale.lotId)?.let { Fact("Lote", it.name) }
                if (sale.brand.isNotBlank()) Fact("Marca", sale.brand)
                if (sale.order.isNotBlank()) Fact("Pedido de Vinted", sale.order) else Fact("Origen", "Apuntada a mano")
            }
            if (article != null) {
                OutlinedButton(
                    onClick = { vm.go(Screen.Article(article.number)) },
                    modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                ) { Text("Ver artículo ${Sku.number(article.number, p.digits)}") }
            }
            if (sale.itemId.isNotBlank() && sale.itemId.all(Char::isDigit)) {
                TextButton(
                    onClick = { runCatching { links.openUri("https://www.vinted.es/items/${sale.itemId}") }.onFailure { vm.say("No se pudo abrir Vinted") } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ver anuncio en Vinted") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (sale == null) return
    val article = Sales.article(p, sale)
    val number = article?.let { Sku.number(it.number, p.digits) }
    when (ask) {
        Ask.CANCEL -> Confirm(
            title = "Anular la venta",
            text = when {
                number != null -> "La prenda $number vuelve a estar en stock con sus fotos, sus datos y su número, como antes de venderse. " +
                    "Úsalo si se cancela, se pierde el paquete o te la devuelven."
                sale.numberFreed -> "La venta queda anulada y no cuenta. Su número ya se liberó, así que la prenda no vuelve sola: " +
                    "si la sigues teniendo, dala de alta otra vez."
                else -> "La venta queda anulada y no cuenta en ningún número."
            },
            confirm = "Anular venta",
            destructive = true,
            onConfirm = { vm.cancelSale(id) },
            onDismiss = { ask = null },
        )
        Ask.COMPLETE, Ask.ACCEPT -> Confirm(
            title = "Dar por completada",
            text = "El dinero cuenta como cobrado" +
                (if (number != null) ", se borran las fotos de la prenda $number y su número queda libre" else "") +
                if (sale.order.isBlank()) ". Podrás volverla a pendiente, pero la prenda no vuelve al inventario."
                else ". Es definitivo: después no se puede anular.",
            confirm = "Completar",
            onConfirm = { if (ask == Ask.ACCEPT) vm.acceptSale(id) else vm.completeSale(id) },
            onDismiss = { ask = null },
        )
        Ask.RELEASE -> Confirm(
            title = "Liberar el número",
            text = "Se borran las fotos de la prenda $number y su número queda libre para otra. La venta sigue en curso. " +
                "No se puede deshacer: si luego se anula, la prenda no vuelve sola.",
            confirm = "Liberar",
            destructive = true,
            onConfirm = { vm.releaseSale(id) },
            onDismiss = { ask = null },
        )
        Ask.UNPAY -> Confirm(
            title = "Volver a pendiente de cobro",
            text = "La venta vuelve a estar en curso y el dinero deja de contar como cobrado.",
            confirm = "Volver",
            onConfirm = { vm.unpaySale(id) },
            onDismiss = { ask = null },
        )
        null -> Unit
    }
    if (assigning) AssignDialog(vm, p, sale, onDismiss = { assigning = false })
}

private fun explain(p: ParentState, sale: Sale, article: Article?): Pair<String, String> = when {
    sale.conflict -> "Hay que revisarla" to "Aquí está anulada, pero Vinted dice otra cosa. Si el dinero ha entrado, confírmala."
    sale.status == SaleStatus.PENDING -> "En curso: aún puede anularse" to when {
        article != null -> "La prenda está guardada con su número. " +
            if (sale.order.isBlank()) "Márcala como cobrada cuando llegue el dinero."
            else "Cuando Vinted la dé por finalizada se completará sola."
        sale.order.isBlank() -> "Márcala como cobrada cuando llegue el dinero."
        sale.listedAt == null && reused(p, sale) -> "Su prenda ya no está en el inventario (el número ahora es de otra). No tienes que hacer nada."
        sale.listedAt == null -> "No sé qué prenda es: si sigue en el inventario, asígnala para que salga del stock."
        else -> "Cuando Vinted la dé por finalizada se completará sola."
    }
    sale.status == SaleStatus.COMPLETED -> "Completada" to
        if (sale.order.isBlank()) "El dinero ya entró." else "El dinero ya entró. Es definitiva y no se puede deshacer."
    sale.byHand -> "Anulada por ti" to "No cuenta en los números."
    sale.order.isBlank() -> "Anulada" to "No cuenta en los números."
    else -> "Cancelada en Vinted" to "No cuenta en los números."
}

/** The number in the sale's code belongs now to a garment made after the sale. */
private fun reused(p: ParentState, sale: Sale): Boolean =
    sale.code.isNotBlank() && Catalog.article(p, sale.number)?.let { it.createdAt > sale.soldAt } == true

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted(label, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SaleActions(vm: MainViewModel, p: ParentState, sale: Sale, onAsk: (Ask) -> Unit, onAssign: () -> Unit) {
    val unlinked = sale.listedAt == null && sale.status != SaleStatus.CANCELLED && p.articles.any { it.inStock }
    val manual = sale.status == SaleStatus.PENDING && sale.order.isBlank()
    val reopen = Sales.canReopen(p, sale)
    val byHandPaid = sale.status == SaleStatus.COMPLETED && sale.order.isBlank()
    val holding = sale.status == SaleStatus.PENDING && Sales.article(p, sale)?.soldIn == sale.id
    if (sale.status == SaleStatus.COMPLETED && !unlinked && !sale.conflict && !byHandPaid) return
    if (sale.status == SaleStatus.CANCELLED && !reopen && !sale.conflict) return
    BottomActions {
        if (sale.conflict) BigButton("Confirmar venta", onClick = { onAsk(Ask.ACCEPT) })
        if (unlinked) {
            FilledTonalButton(onClick = onAssign, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) {
                Text("Asignar artículo")
            }
        }
        if (manual) BigButton("Marcar como cobrada", onClick = { onAsk(Ask.COMPLETE) })
        if (holding) {
            FilledTonalButton(onClick = { onAsk(Ask.RELEASE) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) {
                Text("Liberar su número")
            }
        }
        if (byHandPaid) {
            OutlinedButton(onClick = { onAsk(Ask.UNPAY) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) {
                Text("Volver a pendiente de cobro")
            }
        }
        if (reopen) {
            FilledTonalButton(onClick = { vm.reopenSale(sale.id) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) {
                Text("Recuperar venta")
            }
        }
        if (sale.status == SaleStatus.PENDING) {
            OutlinedButton(onClick = { onAsk(Ask.CANCEL) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) {
                Text("Anular venta", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Which article was it? Only those in stock. */
@Composable
private fun AssignDialog(vm: MainViewModel, p: ParentState, sale: Sale, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf<Int?>(null) }
    val covers by vm.articleCovers.collectAsStateWithLifecycle()
    val stock = p.articles.filter { it.inStock }.sortedByDescending { it.number }
    val chosen = picked
    if (chosen != null) {
        val number = Sku.number(chosen, p.digits)
        Confirm(
            title = "Es la prenda $number",
            text = if (sale.status == SaleStatus.PENDING) "Sale del stock mientras la venta esté en curso; si la anulas, vuelve."
            else "La venta ya está completada: la prenda $number se quita, se borran sus fotos y el número queda libre. No se puede deshacer.",
            confirm = "Asignar",
            onConfirm = { vm.assignSale(sale.id, chosen); onDismiss() },
            onDismiss = { picked = null },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Qué prenda era?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(stock, key = { it.number }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { picked = a.number }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Cover(covers[a.number], size = 52.dp)
                        Text(Sku.number(a.number, p.digits), style = Mono.copy(fontSize = 15.sp))
                        Text(
                            articleName(a), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Short and readable: brand and model («Levi's 512»). */
fun articleName(a: Article): String =
    listOfNotNull(a.listing?.brand?.takeIf { it.isNotBlank() }, a.listing?.model?.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { "Sin datos" }

/** The article's page while a sale holds it: nothing to edit until the sale ends. */
@Composable
fun HeldArticle(vm: MainViewModel, p: ParentState, a: Article) {
    val sale = Sales.sale(p, a.soldIn)
    var cancelling by remember { mutableStateOf(false) }
    val number = Sku.number(a.number, p.digits)
    val strip by produceState(emptyList<android.net.Uri>(), a.number) {
        value = vm.albumPhotos(Sku.format(p.self.prefix, a.number, p.digits)).ifEmpty { vm.aiPhotos(a.number) }
    }
    Scaffold(topBar = { Bar("", onBack = { vm.back() }) }) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(number, style = Mono.copy(fontSize = 34.sp), modifier = Modifier.padding(start = 4.dp))
            Tile {
                Text("Venta en curso", style = MaterialTheme.typography.titleMedium, color = Tone.Warn.color)
                if (sale != null) Muted(listOf(Money.format(sale.price), longDate(sale.soldAt)).joinToString(GAP))
                Muted("La prenda queda guardada con su número hasta que la venta se complete o se anule.")
                if (sale != null) {
                    FilledTonalButton(
                        onClick = { vm.go(Screen.Sale(sale.id)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(14.dp),
                    ) { Text("Ver venta") }
                    OutlinedButton(
                        onClick = { cancelling = true },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    ) { Text("Anular venta", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (strip.isNotEmpty()) {
                Section("Fotos")
                Tile { PhotoStrip(strip, onOpen = { vm.openViewer(strip, it) }) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (cancelling && sale != null) {
        Confirm(
            title = "Anular la venta",
            text = "La prenda $number vuelve a estar en stock con sus fotos, sus datos y su número.",
            confirm = "Anular venta",
            destructive = true,
            onConfirm = { vm.cancelSale(sale.id) },
            onDismiss = { cancelling = false },
        )
    }
}
