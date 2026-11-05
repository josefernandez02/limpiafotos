package dev.catalogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.AppState
import dev.catalogo.core.Catalog
import dev.catalogo.core.Device
import dev.catalogo.core.Sku

// ---------- first run ----------

@Composable
fun SetupScreen(vm: MainViewModel) {
    var askParent by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Text("Catálogo", style = MaterialTheme.typography.headlineMedium)
            Muted("¿Qué hace este móvil?", Modifier.padding(bottom = 16.dp))
            Tile(onClick = { askParent = true }) {
                Text("Principal", style = MaterialTheme.typography.titleMedium)
                Muted("Hace las fotos y las reparte")
            }
            Tile(onClick = { vm.setupChild() }) {
                Text("Secundario", style = MaterialTheme.typography.titleMedium)
                Muted("Recibe sus fotos del principal")
            }
            Spacer(Modifier.weight(1f))
        }
    }
    if (askParent) {
        DeviceDialog(
            title = "Este móvil",
            initialName = "Samsung",
            initialPrefix = "SH",
            confirm = "Crear",
            onDismiss = { askParent = false },
            onConfirm = { name, prefix -> vm.setupParent(name, prefix); null },
        )
    }
}

// ---------- connect ----------

@Composable
fun ConnectScreen(vm: MainViewModel, state: AppState) {
    val status by vm.sync.status.collectAsStateWithLifecycle()
    val isParent = state.parent != null
    val start = rememberWithPermissions(Perms.nearby, onDenied = { vm.say("Permite el acceso a dispositivos cercanos") }) {
        if (isParent) vm.sync.startParent() else vm.sync.startChild()
    }
    LaunchedEffect(Unit) { start() }

    Scaffold(
        topBar = { Bar("Conectar", onBack = { vm.back() }) },
        bottomBar = {
            BottomActions {
                if (status.running || status.working) {
                    OutlinedButton(
                        onClick = { vm.back() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Cancelar") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = start,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Conectar de nuevo") }
                        Button(
                            onClick = { vm.back() },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Hecho") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(status.log) { line -> Text(line, style = MaterialTheme.typography.bodyLarge) }
            if (status.running && !status.working) {
                item {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Muted(if (isParent) "  Abre Conectar en el otro móvil" else "  Mantén el principal en Conectar")
                    }
                }
            }
        }
    }

    val request = status.pairRequest
    if (request != null) {
        if (isParent) {
            DeviceDialog(
                title = "Vincular · ${request.code}",
                subtitle = "Comprueba que el código coincide en el otro móvil.",
                initialName = "",
                initialPrefix = "",
                confirm = "Vincular",
                onDismiss = { vm.sync.rejectPairing() },
                onConfirm = { name, prefix -> vm.sync.approvePairing(name, prefix) },
            )
        } else {
            AlertDialog(
                onDismissRequest = { vm.sync.rejectPairing() },
                title = { Text("Código ${request.code}") },
                text = { Text("Comprueba que coincide en el principal.") },
                confirmButton = { TextButton(onClick = { vm.sync.confirmPairing() }) { Text("Coincide") } },
                dismissButton = { TextButton(onClick = { vm.sync.rejectPairing() }) { Text("Cancelar") } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
    }
}

// ---------- settings ----------

@Composable
fun SettingsScreen(vm: MainViewModel, state: AppState) {
    var editing by remember { mutableStateOf<Device?>(null) }
    var unpairing by remember { mutableStateOf(false) }
    val p = state.parent
    val c = state.child

    Scaffold(topBar = { Bar("Ajustes", onBack = { vm.back() }) }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize()) {
            if (p != null) {
                Section("Dispositivos")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in Catalog.devices(p)) {
                        Tile(onClick = { editing = d }) {
                            Row2(
                                left = { Text(d.name, style = MaterialTheme.typography.titleMedium) },
                                right = { Text(Sku.format(d.prefix, 54, p.digits), style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            )
                        }
                    }
                }
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
            if (c != null) {
                Section("Este móvil")
                Tile {
                    Row2(
                        left = { Text(c.self.name, style = MaterialTheme.typography.titleMedium) },
                        right = { Text(Sku.format(c.self.prefix, 54, c.digits), style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    Muted("Se cambia desde el principal")
                }
            }
            Section("Carpeta de álbumes")
            Tile {
                for (folder in AppState.BASE_FOLDERS) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = state.baseFolder == folder, onClick = { vm.setBaseFolder(folder) })
                        Text(folder, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (c != null) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { unpairing = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text("Desvincular", color = MaterialTheme.colorScheme.error)
                }
            } else if (p == null) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.resetRole() }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text("Cambiar rol", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    val device = editing
    if (device != null && p != null) {
        val isChild = device.id != p.self.id
        var confirmRemove by remember(device.id) { mutableStateOf(false) }
        val removeButton: @Composable () -> Unit = {
            TextButton(onClick = { confirmRemove = true }) { Text("Desvincular", color = MaterialTheme.colorScheme.error) }
        }
        DeviceDialog(
            title = device.name,
            initialName = device.name,
            initialPrefix = device.prefix,
            confirm = "Guardar",
            onDismiss = { editing = null },
            onConfirm = { name, prefix ->
                try {
                    Catalog.validateDevice(p, device.id, name.trim(), prefix.trim().uppercase())
                    vm.renameDevice(device.id, name, prefix) { editing = null }
                    null
                } catch (e: Exception) {
                    e.message
                }
            },
            extra = if (isChild) removeButton else null,
        )
        if (confirmRemove) {
            Confirm(
                title = "Desvincular ${device.name}",
                text = "Dejará de recibir fotos. Sus álbumes no se borran.",
                confirm = "Desvincular",
                destructive = true,
                onConfirm = { vm.removeChild(device.id); editing = null },
                onDismiss = { confirmRemove = false },
            )
        }
    }

    if (unpairing) {
        Confirm(
            title = "Desvincular",
            text = "Este móvil dejará de recibir fotos. Los álbumes no se borran.",
            confirm = "Desvincular",
            destructive = true,
            onConfirm = { vm.unpair() },
            onDismiss = { unpairing = false },
        )
    }
}

/** Name + prefix form. [onConfirm] returns an error to show, or null when accepted. */
@Composable
fun DeviceDialog(
    title: String,
    initialName: String,
    initialPrefix: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> String?,
    subtitle: String? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var prefix by remember { mutableStateOf(initialPrefix) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (subtitle != null) Muted(subtitle)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24); error = null },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { v -> prefix = v.uppercase().filter { it in 'A'..'Z' }.take(4); error = null },
                    label = { Text("Prefijo SKU") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (extra != null) {
                    Spacer(Modifier.height(4.dp))
                    extra()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onConfirm(name, prefix) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
