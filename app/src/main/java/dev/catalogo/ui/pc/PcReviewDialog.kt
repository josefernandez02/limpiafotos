package dev.catalogo.ui.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.model.Article
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.PcTask
import dev.catalogo.core.vinted.VintedSizes
import dev.catalogo.core.vinted.VintedTaxonomy
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.common.Choice
import dev.catalogo.ui.common.Muted

/** Only the doubtful fields, with the AI's choice already marked: one tap to confirm. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcReviewDialog(vm: MainViewModel, p: CatalogState, article: Article, pcTask: PcTask, onDismiss: () -> Unit) {
    val ai = Catalog.freshAi(p, article.number)
    val listing = article.listing
    var category by remember { mutableStateOf(pcTask.fixes.category ?: ai?.category?.takeIf { it in VintedTaxonomy.categories }) }
    var search by remember { mutableStateOf("") }
    var size by remember {
        mutableStateOf(
            pcTask.fixes.size ?: (pcTask.fixes.category ?: ai?.category)?.takeIf { it in VintedTaxonomy.categories }
                ?.let { c -> listing?.let { VintedSizes.resolve(c, it) } }.orEmpty(),
        )
    }
    var colors by remember { mutableStateOf(pcTask.fixes.colors ?: ai?.colors?.mapNotNull(VintedTaxonomy::color).orEmpty()) }
    var materials by remember { mutableStateOf(pcTask.fixes.materials ?: ai?.materials.orEmpty()) }
    var brandOk by remember { mutableStateOf(pcTask.fixes.brandChecked) }
    val doubts = pcTask.doubts

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revisar") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if ("categoria" in doubts) {
                    Muted("Categoría")
                    category?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it.take(40) },
                        label = { Text("Buscar otra (p. ej. ajustados)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (search.length >= 2) {
                        val gender = listing?.gender?.label
                        val found = VintedTaxonomy.categories.filter { c ->
                            (gender == null || c.startsWith("$gender ")) && c.lowercase().contains(search.lowercase())
                        }.take(8)
                        Column(Modifier.heightIn(max = 260.dp)) {
                            for (c in found) {
                                TextButton(onClick = { category = c; search = "" }) {
                                    Text(c.substringAfter(" › "), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (found.isEmpty()) Muted("Ninguna categoría con ese texto")
                        }
                    }
                }
                if ("talla" in doubts) {
                    val options = category?.let(VintedSizes::options).orEmpty()
                    Muted("Talla" + listing?.size?.takeIf { it.isNotBlank() }?.let { " (etiqueta $it)" }.orEmpty())
                    when {
                        category == null -> Muted("Elige antes la categoría")
                        options.isEmpty() -> Muted("Esta categoría no lleva talla")
                        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (o in options) Choice(o, size == o) { size = o }
                        }
                    }
                }
                if ("colores" in doubts) {
                    Muted("Color (hasta ${VintedTaxonomy.MAX_COLORS})")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in VintedTaxonomy.colors) {
                            val on = c in colors
                            Choice(c, on) {
                                colors = when {
                                    on -> colors - c
                                    colors.size < VintedTaxonomy.MAX_COLORS -> colors + c
                                    else -> colors
                                }
                            }
                        }
                    }
                }
                if ("materiales" in doubts) {
                    Muted("Material (hasta ${VintedTaxonomy.MAX_MATERIALS}, opcional)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in VintedTaxonomy.materials) {
                            val on = m in materials
                            Choice(m, on) {
                                materials = when {
                                    on -> materials - m
                                    materials.size < VintedTaxonomy.MAX_MATERIALS -> materials + m
                                    else -> materials
                                }
                            }
                        }
                    }
                }
                if ("marca" in doubts) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = brandOk, onCheckedChange = { brandOk = it })
                        Text("La marca «${listing?.brand.orEmpty()}» es correcta", style = MaterialTheme.typography.bodyMedium)
                    }
                    Muted("Si no lo es, cámbiala en Editar y vuelve a preparar.")
                }
            }
        },
        confirmButton = {
            val sizeOk = category?.let(VintedSizes::options)?.let { it.isEmpty() || size in it } ?: false
            val ok = ("categoria" !in doubts || category != null) &&
                ("talla" !in doubts || sizeOk) &&
                ("colores" !in doubts || colors.isNotEmpty()) &&
                ("marca" !in doubts || brandOk)
            TextButton(
                onClick = {
                    onDismiss()
                    vm.fixDraft(
                        article.number,
                        pcTask.fixes.copy(
                            category = if ("categoria" in doubts) category else pcTask.fixes.category,
                            size = if ("talla" in doubts) size else pcTask.fixes.size,
                            colors = if ("colores" in doubts) colors else pcTask.fixes.colors,
                            materials = if ("materiales" in doubts) materials else pcTask.fixes.materials,
                            brandChecked = pcTask.fixes.brandChecked || brandOk,
                        ),
                    )
                },
                enabled = ok,
            ) { Text("Confirmar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Luego") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
