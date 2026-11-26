package dev.catalogo.core

/** One pass over «Mis pedidos › Ventas». */
class SalesSync(private val page: Page, private val book: Book, private val now: () -> Long) {

    /** Requests made by Vinted's own page, with the user's session. Each answers (HTTP status, body). */
    interface Page {
        suspend fun get(path: String): Pair<Int, String>
        /** What an order was, in the form [VintedSales.parseDeal] reads. */
        suspend fun deal(order: String): Pair<Int, String>
        suspend fun pause()
    }

    /** The catalogue. */
    interface Book {
        fun state(): ParentState
        suspend fun change(block: (ParentState) -> ParentState)
    }

    enum class Stop { LIMIT, LOGIN, ERRORS }

    /** [left]: orders still to be looked up; [stop]: why the pass ended before them. */
    data class Report(val account: String, val read: Int, val counts: Map<VintedSales.Kind, Int>, val left: Int, val failed: Int, val stop: Stop?) {
        fun count(kind: VintedSales.Kind) = counts[kind] ?: 0
    }

    private val counts = HashMap<VintedSales.Kind, Int>()
    private var requests = 0

    suspend fun run(progress: (String) -> Unit = {}): Report {
        counts.clear()
        requests = 0
        progress("Leyendo tus ventas…")
        val account = account()
        val orders = list()

        // First everything that needs no more questions: states of known orders and old history.
        val (ask, rest) = orders.partition { VintedSales.needsLookup(book.state(), it) }
        record(account.id, rest.sortedBy { it.date }.map { it to null })

        // Then one question per order, the ones that can still change first.
        val queue = ask.sortedWith(compareBy<VintedSales.Order> { it.status.ordinal }.thenByDescending { it.date })
        var done = 0
        var failed = 0
        var inARow = 0
        var stop: Stop? = null
        for (order in queue) {
            // An earlier answer may have made this question pointless.
            if (!VintedSales.needsLookup(book.state(), order)) {
                record(account.id, listOf(order to null))
                done++
                continue
            }
            progress("Revisando venta ${done + failed + 1} de ${queue.size}…")
            page.pause()
            val (status, body) = page.deal(order.id)
            if (status == 429) stop = Stop.LIMIT
            if (status == 401 || status == 403) stop = Stop.LOGIN
            if (stop != null) break
            val ok = status == 200 && runCatching { VintedSales.parseDeal(body) }
                .map { deal -> record(account.id, listOf(order to deal)) }
                .isSuccess
            if (ok) {
                done++
                inARow = 0
            } else {
                failed++
                if (++inARow == MAX_FAILURES) {
                    stop = Stop.ERRORS
                    break
                }
            }
        }
        return Report(account.label, orders.size, counts.toMap(), queue.size - done, failed, stop)
    }

    /** Writes the orders down in one change, and counts what happened to each. */
    private suspend fun record(account: String, orders: List<Pair<VintedSales.Order, VintedSales.Deal?>>) {
        if (orders.isEmpty()) return
        val at = now()
        val kinds = ArrayList<VintedSales.Kind>()
        orders.fold(book.state()) { s, (order, deal) ->
            val (next, kind) = VintedSales.apply(s, account, order, deal, at)
            kinds += kind
            next
        }
        book.change { start -> orders.fold(start) { s, (order, deal) -> VintedSales.apply(s, account, order, deal, at).first } }
        for (kind in kinds) counts[kind] = (counts[kind] ?: 0) + 1
    }

    /** The account of the session open in the page. */
    private suspend fun account(): Device {
        val (status, body) = ask("/api/v2/users/current")
        if (status != 200) refuse(status)
        val id = VintedSales.userId(body)
        val s = book.state()
        return Catalog.devices(s).firstOrNull { it.vintedId.isNotBlank() && it.vintedId == id } ?: s.self
    }

    private suspend fun list(): List<VintedSales.Order> {
        var address: (Int) -> String = VintedSales::path
        val orders = ArrayList<VintedSales.Order>()
        var number = 1
        while (number <= VintedSales.MAX_PAGES) {
            var answer = ask(address(number))
            if (answer.first == 404 && number == 1) {
                address = VintedSales::oldPath
                answer = ask(address(number))
            }
            if (answer.first != 200) refuse(answer.first)
            val got = VintedSales.parseList(answer.second)
            orders += got.orders
            if (got.orders.isEmpty() || number >= got.pages) break
            number++
        }
        return orders.distinctBy { it.id }
    }

    private suspend fun ask(path: String): Pair<Int, String> {
        if (requests++ > 0) page.pause()
        return page.get(path)
    }

    private fun refuse(status: Int): Nothing = throw CatalogException(
        when (status) {
            401, 403 -> "Vinted pide iniciar sesión o una comprobación: hazlo arriba y vuelve a pulsar"
            429 -> "Vinted pide esperar: vuelve a pulsar en unos minutos"
            0 -> "Sin conexión con Vinted"
            else -> "Vinted ha respondido $status"
        },
    )

    private companion object {
        const val MAX_FAILURES = 3
    }
}
