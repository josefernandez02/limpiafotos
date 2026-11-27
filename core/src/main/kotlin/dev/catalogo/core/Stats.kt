package dev.catalogo.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Inventory numbers that help decide: what is in stock and what it cost, what
 * sold, for how much and how fast, and what is getting old. Pure functions.
 */
object Stats {
    private const val DAY = 86_400_000L

    data class Month(val month: YearMonth, val sold: Int, val revenue: Long, val profit: Long)

    data class Account(val deviceId: String, val label: String, val sold: Int, val revenue: Long, val listed: Int)

    data class Kpis(
        val inStock: Int,
        /** Cost of what is in stock (known costs only). */
        val stockCost: Long,
        val notUploaded: Int,
        /** In stock for more than 30, 60 and 90 days. */
        val aged30: Int,
        val aged60: Int,
        val aged90: Int,
        val sold: Int,
        val revenue: Long,
        val profit: Long,
        /** Profit over the cost of what sold, in percent; null without costs. */
        val roi: Int?,
        val averagePrice: Long?,
        /** Sold over sold plus in stock, in percent; null with nothing. */
        val sellThrough: Int?,
        /** Median days from creation to sale; null without data. */
        val medianDays: Int?,
        val months: List<Month>,
        val accounts: List<Account>,
        /** Brands by units sold, best first. */
        val brands: List<Pair<String, Int>>,
        /** Sales in progress and their money, not counted anywhere else. */
        val pending: Int = 0,
        val pendingRevenue: Long = 0,
        /** Finished sales with no cost: left out of the profit. */
        val unknownCost: Int = 0,
        /** What the stock adds up to at its listed prices. */
        val stockValue: Long = 0,
        val stockProfit: Long = 0,
    )

    enum class Period(val label: String) { MONTH("Este mes"), LAST_30("30 días"), YEAR("Año"), ALL("Todo") }

    /** [sales] made in [period], counting from [now]. */
    fun within(sales: List<Sale>, period: Period, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Sale> {
        val today = Instant.ofEpochMilli(now).atZone(zone)
        return sales.filter { sale ->
            val day = Instant.ofEpochMilli(sale.soldAt).atZone(zone)
            when (period) {
                Period.MONTH -> YearMonth.from(day) == YearMonth.from(today)
                Period.LAST_30 -> now - sale.soldAt <= 30 * DAY
                Period.YEAR -> day.year == today.year
                Period.ALL -> true
            }
        }
    }

    /** Days between the article being catalogued and its sale; null for sales without article. */
    fun daysToSell(sale: Sale): Int? = sale.listedAt?.let { ((sale.soldAt - it) / DAY).toInt().coerceAtLeast(0) }

    /** What a group of sales adds up to. Money, profit, price and speed only count finished ones. */
    data class Summary(
        val sold: Int,
        val revenue: Long,
        val profit: Long,
        val unknownCost: Int,
        val pending: Int,
        val pendingRevenue: Long,
        val cancelled: Int,
        val averagePrice: Long?,
        val medianDays: Int?,
        val pendingProfit: Long = 0,
    )

    fun summary(sales: List<Sale>): Summary {
        val done = sales.filter { it.status == SaleStatus.COMPLETED }
        val pending = sales.filter { it.status == SaleStatus.PENDING }
        val totals = Ledger.totals(done)
        val days = done.mapNotNull(::daysToSell).sorted()
        return Summary(
            sold = done.size,
            revenue = totals.revenue,
            profit = totals.profit,
            unknownCost = done.count { it.cost == null },
            pending = pending.size,
            pendingRevenue = pending.sumOf { it.price },
            cancelled = sales.count { it.status == SaleStatus.CANCELLED },
            averagePrice = if (done.isEmpty()) null else totals.revenue / done.size,
            medianDays = days.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] },
            pendingProfit = pending.sumOf { v -> v.cost?.let { v.price - it } ?: 0 },
        )
    }

    fun kpis(s: ParentState, now: Long, zone: ZoneId = ZoneId.systemDefault(), months: Int = 6): Kpis {
        val stock = s.articles.filter { it.inStock }
        val sales = s.sales.filter { it.status == SaleStatus.COMPLETED }
        val pending = s.sales.filter { it.status == SaleStatus.PENDING }
        val totals = Ledger.totals(sales)
        val costOfSold = totals.cost
        val revenue = totals.revenue
        val profit = totals.profit
        val days = sales.mapNotNull(::daysToSell).sorted()
        val thisMonth = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
        val byMonth = sales.groupBy { YearMonth.from(Instant.ofEpochMilli(it.soldAt).atZone(zone)) }
        val monthly = (months - 1 downTo 0).map { back ->
            val m = thisMonth.minusMonths(back.toLong())
            val list = byMonth[m].orEmpty()
            Month(m, list.size, list.sumOf { it.price }, Ledger.totals(list).profit)
        }
        val accounts = Catalog.devices(s).map { d ->
            val mine = sales.filter { it.deviceId == d.id }
            Account(d.id, d.label, mine.size, mine.sumOf { it.price }, stock.count { d.id in it.targets })
        }
        val brands = sales.filter { it.brand.isNotBlank() }
            .groupingBy { it.brand.lowercase().replace("’", "'") }.eachCount()
            .map { (k, n) -> (sales.first { it.brand.lowercase().replace("’", "'") == k }.brand) to n }
            .sortedByDescending { it.second }
        fun olderThan(d: Int) = stock.count { now - it.createdAt > d * DAY }
        return Kpis(
            inStock = stock.size,
            stockCost = stock.sumOf { Ledger.costOf(s, it) ?: 0 },
            notUploaded = stock.count { it.toUpload },
            aged30 = olderThan(30),
            aged60 = olderThan(60),
            aged90 = olderThan(90),
            sold = sales.size,
            revenue = revenue,
            profit = profit,
            roi = if (costOfSold > 0) (profit * 100 / costOfSold).toInt() else null,
            averagePrice = if (sales.isNotEmpty()) revenue / sales.size else null,
            // Only articles catalogued here: older history has no stock to compare with.
            sellThrough = sales.count { it.listedAt != null }.let { sold -> (sold + stock.size).takeIf { it > 0 }?.let { sold * 100 / it } },
            medianDays = days.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] },
            months = monthly,
            accounts = accounts,
            brands = brands,
            pending = pending.size,
            pendingRevenue = pending.sumOf { it.price },
            unknownCost = sales.count { it.cost == null },
            stockValue = stock.sumOf { it.price ?: 0 },
            // What would be earned selling at the listed prices what has a price and a known cost.
            stockProfit = stock.sumOf { a -> a.price?.let { p -> Ledger.costOf(s, a)?.let { c -> p - c } } ?: 0 },
        )
    }

    /** Articles in stock, oldest first, with their age in days: what to review, discount or re-upload. */
    fun oldest(s: ParentState, now: Long, limit: Int = 5): List<Pair<Article, Int>> =
        s.articles.filter { it.inStock }.sortedBy { it.createdAt }.take(limit)
            .map { it to ((now - it.createdAt) / DAY).toInt() }
}
