package dev.catalogo.ui.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.catalogo.data.pc.PcClient
import dev.catalogo.data.pc.PcSync
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.common.Confirm
import dev.catalogo.ui.common.GAP
import dev.catalogo.ui.common.Muted
import dev.catalogo.ui.common.OneLine
import dev.catalogo.ui.common.Tile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** The PC: what it is doing, when that was checked, and the buttons that matter (pause / go on now / stop). */
@Composable
internal fun PcPanel(vm: MainViewModel, pc: PcSync.Pc) {
    val status = pc.status
    var asking by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    // «Actualizar» shows it is asking until a newer answer (or error) arrives.
    LaunchedEffect(pc.at) { asking = false }
    LaunchedEffect(asking) { if (asking) { delay(12_000); asking = false } }
    val running = status != null && status.text != "parado"
    val waiting = running && status?.usingPc == true
    val headline = when {
        pc.error != null -> "Sin conexión"
        status == null -> "Conectando…"
        waiting -> "Esperando"
        status.paused -> "En pausa"
        status.text == "parado" -> "Parado"
        else -> status.text.replaceFirstChar(Char::uppercase)
    }
    val tone = when {
        pc.error != null -> MaterialTheme.colorScheme.error
        status == null || status.paused || waiting || !running -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                status?.name?.takeIf { it.isNotBlank() }?.let { "PC $it" } ?: "PC",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(8.dp))
            Text(headline, style = MaterialTheme.typography.titleMedium, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val sub = when {
            pc.error != null -> "Comprueba que el PC esté encendido"
            waiting -> "Sigue solo cuando dejes de usarlo"
            else -> status?.next?.takeIf { it.isNotBlank() }
        }
        if (sub != null) Muted(sub, Modifier.padding(top = 2.dp), maxLines = 1)
        val queue = status?.queue?.filterValues { it > 0 }?.takeIf { it.isNotEmpty() }?.let { q ->
            val names = mapOf("pendiente" to "en cola", "en_curso" to "creando", "borrador" to "borradores",
                "publicado" to "sin jugar", "revisar" to "a revisar", "error" to "con error")
            q.filterKeys { it in names }.entries.joinToString(GAP) { "${it.value} ${names[it.key]}" }
        }
        val checked = if (pc.at > 0) "a las " + SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(pc.at)) else null
        val foot = listOfNotNull(queue?.takeIf { it.isNotBlank() }, checked?.let { "visto $it" }).joinToString(GAP)
        if (foot.isNotBlank()) Muted(foot, Modifier.padding(top = 4.dp), maxLines = 1)
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

/** One switch per game: what is off is never played, and no article is kept for it. */
@Composable
fun GamesPanel(vm: MainViewModel, off: Set<String>) {
    val groups = listOf(
        Triple("ava", "Juegos AVA", "Grupo de WhatsApp"),
        Triple("likes", "Juego de Likes", "Grupo de WhatsApp"),
        Triple("discord", "Juegos de Discord", "Servidor de Discord"),
    )
    Tile {
        Muted("Si apagas uno, no se juega ninguno de ese grupo", maxLines = 1)
        groups.forEach { (kind, title, origin) ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Muted(origin)
                }
                androidx.compose.material3.Switch(checked = kind !in off, onCheckedChange = { vm.setGame(kind, it) })
            }
        }
    }
}

/** Automatic offers: one button. It goes through the likes until it reaches what was already offered, then stops. */
@Composable
internal fun OffersPanel(vm: MainViewModel, o: PcClient.Offers) {
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
