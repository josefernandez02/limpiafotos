package dev.catalogo.core.catalog

import dev.catalogo.core.ai.Ai
import dev.catalogo.core.ai.AiResult
import dev.catalogo.core.ai.AiSuggestion
import dev.catalogo.core.listing.Garments
import dev.catalogo.core.listing.Listing
import dev.catalogo.core.listing.ListingText
import dev.catalogo.core.listing.ListingWriter
import dev.catalogo.core.model.Article
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.PcTask
import dev.catalogo.core.model.Relist
import dev.catalogo.core.model.Sku
import dev.catalogo.core.model.Stage
import dev.catalogo.core.model.VintedAccount
import dev.catalogo.core.sales.Sales
import dev.catalogo.core.vinted.VintedProfile

class CatalogException(message: String) : Exception(message)

/** Pure state transitions of the parent's catalogue. No I/O. */
object Catalog {

    fun allAccounts(s: CatalogState): List<VintedAccount> = listOf(s.mainAccount) + s.otherAccounts

    fun findAccount(s: CatalogState, id: String): VintedAccount? = allAccounts(s).firstOrNull { it.id == id }

    fun article(s: CatalogState, number: Int): Article? = s.articles.firstOrNull { it.number == number }

    /** Lowest number not used by any article (released ones still pending included). */
    fun nextFree(s: CatalogState): Int? {
        val used = s.articles.mapTo(HashSet()) { it.number }
        return (0..Sku.maxNumber(s.digits)).firstOrNull { it !in used }
    }

    /** Article details: listing data, lot and manual cost. */
    fun updateArticle(s: CatalogState, number: Int, listing: Listing?, lotId: String?, cost: Long?, ai: AiResult? = null): CatalogState {
        val a = article(s, number) ?: return s
        if (lotId != null && s.lots.none { it.id == lotId }) throw CatalogException("Lote no encontrado")
        return replace(s, a.copy(listing = listing, lotId = lotId, cost = if (lotId != null) null else cost, ai = ai))
    }

    /** Price in cents [number] is listed at in Vinted. */
    fun setPrice(s: CatalogState, number: Int, price: Long?): CatalogState {
        val a = article(s, number) ?: return s
        if (price != null && price <= 0) throw CatalogException("Precio no válido")
        return replace(s, a.copy(price = price))
    }

    /** Photos of [number] the AI looks at, in order; empty = every photo of the album. */
    fun setPhotos(s: CatalogState, number: Int, photos: List<String>): CatalogState {
        val a = article(s, number) ?: return s
        val clean = photos.distinct()
        return if (a.photos == clean) s else replace(s, a.copy(photos = clean))
    }

    /**
     * The album's photos the AI looks at: the chosen ones in their order (those
     * deleted since are skipped), or all of them when nothing was chosen or none is left.
     */
    fun <T> chosenPhotos(album: List<T>, chosen: List<String>, id: (T) -> String): List<T> {
        if (chosen.isEmpty()) return album
        val byId = album.associateBy(id)
        return chosen.mapNotNull { byId[it] }.ifEmpty { album }
    }

    /**
     * One word for where [article] is in [vintedAccount]'s account. This phone's account
     * follows the PC (or what was set by hand); other accounts only have the mark
     * «subido» from their own phones.
     */
    fun stage(s: CatalogState, article: Article, vintedAccount: VintedAccount): Stage {
        if (article.held || article.vinted[vintedAccount.id]?.status == "sold") return Stage.SOLD
        val pcTask = article.pcTask.takeIf { vintedAccount.id == s.mainAccount.id }
        return when {
            pcTask?.status == "jugado" -> Stage.PLAYED
            pcTask?.status == "publicado" -> Stage.UPLOADED
            vintedAccount.id in article.uploaded -> Stage.UPLOADED
            pcTask == null -> Stage.TO_UPLOAD
            pcTask.status == "borrador" -> Stage.DRAFT
            pcTask.needsUser -> Stage.REVIEW
            else -> Stage.WORKING
        }
    }

    /** Where [number] stands on its way to the PC (null = never sent). */
    fun setPcTask(s: CatalogState, number: Int, pcTask: PcTask?): CatalogState {
        val a = article(s, number) ?: return s
        return if (a.pcTask == pcTask) s else replace(s, a.copy(pcTask = pcTask))
    }

    /** Applies an AI answer to [number]: listing data the user left empty and the titles. */
    fun applyAi(s: CatalogState, number: Int, suggestion: AiSuggestion, model: String, seconds: Int = 0): CatalogState {
        val a = article(s, number) ?: return s
        if (a.released) return s
        val applied = suggestion.applyTo(a.listing ?: Listing())
        return replace(s, a.copy(listing = applied, ai = suggestion.result(applied, model, seconds)))
    }

