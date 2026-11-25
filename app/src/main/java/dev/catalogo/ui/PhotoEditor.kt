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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
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
import androidx.compose.ui.text.style.TextAlign
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

    val autoCover: () -> Unit = {
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
    }

    Scaffold(
        topBar = {
            Bar(device?.label?.let { "Fotos de $it" } ?: "Fotos", onBack = if (saving) null else leave, actions = {
                Button(
                    onClick = {
                        CropLog.save(context, deviceId, number, list.orEmpty().map { it.edit })
                        vm.savePhotos(number, deviceId, list.orEmpty().map { Repo.EditedPhoto(it.source, it.bytes) })
                    },
                    enabled = changed && !saving,
                    modifier = Modifier.padding(end = 8.dp),
                ) { Text(if (saving) "Guardando…" else "Guardar") }
            })
        },
        bottomBar = {
            Panel {
                val sel = selected
                if (saving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    // One fixed-height strip: the tools of the chosen photo, or a hint while none is chosen.
                    val on = list != null && sel != null && sel in list.indices
                    val i = sel ?: -1
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                        if (on) {
                          Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val e = list?.getOrNull(i)?.edit
                            Text(
                                e?.let { CropHistory.describe(it, System.currentTimeMillis()) } ?: "Sin recortar",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Tool(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Antes", i > 0) { move(i, i - 1) }
                                Tool(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Después", i < (list?.size ?: 0) - 1) { move(i, i + 1) }
                                Tool(Icons.Default.Star, "Portada", i > 0) { move(i, 0) }
                                Tool(CropIcon, "Recortar", true) { cropping = i }
                                Tool(Icons.Default.Delete, "Quitar", (list?.size ?: 0) > 1) {
                                    slots = list.orEmpty().filterIndexed { k, _ -> k != i }
                                    selected = null
                                }
                            }
                          }
                        } else {
                            Text("Toca una foto para moverla, recortarla o quitarla", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
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
            item(key = "auto-cover", span = { GridItemSpan(maxLineSpan) }) {
                FilledTonalButton(
                    onClick = autoCover,
                    enabled = !autoWorking && !saving,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) { OneLine(if (autoWorking) "Encuadrando…" else "Encuadrar la portada automáticamente") }
            }
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
                    if (on) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
                    if (i == 0) {
                        Row(
                            Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary).padding(start = 6.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                            Text(" Portada", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    } else {
                        Box(
                            Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White) }
                    }
                    if (slot.bytes != null || slot.edit != null) {
                        // cropped: just a small mark (the number of times lives in the crop screen's history)
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(CropIcon, contentDescription = "Recortada", tint = Color.White, modifier = Modifier.size(14.dp)) }
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

private enum class Grip { NONE, MOVE, TL, TR, BL, BR, T, B, L, R }

/** Where the photo sits on screen: [d] screen pixels per image pixel, its centre at ([cx], [cy]); the crop box moves over it. */
private class Geo(val cx: Float, val cy: Float, val d: Float, val imgW: Int, val imgH: Int) {
    val minW = d * imgW * 0.15f
}

private fun Framing.placement() = Placement(scale, dx, dy, rotation)
private fun Placement.framing() = Framing(scale, dx, dy, rotation)
private fun Framing.covers(w: Int, h: Int) = CoverFrame.isCovered(placement(), w, h, CoverFrame.fillLimit(rotation))   // or only a strip of mirrored backdrop, which the box already shows

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
    val edge = minOf(slop * 0.6f, (b[2] - b[0]) * 0.2f, (b[3] - b[1]) * 0.2f)
    fun near(x: Float, y: Float) = kotlin.math.abs(at.x - x) <= slop && kotlin.math.abs(at.y - y) <= slop
    return when {
        near(b[0], b[1]) -> Grip.TL
        near(b[2], b[1]) -> Grip.TR
        near(b[0], b[3]) -> Grip.BL
        near(b[2], b[3]) -> Grip.BR
        // a side: only that side grows (the box keeps 3:4, so the other axis follows around its middle)
        at.y in b[1]..b[3] && kotlin.math.abs(at.x - b[0]) <= edge -> Grip.L
        at.y in b[1]..b[3] && kotlin.math.abs(at.x - b[2]) <= edge -> Grip.R
        at.x in b[0]..b[2] && kotlin.math.abs(at.y - b[1]) <= edge -> Grip.T
        at.x in b[0]..b[2] && kotlin.math.abs(at.y - b[3]) <= edge -> Grip.B
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
    val h0 = bt - t
    val w = when (grip) {
        Grip.T -> (h0 - my) * Crop.RATIO
        Grip.B -> (h0 + my) * Crop.RATIO
        Grip.L -> w0 - mx
        Grip.R -> w0 + mx
        else -> w0 + grow
    }.coerceAtLeast(g.minW)
    val h = w / Crop.RATIO
    val midX = (l + r) / 2
    val midY = (t + bt) / 2
    when (grip) {
        Grip.T -> { t = bt - h; l = midX - w / 2; r = midX + w / 2 }
        Grip.B -> { bt = t + h; l = midX - w / 2; r = midX + w / 2 }
        Grip.L -> { l = r - w; t = midY - h / 2; bt = midY + h / 2 }
        Grip.R -> { r = l + w; t = midY - h / 2; bt = midY + h / 2 }
        Grip.MOVE -> { l += mx; r += mx; t += my; bt += my }
        Grip.BR -> { r = l + w; bt = t + h }
        Grip.BL -> { l = r - w; bt = t + h }
        Grip.TR -> { r = l + w; t = bt - h }
        Grip.TL -> { l = r - w; t = bt - h }
        Grip.NONE -> return null
    }
    return framingOf(l, t, r, bt, f.rotation, g)
}

/** The photo stays still; the 3:4 box is dragged by its corners or sides (size) and its inside (position), never beyond the photo. */
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
            drawFramed(img, f, b[0], b[1], bw)      // inside the box: exactly what will be saved, mirrored backdrop included (no black strips)
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
            if (guide) {
                // legend: what each guide is
                drawIntoCanvas { c ->
                    val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.dp.toPx(); setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK) }
                    val sb = CoverFrame.safeBox()
                    tp.color = android.graphics.Color.WHITE
                    c.nativeCanvas.drawText("Zona segura (la prenda debe caber)", b[0] + sb[0] * bw + 6.dp.toPx(), b[1] + sb[1] * bw + 14.dp.toPx(), tp)
                    if (garment != null) {
                        tp.color = 0xFF4DD0E1.toInt()
                        c.nativeCanvas.drawText("Prenda detectada", b[0] + garment[2] * (bw / Crop.OUT_W) + 6.dp.toPx(), b[3] - garment[1] * (bw / Crop.OUT_W) - 6.dp.toPx(), tp)
                    }
                }
            }
            drawRect(Color.White, Offset(b[0], b[1]), GSize(bw, b[3] - b[1]), style = Stroke(width = 1.5.dp.toPx()))
            val len = 24.dp.toPx()
            val th = 4.dp.toPx()
            fun corner(x: Float, y: Float, sx: Float, sy: Float) {
                drawLine(Color.White, Offset(x - sx * th / 2, y), Offset(x + sx * len, y), th)
                drawLine(Color.White, Offset(x, y - sy * th / 2), Offset(x, y + sy * len), th)
            }
            corner(b[0], b[1], 1f, 1f); corner(b[2], b[1], -1f, 1f); corner(b[0], b[3], 1f, -1f); corner(b[2], b[3], -1f, -1f)
            // a short bar in the middle of each side: they can be dragged on their own
            val bar = 14.dp.toPx()
            val mx = (b[0] + b[2]) / 2
            val my = (b[1] + b[3]) / 2
            drawLine(Color.White, Offset(mx - bar, b[1]), Offset(mx + bar, b[1]), th)
            drawLine(Color.White, Offset(mx - bar, b[3]), Offset(mx + bar, b[3]), th)
            drawLine(Color.White, Offset(b[0], my - bar), Offset(b[0], my + bar), th)
            drawLine(Color.White, Offset(b[2], my - bar), Offset(b[2], my + bar), th)
        }
    }
}

/** Rectangle (x0, y0, x1, y1 as shares of the photo) around the garment, each side dragged on its own: «Marcar prenda». */
@Composable
private fun MarkArea(img: ImageBitmap, rect: FloatArray, onChange: (FloatArray) -> Unit) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(rect)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val areaW = with(density) { maxWidth.toPx() }
        val areaH = with(density) { maxHeight.toPx() }
        val d = minOf(areaW / img.width, areaH / img.height) * 0.97f
        val pw = d * img.width
        val ph = d * img.height
        val ox = (areaW - pw) / 2
        val oy = (areaH - ph) / 2
        val slop = with(density) { 36.dp.toPx() }
        var sides by remember { mutableStateOf(booleanArrayOf(false, false, false, false)) }   // left, top, right, bottom being dragged
        Canvas(
            Modifier.fillMaxSize().pointerInput(img, areaW, areaH) {
                detectDragGestures(
                    onDragStart = { at ->
                        val r = current
                        val x = floatArrayOf(ox + r[0] * pw, oy + r[1] * ph, ox + r[2] * pw, oy + r[3] * ph)
                        val inX = at.x in x[0] - slop..x[2] + slop
                        val inY = at.y in x[1] - slop..x[3] + slop
                        sides = booleanArrayOf(
                            inY && kotlin.math.abs(at.x - x[0]) <= slop,
                            inX && kotlin.math.abs(at.y - x[1]) <= slop,
                            inY && kotlin.math.abs(at.x - x[2]) <= slop,
                            inX && kotlin.math.abs(at.y - x[3]) <= slop,
                        )
                    },
                    onDragEnd = { sides = booleanArrayOf(false, false, false, false) },
                    onDragCancel = { sides = booleanArrayOf(false, false, false, false) },
                    onDrag = { change, drag ->
                        change.consume()
                        val r = current.copyOf()
                        val gap = 0.05f
                        if (sides[0]) r[0] = (r[0] + drag.x / pw).coerceIn(0f, r[2] - gap)
                        if (sides[1]) r[1] = (r[1] + drag.y / ph).coerceIn(0f, r[3] - gap)
                        if (sides[2]) r[2] = (r[2] + drag.x / pw).coerceIn(r[0] + gap, 1f)
                        if (sides[3]) r[3] = (r[3] + drag.y / ph).coerceIn(r[1] + gap, 1f)
                        onChange(r)
                    },
                )
            },
        ) {
            drawIntoCanvas { c -> c.nativeCanvas.drawBitmap(img.asAndroidBitmap(), null, android.graphics.RectF(ox, oy, ox + pw, oy + ph), null) }
            val l = ox + rect[0] * pw; val t = oy + rect[1] * ph; val r = ox + rect[2] * pw; val b = oy + rect[3] * ph
            val dim = Color.Black.copy(alpha = 0.5f)
            drawRect(dim, Offset(ox, oy), GSize(pw, t - oy))
            drawRect(dim, Offset(ox, b), GSize(pw, oy + ph - b))
            drawRect(dim, Offset(ox, t), GSize(l - ox, b - t))
            drawRect(dim, Offset(r, t), GSize(ox + pw - r, b - t))
            val cyan = Color(0xFF4DD0E1)
            drawRect(cyan, Offset(l, t), GSize(r - l, b - t), style = Stroke(width = 2.dp.toPx()))
            val bar = 18.dp.toPx(); val th = 5.dp.toPx()
            val mx = (l + r) / 2; val my = (t + b) / 2
            drawLine(cyan, Offset(mx - bar, t), Offset(mx + bar, t), th); drawLine(cyan, Offset(mx - bar, b), Offset(mx + bar, b), th)
            drawLine(cyan, Offset(l, my - bar), Offset(l, my + bar), th); drawLine(cyan, Offset(r, my - bar), Offset(r, my + bar), th)
            for ((cx, cy) in listOf(l to t, r to t, l to b, r to b)) drawCircle(cyan, 6.dp.toPx(), Offset(cx, cy))
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
                framing = Crop.cover(img.width, img.height)
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
                            onClick = { touched = true; manual = true; framing = Crop.cover(img.width, img.height); note = "" },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) { OneLine("Original") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val g = garment
            val m = if (isCover && fr != null && g != null && img != null) remember(fr, g, img) { CoverFrame.margins(g, img.width, fr.placement(), Crop.OUT_W) } else null
            // the work area takes everything the panel leaves
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (img == null || fr == null) return@Box
                if (marking) MarkArea(img, markRect) { markRect = it }
                else CropArea(img, fr, isCover, m) { framing = it; touched = true; manual = true; note = "" }
            }
            val off = if (m != null && g != null && img != null && fr != null) remember(fr, g, img) { CoverFrame.offCentre(g, img.width, img.height, fr.placement(), Crop.OUT_W) } else 0
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
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        BottomActions(content)
    }
}

private val PREVIEW_NAMES = listOf(0.75f to "Ficha 3:4", 0.8f to "Móvil 4:5", 2f / 3f to "PC 2:3", 1f to "Cuadro 1:1")

/**
 * One preview in big, same proportions as the real thing: what Vinted would show in that container. It is drawn from a bigger copy
 * of the photo than the editor's, so it is as sharp as the photo allows. «Resaltar relleno» tints what is not photo but mirrored
 * backdrop; two fingers zoom to look at an edge, a double tap goes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(source: Uri, quick: ImageBitmap, f: Framing, ratio: Float, onPick: (Float) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val sharp by produceState<ImageBitmap?>(null, source) {
        value = withContext(Dispatchers.IO) { runCatching { loadPhoto(context, source, 3000) }.getOrNull() }
    }
    val img = sharp ?: quick
    var showFill by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Scaffold(
        topBar = { Bar(PREVIEW_NAMES.firstOrNull { it.first == ratio }?.second.orEmpty().replace(" ", " · "), onBack = onClose) },
        bottomBar = {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((r, name) in PREVIEW_NAMES) {
                        FilterChip(
                            selected = r == ratio,
                            onClick = { zoom = 1f; pan = Offset.Zero; onPick(r) },
                            label = { Text(name.substringBefore(' '), maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showFill = !showFill }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Resaltar relleno", style = MaterialTheme.typography.bodyLarge)
                        Text("Tiñe lo que es fondo espejado y no foto", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = showFill, onCheckedChange = { showFill = it })
                }
                Text("Pellizca para ampliar · doble toque para volver", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            val w = minOf(maxWidth, maxHeight * ratio)
            Box(
                Modifier.width(w).aspectRatio(ratio).clip(RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, dragged, scaleBy, _ ->
                            val z = (zoom * scaleBy).coerceIn(1f, 5f)
                            val mx = size.width * (z - 1f) / 2f
                            val my = size.height * (z - 1f) / 2f
                            zoom = z
                            pan = Offset((pan.x + dragged.x).coerceIn(-mx, mx), (pan.y + dragged.y).coerceIn(-my, my))
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero }) }
                    .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
            ) {
                Canvas(Modifier.fillMaxSize().background(Color(0xFF2A2C2F))) {
                    val fw = if (ratio >= Crop.RATIO) size.width else size.height * Crop.RATIO
                    val fh = fw / Crop.RATIO
                    val left = (size.width - fw) / 2f
                    val top = (size.height - fh) / 2f
                    drawFramed(img, f, left, top, fw)
                    if (showFill) {
                        // everything of the frame the photo does not reach: the part that is mirrored backdrop
                        val iw = f.scale * fw
                        val ih = iw * img.height / img.width
                        val cx = left + fw / 2 + f.dx * fw
                        val cy = top + fh / 2 + f.dy * fw
                        withTransform({
                            rotate(f.rotation, Offset(cx, cy))
                            clipRect(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2, ClipOp.Difference)
                            rotate(-f.rotation, Offset(cx, cy))
                        }) { drawRect(Color(0xCCFF2D9A), Offset(left, top), GSize(fw, fh)) }
                    }
                }
            }
        }
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
    val needX = sb[0] * Crop.OUT_W * 0.8f       // what the narrowest preview leaves the garment, with a little tolerance
    val needY = sb[1] * Crop.OUT_W * 0.8f
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

/**
 * How the framed photo looks in a container of another shape: the 3:4 image fills it and what falls outside is cut,
 * as Vinted does in its grids. If the garment shows complete in all three, it is safe on any phone.
 */
@Composable
private fun FramePreview(img: ImageBitmap, f: Framing, bg: Color, ratio: Float, label: String, height: Dp, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.height(height).aspectRatio(ratio).clip(RoundedCornerShape(6.dp)).background(bg)) {
            val fw = if (ratio >= Crop.RATIO) size.width else size.height * Crop.RATIO
            val fh = fw / Crop.RATIO
            val left = (size.width - fw) / 2f
            val top = (size.height - fh) / 2f
            drawFramed(img, f, left, top, fw)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    }
}

/** The same drawing the saved JPEG gets (see [Crop.draw]), so the previews do not lie. */
private fun DrawScope.drawFramed(img: ImageBitmap, f: Framing, left: Float, top: Float, fw: Float) {
    drawIntoCanvas { Crop.draw(it.nativeCanvas, img.asAndroidBitmap(), f, left, top, fw) }
}
