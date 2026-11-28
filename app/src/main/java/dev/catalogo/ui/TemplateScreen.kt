package dev.catalogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.catalogo.core.Garments
import dev.catalogo.core.Listing
import dev.catalogo.core.ListingWriter
import dev.catalogo.core.Measures
import dev.catalogo.core.CatalogState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import dev.catalogo.core.Catalog
import dev.catalogo.core.Sku

private val SAMPLE = Listing(
    type = "jeans", brand = "Levi's", model = "501", size = "W31 L32",
    keywords = listOf("vintage", "90s", "straight", "faded"), flat = Measures(waist = "40", length = "104"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateScreen(vm: MainViewModel, p: CatalogState, accountId: String) {
    val vintedAccount = Catalog.findAccount(p, accountId) ?: return
    var style by remember { mutableIntStateOf(vintedAccount.style) }
    var text by remember { mutableStateOf(TextFieldValue(ListingWriter.upgrade(vintedAccount.template, vintedAccount.style))) }
    val sample = p.articles.firstOrNull { it.listing != null && !it.released }?.listing ?: SAMPLE
    val sku = Sku.format(vintedAccount.prefix, 54, p.digits)

    fun insert(token: String) {
        val sel = text.selection
        val next = text.text.substring(0, sel.min) + token + text.text.substring(sel.max)
        text = TextFieldValue(next, TextRange(sel.min + token.length))
    }

    Scaffold(
        topBar = { Bar("Anuncio de ${vintedAccount.label}", onBack = { vm.back() }) },
        bottomBar = {
            BottomActions {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { text = TextFieldValue(ListingWriter.defaultTemplate(style)) },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { OneLine("Restablecer") }
                    Button(
                        onClick = {
                            val custom = text.text.trim().takeIf { it != ListingWriter.defaultTemplate(style) } ?: ""
                            vm.saveStyle(vintedAccount.id, style, custom)
                        },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { OneLine("Guardar") }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Estilo")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in 0 until Garments.STYLES) {
                    Choice("Estilo ${s + 1}", style == s) {
                        if (text.text.trim() == ListingWriter.defaultTemplate(style)) text = TextFieldValue(ListingWriter.defaultTemplate(s))
                        style = s
                    }
                }
            }

            Section("Descripción")
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (token in listOf("{talla}", "{medidas}", "{estado}", "{hashtags}", "{sku}")) Choice(token, false) { insert(token) }
            }

            Section("Vista previa")
            Tile {
                Text(ListingWriter.title(sample, style), style = MaterialTheme.typography.titleMedium)
                Text(
                    ListingWriter.description(sample, style, text.text, sku),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
