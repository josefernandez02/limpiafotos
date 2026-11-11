package dev.catalogo.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.Catalog
import dev.catalogo.core.ParentState
import dev.catalogo.data.Crop
import dev.catalogo.data.Framing
import dev.catalogo.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A photo in the editor: its source and, once cropped, the new JPEG. */
private data class Slot(val source: Uri, val bytes: ByteArray? = null, val key: Int)

/**
 * Order, cover (the first photo), removal and crop of one account's photos.
 * Nothing is written until "Guardar".
 */
@Composable
fun PhotosScreen(vm: MainViewModel, p: ParentState, number: Int, deviceId: String) {
    val device = Catalog.device(p, deviceId)
    val saving by vm.savingPhotos.collectAsStateWithLifecycle()
    var slots by remember { mutableStateOf<List<Slot>?>(null) }
    var original by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var cropping by remember { mutableStateOf<Int?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(number, deviceId) {
        val photos = vm.editablePhotos(number, deviceId)
        original = photos
        slots = photos.mapIndexed { i, uri -> Slot(uri, key = i) }
    }
    val list = slots
    val changed = list != null && (list.map { it.source } != original || list.any { it.bytes != null })
    val leave = { if (changed) confirmLeave = true else vm.back() }
    BackHandler(enabled = cropping == null && !saving) { leave() }

    fun move(from: Int, to: Int) {
        val l = slots?.toMutableList() ?: return
        if (to !in l.indices) return
        l.add(to, l.removeAt(from))
        slots = l
        selected = to
    }

    val crop = cropping
    if (crop != null && list != null) {
        CropScreen(
            vm = vm,
            slot = list[crop],
            onCancel = { cropping = null },
            onDone = { bytes ->
                slots = list.toMutableList().also { it[crop] = it[crop].copy(bytes = bytes) }
                cropping = null
            },
        )
        return
    }

    Scaffold(
        topBar = { Bar("Fotos · ${device?.label ?: ""}", onBack = if (saving) null else leave) },
        bottomBar = {
            BottomActions {
                val sel = selected
                if (saving) {
                    Muted("Guardando…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    if (list != null && sel != null && sel in list.indices) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalIconButton(onClick = { move(sel, sel - 1) }, enabled = sel > 0) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mover antes")
                            }
                            FilledTonalIconButton(onClick = { move(sel, sel + 1) }, enabled = sel < list.size - 1) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mover después")
                            }
                            FilledTonalButton(onClick = { move(sel, 0) }, enabled = sel > 0, modifier = Modifier.weight(1f)) { Text("Portada") }
                            FilledTonalButton(onClick = { cropping = sel }, modifier = Modifier.weight(1f)) { Text("Recortar") }
                            FilledTonalIconButton(onClick = {
                                slots = list.filterIndexed { i, _ -> i != sel }
                                selected = null
                            }, enabled = list.size > 1) {
                                Icon(Icons.Default.Delete, contentDescription = "Quitar")
                            }
                        }
                    }
                    BigButton(
                        "Guardar",
                        enabled = changed,
                        onClick = { vm.savePhotos(number, deviceId, list.orEmpty().map { Repo.EditedPhoto(it.source, it.bytes) }) },
                    )
                }
            }
        },
    ) { padding ->
        if (list == null) return@Scaffold
        if (list.isEmpty()) {
            Empty("Sin fotos", Modifier.padding(padding))
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(list, key = { _, s -> s.key }) { i, slot ->
                val on = selected == i
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .animateItem()
                        .aspectRatio(Crop.RATIO)
                        .clip(shape)
                        .border(if (on) 3.dp else 0.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                        .clickable { selected = if (on) null else i },
                ) {
                    SlotImage(slot, Modifier.fillMaxSize())
                    Text(
                        if (i == 0) "Portada" else "${i + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                            .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    if (slot.bytes != null) {
                        Text(
                            "Recortada",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                                .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        Confirm(
            title = "Salir sin guardar",
            text = "Se pierden los cambios de orden y recorte.",
            confirm = "Salir",
            destructive = true,
            onConfirm = { confirmLeave = false; vm.back() },
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun SlotImage(slot: Slot, modifier: Modifier) {
    val bytes = slot.bytes
    if (bytes == null) {
        Thumb(slot.source, modifier)
        return
    }
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.Default) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * The 3:4 template over the photo. Drag to move and pinch to zoom; the inner
 * guide marks the margin the garment should touch, so every photo of the
 * catalogue ends up with about the same space around the garment.
 */
@Composable
private fun CropScreen(vm: MainViewModel, slot: Slot, onCancel: () -> Unit, onDone: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onCancel)
    // The photo to frame, and the colour of its edges that fills what it does not cover.
    val loaded by produceState<Pair<ImageBitmap, Color>?>(null, slot.source) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val big = loadPhoto(context, slot.source, 1600)
                val small = android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(context.contentResolver, slot.source),
                ) { decoder, info, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = 200f / maxOf(info.size.width, info.size.height)
                    if (s < 1f) decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                }
                big to Color(Crop.edgeColor(small))
            }.getOrNull()
        }
    }
    val image = loaded?.first
    var framing by remember { mutableStateOf<Framing?>(null) }
    var working by remember { mutableStateOf(false) }
    val img = image
    LaunchedEffect(img) { if (img != null && framing == null) framing = Crop.cover(img.width, img.height) }

    Scaffold(
        topBar = { Bar("Recortar", onBack = onCancel) },
        bottomBar = {
            BottomActions {
                if (img != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { framing = Crop.fit(img.width, img.height) }, modifier = Modifier.weight(1f)) { Text("Entera") }
                        OutlinedButton(onClick = { framing = Crop.cover(img.width, img.height) }, modifier = Modifier.weight(1f)) { Text("Rellenar") }
                    }
                }
                BigButton(if (working) "Aplicando…" else "Aplicar", enabled = framing != null && !working, onClick = {
                    val f = framing ?: return@BigButton
                    working = true
                    scope.launch {
                        val bytes = runCatching { vm.renderCrop(slot.source, f) }
                        working = false
                        bytes.onSuccess(onDone).onFailure { vm.say("No se pudo recortar la foto") }
                    }
                })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            val f = framing
            if (img == null || f == null) return@Box
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(Crop.RATIO, matchHeightConstraintsFirst = true)) {
                val frameW = with(LocalDensity.current) { maxWidth.toPx() }
                Canvas(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(loaded?.second ?: Color(0xFF2A2C2F))
                        .pointerInput(img) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val cur = framing ?: return@detectTransformGestures
                                framing = cur.copy(
                                    scale = (cur.scale * zoom).coerceIn(0.3f, 6f),
                                    dx = (cur.dx + pan.x / frameW).coerceIn(-1.5f, 1.5f),
                                    dy = (cur.dy + pan.y / frameW).coerceIn(-2f, 2f),
                                )
                            }
                        },
                ) {
                    val w = f.scale * size.width
                    val h = w * img.height / img.width
                    val cx = size.width / 2 + f.dx * size.width
                    val cy = size.height / 2 + f.dy * size.width
                    drawImage(
                        img,
                        dstOffset = IntOffset((cx - w / 2).toInt(), (cy - h / 2).toInt()),
                        dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                    )
                    // Margin guide: the garment should touch this line on its widest side.
                    val inset = size.width * 0.08f
                    drawRect(
                        color = Color.White.copy(alpha = 0.8f),
                        topLeft = Offset(inset, inset),
                        size = GSize(size.width - 2 * inset, size.height - 2 * inset),
                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
                    )
                    // Centre lines, to keep the garment symmetric.
                    val faint = Color.White.copy(alpha = 0.25f)
                    drawLine(faint, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
                    drawLine(faint, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
                    drawRect(Color.White, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}
