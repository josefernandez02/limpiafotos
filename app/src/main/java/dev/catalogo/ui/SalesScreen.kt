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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.catalogo.core.Catalog
import dev.catalogo.core.Garments
import dev.catalogo.core.Money
import dev.catalogo.core.ParentState
import dev.catalogo.core.Sale
import dev.catalogo.core.SaleStatus
import dev.catalogo.core.Sales
import dev.catalogo.core.Sku
import dev.catalogo.core.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the sales tab is showing; kept while a sale is open. */
data class SalesView(val stats: Boolean = false, val period: Stats.Period = Stats.Period.ALL, val status: SaleStatus? = null)

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
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** «Ventas»: the money first, then every sale by month; the numbers behind them in «Estadísticas». */
@Composable
fun SalesScreen(vm: MainViewModel, p: ParentState) {
    val view by vm.salesView.collectAsStateWithLifecycle()
    val now = remember(p) { System.currentTimeMillis() }
    val inPeriod = remember(p, view.period) { Stats.within(p.sales, view.period, now) }
    val summary = remember(inPeriod) { Stats.summary(inPeriod) }
    val current = remember(p) { Stats.summary(p.sales) }
    val reading by vm.reading.collectAsStateWithLifecycle()
    val working = reading?.working == true

    // While the tab is on screen, what the PC reads comes in by itself.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshSales()
                delay(if (vm.reading.value?.working == true) 8_000 else 30_000)
            }
        }
    }

    Scaffold(
        topBar = {
            val subtitle = when {
                current.pending == 0 -> if (current.sold == 1) "1 completada" else "${current.sold} completadas"
                else -> "${current.pending} en curso$GAP${current.sold} completadas"
            }
            Header("Ventas", subtitle) {
                IconButton(onClick = { vm.readSales(!working) }) {
                    if (working) Icon(Icons.Default.Close, contentDescription = "Parar la lectura de ventas")
                    else Icon(Icons.Default.Refresh, contentDescription = "Pedir al PC que lea mis ventas")
                }
                IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, contentDescription = "Ajustes") }
            }
        },
        bottomBar = { MainTabs(vm, Screen.Sales) },
    ) { padding ->
        if (p.sales.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Muted("Aún no hay ventas")
                reading?.let { ReadingCard(it) }
                BigButton(if (working) "Parar" else "Que el PC lea mis ventas", onClick = { vm.readSales(!working) })
            }
            return@Scaffold
        }
        // Sales in progress are today's business: they show whatever the period.
        val listed = remember(p, inPeriod, view.status) {
            (inPeriod + p.sales.filter { it.status == SaleStatus.PENDING }).distinctBy { it.id }
                .filter { view.status == null || it.status == view.status }
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
            reading?.let { r -> item(key = "reading") { ReadingCard(r) } }
            item(key = "mode") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Ventas", !view.stats) { vm.showSales(view.copy(stats = false)) }
                    Choice("Estadísticas", view.stats) { vm.showSales(view.copy(stats = true)) }
                }
            }
            item(key = "periods") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Stats.Period.entries, key = { it.name }) { period ->
                        Choice(period.label, view.period == period) { vm.showSales(view.copy(period = period)) }
                    }
                }
            }
            item(key = "money") { MoneyCard(summary, current) }
            if (view.stats) {
                statistics(p, inPeriod, summary, now)
                return@LazyColumn
            }
            item(key = "statuses") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 6.dp)) {
                    item { Choice("Todas", view.status == null) { vm.showSales(view.copy(status = null)) } }
                    items(SaleStatus.entries, key = { it.name }) { status ->
                        val n = if (status == SaleStatus.PENDING) current.pending else inPeriod.count { it.status == status }
                        val plural = when (status) {
                            SaleStatus.PENDING -> "En curso"
                            SaleStatus.COMPLETED -> "Completadas"
                            SaleStatus.CANCELLED -> "Canceladas"
                        }
                        Choice("$plural ($n)", view.status == status) { vm.showSales(view.copy(status = status)) }
                    }
                }
            }
            if (listed.isEmpty()) item(key = "none") { Muted("Ninguna venta aquí", Modifier.padding(start = 4.dp, top = 16.dp)) }
            if (review.isNotEmpty()) {
                item(key = "review-title") { Section("Para revisar") }
                items(review, key = { it.id }) { SaleCard(vm, p, it) }
            }
            for ((month, sales) in months) {
                item(key = "month-$month") { MonthTitle(month, Stats.summary(sales)) }
                items(sales, key = { it.id }) { SaleCard(vm, p, it) }
            }
        }
    }
}

