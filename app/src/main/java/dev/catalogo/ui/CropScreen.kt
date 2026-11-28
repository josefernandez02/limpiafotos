package dev.catalogo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.catalogo.core.CoverFrame
import dev.catalogo.core.CropHistory
import dev.catalogo.data.AutoFrame
import dev.catalogo.data.PhotoCropper
import dev.catalogo.data.Framing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Crop like in any photo app: the photo stays still and a 3:4 box is dragged by its corners and its middle. The box never
 * goes beyond the photo, so nothing is ever made up around it. "Giro" straightens the photo.
 */
@Composable
internal fun CropScreen(vm: MainViewModel, slot: Slot, isCover: Boolean, onCancel: () -> Unit, onDone: (ByteArray, Framing, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onCancel)
    val image by produceState<ImageBitmap?>(null, slot.source) {
        value = withContext(Dispatchers.IO) { runCatching { loadPhoto(context, slot.source, 1600) }.getOrNull() }
    }
    var framing by remember { mutableStateOf(slot.framing) }
    var working by remember { mutableStateOf(false) }
    val img = image
    var note by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }      // the user has moved the box: an automatic framing on opening must not undo it
    var manual by remember { mutableStateOf(false) }       // how the framing was made, for the record
    var garment by remember { mutableStateOf<dev.catalogo.core.Mask?>(null) }   // the garment's silhouette, to measure the space around it
    var marking by remember { mutableStateOf(false) }            // «Marcar prenda»: the user draws the box around the garment and the app frames by it
    var markRect by remember { mutableStateOf(floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f)) }
    val auto: (Boolean) -> Unit = { onOpen ->
        scope.launch {
            analyzing = true
            val r = AutoFrame.analyze(context, slot.source)
            analyzing = false
            if (onOpen && touched) return@launch
            val fr = r.framing
            if (r.mask != null) garment = r.mask
            if (fr != null) {
                framing = fr
                manual = false
                note = when {
                    r.tight -> "Prenda muy grande en la foto: puede quedar justa. Mejor una foto más abierta."
                    kotlin.math.abs(fr.rotation) >= 0.5f -> "Enderezada ${"%.1f".format(java.util.Locale.ROOT, kotlin.math.abs(fr.rotation))}°"
                    else -> ""
                }
            } else {
                note = "No localicé la prenda: ajústala a mano"
            }
        }
    }
    LaunchedEffect(img) {
        if (img != null) {
            val cur = framing
            if (cur == null) {
                framing = PhotoCropper.cover(img.width, img.height)
                if (isCover && slot.edit == null) auto(true)
            } else if (!cur.covers(img.width, img.height)) {
                framing = CoverFrame.settle(cur.placement(), img.width, img.height, CoverFrame.fillLimit(cur.rotation)).framing()      // an old crop with made-up edges is tidied
            }
            // an already framed cover: the silhouette is only looked for to measure the space around the garment
            if (isCover && !(cur == null && slot.edit == null)) garment = AutoFrame.analyze(context, slot.source).mask
        }
    }

    val apply: () -> Unit = {
        val f = framing
        if (f != null && !working) {
            working = true
            scope.launch {
                val bytes = runCatching { vm.renderCrop(slot.source, f) }
                working = false
                bytes.onSuccess { onDone(it, f, if (manual) CropHistory.MANUAL else CropHistory.AUTO) }.onFailure { vm.say("No se pudo recortar la foto") }
            }
        }
    }

    var enlarged by remember { mutableStateOf<Float?>(null) }        // the preview (its ratio) being looked at in big
    val big = enlarged
    val fr = framing
    if (big != null && img != null && fr != null) {
        PreviewScreen(slot.source, img, fr, big, onPick = { enlarged = it }, onClose = { enlarged = null })
        return
    }

    Scaffold(
        topBar = {
            Bar("Recortar", onBack = onCancel, actions = {
                Button(onClick = apply, enabled = framing != null && !working, modifier = Modifier.padding(end = 8.dp)) {
                    Text(if (working) "Aplicando…" else "Aplicar")
                }
            })
        },
        bottomBar = {
            if (img != null && fr != null) {
                // everything that adjusts the photo lives in one panel: straighten, the four views, the three shortcuts
                if (marking) Panel {
                    Text("Arrastra cada lado de la caja azul hasta rodear la prenda entera (mangas incluidas).", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { marking = false }, modifier = Modifier.weight(1f).height(44.dp)) { OneLine("Cancelar") }
                        Button(
                            onClick = {
                                val mw = maxOf(1, img.width * 128 / maxOf(img.width, img.height))
                                val mh = maxOf(1, img.height * 128 / maxOf(img.width, img.height))
                                val x0 = (markRect[0] * mw).toInt(); val x1 = (markRect[2] * mw).toInt().coerceAtLeast(x0 + 1)
                                val y0 = (markRect[1] * mh).toInt(); val y1 = (markRect[3] * mh).toInt().coerceAtLeast(y0 + 1)
                                val mask = dev.catalogo.core.Mask(mw, mh, BooleanArray(mw * mh) { (it % mw) in x0 until x1 && (it / mw) in y0 until y1 })
                                val p = CoverFrame.place(mask, img.width, img.height)
                                if (p != null) {
                                    framing = CoverFrame.settle(p, img.width, img.height, CoverFrame.fillLimit(p.rotation)).framing()
                                    garment = mask; touched = true; manual = true; note = ""; marking = false
                                } else note = "Esa caja no sirve: debe ocupar entre un 8 % y un 90 % de la foto"
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { OneLine("Reencuadrar") }
                    }
                    if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                } else Panel {
                    StraightenRow(fr.rotation) {
                        touched = true; manual = true; note = ""
                        framing = CoverFrame.settle(fr.copy(rotation = it).placement(), img.width, img.height, CoverFrame.FILL_MAX).framing()
                    }
                    Text("Vistas previas · toca una para verla en grande", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        for ((ratio, name) in PREVIEW_NAMES) {
                            FramePreview(img, fr, Color(0xFF2A2C2F), ratio, name, 72.dp, Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { enlarged = ratio })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { auto(false) },
                            enabled = !analyzing,
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { OneLine(if (analyzing) "Buscando…" else "Auto") }
                        if (isCover) OutlinedButton(
                            onClick = {
                                garment?.let { g -> CoverFrame.largestParts(g) }?.let { c ->
                                    var x0 = c.w; var y0 = c.h; var x1 = -1; var y1 = -1
                                    for (y in 0 until c.h) for (x in 0 until c.w) if (c.on[y * c.w + x]) { x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) }
                                    if (x1 >= 0) markRect = floatArrayOf(x0.toFloat() / c.w, y0.toFloat() / c.h, (x1 + 1f) / c.w, (y1 + 1f) / c.h)
                                }
                                note = ""; marking = true
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { OneLine("Marcar prenda") }
                        OutlinedButton(
                            onClick = {
                                val c = garment?.let { g -> CoverFrame.centered(g, img.width, fr.placement())?.framing() }
                                    ?: fr.copy(dx = 0f, dy = 0f)
                                if (c.covers(img.width, img.height)) { touched = true; manual = true; framing = c; note = "" }
                                else note = "No se puede centrar sin salir de la foto"
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { OneLine("Centrar") }
                        OutlinedButton(
                            onClick = { touched = true; manual = true; framing = PhotoCropper.cover(img.width, img.height); note = "" },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { OneLine("Original") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val g = garment
            val m = if (isCover && fr != null && g != null && img != null) remember(fr, g, img) { CoverFrame.margins(g, img.width, fr.placement(), PhotoCropper.OUT_W) } else null
            // the work area takes everything the panel leaves
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (img == null || fr == null) return@Box
                if (marking) MarkArea(img, markRect) { markRect = it }
                else CropArea(img, fr, isCover, m) { framing = it; touched = true; manual = true; note = "" }
            }
            val off = if (m != null && g != null && img != null && fr != null) remember(fr, g, img) { CoverFrame.offCentre(g, img.width, img.height, fr.placement(), PhotoCropper.OUT_W) } else 0
            if (m != null) MarginChip(m, off, Modifier.padding(top = 8.dp))
            if (note.isNotBlank()) {
                Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            Box(Modifier.height(8.dp))
        }
    }
}

/** The controls' base: a rounded sheet that runs under the system bar, with even spacing between its rows. */
@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        BottomActions(content)
    }
}

/** Straighten: a thin slider with a mark at 0°; tapping the number puts the photo straight again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StraightenRow(rotation: Float, onChange: (Float) -> Unit) {
    val active = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val straight = kotlin.math.abs(rotation) < 0.05f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Giro ${"%.1f".format(java.util.Locale.ROOT, rotation)}°",
            style = MaterialTheme.typography.labelMedium,
            color = if (straight) MaterialTheme.colorScheme.onSurfaceVariant else active,
            modifier = Modifier.width(76.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !straight) { onChange(0f) }.padding(vertical = 6.dp),
        )
        Slider(
            value = rotation.coerceIn(-15f, 15f),
            onValueChange = onChange,
            valueRange = -15f..15f,
            modifier = Modifier.weight(1f),
            thumb = { Box(Modifier.size(20.dp).background(active, CircleShape)) },
            track = { st ->
                val frac = ((st.value + 15f) / 30f).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    drawRoundRect(rail, size = size, cornerRadius = CornerRadius(size.height / 2))
                    val a = size.width / 2f
                    val b = size.width * frac
                    drawRect(active, Offset(minOf(a, b), 0f), GSize(kotlin.math.abs(b - a), size.height))
                    drawRect(Color.White.copy(alpha = 0.7f), Offset(a - 1.dp.toPx(), -4.dp.toPx()), GSize(2.dp.toPx(), size.height + 8.dp.toPx()))
                }
            },
        )
    }
}

/** The space between the garment and the cover's edges, in pixels of the saved cover: up, down, left, right. */
@Composable
private fun MarginChip(m: IntArray, offCentre: Int, modifier: Modifier = Modifier) {
    val sb = CoverFrame.safeBox()
    val needX = sb[0] * PhotoCropper.OUT_W * 0.8f       // what the narrowest preview leaves the garment, with a little tolerance
    val needY = sb[1] * PhotoCropper.OUT_W * 0.8f
    val tone = when {
        m.any { it < 0 } -> Color(0xFFFF6B6B)                                                  // the garment is cut
        m[2] < needX || m[3] < needX || m[0] < needY || m[1] < needY -> Color(0xFFFFC857)      // too close to an edge for some preview
        offCentre > 12 -> Color(0xFFFFC857)      // «Centrar» would place it better (unequal margins alone are not that: a folded garment is centred by its body)
        else -> Color(0xFF8EE0B0)
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
        Text("↑ ${m[0]}   ↓ ${m[1]}", color = tone, fontSize = 13.sp)
        Text("← ${m[2]}   → ${m[3]}", color = tone, fontSize = 13.sp)
    }
}
