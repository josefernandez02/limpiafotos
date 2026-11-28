package dev.catalogo.ui.sales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.catalog.Stats
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.Money
import dev.catalogo.core.model.Sale
import dev.catalogo.core.model.SaleStatus
import dev.catalogo.core.sales.Sales
import dev.catalogo.ui.MainTabs
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.Screen
import dev.catalogo.ui.common.BigButton
import dev.catalogo.ui.common.Choice
import dev.catalogo.ui.common.Header
import dev.catalogo.ui.common.KpiGrid
import dev.catalogo.ui.common.Muted
import dev.catalogo.ui.common.Section
import java.time.YearMonth

/** What the sales tab is showing; kept while a sale is open. */
data class SalesView(val period: Stats.Period = Stats.Period.ALL, val status: SaleStatus? = null)

/** «Ventas»: where the money is, what needs the user, and every sale. The numbers behind are in their own screen. */
@Composable
fun SalesScreen(vm: MainViewModel, p: CatalogState) {
    val view by vm.salesView.collectAsStateWithLifecycle()
    val reading by vm.reading.collectAsStateWithLifecycle()
    val working = reading?.working == true
    val sales = p.sales
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
            item(key = "labels") { LabelsCard(vm, p) }
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

internal fun count(n: Int): String = if (n == 1) "1 venta" else "$n ventas"

/** What a profit figure leaves out. */
internal fun costNote(sales: List<Sale>, status: SaleStatus): String {
    val unknown = sales.count { it.status == status && it.cost == null }
    return if (unknown == 0) "descontado el coste" else "$unknown sin coste, fuera"
}
