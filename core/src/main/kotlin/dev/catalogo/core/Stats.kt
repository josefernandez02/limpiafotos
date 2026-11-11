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
        val favourites: Int,
        val months: List<Month>,
        val accounts: List<Account>,
        /** Brands by units sold, best first. */
        val brands: List<Pair<String, Int>>,
    )

    fun kpis(s: ParentState, now: Long, zone: ZoneId = ZoneId.systemDefault(), months: Int = 6): Kpis {
        val stock = s.articles.filter { !it.released }
        val sales = s.sales
        val costOfSold = sales.sumOf { it.cost ?: 0 }
        val revenue = sales.sumOf { it.price }
        val profit = revenue - costOfSold
        val days = sales.mapNotNull { sale -> sale.listedAt?.let { ((sale.soldAt - it) / DAY).toInt().coerceAtLeast(0) } }.sorted()
        val thisMonth = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
        val byMonth = sales.groupBy { YearMonth.from(Instant.ofEpochMilli(it.soldAt).atZone(zone)) }
        val monthly = (months - 1 downTo 0).map { back ->
            val m = thisMonth.minusMonths(back.toLong())
            val list = byMonth[m].orEmpty()
            Month(m, list.size, list.sumOf { it.price }, list.sumOf { it.price - (it.cost ?: 0) })
        }
        val accounts = Catalog.devices(s).map { d ->
            val mine = sales.filter { it.deviceId == d.id }
            Account(d.id, d.label, mine.size, mine.sumOf { it.price }, stock.count { d.id in it.uploaded })
        }
        val brands = sales.filter { it.brand.isNotBlank() }
            .groupingBy { it.brand.lowercase().replace("’", "'") }.eachCount()
            .map { (k, n) -> (sales.first { it.brand.lowercase().replace("’", "'") == k }.brand) to n }
            .sortedByDescending { it.second }
        fun olderThan(d: Int) = stock.count { now - it.createdAt > d * DAY }
        return Kpis(
            inStock = stock.size,
            stockCost = stock.sumOf { Ledger.costOf(s, it) ?: 0 },
            notUploaded = stock.count { it.uploaded.isEmpty() },
            aged30 = olderThan(30),
            aged60 = olderThan(60),
            aged90 = olderThan(90),
            sold = sales.size,
            revenue = revenue,
            profit = profit,
            roi = if (costOfSold > 0) (profit * 100 / costOfSold).toInt() else null,
            averagePrice = if (sales.isNotEmpty()) revenue / sales.size else null,
            sellThrough = (sales.size + stock.size).takeIf { it > 0 }?.let { sales.size * 100 / it },
            medianDays = days.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] },
            favourites = stock.sumOf { a -> a.vinted.values.sumOf { it.favourites ?: 0 } },
            months = monthly,
            accounts = accounts,
            brands = brands,
        )
    }

    /** Articles in stock, oldest first, with their age in days: what to review, discount or re-upload. */
    fun oldest(s: ParentState, now: Long, limit: Int = 5): List<Pair<Article, Int>> =
        s.articles.filter { !it.released }.sortedBy { it.createdAt }.take(limit)
            .map { it to ((now - it.createdAt) / DAY).toInt() }
}
