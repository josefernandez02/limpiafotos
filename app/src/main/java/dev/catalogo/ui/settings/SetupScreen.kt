package dev.catalogo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.common.Muted

@Composable
fun SetupScreen(vm: MainViewModel) {
    var ask by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Text("Catálogo", style = MaterialTheme.typography.headlineLarge)
            Muted("Haz las fotos, deja que la IA escriba el anuncio y el PC lo sube a Vinted.", Modifier.padding(bottom = 24.dp), maxLines = 4)
            Button(
                onClick = { ask = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Empezar") }
            Spacer(Modifier.weight(1f))
        }
    }
    if (ask) {
        AccountDialog(
            title = "Tu cuenta principal",
            subtitle = "El prefijo es el principio del código de cada artículo (SH0045).",
            initialName = "Samsung",
            initialPrefix = "SH",
            confirm = "Crear",
            onDismiss = { ask = false },
            onConfirm = { name, prefix -> vm.setupMainPhone(name, prefix); null },
        )
    }
}

// ---------- settings ----------
