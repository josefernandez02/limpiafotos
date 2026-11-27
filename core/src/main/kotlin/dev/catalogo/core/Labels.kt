package dev.catalogo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What is left of a request for labels, so it survives closing the app: the lot the PC is
 * still working on ([lot] 0 when none), or the PDF it ended in ([file] is its address on this
 * phone, [count] its labels) and what the user must know about it.
 */
@Serializable
data class LabelJob(val lot: Long = 0, val file: String = "", val count: Int = 0, val warnings: List<String> = emptyList())

/** A label PDF saved on this phone and the orders whose labels are inside. */
@Serializable
data class LabelFile(val file: String, val orders: List<String>)

/**
 * Shipping labels. The PC takes them out of Vinted, crops them upright and joins them into
 * one PDF; the phone only says which sales, takes the PDF and hands it to the printer app.
 */
object Labels {
    /** One sale to print. [code] is written on its label, so it is known which parcel it goes on. */
    data class Request(val id: String, val code: String, val title: String)

    /** The lot the PC finished: its file ("" when no label came out) and what the user must know. */
    data class Done(val lot: Long, val file: String, val labels: Int, val warnings: List<String>)

    /**
     * What the PC says about the lot it was last asked for. [left] labels still to be taken
     * out; [ready], the orders whose label is already out.
     */
    data class Reply(
        val working: Boolean, val waiting: String, val lot: Long, val left: Int, val done: Done?,
        val ready: List<String> = emptyList(),
    )

    class Pdf(val lot: Long, val name: String, val bytes: ByteArray)

    sealed interface Step {
        data class Wait(val text: String) : Step
        data class Fetch(val done: Done) : Step
        data class Failed(val warnings: List<String>) : Step

        /** The lot was stopped, or another one took its place. */
        data object Dropped : Step
    }

    private const val PRINTER = "com.xinye.barcode4"

    /** The PC's limit for the code written on a label. */
    private const val CODE_LENGTH = 12

    /** What Vinted says once the parcel is no longer in the seller's hands. */
    private val GONE = listOf("enviado", "de camino", "en camino", "recoger", "recogida", "entregado", "recibido", "devoluc", "finalizado", "cancelad")

    private val json = Json { ignoreUnknownKeys = true }

    /** A Vinted order still to be sent. */
    fun canPrint(sale: Sale): Boolean {
        if (sale.order.isBlank() || sale.status != SaleStatus.PENDING || sale.conflict) return false
        val detail = sale.detail.lowercase()
        // «Se ha enviado la etiqueta» talks about the label, not about the parcel.
        return "etiqueta" in detail || GONE.none { it in detail }
    }

    /** What to ask the PC for, the oldest sale first: all of them, or the order [only]. */
    fun requests(s: ParentState, only: String? = null): List<Request> = s.sales
        .filter { canPrint(it) && (only == null || it.order == only) }
        .sortedBy { it.soldAt }
        .groupBy { it.order }
        .map { (order, garments) -> Request(order, lotCode(garments.map { code(s, it) }), garments.first().title) }

    private fun code(s: ParentState, sale: Sale): String = sale.code.ifBlank {
        if (sale.number < 0) "" else Sku.format(Catalog.device(s, sale.deviceId)?.prefix ?: s.self.prefix, sale.number, s.digits)
    }

    /** One code for a parcel: «SH0006», «SH0006-0012» for two garments, «SH0006#3» for more. */
    private fun lotCode(codes: List<String>): String {
        val known = codes.filter { it.isNotBlank() }
        val first = known.firstOrNull() ?: return ""
        if (codes.size == 1) return first
        if (codes.size == 2 && known.size == 2) {
            val prefix = first.trimEnd { it.isDigit() }
            val second = known[1].removePrefix(prefix)
            val both = "$first-$second"
            if (second.all { it.isDigit() } && both.length <= CODE_LENGTH) return both
        }
        return "$first#${codes.size}"
    }

