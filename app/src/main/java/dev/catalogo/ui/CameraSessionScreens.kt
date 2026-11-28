package dev.catalogo.ui





import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.Catalog
import dev.catalogo.core.CatalogState
import dev.catalogo.core.Sku

internal fun openCamera(context: Context, vm: MainViewModel) {
    try {
        context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        vm.say("No se encontró la cámara")
    }
}


// ---------- Artículos (tab) ----------

@Composable
fun CameraSessionScreen(vm: MainViewModel, p: CatalogState) {
    val context = LocalContext.current
    val session = p.session ?: return
    val vintedAccount = Catalog.findAccount(p, session.accountId)
    val sku = Sku.format(vintedAccount?.prefix ?: "", session.number, p.digits)
    var confirmCancel by remember { mutableStateOf(false) }
    val withMedia = rememberWithPermissions(Perms.media, onDenied = { vm.say("Permite el acceso a las fotos") }) { vm.finishSession() }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Muted(vintedAccount?.name ?: "")
            Text(sku, style = Mono.copy(fontSize = 40.sp), modifier = Modifier.padding(vertical = 8.dp))
            Text(
                "Haz las fotos y edítalas si quieres.\nLuego vuelve y pulsa Terminar.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            BigButton("Abrir cámara", onClick = { openCamera(context, vm) })
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = withMedia,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Terminar") }
            TextButton(onClick = { confirmCancel = true }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Cancelar", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmCancel) {
        Confirm(
            title = "Cancelar",
            text = "Las fotos hechas se quedan en la galería sin tocar.",
            confirm = "Cancelar sesión",
            onConfirm = { vm.cancelSession() },
            onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
fun SessionPhotosScreen(vm: MainViewModel, p: CatalogState) {
    val photos by vm.review.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val session = p.session
    val vintedAccount = session?.let { Catalog.findAccount(p, it.accountId) }
    val title = if (session != null && vintedAccount != null) Sku.format(vintedAccount.prefix, session.number, p.digits) else ""
    val goBack: () -> Unit = { vm.back() }

    Scaffold(
        topBar = { Bar(title, onBack = if (progress == null) goBack else null) },
        bottomBar = {
            BottomActions {
                val current = progress
                if (current != null) {
                    Muted("Guardando ${current.first} de ${current.second}", Modifier.padding(bottom = 8.dp))
                    LinearProgressIndicator(
                        progress = { current.first.toFloat() / current.second.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    BigButton("Guardar ${selected.size}", onClick = { vm.saveReview() }, enabled = selected.isNotEmpty())
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(photos, key = { it.toString() }) { uri ->
                val on = uri in selected
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(shape)
                        .border(2.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background, shape)
                        .clickable(enabled = progress == null) { vm.toggle(uri) },
                ) {
                    Thumb(uri, Modifier.fillMaxSize())
                    if (on) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                    } else {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f)))
                    }
                }
            }
        }
    }
}
