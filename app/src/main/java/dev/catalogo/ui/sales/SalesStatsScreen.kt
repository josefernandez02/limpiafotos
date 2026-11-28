package dev.catalogo.ui.sales

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.catalog.Stats
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.Money
import dev.catalogo.core.model.SaleStatus
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.common.Bar
import dev.catalogo.ui.common.BarRow
import dev.catalogo.ui.common.Choice
import dev.catalogo.ui.common.GAP
import dev.catalogo.ui.common.KpiGrid
import dev.catalogo.ui.common.MonthBars
import dev.catalogo.ui.common.Tile

/** The numbers behind the sales, for the period chosen. */
@Composable
fun SalesStatsScreen(vm: MainViewModel, p: CatalogState) {
    val view by vm.salesView.collectAsStateWithLifecycle()
    val now = remember(p) { System.currentTimeMillis() }
    val inPeriod = remember(p, view.period) { Stats.within(p.sales, view.period, now) }
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
internal val StatsIcon: ImageVector by lazy {
    ImageVector.Builder("Catalogo.Stats", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M16,6l2.29,2.29-4.88,4.88-4-4L2,16.59 3.41,18l6-6 4,4 6.3-6.29L22,12V6z"),
        fill = SolidColor(Color.Black),
    ).build()
}
