package dev.catalogo.core

import java.io.ByteArrayOutputStream

/**
 * Rebuilds a JPEG keeping only what is needed to display it exactly as encoded:
 * a canonical JFIF header, the ICC colour profile (needed for wide-gamut
 * colours, generic and non-identifying), quantization/Huffman tables, frame
 * and scan data. Everything else is dropped: EXIF/GPS (APP1), XMP, MPF and
 * other APPn blocks, comments, and any bytes after the end of the image
 * (motion-photo video, HDR gain maps).
 */
object JpegClean {

    class InvalidJpeg(message: String) : Exception(message)

    private val JFIF = byteArrayOf(
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
        0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
    )

    fun strip(src: ByteArray, keepIcc: Boolean = true): ByteArray {
        if (src.size < 4 || u(src, 0) != 0xFF || u(src, 1) != 0xD8) throw InvalidJpeg("not a JPEG")
        val out = ByteArrayOutputStream(src.size)
        out.write(0xFF)
        out.write(0xD8)
        out.write(JFIF)

        var pos = 2
        var sawFrame = false
        while (true) {
            if (pos + 1 >= src.size) throw InvalidJpeg("missing end of image")
            if (u(src, pos) != 0xFF) throw InvalidJpeg("expected marker at $pos")
            val marker = u(src, pos + 1)
            when {
                marker == 0xFF -> pos += 1 // fill byte
                marker == 0xD9 -> {
                    if (!sawFrame) throw InvalidJpeg("no image data")
                    out.write(0xFF)
                    out.write(0xD9)
                    return out.toByteArray()
                }
                marker in 0xD0..0xD7 || marker == 0x01 -> pos += 2
                else -> {
                    val length = segmentLength(src, pos)
                    val end = pos + 2 + length
                    val keep = when {
                        marker == 0xE2 -> keepIcc && isIcc(src, pos + 4, length - 2)
                        marker in 0xE0..0xEF || marker == 0xFE -> false
                        else -> true
                    }
                    if (keep) out.write(src, pos, end - pos)
                    if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) sawFrame = true
                    pos = end
                    if (marker == 0xDA) {
                        if (!sawFrame) throw InvalidJpeg("scan before frame")
                        val dataEnd = scanEnd(src, pos)
                        out.write(src, pos, dataEnd - pos)
                        pos = dataEnd
                    }
                }
            }
        }
    }

    /** True when [bytes] is exactly what [strip] would produce, i.e. nothing left to remove. */
    fun isClean(bytes: ByteArray): Boolean =
        runCatching { strip(bytes).contentEquals(bytes) }.getOrDefault(false)

    private fun segmentLength(src: ByteArray, pos: Int): Int {
        if (pos + 4 > src.size) throw InvalidJpeg("truncated segment")
        val length = (u(src, pos + 2) shl 8) or u(src, pos + 3)
        if (length < 2 || pos + 2 + length > src.size) throw InvalidJpeg("bad segment length")
        return length
    }

    /** Entropy-coded data runs until a marker other than stuffed 0x00 or RSTn. */
    private fun scanEnd(src: ByteArray, from: Int): Int {
        var i = from
        while (i + 1 < src.size) {
            if (u(src, i) == 0xFF) {
                val next = u(src, i + 1)
                if (next == 0x00 || next in 0xD0..0xD7) {
                    i += 2
                    continue
                }
                return i
            }
            i += 1
        }
        throw InvalidJpeg("truncated scan")
    }

    private fun isIcc(src: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 12) return false
        val tag = "ICC_PROFILE"
        for (k in tag.indices) if (src[offset + k] != tag[k].code.toByte()) return false
        return src[offset + 11] == 0.toByte()
    }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}
