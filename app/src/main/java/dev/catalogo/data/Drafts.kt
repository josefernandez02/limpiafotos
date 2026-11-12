package dev.catalogo.data

import dev.catalogo.core.Ai
import dev.catalogo.core.AiException
import dev.catalogo.core.Article
import dev.catalogo.core.Catalog
import dev.catalogo.core.CatalogException
import dev.catalogo.core.Desk as DeskState
import dev.catalogo.core.Draft
import dev.catalogo.core.Listing
import dev.catalogo.core.Sku
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Articles on their way to the PC, worked in the background while the app is
 * alive: AI (if missing or stale) → checks → photos and data to the PC → the
 * PC's progress (draft, published, in a game). Everything is saved in the
 * article ([DeskState]), so closing the app loses nothing: it carries on the
 * next time. Only this phone's own account goes to the PC.
 */
class Drafts(private val repo: Repo) {

    data class Pc(val status: Desk.Status? = null, val error: String? = null, val at: Long = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _pc = MutableStateFlow(Pc())
    val pc: StateFlow<Pc> = _pc.asStateFlow()

    /** Background loop: quick while something is moving, slow otherwise. */
    fun start() {
        scope.launch {
            while (true) {
                runCatching { tick() }
                delay(if (busy()) BUSY_MS else IDLE_MS)
            }
        }
    }

    /** Work now (after a change by the user). */
    fun kick() {
        scope.launch { runCatching { tick() } }
    }

    private fun busy(): Boolean = repo.state.parent?.articles.orEmpty().any { a ->
        a.desk?.let { it.working || it.status == "borrador" || it.status == "publicado" } == true
    }

    private suspend fun tick() = mutex.withLock {
        val p = repo.state.parent ?: return@withLock
        val moving = p.articles.filter { !it.released && (it.desk?.status == DeskState.PREPARING || it.desk?.status == DeskState.SENDING) }
            .sortedBy { it.desk!!.at }
        for (a in moving) {
            if (a.desk!!.status == DeskState.PREPARING) prepare(a.number)
            if (current(a.number)?.desk?.status == DeskState.SENDING) send(a.number)
        }
        poll()
    }

    private fun current(number: Int): Article? = repo.state.parent?.let { Catalog.article(it, number) }

    private suspend fun set(number: Int, status: String, message: String = "", doubts: List<String> = emptyList()) {
        repo.updateDesk(number) { d ->
            d?.copy(status = status, message = message, doubts = doubts, at = System.currentTimeMillis())
        }
    }

    /** AI if needed, then the checks: to the PC, or to «Revisar» with the reason. */
    private suspend fun prepare(number: Int) {
        val p = repo.state.parent ?: return
        val a = current(number) ?: return
        if (p.self.id !in a.targets) {
            return set(number, DeskState.REVIEW, "Marca la cuenta ${p.self.label} en «Publicar en»")
        }
        if (Catalog.freshAi(p, number) == null) {
            val key = repo.state.geminiKey
            if (key.isBlank()) return set(number, DeskState.REVIEW, "Falta la clave de Gemini (Ajustes)")
            val photos = runCatching { repo.gallery.albumPhotos(repo.base, Sku.format(p.self.prefix, number, p.digits)) }
                .getOrDefault(emptyList()).take(Ai.PHOTOS)
            if (photos.isEmpty()) return set(number, DeskState.REVIEW, "Este artículo no tiene fotos")
            try {
                val answer = repo.gemini.suggest(key, a.listing ?: Listing(), Catalog.aiAccounts(p), photos)
                repo.applyAi(number, answer.suggestion, answer.model, answer.seconds)
            } catch (e: AiException) {
                return set(number, DeskState.REVIEW, e.message ?: "La IA no ha respondido")
            } catch (e: CatalogException) {
                return set(number, DeskState.REVIEW, e.message ?: "Error con la IA")
            } catch (e: IOException) {
                return set(number, DeskState.PREPARING, "Sin internet: se reintenta solo")
            }
        }
        check(number)
    }

    /** Builds the draft data; with problems or doubts it waits for the user. */
    private suspend fun check(number: Int) {
        val p = repo.state.parent ?: return
        val a = current(number) ?: return
        val built = Draft.build(p, a, p.self, a.desk?.fixes ?: Draft.Fixes())
        when {
            built.problems.isNotEmpty() -> set(number, DeskState.REVIEW, built.problems.joinToString(" · "))
            built.doubts.isNotEmpty() -> set(number, DeskState.REVIEW, "Revisa: " + built.doubts.joinToString(", ") { label(it) }, built.doubts)
            else -> set(number, DeskState.SENDING)
        }
    }

    private suspend fun send(number: Int) {
        val link = repo.state.pc ?: return set(number, DeskState.REVIEW, "Configura el PC en Ajustes")
        val p = repo.state.parent ?: return
        val a = current(number) ?: return
        val built = Draft.build(p, a, p.self, a.desk?.fixes ?: Draft.Fixes())
        if (!built.ready) return check(number)
        val photos = runCatching { repo.gallery.albumPhotos(repo.base, Sku.format(p.self.prefix, number, p.digits)) }
            .getOrDefault(emptyList()).take(MAX_PHOTOS)
        if (photos.isEmpty()) return set(number, DeskState.REVIEW, "Este artículo no tiene fotos")
        try {
            val job = repo.desk.send(link, built.ref, built.payload!!, photos)
            repo.updateDesk(number) { d ->
                d?.copy(ref = built.ref, status = job.status, message = job.message, doubts = emptyList(), at = System.currentTimeMillis())
            }
        } catch (e: CatalogException) {
            val offline = e.message.orEmpty().startsWith("No llego")
            // Without connection it stays in «Enviando» and is retried; a refusal goes to «Revisar».
            set(number, if (offline) DeskState.SENDING else DeskState.REVIEW, e.message.orEmpty())
        }
    }

    /** Asks the PC how it is doing and copies each article's progress. */
    private suspend fun poll() {
        val link = repo.state.pc ?: return run { _pc.value = Pc() }
        try {
            val status = repo.desk.status(link)
            val onPc = repo.state.parent?.articles.orEmpty().filter { a ->
                a.desk != null && a.desk!!.status !in LOCAL
            }
            if (onPc.isNotEmpty()) {
                val jobs = repo.desk.jobs(link).associateBy { it.ref }
                for (a in onPc) {
                    val job = jobs[a.desk!!.ref] ?: continue
                    repo.updateDesk(a.number) { d ->
                        d?.copy(status = job.status, message = job.message, itemId = job.itemId, url = job.url, game = job.game)
                    }
                    if (job.status == "publicado" || job.status == "jugado") {
                        val p = repo.state.parent
                        if (p != null && p.self.id !in (current(a.number)?.uploaded ?: emptySet())) {
                            repo.setUploaded(a.number, p.self.id, true)
                        }
                    }
                }
            }
            _pc.value = Pc(status, null, System.currentTimeMillis())
        } catch (e: CatalogException) {
            _pc.value = Pc(_pc.value.status, e.message, System.currentTimeMillis())
        }
    }

    // ---------- actions from the screens ----------

    /** «Generar y preparar borrador». */
    suspend fun prepareNow(number: Int) {
        val p = repo.state.parent ?: return
        val a = current(number) ?: return
        val ref = Draft.ref(p.self, a, p.digits)
        repo.updateDesk(number) { d ->
            val keep = d?.takeIf { it.ref == ref }
            DeskState(ref, DeskState.PREPARING, fixes = keep?.fixes ?: Draft.Fixes(), at = System.currentTimeMillis())
        }
        kick()
    }

    /** What the user settled in «Revisar»; the article goes back through the checks. */
    suspend fun fix(number: Int, fixes: Draft.Fixes) {
        repo.updateDesk(number) { d -> d?.copy(fixes = fixes, status = DeskState.PREPARING, message = "", at = System.currentTimeMillis()) }
        kick()
    }

    /** After an error on the PC: try again there (or from scratch if it never got there). */
    suspend fun retry(number: Int) {
        val a = current(number) ?: return
        val d = a.desk ?: return prepareNow(number)
        val link = repo.state.pc
        if (link != null && (d.status == "revisar" || d.status == "error")) {
            val job = repo.desk.retry(link, d.ref)
            repo.updateDesk(number) { it?.copy(status = job.status, message = job.message) }
            kick()
        } else {
            prepareNow(number)
        }
    }

    suspend fun control(action: String): Desk.Status {
        val link = repo.state.pc ?: throw CatalogException("Configura el PC en Ajustes")
        val status = repo.desk.control(link, action)
        _pc.value = Pc(status, null, System.currentTimeMillis())
        return status
    }

    suspend fun test(link: dev.catalogo.core.PcLink): Desk.Status = repo.desk.status(link)

    companion object {
        private const val BUSY_MS = 20_000L
        private const val IDLE_MS = 60_000L
        private const val MAX_PHOTOS = 20
        private val LOCAL = setOf(DeskState.PREPARING, DeskState.REVIEW, DeskState.SENDING)

        fun label(field: String): String = when (field) {
            "categoria" -> "categoría"
            "talla" -> "talla"
            "colores" -> "color"
            "materiales" -> "material"
            "marca" -> "marca"
            else -> field
        }
    }
}
