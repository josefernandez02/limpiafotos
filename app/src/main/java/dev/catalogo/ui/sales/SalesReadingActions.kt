package dev.catalogo.ui.sales

import androidx.lifecycle.viewModelScope
import dev.catalogo.core.catalog.CatalogException
import dev.catalogo.core.model.PcLink
import dev.catalogo.core.sales.Sales
import dev.catalogo.core.sales.VintedSales
import dev.catalogo.core.sales.VintedSales.Kind
import dev.catalogo.data.AppLog
import dev.catalogo.data.pc.PcClient
import dev.catalogo.data.pc.tellPc
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.MainViewModel.SalesReading
import dev.catalogo.ui.Screen
import dev.catalogo.ui.common.GAP
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The app has come to the screen: the PC is to read again if its last reading is old. */
fun MainViewModel.salesOpened() {
    if (pendingAsk == null) pendingAsk = "refrescar"
}

/** Asks the PC to read the sales in Vinted now, or to stop. Without a connection it is told later. */
fun MainViewModel.readSales(start: Boolean) = run {
    if (repo.state.pc == null) throw CatalogException("Configura el PC en Ajustes")
    val action = if (start) "mandar" else "parar"
    pendingAsk = action
    refreshSales()
    if (pendingAsk == action) _message.value = "Sin conexión con el PC: se lo pediré en cuanto vuelva"
}

/**
 * How often to ask the PC for the sales on the screen shown now; null while photos are
 * being taken or something is being edited, so that nothing changes under the user.
 */
fun MainViewModel.salesEvery(): Long? {
    val top = stack.value.last()
    return when {
        repo.state.catalog?.session != null || top is Screen.Editor || top is Screen.Photos || top is Screen.Template -> null
        reading.value?.working == true -> 8_000
        top == Screen.Sales || top is Screen.Sale || pendingAsk != null -> 30_000
        else -> 5 * 60_000
    }
}

/** Tells the PC what is pending and takes what it has read so far. */
suspend fun MainViewModel.refreshSales() {
    val link = repo.state.pc ?: return
    if (repo.state.catalog == null) return
    val ask = pendingAsk
    try {
        val read = if (ask != null) tellSales(link, ask)
        else VintedSales.parsePc(withContext(Dispatchers.IO) { repo.pcClient.sales(link) })
        if (pendingAsk == ask) pendingAsk = null
        takeSales(read)
    } catch (e: CancellationException) {
        throw e
    } catch (e: CatalogException) {
        val offline = e.message == PcClient.UNREACHABLE
        // An answer, even a bad one, means it heard: asking again would not change it.
        if (!offline && pendingAsk == ask) pendingAsk = null
        _reading.value = SalesReading(false, if (offline) offlineText() else e.message.orEmpty())
    } catch (e: Exception) {
        AppLog.w("ventas", "error: ${e.message ?: e.javaClass.simpleName}")
        _reading.value = SalesReading(false, "No se pudieron apuntar las ventas (${e.message ?: e.javaClass.simpleName})")
    }
}

private suspend fun MainViewModel.tellSales(link: PcLink, action: String): VintedSales.Reading {
    val p = repo.state.catalog ?: throw CatalogException("No hay catálogo")
    return VintedSales.parsePc(withContext(Dispatchers.IO) {
        repo.pcClient.salesControl(link, action, VintedSales.prefixes(p))
    })
}

private suspend fun MainViewModel.takeSales(read: VintedSales.Reading) = readingLock.withLock {
    val before = repo.state.catalog ?: return@withLock
    if (read.lastRead > before.salesReadAt) repo.salesRead(read.lastRead)
    _reading.value = readingText(read)
    val current = repo.state.catalog ?: return@withLock
    val now = System.currentTimeMillis()
    val (after, counts) = VintedSales.applyAll(current, current.mainAccount.id, read.orders, now)
    if (after == current) return@withLock
    // Never cut between deleting photos and saving the state.
    val originals = withContext(NonCancellable) {
        repo.changeSales { VintedSales.applyAll(it, it.mainAccount.id, read.orders, now).first }
    }
    AppLog.i("ventas", "del PC ${read.orders.size}, cambios $counts, quedan ${read.pending}")
    summary(counts)?.let { _message.value = it }
    askToDelete(originals)
    app.pcSync.kick()
    refresh()
    viewModelScope.launch { app.pcSync.tellPc() }
}

private fun MainViewModel.updated(): String =
    repo.state.catalog?.salesReadAt?.takeIf { it > 0 }?.let { "Actualizado ${shortTime(it)}" } ?: "Aún sin leer"

private fun MainViewModel.offlineText(): String = "${updated()}$GAP· PC sin conexión"

private fun MainViewModel.readingText(read: VintedSales.Reading): SalesReading = SalesReading(
    read.working,
    when {
        !read.working -> updated()
        read.waiting.isNotBlank() -> "PC: ${read.waiting}"
        read.pending > 0 -> "Leyendo en el PC$GAP· quedan ${read.pending}"
        else -> "Leyendo en el PC…"
    },
)

/** «hoy 22:10», or «26/11 22:10». */
private fun MainViewModel.shortTime(at: Long): String {
    val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    return if (t.toLocalDate() == LocalDate.now()) "hoy ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    else t.format(DateTimeFormatter.ofPattern("d/M HH:mm"))
}

private fun MainViewModel.summary(counts: Map<Kind, Int>): String? {
    fun n(kind: Kind, one: String, many: String) = counts[kind]?.let { "$it ${if (it == 1) one else many}" }
    val changes = listOfNotNull(
        n(Kind.NEW_PENDING, "venta nueva en curso", "ventas nuevas en curso"),
        n(Kind.NEW_COMPLETED, "venta completada nueva", "ventas completadas nuevas"),
        n(Kind.COMPLETED, "completada", "completadas"),
        n(Kind.UNPAID, "vuelve a pendiente de cobro", "vuelven a pendiente de cobro"),
        n(Kind.CANCELLED, "anulada por Vinted", "anuladas por Vinted"),
        n(Kind.REOPENED, "recuperada", "recuperadas"),
        n(Kind.CONFLICT, "para que la revises", "para que las revises"),
    )
    return if (changes.isEmpty()) null else "Ventas: ${changes.joinToString(", ")}"
}
