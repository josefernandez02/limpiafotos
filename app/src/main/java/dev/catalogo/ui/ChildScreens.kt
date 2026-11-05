package dev.catalogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.AppState
import dev.catalogo.core.Sku

@Composable
fun ChildHome(vm: MainViewModel, state: AppState) {
    val counts by vm.counts.collectAsStateWithLifecycle()
    val child = state.child
    var sold by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            Bar("Catálogo", actions = {
                IconButton(onClick = { vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, contentDescription = "Ajustes") }
            })
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                BigButton(if (child == null) "Vincular" else "Conectar", onClick = { vm.go(Screen.Connect) })
            }
        },
    ) { padding ->
        if (child == null) {
            Empty("Sin vincular", Modifier.padding(padding))
            return@Scaffold
        }
        val albums = counts.mapNotNull { (album, n) -> Sku.parse(album, child.self.prefix, child.digits)?.let { it to n } }
            .sortedBy { it.first }
        val pending = child.pendingReleases.sorted()
        if (albums.isEmpty() && pending.isEmpty()) {
            Empty("Sin álbumes", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albums, key = { "a${it.first}" }) { (number, n) ->
                Tile {
                    Row2(
                        left = {
                            Column {
                                Text(Sku.format(child.self.prefix, number, child.digits), style = Mono.copy(fontSize = 18.sp))
                                Muted(if (n == 1) "1 foto" else "$n fotos")
                            }
                        },
                        right = {
                            TextButton(onClick = { sold = number }) { Text("Vendido", color = MaterialTheme.colorScheme.error) }
                        },
                    )
                }
            }
            items(pending.filter { p -> albums.none { it.first == p } }, key = { "p$it" }) { number ->
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
