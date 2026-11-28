package dev.catalogo.core.vinted

import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.VintedItem

/** An article known to be listed in Vinted (data kept from earlier versions of the app). */
fun inVinted(s: CatalogState, number: Int, accountId: String, itemId: String, price: Long, status: String): CatalogState =
    s.copy(articles = s.articles.map { a ->
        if (a.number != number) a
        else a.copy(
            uploaded = a.uploaded + accountId,
            accounts = a.targets + accountId,
            vinted = a.vinted + (accountId to VintedItem(itemId, price, 1, 1, status)),
        )
    })
