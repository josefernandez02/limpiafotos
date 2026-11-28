package dev.catalogo.ui.pc

import android.net.Uri
import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.catalog.CatalogException
import dev.catalogo.core.model.Mode
import dev.catalogo.core.model.PcLink
import dev.catalogo.core.model.Sku
import dev.catalogo.core.model.Stage
import dev.catalogo.core.pc.PcPayload
import dev.catalogo.data.pc.fix
import dev.catalogo.data.pc.playOnly
import dev.catalogo.data.pc.prepareNow
import dev.catalogo.data.pc.remove
import dev.catalogo.data.pc.retry
import dev.catalogo.data.pc.setMode
import dev.catalogo.data.pc.setStage
import dev.catalogo.data.pc.setStageMany
import dev.catalogo.ui.MainViewModel

fun MainViewModel.incidentReviewed(id: String) = run { app.pcSync.reviewed(id) }

/** «Generar y preparar borrador»: AI, checks and sending happen in the background. */
/** «Generar y preparar borrador» with the photos the AI should look at (the PC gets all of them). */
fun MainViewModel.prepareDraft(number: Int, photos: List<Uri>, mode: String = Mode.UPLOAD_PLAY) = run {
    if (repo.state.pc == null) throw CatalogException("Configura el PC en Ajustes")
    if (photos.isEmpty()) throw CatalogException("Elige al menos una foto")
    repo.setPhotos(number, photos.map { it.toString() })
    app.pcSync.prepareNow(number, mode)
    _message.value = "En cola: puedes salir de la app y seguir con otros artículos"
}

/** Photos of [number]'s album, and which ones the AI looks at. */
suspend fun MainViewModel.vintedPhotos(number: Int): Pair<List<Uri>, List<Uri>> {
    val p = repo.state.catalog ?: return emptyList<Uri>() to emptyList()
    val album = albumPhotos(Sku.format(p.mainAccount.prefix, number, p.digits))
    val chosen = Catalog.chosenPhotos(album, Catalog.article(p, number)?.photos.orEmpty()) { it.toString() }
    return album to chosen
}

fun MainViewModel.fixDraft(number: Int, fixes: PcPayload.Fixes) = run {
    app.pcSync.fix(number, fixes)
    _message.value = "Revisado: sigue su camino al PC"
}

fun MainViewModel.retryDraft(number: Int) = run { app.pcSync.retry(number) }

/** Where it stands, set by hand (also tells the PC). */
fun MainViewModel.setStage(number: Int, stage: Stage, url: String = "") = run {
    app.pcSync.setStage(number, stage, url)
    _message.value = "Estado: ${stage.label}"
}

/** The same status for several articles; those the PC cannot change now are reported. */
fun MainViewModel.setStageMany(numbers: List<Int>, stage: Stage) = run {
    val failed = app.pcSync.setStageMany(numbers, stage)
    _message.value = if (failed.isEmpty()) "${numbers.size} en «${stage.label}»"
    else "${numbers.size - failed.size} cambiados; ${failed.size} no (¿PC sin conexión?)"
}

fun MainViewModel.setMode(number: Int, mode: String) = run {
    app.pcSync.setMode(number, mode)
    _message.value = Mode.label(mode)
}

/** «Solo jugar»: an article already on Vinted goes to the next game with its link. */
fun MainViewModel.playOnly(number: Int, url: String) = run {
    if (!Regex("^https://(www\\.)?vinted\\.[a-z.]{2,8}/items/\\d+").containsMatchIn(url.trim())) {
        throw CatalogException("Pega el link del artículo en Vinted")
    }
    app.pcSync.playOnly(number, url)
    _message.value = "Irá al siguiente juego"
}

/** Ask the PC now («Actualizar» in Estado). */
fun MainViewModel.refreshPc() {
    app.pcSync.refresh()
}

/** Out of the queue (and off the PC) until the user sends it again. */
fun MainViewModel.removeDraft(number: Int) = run {
    app.pcSync.remove(number)
    _message.value = "Fuera de la cola"
}

/** «iniciar», «pausar», «reanudar», «detener» or «seguir» (go on now) on the PC program. */
fun MainViewModel.pcControl(action: String) = run {
    val s = app.pcSync.control(action)
    _message.value = when {
        action == "detener" -> "PC detenido"
        action == "seguir" -> "Sigo ya"
        s.paused -> "PC en pausa"
        else -> "PC ${s.text}"
    }
}

/** Switch of one game (the PC never plays one that is off). */
fun MainViewModel.setGame(kind: String, on: Boolean) = run { app.pcSync.setGame(kind, on) }

/** «mandar» or «parar» for the automatic offers. */
fun MainViewModel.offersControl(action: String) = run {
    app.pcSync.offersControl(action)
    _message.value = if (action == "mandar") "Ofertas: en cuanto haya hueco empiezo" else "Ofertas paradas"
}

/** Checks the PC answers with this key before saving it. */
fun MainViewModel.savePc(url: String, token: String, done: () -> Unit) = run {
    val clean = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
    val link = PcLink(clean, token.trim())
    val s = app.pcSync.test(link)
    repo.setPc(link)
    _message.value = "PC conectado: ${s.text}"
    app.pcSync.kick()
    done()
}

fun MainViewModel.forgetPc() = run { repo.setPc(null) }
