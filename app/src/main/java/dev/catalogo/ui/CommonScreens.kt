package dev.catalogo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.catalogo.core.Garments
import dev.catalogo.core.Listing
import dev.catalogo.core.ListingWriter
import dev.catalogo.core.Measures
import dev.catalogo.core.ParentState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    // The transfer stops if the screen turns off: keep it on while this screen is open.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val outbox by vm.outbox.collectAsStateWithLifecycle()

    val done: () -> Unit = { if (isParent) vm.tab(Screen.Home) else vm.back() }
    Scaffold(
        topBar = {
            if (isParent) Header("Conectar", "Sincroniza fotos, anuncios y ventas")
            else Bar("Conectar", onBack = { vm.back() })
        },
        bottomBar = {
            Column {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (status.running || status.working) {
                        OutlinedButton(
                            onClick = { if (isParent) vm.sync.stop() else vm.back() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Cancelar") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = start,
                                modifier = Modifier.weight(1f).height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) { OneLine("Conectar de nuevo") }
                            Button(
                                onClick = done,
                                modifier = Modifier.weight(1f).height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) { OneLine("Hecho") }
                        }
                    }
                }
                if (isParent) MainTabs(vm, Screen.Connect) else Spacer(Modifier.navigationBarsPadding())
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val p = state.parent
            if (p != null && p.children.isNotEmpty()) {
                item {
                    Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in p.children) {
                            val photos = outbox[c.id]?.values?.sum() ?: 0
                            val releases = p.articles.count { it.released && c.id in it.pendingRelease }
                            val synced = status.log.any { it == "Listo: ${c.name}" }
                            Tile {
                                Row2(
                                    left = {
                                        Column {
                                            Text(c.label, style = MaterialTheme.typography.titleMedium)
                                            Muted(
                                                when {
                                                    synced && photos == 0 && releases == 0 -> "Al día"
                                                    photos == 0 && releases == 0 -> "Nada pendiente"
                                                    else -> listOfNotNull(
                                                        if (photos > 0) "$photos fotos por enviar" else null,
                                                        if (releases > 0) "$releases por quitar" else null,
                                                    ).joinToString(" · ")
                                                },
                                            )
                                        }
                                    },
                                    right = { if (synced) Pill("✓", strong = true) },
                                )
                            }
                        }
                    }
                }
            }
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
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (p != null) {
                Section("Cuentas y móviles")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in Catalog.devices(p)) {
                        Tile(onClick = { editing = d }) {
                            Row2(
                                left = {
                                    Column {
                                        Text(d.label, style = MaterialTheme.typography.titleMedium)
                                        Muted(
                                            listOfNotNull(
                                                d.name.takeIf { d.account.isNotBlank() },
                                                if (d.id == p.self.id) "Principal" else "Secundario",
                                                "estilo ${d.style + 1}",
                                                if (d.vintedId.isBlank()) "sin perfil de Vinted" else null,
                                            ).joinToString(" · "),
                                        )
                                    }
                                },
                                right = { Pill(d.prefix) },
                            )
                        }
                    }
                }
                Section("IA (Gemini)")
                GeminiKeyTile(vm, state.geminiKey)
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
                        right = { Pill(c.self.prefix) },
                    )
                    Muted("Se cambia desde el principal")
                }
            }
            if (c != null) {
                TextButton(onClick = { unpairing = true }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp)) {
                    Text("Desvincular", color = MaterialTheme.colorScheme.error)
                }
            } else if (p == null) {
                TextButton(onClick = { vm.resetRole() }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp)) {
                    Text("Cambiar rol", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    var accountOf by remember { mutableStateOf<Device?>(null) }
    accountOf?.let { d -> AccountDialog(vm, d) { accountOf = null } }

    val device = editing
    if (device != null && p != null) {
        val isChild = device.id != p.self.id
        var confirmRemove by remember(device.id) { mutableStateOf(false) }
        val extraButtons: @Composable () -> Unit = {
            Column {
                TextButton(onClick = { editing = null; accountOf = device }) { Text("Cuenta de Vinted") }
                TextButton(onClick = { editing = null; vm.go(Screen.Template(device.id)) }) { Text("Estilo y plantilla del anuncio") }
                if (isChild) {
                    TextButton(onClick = { confirmRemove = true }) { Text("Desvincular", color = MaterialTheme.colorScheme.error) }
                }
            }
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
            extra = extraButtons,
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

/** Vinted user name and profile link of the account a phone publishes in. */
@Composable
private fun AccountDialog(vm: MainViewModel, device: Device, onDismiss: () -> Unit) {
    var account by remember { mutableStateOf(device.account) }
    var profile by remember { mutableStateOf(if (device.vintedId.isBlank()) "" else "https://www.vinted.es/member/${device.vintedId}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cuenta de Vinted · ${device.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it.trim().take(40) },
                    label = { Text("Usuario") },
                    placeholder = { Text("shiorawear") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = profile,
                    onValueChange = { profile = it.trim().take(200) },
                    label = { Text("Enlace del perfil") },
                    placeholder = { Text("vinted.es/member/123456-shiorawear") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.setAccount(device.id, account, profile, onDismiss) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

// ---------- listing style and template ----------

private val SAMPLE = Listing(
    type = "jeans", brand = "Levi's", model = "501", size = "W31 L32",
    keywords = listOf("vintage", "90s", "straight", "faded"), flat = Measures(waist = "40", length = "104"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateScreen(vm: MainViewModel, p: ParentState, deviceId: String) {
    val device = Catalog.device(p, deviceId) ?: return
    var style by remember { mutableIntStateOf(device.style) }
    var text by remember { mutableStateOf(TextFieldValue(ListingWriter.upgrade(device.template, device.style))) }
    val sample = p.articles.firstOrNull { it.listing != null && !it.released }?.listing ?: SAMPLE
    val sku = Sku.format(device.prefix, 54, p.digits)

    fun insert(token: String) {
        val sel = text.selection
        val next = text.text.substring(0, sel.min) + token + text.text.substring(sel.max)
        text = TextFieldValue(next, TextRange(sel.min + token.length))
    }

    Scaffold(
        topBar = { Bar("Anuncio · ${device.label}", onBack = { vm.back() }) },
        bottomBar = {
            BottomActions {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { text = TextFieldValue(ListingWriter.defaultTemplate(style)) },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { OneLine("Restablecer") }
                    Button(
                        onClick = {
                            val custom = text.text.trim().takeIf { it != ListingWriter.defaultTemplate(style) } ?: ""
                            vm.saveStyle(device.id, style, custom)
                        },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { OneLine("Guardar") }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Estilo")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in 0 until Garments.STYLES) {
                    Choice("Estilo ${s + 1}", style == s) {
                        if (text.text.trim() == ListingWriter.defaultTemplate(style)) text = TextFieldValue(ListingWriter.defaultTemplate(s))
                        style = s
                    }
                }
            }

            Section("Descripción")
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (token in listOf("{talla}", "{medidas}", "{estado}", "{hashtags}", "{sku}")) Choice(token, false) { insert(token) }
            }

            Section("Vista previa")
            Tile {
                Text(ListingWriter.title(sample, style), style = MaterialTheme.typography.titleMedium)
                Text(
                    ListingWriter.description(sample, style, text.text, sku),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
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
