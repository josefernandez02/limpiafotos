package dev.catalogo.data

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
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Articles on their way to the PC: AI (if missing or stale) → checks → photos
 * and data to the PC → the PC's progress (draft, published, in a game).
 *
 * While something is on its way here (AI or upload), [DraftsService] keeps the
 * app running in the background with a notification, so leaving the app or
 * locking the phone does not cut Gemini or the upload. Everything is saved in
 * the article ([DeskState]): if Android still stops the app, it carries on the
 * next time. Only this phone's own account goes to the PC.
 */
class Drafts(private val repo: Repo, private val context: android.content.Context) {

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
                DraftsService.sync(context, local())
                delay(if (local().isNotEmpty()) LOCAL_MS else if (busy()) BUSY_MS else IDLE_MS)
            }
        }
    }

    /** Articles still being worked on this phone (AI or upload), in queue order. */
    fun local(): List<Article> = repo.state.parent?.articles.orEmpty()
        .filter { !it.released && (it.desk?.status == DeskState.PREPARING || it.desk?.status == DeskState.SENDING) }
        .sortedBy { it.desk!!.at }

    private fun photos(p: dev.catalogo.core.ParentState, a: Article): List<android.net.Uri> {
        val album = runCatching { repo.gallery.albumPhotos(repo.base, Sku.format(p.self.prefix, a.number, p.digits)) }
            .getOrDefault(emptyList())
        return Catalog.chosenPhotos(album, a.photos) { it.toString() }
    }

    /** Work now (after a change by the user). */
    fun kick() {
        DraftsService.sync(context, local())
        scope.launch {
            runCatching { tick() }
            DraftsService.sync(context, local())
        }
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
            // With a single phone there is nothing to choose: it goes to this account.
            if (p.children.isEmpty() && a.accounts.isEmpty()) {
                repo.setAccounts(number, setOf(p.self.id))
            } else {
                return set(number, DeskState.REVIEW, "Marca la cuenta ${p.self.label} en «Publicar en»")
            }
        }
        if (Catalog.freshAi(p, number) == null) {
            val key = repo.state.geminiKey
            if (key.isBlank()) return set(number, DeskState.REVIEW, "Falta la clave de Gemini (Ajustes)")
            val photos = photos(p, a)
            if (photos.isEmpty()) return set(number, DeskState.REVIEW, "Este artículo no tiene fotos")
            try {
                val answer = repo.gemini.suggest(key, a.listing ?: Listing(), Catalog.aiAccounts(p), photos)
                repo.applyAi(number, answer.suggestion, answer.model, answer.seconds)
            } catch (e: AiException) {
                return set(number, DeskState.REVIEW, e.message ?: "La IA no ha respondido")
            } catch (e: CatalogException) {
                return set(number, DeskState.REVIEW, e.message ?: "Error con la IA")
            } catch (e: IOException) {
                return set(number, DeskState.PREPARING, "Sin conexión: se reintenta solo")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nunca en bucle: cualquier otro fallo espera al usuario (no gasta cuota de Gemini).
                return set(number, DeskState.REVIEW, "Error con la IA: ${e.message ?: e.javaClass.simpleName}")
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
        val photos = photos(p, a).take(MAX_PHOTOS)
        if (photos.isEmpty()) return set(number, DeskState.REVIEW, "Este artículo no tiene fotos")
        try {
            // A cheap question first: without the PC, the photos (MBs) are not uploaded every few seconds.
            repo.desk.status(link)
            val job = repo.desk.send(link, built.ref, built.payload!!, photos)
            repo.updateDesk(number) { d ->
                d?.copy(ref = built.ref, status = job.status, message = job.message, doubts = emptyList(), at = System.currentTimeMillis())
            }
        } catch (e: CatalogException) {
            val offline = e.message.orEmpty().startsWith("No llego")
            // Without connection it stays in «Enviando» and is retried; a refusal goes to «Revisar»,
            // with the field to fix when the PC names one (size, category…).
            if (offline) set(number, DeskState.SENDING, e.message.orEmpty())
            else set(number, DeskState.REVIEW, e.message.orEmpty(), fieldsIn(e.message.orEmpty()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            set(number, DeskState.REVIEW, "No se pudo enviar: ${e.message ?: e.javaClass.simpleName}")
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
                    // The PC could not fill a field the user can settle here: it comes back to «Revisar»
                    // with that field to choose, instead of a retry that would fail the same way.
                    val fields = if (job.status == "revisar" || job.status == "error") fieldsIn(job.message) else emptyList()
                    if (fields.isNotEmpty()) {
                        repo.updateDesk(a.number) { d ->
                            d?.copy(status = DeskState.REVIEW, message = "PC: ${job.message}", doubts = fields, at = System.currentTimeMillis())
                        }
                        continue
                    }
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

    /**
     * After a problem (here or on the PC): the article goes through the checks again
     * with its current data and, if it is fine, is sent again. The PC replaces what it
     * had while the draft was not created yet, so editing and retrying is enough.
     */
    suspend fun retry(number: Int) = prepareNow(number)

    suspend fun control(action: String): Desk.Status {
        val link = repo.state.pc ?: throw CatalogException("Configura el PC en Ajustes")
        val status = withContext(Dispatchers.IO) { repo.desk.control(link, action) }   // never on the UI thread
        _pc.value = Pc(status, null, System.currentTimeMillis())
        return status
    }

    suspend fun test(link: dev.catalogo.core.PcLink): Desk.Status = withContext(Dispatchers.IO) { repo.desk.status(link) }

    companion object {
        /** AI or upload waiting on this phone (e.g. after a cut connection). */
        private const val LOCAL_MS = 10_000L
        private const val BUSY_MS = 20_000L
        private const val IDLE_MS = 60_000L
        private const val MAX_PHOTOS = 20
        private val LOCAL = setOf(DeskState.PREPARING, DeskState.REVIEW, DeskState.SENDING)

        /** Fields named in a refusal from the PC that «Revisar» lets the user choose. */
        fun fieldsIn(message: String): List<String> {
            val m = message.lowercase()
            return listOfNotNull(
                "talla".takeIf { "talla" in m },
                // «…en esta categoría» only says where; it is not about the category itself
                "categoria".takeIf { Regex("(?<!esta )categor[ií]a").containsMatchIn(m) },
                "colores".takeIf { "color" in m },
                "materiales".takeIf { "material" in m },
            )
        }

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
