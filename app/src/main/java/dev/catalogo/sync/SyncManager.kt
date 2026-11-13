package dev.catalogo.sync

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dev.catalogo.core.Album
import dev.catalogo.core.AlbumAck
import dev.catalogo.core.Auth
import dev.catalogo.core.Catalog
import dev.catalogo.core.CatalogException
import dev.catalogo.core.Challenge
import dev.catalogo.core.Device
import dev.catalogo.core.Done
import dev.catalogo.core.Failure
import dev.catalogo.core.FileInfo
import dev.catalogo.core.Hello
import dev.catalogo.core.Listings
import dev.catalogo.core.Msg
import dev.catalogo.core.PROTOCOL_VERSION
import dev.catalogo.core.PairAccept
import dev.catalogo.core.Proof
import dev.catalogo.core.Role
import dev.catalogo.core.Sku
import dev.catalogo.core.Sync
import dev.catalogo.core.SyncAck
import dev.catalogo.core.SyncReply
import dev.catalogo.core.Wire
import dev.catalogo.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class SyncException(message: String) : Exception(message)

/**
 * Direct, encrypted phone-to-phone link (Nearby Connections: Bluetooth and
 * Wi-Fi Direct, no internet). The parent advertises; children discover it.
 * Every session: hello → (pairing) → mutual HMAC proof → sync releases →
 * albums, each acknowledged only after the child verified sizes and hashes
 * and saved the photos.
 */
class SyncManager(context: Context, private val repo: Repo) {

    data class PairRequest(val endpointId: String, val code: String)

    data class Status(
        val running: Boolean = false,
        val working: Boolean = false,
        val finished: Boolean = false,
        val log: List<String> = emptyList(),
        val pairRequest: PairRequest? = null,
    )

    private class Link(val endpointId: String) {
        val inbox = Channel<Msg>(Channel.UNLIMITED)
        val streams = ConcurrentHashMap<Long, CompletableDeferred<Incoming>>()
        fun stream(id: Long): CompletableDeferred<Incoming> = streams.getOrPut(id) { CompletableDeferred() }
        fun close(cause: Exception) {
            inbox.close()
            streams.values.forEach { it.completeExceptionally(cause) }
        }
    }

    private data class Incoming(val file: File, val size: Long, val sha256: String)

    private val client = Nearby.getConnectionsClient(context)
    private val incomingDir = File(context.cacheDir, "incoming")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val links = ConcurrentHashMap<String, Link>()
    private val approved = ConcurrentHashMap<String, Pair<String, String>>()
    private var requested = false
    private var idleJob: Job? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun log(line: String) = _status.update { it.copy(log = (it.log + line).takeLast(40)) }

    // ---------- start / stop ----------

    fun startParent() {
        if (_status.value.running) return
        clearIncoming()
        _status.value = Status(running = true, log = listOf("Esperando dispositivos…"))
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising("$TAG_PARENT|${repo.state.deviceId}", SERVICE_ID, lifecycle, options)
            .addOnFailureListener { fail(it) }
        armIdle()
    }