    /**
     * Lists [number] again, with new photos: everything the user filled in stays (listing, price, cost, lot, accounts);
     * what the old publication left behind goes: the AI's answer, the photo choice, the draft on the PC and this phone's
     * «subido» and wardrobe marks. The titles and hashtags it had are kept apart, as what the new ones must not repeat.
     */
    fun relist(s: CatalogState, number: Int): CatalogState {
        val a = article(s, number) ?: return s
        if (!a.inStock) return s
        val me = s.mainAccount
        // What it was published with is what the AI wrote (a draft is never sent without it); without AI data there is nothing to avoid.
        val avoid = a.relist ?: Relist(
            titles = a.ai?.titles.orEmpty().mapValues { Ai.distinctWords(it.value) }.filterValues { it.isNotBlank() },
            hashtags = a.ai?.hashtags.orEmpty(),
        )
        return replace(s, a.copy(
            ai = null, photos = emptyList(), pcTask = null,
            uploaded = a.uploaded - me.id, vinted = a.vinted - me.id,
            round = a.round + 1, relist = avoid,
        ))
    }

    /** The AI wrote titles and hashtags that differ: the re-listing no longer waits for them. */
    fun finishRelist(s: CatalogState, number: Int): CatalogState {
        val a = article(s, number) ?: return s
        return if (a.relist == null) s else replace(s, a.copy(relist = null))
    }

    /** AI data of [number] while it still matches the listing (null when stale or absent). */
    fun freshAi(s: CatalogState, number: Int): AiResult? {
        val a = article(s, number) ?: return null
        val listing = a.listing ?: return null
        return a.ai?.takeIf { it.basis == Ai.basis(listing) }
    }

    /** Accounts the AI writes titles for: every phone, in order. */
    fun aiAccounts(s: CatalogState): List<Ai.Account> = allAccounts(s).map { Ai.Account(it.id, it.prefix, it.style) }

    /** Marks whether [accountId]'s account already has [number] published on Vinted (and so is one of its accounts). */
    fun setUploaded(s: CatalogState, number: Int, accountId: String, uploaded: Boolean): CatalogState {
        val a = article(s, number) ?: return s
        if (!a.inStock || findAccount(s, accountId) == null) return s
        return replace(
            s,
            a.copy(
                uploaded = if (uploaded) a.uploaded + accountId else a.uploaded - accountId,
                accounts = if (uploaded) a.targets + accountId else a.targets,
            ),
        )
    }

    /** Accounts [number] will be published in. Unknown devices are ignored. */
    fun setAccounts(s: CatalogState, number: Int, accounts: Set<String>): CatalogState {
        val a = article(s, number) ?: return s
        if (!a.inStock) return s
        val known = allAccounts(s).map { it.id }.toSet()
        val chosen = accounts.filter { it in known }.toSet()
        return replace(s, a.copy(accounts = chosen, uploaded = a.uploaded.filter { it in chosen }.toSet()))
    }

    /**
     * Makes sure no secondary phone writes with the main phone's style (phones
     * paired before styles existed all had style 0).
     */
    fun ensureDistinctStyles(s: CatalogState): CatalogState {
        if (s.otherAccounts.none { it.style == s.mainAccount.style }) return s
        val other = (s.mainAccount.style + 1) % Garments.STYLES
        val otherAccounts = s.otherAccounts.map { c -> if (c.style != s.mainAccount.style) c else c.copy(style = other) }
        return s.copy(otherAccounts = otherAccounts)
    }

    /** Style for a newly paired phone: never the main phone's own style. */
    fun nextStyle(s: CatalogState): Int = (s.mainAccount.style + 1) % Garments.STYLES

    fun styleAndTemplate(s: CatalogState, id: String, style: Int, template: String): CatalogState {
        val clean = template.trim().take(ListingWriter.DESCRIPTION_MAX)
        return if (id == s.mainAccount.id) {
            s.copy(mainAccount = s.mainAccount.copy(style = style.mod(Garments.STYLES), template = clean))
        } else {
            s.copy(
                otherAccounts = s.otherAccounts.map { if (it.id == id) it.copy(style = style.mod(Garments.STYLES), template = clean) else it },
            )
        }
    }

    /** Title and description of [number] as [vintedAccount] publishes it. */
    fun listingText(s: CatalogState, number: Int, vintedAccount: VintedAccount): ListingText? {
        val listing = article(s, number)?.listing ?: return null
        val ai = freshAi(s, number)
        val text = ListingWriter.text(listing, vintedAccount.style, vintedAccount.template, Sku.format(vintedAccount.prefix, number, s.digits), ai?.hashtags)
        // (also for titles generated before repeats were removed)
        val title = ai?.titles?.get(vintedAccount.id)?.let(Ai::distinctWords)?.takeIf { it.isNotBlank() } ?: return text
        return text.copy(title = title)
    }

