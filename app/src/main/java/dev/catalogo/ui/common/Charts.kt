package dev.catalogo.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.catalogo.core.catalog.Stats
import dev.catalogo.core.model.Money

/** One number with what it is above and a detail below. */
@Composable
fun Kpi(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Tile(modifier) {
        Muted(label, maxLines = 2)
        Text(value, style = Mono.copy(fontSize = 22.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Muted(detail, maxLines = 2)
    }
}

/** Pairs of equal tiles: same height, one line each, so the grid stays symmetric. */
@Composable
fun KpiGrid(tiles: List<Triple<String, String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (pair in tiles.chunked(2)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((label, value, detail) in pair) Kpi(label, value, detail, Modifier.weight(1f).fillMaxHeight())
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** A labelled horizontal bar: one value per row, one colour. */
@Composable
fun BarRow(label: String, detail: String, fraction: Float) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Muted(detail, maxLines = 1)
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
        }
    }
}

private val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

/**
 * Revenue of the last months as thin bars on a shared baseline. The latest
 * month is labelled; tapping a bar shows its numbers.
 */
@Composable
fun MonthBars(months: List<Stats.Month>) {
    var picked by remember(months) { mutableStateOf(months.lastIndex) }
    val max = months.maxOfOrNull { it.revenue }?.coerceAtLeast(1) ?: 1
    months.getOrNull(picked)?.let { m ->
        Muted("${MONTHS[m.month.monthValue - 1]} ${m.month.year}: ${m.sold} ventas$GAP${Money.format(m.revenue)}${GAP}beneficio ${Money.format(m.profit)}")
    }
    Row(
        Modifier.fillMaxWidth().height(120.dp).padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        months.forEachIndexed { i, m ->
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { picked = i },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                val f = m.revenue.toFloat() / max
                Box(
                    Modifier.fillMaxWidth(0.55f).fillMaxHeight(0.82f * f.coerceAtLeast(0.02f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (i == picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary),
                )
                Text(MONTHS[m.month.monthValue - 1], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** How a whole splits into parts: one bar, and under it what each colour is. */
@Composable
fun SplitBar(parts: List<Triple<String, Int, Color>>) {
    val total = parts.sumOf { it.second }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).height(12.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (total > 0) for ((_, count, color) in parts) {
            if (count > 0) Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(color))
        }
    }
    for ((label, count, color) in parts) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Muted(if (count == 1) "1 prenda" else "$count prendas", maxLines = 1)
        }
    }
}
