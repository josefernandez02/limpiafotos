package dev.catalogo.data.pc

import android.content.Context
import android.util.Log
import dev.catalogo.core.ai.AiException
import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.catalog.CatalogException
import dev.catalogo.core.catalog.RelistRules
import dev.catalogo.core.listing.Listing
import dev.catalogo.core.model.Article
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.Mode
import dev.catalogo.core.model.PcLink
import dev.catalogo.core.model.PcTask
import dev.catalogo.core.model.Sku
import dev.catalogo.core.model.Stage
import dev.catalogo.core.pc.PcPayload
import dev.catalogo.data.AppLog
import dev.catalogo.data.CatalogRepository
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Articles on their way to the PC: AI (if missing or stale) → checks → photos
 * and data to the PC → the PC's progress (draft, published, in a game).
 *
 * While something is on its way here (AI or upload), [PcSyncService] keeps the
 * app running in the background with a notification, so leaving the app or
 * locking the phone does not cut Gemini or the upload. Everything is saved in
 * the article ([PcTask]): if Android still stops the app, it carries on the
 * next time. Only this phone's own account goes to the PC.
 */
class PcSync(private val repo: CatalogRepository, private val context: android.content.Context) {

    data class Pc(val status: PcClient.Status? = null, val error: String? = null, val at: Long = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _pc = MutableStateFlow(Pc())
    val pc: StateFlow<Pc> = _pc.asStateFlow()
    private val _offers = MutableStateFlow<PcClient.Offers?>(null)
    val offers: StateFlow<PcClient.Offers?> = _offers.asStateFlow()
    private val _incidents = MutableStateFlow<List<PcClient.Incident>>(emptyList())
    val incidents: StateFlow<List<PcClient.Incident>> = _incidents.asStateFlow()

    /** Links without like reported by the PC: fetched when their count changes; new ones notify once. */
    private suspend fun checkIncidents(link: PcLink, count: Int, last: String) {
        val known = _incidents.value
        if (count == known.size && (count == 0 || (known.isNotEmpty() && (last.isBlank() || known.any { it.id == last })))) return
        val list = withContext(Dispatchers.IO) { repo.pcClient.incidents(link) }.sortedByDescending { it.at }
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
            PcSyncService.alert(context, 9_000, title, text)
            AppLog.w("pc", "avisos del PC: ${fresh.joinToString { it.title.ifBlank { it.url } }}")
        }
    }

