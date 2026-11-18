package dev.catalogo.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.Catalog
import dev.catalogo.core.CoverFrame
import dev.catalogo.core.CropEdit
import dev.catalogo.core.CropHistory
import dev.catalogo.core.Placement
import dev.catalogo.data.CropLog
import dev.catalogo.core.ParentState
import dev.catalogo.data.AutoFrame
import dev.catalogo.data.Crop
import dev.catalogo.data.Framing
import dev.catalogo.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A photo in the editor: its source and, once cropped, the new JPEG. */
private data class Slot(val source: Uri, val bytes: ByteArray? = null, val key: Int, val edit: CropEdit? = null, val framing: Framing? = null)

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoWorking by remember { mutableStateOf(false) }

    LaunchedEffect(number, deviceId) {
        val photos = vm.editablePhotos(number, deviceId)
        original = photos
        val log = CropHistory.aligned(CropLog.load(context, deviceId, number), photos.size)
        slots = photos.mapIndexed { i, uri -> Slot(uri, key = i, edit = log.getOrNull(i)) }
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
            isCover = crop == 0,
            onCancel = { cropping = null },
            onDone = { bytes, f, kind ->
                slots = list.toMutableList().also {
                    it[crop] = it[crop].copy(bytes = bytes, framing = f, edit = CropHistory.next(it[crop].edit, kind, System.currentTimeMillis()))
                }
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
                    OutlinedButton(
                        onClick = {
                            val l = slots
                            if (l != null && l.isNotEmpty()) scope.launch {
                                autoWorking = true
                                val r = AutoFrame.analyze(context, l[0].source)
                                val fr = r.framing
                                if (fr == null) {
                                    vm.say("No localicé la prenda en la portada: recórtala a mano")
                                } else {
                                    runCatching { vm.renderCrop(l[0].source, fr) }
                                        .onSuccess { b ->
                                            slots = slots?.toMutableList()?.also { s ->
                                                if (s.isNotEmpty()) s[0] = s[0].copy(bytes = b, framing = fr, edit = CropHistory.next(s[0].edit, CropHistory.AUTO, System.currentTimeMillis()))
                                            }
                                        }
                                        .onFailure { vm.say("No se pudo recortar la foto") }
                                }
                                autoWorking = false
                            }
                        },
                        enabled = list != null && list.isNotEmpty() && !autoWorking,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { OneLine(if (autoWorking) "Encuadrando…" else "Encuadrar la portada automáticamente") }
                    BigButton(
                        "Guardar",
                        enabled = changed,
                        onClick = {
                            CropLog.save(context, deviceId, number, list.orEmpty().map { it.edit })
                            vm.savePhotos(number, deviceId, list.orEmpty().map { Repo.EditedPhoto(it.source, it.bytes) })
                        },
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
                    if (slot.bytes != null || slot.edit != null) {
                        Text(
                            slot.edit?.takeIf { it.count > 1 }?.let { "Recortada ×${it.count}" } ?: "Recortada",
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

private enum class Grip { NONE, MOVE, TL, TR, BL, BR }

/** Where the photo sits on screen: [d] screen pixels per image pixel, its centre at ([cx], [cy]); the crop box moves over it. */
private class Geo(val cx: Float, val cy: Float, val d: Float, val imgW: Int, val imgH: Int) {
    val minW = d * imgW * 0.15f
}

private fun Framing.placement() = Placement(scale, dx, dy, rotation)
private fun Placement.framing() = Framing(scale, dx, dy, rotation)
private fun Framing.covers(w: Int, h: Int) = CoverFrame.isCovered(placement(), w, h)   // or only a hair-thin strip of backdrop

/** The crop box (left, top, right, bottom, in screen pixels) of a framing: the 3:4 frame, with the photo fixed under it. */
private fun boxOf(f: Framing, g: Geo): FloatArray {
    val bw = g.d * g.imgW / f.scale
    val bx = g.cx - f.dx * bw
    val by = g.cy - f.dy * bw
    val bh = bw / Crop.RATIO
    return floatArrayOf(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
}

private fun framingOf(l: Float, t: Float, r: Float, b: Float, rotation: Float, g: Geo): Framing {
    val bw = r - l
    return Framing(g.d * g.imgW / bw, (g.cx - (l + r) / 2) / bw, (g.cy - (t + b) / 2) / bw, rotation)
}

private fun gripAt(at: Offset, b: FloatArray, slop: Float): Grip {
    fun near(x: Float, y: Float) = kotlin.math.abs(at.x - x) <= slop && kotlin.math.abs(at.y - y) <= slop
    return when {
        near(b[0], b[1]) -> Grip.TL
        near(b[2], b[1]) -> Grip.TR
        near(b[0], b[3]) -> Grip.BL
        near(b[2], b[3]) -> Grip.BR
        at.x in b[0]..b[2] && at.y in b[1]..b[3] -> Grip.MOVE
        else -> Grip.NONE
    }
}

/** The framing after dragging [grip] by ([mx], [my]) screen pixels; a corner resizes the box around the opposite corner. */
private fun dragged(f: Framing, grip: Grip, mx: Float, my: Float, g: Geo): Framing? {
    val b = boxOf(f, g)
    var l = b[0]; var t = b[1]; var r = b[2]; var bt = b[3]
    val w0 = r - l
    val grow = when (grip) {
        Grip.BR -> (mx + my * Crop.RATIO) / 2
        Grip.BL -> (-mx + my * Crop.RATIO) / 2
        Grip.TR -> (mx - my * Crop.RATIO) / 2
        Grip.TL -> (-mx - my * Crop.RATIO) / 2
        else -> 0f
    }
    val w = (w0 + grow).coerceAtLeast(g.minW)
    val h = w / Crop.RATIO
    when (grip) {
        Grip.MOVE -> { l += mx; r += mx; t += my; bt += my }
        Grip.BR -> { r = l + w; bt = t + h }
        Grip.BL -> { l = r - w; bt = t + h }
        Grip.TR -> { r = l + w; t = bt - h }
        Grip.TL -> { l = r - w; t = bt - h }
        Grip.NONE -> return null
    }
    return framingOf(l, t, r, bt, f.rotation, g)
}

/** The photo stays still; the 3:4 box is dragged by its corners (size) and its inside (position), never beyond the photo. */
@Composable
private fun CropArea(img: ImageBitmap, f: Framing, guide: Boolean, garment: IntArray?, onChange: (Framing) -> Unit) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(f)
    val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val areaW = with(density) { maxWidth.toPx() }
        val areaH = with(density) { maxHeight.toPx() }
        val geo = Geo(areaW / 2f, areaH / 2f, minOf(areaW / img.width, areaH / img.height) * 0.97f, img.width, img.height)
        val slop = with(density) { 40.dp.toPx() }
        var grip by remember { mutableStateOf(Grip.NONE) }
        Canvas(
            Modifier.fillMaxSize().pointerInput(img, areaW, areaH) {
                detectDragGestures(
                    onDragStart = { at -> grip = gripAt(at, boxOf(current, geo), slop) },
                    onDragEnd = { grip = Grip.NONE },
                    onDragCancel = { grip = Grip.NONE },
                    onDrag = { change, drag ->
                        change.consume()
                        val cur = current
                        // Never beyond the photo: if the whole step does not fit, try a smaller one (and, moving, each axis alone).
                        val tries = ArrayList<Pair<Float, Float>>()
                        for (k in floatArrayOf(1f, 0.5f, 0.25f)) tries.add(drag.x * k to drag.y * k)
                        if (grip == Grip.MOVE) { tries.add(drag.x to 0f); tries.add(0f to drag.y) }
                        for ((mx, my) in tries) {
                            val c = dragged(cur, grip, mx, my, geo) ?: break
                            if (c.covers(img.width, img.height)) { onChange(c); break }
                        }
                    },
                )
            },
        ) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.rotate(f.rotation, geo.cx, geo.cy)
                val pw = geo.d * img.width
                val ph = geo.d * img.height
                nc.drawBitmap(img.asAndroidBitmap(), null, android.graphics.RectF(geo.cx - pw / 2, geo.cy - ph / 2, geo.cx + pw / 2, geo.cy + ph / 2), paint)
                nc.restore()
            }
            val b = boxOf(f, geo)
            val bw = b[2] - b[0]
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset(0f, 0f), GSize(size.width, b[1].coerceAtLeast(0f)))
            drawRect(dim, Offset(0f, b[3]), GSize(size.width, (size.height - b[3]).coerceAtLeast(0f)))
            drawRect(dim, Offset(0f, b[1]), GSize(b[0].coerceAtLeast(0f), b[3] - b[1]))
            drawRect(dim, Offset(b[2], b[1]), GSize((size.width - b[2]).coerceAtLeast(0f), b[3] - b[1]))
            if (!guide) {       // thirds, for ordinary photos (the cover has its own guides, below)
                val faint = Color.White.copy(alpha = 0.35f)
                for (k in 1..2) {
                    drawLine(faint, Offset(b[0] + bw * k / 3, b[1]), Offset(b[0] + bw * k / 3, b[3]), 1.dp.toPx())
                    drawLine(faint, Offset(b[0], b[1] + (b[3] - b[1]) * k / 3), Offset(b[2], b[1] + (b[3] - b[1]) * k / 3), 1.dp.toPx())
                }
            }
            if (guide) {
                // the margin the garment should touch, so every cover has the same space around it
                val sb = CoverFrame.safeBox()
                drawRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset(b[0] + sb[0] * bw, b[1] + sb[1] * bw),
                    size = GSize((sb[2] - sb[0]) * bw, (sb[3] - sb[1]) * bw),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
                )
            }
            if (guide && garment != null) {
                // where the garment is: the space between this box and the frame is what the numbers above measure
                val k = bw / Crop.OUT_W
                drawRect(
                    color = Color(0xFF4DD0E1).copy(alpha = 0.9f),
                    topLeft = Offset(b[0] + garment[2] * k, b[1] + garment[0] * k),
                    size = GSize(bw - (garment[2] + garment[3]) * k, (b[3] - b[1]) - (garment[0] + garment[1]) * k),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawRect(Color.White, Offset(b[0], b[1]), GSize(bw, b[3] - b[1]), style = Stroke(width = 1.5.dp.toPx()))
            val len = 24.dp.toPx()
            val th = 4.dp.toPx()
            fun corner(x: Float, y: Float, sx: Float, sy: Float) {
                drawLine(Color.White, Offset(x - sx * th / 2, y), Offset(x + sx * len, y), th)
                drawLine(Color.White, Offset(x, y - sy * th / 2), Offset(x, y + sy * len), th)
            }
            corner(b[0], b[1], 1f, 1f); corner(b[2], b[1], -1f, 1f); corner(b[0], b[3], 1f, -1f); corner(b[2], b[3], -1f, -1f)
        }
    }
}

/**
 * Crop like in any photo app: the photo stays still and a 3:4 box is dragged by its corners and its middle. The box never
 * goes beyond the photo, so nothing is ever made up around it. "Giro" straightens the photo.
 */
@Composable
private fun CropScreen(vm: MainViewModel, slot: Slot, isCover: Boolean, onCancel: () -> Unit, onDone: (ByteArray, Framing, String) -> Unit) {
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
                framing = Crop.cover(img.width, img.height)
                if (isCover && slot.edit == null) auto(true)
            } else if (!cur.covers(img.width, img.height)) {
                framing = CoverFrame.settle(cur.placement(), img.width, img.height).framing()      // an old crop with made-up edges is tidied
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

    Scaffold(
        topBar = {
            Bar("Recortar", onBack = onCancel, actions = {
                FilledTonalButton(onClick = apply, enabled = framing != null && !working, modifier = Modifier.padding(end = 8.dp)) {
                    Text(if (working) "Aplicando…" else "Aplicar")
                }
            })
        },
        bottomBar = {
            if (img != null) {
                BottomActions {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { auto(false) },
                            enabled = !analyzing,
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { OneLine(if (analyzing) "Buscando…" else "Auto") }
                        OutlinedButton(
                            onClick = {
                                val c = garment?.let { g -> framing?.let { CoverFrame.centered(g, img.width, it.placement())?.framing() } }
                                    ?: framing?.copy(dx = 0f, dy = 0f)
                                if (c != null && c.covers(img.width, img.height)) { touched = true; manual = true; framing = c; note = "" }
                                else note = "No se puede centrar sin salir de la foto"
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { OneLine("Centrar") }
                        OutlinedButton(
                            onClick = { touched = true; manual = true; framing = Crop.cover(img.width, img.height); note = "" },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { OneLine("Original") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val f = framing
            val g = garment
            val m = if (isCover && f != null && g != null && img != null) remember(f, g, img) { CoverFrame.margins(g, img.width, f.placement(), Crop.OUT_W) } else null
            // the work area takes everything the rest leaves
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (img == null || f == null) return@Box
                CropArea(img, f, isCover, m) { framing = it; touched = true; manual = true; note = "" }
                if (m != null) MarginChip(m, Modifier.align(Alignment.TopCenter))
            }
            if (img != null && f != null) {
                // one quiet line: a warning if there is one, else when the photo was last cropped
                val status = note.ifBlank { slot.edit?.let { CropHistory.describe(it, System.currentTimeMillis()) }.orEmpty() }
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Giro ${"%.1f".format(java.util.Locale.ROOT, f.rotation)}°", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
                    Slider(
                        value = f.rotation.coerceIn(-15f, 15f),
                        onValueChange = {
                            touched = true; manual = true; note = ""
                            framing = CoverFrame.settle(f.copy(rotation = it).placement(), img.width, img.height).framing()
                        },
                        valueRange = -15f..15f, modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                    for ((ratio, label) in CoverFrame.PREVIEWS) {
                        val name = when (ratio) { 0.75f -> "Ficha"; 0.8f -> "Móvil"; 1f -> "Cuadro"; else -> "PC" }
                        FramePreview(img, f, Color(0xFF2A2C2F), ratio, name, 84.dp, label)
                    }
                }
            }
        }
    }
}

/** The space between the garment and the cover's edges, in pixels of the saved cover: above · below, left · right. */
@Composable
private fun MarginChip(m: IntArray, modifier: Modifier) {
    val sb = CoverFrame.safeBox()
    val needX = sb[0] * Crop.OUT_W * 0.8f       // what the narrowest preview leaves the garment, with a little tolerance
    val needY = sb[1] * Crop.OUT_W * 0.8f
    val tone = when {
        m.any { it < 0 } -> Color(0xFFFF6B6B)                                                  // the garment is cut
        m[2] < needX || m[3] < needX || m[0] < needY || m[1] < needY -> Color(0xFFFFC857)      // too close to an edge for some preview
        kotlin.math.abs(m[0] - m[1]) > 12 || kotlin.math.abs(m[2] - m[3]) > 12 -> Color(0xFFFFC857)   // not centred
        else -> Color(0xFF8EE0B0)
    }
    Text(
        "↕ ${m[0]} · ${m[1]}      ↔ ${m[2]} · ${m[3]}",
        color = tone, fontSize = 12.sp,
        modifier = modifier.padding(top = 6.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * How the framed photo looks in a container of another shape: the 3:4 image fills it and what falls outside is cut,
 * as Vinted does in its grids. If the garment shows complete in all three, it is safe on any phone.
 */
@Composable
private fun FramePreview(img: ImageBitmap, f: Framing, bg: Color, ratio: Float, label: String, height: Dp, labelBand: Float = 0f) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.height(height).aspectRatio(ratio).clip(RoundedCornerShape(6.dp)).background(bg)) {
            val fw = if (ratio >= Crop.RATIO) size.width else size.height * Crop.RATIO
            val fh = fw / Crop.RATIO
            val left = (size.width - fw) / 2f
            val top = (size.height - fh) / 2f
            drawFramed(img, f, left, top, fw)
            if (labelBand > 0f) drawRect(Color(0xFF3F9FAF).copy(alpha = 0.85f), Offset(0f, size.height * (1f - labelBand)), GSize(size.width, size.height * labelBand))   // «Destacado» band
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The same drawing the saved JPEG gets (see [Crop.draw]), so the previews do not lie. */
private fun DrawScope.drawFramed(img: ImageBitmap, f: Framing, left: Float, top: Float, fw: Float) {
    drawIntoCanvas { Crop.draw(it.nativeCanvas, img.asAndroidBitmap(), f, left, top, fw) }
}
