package dev.catalogo.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.Catalog
import dev.catalogo.core.Garments
import dev.catalogo.core.Ledger
import dev.catalogo.core.Money
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sale
import dev.catalogo.core.SaleStatus
import dev.catalogo.core.Sales
import dev.catalogo.core.Sku
import dev.catalogo.core.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the sales tab is showing; kept while a sale is open. */
data class SalesView(val period: Stats.Period = Stats.Period.ALL, val status: SaleStatus? = null)

private val SPANISH = Locale("es", "ES")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM", SPANISH)
private val FULL_FORMAT = DateTimeFormatter.ofPattern("d 'de' MMMM yyyy", SPANISH)
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("LLLL yyyy", SPANISH)

private fun date(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
fun longDate(at: Long): String = FULL_FORMAT.format(date(at))

val SaleGreen = Color(0xFF6CC48A)

/** The colour and word a sale is recognised by. */
@Composable
fun saleTone(sale: Sale): Pair<Color, String> = when {
    sale.conflict -> MaterialTheme.colorScheme.error to "Revisar"
    sale.status == SaleStatus.PENDING -> Tone.Warn.color to sale.status.label
    sale.status == SaleStatus.COMPLETED -> SaleGreen to sale.status.label
    else -> Tone.Idle.color to sale.status.label
}

@Composable
fun SaleChip(sale: Sale) {
    val (color, text) = saleTone(sale)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, softWrap = false)
    }
}

/** «SH0012», or null while nobody knows which article it was. */
fun saleCode(p: ParentState, sale: Sale): String? = sale.code.ifBlank {
    if (sale.number < 0) "" else Sku.format(Catalog.device(p, sale.deviceId)?.prefix ?: p.self.prefix, sale.number, p.digits)
}.ifBlank { null }

fun saleTitle(sale: Sale): String = sale.title.ifBlank {
    listOf(sale.brand, Garments.type(sale.type)?.label.orEmpty()).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Venta" }
}

/** The picture of a sale: its article's cover while there is one, else the one kept or Vinted's. */
@Composable
fun SaleImage(vm: MainViewModel, p: ParentState, sale: Sale, modifier: Modifier, edge: Int = 256) {
    val covers by vm.articleCovers.collectAsStateWithLifecycle()
    val cover: Uri? = Sales.article(p, sale)?.let { covers[it.number] }
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, sale.id, sale.photo, cover, edge) {
        val source = cover ?: vm.saleImage(sale)?.let(Uri::fromFile)
        value = source?.let { uri -> withContext(Dispatchers.IO) { runCatching { loadPhoto(context, uri, edge) }.getOrNull() } }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val picture = bitmap
        if (picture != null) {
            Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // Its photos are gone: the code is what recognises it.
            saleCode(p, sale)?.let {
                Text(
                    it, style = Mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** «Ventas»: where the money is, what needs the user, and every sale. The numbers behind are in their own screen. */
@Composable
fun SalesScreen(vm: MainViewModel, p: ParentState) {
    val view by vm.salesView.collectAsStateWithLifecycle()
    val reading by vm.reading.collectAsStateWithLifecycle()
    val working = reading?.working == true
    val sales = remember(p) { Ledger.counted(p.sales) }
    val total = remember(p) { Stats.summary(p.sales) }
    val doubts = remember(p) { Sales.doubts(p) }

    Scaffold(
        topBar = {
            Header("Ventas", reading?.text) {
                IconButton(onClick = { vm.go(Screen.SalesStats) }) { Icon(StatsIcon, contentDescription = "Estadísticas") }
                IconButton(onClick = { vm.readSales(!working) }) {
                    if (working) Icon(Icons.Default.Close, contentDescription = "Parar la lectura de ventas")
                    else Icon(Icons.Default.Refresh, contentDescription = "Pedir al PC que lea mis ventas")
                }
                IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, contentDescription = "Ajustes") }
            }
        },
        bottomBar = { MainTabs(vm, Screen.Sales) },
    ) { padding ->
        if (sales.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Muted("Aún no hay ventas")
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                BigButton(if (working) "Parar" else "Que el PC lea mis ventas", onClick = { vm.readSales(!working) })
            }
            return@Scaffold
        }
        val listed = remember(sales, view.status) {
            sales.filter { view.status == null || it.status == view.status }
                .sortedWith(compareByDescending<Sale> { it.conflict }.thenByDescending { it.soldAt })
        }
        val review = listed.filter { it.conflict }
        val months = remember(listed) { listed.filter { !it.conflict }.groupBy { YearMonth.from(date(it.soldAt)) } }
        LazyColumn(
            state = vm.salesScroll,
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (working) item(key = "working") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item(key = "money") {
                KpiGrid(
                    listOf(
                        Triple("En camino", Money.format(total.pendingRevenue), count(total.pending)),
                        Triple("Cobrado", Money.format(total.revenue), count(total.sold)),
                        Triple("Beneficio en camino", Money.format(total.pendingProfit), costNote(sales, SaleStatus.PENDING)),
                        Triple("Beneficio cobrado", Money.format(total.profit), costNote(sales, SaleStatus.COMPLETED)),
                    ),
                )
            }
            items(doubts, key = { "doubt-${it.first.id}" }) { (order, hand) -> DoubtCard(vm, p, order, hand) }
            item(key = "statuses") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (status in SaleStatus.entries) {
                        val plural = when (status) {
                            SaleStatus.PENDING -> "En curso"
                            SaleStatus.COMPLETED -> "Cobradas"
                            SaleStatus.CANCELLED -> "Anuladas"
                        }
                        Choice("$plural ${sales.count { it.status == status }}", view.status == status) {
                            vm.showSales(view.copy(status = status.takeIf { view.status != status }))
                        }
                    }
                }
            }
            if (listed.isEmpty()) item(key = "none") { Muted("Ninguna venta aquí", Modifier.padding(start = 4.dp, top = 16.dp)) }
            if (review.isNotEmpty()) {
                item(key = "review-title") { Section("Para revisar") }
                items(review, key = { it.id }) { SaleCard(vm, p, it) }
            }
            for ((month, list) in months) {
                item(key = "month-$month") { Section(MONTH_FORMAT.format(month).replaceFirstChar { it.uppercase(SPANISH) }) }
                items(list, key = { it.id }) { SaleCard(vm, p, it) }
            }
        }
    }
}

