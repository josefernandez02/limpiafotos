package dev.catalogo.ui.sales

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.listing.Garments
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.Money
import dev.catalogo.core.model.Sale
import dev.catalogo.core.model.SaleStatus
import dev.catalogo.core.model.Sku
import dev.catalogo.core.sales.Sales
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.Screen
import dev.catalogo.ui.common.Mono
import dev.catalogo.ui.common.Muted
import dev.catalogo.ui.common.Tile
import dev.catalogo.ui.common.Tone
import dev.catalogo.ui.common.loadPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val SaleGreen = Color(0xFF6CC48A)

/** The colour and word a sale is recognised by. */
@Composable
fun saleTone(sale: Sale): Pair<Color, String> = when {
    sale.conflict -> MaterialTheme.colorScheme.error to "Revisar"
    sale.status == SaleStatus.PENDING -> Tone.Warn.color to sale.status.label
    sale.status == SaleStatus.COMPLETED -> SaleGreen to sale.status.label
    else -> Tone.Idle.color to sale.status.label
}

@Composable
fun SaleChip(sale: Sale) {
    val (color, text) = saleTone(sale)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, softWrap = false)
    }
}

/** «SH0012», or null while nobody knows which article it was. */
fun saleCode(p: CatalogState, sale: Sale): String? = sale.code.ifBlank {
    if (sale.number < 0) "" else Sku.format(Catalog.findAccount(p, sale.accountId)?.prefix ?: p.mainAccount.prefix, sale.number, p.digits)
}.ifBlank { null }

fun saleTitle(sale: Sale): String = sale.title.ifBlank {
    listOf(sale.brand, Garments.type(sale.type)?.label.orEmpty()).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Venta" }
}

/** The picture of a sale: its article's cover while there is one, else the one kept or Vinted's. */
@Composable
fun SaleImage(vm: MainViewModel, p: CatalogState, sale: Sale, modifier: Modifier, edge: Int = 256) {
    val covers by vm.articleCovers.collectAsStateWithLifecycle()
    val cover: Uri? = Sales.article(p, sale)?.let { covers[it.number] }
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, sale.id, sale.photo, cover, edge) {
        val source = cover ?: vm.saleImage(sale)?.let(Uri::fromFile)
        value = source?.let { uri -> withContext(Dispatchers.IO) { runCatching { loadPhoto(context, uri, edge) }.getOrNull() } }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val picture = bitmap
        if (picture != null) {
            Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // Its photos are gone: the code is what recognises it.
            saleCode(p, sale)?.let {
                Text(
                    it, style = Mono.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/** A Vinted order about a garment already sold by hand: the user is told, nothing is touched. */
@Composable
fun DoubtCard(vm: MainViewModel, p: CatalogState, order: Sale, hand: Sale) {
    Tile {
        Text("Ya la marcaste como vendida", style = MaterialTheme.typography.titleMedium, color = Tone.Warn.color)
        Text(saleTitle(order), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        for ((from, sale) in listOf("En Vinted" to order, "Apuntada a mano" to hand)) {
            Row(Modifier.fillMaxWidth()) {
                Muted(listOfNotNull(from, saleCode(p, sale), DAY_FORMAT.format(date(sale.soldAt))).joinToString(" · "), Modifier.weight(1f))
                Text(Money.format(sale.price), style = Mono.copy(fontSize = 14.sp), maxLines = 1, softWrap = false)
            }
        }
        Muted("Si la vendiste fuera, cancela el pedido en Vinted: el aviso se quitará cuando el PC lo lea.")
        FilledTonalButton(onClick = { vm.mergeSale(order.id) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Es la misma", maxLines = 1)
        }
    }
}

/** Where a sale is, in the words of Vinted while it is on its way. */
private fun saleState(sale: Sale): String = when {
    sale.conflict -> "para revisar"
    sale.status == SaleStatus.PENDING -> sale.detail.trim().trimEnd('.').ifBlank { "en curso" }
    sale.status == SaleStatus.COMPLETED -> "cobrada"
    else -> "anulada"
}

/** Photo, then three lines that are never cut short: title and price, code and account with profit, date and state. */
@Composable
internal fun SaleCard(vm: MainViewModel, p: CatalogState, sale: Sale) {
    val cancelled = sale.status == SaleStatus.CANCELLED
    Tile(onClick = { vm.go(Screen.Sale(sale.id)) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SaleImage(vm, p, sale, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        saleTitle(sale), style = MaterialTheme.typography.titleSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(
                        Money.format(sale.price),
                        style = Mono.copy(fontSize = 16.sp, textDecoration = if (cancelled) TextDecoration.LineThrough else TextDecoration.None),
                        color = if (cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, softWrap = false,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Muted(
                        listOfNotNull(saleCode(p, sale) ?: "Sin código", Catalog.findAccount(p, sale.accountId)?.label).joinToString(" · "),
                        Modifier.weight(1f),
                    )
                    val cost = sale.cost
                    if (!cancelled && cost != null) {
                        val profit = sale.price - cost
                        Text(
                            (if (profit > 0) "+" else "") + Money.format(profit),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (profit > 0) SaleGreen else MaterialTheme.colorScheme.error,
                            maxLines = 1, softWrap = false,
                        )
                    }
                }
                Text(
                    "${DAY_FORMAT.format(date(sale.soldAt))} · ${saleState(sale)}",
                    style = MaterialTheme.typography.bodySmall, color = saleTone(sale).first,
                )
            }
        }
    }
}
