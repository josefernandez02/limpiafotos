package dev.catalogo.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val PROTOCOL_VERSION = 1

/** Messages exchanged between parent and child, one per BYTES payload. */
@Serializable
sealed interface Msg

@Serializable @SerialName("hello")
data class Hello(val deviceId: String, val role: Role, val protocol: Int = PROTOCOL_VERSION) : Msg

@Serializable @SerialName("pair")
data class PairAccept(
    val parentId: String,
    val name: String,
    val prefix: String,
    val digits: Int,
    val configVersion: Long,
    val secret: String,
) : Msg

@Serializable @SerialName("challenge")
data class Challenge(val nonce: String) : Msg

@Serializable @SerialName("proof")
data class Proof(val mac: String) : Msg

/** Parent → child: current config for the child and numbers it must delete. */
@Serializable @SerialName("sync")
data class Sync(
    val name: String,
    val prefix: String,
    val digits: Int,
    val configVersion: Long,
    val releases: List<Int>,
) : Msg

/** Child → parent: numbers deleted as asked, and numbers released on the child. */
@Serializable @SerialName("sync_reply")
data class SyncReply(val released: List<Int>, val releasedHere: List<Int>) : Msg

@Serializable @SerialName("sync_ack")
data class SyncAck(val accepted: List<Int>) : Msg

@Serializable
data class FileInfo(val payloadId: Long, val size: Long, val sha256: String)

/** Header of one album; its files follow as STREAM payloads with these ids. */
@Serializable @SerialName("album")
data class Album(val number: Int, val files: List<FileInfo>) : Msg

@Serializable @SerialName("album_ack")
data class AlbumAck(val number: Int, val ok: Boolean) : Msg

@Serializable @SerialName("done")
data object Done : Msg

@Serializable @SerialName("failure")
data class Failure(val reason: String) : Msg

object Wire {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "t"
    }

    fun encode(msg: Msg): ByteArray = json.encodeToString(Msg.serializer(), msg).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Msg = json.decodeFromString(Msg.serializer(), bytes.toString(Charsets.UTF_8))
}

/** Pairing secret and mutual challenge-response (HMAC-SHA256). */
object Auth {
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()

    fun newSecret(): String = randomB64(32)

    fun newNonce(): String = randomB64(16)

    fun newId(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun mac(secret: String, label: String, nonce: String): String {
        val key = Base64.getDecoder().decode(secret)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return b64.encodeToString(mac.doFinal("$label:$nonce".toByteArray(Charsets.UTF_8)))
    }

    fun verify(secret: String, label: String, nonce: String, candidate: String): Boolean =
        MessageDigest.isEqual(mac(secret, label, nonce).toByteArray(), candidate.toByteArray())

    private fun randomB64(size: Int): String = b64.encodeToString(ByteArray(size).also(random::nextBytes))
}

object Hashing {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
