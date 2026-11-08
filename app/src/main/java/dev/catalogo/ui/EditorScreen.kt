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
import dev.catalogo.core.Ai
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.alpha
import dev.catalogo.core.AiResult
import dev.catalogo.core.Catalog
import dev.catalogo.core.Gender
import dev.catalogo.core.Vinted
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
    var notes by remember { mutableStateOf(start.notes) }
    var gender by remember { mutableStateOf(start.gender) }
    var ai by remember { mutableStateOf(article?.ai) }
    var generating by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var lotId by remember { mutableStateOf(article?.lotId) }
    var cost by remember { mutableStateOf(Money.input(article?.cost)) }
    var help by remember { mutableStateOf<Keyword?>(null) }

    val listing = Listing(type, brand.trim(), model.trim(), size.trim(), condition, keywords, notes.trim(), gender)
    val freshAi = ai?.takeIf { it.basis == Ai.basis(listing) }
    val group = Garments.type(type)?.group

    Scaffold(
        topBar = { Bar(if (number == null) "Nuevo artículo" else "Editar ${Sku.number(number, p.digits)}", onBack = { vm.back() }) },
        bottomBar = {
            BottomActions {
                BigButton("Guardar", onClick = { vm.saveArticle(number, listing, lotId, cost, ai) }, enabled = type.isNotEmpty())
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Tipo de prenda")
            key(type) {
                TypePicker(type) { picked ->
                    type = picked
                    keywords = keywords.filter { Garments.keyword(it)?.appliesTo(Garments.type(picked)?.group) == true }
                }
            }

            Section("Prenda")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(brand, { brand = it }, "Marca", Modifier.weight(1f), capitalize = true)
                Field(model, { model = it }, "Modelo", Modifier.weight(1f), capitalize = true)
            }
            Field(size, { size = it }, "Talla (W31 L32, M, 42…)", capitalize = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted("Sexo", Modifier.padding(start = 4.dp, end = 4.dp))
                for (g in Gender.entries) Choice(g.label, gender == g) { gender = if (gender == g) null else g }
            }
            Vinted.letterSize(size, gender)?.let { Muted("Equivale a una $it", Modifier.padding(start = 4.dp)) }

            Section("Estado")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in Condition.entries) Choice(c.label, condition == c) { condition = c }
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it.replace('\n', ' ').take(120) },
                label = { Text("Notas (opcional)") },
                placeholder = { Text("Tiene manchas") },
                supportingText = { Text("Se añade detrás del estado en la descripción.") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

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

            Section("IA")
            if (number == null) {
                Muted("Guarda el artículo y haz las fotos para que la IA lo complete.", Modifier.padding(start = 4.dp))
            } else {
                Muted(
                    "Pon tú marca, modelo, talla y época. La IA mira las fotos y completa tipo, palabras, sexo, " +
                        "categoría, color y los títulos de cada móvil. Revísalo antes de guardar.",
                    Modifier.padding(start = 4.dp),
                )
                FilledTonalButton(
                    onClick = { picking = true },
                    enabled = !generating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (generating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Mirando las fotos…")
                    } else {
                        Text(if (ai == null) "Rellenar con IA" else "Volver a generar con IA")
                    }
                }
                if (picking) {
                    PhotoPicker(vm, number, onDismiss = { picking = false }) { photos ->
                        picking = false
                        generating = true
                        vm.suggestWithAi(number, listing, photos) { s, model ->
                            generating = false
                            if (s != null) {
                                val applied = s.applyTo(listing)
                                type = applied.type
                                keywords = applied.keywords
                                gender = applied.gender
                                size = applied.size
                                ai = s.result(applied, model)
                            }
                        }
                    }
                }
                ai?.let { r ->
                    Tile {
                        if (freshAi == null) {
                            Muted("Has cambiado marca, modelo, tipo o palabras: se usan los títulos automáticos hasta que vuelvas a generar.")
                        } else {
                            for (d in Catalog.devices(p)) {
                                r.titles[d.id]?.let { t ->
                                    Muted(d.prefix)
                                    Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
                                }
                            }
                        }
                        VintedInfo(gender, r)
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
                    Text(
                        freshAi?.titles?.get(p.self.id) ?: ListingWriter.title(listing, p.self.style),
                        style = MaterialTheme.typography.titleMedium,
                    )
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

/** What to pick by hand in Vinted: gender, category and colour. */
@Composable
fun VintedInfo(gender: Gender?, ai: AiResult) {
    val rows = listOfNotNull(
        gender?.let { "Sexo" to it.label },
        ai.category?.let { "Categoría" to it },
        ai.colors.takeIf { it.isNotEmpty() }?.let { "Color" to it.joinToString(" y ") },
    )
    if (rows.isEmpty()) return
    Muted("Para rellenar en Vinted", Modifier.padding(top = 6.dp))
    for ((label, value) in rows) {
        Text(
            "$label: $value",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Choose the 1 to 3 photos sent to the AI; the first ones come selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoPicker(vm: MainViewModel, number: Int, onDismiss: () -> Unit, onSend: (List<android.net.Uri>) -> Unit) {
    val photos by produceState<List<android.net.Uri>?>(null, number) { value = vm.aiPhotos(number) }
    var chosen by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    LaunchedEffect(photos) { if (chosen == null && photos != null) chosen = photos!!.take(Ai.PHOTOS) }
    val picked = chosen.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fotos para la IA") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    photos == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    photos!!.isEmpty() -> Text("Este artículo no tiene fotos. Hazlas primero.")
                    else -> {
                        Muted("Elige de 1 a ${Ai.PHOTOS}. Lo ideal: delante, detrás y la etiqueta.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (uri in photos!!) {
                                val index = picked.indexOf(uri)
                                val shape = RoundedCornerShape(12.dp)
                                Box(
                                    Modifier.size(84.dp).clip(shape)
                                        .border(if (index >= 0) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, shape)
                                        .clickable {
                                            chosen = when {
                                                index >= 0 -> picked - uri
                                                picked.size < Ai.PHOTOS -> picked + uri
                                                else -> picked
                                            }
                                        },
                                ) {
                                    Thumb(uri, Modifier.fillMaxSize().alpha(if (index >= 0) 1f else 0.6f))
                                    if (index >= 0) {
                                        Text(
                                            "${index + 1}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                                .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                                                .padding(horizontal = 7.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(picked) }, enabled = picked.size in 1..Ai.PHOTOS) { Text("Enviar (${picked.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
