package dev.catalogo.ui.sales

import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.Sale
import dev.catalogo.core.sales.Sales as SalesRules
import dev.catalogo.data.pc.tellPc
import dev.catalogo.ui.MainViewModel

fun MainViewModel.showSales(view: SalesView) {
    _salesView.value = view
}

fun MainViewModel.mergeSale(id: String) = sale("Unidas en una sola venta") { SalesRules.merge(it, id) }

fun MainViewModel.completeSale(id: String) = sale("Venta completada") { SalesRules.complete(it, id, System.currentTimeMillis()) }

fun MainViewModel.cancelSale(id: String) = sale("Venta anulada") { SalesRules.cancel(it, id, System.currentTimeMillis()) }

fun MainViewModel.releaseSale(id: String) = sale("Número libre: la venta sigue en curso") { SalesRules.release(it, id) }

fun MainViewModel.unpaySale(id: String) = sale("Venta en curso: pendiente de cobro") { SalesRules.unpay(it, id) }

fun MainViewModel.reopenSale(id: String) = sale("Venta recuperada: en curso") { SalesRules.reopen(it, id) }

fun MainViewModel.acceptSale(id: String) = sale("Venta completada") { SalesRules.accept(it, id, System.currentTimeMillis()) }

fun MainViewModel.assignSale(id: String, number: Int) = sale("Artículo asignado") { SalesRules.assign(it, id, number) }

/** The picture kept from a sold article, if any. */
suspend fun MainViewModel.saleImage(sale: Sale): java.io.File? = repo.saleImages.get(sale.id)

private fun MainViewModel.sale(done: String, change: (CatalogState) -> CatalogState) = run {
    askToDelete(repo.changeSales(change))
    _message.value = done
    app.pcSync.kick()
    refresh()
    app.pcSync.tellPc()
}
