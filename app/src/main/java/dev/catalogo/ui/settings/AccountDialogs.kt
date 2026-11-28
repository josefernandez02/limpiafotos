package dev.catalogo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.catalogo.core.model.VintedAccount
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.common.Muted

/** Name + prefix form. [onConfirm] returns an error to show, or null when accepted. */
@Composable
fun AccountDialog(
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
internal fun VintedProfileDialog(vm: MainViewModel, vintedAccount: VintedAccount, onDismiss: () -> Unit) {
    var account by remember { mutableStateOf(vintedAccount.vintedName) }
    var profile by remember { mutableStateOf(if (vintedAccount.vintedId.isBlank()) "" else "https://www.vinted.es/member/${vintedAccount.vintedId}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cuenta de Vinted de ${vintedAccount.name}") },
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
        confirmButton = { TextButton(onClick = { vm.setVintedProfile(vintedAccount.id, account, profile, onDismiss) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

// ---------- listing style and template ----------
