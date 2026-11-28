package dev.catalogo.ui.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.catalogo.core.model.PcLink
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.common.Confirm
import dev.catalogo.ui.common.Muted
import dev.catalogo.ui.common.OneLine
import dev.catalogo.ui.common.Row2
import dev.catalogo.ui.common.Tile

/** Settings: address and key of the PC (stay on this phone). */
@Composable
fun PcSettingsTile(vm: MainViewModel, saved: PcLink?) {
    var editing by remember(saved) { mutableStateOf(saved == null) }
    var url by remember(saved) { mutableStateOf(saved?.url ?: "http://") }
    var token by remember(saved) { mutableStateOf("") }
    Tile {
        if (!editing && saved != null) {
            Row2(
                left = {
                    Column {
                        Text(saved.url.removePrefix("http://"), style = MaterialTheme.typography.titleMedium)
                        Muted("Clave •••• ${saved.token.takeLast(4)}")
                    }
                },
                right = { TextButton(onClick = { editing = true }) { Text("Cambiar") } },
            )
            return@Tile
        }
        Muted("Dirección del PC con el puerto (Tailscale o wifi de casa), p. ej. 100.64.0.1:8765, y la clave de config.toml")
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim().take(80) },
            label = { Text("Dirección") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim().take(100) },
            label = { Text("Clave") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saved != null) {
                OutlinedButton(onClick = { editing = false }, modifier = Modifier.weight(1f)) { OneLine("Cancelar") }
            }
            FilledTonalButton(
                onClick = { vm.savePc(url, token) { editing = false } },
                enabled = url.length > 10 && token.length >= 16,
                modifier = Modifier.weight(1f),
            ) { OneLine("Probar y guardar") }
        }
        if (saved != null) {
            var forgetting by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { forgetting = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Desconectar el PC", color = MaterialTheme.colorScheme.error) }
            if (forgetting) {
                Confirm(
                    title = "¿Desconectar el PC?",
                    text = "Este móvil dejará de mandarle artículos hasta que lo vuelvas a configurar.",
                    confirm = "Desconectar",
                    onConfirm = { vm.forgetPc() },
                    onDismiss = { forgetting = false },
                    destructive = true,
                )
            }
        }
    }
}
