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
import java.util.Calendar

private fun monthStart(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun LotsScreen(vm: MainViewModel, p: ParentState) {
    var editing by remember { mutableStateOf<Lot?>(null) }
    var creating by remember { mutableStateOf(false) }
    val month = Ledger.totalsBetween(p, monthStart(), Long.MAX_VALUE)
    val all = Ledger.totals(p.sales)

    Scaffold(
        topBar = {
            Header("Lotes", if (p.lots.size == 1) "1 lote" else "${p.lots.size} lotes") {
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Tile {
                    Row2(left = { Muted("Este mes") }, right = { Muted("${month.count} ventas") })
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Figure("Ingresos", Money.format(month.revenue), Modifier.weight(1f))
                        Figure("Beneficio", Money.format(month.profit), Modifier.weight(1f))
                    }
                    Muted("Total: ${all.count} ventas · ${Money.format(all.revenue)} · beneficio ${Money.format(all.profit)}", Modifier.padding(top = 8.dp))
                }
            }
            if (p.lots.isEmpty()) {
                item { Muted("Pulsa + para crear un lote", Modifier.padding(top = 24.dp, start = 4.dp)) }
            }
            items(p.lots.sortedByDescending { it.createdAt }, key = { it.id }) { lot ->
                val st = Ledger.stats(p, lot)
                val progress = if (lot.price <= 0) 1f else (st.recovered.toFloat() / lot.price).coerceIn(0f, 1f)
                Tile(onClick = { editing = lot }) {
                    Row2(
                        left = { Text(lot.name, style = MaterialTheme.typography.titleMedium) },
                        right = { Pill(if (st.profit >= 0) "+${Money.format(st.profit)}" else Money.format(st.profit), strong = st.profit >= 0) },
                    )
                    Muted("${st.sold}/${lot.quantity} vendidas · ${st.inStock} en catálogo · ${Money.format(Ledger.unitCost(lot))} por prenda")
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
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = Mono.copy(fontSize = 22.sp))
        Muted(label)
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
