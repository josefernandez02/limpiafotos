package dev.catalogo.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JpegCleanTest {

    private fun baseJpeg(): ByteArray {
        val img = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 48) for (x in 0 until 64) img.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or 120)
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** A JPEG carrying every kind of metadata a phone camera writes. */
    private fun dirtyJpeg(): ByteArray {
        val base = baseJpeg()
        val extra = segment(0xE1, ascii("Exif\u0000\u0000MM SM-S928B GPS 40.4168N SERIAL R5CX12345")) +
            segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>MotionPhoto DEVICE</x:xmpmeta>")) +
            segment(0xE2, ascii("ICC_PROFILE\u0000\u0001\u0001") + ByteArray(40) { 7 }) +
            segment(0xE2, ascii("MPF\u0000 secondary-image")) +
            segment(0xED, ascii("Photoshop 3.0\u0000IPTC CAPTION")) +
            segment(0xEB, ascii("JP Samsung trailer")) +
            segment(0xFE, ascii("COMMENT owner=jose"))
        // Insert right after SOI, replacing nothing; add a motion-photo style trailer.
        return base.copyOfRange(0, 2) + extra + base.copyOfRange(2, base.size) + ascii("MotionPhoto_Data") + ByteArray(256) { 0xD9.toByte() }
    }

    private fun markers(jpeg: ByteArray): List<Int> {
        val found = mutableListOf<Int>()
        var i = 2
        while (true) {
            val m = jpeg[i + 1].toInt() and 0xFF
            found += m
            if (m == 0xDA) return found
            i += 2 + (((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF))
        }
    }

    private fun pixels(jpeg: ByteArray): IntArray {
        val img = ImageIO.read(ByteArrayInputStream(jpeg))
        return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    }

    @Test
    fun `removes all metadata and trailing data`() {
        val dirty = dirtyJpeg()
        val clean = JpegClean.strip(dirty)
        val text = clean.toString(Charsets.ISO_8859_1)
        for (leak in listOf("Exif", "SM-S928B", "GPS", "SERIAL", "xmpmeta", "MotionPhoto", "MPF", "IPTC", "Samsung", "COMMENT", "jose")) {
            assertFalse(text.contains(leak), "leaked $leak")
        }
        val kept = markers(clean)
        assertTrue(kept.none { (it in 0xE1..0xEF && it != 0xE2) || it == 0xFE }, "metadata marker kept: $kept")
        assertEquals(1, kept.count { it == 0xE2 }, "only the ICC block survives")
        assertEquals(0xFF, clean[clean.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, clean[clean.size - 1].toInt() and 0xFF)
    }

    @Test
    fun `image pixels are unchanged`() {
        val dirty = dirtyJpeg()
        assertContentEquals(pixels(baseJpeg()), pixels(JpegClean.strip(dirty)))
    }

    @Test
    fun `icc can be dropped too`() {
        val clean = JpegClean.strip(dirtyJpeg(), keepIcc = false)
        assertTrue(markers(clean).none { it in 0xE1..0xEF })
    }

    @Test
    fun `output is stable and recognised as clean`() {
        val once = JpegClean.strip(dirtyJpeg())
        assertTrue(JpegClean.isClean(once))
        assertContentEquals(once, JpegClean.strip(once))
        assertFalse(JpegClean.isClean(dirtyJpeg()))
    }

    @Test
    fun `rejects invalid input`() {
        assertFailsWith<JpegClean.InvalidJpeg> { JpegClean.strip(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }
        val truncated = baseJpeg().let { it.copyOfRange(0, it.size / 2) }
        assertFailsWith<JpegClean.InvalidJpeg> { JpegClean.strip(truncated) }
    }
}
