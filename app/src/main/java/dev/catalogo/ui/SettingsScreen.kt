package dev.catalogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import dev.catalogo.core.AppState
import dev.catalogo.core.Catalog
import dev.catalogo.core.VintedAccount
import dev.catalogo.core.Sku

@Composable
fun SettingsScreen(vm: MainViewModel, state: AppState) {
    var editing by remember { mutableStateOf<VintedAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    val p = state.catalog

    Scaffold(topBar = { Bar("Ajustes", onBack = { vm.back() }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (p != null) {
                Section("Cuentas de Vinted")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in Catalog.allAccounts(p)) {
                        Tile(onClick = { editing = d }) {
                            Row2(
                                left = {
                                    Column {
                                        Text(d.label, style = MaterialTheme.typography.titleMedium)
                                        Muted(
                                            listOfNotNull(
                                                d.name.takeIf { d.vintedName.isNotBlank() },
                                                if (d.id == p.mainAccount.id) "Principal" else "Otra cuenta",
                                                "estilo ${d.style + 1}",
                                                if (d.vintedId.isBlank()) "sin perfil de Vinted" else null,
                                            ).joinToString(GAP),
                                        )
                                    }
                                },
                                right = { Pill(d.prefix) },
                            )
                        }
                    }
                }
                TextButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Añadir otra cuenta") }
                Section("IA (Gemini)")
                GeminiKeyTile(vm, state.geminiKey)
                Section("Juegos")
                GamesPanel(vm, state.gamesOff)
                Section("PC (crea los borradores)")
                PcSettingsTile(vm, state.pc)
                Section("SKU")
                Tile {
                    Row2(
                        left = {
                            Column {
                                Text("Dígitos", style = MaterialTheme.typography.titleMedium)
                                Muted("${Sku.number(0, p.digits)} – ${Sku.number(Sku.maxNumber(p.digits), p.digits)}")
                            }
                        },
                        right = {
                            FilledTonalIconButton(onClick = { vm.setDigits(p.digits - 1) }, enabled = p.digits > Sku.MIN_DIGITS) { Text("−", fontSize = 20.sp) }
                            Text(" ${p.digits} ", style = Mono.copy(fontSize = 18.sp))
                            FilledTonalIconButton(onClick = { vm.setDigits(p.digits + 1) }, enabled = p.digits < Sku.MAX_DIGITS) { Text("+", fontSize = 20.sp) }
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    var accountOf by remember { mutableStateOf<VintedAccount?>(null) }
    accountOf?.let { d -> VintedProfileDialog(vm, d) { accountOf = null } }

    val vintedAccount = editing
    if (vintedAccount != null && p != null) {
        val isOtherAccount = vintedAccount.id != p.mainAccount.id
        var confirmRemove by remember(vintedAccount.id) { mutableStateOf(false) }
        val extraButtons: @Composable () -> Unit = {
            Column {
                TextButton(onClick = { editing = null; accountOf = vintedAccount }) { Text("Cuenta de Vinted") }
                TextButton(onClick = { editing = null; vm.go(Screen.Template(vintedAccount.id)) }) { Text("Estilo y plantilla del anuncio") }
                if (isOtherAccount) {
                    TextButton(onClick = { confirmRemove = true }) { Text("Quitar cuenta", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        AccountDialog(
            title = vintedAccount.name,
            initialName = vintedAccount.name,
            initialPrefix = vintedAccount.prefix,
            confirm = "Guardar",
            onDismiss = { editing = null },
            onConfirm = { name, prefix ->
                try {
                    Catalog.validateAccount(p, vintedAccount.id, name.trim(), prefix.trim().uppercase())
                    vm.renameAccount(vintedAccount.id, name, prefix) { editing = null }
                    null
                } catch (e: Exception) {
                    e.message
                }
            },
            extra = extraButtons,
        )
        if (confirmRemove) {
            Confirm(
                title = "Quitar ${vintedAccount.label}",
                text = "Dejará de estar entre tus cuentas. Sus álbumes no se borran.",
                confirm = "Quitar",
                destructive = true,
                onConfirm = { vm.removeAccount(vintedAccount.id); editing = null },
                onDismiss = { confirmRemove = false },
            )
        }
    }

    if (adding && p != null) {
        AccountDialog(
            title = "Otra cuenta",
            subtitle = "La que publica otro PC. El prefijo abre el código de sus artículos.",
            initialName = "",
            initialPrefix = "",
            confirm = "Añadir",
            onDismiss = { adding = false },
            onConfirm = { name, prefix -> vm.addAccount(name, prefix).also { if (it == null) adding = false } },
        )
    }
}

@Composable
private fun GeminiKeyTile(vm: MainViewModel, saved: String) {
    var editing by remember(saved) { mutableStateOf(saved.isBlank()) }
    var key by remember(saved) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    Tile {
        if (!editing) {
            Row2(
                left = {
                    Column {
                        Text("Clave guardada", style = MaterialTheme.typography.titleMedium)
                        Muted("•••• ${saved.takeLast(4)}")
                    }
                },
                right = { TextButton(onClick = { editing = true }) { Text("Cambiar") } },
            )
            return@Tile
        }
        if (saved.isBlank()) Muted("aistudio.google.com → Get API key")
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim().take(100) },
            label = { Text(if (saved.isBlank()) "Clave de Gemini" else "Clave nueva") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saved.isNotBlank()) {
                OutlinedButton(onClick = { editing = false; key = "" }, modifier = Modifier.weight(1f)) { OneLine("Cancelar") }
            }
            FilledTonalButton(onClick = { vm.setGeminiKey(key) }, enabled = key.length >= 20 && key != saved, modifier = Modifier.weight(1f)) {
                Text("Guardar")
            }
        }
        if (saved.isNotBlank()) {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Quitar la clave", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Quitar la clave?") },
            text = { Text("La IA dejará de funcionar hasta que pongas otra.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.setGeminiKey("") }) {
                    Text("Quitar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}
