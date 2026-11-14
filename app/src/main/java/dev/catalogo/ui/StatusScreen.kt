package dev.catalogo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.Article
import dev.catalogo.core.Catalog
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sku
import dev.catalogo.core.Stage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Estado»: what the PC is doing right now (with a button to ask it again when it
 * seems stuck) and every article on its way, grouped by where it is. Tapping one
 * opens it, where its status can also be changed by hand.
 */
@Composable
fun StatusScreen(vm: MainViewModel, p: ParentState) {
    val pc by vm.pc.collectAsStateWithLifecycle()
    val articles = p.articles.filter { !it.released }
    val groups = listOf(Stage.REVIEW, Stage.WORKING, Stage.DRAFT, Stage.UPLOADED, Stage.PLAYED).associateWith { st ->
        articles.filter { Catalog.stage(p, it, p.self) == st }.sortedByDescending { it.desk?.at ?: 0 }
            .let { if (st == Stage.PLAYED) it.take(10) else it }
    }.filterValues { it.isNotEmpty() }

    Scaffold(topBar = { Header("Estado") }, bottomBar = { MainTabs(vm, Screen.Status) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "pc") { PcPanel(vm, pc) }
            if (groups.isEmpty()) item(key = "none") { Muted("Nada en marcha", Modifier.padding(start = 4.dp, top = 12.dp)) }
            for ((stage, list) in groups) {
                item(key = "h-${stage.name}") {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (stage) {
                                Stage.REVIEW -> "Necesitan que los mires"
                                Stage.WORKING -> "En curso"
                                Stage.DRAFT -> "Borradores en Vinted"
                                Stage.UPLOADED -> "Subidos, sin jugar"
                                else -> "Jugados"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Muted("${list.size}")
                    }
                }
                item(key = "g-${stage.name}") {
                    Tile {
                        list.forEachIndexed { i, a ->
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
                            StatusRow(vm, p, a)
                        }
                    }
                }
            }
        }
    }
}

/** «0031  Levi's 505 W32 ·········· ● En curso», and below what is happening, if anything. */
@Composable
private fun StatusRow(vm: MainViewModel, p: ParentState, a: Article) {
    val title = Catalog.freshAi(p, a.number)?.titles?.get(p.self.id)
        ?: a.listing?.let { l -> listOf(l.brand, l.model, l.size).filter { it.isNotBlank() }.joinToString(" ") }.orEmpty()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Article(a.number)) }.padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Sku.number(a.number, p.digits), style = Mono)
            Spacer(Modifier.width(12.dp))
            Text(
                title.ifBlank { "Sin datos" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusChip(Catalog.stage(p, a, p.self))
        }
        val d = a.desk
        val line = when {
            d == null -> null
            d.pending.isNotBlank() -> "Pendiente de avisar al PC"
            d.message.isNotBlank() -> d.message
            d.status == "jugado" && d.game.isNotBlank() -> "Jugado en ${d.game}"
            else -> null
        }
        if (line != null) Muted(line, maxLines = 2)
    }
}

/** The PC: what it is doing, when that was checked, and the two buttons that matter. */
@Composable
private fun PcPanel(vm: MainViewModel, pc: dev.catalogo.data.Drafts.Pc) {
    val status = pc.status
    var asking by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    // «Actualizar» shows it is asking until a newer answer (or error) arrives.
    LaunchedEffect(pc.at) { asking = false }
    LaunchedEffect(asking) { if (asking) { delay(12_000); asking = false } }
    Tile {
        Text(
            when {
                pc.error != null -> "PC sin conexión"
                status == null -> "Conectando con el PC"
                status.paused -> "PC en pausa"
                status.text == "parado" -> "PC parado"
                else -> status.text.replaceFirstChar(Char::uppercase)
            },
            style = MaterialTheme.typography.titleLarge,
        )
        val sub = pc.error ?: status?.next?.takeIf { it.isNotBlank() }
        if (sub != null) Muted(sub, Modifier.padding(top = 2.dp))
        if (pc.at > 0) {
            Muted(
                "Comprobado a las " + SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(pc.at)),
                Modifier.padding(top = 6.dp),
            )
        }
        status?.queue?.filterValues { it > 0 }?.takeIf { it.isNotEmpty() }?.let { q ->
            val names = mapOf("pendiente" to "En cola", "en_curso" to "Creando", "borrador" to "Borradores",
                "publicado" to "Sin jugar", "revisar" to "Revisar", "error" to "Error")
            Muted(q.filterKeys { it in names }.entries.joinToString(GAP) { "${names[it.key]} ${it.value}" }, Modifier.padding(top = 2.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { asking = true; vm.refreshPc() },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (asking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else OneLine("Actualizar")
            }
            if (status != null && pc.error == null) {
                val running = status.text != "parado"
                FilledTonalButton(
                    onClick = {
                        when {
                            !running -> starting = true
                            status.paused -> vm.pcControl("reanudar")
                            else -> vm.pcControl("pausar")
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { OneLine(if (!running) "Empezar" else if (status.paused) "Seguir" else "Pausar") }
            }
        }
    }
    if (starting) {
        Confirm(
            title = "¿Empezar en el PC?",
            text = "Publicará los borradores que tocan y jugará con ellos, con pausas entre acciones.",
            confirm = "Empezar",
            onConfirm = { vm.pcControl("iniciar") },
            onDismiss = { starting = false },
        )
    }
}
