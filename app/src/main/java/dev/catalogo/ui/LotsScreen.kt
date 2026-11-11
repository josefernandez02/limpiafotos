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
import dev.catalogo.core.Ledger
import dev.catalogo.core.Lot
import dev.catalogo.core.Money
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sku
import dev.catalogo.core.Stats
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.style.TextOverflow

/**
 * Inventory: the numbers that matter (stock, sales, profit, speed, age),
 * sales per month, per account and per brand, what is getting old, and lots.
 */
@Composable
fun LotsScreen(vm: MainViewModel, p: ParentState) {
    var editing by remember { mutableStateOf<Lot?>(null) }
    var creating by remember { mutableStateOf(false) }
    val now = remember(p) { System.currentTimeMillis() }
    val k = remember(p) { Stats.kpis(p, now) }

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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "kpis") {
                // Pairs of equal tiles: same height, one line each, so the grid stays symmetric.
                val tiles = listOf(
                    Triple("Beneficio", Money.format(k.profit), k.roi?.let { "ROI $it %" } ?: "sin costes"),
                    Triple("Ingresos", Money.format(k.revenue), if (k.sold == 1) "1 venta" else "${k.sold} ventas"),
                    Triple("Vendido", k.sellThrough?.let { "$it %" } ?: "—", "del total"),
                    Triple("Días en venderse", k.medianDays?.toString() ?: "—", "mediana"),
                    Triple("En stock", "${k.inStock}", "coste ${Money.format(k.stockCost)}"),
                    Triple("Precio medio", k.averagePrice?.let(Money::format) ?: "—", "por venta"),
                    Triple("Por subir", "${k.notUploaded}", "en stock"),
                    Triple("+60 días", "${k.aged60}", "${k.aged90} de +90 días"),
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (pair in tiles.chunked(2)) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((label, value, detail) in pair) Kpi(label, value, detail, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
            item(key = "months") {
                Tile {
                    Text("Ingresos por mes", style = MaterialTheme.typography.titleMedium)
                    MonthBars(k.months)
                }
            }
            if (k.accounts.size > 1 || k.sold > 0) {
                item(key = "accounts") {
                    Tile {
                        Text("Por cuenta", style = MaterialTheme.typography.titleMedium)
                        val max = k.accounts.maxOf { it.revenue }.coerceAtLeast(1)
                        for (a in k.accounts) {
                            BarRow(a.label, "${a.sold} vendidos · ${Money.format(a.revenue)} · ${a.listed} subidos", a.revenue.toFloat() / max)
                        }
                    }
                }
            }
            if (k.brands.isNotEmpty()) {
                item(key = "brands") {
                    Tile {
                        Text("Marcas más vendidas", style = MaterialTheme.typography.titleMedium)
                        val max = k.brands.first().second.coerceAtLeast(1)
                        for ((brand, n) in k.brands.take(5)) BarRow(brand, if (n == 1) "1 venta" else "$n ventas", n.toFloat() / max)
                    }
                }
            }
            val oldest = Stats.oldest(p, now)
            if (oldest.isNotEmpty()) {
                item(key = "oldest") {
                    Tile {
                        Text("Más tiempo en stock", style = MaterialTheme.typography.titleMedium)
                        for ((a, days) in oldest) {
                            val l = a.listing
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Article(a.number)) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(Sku.number(a.number, p.digits), style = Mono.copy(fontSize = 15.sp))
                                Text(
                                    listOfNotNull(l?.brand?.takeIf { it.isNotBlank() }, l?.model?.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { "—" },
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
                    Muted("${st.sold}/${lot.quantity} vendidas · ${st.inStock} en stock · ${Money.format(Ledger.unitCost(lot))} por prenda")
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
private fun Kpi(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Tile(modifier) {
        Muted(label, maxLines = 1)
        Text(value, style = Mono.copy(fontSize = 22.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Muted(detail, maxLines = 1)
    }
}

/** A labelled horizontal bar: one value per row, one colour. */
@Composable
private fun BarRow(label: String, detail: String, fraction: Float) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Muted(detail, maxLines = 1)
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/**
 * Revenue of the last months as thin bars on a shared baseline. The latest
 * month is labelled; tapping a bar shows its numbers.
 */
@Composable
private fun MonthBars(months: List<Stats.Month>) {
    var picked by remember(months) { mutableStateOf(months.lastIndex) }
    val max = months.maxOfOrNull { it.revenue }?.coerceAtLeast(1) ?: 1
    val names = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    months.getOrNull(picked)?.let { m ->
        Muted("${names[m.month.monthValue - 1]} ${m.month.year}: ${m.sold} ventas · ${Money.format(m.revenue)} · beneficio ${Money.format(m.profit)}")
    }
    Row(
        Modifier.fillMaxWidth().height(120.dp).padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        months.forEachIndexed { i, m ->
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { picked = i },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                val f = m.revenue.toFloat() / max
                Box(
                    Modifier.fillMaxWidth(0.55f).fillMaxHeight(0.82f * f.coerceAtLeast(0.02f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (i == picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary),
                )
                Text(names[m.month.monthValue - 1], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
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
