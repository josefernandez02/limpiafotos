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
    val offers by vm.offers.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val articles = p.articles.filter { !it.released }
    // Pending notices are always open by default: a missed like must never hide behind a tap. The user can hide them.
    val now = System.currentTimeMillis()
    val urgent = incidents.any { it.level == "grave" && now - it.at < 3 * 3_600_000L }
    var alertsChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val showAlerts = alertsChoice ?: incidents.isNotEmpty()
    var showPlayed by rememberSaveable { mutableStateOf(false) }
    var allAlerts by rememberSaveable { mutableStateOf(false) }
    val shownAlerts = if (allAlerts) incidents else incidents.take(ALERTS_SHOWN)
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
            offers?.let { o -> item(key = "ofertas") { OffersPanel(vm, o) } }
            pc.status?.let { s -> if (s.health != null || s.today.isNotEmpty()) item(key = "salud") { HealthTile(s.health, s.today, incidents) } }
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
                        shownAlerts.forEachIndexed { n, inc ->
                            if (n > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
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
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (incidents.size > ALERTS_SHOWN) {
                                    TextButton(onClick = { allAlerts = !allAlerts }) {
                                        OneLine(if (allAlerts) "Ver menos" else "Ver ${incidents.size - ALERTS_SHOWN} más")
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                if (incidents.size > 1) {
                                    TextButton(onClick = { incidents.forEach { vm.incidentReviewed(it.id) } }) { OneLine("Marcar todos como revisados") }
                                }
                            }
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

private const val ALERTS_SHOWN = 3

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

/** The PC: what it is doing, when that was checked, and the buttons that matter (pause / go on now / stop). */
@Composable
private fun PcPanel(vm: MainViewModel, pc: dev.catalogo.data.Drafts.Pc) {
    val status = pc.status
    var asking by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    // «Actualizar» shows it is asking until a newer answer (or error) arrives.
    LaunchedEffect(pc.at) { asking = false }
    LaunchedEffect(asking) { if (asking) { delay(12_000); asking = false } }
    val running = status != null && status.text != "parado"
    val waiting = running && status?.usingPc == true
    Tile {
        Text(
            when {
                pc.error != null -> "PC sin conexión"
                status == null -> "Conectando con el PC"
                waiting -> "Esperando a que dejes el PC"
                status.paused -> "PC en pausa"
                status.text == "parado" -> "PC parado"
                else -> status.text.replaceFirstChar(Char::uppercase)
            },
            style = MaterialTheme.typography.titleLarge,
        )
        val sub = pc.error
            ?: if (waiting) "Retoma lo que estaba haciendo en cuanto pares" else status?.next?.takeIf { it.isNotBlank() }
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
        if (running && pc.error == null) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (waiting) {
                    FilledTonalButton(
                        onClick = { vm.pcControl("seguir") },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { OneLine("Seguir ya") }
                }
                OutlinedButton(
                    onClick = { stopping = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { OneLine("Detener", Modifier) }
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
    if (stopping) {
        Confirm(
            title = "¿Detener el programa del PC?",
            text = "Para lo que esté haciendo. Para volver a ponerlo en marcha, pulsa «Empezar» aquí mismo.",
            confirm = "Detener",
            onConfirm = { vm.pcControl("detener") },
            onDismiss = { stopping = false },
            destructive = true,
        )
    }
}


/** Automatic offers: one button. It goes through the likes until it reaches what was already offered, then stops. */
@Composable
private fun OffersPanel(vm: MainViewModel, o: dev.catalogo.data.Desk.Offers) {
    var showLast by rememberSaveable { mutableStateOf(false) }
    Tile {
        Text(if (o.working) "Ofertas: trabajando" else "Ofertas", style = MaterialTheme.typography.titleMedium)
        Muted(
            "Hoy ${o.today} enviadas" + (if (o.errorsToday > 0) " · ${o.errorsToday} con fallo" else "") + " · ${o.total} en total",
            Modifier.padding(top = 2.dp),
        )
        if (o.working) {
            Muted(o.waiting.ifBlank { "Recorriendo los favoritos hasta llegar a lo ya ofertado" }, Modifier.padding(top = 2.dp))
        } else if (o.lastRunAt > 0) {
            Muted(
                "Última vez " + SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(Date(o.lastRunAt)) +
                    ": ${o.lastRunSent} enviadas · ${o.lastRunWhy}",
                Modifier.padding(top = 2.dp), maxLines = 3,
            )
        }
        FilledTonalButton(
            onClick = { vm.offersControl(if (o.working) "parar" else "mandar") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
            shape = RoundedCornerShape(16.dp),
        ) { OneLine(if (o.working) "Parar" else "Mandar ofertas") }
        if (o.last.isNotEmpty()) {
            TextButton(onClick = { showLast = !showLast }) { Text(if (showLast) "Ocultar últimas" else "Ver últimas") }
            if (showLast) o.last.forEach { e ->
                val what = when (e.result) {
                    "ofertada" -> "${e.price.toInt()} € → ${e.offer.toInt()} €"
                    "error" -> "fallo: ${e.reason}"
                    "sin_oferta" -> "sin oferta"
                    else -> e.result
                }
                Muted("${e.user} · ${e.title.take(28)} · $what", Modifier.padding(top = 2.dp), maxLines = 2)
            }
        }
    }
}

/** One notice from the PC: a watchdog warning or a single link without like. Compact; tap it for what to do. */
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
    val title = (if (inc.isWarning) inc.title.ifBlank { inc.game } else inc.game).removePrefix("REINCIDENTE · ")
    val time = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(inc.at))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = color, maxLines = if (open) 3 else 1,
                    overflow = TextOverflow.Ellipsis)
                val body = if (inc.isWarning) inc.summary.ifBlank { inc.reason }
                else itemName(inc.url).replaceFirstChar(Char::uppercase) + GAP + inc.reason
                Muted((if (inc.repeated) "$time$GAP" + "Reincidente$GAP" else "$time$GAP") + body, maxLines = if (open) 6 else 1)
            }
            TextButton(onClick = onDone) { OneLine("Revisado") }
        }
        if (open) {
            if (inc.isWarning && inc.actions.isNotEmpty()) {
                Text("Qué hacer", style = MaterialTheme.typography.labelMedium)
                inc.actions.take(2).forEachIndexed { i, a -> Muted("${i + 1}. $a", maxLines = 3) }
            }
            if (links.isNotEmpty()) {
                Text("Para revisar", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                links.take(3).forEach { u ->
                    Text(itemName(u).replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpen(u) }.padding(vertical = 4.dp))
                }
                if (links.size > 3) Muted("y ${links.size - 3} más")
            }
        }
    }
}

/** Today's games, one line each: ✔ perfect, ⚠ to check, ✖ not played; what went wrong only for the last two. */
@Composable
private fun HealthTile(
    h: dev.catalogo.data.Desk.Health?, today: List<dev.catalogo.data.Desk.GameDay>, notices: List<dev.catalogo.data.Desk.Incident>,
) {
    val games = h?.today.orEmpty()
    Tile {
        Text("Juegos de hoy", style = MaterialTheme.typography.titleSmall)
        if (games.isEmpty()) {
            Muted(if (today.isEmpty()) "Todavía ninguno" else today.sortedBy { it.game }.joinToString(", ") { "${it.game} ${it.played} de ${it.hours}" })
            return@Tile
        }
        games.sortedBy { it.at }.forEachIndexed { n, g ->
            val color = when (g.label) {
                "perdido" -> MaterialTheme.colorScheme.error
                "revisar" -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurface
            }
            val mark = when (g.label) { "perdido" -> "✖"; "revisar" -> "⚠"; else -> "✔" }
            val word = when (g.label) { "perdido" -> "No se jugó"; "revisar" -> "A revisar"; else -> "Perfecto" }
            Row(Modifier.fillMaxWidth().padding(top = if (n == 0) 8.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(mark, color = color, modifier = Modifier.width(24.dp))
                Text("${g.time.ifBlank { SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(g.at)) }} ${g.game}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(word, style = MaterialTheme.typography.labelMedium, color = color)
            }
            // The detail lives in «Avisos»; it is only repeated here when no notice covers this game.
            val covered = notices.any { it.game.startsWith("${g.game} ${g.time}".trim()) }
            if (g.label != "perfecto" && g.summary.isNotBlank() && !covered) Muted(g.summary, Modifier.padding(start = 24.dp), maxLines = 1)
        }
    }
}
