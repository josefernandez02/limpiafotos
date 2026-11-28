package dev.catalogo.core.photo

import dev.catalogo.ui.sales.count

/**
 * Where a photo goes inside the 3:4 template: [scale] is the photo's width in frame widths,
 * [dx]/[dy] move the photo's centre away from the frame's centre (also in frame widths), and
 * [rotation] turns it (degrees, clockwise) around its centre. Same meaning as the app's Framing.
 */
data class Placement(val scale: Float, val dx: Float, val dy: Float, val rotation: Float, val tight: Boolean = false)

/** A yes/no picture of where the garment is: [on] has [w] x [h] cells, row by row. */
class Mask(val w: Int, val h: Int, val on: BooleanArray) {
    init { require(on.size == w * h) { "mask size" } }
    fun at(x: Int, y: Int) = x in 0 until w && y in 0 until h && on[y * w + x]
    fun count() = on.count { it }
}
