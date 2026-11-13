package dev.catalogo.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import dev.catalogo.core.Sku
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
import dev.catalogo.core.Sizes
import dev.catalogo.core.Vinted

/** The article's way to Vinted through the PC: one button, then its progress. */
@Composable
fun DeskBlock(vm: MainViewModel, p: ParentState, article: Article, state: AppState) {
    val desk = article.desk
    var reviewing by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    // The PC already has photos and data: «Volver a intentarlo» asks it again, nothing is re-sent.
    val onPc = desk != null && (desk.status == "revisar" || desk.status == "error")
    Column(Modifier.fillMaxWidth()) {
        when {
            state.pc == null -> Muted("Conecta el PC en Ajustes")
            desk != null -> {
                // The status itself is the chip next to the account; here only the work in progress.
                if (desk.working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Muted(
                            when (desk.status) {
                                Desk.PREPARING -> "La IA está leyendo las fotos"
                                Desk.SENDING -> "Enviando fotos y datos al PC"
                                "pendiente" -> "En cola en el PC"
                                else -> "El PC está rellenando el formulario"
                            },
                        )
                    }
                }
                val extra = listOfNotNull(
                    desk.message.takeIf { it.isNotBlank() },
                    desk.game.takeIf { it.isNotBlank() && desk.status != "jugado" }?.let { "Juego: $it" },
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
            ) { OneLine("Revisar ${desk.doubts.size} ${if (desk.doubts.size == 1) "dato" else "datos"}") }
            desk == null || desk.needsUser -> FilledTonalButton(
                onClick = { if (onPc) vm.retryDraft(article.number) else choosing = true },
                enabled = state.pc != null,
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
            ) { OneLine(if (desk == null) "Generar y preparar borrador" else "Volver a intentarlo") }
        }
        // Stuck or not wanted now: out of the queue until it is sent again.
        if (desk != null && desk.status !in setOf("borrador", "publicado", "jugado")) {
            OutlinedButton(
                onClick = { removing = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
            ) { OneLine("Sacar de la cola") }
        }
    }
    if (removing) {
        Confirm(
            title = "¿Sacar de la cola?",
            text = "El PC no hará nada con este artículo hasta que lo vuelvas a mandar. No se borra nada.",
            confirm = "Sacar",
            onConfirm = { vm.removeDraft(article.number) },
            onDismiss = { removing = false },
            destructive = true,
        )
    }
    if (reviewing && desk != null) {
        ReviewDialog(vm, p, article, desk, onDismiss = { reviewing = false })
    }
    if (choosing) {
        PhotoChoiceDialog(vm, article.number, onDismiss = { choosing = false })
    }
}

/**
 * Which photos the AI looks at, right before the draft: all are marked; a tap
 * takes out one that adds nothing (or puts it back). Vinted gets all of them anyway.
 */
@Composable
fun PhotoChoiceDialog(vm: MainViewModel, number: Int, onDismiss: () -> Unit) {
    val loaded by produceState<Pair<List<Uri>, List<Uri>>?>(null, number) { value = vm.vintedPhotos(number) }
    var chosen by remember(loaded) { mutableStateOf(loaded?.second.orEmpty()) }
    val go = rememberWithPermissions(Perms.notifications, onDenied = { vm.prepareDraft(number, chosen) }) {
        vm.prepareDraft(number, chosen)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fotos para la IA") },
        text = {
            val album = loaded?.first
            when {
                album == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                album.isEmpty() -> Muted("Este artículo no tiene fotos")
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Muted("Quita las que no aportan. Al PC van todas.")
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.heightIn(max = 420.dp),
                    ) {
                        // Chosen ones first, in their order; the rest after, dimmed.
                        val shown = chosen + album.filter { it !in chosen }
                        items(shown, key = { it.toString() }) { uri ->
                            val at = chosen.indexOf(uri)
                            Box(
                                Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                    .clickable { chosen = if (at >= 0) chosen - uri else chosen + uri },
                            ) {
                                Thumb(uri, Modifier.fillMaxSize().alpha(if (at >= 0) 1f else 0.35f))
                                if (at >= 0) {
                                    Box(
                                        Modifier.padding(5.dp).size(24.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary).align(Alignment.TopStart),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("${at + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); go() }, enabled = chosen.isNotEmpty()) {
                Text(if (chosen.isEmpty()) "Generar" else "Generar con ${chosen.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Only the doubtful fields, with the AI's choice already marked: one tap to confirm. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewDialog(vm: MainViewModel, p: ParentState, article: Article, desk: Desk, onDismiss: () -> Unit) {
    val ai = Catalog.freshAi(p, article.number)
    val listing = article.listing
    var category by remember { mutableStateOf(desk.fixes.category ?: ai?.category?.takeIf { it in Vinted.categories }) }
    var search by remember { mutableStateOf("") }
    var size by remember {
        mutableStateOf(
            desk.fixes.size ?: (desk.fixes.category ?: ai?.category)?.takeIf { it in Vinted.categories }
                ?.let { c -> listing?.let { Sizes.resolve(c, it) } }.orEmpty(),
        )
    }
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
                    val options = category?.let(Sizes::options).orEmpty()
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
            val sizeOk = category?.let(Sizes::options)?.let { it.isEmpty() || size in it } ?: false
            val ok = ("categoria" !in doubts || category != null) &&
                ("talla" !in doubts || sizeOk) &&
                ("colores" !in doubts || colors.isNotEmpty()) &&
                ("marca" !in doubts || brandOk)
            TextButton(
                onClick = {
                    onDismiss()
                    vm.fixDraft(
                        article.number,
                        desk.fixes.copy(
                            category = if ("categoria" in doubts) category else desk.fixes.category,
                            size = if ("talla" in doubts) size else desk.fixes.size,
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

/**
 * Home: what the PC is doing, with start and pause, and the queue of articles
 * on their way (AI here, then the PC) so the work is visible without opening each one.
 */
@Composable
fun PcCard(vm: MainViewModel, p: ParentState) {
    val pc by vm.pc.collectAsStateWithLifecycle()
    val status = pc.status
    var starting by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val queue = p.articles.filter { a ->
        !a.released && a.desk != null && p.self.id !in a.uploaded && a.desk!!.status !in setOf("publicado", "jugado")
    }.sortedWith(compareBy({ stage(it.desk!!) }, { it.desk!!.at }))
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        pc.error != null -> "PC sin conexión"
                        status == null -> "Conectando con el PC"
                        status.paused -> "PC en pausa"
                        status.text == "parado" -> "PC parado"
                        else -> "PC " + status.text
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                val sub = pc.error ?: status?.next?.takeIf { it.isNotBlank() }
                if (sub != null) Muted(sub)
            }
            if (status != null && pc.error == null) {
                val running = status.text != "parado"
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(
                    onClick = {
                        when {
                            !running -> starting = true          // publishes and plays: asks first
                            status.paused -> vm.pcControl("reanudar")
                            else -> vm.pcControl("pausar")
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                ) { OneLine(if (!running) "Empezar" else if (status.paused) "Seguir" else "Pausar") }
            }
        }
        if (queue.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("En cola", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                val review = queue.count { it.desk!!.needsUser }
                Muted(listOfNotNull("${queue.size}", review.takeIf { it > 0 }?.let { "$it por revisar" }).joinToString(GAP))
                Text(if (open) "  ▲" else "  ▼", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in queue) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { vm.go(Screen.Article(a.number)) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(Sku.number(a.number, p.digits), style = Mono)
                            Column(Modifier.weight(1f)) {
                                StatusChip(accountStatus(p, a, p.self))
                                a.desk!!.message.takeIf { it.isNotBlank() }?.let { Muted(it, maxLines = 2) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (starting) {
        Confirm(
            title = "¿Empezar en el PC?",
            text = "Publicará los borradores listos y jugará con ellos cuando toque, con pausas entre acciones.",
            confirm = "Empezar",
            onConfirm = { vm.pcControl("iniciar") },
            onDismiss = { starting = false },
        )
    }
}

/** Queue order: what needs you, then the AI here, the upload, and the PC. */
private fun stage(d: Desk): Int = when {
    d.needsUser -> 0
    d.status == Desk.PREPARING -> 1
    d.status == Desk.SENDING -> 2
    d.status == "en_curso" -> 3
    d.status == "pendiente" -> 4
    else -> 5
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
            var forgetting by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { forgetting = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Desconectar el PC", color = MaterialTheme.colorScheme.error) }
            if (forgetting) {
                Confirm(
                    title = "¿Desconectar el PC?",
                    text = "Este móvil dejará de mandarle artículos hasta que lo vuelvas a configurar.",
                    confirm = "Desconectar",
                    onConfirm = { vm.forgetPc() },
                    onDismiss = { forgetting = false },
                    destructive = true,
                )
            }
        }
    }
}
