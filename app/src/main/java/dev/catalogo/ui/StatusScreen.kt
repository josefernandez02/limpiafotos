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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val articles = p.articles.filter { !it.released }
    // Pending notices are always open by default: a missed like must never hide behind a tap. The user can hide them.
    val now = System.currentTimeMillis()
    val urgent = incidents.any { it.level == "grave" && now - it.at < 3 * 3_600_000L }
    var alertsChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val showAlerts = alertsChoice ?: incidents.isNotEmpty()
    var showPlayed by rememberSaveable { mutableStateOf(false) }
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
            pc.status?.let { s -> if (s.health != null || s.today.isNotEmpty()) item(key = "salud") { HealthTile(s.health, s.today) } }
            if (incidents.isNotEmpty()) {
                item(key = "h-inc") {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Avisos (${incidents.size})", style = MaterialTheme.typography.titleSmall,
                            color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { alertsChoice = !showAlerts }) { Text(if (showAlerts) "Ocultar" else "Ver") }
                    }
                }
                if (showAlerts) item(key = "inc") {
                    Tile {
                        incidents.forEachIndexed { n, inc ->
                            if (n > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
                            IncidentRow(inc, onOpen = { url ->
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }, onDone = { vm.incidentReviewed(inc.id) })
                        }
                        if (incidents.size > 1) {
                            OutlinedButton(
                                onClick = { incidents.forEach { vm.incidentReviewed(it.id) } },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(44.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) { OneLine("Marcar todos como revisados") }
                        }
                    }
                }
            }
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
                        if (stage == Stage.PLAYED) TextButton(onClick = { showPlayed = !showPlayed }) { Text(if (showPlayed) "Ocultar" else "Ver") }
                    }
                }
                if (stage != Stage.PLAYED || showPlayed) item(key = "g-${stage.name}") {
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
            text = "Creará los borradores que haya en cola y publicará los de «Subir y jugar» justo antes de cada juego.",
            confirm = "Empezar",
            onConfirm = { vm.pcControl("iniciar") },
            onDismiss = { starting = false },
        )
    }
}


/** One notice from the PC: a watchdog warning (what happened, what to do) or a single link without like. */
@Composable
private fun IncidentRow(inc: dev.catalogo.data.Desk.Incident, onOpen: (String) -> Unit, onDone: () -> Unit) {
    var open by remember(inc.id) { mutableStateOf(false) }
    val color = when (inc.level) {
        "grave" -> MaterialTheme.colorScheme.error
        "aviso" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val links = inc.links.ifEmpty { listOfNotNull(inc.url.takeIf { it.startsWith("http") }) }
    fun itemName(url: String): String {
        val item = Regex("""/items/(\d+)(?:-([^/?#]+))?""").find(url)
        return item?.groupValues?.get(2)?.replace('-', ' ')?.takeIf { it.isNotBlank() } ?: item?.groupValues?.get(1) ?: url
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = inc.isWarning) { open = !open }) {
            Text(
                if (inc.isWarning) inc.title.ifBlank { inc.game } else inc.game,
                style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.weight(1f), maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Muted(SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(inc.at)))
        }
        if (inc.repeated) Text("Reincidente", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        if (inc.isWarning) {
            // Short by default: one line of what happened; tapping adds the first things to do, never a wall of text.
            Muted(inc.summary.ifBlank { inc.reason }, maxLines = if (open) 4 else 2)
            if (open) {
                if (inc.actions.isNotEmpty()) {
                    Text("Qué hacer", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    inc.actions.take(2).forEachIndexed { i, s -> Muted("${i + 1}. $s", maxLines = 3) }
                }
                if (links.isNotEmpty()) {
                    Text("Para revisar", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    links.take(3).forEach { u ->
                        Text(itemName(u).replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onOpen(u) }.padding(vertical = 4.dp))
                    }
                    if (links.size > 3) Muted("y ${links.size - 3} más")
                }
            }
        } else {
            Text(itemName(inc.url).replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Muted(inc.reason.replaceFirstChar(Char::uppercase), maxLines = 2)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!inc.isWarning && links.isNotEmpty()) {
                OutlinedButton(onClick = { onOpen(links.first()) }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp)) {
                    OneLine("Abrir en Vinted")
                }
            }
            FilledTonalButton(onClick = onDone, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp)) {
                OneLine("Revisado")
            }
        }
    }
}

/** The watchdog's last hours at a glance: games, how many fine, warnings, serious ones, and the latest not fine. */
@Composable
private fun HealthTile(h: dev.catalogo.data.Desk.Health?, today: List<dev.catalogo.data.Desk.GameDay>) {
    var more by rememberSaveable { mutableStateOf(false) }
    Tile {
        // Two lines are the whole story; the reasons and the latest problems are one tap away.
        if (today.isNotEmpty()) {
            Text("Hoy", style = MaterialTheme.typography.titleSmall)
            Muted(today.sortedBy { it.game }.joinToString(GAP) { "${it.game} ${it.played}/${it.hours}" }, maxLines = 2)
        }
        if (h != null) {
            Text("Últimas ${h.hours} h", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = if (today.isNotEmpty()) 8.dp else 0.dp))
            Muted(if (h.games == 0) "Ningún juego registrado todavía." else "${h.games} juegos · ${h.ok} bien · ${h.warnings} con aviso · ${h.serious} graves")
        }
        val reasons = today.flatMap { g -> g.reasons.entries.map { Triple(g.game, it.key, it.value) } }.sortedByDescending { it.third }.take(3)
        val problems = h?.last?.filter { it.level == "aviso" || it.level == "grave" }?.takeLast(3)?.reversed().orEmpty()
        if (reasons.isNotEmpty() || problems.isNotEmpty()) {
            if (more) {
                reasons.forEach { (game, why, n) -> Muted("$n × $why ($game)", maxLines = 2) }
                problems.forEach {
                    val hhmm = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(it.at))
                    Muted("${if (it.level == "grave") "🔴" else "🟠"} $hhmm ${it.game}: ${it.summary}", maxLines = 1)
                }
            }
            TextButton(onClick = { more = !more }) { Text(if (more) "Menos" else "Detalle") }
        }
    }
}
