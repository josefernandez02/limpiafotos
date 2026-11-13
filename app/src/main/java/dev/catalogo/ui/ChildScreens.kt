package dev.catalogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.AppState
import dev.catalogo.core.ChildState
import dev.catalogo.core.Sku

@Composable
fun ChildHome(vm: MainViewModel, state: AppState) {
    val counts by vm.counts.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()
    val child = state.child

    val albums = if (child == null) emptyList() else counts
        .mapNotNull { (album, n) -> Sku.parse(album, child.self.prefix, child.digits)?.let { it to n } }
        .sortedByDescending { it.first }
    val pending = child?.pendingReleases?.sorted()?.filter { p -> albums.none { it.first == p } } ?: emptyList()

    Scaffold(
        topBar = {
            Header(
                "Catálogo",
                if (child == null) "Sin vincular" else if (albums.size == 1) "1 álbum" else "${albums.size} álbumes",
            ) {
                IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, contentDescription = "Ajustes") }
            }
        },
        bottomBar = {
            BottomActions {
                BigButton(if (child == null) "Vincular" else "Conectar", onClick = { vm.go(Screen.Connect) })
            }
        },
    ) { padding ->
        if (child == null) {
            Empty("Vincula este móvil con el principal", Modifier.padding(padding))
            return@Scaffold
        }
        if (albums.isEmpty() && pending.isEmpty()) {
            Empty("Sin álbumes todavía", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(albums, key = { "a${it.first}" }) { (number, n) ->
                val sku = Sku.format(child.self.prefix, number, child.digits)
                Tile(onClick = { vm.go(Screen.Album(number)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Cover(covers[sku])
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(sku, style = Mono.copy(fontSize = 18.sp))
                            val title = child.listings[number]?.title
                            if (title != null) Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(if (n == 1) "1 foto" else "$n fotos")
                        }
                    }
                }
            }
            items(pending, key = { "p$it" }) { number ->
                Tile {
                    Text(Sku.format(child.self.prefix, number, child.digits), style = Mono.copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Muted(if (number in child.salePrices) "Vendido. Conecta para terminar" else "Eliminado. Conecta para terminar")
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(vm: MainViewModel, child: ChildState, number: Int) {
    val counts by vm.counts.collectAsStateWithLifecycle()
    val sku = Sku.format(child.self.prefix, number, child.digits)
    val n = counts[sku] ?: 0
    val strip by produceState(emptyList<android.net.Uri>(), sku, n) { value = vm.albumPhotos(sku) }
    var selling by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Bar(sku, onBack = { vm.back() }) {
                IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, contentDescription = "Eliminar") }
            }
        },
        bottomBar = {
            BottomActions {
                OutlinedButton(
                    onClick = { selling = true },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                ) { Text("Vendido", color = MaterialTheme.colorScheme.error) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section("Fotos")
            Tile {
                Muted(if (n == 1) "1 foto" else "$n fotos")
                PhotoStrip(strip, onOpen = { vm.openViewer(strip, it) }, modifier = Modifier.padding(top = 10.dp))
                FilledTonalButton(
                    onClick = { vm.stage(number) },
                    enabled = n > 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { OneLine("Subir") }
            }
            Section("Anuncio")
            ListingCard(vm, child.listings[number])
        }
    }

    if (deleting) {
        Confirm(
            title = "Eliminar $sku",
            text = "Se borran sus fotos en todos los móviles, sin contar como venta.",
            confirm = "Eliminar",
            destructive = true,
            onConfirm = { vm.deleteOnChild(number) },
            onDismiss = { deleting = false },
        )
    }

    if (selling) {
        SellDialog(
            title = "Vender $sku",
            initialCost = null,
            costHint = null,
            onDismiss = { selling = false },
            onConfirm = { price, _ -> vm.sellOnChild(number, price) },
        )
    }
}
