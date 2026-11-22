package dev.catalogo.data

import android.content.Context

import kotlinx.coroutines.coroutineScope

import kotlinx.coroutines.awaitAll

import kotlinx.coroutines.async

import android.util.Log

import dev.catalogo.core.AiException
import dev.catalogo.core.Article
import dev.catalogo.core.Catalog
import dev.catalogo.core.CatalogException
import dev.catalogo.core.Desk as DeskState
import dev.catalogo.core.Draft
import dev.catalogo.core.Listing
import dev.catalogo.core.Relisting
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
    private val _offers = MutableStateFlow<Desk.Offers?>(null)
    val offers: StateFlow<Desk.Offers?> = _offers.asStateFlow()
    private val _incidents = MutableStateFlow<List<Desk.Incident>>(emptyList())
    val incidents: StateFlow<List<Desk.Incident>> = _incidents.asStateFlow()

    /** Links without like reported by the PC: fetched when their count changes; new ones notify once. */
    private suspend fun checkIncidents(link: dev.catalogo.core.PcLink, count: Int, last: String) {
        val known = _incidents.value
        if (count == known.size && (count == 0 || (known.isNotEmpty() && (last.isBlank() || known.any { it.id == last })))) return
        val list = withContext(Dispatchers.IO) { repo.desk.incidents(link) }.sortedByDescending { it.at }
        _incidents.value = list
        val prefs = context.getSharedPreferences("incidencias", Context.MODE_PRIVATE)
        val seen = prefs.getLong("vistas_hasta", 0)
        val fresh = list.filter { it.at > seen }
        if (fresh.isNotEmpty()) {
            prefs.edit().putLong("vistas_hasta", list.maxOf { it.at }).apply()
            val games = fresh.map { it.game }.distinct().joinToString(", ")
            val worst = fresh.firstOrNull { it.level == "grave" } ?: fresh.firstOrNull { it.isWarning }
            val mark = if (worst?.level == "grave") "\uD83D\uDD34 " else ""
            val title = mark + when {
                fresh.size == 1 && worst != null -> worst.title.ifBlank { "Aviso del PC" }
                worst != null -> "${fresh.size} avisos del PC"
                fresh.size == 1 -> "1 link por revisar"
                else -> "${fresh.size} links por revisar"
            }
            val text = if (fresh.size == 1 && worst != null) worst.summary.ifBlank { worst.reason }.let { if (it.length > 90) it.take(89).trimEnd() + "…" else it }
                else games.let { if (it.length > 70) it.take(69).trimEnd() + "…" else it }
            DraftsService.alert(context, 9_000, title, text)
            AppLog.w("pc", "avisos del PC: ${fresh.joinToString { it.title.ifBlank { it.url } }}")
        }
    }

    /** «Revisado»: it leaves the list here and on the PC. */
    suspend fun reviewed(id: String) {
        val link = repo.state.pc ?: return
        withContext(Dispatchers.IO) { repo.desk.reviewed(link, id) }
        _incidents.value = _incidents.value.filterNot { it.id == id }
    }

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
                val accounts = Catalog.aiAccounts(p)
                var answer = repo.gemini.suggest(key, a.listing ?: Listing(), accounts, photos, a.relist)
                // A re-listed article must not go up with the old title or hashtags: the AI is asked again (twice at most), then it waits for the user.
                var repeated = a.relist?.let { Relisting.repeated(it, answer.suggestion.titles, answer.suggestion.hashtags) }.orEmpty()
                var tries = 0
                while (repeated.isNotEmpty() && tries < RELIST_RETRIES) {
                    tries++
                    val old = a.relist!!
                    val tags = answer.suggestion.hashtags
                    val newTags = tags.count { t -> t.lowercase() !in old.hashtags.map { it.lowercase() }.toSet() }
                    AppLog.i("ia", "${Catalog.article(p, number)?.desk?.ref ?: number}: repite ${repeated.joinToString(" y ")}; intento $tries · " +
                        "títulos antes=${old.titles.values} ahora=${answer.suggestion.titles.values} · hashtags nuevos $newTags de ${tags.size}")
                    // Same prompt, same answer: the retry says what was refused and asks with more variety.
                    answer = repo.gemini.suggest(key, a.listing ?: Listing(), accounts, photos, a.relist,
                        rejected = dev.catalogo.core.Relist(answer.suggestion.titles, answer.suggestion.hashtags))
                    repeated = a.relist?.let { Relisting.repeated(it, answer.suggestion.titles, answer.suggestion.hashtags) }.orEmpty()
                }
                // Only the hashtags still fall short: the title is what makes the listing different, so two new tags are enough at the end.
                if (repeated == listOf("hashtags")) {
                    val before = a.relist!!.hashtags.map { it.lowercase() }.toSet()
                    val fresh = answer.suggestion.hashtags.count { it.lowercase() !in before }
                    AppLog.i("ia", "${Catalog.article(p, number)?.desk?.ref ?: number}: hashtags nuevos $fresh de ${answer.suggestion.hashtags.size} tras los reintentos")
                    if (fresh >= 2) repeated = emptyList()
                }
                if (repeated.isNotEmpty()) {
                    return set(number, DeskState.REVIEW, "La IA ha repetido ${repeated.joinToString(" y ")} de la publicación anterior. Vuelve a intentarlo")
                }
                repo.applyAi(number, answer.suggestion, answer.model, answer.seconds)
                if (a.relist != null) repo.finishRelist(number)
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
    private var downSince = 0L
    private var downAlerted = false

    /**
     * The PC stops answering for a long stretch in the daytime: one normal notification per outage
     * (the app is the one asking, so it is also the one that notices; the PC cannot say it is off).
     */
    private fun checkDown(now: Long) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (downAlerted || downSince == 0L || now - downSince < DOWN_ALERT_MS || hour !in 9..23) return
        // The last thing the PC said was «parado» (stopped or closed on purpose): not answering is expected, not an outage.
        if (_pc.value.status?.text == "parado") return
        downAlerted = true
        DraftsService.alert(
            context, 9_002, "No llego al PC",
            "Lleva ${(now - downSince) / 60_000} min sin responder. Mira que el PC esté encendido, con internet y el programa abierto.",
        )
        AppLog.w("pc", "sin respuesta del PC desde hace ${(now - downSince) / 60_000} min")
    }
    private var logSent = 0L

    /** Asks the PC how it is doing and copies each article's progress. */
    private suspend fun poll() {
        val link = repo.state.pc ?: return run { _pc.value = Pc() }
        try {
            val status = repo.desk.status(link)
            // States set by hand first: the PC must know them before it plays anything.
            for (a in repo.state.parent?.articles.orEmpty().filter { it.desk?.pending?.isNotBlank() == true }) push(a.number)
            val onPc = repo.state.parent?.articles.orEmpty().filter { a ->
                a.desk != null && a.desk!!.status !in LOCAL && a.desk!!.pending.isBlank()
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
                    // A state set by hand meanwhile wins: the PC is told first, then it answers.
                    repo.updateDesk(a.number) { d ->
                        if (d == null || d.pending.isNotBlank()) d
                        else d.copy(status = job.status, message = job.message, itemId = job.itemId, url = job.url, game = job.game)
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
            downSince = 0L
            downAlerted = false
            _pc.value = Pc(status, null, System.currentTimeMillis())
            // An older PC program has no offers: that is not an error.
            runCatching { repo.desk.offers(link) }.onSuccess { _offers.value = it }
            runCatching { checkIncidents(link, status.incidents, status.lastIncident) }
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
            val now = System.currentTimeMillis()
            if (downSince == 0L) downSince = now
            checkDown(now)
            AppLog.w("pc", "estado: ${e.message ?: e.javaClass.simpleName} (fallo $failures)")
            if (failures >= 3 || _pc.value.status == null) {
                _pc.value = Pc(_pc.value.status, e.message ?: "No llego al PC", System.currentTimeMillis())
            }
        }
    }

    // ---------- actions from the screens ----------

    // ---------- by hand: what to do and where it stands ----------

    /**
     * Sets where the article stands in this phone's account, as the user knows it
     * (done by hand, or the system got it wrong). The PC hears about it so it never
     * likes a link twice or publishes a draft that must stay a draft. If the PC
     * cannot be reached, the change stays here and is sent on the next check.
     */
    suspend fun setStage(number: Int, stage: dev.catalogo.core.Stage, url: String = "", notify: Boolean = true) {
        val p = repo.state.parent ?: return
        val a = current(number) ?: return
        val ref = a.desk?.ref?.takeIf { it.isNotBlank() } ?: Draft.ref(p.self, a, p.digits)
        val link = repo.state.pc
        val cleanUrl = url.trim().ifBlank { a.desk?.url.orEmpty() }
        if (stage == dev.catalogo.core.Stage.TO_UPLOAD) {
            // Off the PC first: if it cannot hear it, nothing changes here (it could still play it).
            val d = a.desk
            if (link != null && d != null && d.status !in LOCAL) {
                withContext(Dispatchers.IO) {
                    if (d.status in setOf("pendiente", "revisar", "error")) repo.desk.delete(link, d.ref)
                    else repo.desk.setState(link, d.ref, "fuera", d.mode, d.url, p.self.account)
                }
            }
            repo.updateDesk(number) { null }
            repo.setUploaded(number, p.self.id, false)
            AppLog.i("cola", "$ref a mano: por subir")
            return
        }
        val status = when (stage) {
            dev.catalogo.core.Stage.DRAFT -> "borrador"
            dev.catalogo.core.Stage.PLAYED -> "jugado"
            else -> "publicado"
        }
        repo.updateDesk(number) { d ->
            val base = d ?: DeskState(ref, status, at = System.currentTimeMillis())
            // Marked «Subido» by hand: it does not go to a game unless asked («Meter en los juegos»).
            // An article the PC published and is waiting for its game keeps its place.
            // Marked «Borrador» by hand: it stays a draft until «Subir y jugar» is pressed.
            val mode = when {
                stage == dev.catalogo.core.Stage.DRAFT && d?.status != "borrador" -> dev.catalogo.core.Mode.DRAFT
                stage == dev.catalogo.core.Stage.UPLOADED && d?.status != "publicado" -> dev.catalogo.core.Mode.DRAFT
                else -> base.mode
            }
            base.copy(ref = ref, status = status, message = "", doubts = emptyList(), url = cleanUrl,
                pending = status, mode = mode, at = System.currentTimeMillis())
        }
        repo.setUploaded(number, p.self.id, stage != dev.catalogo.core.Stage.DRAFT)
        AppLog.i("cola", "$ref a mano: $status")
        if (notify) push(number)
    }

    /**
     * The same status for several articles. Everything changes here at once; the PC is told
     * in parallel afterwards (if it cannot hear it now, the next check sends it). Returns the
     * articles that could not change («Por subir» needs the PC to drop them first).
     */
    suspend fun setStageMany(numbers: List<Int>, stage: dev.catalogo.core.Stage): List<Int> = coroutineScope {
        val failed = java.util.Collections.synchronizedList(mutableListOf<Int>())
        if (stage == dev.catalogo.core.Stage.TO_UPLOAD) {
            numbers.map { n -> async { runCatching { setStage(n, stage) }.onFailure { failed += n } } }.awaitAll()
        } else {
            for (n in numbers) runCatching { setStage(n, stage, notify = false) }.onFailure { failed += n }
            numbers.filter { it !in failed }.map { n -> async { push(n) } }.awaitAll()
        }
        failed.toList()
    }

    /** What to do with it (only before the PC acts, or for a draft / an uploaded article). */
    suspend fun setMode(number: Int, mode: String) {
        val d = current(number)?.desk ?: return
        repo.updateDesk(number) { x -> x?.copy(mode = mode, pending = if (x.status in setOf("borrador", "publicado")) x.status else x.pending) }
        AppLog.i("cola", "${d.ref} modo: $mode")
        push(number)
    }

    /** «Solo jugar»: the article is already on Vinted; its link goes to the next game. */
    suspend fun playOnly(number: Int, url: String) {
        val p = repo.state.parent ?: return
        val a = current(number) ?: return
        val ref = a.desk?.ref?.takeIf { it.isNotBlank() } ?: Draft.ref(p.self, a, p.digits)
        repo.updateDesk(number) { d ->
            (d ?: DeskState(ref, "publicado")).copy(ref = ref, status = "publicado", mode = dev.catalogo.core.Mode.PLAY,
                url = url.trim(), message = "", doubts = emptyList(), pending = "publicado", at = System.currentTimeMillis())
        }
        repo.setUploaded(number, p.self.id, true)
        push(number)
    }

    /** Sends a state set by hand to the PC; on failure it stays pending for the next check. */
    private suspend fun push(number: Int) {
        val p = repo.state.parent ?: return
        val d = current(number)?.desk?.takeIf { it.pending.isNotBlank() } ?: return
        val link = repo.state.pc ?: return
        runCatching {
            withContext(Dispatchers.IO) { repo.desk.setState(link, d.ref, d.pending, d.mode, d.url, p.self.account) }
        }.onSuccess {
            repo.updateDesk(number) { x -> x?.copy(pending = "") }
            AppLog.i("cola", "${d.ref} PC enterado: ${d.pending} (${d.mode})")
        }.onFailure { AppLog.w("cola", "${d.ref} pendiente de avisar al PC: ${it.message}") }
    }

    /**
     * «Resubir»: the article goes back to «Por subir» with its data and without its photos; the title and hashtags
     * will be written again. An article the PC has is first taken off it (its old link is not played any more); if the
     * PC cannot hear that, nothing changes. Returns the originals the user must allow to delete.
     */
    suspend fun relist(number: Int, keepPhotos: Boolean = false): List<android.net.Uri> {
        if (current(number)?.desk != null) setStage(number, dev.catalogo.core.Stage.TO_UPLOAD)
        AppLog.i("cola", "$number resubido" + if (keepPhotos) " (con las fotos que ya tenía)" else "")
        return repo.relist(number, keepPhotos)
    }

    /** «Generar y preparar borrador». */
    suspend fun prepareNow(number: Int, mode: String? = null) {
        val p = repo.state.parent ?: return
        val a = current(number) ?: return
        val ref = Draft.ref(p.self, a, p.digits)
        repo.updateDesk(number) { d ->
            val keep = d?.takeIf { it.ref == ref }
            DeskState(ref, DeskState.PREPARING, fixes = keep?.fixes ?: Draft.Fixes(), at = System.currentTimeMillis(),
                mode = mode ?: keep?.mode ?: dev.catalogo.core.Mode.UPLOAD_PLAY)
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

    suspend fun offersControl(action: String): Desk.Offers {
        val link = repo.state.pc ?: throw CatalogException("Configura el PC en Ajustes")
        val o = withContext(Dispatchers.IO) { repo.desk.offersControl(link, action) }
        _offers.value = o
        return o
    }

    suspend fun test(link: dev.catalogo.core.PcLink): Desk.Status = withContext(Dispatchers.IO) { repo.desk.status(link) }

    companion object {
        /** AI or upload waiting on this phone (e.g. after a cut connection). */
        private const val LOCAL_MS = 10_000L
        private const val LOG_EVERY_MS = 5 * 60_000L
        private const val DOWN_ALERT_MS = 15 * 60_000L
        private const val POLL_BUSY_MS = 8_000L
        private const val POLL_IDLE_MS = 30_000L
        private const val IDLE_MS = 60_000L
        private const val MAX_PHOTOS = 20
        /** Extra AI answers asked for when a re-listed article's new title or hashtags still repeat the old ones. */
        private const val RELIST_RETRIES = 3
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