private fun count(n: Int): String = if (n == 1) "1 venta" else "$n ventas"

/** What a profit figure leaves out. */
private fun costNote(sales: List<Sale>, status: SaleStatus): String {
    val unknown = sales.count { it.status == status && it.cost == null }
    return if (unknown == 0) "descontado el coste" else "$unknown sin coste, fuera"
}

/** A Vinted order and a sale written by hand that may be one and the same: only the user knows. */
@Composable
fun DoubtCard(vm: MainViewModel, p: ParentState, order: Sale, hand: Sale) {
    Tile {
        Text("¿Es la misma venta?", style = MaterialTheme.typography.titleMedium, color = Tone.Warn.color)
        Text(saleTitle(order), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        for ((from, sale) in listOf("En Vinted" to order, "Apuntada a mano" to hand)) {
            Row(Modifier.fillMaxWidth()) {
                Muted(listOfNotNull(from, saleCode(p, sale), DAY_FORMAT.format(date(sale.soldAt))).joinToString(" · "), Modifier.weight(1f))
                Text(Money.format(sale.price), style = Mono.copy(fontSize = 14.sp), maxLines = 1, softWrap = false)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { vm.mergeSale(order.id) }, modifier = Modifier.weight(1f)) { Text("Es la misma", maxLines = 1) }
            OutlinedButton(onClick = { vm.separateSale(order.id) }, modifier = Modifier.weight(1f)) { Text("Son dos", maxLines = 1) }
        }
    }
}

/** Where a sale is, in the words of Vinted while it is on its way. */
private fun saleState(sale: Sale): String = when {
    sale.conflict -> "para revisar"
    sale.status == SaleStatus.PENDING -> sale.detail.trim().trimEnd('.').ifBlank { "en curso" }
    sale.status == SaleStatus.COMPLETED -> "cobrada"
    else -> "anulada"
}

/** Photo, then three lines that are never cut short: title and price, code and account with profit, date and state. */
@Composable
private fun SaleCard(vm: MainViewModel, p: ParentState, sale: Sale) {
    val cancelled = sale.status == SaleStatus.CANCELLED
    Tile(onClick = { vm.go(Screen.Sale(sale.id)) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SaleImage(vm, p, sale, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        saleTitle(sale), style = MaterialTheme.typography.titleSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(
                        Money.format(sale.price),
                        style = Mono.copy(fontSize = 16.sp, textDecoration = if (cancelled) TextDecoration.LineThrough else TextDecoration.None),
                        color = if (cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, softWrap = false,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Muted(
                        listOfNotNull(saleCode(p, sale) ?: "Sin código", Catalog.device(p, sale.deviceId)?.label).joinToString(" · "),
                        Modifier.weight(1f),
                    )
                    val cost = sale.cost
                    if (!cancelled && cost != null) {
                        val profit = sale.price - cost
                        Text(
                            (if (profit > 0) "+" else "") + Money.format(profit),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (profit > 0) SaleGreen else MaterialTheme.colorScheme.error,
                            maxLines = 1, softWrap = false,
                        )
                    }
                }
                Text(
                    "${DAY_FORMAT.format(date(sale.soldAt))} · ${saleState(sale)}",
                    style = MaterialTheme.typography.bodySmall, color = saleTone(sale).first,
                )
            }
        }
    }
}

/** The numbers behind the sales, for the period chosen. */
@Composable
fun SalesStatsScreen(vm: MainViewModel, p: ParentState) {
    val view by vm.salesView.collectAsStateWithLifecycle()
    val now = remember(p) { System.currentTimeMillis() }
    val inPeriod = remember(p, view.period) { Stats.within(Ledger.counted(p.sales), view.period, now) }
    val summary = remember(inPeriod) { Stats.summary(inPeriod) }
    val all = remember(p) { Stats.kpis(p, now) }
    val period = remember(p, inPeriod) { Stats.kpis(p.copy(sales = inPeriod), now) }

    Scaffold(topBar = { Bar("Estadísticas", onBack = { vm.back() }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "periods") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Stats.Period.entries, key = { it.name }) { option ->
                        Choice(option.label, view.period == option) { vm.showSales(view.copy(period = option)) }
                    }
                }
            }
            item(key = "numbers") {
                KpiGrid(
                    listOf(
                        Triple("Cobrado", Money.format(summary.revenue), count(summary.sold)),
                        Triple("Beneficio", Money.format(summary.profit), costNote(inPeriod, SaleStatus.COMPLETED)),
                        Triple("Precio medio", summary.averagePrice?.let(Money::format) ?: "—", "de lo cobrado"),
                        Triple("Rentabilidad", period.roi?.let { "$it %" } ?: "—", "sobre el coste"),
                        Triple("Días en venderse", summary.medianDays?.toString() ?: "—", "lo habitual"),
                        Triple("Vendido", all.sellThrough?.let { "$it %" } ?: "—", "de lo catalogado"),
                    ),
                )
            }
            item(key = "months") {
                Tile {
                    Text("Cobrado por mes", style = MaterialTheme.typography.titleMedium)
                    MonthBars(all.months)
                }
            }
            if (period.accounts.size > 1) {
                item(key = "accounts") {
                    Tile {
                        Text("Por cuenta", style = MaterialTheme.typography.titleMedium)
                        val max = period.accounts.maxOf { it.revenue }.coerceAtLeast(1)
                        for (a in period.accounts) {
                            BarRow(a.label, "${a.sold} vendidos$GAP${Money.format(a.revenue)}", a.revenue.toFloat() / max)
                        }
                    }
                }
            }
            if (period.brands.isNotEmpty()) {
                item(key = "brands") {
                    Tile {
                        Text("Marcas más vendidas", style = MaterialTheme.typography.titleMedium)
                        val max = period.brands.first().second.coerceAtLeast(1)
                        for ((brand, n) in period.brands.take(6)) BarRow(brand, if (n == 1) "1 venta" else "$n ventas", n.toFloat() / max)
                    }
                }
            }
        }
    }
}

/** A rising line: the statistics (material-icons-core has none). */
private val StatsIcon: ImageVector by lazy {
    ImageVector.Builder("Catalogo.Stats", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M16,6l2.29,2.29-4.88,4.88-4-4L2,16.59 3.41,18l6-6 4,4 6.3-6.29L22,12V6z"),
        fill = SolidColor(Color.Black),
    ).build()
}
