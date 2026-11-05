package dev.catalogo.core

/** SKU = device prefix (letters) + zero-padded article number, e.g. "SA0054". */
object Sku {
    const val MIN_DIGITS = 3
    const val MAX_DIGITS = 6
    private val PREFIX = Regex("^[A-Z]{1,4}$")

    fun isValidPrefix(prefix: String): Boolean = PREFIX.matches(prefix)

    fun maxNumber(digits: Int): Int {
        require(digits in MIN_DIGITS..MAX_DIGITS) { "digits out of range" }
        var max = 1
        repeat(digits) { max *= 10 }
        return max - 1
    }

    fun number(number: Int, digits: Int): String = number.toString().padStart(digits, '0')

    fun format(prefix: String, number: Int, digits: Int): String = prefix + number(number, digits)

    /** Returns the article number if [name] is exactly prefix + [digits] digits. */
    fun parse(name: String, prefix: String, digits: Int): Int? {
        if (!name.startsWith(prefix)) return null
        val rest = name.substring(prefix.length)
        if (rest.length != digits || !rest.all { it in '0'..'9' }) return null
        return rest.toInt()
    }
}
