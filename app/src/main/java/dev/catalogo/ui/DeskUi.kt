package dev.catalogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.AppState
import dev.catalogo.core.Article
import dev.catalogo.core.Catalog
import dev.catalogo.core.Desk
import dev.catalogo.core.Draft
import dev.catalogo.core.ParentState
import dev.catalogo.core.PcLink
import dev.catalogo.core.Vinted

/** The article's way to Vinted through the PC: one button, then its progress. */
@Composable
fun DeskTile(vm: MainViewModel, p: ParentState, article: Article, state: AppState) {
    val desk = article.desk
    var reviewing by remember { mutableStateOf(false) }
    Tile {
        when {
            state.pc == null -> Muted("Conecta el PC en Ajustes para crear el borrador automáticamente")
            desk == null -> Muted("La IA prepara los datos y el PC crea el borrador en Vinted. Puedes seguir con otros artículos.")
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (desk.working) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(Desk.label(desk.status), style = MaterialTheme.typography.titleMedium)
                }
                val extra = listOfNotNull(
                    desk.message.takeIf { it.isNotBlank() },
                    desk.game.takeIf { it.isNotBlank() }?.let { "Juego: $it" },
                )
                for (line in extra) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (desk.needsUser) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (article.price == null && state.pc != null) Muted("Falta el precio de Vinted (Editar)", Modifier.padding(top = 4.dp))
        when {
            desk?.status == Desk.REVIEW && desk.doubts.isNotEmpty() -> FilledTonalButton(
                onClick = { reviewing = true },
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Revisar ${desk.doubts.size} ${if (desk.doubts.size == 1) "dato" else "datos"}") }
            desk == null || desk.needsUser -> FilledTonalButton(
                onClick = { if (desk == null) vm.prepareDraft(article.number) else vm.retryDraft(article.number) },
                enabled = state.pc != null,
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text(if (desk == null) "Generar y preparar borrador" else "Volver a intentarlo") }
        }
    }
    if (reviewing && desk != null) {
        ReviewDialog(vm, p, article, desk, onDismiss = { reviewing = false })
    }
}

/** Only the doubtful fields, with the AI's choice already marked: one tap to confirm. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewDialog(vm: MainViewModel, p: ParentState, article: Article, desk: Desk, onDismiss: () -> Unit) {
    val ai = Catalog.freshAi(p, article.number)
    val listing = article.listing
    var category by remember { mutableStateOf(desk.fixes.category ?: ai?.category?.takeIf { it in Vinted.categories }) }
    var search by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(desk.fixes.size ?: listing?.let(Draft::vintedSize).orEmpty()) }
    var colors by remember { mutableStateOf(desk.fixes.colors ?: ai?.colors?.mapNotNull(Vinted::color).orEmpty()) }
    var materials by remember { mutableStateOf(desk.fixes.materials ?: ai?.materials.orEmpty()) }
    var brandOk by remember { mutableStateOf(desk.fixes.brandChecked) }
    val doubts = desk.doubts

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
                        val found = Vinted.categories.filter { c ->
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
                    OutlinedTextField(
                        value = size,
                        onValueChange = { size = it.take(20) },
                        label = { Text("Talla (como en Vinted: W32, M, 42…)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if ("colores" in doubts) {
                    Muted("Color (hasta ${Vinted.MAX_COLORS})")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in Vinted.colors) {
                            val on = c in colors
                            Choice(c, on) {
                                colors = when {
                                    on -> colors - c
                                    colors.size < Vinted.MAX_COLORS -> colors + c
                                    else -> colors
                                }
                            }
                        }
                    }
                }
                if ("materiales" in doubts) {
                    Muted("Material (hasta ${Vinted.MAX_MATERIALS}, opcional)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in Vinted.materials) {
                            val on = m in materials
                            Choice(m, on) {
                                materials = when {
                                    on -> materials - m
                                    materials.size < Vinted.MAX_MATERIALS -> materials + m
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
            val ok = ("categoria" !in doubts || category != null) &&
                ("talla" !in doubts || size.isNotBlank()) &&
                ("colores" !in doubts || colors.isNotEmpty()) &&
                ("marca" !in doubts || brandOk)
            TextButton(
                onClick = {
                    onDismiss()
                    vm.fixDraft(
                        article.number,
                        desk.fixes.copy(
                            category = if ("categoria" in doubts) category else desk.fixes.category,
                            size = if ("talla" in doubts) size.trim() else desk.fixes.size,
                            colors = if ("colores" in doubts) colors else desk.fixes.colors,
                            materials = if ("materiales" in doubts) materials else desk.fixes.materials,
                            brandChecked = desk.fixes.brandChecked || brandOk,
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

/** Home: what the PC is doing, with start and pause. */
@Composable
fun PcCard(vm: MainViewModel) {
    val pc by vm.pc.collectAsStateWithLifecycle()
    val status = pc.status
    Tile {
        Row2(
            left = {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            pc.error != null -> "PC sin conexión"
                            status == null -> "PC: conectando…"
                            status.paused -> "PC en pausa"
                            else -> "PC: ${status.text}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val sub = pc.error ?: status?.next?.takeIf { it.isNotBlank() }
                    if (sub != null) Muted(sub, maxLines = 2)
                }
            },
            right = {
                if (status != null && pc.error == null) {
                    val running = status.text != "parado"
                    FilledTonalButton(
                        onClick = {
                            vm.pcControl(
                                when {
                                    !running -> "iniciar"
                                    status.paused -> "reanudar"
                                    else -> "pausar"
                                },
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                    ) { OneLine(if (!running) "Empezar" else if (status.paused) "Seguir" else "Pausar") }
                }
            },
        )
    }
}

/** Settings: address and key of the PC (stay on this phone). */
@Composable
fun PcSettingsTile(vm: MainViewModel, saved: PcLink?) {
    var editing by remember(saved) { mutableStateOf(saved == null) }
    var url by remember(saved) { mutableStateOf(saved?.url ?: "http://") }
    var token by remember(saved) { mutableStateOf("") }
    Tile {
        if (!editing && saved != null) {
            Row2(
                left = {
                    Column {
                        Text(saved.url.removePrefix("http://"), style = MaterialTheme.typography.titleMedium)
                        Muted("Clave •••• ${saved.token.takeLast(4)}")
                    }
                },
                right = { TextButton(onClick = { editing = true }) { Text("Cambiar") } },
            )
            return@Tile
        }
        Muted("Dirección del PC con el puerto (Tailscale o wifi de casa), p. ej. 100.112.139.110:8765, y la clave de config.toml")
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim().take(80) },
            label = { Text("Dirección") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim().take(100) },
            label = { Text("Clave") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saved != null) {
                OutlinedButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) { OneLine("Cancelar") }
            }
            FilledTonalButton(
                onClick = { vm.savePc(url, token) { editing = false } },
                enabled = url.length > 10 && token.length >= 16,
                modifier = Modifier.weight(1f),
            ) { OneLine("Probar y guardar") }
        }
        if (saved != null) {
            TextButton(onClick = { vm.forgetPc() }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Desconectar el PC", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
