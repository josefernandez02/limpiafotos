package dev.catalogo.data

import android.util.Log

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

    /**
     * Two loops: the work here (AI, upload) and, apart, asking the PC how it is
     * doing. Apart so a long Gemini answer or a big upload never leaves the PC card
     * stale or showing «sin conexión» while the PC is in fact working.
     */
    fun start() {
        scope.launch {
            while (true) {
                runCatching { tick() }.onFailure { Log.e("cola", "tick", it) }
                DraftsService.sync(context, local())
                delay(if (local().isNotEmpty()) LOCAL_MS else IDLE_MS)
            }
        }
        scope.launch {
            while (true) {
                runCatching { poll() }.onFailure { Log.e("cola", "poll", it) }
                delay(if (local().isNotEmpty() || busy()) POLL_BUSY_MS else POLL_IDLE_MS)
            }
        }
    }

    /** Ask the PC now (app back on screen). */
    fun refresh() {
        scope.launch { runCatching { poll() } }
    }

    /** Articles still being worked on this phone (AI or upload), in queue order. */
    fun local(): List<Article> = repo.state.parent?.articles.orEmpty()
        .filter { !it.released && (it.desk?.status == DeskState.PREPARING || it.desk?.status == DeskState.SENDING) }
        .sortedBy { it.desk!!.at }

    private fun album(p: dev.catalogo.core.ParentState, a: Article): List<android.net.Uri> =
        runCatching { repo.gallery.albumPhotos(repo.base, Sku.format(p.self.prefix, a.number, p.digits)) }
            .getOrDefault(emptyList())

    /** The AI only looks at the photos chosen for it; the PC always gets the whole album. */
    private fun aiPhotos(p: dev.catalogo.core.ParentState, a: Article): List<android.net.Uri> =
        Catalog.chosenPhotos(album(p, a), a.photos) { it.toString() }

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
    }

    private fun current(number: Int): Article? = repo.state.parent?.let { Catalog.article(it, number) }

    private suspend fun set(number: Int, status: String, message: String = "", doubts: List<String> = emptyList()) {
        val before = current(number)?.desk?.status
        repo.updateDesk(number) { d ->
            d?.copy(status = status, message = message, doubts = doubts, at = System.currentTimeMillis())
        }
        AppLog.i("cola", "${current(number)?.desk?.ref ?: number} $before -> $status ${message.take(160)}")
        if (status == DeskState.REVIEW && before != DeskState.REVIEW) alert(number, message)
    }

    /** «0031 necesita que lo mires» when an article stops and waits for the user. */
    private fun alert(number: Int, message: String) {
        val p = repo.state.parent ?: return
        DraftsService.alert(context, number, "${Sku.format(p.self.prefix, number, p.digits)} necesita que lo mires", message)
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
            val photos = aiPhotos(p, a)
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
            built.problems.isNotEmpty() -> set(number, DeskState.REVIEW, built.problems.joinToString(". "))
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
        val photos = album(p, a).take(MAX_PHOTOS)
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

    private var failures = 0
    private var logSent = 0L

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
                        set(a.number, DeskState.REVIEW, "PC: ${job.message}", fields)
                        continue
                    }
                    val before = a.desk!!.status
                    repo.updateDesk(a.number) { d ->
                        d?.copy(status = job.status, message = job.message, itemId = job.itemId, url = job.url, game = job.game)
                    }
                    if (before != job.status) {
                        AppLog.i("cola", "${job.ref} PC: $before -> ${job.status} ${job.message.take(160)}")
                        if (job.status == "revisar" || job.status == "error") alert(a.number, job.message)
                    }
                    if (job.status == "publicado" || job.status == "jugado") {
                        val p = repo.state.parent
                        if (p != null && p.self.id !in (current(a.number)?.uploaded ?: emptySet())) {
                            repo.setUploaded(a.number, p.self.id, true)
                        }
                    }
                }
            }
            failures = 0
            _pc.value = Pc(status, null, System.currentTimeMillis())
            if (System.currentTimeMillis() - logSent > LOG_EVERY_MS) {
                logSent = System.currentTimeMillis()
                runCatching { repo.desk.log(link, AppLog.tail()) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // One slow answer (phone waking up, Tailscale reconnecting) is not «sin conexión»:
            // the last status stays until three tries in a row fail.
            failures++
            AppLog.w("pc", "estado: ${e.message ?: e.javaClass.simpleName} (fallo $failures)")
            if (failures >= 3 || _pc.value.status == null) {
                _pc.value = Pc(_pc.value.status, e.message ?: "No llego al PC", System.currentTimeMillis())
            }
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
     * After a problem. If it was the PC (it already has the photos and data), the PC
     * tries again: nothing is re-sent and the AI is not asked again. If it was here,
     * the article goes through the checks again (the AI only if its data changed).
     */
    suspend fun retry(number: Int) {
        val d = current(number)?.desk ?: return
        val link = repo.state.pc
        if (link != null && (d.status == "revisar" || d.status == "error") && d.ref.isNotBlank()) {
            val job = withContext(Dispatchers.IO) { repo.desk.retry(link, d.ref) }
            repo.updateDesk(number) { x -> x?.copy(status = job.status, message = job.message, doubts = emptyList(), at = System.currentTimeMillis()) }
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
    suspend fun remove(number: Int) {
        val d = current(number)?.desk ?: return
        val link = repo.state.pc
        if (link != null && d.ref.isNotBlank() && d.status !in LOCAL) {
            withContext(Dispatchers.IO) { runCatching { repo.desk.delete(link, d.ref) } }
        }
        repo.updateDesk(number) { null }
        AppLog.i("cola", "${d.ref} fuera de la cola")
    }

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
        private const val LOG_EVERY_MS = 5 * 60_000L
        private const val POLL_BUSY_MS = 8_000L
        private const val POLL_IDLE_MS = 30_000L
        private const val IDLE_MS = 60_000L
        private const val MAX_PHOTOS = 20
        private val LOCAL = setOf(DeskState.PREPARING, DeskState.REVIEW, DeskState.SENDING)

        /** Fields named in a refusal from the PC that «Revisar» lets the user choose. */
        fun fieldsIn(message: String): List<String> {
            val m = message.lowercase()
            // The PC not seeing or opening a field for a moment is not about its value: retry, do not ask.
            if (Regex("no consigo ver|no se abre|no queda a la vista|no consigo cerrar|no hay sitio|no veo la página|no cargó")
                    .containsMatchIn(m)) return emptyList()
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
