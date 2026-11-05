package dev.catalogo.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
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
import dev.catalogo.core.ListingText

/** Bottom tabs of the main phone. */
@Composable
fun MainTabs(vm: MainViewModel, current: Screen) {
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
            onClick = { vm.tab(Screen.Home) },
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Artículos") },
            colors = colors,
        )
        NavigationBarItem(
            selected = current == Screen.Lots,
            onClick = { vm.tab(Screen.Lots) },
            icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
            label = { Text("Lotes") },
            colors = colors,
        )
        NavigationBarItem(
            selected = current == Screen.Connect,
            onClick = { vm.tab(Screen.Connect) },
            icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
            label = { Text("Conectar") },
            colors = colors,
        )
    }
}

/** Title + description ready to publish, with copy and open-Vinted actions. */
@Composable
fun ListingCard(vm: MainViewModel, text: ListingText?, onComplete: (() -> Unit)? = null) {
    Tile {
        if (text == null) {
            Muted("Sin datos del anuncio")
            if (onComplete != null) {
                FilledTonalButton(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Completar datos") }
            }
            return@Tile
        }
        Text(text.title, style = MaterialTheme.typography.titleMedium)
        Text(
            text.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { vm.copy("Título", text.title) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Copiar título", maxLines = 1, softWrap = false) }
            FilledTonalButton(
                onClick = { vm.copyDescription(text.description) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Copiar descripción", maxLines = 1, softWrap = false) }
        }
        OutlinedButton(
            onClick = { vm.openVinted() },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Abrir Vinted") }
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

/** Sale dialog: price, plus cost on the main phone (prefilled from lot or manual cost). */
@Composable
fun SellDialog(
    title: String,
    initialCost: String?,
    costHint: String?,
    onDismiss: () -> Unit,
    onConfirm: (price: String, cost: String) -> Unit,
) {
    var price by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf(initialCost ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("Se borrarán sus fotos en todos los móviles.")
                MoneyField(price, { price = it }, "Precio de venta")
                if (initialCost != null) MoneyField(cost, { cost = it }, "Coste", hint = costHint)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm(price, cost) }, enabled = price.isNotBlank()) {
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
        label = { Text(text) },
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
