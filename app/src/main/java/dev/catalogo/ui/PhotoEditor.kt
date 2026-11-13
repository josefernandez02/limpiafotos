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
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
    val leave: () -> Unit = {
        if (changed) confirmLeave = true else vm.back()
    }
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
        topBar = { Bar(device?.label?.let { "Fotos de $it" } ?: "Fotos", onBack = if (saving) null else leave) },
        bottomBar = {
            BottomActions {
                val sel = selected
                if (saving) {
                    Muted("Guardando…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    // Always visible, enabled once a photo is chosen: five equal tools.
                    val on = list != null && sel != null && sel in list.indices
                    val i = sel ?: -1
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Tool(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Antes", on && i > 0) { move(i, i - 1) }
                        Tool(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Después", on && i < (list?.size ?: 0) - 1) { move(i, i + 1) }
                        Tool(Icons.Default.Star, "Portada", on && i > 0) { move(i, 0) }
                        Tool(CropIcon, "Recortar", on) { cropping = i }
                        Tool(Icons.Default.Delete, "Quitar", on && (list?.size ?: 0) > 1) {
                            slots = list.orEmpty().filterIndexed { k, _ -> k != i }
                            selected = null
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

/** Icon with its name underneath, all the same width: a tidy, symmetric toolbar. */
@Composable
private fun Tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = color) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Two crossing corners: the crop tool. */
private val CropIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("Catalogo.Crop", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M7,2H9V15H22V17H17V22H15V17H7Z M2,7H7V9H2Z M11,7H17V13H15V9H11Z",
        ).toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
    ).build()
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
                        OutlinedButton(
                            onClick = { framing = framing?.copy(dx = 0f, dy = 0f) },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { OneLine("Centrar") }
                        OutlinedButton(
                            onClick = { framing = Crop.cover(img.width, img.height) },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { OneLine("Restablecer") }
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
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val cur = framing ?: return@detectTransformGestures
                                val scale = (cur.scale * zoom).coerceIn(0.3f, 6f)
                                val k = scale / cur.scale
                                // Zoom around the fingers: the point under them stays put.
                                val cx = (centroid.x - size.width / 2f) / frameW
                                val cy = (centroid.y - size.height / 2f) / frameW
                                framing = cur.copy(
                                    scale = scale,
                                    dx = (cx + (cur.dx - cx) * k + pan.x / frameW).coerceIn(-1.5f, 1.5f),
                                    dy = (cy + (cur.dy - cy) * k + pan.y / frameW).coerceIn(-2f, 2f),
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
