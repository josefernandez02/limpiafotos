package dev.catalogo.core

class CatalogException(message: String) : Exception(message)

/** Pure state transitions of the parent's catalogue. No I/O. */
object Catalog {

    fun devices(s: ParentState): List<Device> = listOf(s.self) + s.children

    fun device(s: ParentState, id: String): Device? = devices(s).firstOrNull { it.id == id }

    fun article(s: ParentState, number: Int): Article? = s.articles.firstOrNull { it.number == number }

    /** Lowest number not used by any article (released ones still pending included). */
    fun nextFree(s: ParentState): Int? {
        val used = s.articles.mapTo(HashSet()) { it.number }
        return (0..Sku.maxNumber(s.digits)).firstOrNull { it !in used }
    }

    fun create(s: ParentState, now: Long): Pair<ParentState, Int> {
        val number = nextFree(s) ?: throw CatalogException("No quedan números libres")
        val articles = (s.articles + Article(number, now)).sortedBy { it.number }
        return s.copy(articles = articles) to number
    }

    /**
     * Marks [number] as sold. Every child must confirm, except [confirmedBy]
     * (the child that originated the release, already done there).
     */
    fun release(s: ParentState, number: Int, confirmedBy: String? = null): ParentState {
        val a = article(s, number) ?: return s
        val done = setOfNotNull(confirmedBy)
        val pending = if (a.released) a.pendingRelease - done else s.children.map { it.id }.toSet() - done
        return settle(s, a.copy(released = true, pendingRelease = pending, delivered = emptyMap()))
    }

    fun confirmRelease(s: ParentState, number: Int, childId: String): ParentState {
        val a = article(s, number) ?: return s
        if (!a.released) return s
        return settle(s, a.copy(pendingRelease = a.pendingRelease - childId))
    }

    fun releasesFor(s: ParentState, childId: String): List<Int> =
        s.articles.filter { it.released && childId in it.pendingRelease }.map { it.number }

    fun markDelivered(s: ParentState, number: Int, childId: String, count: Int): ParentState {
        val a = article(s, number) ?: return s
        if (a.released) return s
        val total = (a.delivered[childId] ?: 0) + count
        return replace(s, a.copy(delivered = a.delivered + (childId to total)))
    }

    fun addChild(s: ParentState, device: Device): ParentState {
        validateDevice(s, device.id, device.name, device.prefix)
        return s.copy(children = s.children.filter { it.id != device.id } + device)
    }

    fun removeChild(s: ParentState, id: String): ParentState {
        var next = s.copy(children = s.children.filter { it.id != id })
        for (a in next.articles) {
            next = if (a.released) settle(next, a.copy(pendingRelease = a.pendingRelease - id))
            else replace(next, a.copy(delivered = a.delivered - id))
        }
        return next
    }

    fun renameDevice(s: ParentState, id: String, name: String, prefix: String): ParentState {
        val cleanName = name.trim()
        val cleanPrefix = prefix.trim().uppercase()
        validateDevice(s, id, cleanName, cleanPrefix)
        return if (id == s.self.id) {
            s.copy(self = s.self.copy(name = cleanName, prefix = cleanPrefix))
        } else {
            val children = s.children.map { if (it.id == id) it.copy(name = cleanName, prefix = cleanPrefix) else it }
            s.copy(children = children, configVersion = s.configVersion + 1)
        }
    }

    fun setDigits(s: ParentState, digits: Int): ParentState {
        if (digits !in Sku.MIN_DIGITS..Sku.MAX_DIGITS) throw CatalogException("Entre ${Sku.MIN_DIGITS} y ${Sku.MAX_DIGITS} dígitos")
        val highest = s.articles.maxOfOrNull { it.number } ?: -1
        if (highest > Sku.maxNumber(digits)) throw CatalogException("Hay artículos que no caben en $digits dígitos")
        if (digits == s.digits) return s
        return s.copy(digits = digits, configVersion = s.configVersion + 1)
    }

    fun validateDevice(s: ParentState, id: String, name: String, prefix: String) {
        if (name.isBlank() || name.length > 24) throw CatalogException("Nombre de 1 a 24 caracteres")
        if (!Sku.isValidPrefix(prefix)) throw CatalogException("Prefijo de 1 a 4 letras (A-Z)")
        if (devices(s).any { it.id != id && it.prefix == prefix }) throw CatalogException("Ese prefijo ya está en uso")
        if (devices(s).any { it.id != id && it.name.equals(name, ignoreCase = true) }) throw CatalogException("Ese nombre ya está en uso")
    }

    private fun settle(s: ParentState, a: Article): ParentState =
        if (a.released && a.pendingRelease.isEmpty()) s.copy(articles = s.articles.filter { it.number != a.number })
        else replace(s, a)

    private fun replace(s: ParentState, a: Article): ParentState =
        s.copy(articles = s.articles.map { if (it.number == a.number) a else it })
}
