package dev.catalogo.ui.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.model.AppState
import dev.catalogo.core.model.Article
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.model.Mode
import dev.catalogo.core.model.PcTask
import dev.catalogo.core.model.Stage
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.common.Confirm
import dev.catalogo.ui.common.Muted
import dev.catalogo.ui.common.OneLine

/**
 * The main account in the article: what to do with it, how far it is, and the
 * one button that moves it on. Same shape in every state: a short line on top
 * (what is happening), the main button, and «Cambiar estado» at the bottom.
 */
@Composable
fun PcBlock(vm: MainViewModel, p: CatalogState, article: Article, state: AppState) {
    val pcTask = article.pcTask
    val stage = Catalog.stage(p, article, p.mainAccount)
    var reviewing by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var mode by remember(pcTask?.mode) { mutableStateOf(pcTask?.mode ?: Mode.UPLOAD_PLAY) }
    var link by remember(pcTask?.url) { mutableStateOf(pcTask?.url.orEmpty()) }
    // The PC already has photos and data: «Volver a intentarlo» asks it again, nothing is re-sent.
    val onPc = pcTask != null && (pcTask.status == "revisar" || pcTask.status == "error")

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.pc == null) {
            Muted("Conecta el PC en Ajustes")
            return@Column
        }
        when (stage) {
            Stage.TO_UPLOAD -> {
                ModePicker(mode) { mode = it }
                Muted(
                    when (mode) {
                        Mode.DRAFT -> "Crea el borrador en Vinted y lo deja ahí hasta que digas."
                        Mode.PLAY -> "Ya está subido: su link va al siguiente juego."
                        else -> "Crea el borrador, lo publica justo antes del siguiente juego y le da likes."
                    },
                )
                if (mode == Mode.PLAY) {
                    LinkField(link) { link = it }
                    MainButton("Añadir a los juegos", enabled = vintedLink(link) != null) { vm.playOnly(article.number, vintedLink(link)!!) }
                } else {
                    if (article.price == null) Muted("Falta el precio de Vinted (Editar)")
                    MainButton("Preparar borrador") { choosing = true }
                }
            }
            Stage.WORKING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Muted(
                        when (pcTask?.status) {
                            PcTask.PREPARING -> "La IA está leyendo las fotos"
                            PcTask.SENDING -> "Enviando fotos y datos al PC"
                            "pendiente" -> "En cola en el PC"
                            else -> "El PC está rellenando el formulario"
                        } + "  (${Mode.label(pcTask?.mode ?: Mode.UPLOAD_PLAY)})",
                    )
                }
                pcTask?.message?.takeIf { it.isNotBlank() }?.let { Muted(it) }
                SecondButton("Sacar de la cola") { removing = true }
            }
            Stage.REVIEW -> {
                pcTask?.message?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (pcTask != null && pcTask.status == PcTask.REVIEW && pcTask.doubts.isNotEmpty()) {
                    MainButton("Revisar ${pcTask.doubts.size} ${if (pcTask.doubts.size == 1) "dato" else "datos"}") { reviewing = true }
                } else {
                    MainButton("Volver a intentarlo") { if (onPc) vm.retryDraft(article.number) else choosing = true }
                }
                SecondButton("Sacar de la cola") { removing = true }
            }
            Stage.DRAFT -> {
                val onlyDraft = pcTask?.mode == Mode.DRAFT
                Muted(if (onlyDraft) "Se queda como borrador: el PC no lo publica." else "Se publicará justo antes del siguiente juego.")
                if (onlyDraft) MainButton("Subir y jugar") { vm.setMode(article.number, Mode.UPLOAD_PLAY) }
                else SecondButton("Dejar solo como borrador") { vm.setMode(article.number, Mode.DRAFT) }
            }
            Stage.UPLOADED -> {
                val waiting = pcTask?.mode == Mode.PLAY || pcTask?.status == "publicado" && pcTask.mode == Mode.UPLOAD_PLAY
                Muted(if (waiting) "Sin jugar: irá al siguiente juego." else "Subido. No está en los juegos.")
                if (!waiting) {
                    LinkField(link) { link = it }
                    MainButton("Meter en los juegos", enabled = vintedLink(link) != null) { vm.playOnly(article.number, vintedLink(link)!!) }
                }
            }
            Stage.PLAYED -> Muted(pcTask?.game?.takeIf { it.isNotBlank() }?.let { "Jugado en $it." } ?: "Jugado.")
            Stage.SOLD -> Muted("Vendido en Vinted.")
        }
        SecondButton("Cambiar estado") { changing = true }
    }
    if (reviewing && pcTask != null) PcReviewDialog(vm, p, article, pcTask, onDismiss = { reviewing = false })
    if (choosing) PhotoChoiceDialog(vm, article.number, mode, onDismiss = { choosing = false })
    if (removing) {
        Confirm(
            title = "¿Sacar de la cola?",
            text = "El PC no hará nada con este artículo hasta que lo vuelvas a mandar. No se borra nada en Vinted.",
            confirm = "Sacar",
            onConfirm = { vm.removeDraft(article.number) },
            onDismiss = { removing = false },
            destructive = true,
        )
    }
    if (changing) StageDialog(vm, article, stage, onDismiss = { changing = false })
}

/** The article's Vinted link from whatever was pasted (share text included), or null. */
private fun vintedLink(text: String): String? =
    Regex("""(?:https?://)?(?:www\.)?vinted\.[a-z.]{2,8}/items/\d+[^\s]*""").find(text)?.value
        ?.let { if (it.startsWith("http")) it.replaceFirst("http://", "https://") else "https://$it" }

@Composable
private fun LinkField(link: String, onChange: (String) -> Unit) {
    val bad = link.isNotBlank() && vintedLink(link) == null
    OutlinedTextField(
        value = link,
        onValueChange = { onChange(it.trim()) },
        label = { Text("Link del artículo en Vinted") },
        singleLine = true,
        isError = bad,
        supportingText = if (bad) ({ Text("No es un link de un artículo de Vinted") }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** What to do with the article: three equal options, one line each. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModePicker(mode: String, onPick: (String) -> Unit) {
    val modes = listOf(Mode.DRAFT, Mode.UPLOAD_PLAY, Mode.PLAY)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onPick(m) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                icon = {},
                label = { OneLine(Mode.label(m)) },
            )
        }
    }
}

@Composable
private fun MainButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(16.dp),
    ) { OneLine(text) }
}

@Composable
private fun SecondButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(16.dp),
    ) { OneLine(text) }
}
