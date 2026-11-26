package dev.catalogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import dev.catalogo.core.Ledger
import dev.catalogo.core.Lot
import dev.catalogo.core.Money
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sku
import dev.catalogo.core.Stats
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow

/**
 * Inventory: what is in stock, what it cost and is worth, how old it is, and the
 * lots. The money that came in and its statistics are in «Ventas».
 */
@Composable
fun LotsScreen(vm: MainViewModel, p: ParentState) {
    var editing by remember { mutableStateOf<Lot?>(null) }
    var creating by remember { mutableStateOf(false) }
    val now = remember(p) { System.currentTimeMillis() }
    val k = remember(p) { Stats.kpis(p, now) }
    val covers by vm.articleCovers.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Header("Inventario", if (k.inStock == 1) "1 en stock" else "${k.inStock} en stock") {
                IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, contentDescription = "Ajustes") }
            }
        },
        bottomBar = { MainTabs(vm, Screen.Lots) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { creating = true },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Default.Add, contentDescription = "Nuevo lote") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "stock") {
                Tile {
                    Muted("Valor a la venta")
                    Text(Money.format(k.stockValue), style = Mono.copy(fontSize = 38.sp), maxLines = 1, softWrap = false)
                    val unpriced = p.articles.count { it.inStock && it.price == null }
                    Muted(
                        listOfNotNull(
                            if (k.inStock == 1) "1 prenda" else "${k.inStock} prendas",
                            if (unpriced > 0) "$unpriced sin precio" else null,
                        ).joinToString(GAP),
                        maxLines = 1,
                    )
                }
            }
            item(key = "kpis") {
                KpiGrid(
                    listOf(
                        Triple("Invertido", Money.format(k.stockCost), "coste en stock"),
                        Triple("Por subir", "${k.notUploaded}", "en stock"),
                        Triple("Favoritos", "${k.favourites}", "en tus anuncios"),
                        Triple("En camino", "${k.pending}", Money.format(k.pendingRevenue)),
                    ),
                )
            }
            if (k.inStock > 0) {
                item(key = "age") {
                    Tile {
                        Text("Antigüedad del stock", style = MaterialTheme.typography.titleMedium)
                        SplitBar(
                            listOf(
                                Triple("Hasta 30 días", k.inStock - k.aged30, Color(0xFF6CC48A)),
                                Triple("De 31 a 60 días", k.aged30 - k.aged60, Color(0xFF7FA7E8)),
                                Triple("De 61 a 90 días", k.aged60 - k.aged90, Color(0xFFE8B45C)),
                                Triple("Más de 90 días", k.aged90, Color(0xFFE5484D)),
                            ),
                        )
                    }
                }
            }
            val oldest = Stats.oldest(p, now)
            if (oldest.isNotEmpty()) {
                item(key = "oldest") {
                    Tile {
                        Text("Más tiempo en stock", style = MaterialTheme.typography.titleMedium)
                        for ((a, days) in oldest) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { vm.go(Screen.Article(a.number)) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Cover(covers[a.number], size = 48.dp)
                                Text(Sku.number(a.number, p.digits), style = Mono.copy(fontSize = 15.sp))
                                Text(
                                    articleName(a),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Muted("$days días", maxLines = 1)
                            }
                        }
                    }
                }
            }
            item(key = "lots-title") { Section("Lotes") }
            if (p.lots.isEmpty()) {
                item(key = "lots-empty") { Muted("Pulsa + para crear un lote", Modifier.padding(start = 4.dp)) }
            }
            items(p.lots.sortedByDescending { it.createdAt }, key = { it.id }) { lot ->
                val st = Ledger.stats(p, lot)
                val progress = if (lot.price <= 0) 1f else (st.recovered.toFloat() / lot.price).coerceIn(0f, 1f)
                Tile(onClick = { editing = lot }) {
                    Row2(
                        left = { Text(lot.name, style = MaterialTheme.typography.titleMedium) },
                        right = { Pill(if (st.profit >= 0) "+${Money.format(st.profit)}" else Money.format(st.profit), strong = st.profit >= 0) },
                    )
                    Muted(
                        listOfNotNull(
                            "${st.sold}/${lot.quantity} vendidas",
                            if (st.pending > 0) "${st.pending} en curso" else null,
                            "${st.inStock} en stock",
                            "${Money.format(Ledger.unitCost(lot))} por prenda",
                        ).joinToString(GAP),
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    Muted("Recuperado ${Money.format(st.recovered)} de ${Money.format(lot.price)}", Modifier.padding(top = 6.dp))
                }
            }
        }
    }

    if (creating) LotDialog(vm, null) { creating = false }
    editing?.let { lot -> LotDialog(vm, lot) { editing = null } }
}

@Composable
private fun LotDialog(vm: MainViewModel, lot: Lot?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(lot?.name ?: "") }
    var price by remember { mutableStateOf(Money.input(lot?.price)) }
    var quantity by remember { mutableStateOf(lot?.quantity?.toString() ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (lot == null) "Nuevo lote" else lot.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Nombre") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                MoneyField(price, { price = it }, "Precio del lote")
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { v -> quantity = v.filter(Char::isDigit).take(5) },
                    label = { Text("Prendas") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (lot != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Eliminar lote", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveLot(lot?.id, name, price, quantity, onDismiss) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    if (confirmDelete && lot != null) {
        Confirm(
            title = "Eliminar ${lot.name}",
            text = "Sus artículos se quedan sin lote. Las ventas ya hechas no cambian.",
            confirm = "Eliminar",
            destructive = true,
            onConfirm = { vm.deleteLot(lot.id); onDismiss() },
            onDismiss = { confirmDelete = false },
        )
    }
}