    /** Stops looking for the other phone if nobody connects in a while. */
    private fun armIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT)
            if (_status.value.running && links.isEmpty()) {
                log("Nadie se ha conectado")
                stop()
            }
        }
    }

    fun startChild() {
        if (_status.value.running) return
        clearIncoming()
        requested = false
        _status.value = Status(running = true, log = listOf("Buscando el principal…"))
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discovery, options).addOnFailureListener { fail(it) }
        armIdle()
    }

    fun stop() {
        idleJob?.cancel()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        links.values.forEach { it.close(IOException("Cerrado")) }
        links.clear()
        approved.clear()
        requested = false
        _status.update { it.copy(running = false, working = false, pairRequest = null) }
    }

    private fun fail(e: Exception) {
        val code = (e as? ApiException)?.statusCode
        if (code == null) {
            log(e.message ?: "Error de conexión")
            return
        }
        when (code) {
            ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING, ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING -> Unit
            ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> log("Activa el Bluetooth")
            in 8030..8049 -> log("Faltan permisos de dispositivos cercanos")
            else -> log("No se pudo conectar ($code). Activa Bluetooth y ubicación.")
        }
    }

    // ---------- pairing prompts ----------

    /** Parent: accept an unknown phone with the name and prefix it will use. */
    fun approvePairing(name: String, prefix: String): String? {
        val req = _status.value.pairRequest ?: return null
        val p = repo.state.parent ?: return null
        val cleanName = name.trim()
        val cleanPrefix = prefix.trim().uppercase()
        try {
            Catalog.validateDevice(p, "", cleanName, cleanPrefix)
        } catch (e: CatalogException) {
            return e.message
        }
        approved[req.endpointId] = cleanName to cleanPrefix
        _status.update { it.copy(pairRequest = null) }
        accept(req.endpointId)
        return null
    }

    /** Child: the code matches on both screens. */
    fun confirmPairing() {
        val req = _status.value.pairRequest ?: return
        _status.update { it.copy(pairRequest = null) }
        accept(req.endpointId)
    }

    fun rejectPairing() {
        val req = _status.value.pairRequest ?: return
        client.rejectConnection(req.endpointId)
        requested = false
        _status.update { it.copy(pairRequest = null) }
    }

    // ---------- Nearby callbacks ----------

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val (tag, id) = parseName(info.endpointName) ?: return
            if (tag != TAG_PARENT || requested) return
            val child = repo.state.child
            if (child != null && child.parentId != id) return
            requested = true
            log("Conectando…")
            client.requestConnection("$TAG_CHILD|${repo.state.deviceId}", endpointId, lifecycle)
                .addOnFailureListener {
                    requested = false
                    fail(it)
                }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val (tag, id) = parseName(info.endpointName) ?: return reject(endpointId)
            val state = repo.state
            val parent = state.parent
            val child = state.child
            val known = when {
                parent != null -> tag == TAG_CHILD && parent.children.any { it.id == id }
                else -> tag == TAG_PARENT && child?.parentId == id
            }
            val rightRole = if (parent != null) tag == TAG_CHILD else tag == TAG_PARENT
            when {
                !rightRole -> reject(endpointId)
                known -> accept(endpointId)
                else -> _status.update { it.copy(pairRequest = PairRequest(endpointId, info.authenticationDigits)) }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val link = links[endpointId]
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK && link != null) {
                if (repo.state.parent == null) client.stopDiscovery()
                scope.launch { runSession(link) }
            } else {
                links.remove(endpointId)
                requested = false
                log("Conexión rechazada")
            }
        }

        override fun onDisconnected(endpointId: String) {
            links.remove(endpointId)?.close(IOException("Desconectado"))
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val link = links[endpointId] ?: return
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    runCatching { Wire.decode(bytes) }.onSuccess { link.inbox.trySend(it) }
                }
                Payload.Type.STREAM -> {
                    val input = payload.asStream()?.asInputStream() ?: return
                    val target = link.stream(payload.id)
                    scope.launch(Dispatchers.IO) {
                        runCatching { readStream(payload.id, input) }
                            .onSuccess { target.complete(it) }
                            .onFailure { target.completeExceptionally(it) }
                    }
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun accept(endpointId: String) {
        idleJob?.cancel()
        links[endpointId] = Link(endpointId)
        client.acceptConnection(endpointId, payloads).addOnFailureListener {
            links.remove(endpointId)
            fail(it)
        }
    }

    private fun reject(endpointId: String) {
        client.rejectConnection(endpointId)
    }

    // ---------- sessions ----------

    private suspend fun runSession(link: Link) {
        _status.update { it.copy(working = true, finished = false) }
        var ok = false
        try {
            if (repo.state.parent != null) parentSession(link) else childSession(link)
            ok = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = when (e) {
                is SyncException, is CatalogException -> e.message ?: "Error"
                is IOException -> "Conexión perdida"
                else -> "Error: ${e.message}"
            }
            log(reason)
            runCatching { send(link, Failure(reason)) }
        } finally {
            delay(500)
            client.disconnectFromEndpoint(link.endpointId)
            links.remove(link.endpointId)?.close(IOException("Cerrado"))
            // Session over: stop advertising/discovery unless another phone is still connected.
            val idle = links.isEmpty()
            if (idle) {
                client.stopAdvertising()
                client.stopDiscovery()
            }
            _status.update { it.copy(working = !idle, finished = ok, running = if (idle) false else it.running) }
            requested = false
        }
    }

    private suspend fun parentSession(link: Link) {
        val hello = link.expect<Hello>()
        if (hello.role != Role.CHILD) throw SyncException("Dispositivo no válido")
        if (hello.protocol != PROTOCOL_VERSION) throw SyncException("Actualiza la app en ambos móviles")

        val pairing = approved.remove(link.endpointId)
        val child: Device = if (pairing != null) {
            val device = Device(hello.deviceId, pairing.first, pairing.second, Auth.newSecret())
            val p = repo.addChild(device)
            send(link, PairAccept(repo.state.deviceId, device.name, device.prefix, p.digits, p.configVersion, device.secret))
            log("Vinculado: ${device.name}")
            device
        } else {
            repo.state.parent!!.children.firstOrNull { it.id == hello.deviceId }
                ?: throw SyncException("Dispositivo no vinculado")
        }

        val nonce = Auth.newNonce()
        send(link, Challenge(nonce))
        if (!Auth.verify(child.secret, "child", nonce, link.expect<Proof>().mac)) throw SyncException("Fallo de seguridad. Vuelve a vincular")
        send(link, Proof(Auth.mac(child.secret, "parent", link.expect<Challenge>().nonce)))
        log("Conectado: ${child.name}")

        val p = repo.state.parent!!
        val current = p.children.first { it.id == child.id }
        send(link, Sync(current.name, current.prefix, p.digits, p.configVersion, Catalog.releasesFor(p, current.id)))
        val reply = link.expect<SyncReply>()
        repo.confirmReleases(current.id, reply.released)
        for (n in reply.releasedHere) repo.releaseFromChild(n, current.id, reply.prices[n])
        send(link, SyncAck(reply.releasedHere))

        // This phone's titles and descriptions, in batches that fit one payload.
        val texts = repo.state.parent!!.let { p ->
            p.articles.filter { !it.released }.mapNotNull { a -> Catalog.listingText(p, a.number, current)?.let { a.number to it } }
        }
        val batches = texts.chunked(LISTING_BATCH)
        if (batches.isEmpty()) send(link, Listings(emptyMap(), last = true))
        batches.forEachIndexed { i, batch -> send(link, Listings(batch.toMap(), last = i == batches.lastIndex)) }
        val releasedCount = (reply.released + reply.releasedHere).distinct().size
        if (releasedCount > 0) log("Liberados: $releasedCount")

        for ((number, files) in repo.outbox.pending(current.id)) {
            val sku = Sku.format(current.prefix, number, repo.state.parent!!.digits)
            log("Enviando $sku: ${files.size}")
            val streams = files.map { it.inputStream() }
            try {
                val payloads = streams.map { Payload.fromStream(it) }
                val infos = withContext(Dispatchers.IO) {
                    files.zip(payloads) { f, pl -> FileInfo(pl.id, f.length(), sha256(f)) }
                }
                send(link, Album(number, infos))
                for (pl in payloads) client.sendPayload(link.endpointId, pl).await()
                val ack = link.expect<AlbumAck>(TRANSFER_TIMEOUT)
                if (ack.number != number || !ack.ok) throw SyncException("$sku no llegó bien. Vuelve a intentarlo")
            } finally {
                streams.forEach { runCatching { it.close() } }
            }
            repo.delivered(current.id, number, files.size)
            log("$sku enviado")
        }

        send(link, Done)
        withTimeoutOrNull(5_000) { link.inbox.receiveCatching() }
        log("Listo: ${current.name}")
    }

    private suspend fun childSession(link: Link) {
        send(link, Hello(repo.state.deviceId, Role.CHILD))
        var first = link.expect<Msg>()
        if (first is PairAccept) {
            repo.pairChild(first)
            log("Vinculado")
            first = link.expect<Msg>()
        }
        val secret = repo.state.child?.secret ?: throw SyncException("Vincula este móvil desde el principal")
        val challenge = first as? Challenge ?: throw SyncException("El principal no reconoce este móvil. Vuelve a vincular")
        send(link, Proof(Auth.mac(secret, "child", challenge.nonce)))
        val nonce = Auth.newNonce()
        send(link, Challenge(nonce))
        if (!Auth.verify(secret, "parent", nonce, link.expect<Proof>().mac)) throw SyncException("Fallo de seguridad")
        log("Conectado")

        val sync = link.expect<Sync>()
        val notMoved = repo.applyChildConfig(sync)
        if (notMoved > 0) log("$notMoved fotos no se pudieron renombrar")
        val released = repo.applyRequestedReleases(sync.releases)
        val mine = repo.state.child!!
        send(link, SyncReply(released, mine.pendingReleases.sorted(), mine.salePrices))
        repo.ackChildReleases(link.expect<SyncAck>().accepted)
        if (released.isNotEmpty()) log("Liberados: ${released.size}")

        val listings = HashMap<Int, dev.catalogo.core.ListingText>()
        while (true) {
            val batch = link.expect<Listings>()
            listings.putAll(batch.entries)
            if (batch.last) break
        }
        repo.setListings(listings)

        while (true) {
            when (val msg = link.expect<Msg>(TRANSFER_TIMEOUT)) {
                is Album -> receiveAlbum(link, msg)
                is Done -> break
                else -> throw SyncException("Respuesta inesperada")
            }
        }
        log("Listo")
    }

    private suspend fun receiveAlbum(link: Link, album: Album) {
        val c = repo.state.child!!
        val sku = Sku.format(c.self.prefix, album.number, c.digits)
        log("Recibiendo $sku: ${album.files.size}")
        val received = ArrayList<Pair<FileInfo, Incoming>>()
        var ok = false
        try {
            for (info in album.files) {
                received += info to withTimeout(TRANSFER_TIMEOUT) { link.stream(info.payloadId).await() }
            }
            if (received.any { (info, got) -> info.size != got.size || info.sha256 != got.sha256 }) {
                throw SyncException("$sku llegó dañado")
            }
            withContext(Dispatchers.IO) {
                for ((info, got) in received) {
                    // Content-derived name: a resent photo is never duplicated.
                    val name = "${info.sha256.take(16)}.jpg"
                    if (!repo.gallery.contains(repo.base, sku, name)) repo.gallery.save(repo.base, sku, got.file, name)
                }
            }
            ok = true
        } finally {
            withContext(Dispatchers.IO) { received.forEach { it.second.file.delete() } }
            runCatching { send(link, AlbumAck(album.number, ok)) }
        }
        log("$sku guardado")
    }

    // ---------- helpers ----------

    private suspend fun send(link: Link, msg: Msg) {
        client.sendPayload(link.endpointId, Payload.fromBytes(Wire.encode(msg))).await()
    }

    private suspend inline fun <reified T : Msg> Link.expect(timeoutMs: Long = MESSAGE_TIMEOUT): T {
        val msg = withTimeoutOrNull(timeoutMs) { inbox.receiveCatching().getOrNull() }
            ?: throw SyncException("Conexión perdida")
        if (msg is Failure) throw SyncException(msg.reason)
        return msg as? T ?: throw SyncException("Respuesta inesperada")
    }

    private fun readStream(id: Long, input: InputStream): Incoming {
        incomingDir.mkdirs()
        val file = File(incomingDir, "$id.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        input.use { inp ->
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = inp.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    size += read
                }
            }
        }
        return Incoming(file, size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = inp.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun clearIncoming() {
        incomingDir.deleteRecursively()
    }

    private fun parseName(name: String): Pair<String, String>? {
        val parts = name.split('|')
        return if (parts.size == 2 && parts[1].isNotEmpty()) parts[0] to parts[1] else null
    }

    companion object {
        private const val SERVICE_ID = "dev.catalogo.sync"
        private const val TAG_PARENT = "P"
        private const val TAG_CHILD = "C"
        private val STRATEGY = Strategy.P2P_STAR
        private const val MESSAGE_TIMEOUT = 60_000L
        private const val TRANSFER_TIMEOUT = 30 * 60_000L
        private const val IDLE_TIMEOUT = 2 * 60_000L
        private const val LISTING_BATCH = 8
    }
}
