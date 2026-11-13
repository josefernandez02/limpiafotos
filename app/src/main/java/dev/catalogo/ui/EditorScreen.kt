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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.catalogo.core.Catalog
import dev.catalogo.core.Gender
import dev.catalogo.core.Vinted
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
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
import dev.catalogo.core.Measures
import androidx.compose.ui.text.input.KeyboardType
import dev.catalogo.core.Money
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sku

/**
 * New article ([number] null) and editing use the same form: only what the AI
 * cannot know (brand, model, size, era, condition, notes, price, cost). Gender,
 * type and keywords are the AI's; a wrong category is fixed in «Revisar».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: MainViewModel, p: ParentState, number: Int?) {
    val article = number?.let { Catalog.article(p, it) }
    val creating = number == null
    val start = article?.listing ?: Listing()
    val type = start.type
    var brand by remember { mutableStateOf(start.brand) }
    var model by remember { mutableStateOf(start.model) }
    var size by remember { mutableStateOf(start.size) }
    var condition by remember { mutableStateOf(start.condition) }
    var keywords by remember { mutableStateOf(start.keywords) }
    var notes by remember { mutableStateOf(start.notes) }
    val gender = start.gender
    var noModel by remember { mutableStateOf(start.noModel) }
    var noLabel by remember { mutableStateOf(start.noLabel) }
    var waist by remember { mutableStateOf(start.flat.waist) }
    var chest by remember { mutableStateOf(start.flat.chest) }
    var length by remember { mutableStateOf(start.flat.length) }
    var aiNotes by remember { mutableStateOf(start.aiNotes) }
    var lotId by remember { mutableStateOf(article?.lotId) }
    var cost by remember { mutableStateOf(Money.input(article?.cost)) }
    var price by remember { mutableStateOf(Money.input(article?.price)) }
    var help by remember { mutableStateOf<Keyword?>(null) }

    val listing = Listing(
        type = type,
        brand = brand.trim(),
        model = if (noModel) "" else model.trim(),
        size = if (noLabel) "" else size.trim(),      // without a label the AI sizes it from the measures
        condition = condition,
        keywords = keywords,
        notes = notes.trim(),
        gender = gender,
        noModel = noModel,
        noLabel = noLabel,
        flat = Measures(waist.trim(), chest.trim(), length.trim()),
        aiNotes = aiNotes.trim(),
    )
    val group = Garments.type(type)?.group

    @Composable
    fun Keywords(kinds: List<KeywordKind>) {
        val available = Garments.keywordsFor(group)
        for (kind in kinds) {
            val list = available.filter { it.kind == kind }
            if (list.isEmpty()) continue
            if (kinds.size > 1) Muted(kind.label, Modifier.padding(start = 4.dp, top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in list) {
                    val on = k.id in keywords
                    Tag(k.label, on, onClick = { keywords = if (on) keywords - k.id else keywords + k.id }, onLongClick = { help = k })
                }
            }
        }
    }

    Scaffold(
        topBar = { Bar(if (creating) "Nuevo artículo" else "Editar ${Sku.number(number!!, p.digits)}", onBack = { vm.back() }) },
        bottomBar = {
            BottomActions {
                BigButton(
                    if (creating) "Guardar y hacer fotos" else "Guardar",
                    onClick = { vm.saveArticle(number, listing, lotId, cost, article?.ai, price) },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Prenda")
            // Always written by hand: also «Vintage Dressing», «Japan Style», «Vintage»…
            Field(brand, { brand = it }, "Marca", capitalize = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!noModel) Field(model, { model = it }, "Modelo", Modifier.weight(1f), capitalize = true)
                else Spacer(Modifier.weight(1f))
                Choice("Sin modelo", noModel) { noModel = !noModel }
            }

            Section("Talla")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!noLabel) Field(size, { size = it }, "Talla", Modifier.weight(1f), capitalize = true)
                else Spacer(Modifier.weight(1f))
                Choice("Sin etiqueta", noLabel) { noLabel = !noLabel }
            }
            if (noLabel || waist.isNotBlank() || chest.isNotBlank() || length.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cm(waist, { waist = it }, "Cintura", Modifier.weight(1f))
                    Cm(chest, { chest = it }, "Pecho", Modifier.weight(1f))
                    Cm(length, { length = it }, "Largo", Modifier.weight(1f))
                }
            }

            Section("Época")
            Keywords(listOf(KeywordKind.EPOCH))

            Section("Estado")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in Condition.entries) Choice(c.label, condition == c) { condition = c }
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it.replace('\n', ' ').take(120) },
                label = { Text("Notas del estado") },
                placeholder = { Text("Tiene manchas") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Section("IA")
            OutlinedTextField(
                value = aiNotes,
                onValueChange = { aiNotes = it.take(300) },
                label = { Text("Notas para la IA") },
                placeholder = { Text("Made in Argentina") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )


            Section("Precio en Vinted")
            MoneyField(price, { price = it }, "Precio de venta")

            Section("Coste")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Choice("Sin lote", lotId == null) { lotId = null }
                for (lot in p.lots) Choice(lot.name, lotId == lot.id) { lotId = lot.id }
            }
            val lot = Ledger.lot(p, lotId)
            if (lot != null) {
                Muted("Coste por prenda: ${Money.format(Ledger.unitCost(lot))}", Modifier.padding(start = 4.dp))
            } else {
                MoneyField(cost, { cost = it }, "Coste de la prenda")
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

/** Flat measure in whole centimetres. */
@Composable
private fun Cm(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        suffix = { Text("cm") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
