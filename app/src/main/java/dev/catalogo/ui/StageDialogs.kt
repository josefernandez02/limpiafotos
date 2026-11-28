package dev.catalogo.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.catalogo.core.Article
import dev.catalogo.core.Mode
import dev.catalogo.core.Stage

/** Status for several articles at once (long press in the list). */
@Composable
fun StageManyDialog(vm: MainViewModel, numbers: List<Int>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val options = listOf(Stage.TO_UPLOAD, Stage.DRAFT, Stage.UPLOADED, Stage.PLAYED)
    var picked by remember { mutableStateOf<Stage?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (numbers.size == 1) "Cambiar estado" else "Cambiar estado de ${numbers.size}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (o in options) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { picked = o }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == o, onClick = { picked = o })
                        Text(o.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { picked?.let { vm.setStageMany(numbers, it) }; onDismiss(); onDone() }, enabled = picked != null) {
                Text("Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/**
 * Where the article stands, set by hand (done by hand, or the system got it wrong).
 * The PC is told, so it does not like a link twice or publish a draft that must stay one.
 */
@Composable
internal fun StageDialog(vm: MainViewModel, article: Article, current: Stage, onDismiss: () -> Unit) {
    val options = listOf(Stage.TO_UPLOAD, Stage.DRAFT, Stage.UPLOADED, Stage.PLAYED)
    var picked by remember { mutableStateOf(current.takeIf { it in options } ?: Stage.TO_UPLOAD) }
    var link by remember { mutableStateOf(article.pcTask?.url.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cambiar estado") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (o in options) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { picked = o }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == o, onClick = { picked = o })
                        Column {
                            Text(o.label, style = MaterialTheme.typography.bodyLarge)
                            Muted(
                                when (o) {
                                    Stage.TO_UPLOAD -> "Fuera de la cola y sin subir"
                                    Stage.DRAFT -> "Está como borrador en Vinted"
                                    Stage.UPLOADED -> "Publicado, aún sin likes de juegos"
                                    else -> "Ya le di los likes del juego"
                                },
                            )
                        }
                    }
                }
                if (picked != Stage.TO_UPLOAD) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it.trim() },
                        label = { Text("Link en Vinted (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); vm.setStage(article.number, picked, link) }, enabled = picked != current || link != article.pcTask?.url.orEmpty()) {
                Text("Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/**
 * Which photos the AI looks at, right before the draft: all are marked; a tap
 * takes out one that adds nothing (or puts it back). Vinted gets all of them anyway.
 */
@Composable
fun PhotoChoiceDialog(vm: MainViewModel, number: Int, mode: String = Mode.UPLOAD_PLAY, onDismiss: () -> Unit) {
    val loaded by produceState<Pair<List<Uri>, List<Uri>>?>(null, number) { value = vm.vintedPhotos(number) }
    var chosen by remember(loaded) { mutableStateOf(loaded?.second.orEmpty()) }
    val go = rememberWithPermissions(Perms.notifications, onDenied = { vm.prepareDraft(number, chosen, mode) }) {
        vm.prepareDraft(number, chosen, mode)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fotos para la IA") },
        text = {
            val album = loaded?.first
            when {
                album == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                album.isEmpty() -> Muted("Este artículo no tiene fotos")
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Muted("Quita las que no aportan. Al PC van todas.")
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.heightIn(max = 420.dp),
                    ) {
                        // Chosen ones first, in their order; the rest after, dimmed.
                        val shown = chosen + album.filter { it !in chosen }
                        items(shown, key = { it.toString() }) { uri ->
                            val at = chosen.indexOf(uri)
                            Box(
                                Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                    .clickable { chosen = if (at >= 0) chosen - uri else chosen + uri },
                            ) {
                                Thumb(uri, Modifier.fillMaxSize().alpha(if (at >= 0) 1f else 0.35f))
                                if (at >= 0) {
                                    Box(
                                        Modifier.padding(5.dp).size(24.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary).align(Alignment.TopStart),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("${at + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); go() }, enabled = chosen.isNotEmpty()) {
                Text(if (chosen.isEmpty()) "Generar" else "Generar con ${chosen.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