    fun create(s: CatalogState, now: Long): Pair<CatalogState, Int> {
        val number = nextFree(s) ?: throw CatalogException("No quedan números libres")
        val articles = (s.articles + Article(number, now)).sortedBy { it.number }
        return s.copy(articles = articles) to number
    }

    /**
     * Removes an article added by mistake, without a sale: its number is free at
     * once (other accounts have no phone of their own left to confirm anything).
     */
    fun delete(s: CatalogState, number: Int): CatalogState {
        val a = article(s, number) ?: return s
        if (a.held) throw CatalogException("Tiene una venta en curso: anúlala primero")
        // Its number becomes free, and the PC, if it has it, must drop it.
        return Sales.free(s, a)
    }

    fun addAccount(s: CatalogState, vintedAccount: VintedAccount): CatalogState {
        validateAccount(s, vintedAccount.id, vintedAccount.name, vintedAccount.prefix)
        return s.copy(otherAccounts = s.otherAccounts.filter { it.id != vintedAccount.id } + vintedAccount)
    }

    fun removeAccount(s: CatalogState, id: String): CatalogState {
        var next = s.copy(otherAccounts = s.otherAccounts.filter { it.id != id })
        for (a in next.articles) {
            next = if (a.released) settle(next, a.copy(pendingRelease = a.pendingRelease - id))
            else replace(next, a.copy(delivered = a.delivered - id, uploaded = a.uploaded - id, vinted = a.vinted - id, accounts = a.accounts - id))
        }
        return next
    }

    fun renameAccount(s: CatalogState, id: String, name: String, prefix: String): CatalogState {
        val cleanName = name.trim()
        val cleanPrefix = prefix.trim().uppercase()
        validateAccount(s, id, cleanName, cleanPrefix)
        return if (id == s.mainAccount.id) {
            s.copy(mainAccount = s.mainAccount.copy(name = cleanName, prefix = cleanPrefix))
        } else {
            val otherAccounts = s.otherAccounts.map { if (it.id == id) it.copy(name = cleanName, prefix = cleanPrefix) else it }
            s.copy(otherAccounts = otherAccounts)
        }
    }

    /**
     * Vinted account of a phone: its user name and the profile link
     * ("https://www.vinted.es/member/123456-shiorawear"), from which the
     * member id is taken. A link without a name fills the name too.
     */
    fun setVintedProfile(s: CatalogState, id: String, account: String, profile: String): CatalogState {
        val link = VintedProfile.parse(profile)
        if (profile.isNotBlank() && link == null) throw CatalogException("Enlace de perfil no válido")
        val name = account.trim().removePrefix("@").ifBlank { link?.second.orEmpty() }
        if (name.length > 40) throw CatalogException("Nombre de cuenta demasiado largo")
        if (name.isNotBlank() && allAccounts(s).any { it.id != id && it.vintedName.equals(name, ignoreCase = true) }) {
            throw CatalogException("Esa cuenta ya está en otro móvil")
        }
        fun VintedAccount.update() = copy(vintedName = name, vintedId = link?.first.orEmpty())
        return if (id == s.mainAccount.id) s.copy(mainAccount = s.mainAccount.update())
        else s.copy(otherAccounts = s.otherAccounts.map { if (it.id == id) it.update() else it })
    }

    fun setDigits(s: CatalogState, digits: Int): CatalogState {
        if (digits !in Sku.MIN_DIGITS..Sku.MAX_DIGITS) throw CatalogException("Entre ${Sku.MIN_DIGITS} y ${Sku.MAX_DIGITS} dígitos")
        val highest = s.articles.maxOfOrNull { it.number } ?: -1
        if (highest > Sku.maxNumber(digits)) throw CatalogException("Hay artículos que no caben en $digits dígitos")
        if (digits == s.digits) return s
        return s.copy(digits = digits)
    }

    fun validateAccount(s: CatalogState, id: String, name: String, prefix: String) {
        if (name.isBlank() || name.length > 24) throw CatalogException("Nombre de 1 a 24 caracteres")
        if (!Sku.isValidPrefix(prefix)) throw CatalogException("Prefijo de 1 a 4 letras (A-Z)")
        if (allAccounts(s).any { it.id != id && it.prefix == prefix }) throw CatalogException("Ese prefijo ya está en uso")
        if (allAccounts(s).any { it.id != id && it.name.equals(name, ignoreCase = true) }) throw CatalogException("Ese nombre ya está en uso")
    }

    private fun settle(s: CatalogState, a: Article): CatalogState =
        if (a.released && a.pendingRelease.isEmpty()) s.copy(articles = s.articles.filter { it.number != a.number })
        else replace(s, a)

    private fun replace(s: CatalogState, a: Article): CatalogState =
        s.copy(articles = s.articles.map { if (it.number == a.number) a else it })
}
