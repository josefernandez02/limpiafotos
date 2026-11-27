package dev.catalogo.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Bottom tabs of the main phone. */
@Composable
fun MainTabs(vm: MainViewModel, current: Screen) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        val colors = NavigationBarItemDefaults.colors(
            indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            selectedIconColor = MaterialTheme.colorScheme.onSurface,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NavigationBarItem(
            selected = current == Screen.Home,
            onClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); vm.tab(Screen.Home) },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Artículos") },
            colors = colors,
        )
        NavigationBarItem(
            selected = current == Screen.Status,
            onClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); vm.tab(Screen.Status) },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            label = { Text("Estado") },
            colors = colors,
        )
        NavigationBarItem(
            selected = current == Screen.Sales,
            onClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); vm.tab(Screen.Sales) },
            icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
            label = { Text("Ventas") },
            colors = colors,
        )
        NavigationBarItem(
            selected = current == Screen.Lots,
            onClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); vm.tab(Screen.Lots) },
            icon = { Icon(ChartIcon, contentDescription = null) },
            label = { Text("Inventario") },
            colors = colors,
        )
    }
}

@Composable
fun MoneyField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, hint: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == ',' || it == '.' }.take(10)) },
        label = { Text(label) },
        suffix = { Text("€") },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

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

@Composable
fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            labelColor = MaterialTheme.colorScheme.onSurface,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        border = null,
    )
}

/** Three rising bars: the inventory tab. */
val ChartIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "Catalogo.Chart",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M4,20V13H8V20Z M10,20V8H14V20Z M16,20V4H20V20Z",
        ).toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
    ).build()
}
