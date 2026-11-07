package dev.catalogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.catalogo.core.Catalog
import dev.catalogo.core.Condition
import dev.catalogo.core.Garments
import dev.catalogo.core.Group
import dev.catalogo.core.Keyword
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import dev.catalogo.core.KeywordKind
import dev.catalogo.core.Ledger
import dev.catalogo.core.Listing
import dev.catalogo.core.ListingWriter
import dev.catalogo.core.Money
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sku

/** Create ([number] null) or edit an article's details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: MainViewModel, p: ParentState, number: Int?) {
    val article = number?.let { Catalog.article(p, it) }
    val start = article?.listing ?: Listing()
    var type by remember { mutableStateOf(start.type) }
    var brand by remember { mutableStateOf(start.brand) }
    var model by remember { mutableStateOf(start.model) }
    var size by remember { mutableStateOf(start.size) }
    var condition by remember { mutableStateOf(start.condition) }
    var keywords by remember { mutableStateOf(start.keywords) }
    var lotId by remember { mutableStateOf(article?.lotId) }
    var cost by remember { mutableStateOf(Money.input(article?.cost)) }
    var help by remember { mutableStateOf<Keyword?>(null) }

    val listing = Listing(type, brand.trim(), model.trim(), size.trim(), condition, keywords)
    val group = Garments.type(type)?.group

    Scaffold(
        topBar = { Bar(if (number == null) "Nuevo artículo" else "Editar ${Sku.number(number, p.digits)}", onBack = { vm.back() }) },
        bottomBar = {
            BottomActions {
                BigButton("Guardar", onClick = { vm.saveArticle(number, listing, lotId, cost) }, enabled = type.isNotEmpty())
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Tipo de prenda")
            TypePicker(type) { picked ->
                type = picked
                keywords = keywords.filter { Garments.keyword(it)?.appliesTo(Garments.type(picked)?.group) == true }
            }

            Section("Prenda")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(brand, { brand = it }, "Marca", Modifier.weight(1f), capitalize = true)
                Field(model, { model = it }, "Modelo", Modifier.weight(1f), capitalize = true)
            }
            Field(size, { size = it }, "Talla (W31 L32, M, 42…)", capitalize = true)

            Section("Estado")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in Condition.entries) Choice(c.label, condition == c) { condition = c }
            }

            Section("Palabras clave")
            Muted("Mantén pulsada una palabra para ver qué significa.", Modifier.padding(start = 4.dp))
            val available = Garments.keywordsFor(group)
            for (kind in KeywordKind.entries) {
                val list = available.filter { it.kind == kind }
                if (list.isEmpty()) continue
                Muted(kind.label, Modifier.padding(start = 4.dp, top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in list) {
                        val on = k.id in keywords
                        Tag(k.label, on, onClick = { keywords = if (on) keywords - k.id else keywords + k.id }, onLongClick = { help = k })
                    }
                }
            }

            Section("Coste")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Choice("Sin lote", lotId == null) { lotId = null }
                for (lot in p.lots) Choice(lot.name, lotId == lot.id) { lotId = lot.id }
            }
            val lot = Ledger.lot(p, lotId)
            if (lot != null) {
                Muted("Coste por prenda: ${Money.format(Ledger.unitCost(lot))}", Modifier.padding(start = 4.dp))
            } else {
                MoneyField(cost, { cost = it }, "Coste de la prenda (opcional)")
            }

            if (type.isNotEmpty()) {
                Section("Vista previa · ${p.self.prefix}")
                Tile {
                    Text(ListingWriter.title(listing, p.self.style), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    help?.let { k ->
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(k.label.replaceFirstChar(Char::uppercase)) },
            text = { Text(k.help) },
            confirmButton = { TextButton(onClick = { help = null }) { Text("Entendido") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

/**
 * Garment type: a discreet search box plus collapsible groups. Once a type is
 * chosen everything folds into a single line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypePicker(selected: String, onPick: (String) -> Unit) {
    val current = Garments.type(selected)
    var open by remember { mutableStateOf(current == null) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Group?>(current?.group) }

    if (current != null && !open) {
        Tile(onClick = { open = true }) {
            Row2(
                left = {
                    Column {
                        Text(current.label, style = MaterialTheme.typography.titleMedium)
                        Muted(current.group.label)
                    }
                },
                right = { Text("Cambiar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
        return
    }

    fun pick(id: String) {
        onPick(id)
        query = ""
        open = false
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(30) },
        placeholder = { Text("Buscar tipo… (chándal, capucha, puffer)") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
    if (query.isNotBlank()) {
        val found = Garments.search(query)
        if (found.isEmpty()) Muted("Nada con «$query»", Modifier.padding(start = 4.dp, top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in found) Choice(t.label, t.id == selected) { pick(t.id) }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in Group.entries) {
            val isOpen = expanded == g
            Tile(onClick = { expanded = if (isOpen) null else g }) {
                Row2(
                    left = { Text(g.label, style = MaterialTheme.typography.titleSmall) },
                    right = {
                        Icon(
                            if (isOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                if (isOpen) {
                    FlowRow(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (t in Garments.types.filter { it.group == g }) Choice(t.label, t.id == selected) { pick(t.id) }
                    }
                }
            }
        }
    }
}

/** Selectable keyword; a long press explains it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tag(text: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, capitalize: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(40)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None),
        modifier = modifier.fillMaxWidth(),
    )
}