    /** The answer of the PC to `/etiquetas`. */
    fun parse(body: String): Reply {
        val root = objectOf(body)
        val list = root?.get("pedidos") as? JsonArray
        if (root == null || list == null || root.str("modo") == null) {
            throw CatalogException("El PC ha respondido algo inesperado: actualiza su programa")
        }
        val done = (root["hecho"] as? JsonObject)?.let { d ->
            Done(
                d.str("lote")?.toLongOrNull() ?: 0, d.str("archivo").orEmpty(), d.str("etiquetas")?.toIntOrNull() ?: 0,
                (d["avisos"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
        }
        val asked = list.mapNotNull { it as? JsonObject }
        return Reply(
            root.str("modo") == "trabajando", root.str("esperando").orEmpty(), root.str("lote")?.toLongOrNull() ?: 0,
            asked.count { it.str("estado") == "pendiente" }, done,
            asked.filter { it.str("estado") == "lista" }.mapNotNull { it.str("id") },
        )
    }

    /** The PDF [job] ended in, saved with the labels of [orders]. */
    fun saved(state: AppState, job: LabelJob, orders: List<String>): AppState =
        state.copy(labels = job, labelPdfs = state.labelPdfs + LabelFile(job.file, orders))

    /**
     * The saved PDFs with nothing left to send: one of their sales paid and none still
     * open. A sale cancelled by hand, in doubt or unknown here keeps its PDF.
     */
    fun spent(s: ParentState, files: List<LabelFile>): List<LabelFile> {
        val sales = s.sales.groupBy { it.order }
        return files.filter { pdf ->
            val inside = pdf.orders.map { sales[it].orEmpty() }
            inside.all { order -> order.isNotEmpty() && order.all(::over) } &&
                inside.any { order -> order.any { it.status == SaleStatus.COMPLETED } }
        }
    }

    private fun over(sale: Sale): Boolean =
        !sale.conflict && (sale.status == SaleStatus.COMPLETED || sale.status == SaleStatus.CANCELLED && !sale.byHand)

    /** Forgets the PDFs [gone], and puts away the card that was offering one of them. */
    fun discard(state: AppState, gone: List<LabelFile>): AppState {
        val files = gone.map { it.file }.toSet()
        return state.copy(
            labels = if (state.labels.file in files) LabelJob() else state.labels,
            labelPdfs = state.labelPdfs.filter { it.file !in files },
        )
    }

    /** What to do about the lot [lot] with what the PC has just answered. */
    fun step(lot: Long, reply: Reply): Step {
        if (reply.lot != lot) return Step.Dropped
        val done = reply.done?.takeIf { it.lot == lot }
        return when {
            done != null -> if (done.file.isBlank()) Step.Failed(done.warnings) else Step.Fetch(done)
            !reply.working -> Step.Dropped
            reply.waiting.isNotBlank() -> Step.Wait("PC: ${reply.waiting}")
            reply.left == 1 -> Step.Wait("Sacando etiquetas en el PC · queda 1")
            reply.left > 1 -> Step.Wait("Sacando etiquetas en el PC · quedan ${reply.left}")
            else -> Step.Wait("Sacando etiquetas en el PC…")
        }
    }

    /** The answer of the PC to `/etiquetas/pdf`. */
    fun parsePdf(body: String): Pdf {
        val root = objectOf(body)
        val bytes = root?.str("datos")?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        if (bytes == null || !String(bytes.copyOf(minOf(5, bytes.size)), Charsets.US_ASCII).startsWith("%PDF-")) {
            throw CatalogException("El PDF de las etiquetas ha llegado mal: vuelve a pedirlas")
        }
        val name = root.str("nombre").orEmpty().substringAfterLast('/').substringAfterLast('\\').trim().trimStart('.')
            .ifBlank { "etiquetas" }
        return Pdf(root.str("lote")?.toLongOrNull() ?: 0, if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf", bytes)
    }

    /**
     * Which of the apps that open PDFs (package to name) is the one of the label printer,
     * 4BarCode; -1 if none is.
     */
    fun printer(apps: List<Pair<String, String>>): Int {
        val exact = apps.indexOfFirst { it.first == PRINTER }
        if (exact >= 0) return exact
        return apps.indexOfFirst { (pack, name) -> listOf(pack, name).any { "4barcode" in it.lowercase() || "xinye" in it.lowercase() } }
    }

    private fun objectOf(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
