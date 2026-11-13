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

    /** Article details: listing data, lot and manual cost. */
    fun updateArticle(s: ParentState, number: Int, listing: Listing?, lotId: String?, cost: Long?, ai: AiResult? = null): ParentState {
        val a = article(s, number) ?: return s
        if (lotId != null && s.lots.none { it.id == lotId }) throw CatalogException("Lote no encontrado")
        return replace(s, a.copy(listing = listing, lotId = lotId, cost = if (lotId != null) null else cost, ai = ai))
    }

    /** Price in cents [number] is listed at in Vinted. */
    fun setPrice(s: ParentState, number: Int, price: Long?): ParentState {
        val a = article(s, number) ?: return s
        if (price != null && price <= 0) throw CatalogException("Precio no válido")
        return replace(s, a.copy(price = price))
    }

    /** Photos of [number] chosen for Vinted, in order; empty = every photo of the album. */
    fun setPhotos(s: ParentState, number: Int, photos: List<String>): ParentState {
        val a = article(s, number) ?: return s
        val clean = photos.distinct()
        return if (a.photos == clean) s else replace(s, a.copy(photos = clean))
    }

    /**
     * The album's photos that go to Vinted: the chosen ones in their order (those
     * deleted since are skipped), or all of them when nothing was chosen or none is left.
     */
    fun <T> chosenPhotos(album: List<T>, chosen: List<String>, id: (T) -> String): List<T> {
        if (chosen.isEmpty()) return album
        val byId = album.associateBy(id)
        return chosen.mapNotNull { byId[it] }.ifEmpty { album }
    }

    /** Where [number] stands on its way to the PC (null = never sent). */
    fun setDesk(s: ParentState, number: Int, desk: Desk?): ParentState {
        val a = article(s, number) ?: return s
        return if (a.desk == desk) s else replace(s, a.copy(desk = desk))
    }

    /** Applies an AI answer to [number]: listing data the user left empty and the titles. */
    fun applyAi(s: ParentState, number: Int, suggestion: AiSuggestion, model: String, seconds: Int = 0): ParentState {
        val a = article(s, number) ?: return s
        if (a.released) return s
        val applied = suggestion.applyTo(a.listing ?: Listing())
        return replace(s, a.copy(listing = applied, ai = suggestion.result(applied, model, seconds)))
    }

    /** AI data of [number] while it still matches the listing (null when stale or absent). */
    fun freshAi(s: ParentState, number: Int): AiResult? {
        val a = article(s, number) ?: return null
        val listing = a.listing ?: return null
        return a.ai?.takeIf { it.basis == Ai.basis(listing) }
    }

    /** Accounts the AI writes titles for: every phone, in order. */
    fun aiAccounts(s: ParentState): List<Ai.Account> = devices(s).map { Ai.Account(it.id, it.prefix, it.style) }

    /** Marks whether [deviceId]'s account already has [number] published on Vinted (and so is one of its accounts). */
    fun setUploaded(s: ParentState, number: Int, deviceId: String, uploaded: Boolean): ParentState {
        val a = article(s, number) ?: return s
        if (a.released || device(s, deviceId) == null) return s
        return replace(
            s,
            a.copy(
                uploaded = if (uploaded) a.uploaded + deviceId else a.uploaded - deviceId,
                accounts = if (uploaded) a.targets + deviceId else a.targets,
            ),
        )
    }

    /** Accounts [number] will be published in. Unknown devices are ignored. */
    fun setAccounts(s: ParentState, number: Int, accounts: Set<String>): ParentState {
        val a = article(s, number) ?: return s
        if (a.released) return s
        val known = devices(s).map { it.id }.toSet()
        val chosen = accounts.filter { it in known }.toSet()
        return replace(s, a.copy(accounts = chosen, uploaded = a.uploaded.filter { it in chosen }.toSet()))
    }

    /**
     * Makes sure no secondary phone writes with the main phone's style (phones
     * paired before styles existed all had style 0).
     */
    fun ensureDistinctStyles(s: ParentState): ParentState {
        if (s.children.none { it.style == s.self.style }) return s
        val other = (s.self.style + 1) % Garments.STYLES
        val children = s.children.map { c -> if (c.style != s.self.style) c else c.copy(style = other) }
        return s.copy(children = children, configVersion = s.configVersion + 1)
    }

    /** Style for a newly paired phone: never the main phone's own style. */
    fun nextStyle(s: ParentState): Int = (s.self.style + 1) % Garments.STYLES

    fun styleAndTemplate(s: ParentState, id: String, style: Int, template: String): ParentState {
        val clean = template.trim().take(ListingWriter.DESCRIPTION_MAX)
        return if (id == s.self.id) {
            s.copy(self = s.self.copy(style = style.mod(Garments.STYLES), template = clean))
        } else {
            s.copy(
                children = s.children.map { if (it.id == id) it.copy(style = style.mod(Garments.STYLES), template = clean) else it },
                configVersion = s.configVersion + 1,
            )
        }
    }

    /** Title and description of [number] as [device] publishes it. */
    fun listingText(s: ParentState, number: Int, device: Device): ListingText? {
        val listing = article(s, number)?.listing ?: return null
        val ai = freshAi(s, number)
        val extras = ListingWriter.Extras(ai?.intro.orEmpty(), ai?.composition.orEmpty())
        val text = ListingWriter.text(listing, device.style, device.template, Sku.format(device.prefix, number, s.digits), ai?.hashtags, extras)
        // (also for titles generated before repeats were removed)
        val title = ai?.titles?.get(device.id)?.let(Ai::distinctWords)?.takeIf { it.isNotBlank() } ?: return text
        return text.copy(title = title)
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
            else replace(next, a.copy(delivered = a.delivered - id, uploaded = a.uploaded - id, vinted = a.vinted - id, accounts = a.accounts - id))
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

    /**
     * Vinted account of a phone: its user name and the profile link
     * ("https://www.vinted.es/member/123456-shiorawear"), from which the
     * member id is taken. A link without a name fills the name too.
     */
    fun setAccount(s: ParentState, id: String, account: String, profile: String): ParentState {
        val link = VintedWardrobe.profile(profile)
        if (profile.isNotBlank() && link == null) throw CatalogException("Enlace de perfil no válido")
        val name = account.trim().removePrefix("@").ifBlank { link?.second.orEmpty() }
        if (name.length > 40) throw CatalogException("Nombre de cuenta demasiado largo")
        if (name.isNotBlank() && devices(s).any { it.id != id && it.account.equals(name, ignoreCase = true) }) {
            throw CatalogException("Esa cuenta ya está en otro móvil")
        }
        fun Device.update() = copy(account = name, vintedId = link?.first.orEmpty())
        return if (id == s.self.id) s.copy(self = s.self.update())
        else s.copy(children = s.children.map { if (it.id == id) it.update() else it }, configVersion = s.configVersion + 1)
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
