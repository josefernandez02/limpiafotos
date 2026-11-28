package dev.catalogo.data.pc

import dev.catalogo.core.model.Mode
import dev.catalogo.core.model.PcTask
import dev.catalogo.core.model.Stage
import dev.catalogo.core.pc.PcPayload
import dev.catalogo.data.AppLog
import dev.catalogo.data.pc.PcSync.Companion.LOCAL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Sets where the article stands in the main account, as the user knows it
 * (done by hand, or the system got it wrong). The PC hears about it so it never
 * likes a link twice or publishes a draft that must stay a draft. If the PC
 * cannot be reached, the change stays here and is sent on the next check.
 */
suspend fun PcSync.setStage(number: Int, stage: Stage, url: String = "", notify: Boolean = true) {
    val p = repo.state.catalog ?: return
    val a = current(number)?.takeIf { it.inStock } ?: return
    val ref = a.pcTask?.ref?.takeIf { it.isNotBlank() } ?: PcPayload.ref(p.mainAccount, a, p.digits)
    val link = repo.state.pc
    val cleanUrl = url.trim().ifBlank { a.pcTask?.url.orEmpty() }
    if (stage == Stage.TO_UPLOAD) {
        // Off the PC first: if it cannot hear it, nothing changes here (it could still play it).
        val d = a.pcTask
        if (link != null && d != null && d.status !in LOCAL) {
            withContext(Dispatchers.IO) {
                if (d.status in setOf("pendiente", "revisar", "error")) repo.pcClient.delete(link, d.ref)
                else repo.pcClient.setState(link, d.ref, "fuera", d.mode, d.url, p.mainAccount.vintedName)
            }
        }
        repo.updatePcTask(number) { null }
        repo.setUploaded(number, p.mainAccount.id, false)
        AppLog.i("cola", "$ref a mano: por subir")
        return
    }
    val status = when (stage) {
        Stage.DRAFT -> "borrador"
        Stage.PLAYED -> "jugado"
        else -> "publicado"
    }
    repo.updatePcTask(number) { d ->
        val base = d ?: PcTask(ref, status, at = System.currentTimeMillis())
        // Marked «Subido» by hand: it does not go to a game unless asked («Meter en los juegos»).
        // An article the PC published and is waiting for its game keeps its place.
        // Marked «Borrador» by hand: it stays a draft until «Subir y jugar» is pressed.
        val mode = when {
            stage == Stage.DRAFT && d?.status != "borrador" -> Mode.DRAFT
            stage == Stage.UPLOADED && d?.status != "publicado" -> Mode.DRAFT
            else -> base.mode
        }
        base.copy(ref = ref, status = status, message = "", doubts = emptyList(), url = cleanUrl,
            pending = status, mode = mode, at = System.currentTimeMillis())
    }
    repo.setUploaded(number, p.mainAccount.id, stage != Stage.DRAFT)
    AppLog.i("cola", "$ref a mano: $status")
    if (notify) push(number)
}

/**
 * The same status for several articles. Everything changes here at once; the PC is told
 * in parallel afterwards (if it cannot hear it now, the next check sends it). Returns the
 * articles that could not change («Por subir» needs the PC to drop them first).
 */
suspend fun PcSync.setStageMany(numbers: List<Int>, stage: Stage): List<Int> = coroutineScope {
    val failed = java.util.Collections.synchronizedList(mutableListOf<Int>())
    if (stage == Stage.TO_UPLOAD) {
        numbers.map { n -> async { runCatching { setStage(n, stage) }.onFailure { failed += n } } }.awaitAll()
    } else {
        for (n in numbers) runCatching { setStage(n, stage, notify = false) }.onFailure { failed += n }
        numbers.filter { it !in failed }.map { n -> async { push(n) } }.awaitAll()
    }
    failed.toList()
}

/** What to do with it (only before the PC acts, or for a draft / an uploaded article). */
suspend fun PcSync.setMode(number: Int, mode: String) {
    val d = current(number)?.takeIf { it.inStock }?.pcTask ?: return
    repo.updatePcTask(number) { x -> x?.copy(mode = mode, pending = if (x.status in setOf("borrador", "publicado")) x.status else x.pending) }
    AppLog.i("cola", "${d.ref} modo: $mode")
    push(number)
}

/** «Solo jugar»: the article is already on Vinted; its link goes to the next game. */
suspend fun PcSync.playOnly(number: Int, url: String) {
    val p = repo.state.catalog ?: return
    val a = current(number)?.takeIf { it.inStock } ?: return
    val ref = a.pcTask?.ref?.takeIf { it.isNotBlank() } ?: PcPayload.ref(p.mainAccount, a, p.digits)
    repo.updatePcTask(number) { d ->
        (d ?: PcTask(ref, "publicado")).copy(ref = ref, status = "publicado", mode = Mode.PLAY,
            url = url.trim(), message = "", doubts = emptyList(), pending = "publicado", at = System.currentTimeMillis())
    }
    repo.setUploaded(number, p.mainAccount.id, true)
    push(number)
}

