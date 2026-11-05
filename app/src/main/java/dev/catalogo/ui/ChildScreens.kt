package dev.catalogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.AppState
import dev.catalogo.core.Sku

@Composable
fun ChildHome(vm: MainViewModel, state: AppState) {
    val counts by vm.counts.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()
    val child = state.child
    var sold by remember { mutableStateOf<Int?>(null) }

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
            Empty("Sin álbumes", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(albums, key = { "a${it.first}" }) { (number, n) ->
                val sku = Sku.format(child.self.prefix, number, child.digits)
                Tile {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(covers[sku])
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(sku, style = Mono.copy(fontSize = 18.sp))
                            Muted(if (n == 1) "1 foto" else "$n fotos")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { vm.stage(number) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Subir") }
                        TextButton(onClick = { sold = number }, modifier = Modifier.weight(1f)) {
                            Text("Vendido", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            items(pending, key = { "p$it" }) { number ->
                Tile {
                    Text(Sku.format(child.self.prefix, number, child.digits), style = Mono.copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Muted("Vendido · pendiente de sincronizar")
                }
            }
        }
    }

    val number = sold
    if (number != null && child != null) {
        Confirm(
            title = "Vendido",
            text = "Se borrarán las fotos de ${Sku.number(number, child.digits)} en todos los móviles.",
            confirm = "Borrar",
            destructive = true,
            onConfirm = { vm.releaseOnChild(number) },
            onDismiss = { sold = null },
        )
    }
}