/** What the PC is doing about the sales. */
@Composable
private fun ReadingCard(reading: MainViewModel.SalesReading) {
    Tile {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(reading.text, style = MaterialTheme.typography.bodyMedium)
            if (reading.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MoneyCard(period: Stats.Summary, current: Stats.Summary) {
    Tile {
        Muted("Cobrado")
        Text(Money.format(period.revenue), style = Mono.copy(fontSize = 38.sp), maxLines = 1, softWrap = false)
        Muted(
            listOfNotNull(
                if (period.sold == 1) "1 venta completada" else "${period.sold} ventas completadas",
                period.averagePrice?.let { "media ${Money.format(it)}" },
            ).joinToString(GAP),
            maxLines = 1,
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Figure(
                "Beneficio", Money.format(period.profit), if (period.profit > 0) SaleGreen else MaterialTheme.colorScheme.onSurface,
                if (period.unknownCost == 0) "descontado el coste" else "${period.unknownCost} sin coste, fuera",
                Modifier.weight(1f),
            )
            Figure(
                "En camino", Money.format(current.pendingRevenue), if (current.pending > 0) Tone.Warn.color else MaterialTheme.colorScheme.onSurface,
                if (current.pending == 1) "1 venta en curso" else "${current.pending} ventas en curso",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Figure(label: String, value: String, color: Color, detail: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(12.dp),
    ) {
        Muted(label, maxLines = 1)
        Text(value, style = Mono.copy(fontSize = 20.sp), color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MonthTitle(month: YearMonth, summary: Stats.Summary) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            MONTH_FORMAT.format(month).replaceFirstChar { it.uppercase(SPANISH) },
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Muted(
            listOfNotNull(
                if (summary.sold > 0) "cobrado ${Money.format(summary.revenue)}" else null,
                if (summary.pending > 0) "en camino ${Money.format(summary.pendingRevenue)}" else null,
            ).joinToString(GAP),
            maxLines = 1,
        )
    }
}

@Composable
private fun SaleCard(vm: MainViewModel, p: ParentState, sale: Sale) {
    val cancelled = sale.status == SaleStatus.CANCELLED
    Tile(onClick = { vm.go(Screen.Sale(sale.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SaleImage(vm, p, sale, Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(saleTitle(sale), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val code = saleCode(p, sale)
                    if (code != null) Text(code, style = Mono.copy(fontSize = 13.sp), maxLines = 1, softWrap = false)
                    else Muted("Sin código", maxLines = 1)
                    SaleChip(sale)
                }
                Muted(
                    listOfNotNull(
                        DAY_FORMAT.format(date(sale.soldAt)),
                        Stats.daysToSell(sale)?.let { if (it == 1) "en 1 día" else "en $it días" },
                        if (Catalog.devices(p).size > 1) Catalog.device(p, sale.deviceId)?.label else null,
                    ).joinToString(GAP),
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Money.format(sale.price),
                    style = Mono.copy(fontSize = 17.sp, textDecoration = if (cancelled) TextDecoration.LineThrough else TextDecoration.None),
                    color = if (cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, softWrap = false,
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
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.statistics(
    p: ParentState, inPeriod: List<Sale>, summary: Stats.Summary, now: Long,
) {
    val all = Stats.kpis(p, now)
    val period = Stats.kpis(p.copy(sales = inPeriod), now)
    item(key = "numbers") {
        KpiGrid(
            listOf(
                Triple("Días en venderse", summary.medianDays?.toString() ?: "—", "lo habitual"),
                Triple("Vendido", all.sellThrough?.let { "$it %" } ?: "—", "de lo catalogado"),
                Triple("Rentabilidad", period.roi?.let { "$it %" } ?: "—", "sobre el coste"),
                Triple("Canceladas", "${summary.cancelled}", "en el periodo"),
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
