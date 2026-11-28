package dev.catalogo.ui.pc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.model.Article
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.Sku
import dev.catalogo.core.model.Stage
import dev.catalogo.ui.MainTabs
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.Screen
import dev.catalogo.ui.common.Header
import dev.catalogo.ui.common.Mono
import dev.catalogo.ui.common.Muted
import dev.catalogo.ui.common.OneLine
import dev.catalogo.ui.common.StatusChip
import dev.catalogo.ui.common.Tile

/**
 * «Estado»: what the PC is doing right now (with a button to ask it again when it
 * seems stuck) and every article on its way, grouped by where it is. Tapping one
 * opens it, where its status can also be changed by hand.
 */
@Composable
fun StatusScreen(vm: MainViewModel, p: CatalogState) {
    val pc by vm.pc.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val offers by vm.offers.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val articles = p.articles.filter { it.inStock }
    // Pending notices are always open by default: a missed like must never hide behind a tap. The user can hide them.
    val now = System.currentTimeMillis()
    val urgent = incidents.any { it.level == "grave" && now - it.at < 3 * 3_600_000L }
    var alertsChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val showAlerts = alertsChoice ?: incidents.isNotEmpty()
    var allAlerts by rememberSaveable { mutableStateOf(false) }
    val shownAlerts = if (allAlerts) incidents else incidents.take(ALERTS_SHOWN)
    val groups = listOf(Stage.REVIEW, Stage.WORKING, Stage.DRAFT, Stage.UPLOADED, Stage.PLAYED).associateWith { st ->
        articles.filter { Catalog.stage(p, it, p.mainAccount) == st }.sortedByDescending { it.pcTask?.at ?: 0 }
            .let { if (st == Stage.PLAYED) it.filter { a -> now - (a.pcTask?.at ?: 0) < 24 * 3_600_000L } else it }
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
                                else -> "Jugados en las últimas 24 h"
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

private const val ALERTS_SHOWN = 3

/** «0031  Levi's 505 W32 ·········· ● En curso», and below what is happening, if anything. */
@Composable
private fun StatusRow(vm: MainViewModel, p: CatalogState, a: Article) {
    val title = Catalog.freshAi(p, a.number)?.titles?.get(p.mainAccount.id)
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
            StatusChip(Catalog.stage(p, a, p.mainAccount))
        }
        val d = a.pcTask
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
