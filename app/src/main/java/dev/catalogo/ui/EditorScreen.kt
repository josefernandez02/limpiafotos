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
 * New article ([number] null): only what the AI cannot know (brand, model,
 * size, era, condition, notes, cost). Editing also shows gender, type and
 * keywords, which the AI fills in, to correct them by hand.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: MainViewModel, p: ParentState, number: Int?) {
    val article = number?.let { Catalog.article(p, it) }
    val creating = number == null
    val start = article?.listing ?: Listing()
    var type by remember { mutableStateOf(start.type) }
    var brand by remember { mutableStateOf(start.brand) }
    var model by remember { mutableStateOf(start.model) }
    var size by remember { mutableStateOf(start.size) }
    var condition by remember { mutableStateOf(start.condition) }
    var keywords by remember { mutableStateOf(start.keywords) }
    var notes by remember { mutableStateOf(start.notes) }
    var gender by remember { mutableStateOf(start.gender) }
    var noBrand by remember { mutableStateOf(start.noBrand) }
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
        brand = if (noBrand) "" else brand.trim(),
        model = if (noBrand) "" else model.trim(),
        size = size.trim(),
        condition = condition,
        keywords = keywords,
        notes = notes.trim(),
        gender = gender,
        noBrand = noBrand,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!noBrand) Field(brand, { brand = it }, "Marca", Modifier.weight(1f), capitalize = true)
                else Muted("Sin marca", Modifier.weight(1f).padding(start = 4.dp))
                Choice("Sin marca", noBrand) { noBrand = !noBrand }
            }
            if (!noBrand) Field(model, { model = it }, "Modelo", capitalize = true)

            Section("Talla")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(size, { size = it }, if (noLabel) "Talla aproximada" else "Talla", Modifier.weight(1f), capitalize = true)
                Choice("Sin etiqueta", noLabel) { noLabel = !noLabel }
            }
            if (noLabel || waist.isNotBlank() || chest.isNotBlank() || length.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cm(waist, { waist = it }, "Cintura", Modifier.weight(1f))
                    Cm(chest, { chest = it }, "Pecho", Modifier.weight(1f))
                    Cm(length, { length = it }, "Largo", Modifier.weight(1f))
                }
            }
            if (!creating) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Muted("Sexo", Modifier.padding(start = 4.dp, end = 4.dp))
                    for (g in Gender.entries) Choice(g.label, gender == g) { gender = if (gender == g) null else g }
                }
                Vinted.letterSize(size, gender)?.let { Muted("Equivale a una $it", Modifier.padding(start = 4.dp)) }
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

            if (!creating) {
                Section("Tipo y palabras clave")
                key(type) {
                    TypePicker(type) { picked ->
                        type = picked
                        keywords = keywords.filter { Garments.keyword(it)?.appliesTo(Garments.type(picked)?.group) == true }
                    }
                }
                Keywords(KeywordKind.entries.filter { it != KeywordKind.EPOCH })
            }

            Section("Precio en Vinted")
            MoneyField(price, { price = it }, "Precio de venta", hint = "El que se pondrá en el borrador")

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
