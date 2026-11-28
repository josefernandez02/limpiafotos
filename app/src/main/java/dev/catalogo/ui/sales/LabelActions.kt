package dev.catalogo.ui.sales

import android.app.Application
import android.net.Uri
import dev.catalogo.core.catalog.CatalogException
import dev.catalogo.core.model.PcLink
import dev.catalogo.core.sales.LabelJob
import dev.catalogo.core.sales.ShippingLabels
import dev.catalogo.data.AppLog
import dev.catalogo.data.pc.PcClient
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.sales.openInPrinter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Asks the PC for the labels of every sale still to be sent, or of the order [only]. */
fun MainViewModel.printLabels(only: String? = null) = run {
    val link = repo.state.pc ?: throw CatalogException("Configura el PC en Ajustes")
    val p = repo.state.catalog ?: throw CatalogException("No hay catálogo")
    val requests = ShippingLabels.requests(p, only)
    if (requests.isEmpty()) throw CatalogException("No hay ventas pendientes de enviar")
    labelsLock.withLock {
        val reply = ShippingLabels.parse(withContext(Dispatchers.IO) { repo.pcClient.labelsControl(link, "mandar", requests) })
        _labelsText.value = "Pedidas al PC…"
        repo.setLabels(LabelJob(lot = reply.lot))
        takeLabels(link, reply)
    }
}

fun MainViewModel.stopLabels() = run {
    labelsLock.withLock {
        // Forgotten here even if the PC does not hear it: the user can always ask again.
        repo.setLabels(LabelJob())
        val link = repo.state.pc ?: return@withLock
        try {
            withContext(Dispatchers.IO) { repo.pcClient.labelsControl(link, "parar") }
        } catch (e: CatalogException) {
            if (e.message != PcClient.UNREACHABLE) throw e
            _message.value = "Sin conexión: el PC no sabe que has parado"
        }
    }
}

/** Puts away the finished PDF or the warnings; the file stays in Descargas. */
fun MainViewModel.dismissLabels() = run { repo.setLabels(LabelJob()) }

/** Asks the PC how the pending labels go, and brings the PDF when they are done. */
suspend fun MainViewModel.refreshLabels() {
    val link = repo.state.pc ?: return
    if (repo.state.labels.lot == 0L) return
    try {
        labelsLock.withLock {
            if (repo.state.labels.lot == 0L) return@withLock
            takeLabels(link, ShippingLabels.parse(withContext(Dispatchers.IO) { repo.pcClient.labels(link) }))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: CatalogException) {
        _labelsText.value = if (e.message == PcClient.UNREACHABLE) "PC sin conexión: las recojo cuando vuelva" else e.message.orEmpty()
    } catch (e: Exception) {
        AppLog.w("etiquetas", "error: ${e.message ?: e.javaClass.simpleName}")
        _labelsText.value = "No se pudo guardar el PDF (${e.message ?: e.javaClass.simpleName})"
    }
}

/** Only inside [labelsLock]. */
private suspend fun MainViewModel.takeLabels(link: PcLink, reply: ShippingLabels.Reply) {
    val lot = repo.state.labels.lot
    when (val step = ShippingLabels.step(lot, reply)) {
        is ShippingLabels.Step.Wait -> _labelsText.value = step.text
        is ShippingLabels.Step.Fetch -> {
            _labelsText.value = "Trayendo el PDF…"
            val file = withContext(Dispatchers.IO) {
                val pdf = ShippingLabels.parsePdf(repo.pcClient.labelsPdf(link))
                // Another lot took its place meanwhile: the next question tells.
                if (pdf.lot != lot) null else repo.labelFiles.save(pdf.name, pdf.bytes)
            } ?: return
            repo.keepLabels(LabelJob(file = file.toString(), count = step.done.labels, warnings = step.done.warnings), reply.ready)
            AppLog.i("etiquetas", "PDF con ${step.done.labels}, avisos ${step.done.warnings.size}")
            _message.value = "PDF de etiquetas guardado en Descargas"
        }
        is ShippingLabels.Step.Failed -> {
            repo.setLabels(LabelJob(warnings = step.warnings.ifEmpty { listOf("El PC no ha podido sacar ninguna etiqueta") }))
            _message.value = "No ha salido ninguna etiqueta"
        }
        ShippingLabels.Step.Dropped -> {
            repo.setLabels(LabelJob())
            _message.value = "El PC ha parado las etiquetas"
        }
    }
}

/** Opens the PDF in the label printer's app. */
fun MainViewModel.openLabels() = run {
    val file = repo.state.labels.file
    if (file.isBlank()) return@run
    openInPrinter(getApplication<Application>(), Uri.parse(file))?.let { _message.value = it }
}