    /** «Revisado»: it leaves the list here and on the PC. */
    suspend fun reviewed(id: String) {
        val link = repo.state.pc ?: return
        withContext(Dispatchers.IO) { repo.pcClient.reviewed(link, id) }
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
                PcSyncService.sync(context, local())
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
    fun local(): List<Article> = repo.state.catalog?.articles.orEmpty()
        .filter { it.inStock && (it.pcTask?.status == PcTask.PREPARING || it.pcTask?.status == PcTask.SENDING) }
        .sortedBy { it.pcTask!!.at }

    private fun album(p: CatalogState, a: Article): List<android.net.Uri> =
        runCatching { repo.gallery.albumPhotos(repo.base, Sku.format(p.mainAccount.prefix, a.number, p.digits)) }
            .getOrDefault(emptyList())

    /** The AI only looks at the photos chosen for it; the PC always gets the whole album. */
    private fun aiPhotos(p: CatalogState, a: Article): List<android.net.Uri> =
        Catalog.chosenPhotos(album(p, a), a.photos) { it.toString() }

    /** Work now (after a change by the user). */
    fun kick() {
        PcSyncService.sync(context, local())
        scope.launch {
            runCatching { tick() }
            PcSyncService.sync(context, local())
        }
    }

    private fun busy(): Boolean = repo.state.catalog?.articles.orEmpty().any { a ->
        a.inStock && a.pcTask?.let { it.working || it.status == "borrador" || it.status == "publicado" } == true
    }

    private suspend fun tick() = mutex.withLock {
        val p = repo.state.catalog ?: return@withLock
        val moving = p.articles.filter { it.inStock && (it.pcTask?.status == PcTask.PREPARING || it.pcTask?.status == PcTask.SENDING) }
            .sortedBy { it.pcTask!!.at }
        for (a in moving) {
            if (a.pcTask!!.status == PcTask.PREPARING) prepare(a.number)
            if (current(a.number)?.pcTask?.status == PcTask.SENDING) send(a.number)
        }
    }

    private fun current(number: Int): Article? = repo.state.catalog?.let { Catalog.article(it, number) }

    private suspend fun set(number: Int, status: String, message: String = "", doubts: List<String> = emptyList()) {
        // Sold while it was being prepared: it stays as the sale left it.
        if (current(number)?.inStock != true) return
        val before = current(number)?.pcTask?.status
        repo.updatePcTask(number) { d ->
            d?.copy(status = status, message = message, doubts = doubts, at = System.currentTimeMillis())
        }
        AppLog.i("cola", "${current(number)?.pcTask?.ref ?: number} $before -> $status ${message.take(160)}")
        if (status == PcTask.REVIEW && before != PcTask.REVIEW) alert(number, message)
    }

    /** «0031 necesita que lo mires» when an article stops and waits for the user. */
    private fun alert(number: Int, message: String) {
        val p = repo.state.catalog ?: return
        PcSyncService.alert(context, number, "${Sku.format(p.mainAccount.prefix, number, p.digits)} necesita que lo mires", message)
    }

    /** AI if needed, then the checks: to the PC, or to «Revisar» with the reason. */
    private suspend fun prepare(number: Int) {
        val p = repo.state.catalog ?: return
        val a = current(number) ?: return
        if (p.mainAccount.id !in a.targets) {
            // With a single phone there is nothing to choose: it goes to this account.
            if (p.otherAccounts.isEmpty() && a.accounts.isEmpty()) {
                repo.setAccounts(number, setOf(p.mainAccount.id))
            } else {
                return set(number, PcTask.REVIEW, "Marca la cuenta ${p.mainAccount.label} en «Publicar en»")
            }
        }
        if (Catalog.freshAi(p, number) == null) {
            val key = repo.state.geminiKey
            if (key.isBlank()) return set(number, PcTask.REVIEW, "Falta la clave de Gemini (Ajustes)")
            val photos = aiPhotos(p, a)
            if (photos.isEmpty()) return set(number, PcTask.REVIEW, "Este artículo no tiene fotos")
            try {
                val accounts = Catalog.aiAccounts(p)
                var answer = repo.gemini.suggest(key, a.listing ?: Listing(), accounts, photos, a.relist)
                // A re-listed article must not go up with the old title or hashtags. Asking the AI again gives the same answer
                // (the photos and the account rules decide it), so what repeats is edited with one short text-only call.
                var repeated = a.relist?.let { RelistRules.repeated(it, answer.suggestion.titles, answer.suggestion.hashtags) }.orEmpty()
                if (repeated.isNotEmpty()) {
                    val fixed = try {
                        repo.gemini.fixRepeated(key, a.listing ?: Listing(), a.relist!!, answer.suggestion, accounts)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.w("ia", "no se pudo arreglar lo repetido: ${e.message}")
                        null
                    }
                    if (fixed != null) {
                        answer = answer.copy(suggestion = fixed)
                        repeated = RelistRules.repeated(a.relist!!, fixed.titles, fixed.hashtags)
                        AppLog.i("ia", "tras el arreglo: ${if (repeated.isEmpty()) "ya no repite" else "repite ${repeated.joinToString(" y ")}"}")
                    }
                }
                // Only the hashtags still fall short: the title is what makes the listing different, so two new tags are enough at the end.
                if (repeated == listOf("hashtags")) {
                    val before = a.relist!!.hashtags.map { it.lowercase() }.toSet()
                    val fresh = answer.suggestion.hashtags.count { it.lowercase() !in before }
                    AppLog.i("ia", "${Catalog.article(p, number)?.pcTask?.ref ?: number}: hashtags nuevos $fresh de ${answer.suggestion.hashtags.size} tras el arreglo")
                    if (fresh >= 2) repeated = emptyList()
                }
                if (repeated.isNotEmpty()) {
                    return set(number, PcTask.REVIEW, "La IA ha repetido ${repeated.joinToString(" y ")} de la publicación anterior. Vuelve a intentarlo")
                }
                repo.applyAi(number, answer.suggestion, answer.model, answer.seconds)
                if (a.relist != null) repo.finishRelist(number)
            } catch (e: AiException) {
                return set(number, PcTask.REVIEW, e.message ?: "La IA no ha respondido")
            } catch (e: CatalogException) {
                return set(number, PcTask.REVIEW, e.message ?: "Error con la IA")
            } catch (e: IOException) {
                return set(number, PcTask.PREPARING, "Sin conexión: se reintenta solo")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nunca en bucle: cualquier otro fallo espera al usuario (no gasta cuota de Gemini).
                return set(number, PcTask.REVIEW, "Error con la IA: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        check(number)
    }

    /** Builds the draft data; with problems or doubts it waits for the user. */
    private suspend fun check(number: Int) {
        val p = repo.state.catalog ?: return
        val a = current(number) ?: return
        val built = PcPayload.build(p, a, p.mainAccount, a.pcTask?.fixes ?: PcPayload.Fixes())
        when {
            built.problems.isNotEmpty() -> set(number, PcTask.REVIEW, built.problems.joinToString(". "))
            built.doubts.isNotEmpty() -> set(number, PcTask.REVIEW, "Revisa: " + built.doubts.joinToString(", ") { label(it) }, built.doubts)
            else -> set(number, PcTask.SENDING)
        }
    }

    private suspend fun send(number: Int) {
        val link = repo.state.pc ?: return set(number, PcTask.REVIEW, "Configura el PC en Ajustes")
        val p = repo.state.catalog ?: return
        val a = current(number)?.takeIf { it.inStock } ?: return
        val built = PcPayload.build(p, a, p.mainAccount, a.pcTask?.fixes ?: PcPayload.Fixes())
        if (!built.ready) return check(number)
        val photos = album(p, a).take(MAX_PHOTOS)
        if (photos.isEmpty()) return set(number, PcTask.REVIEW, "Este artículo no tiene fotos")
        try {
            // A cheap question first: without the PC, the photos (MBs) are not uploaded every few seconds.
            repo.pcClient.status(link)
            if (current(number)?.inStock != true) return
            val job = repo.pcClient.send(link, built.ref, built.payload!!, photos)
            repo.updatePcTask(number) { d ->
                d?.copy(ref = built.ref, status = job.status, message = job.message, doubts = emptyList(), at = System.currentTimeMillis())
            }
        } catch (e: CatalogException) {
            val offline = e.message.orEmpty().startsWith("No llego")
            // Without connection it stays in «Enviando» and is retried; a refusal goes to «Revisar»,
            // with the field to fix when the PC names one (size, category…).
            if (offline) set(number, PcTask.SENDING, e.message.orEmpty())
            else set(number, PcTask.REVIEW, e.message.orEmpty(), fieldsIn(e.message.orEmpty()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            set(number, PcTask.REVIEW, "No se pudo enviar: ${e.message ?: e.javaClass.simpleName}")
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
        PcSyncService.alert(
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
            val status = repo.pcClient.status(link)
            // States set by hand and sold articles first: the PC must know them before it plays anything.
            tellPc()
            val onPc = repo.state.catalog?.articles.orEmpty().filter { a ->
                a.inStock && a.pcTask != null && a.pcTask!!.status !in LOCAL && a.pcTask!!.pending.isBlank()
            }
            if (onPc.isNotEmpty()) {
                val jobs = repo.pcClient.jobs(link).associateBy { it.ref }
                for (a in onPc) {
                    val job = jobs[a.pcTask!!.ref] ?: continue
                    // The PC could not fill a field the user can settle here: it comes back to «Revisar»
                    // with that field to choose, instead of a retry that would fail the same way.
                    val fields = if (job.status == "revisar" || job.status == "error") fieldsIn(job.message) else emptyList()
                    if (fields.isNotEmpty()) {
                        set(a.number, PcTask.REVIEW, "PC: ${job.message}", fields)
                        continue
                    }
                    val before = a.pcTask!!.status
                    // A state set by hand meanwhile wins: the PC is told first, then it answers.
                    // Sold while the PC was answering: its answer is old news.
                    if (current(a.number)?.inStock != true) continue
                    repo.updatePcTask(a.number) { d ->
                        if (d == null || d.pending.isNotBlank()) d
                        else d.copy(status = job.status, message = job.message, itemId = job.itemId, url = job.url, game = job.game)
                    }
                    if (before != job.status) {
                        AppLog.i("cola", "${job.ref} PC: $before -> ${job.status} ${job.message.take(160)}")
                        if (job.status == "revisar" || job.status == "error") alert(a.number, job.message)
                    }
                    if (job.status == "publicado" || job.status == "jugado") {
                        val p = repo.state.catalog
                        if (p != null && p.mainAccount.id !in (current(a.number)?.uploaded ?: emptySet())) {
                            repo.setUploaded(a.number, p.mainAccount.id, true)
                        }
                    }
                }
            }
            failures = 0
            downSince = 0L
            downAlerted = false
            _pc.value = Pc(status, null, System.currentTimeMillis())
            // An older PC program has no offers: that is not an error.
            runCatching { repo.pcClient.offers(link) }.onSuccess { _offers.value = it }
            // The phone decides which game groups are played; the PC is told whenever it differs (older programs: nothing).
            runCatching {
                val onPc = repo.pcClient.gamesOff(link)
                if (onPc != null && onPc != repo.state.gamesOff) repo.pcClient.setGamesOff(link, repo.state.gamesOff)
            }
            runCatching { checkIncidents(link, status.incidents, status.lastIncident) }
            if (System.currentTimeMillis() - logSent > LOG_EVERY_MS) {
                logSent = System.currentTimeMillis()
                runCatching { repo.pcClient.log(link, AppLog.tail()) }
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
    suspend fun setStage(number: Int, stage: Stage, url: String = "", notify: Boolean = true) {
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
    suspend fun setStageMany(numbers: List<Int>, stage: Stage): List<Int> = coroutineScope {
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
    suspend fun setMode(number: Int, mode: String) {
        val d = current(number)?.takeIf { it.inStock }?.pcTask ?: return
        repo.updatePcTask(number) { x -> x?.copy(mode = mode, pending = if (x.status in setOf("borrador", "publicado")) x.status else x.pending) }
        AppLog.i("cola", "${d.ref} modo: $mode")
        push(number)
    }

    /** «Solo jugar»: the article is already on Vinted; its link goes to the next game. */
    suspend fun playOnly(number: Int, url: String) {
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
    suspend fun tellPc() {
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
    private suspend fun push(number: Int) {
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
    suspend fun relist(number: Int, keepPhotos: Boolean = false): List<android.net.Uri> {
        if (current(number)?.pcTask != null) setStage(number, Stage.TO_UPLOAD)
        AppLog.i("cola", "$number resubido" + if (keepPhotos) " (con las fotos que ya tenía)" else "")
        return repo.relist(number, keepPhotos)
    }

    /** «Generar y preparar borrador». */
    suspend fun prepareNow(number: Int, mode: String? = null) {
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
    suspend fun fix(number: Int, fixes: PcPayload.Fixes) {
        repo.updatePcTask(number) { d -> d?.copy(fixes = fixes, status = PcTask.PREPARING, message = "", at = System.currentTimeMillis()) }
        kick()
    }

    /**
     * After a problem. If it was the PC (it already has the photos and data), the PC
     * tries again: nothing is re-sent and the AI is not asked again. If it was here,
     * the article goes through the checks again (the AI only if its data changed).
     */
    suspend fun retry(number: Int) {
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
    suspend fun remove(number: Int) {
        val d = current(number)?.takeIf { it.inStock }?.pcTask ?: return
        val link = repo.state.pc
        if (link != null && d.ref.isNotBlank() && d.status !in LOCAL) {
            withContext(Dispatchers.IO) { runCatching { repo.pcClient.delete(link, d.ref) } }
        }
        repo.updatePcTask(number) { null }
        AppLog.i("cola", "${d.ref} fuera de la cola")
    }

    suspend fun control(action: String): PcClient.Status {
        val link = repo.state.pc ?: throw CatalogException("Configura el PC en Ajustes")
        val status = withContext(Dispatchers.IO) { repo.pcClient.control(link, action) }   // never on the UI thread
        _pc.value = Pc(status, null, System.currentTimeMillis())
        return status
    }

    /** Turns a game group on or off. It is saved here at once; the PC hears it now or on its next check. */
    suspend fun setGame(kind: String, on: Boolean) {
        val off = if (on) repo.state.gamesOff - kind else repo.state.gamesOff + kind
        repo.setGamesOff(off)
        val link = repo.state.pc ?: return
        runCatching { withContext(Dispatchers.IO) { repo.pcClient.setGamesOff(link, off) } }
    }

    suspend fun offersControl(action: String): PcClient.Offers {
        val link = repo.state.pc ?: throw CatalogException("Configura el PC en Ajustes")
        val o = withContext(Dispatchers.IO) { repo.pcClient.offersControl(link, action) }
        _offers.value = o
        return o
    }

    suspend fun test(link: PcLink): PcClient.Status = withContext(Dispatchers.IO) { repo.pcClient.status(link) }

    companion object {
        /** AI or upload waiting on this phone (e.g. after a cut connection). */
        private const val LOCAL_MS = 10_000L
        private const val LOG_EVERY_MS = 5 * 60_000L
        private const val DOWN_ALERT_MS = 15 * 60_000L
        private const val POLL_BUSY_MS = 8_000L
        private const val POLL_IDLE_MS = 30_000L
        private const val IDLE_MS = 60_000L
        private const val MAX_PHOTOS = 20
        private val LOCAL = setOf(PcTask.PREPARING, PcTask.REVIEW, PcTask.SENDING)

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
