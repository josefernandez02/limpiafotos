package dev.catalogo.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun `every message round-trips`() {
        val messages: List<Msg> = listOf(
            Hello("abc", Role.CHILD),
            PairAccept("p", "Poco", "SA", 4, 3, Auth.newSecret()),
            Challenge(Auth.newNonce()),
            Proof("mac"),
            Sync("Poco", "SA", 4, 3, listOf(1, 2)),
            SyncReply(listOf(1), listOf(7), mapOf(7 to 2_500L)),
            Listings(mapOf(7 to ListingText("t", "d\n#SA0007")), last = true),
            SyncAck(listOf(7)),
            Album(54, listOf(FileInfo(123456789012L, 4_000_000, "ab".repeat(32)))),
            AlbumAck(54, true),
            Done,
            Failure("motivo"),
        )
        for (m in messages) assertEquals(m, Wire.decode(Wire.encode(m)))
    }

    @Test
    fun `a full album header fits in one bytes payload`() {
        val files = (1..60).map { FileInfo(Long.MAX_VALUE - it, 50_000_000, "f".repeat(64)) }
        assertTrue(Wire.encode(Album(999999, files)).size < 32 * 1024)
    }

    @Test
    fun `mutual authentication`() {
        val secret = Auth.newSecret()
        val nonce = Auth.newNonce()
        val proof = Auth.mac(secret, "child", nonce)
        assertTrue(Auth.verify(secret, "child", nonce, proof))
        assertFalse(Auth.verify(secret, "parent", nonce, proof), "labels are not interchangeable")
        assertFalse(Auth.verify(Auth.newSecret(), "child", nonce, proof), "wrong secret")
        assertFalse(Auth.verify(secret, "child", Auth.newNonce(), proof), "replayed proof")
    }

    @Test
    fun `ids and nonces are random`() {
        assertNotEquals(Auth.newId(), Auth.newId())
        assertEquals(16, Auth.newId().length)
        assertNotEquals(Auth.newNonce(), Auth.newNonce())
    }
}
