package dev.catalogo.ui.pc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.catalogo.data.pc.PcClient
import dev.catalogo.ui.common.GAP
import dev.catalogo.ui.common.Muted
import dev.catalogo.ui.common.OneLine
import dev.catalogo.ui.common.Tile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One notice from the PC: a watchdog warning or a single link without like. Compact; tap it for what to do. */
@Composable
internal fun IncidentRow(inc: PcClient.Incident, onOpen: (String) -> Unit, onDone: () -> Unit) {
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
internal fun HealthTile(
    h: PcClient.Health?, today: List<PcClient.GameDay>, notices: List<PcClient.Incident>,
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