/**
 * Everything the PC still has to hear: states set by hand, articles held by a sale
 * («fuera») or back from one, and the sold ones that are already gone. What cannot be
 * sent now stays pending for the next check.
 */
suspend fun PcSync.tellPc() {
    val link = repo.state.pc ?: return
    val p = repo.state.catalog ?: return
    for (a in p.articles.filter { it.pcTask?.pending?.isNotBlank() == true }) push(a.number)
    for (d in p.retired) {
        runCatching {
            withContext(Dispatchers.IO) { repo.pcClient.setState(link, d.ref, d.pending, d.mode, d.url, p.mainAccount.vintedName) }
        }.onSuccess {
            repo.heard(d.ref)
            AppLog.i("cola", "${d.ref} PC enterado: fuera")
        }.onFailure { AppLog.w("cola", "${d.ref} pendiente de avisar al PC: ${it.message}") }
    }
}

/** Sends a state set by hand to the PC; on failure it stays pending for the next check. */
private suspend fun PcSync.push(number: Int) {
    val p = repo.state.catalog ?: return
    val d = current(number)?.pcTask?.takeIf { it.pending.isNotBlank() } ?: return
    val link = repo.state.pc ?: return
    runCatching {
        withContext(Dispatchers.IO) { repo.pcClient.setState(link, d.ref, d.pending, d.mode, d.url, p.mainAccount.vintedName) }
    }.onSuccess {
        repo.updatePcTask(number) { x -> x?.copy(pending = "") }
        AppLog.i("cola", "${d.ref} PC enterado: ${d.pending} (${d.mode})")
    }.onFailure { AppLog.w("cola", "${d.ref} pendiente de avisar al PC: ${it.message}") }
}

/**
 * «Resubir»: the article goes back to «Por subir» with its data and without its photos; the title and hashtags
 * will be written again. An article the PC has is first taken off it (its old link is not played any more); if the
 * PC cannot hear that, nothing changes. Returns the originals the user must allow to delete.
 */
suspend fun PcSync.relist(number: Int, keepPhotos: Boolean = false): List<android.net.Uri> {
    if (current(number)?.pcTask != null) setStage(number, Stage.TO_UPLOAD)
    AppLog.i("cola", "$number resubido" + if (keepPhotos) " (con las fotos que ya tenía)" else "")
    return repo.relist(number, keepPhotos)
}

/** «Generar y preparar borrador». */
suspend fun PcSync.prepareNow(number: Int, mode: String? = null) {
    val p = repo.state.catalog ?: return
    val a = current(number)?.takeIf { it.inStock } ?: return
    val ref = PcPayload.ref(p.mainAccount, a, p.digits)
    repo.updatePcTask(number) { d ->
        val keep = d?.takeIf { it.ref == ref }
        PcTask(ref, PcTask.PREPARING, fixes = keep?.fixes ?: PcPayload.Fixes(), at = System.currentTimeMillis(),
            mode = mode ?: keep?.mode ?: Mode.UPLOAD_PLAY)
    }
    kick()
}

/** What the user settled in «Revisar»; the article goes back through the checks. */
suspend fun PcSync.fix(number: Int, fixes: PcPayload.Fixes) {
    repo.updatePcTask(number) { d -> d?.copy(fixes = fixes, status = PcTask.PREPARING, message = "", at = System.currentTimeMillis()) }
    kick()
}

/**
 * After a problem. If it was the PC (it already has the photos and data), the PC
 * tries again: nothing is re-sent and the AI is not asked again. If it was here,
 * the article goes through the checks again (the AI only if its data changed).
 */
suspend fun PcSync.retry(number: Int) {
    val d = current(number)?.takeIf { it.inStock }?.pcTask ?: return
    val link = repo.state.pc
    if (link != null && (d.status == "revisar" || d.status == "error") && d.ref.isNotBlank()) {
        val job = withContext(Dispatchers.IO) { repo.pcClient.retry(link, d.ref) }
        repo.updatePcTask(number) { x -> x?.copy(status = job.status, message = job.message, doubts = emptyList(), at = System.currentTimeMillis()) }
        AppLog.i("cola", "${d.ref} reintento en el PC")
        refresh()
    } else {
        prepareNow(number)
    }
}

/**
 * Out of the queue until the user decides: off the PC (if it was there and the
 * draft is not created yet) and back to «Por subir». Nothing is deleted in Vinted.
 */
suspend fun PcSync.remove(number: Int) {
    val d = current(number)?.takeIf { it.inStock }?.pcTask ?: return
    val link = repo.state.pc
    if (link != null && d.ref.isNotBlank() && d.status !in LOCAL) {
        withContext(Dispatchers.IO) { runCatching { repo.pcClient.delete(link, d.ref) } }
    }
    repo.updatePcTask(number) { null }
    AppLog.i("cola", "${d.ref} fuera de la cola")
}
