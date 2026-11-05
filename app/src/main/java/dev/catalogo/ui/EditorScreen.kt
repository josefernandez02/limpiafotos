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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.catalogo.core.Catalog
import dev.catalogo.core.Condition
import dev.catalogo.core.Garments
import dev.catalogo.core.Group
import dev.catalogo.core.KeywordKind
import dev.catalogo.core.Ledger
import dev.catalogo.core.Listing
import dev.catalogo.core.ListingWriter
import dev.catalogo.core.Money
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sku

private val GROUP_LABELS = mapOf(
    Group.BOTTOM to "Abajo",
    Group.TOP to "Arriba",
    Group.OUTER to "Abrigo",
    Group.DRESS to "Vestidos",
    Group.SHOES to "Calzado",
    Group.ACCESSORY to "Accesorios",
)

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
    var measures by remember { mutableStateOf(start.measures) }
    var lotId by remember { mutableStateOf(article?.lotId) }
    var cost by remember { mutableStateOf(Money.input(article?.cost)) }

    val listing = Listing(type, brand.trim(), model.trim(), size.trim(), condition, keywords, measures.filterValues { it.isNotBlank() })
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
            for ((g, label) in GROUP_LABELS) {
                Muted(label, Modifier.padding(start = 4.dp, top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in Garments.types.filter { it.group == g }) {
                        Choice(t.label, type == t.id) {
                            type = t.id
                            if (Garments.type(type)?.group != group) measures = emptyMap()
                        }
                    }
                }
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
            for (kind in KeywordKind.entries) {
                Muted(kind.label, Modifier.padding(start = 4.dp, top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in Garments.keywords.filter { it.kind == kind }) {
                        val on = k.id in keywords
                        Choice(k.label, on) { keywords = if (on) keywords - k.id else keywords + k.id }
                    }
                }
            }

            val fields = Garments.measures(group)
            if (fields.isNotEmpty()) {
                Section("Medidas (cm, opcional)")
                for (row in fields.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in row) {
                            OutlinedTextField(
                                value = measures[m.id] ?: "",
                                onValueChange = { v -> measures = measures + (m.id to v.filter { it.isDigit() || it == ',' || it == '.' }.take(6)) },
                                label = { Text(m.label.replaceFirstChar(Char::uppercase)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
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
