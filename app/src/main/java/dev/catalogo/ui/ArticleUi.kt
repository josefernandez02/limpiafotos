package dev.catalogo.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.catalogo.core.AppState
import dev.catalogo.core.Article
import dev.catalogo.core.Catalog
import dev.catalogo.core.Desk
import dev.catalogo.core.Device
import dev.catalogo.core.Money
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sizes
import dev.catalogo.core.Stage
import dev.catalogo.core.Vinted

/** How far an article is in one account, as a colour the eye finds at once. */
enum class Tone(val color: Color) {
    Idle(Color(0xFF8D9095)),      // por subir
    Busy(Color(0xFF7FA7E8)),      // la IA o el PC trabajando
    Ready(Color(0xFFB9A6F2)),     // borrador en Vinted
    Warn(Color(0xFFE8B45C)),      // necesita al usuario
    Done(Color(0xFF6CC48A)),      // subido
    Played(Color(0xFF5EC4C4)),    // subido y jugado
}

fun Stage.tone(): Tone = when (this) {
    Stage.TO_UPLOAD -> Tone.Idle
    Stage.REVIEW -> Tone.Warn
    Stage.WORKING -> Tone.Busy
    Stage.DRAFT -> Tone.Ready
    Stage.UPLOADED -> Tone.Done
    Stage.PLAYED -> Tone.Played
    Stage.SOLD -> Tone.Done
}

/** A steady colour per account, so each one is recognised at a glance in long lists. */
private fun accountColor(label: String): Color {
    val palette = listOf(0xFFE07A9B, 0xFF7FA7E8, 0xFF6CC48A, 0xFFE8B45C, 0xFFB9A6F2, 0xFF5EC4C4)
    return Color(palette[(label.lowercase().hashCode() and 0x7fffffff) % palette.size])
}

@Composable
fun AccountAvatar(label: String, size: Int = 22) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(accountColor(label)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color(0xFF0E0F11),
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.5f).sp,
        )
    }
}

/** One word in a pill, always the same size and place: «● Subido». */
@Composable
fun StatusChip(stage: Stage) {
    val c = stage.tone().color
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(stage.label, style = MaterialTheme.typography.labelMedium, color = c, maxLines = 1, softWrap = false)
    }
}

/** «(S) shiorawear ·········· ● Subido»: one line per account, the status always on the right. */
@Composable
fun AccountLine(label: String, stage: Stage) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AccountAvatar(label, 20)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        StatusChip(stage)
    }
}

/**
 * The article in Vinted: which accounts it goes to, how far it is in each, and the
 * one action that moves it forward. This phone's account is created by the PC; the
 * others are uploaded from their own phones and only marked here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VintedCard(vm: MainViewModel, p: ParentState, article: Article, state: AppState, waiting: Map<String, Int>) {
    val number = article.number
    val devices = Catalog.devices(p)
    val targets = article.targets
    Tile {
        if (devices.size > 1) {
            Muted("Cuentas")
            FlowRow(
                Modifier.padding(top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (d in devices) {
                    val on = d.id in targets
                    Choice(d.label, on) { vm.setAccounts(number, if (on) targets - d.id else targets + d.id) }
                }
            }
            if (targets.isEmpty()) Muted("Elige en qué cuenta lo vas a subir")
        }
        val shown = devices.filter { it.id in targets }.ifEmpty { if (devices.size == 1) devices else emptyList() }
        shown.forEachIndexed { i, d ->
            if (i > 0 || devices.size > 1) HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
            val stage = Catalog.stage(p, article, d)
            // Same header in every state: account on the left, its status on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(d.label, 34)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val v = article.vinted[d.id]
                    val facts = listOfNotNull(
                        v?.price?.let(Money::format),
                        v?.views?.let { "$it visitas" },
                        v?.favourites?.let { "♥ $it" },
                    )
                    if (facts.isNotEmpty()) Muted(facts.joinToString(GAP))
                }
                Spacer(Modifier.width(8.dp))
                StatusChip(stage)
            }
            val uploaded = d.id in article.uploaded
            Spacer(Modifier.size(12.dp))
            if (d.id == p.self.id) {
                DeskBlock(vm, p, article, state)
                return@forEachIndexed
            } else {
                val n = waiting[d.id] ?: 0
                Muted(
                    if (n > 0) "$n fotos se enviarán al ${d.name} al conectar" else "Se sube desde el ${d.name}",
                    Modifier.padding(top = 6.dp),
                )
            }
            // Other phones' accounts are uploaded from those phones: here only the mark.
            var asking by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { asking = true },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                shape = RoundedCornerShape(14.dp),
            ) { OneLine(if (uploaded) "Marcar como no subido" else "Ya está subido a mano") }
            if (asking) {
                Confirm(
                    title = if (uploaded) "¿Marcar como no subido?" else "¿Ya está subido en ${d.label}?",
                    text = if (uploaded) {
                        "Dejará de contar como subido en ${d.label}. No borra nada en Vinted."
                    } else {
                        "Contará como subido en ${d.label} y el PC no creará su borrador."
                    },
                    confirm = if (uploaded) "Marcar no subido" else "Sí, ya está subido",
                    onConfirm = { vm.setUploaded(number, d.id, !uploaded) },
                    onDismiss = { asking = false },
                    destructive = uploaded,
                )
            }
        }
    }
}

/** What goes to Vinted: title, description and the form's fields, as the PC will fill them. */
@Composable
fun ListingPreview(p: ParentState, article: Article, onEdit: () -> Unit) {
    val text = Catalog.listingText(p, article.number, p.self)
    val ai = Catalog.freshAi(p, article.number)
    val listing = article.listing
    val fixes = article.desk?.fixes
    var open by remember { mutableStateOf(false) }
    Tile(Modifier.animateContentSize()) {
        if (listing == null) {
            Muted("Sin datos del artículo")
            TextButton(onClick = onEdit) { Text("Completar datos") }
            return@Tile
        }
        if (ai == null || text == null || text.title.isBlank()) {
            Muted("El título, la descripción y la categoría los escribe la IA al preparar el borrador.")
        } else {
            Text(text.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).clickable { open = !open },
            )
            Text(
                if (open) "Ver menos" else "Ver descripción completa",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).clickable { open = !open },
            )
        }
        val category = (fixes?.category ?: ai?.category)?.takeIf { it in Vinted.categories }
        val size = when {
            category == null -> listing.size.ifBlank { null }
            !Sizes.asksForSize(category) -> "No lleva"
            else -> (fixes?.size?.takeIf { it in Sizes.options(category) } ?: Sizes.resolve(category, listing))
                ?.let { s -> if (listing.size.isNotBlank() && s != listing.size.trim()) "$s (etiqueta ${listing.size.trim()})" else s }
                ?: "Por elegir (etiqueta ${listing.size.ifBlank { "sin talla" }})"
        }
        val colors = (fixes?.colors ?: ai?.colors?.mapNotNull(Vinted::color)).orEmpty()
        val materials = (fixes?.materials ?: ai?.materials).orEmpty()
        val rows = listOf(
            "Categoría" to category,
            "Marca" to if (listing.noBrand) "Sin marca" else listing.brand.ifBlank { null },
            "Talla" to size,
            "Estado" to listing.condition.label,
            "Color" to colors.joinToString(", ").ifBlank { null },
            "Material" to materials.joinToString(", ").ifBlank { null },
            "Precio" to article.price?.let(Money::format),
            "Envío" to "Pequeño",
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, value) in rows) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(92.dp),
                    )
                    Text(
                        value ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
