package dev.catalogo.ui.articles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.catalogo.ui.common.Choice
import dev.catalogo.ui.common.MoneyField
import dev.catalogo.ui.common.Muted

/** Sale dialog: price and cost (prefilled from lot or manual cost), whether it is paid and what happens to the number. */
@Composable
fun SellDialog(
    title: String,
    initialCost: String?,
    costHint: String?,
    onDismiss: () -> Unit,
    onConfirm: (price: String, cost: String, paid: Boolean, freeNumber: Boolean) -> Unit,
    initialPrice: String = "",
    extra: (@Composable () -> Unit)? = null,
) {
    var price by remember { mutableStateOf(initialPrice) }
    var cost by remember { mutableStateOf(initialCost ?: "") }
    var paid by remember { mutableStateOf(false) }
    var free by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(price, { price = it }, "Precio de venta")
                if (initialCost != null) MoneyField(cost, { cost = it }, "Coste", hint = costHint)
                extra?.invoke()
                Muted("¿Te ha llegado el dinero?")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Aún no", !paid) { paid = false }
                    Choice("Ya cobrado", paid) { paid = true }
                }
                if (!paid) {
                    Row(Modifier.fillMaxWidth().clickable { free = !free }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = free, onCheckedChange = { free = it })
                        Text("Liberar su número ya", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Muted(
                    when {
                        paid -> "Queda completada: se borran sus fotos y el número queda libre."
                        free -> "Se borran sus fotos y el número queda libre. La venta sigue en curso; si la anulas, la prenda no vuelve sola."
                        else -> "Queda en curso: la prenda se guarda con su número y, si la anulas, vuelve como estaba."
                    },
                )
                Muted("Solo para ventas fuera de Vinted: las de Vinted las apunta el PC. Borra allí su anuncio.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm(price, cost, paid, !paid && free) }, enabled = price.isNotBlank()) {
                Text("Vendido", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
