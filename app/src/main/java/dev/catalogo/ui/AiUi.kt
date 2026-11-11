package dev.catalogo.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.Ai
import dev.catalogo.core.AiResult
import dev.catalogo.core.Article
import dev.catalogo.core.Catalog
import dev.catalogo.core.Gender
import dev.catalogo.core.ParentState
import kotlinx.coroutines.delay

/**
 * The article's AI block: generate (choosing the photos), a running counter
 * with cancel, the error kept in sight, and what to pick by hand in Vinted.
 */
@Composable
fun AiTile(vm: MainViewModel, p: ParentState, article: Article) {
    val number = article.number
    val runs by vm.ai.collectAsStateWithLifecycle()
    val app by vm.state.collectAsStateWithLifecycle()
    val run = runs[number]
    var picking by remember { mutableStateOf(false) }
    val hasKey = app.geminiKey.isNotBlank()

    Tile {
        when (run) {
            is MainViewModel.AiRun.Running -> {
                val seconds by produceState(0, run) {
                    while (true) {
                        value = ((System.currentTimeMillis() - run.since) / 1000).toInt()
                        delay(1000)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Mirando las fotos… $seconds s", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(
                    onClick = { vm.cancelAi(number) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Cancelar") }
            }
            else -> {
                if (run is MainViewModel.AiRun.Failed) {
                    Text(run.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                when {
                    !hasKey -> Muted("Falta la clave de Gemini (Ajustes)")
                    article.ai != null && Catalog.freshAi(p, number) == null -> Muted("Datos cambiados: vuelve a generar")
                }
                FilledTonalButton(
                    onClick = { picking = true },
                    enabled = hasKey,
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(if (article.ai == null) "Generar anuncio con IA" else "Volver a generar con IA") }
                article.ai?.let { ai ->
                    VintedInfo(article.listing?.gender, ai)
                    if (ai.seconds > 0) Muted("${ai.seconds} s · ${ai.model}", Modifier.padding(top = 6.dp), maxLines = 1)
                }
            }
        }
    }

    if (picking) {
        PhotoPicker(vm, number, onDismiss = { picking = false }) { photos ->
            picking = false
            vm.startAi(number, photos)
        }
    }
}

/** What to pick by hand in Vinted: gender, category and colour. */
@Composable
fun VintedInfo(gender: Gender?, ai: AiResult) {
    val rows = listOfNotNull(
        gender?.let { "Sexo" to it.label },
        ai.category?.let { "Categoría" to it },
        ai.colors.takeIf { it.isNotEmpty() }?.let { "Color" to it.joinToString(" y ") },
    )
    if (rows.isEmpty()) return
    Muted("Para rellenar en Vinted", Modifier.padding(top = 6.dp))
    for ((label, value) in rows) {
        Text(
            "$label: $value",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Choose the 1 to 3 photos sent to the AI; the first ones come selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoPicker(vm: MainViewModel, number: Int, onDismiss: () -> Unit, onSend: (List<Uri>) -> Unit) {
    val photos by produceState<List<Uri>?>(null, number) { value = vm.aiPhotos(number) }
    var chosen by remember { mutableStateOf<List<Uri>?>(null) }
    LaunchedEffect(photos) { if (chosen == null && photos != null) chosen = photos!!.take(Ai.PHOTOS) }
    val picked = chosen.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fotos para la IA") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    photos == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    photos!!.isEmpty() -> Text("Este artículo no tiene fotos. Hazlas primero.")
                    else -> {
                        Muted("De 1 a ${Ai.PHOTOS}")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (uri in photos!!) {
                                val index = picked.indexOf(uri)
                                val shape = RoundedCornerShape(12.dp)
                                Box(
                                    Modifier.size(84.dp).clip(shape)
                                        .border(if (index >= 0) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, shape)
                                        .clickable {
                                            chosen = when {
                                                index >= 0 -> picked - uri
                                                picked.size < Ai.PHOTOS -> picked + uri
                                                else -> picked
                                            }
                                        },
                                ) {
                                    Thumb(uri, Modifier.fillMaxSize().alpha(if (index >= 0) 1f else 0.6f))
                                    if (index >= 0) {
                                        Text(
                                            "${index + 1}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                                                .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                                                .padding(horizontal = 7.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(picked) }, enabled = picked.size in 1..Ai.PHOTOS) { Text("Enviar (${picked.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
