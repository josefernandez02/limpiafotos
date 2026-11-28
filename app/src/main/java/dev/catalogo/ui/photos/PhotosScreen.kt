package dev.catalogo.ui.photos

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.catalog.Catalog
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.photo.CropEdit
import dev.catalogo.core.photo.CropHistory
import dev.catalogo.data.CatalogRepository
import dev.catalogo.data.photo.AutoFrame
import dev.catalogo.data.photo.Framing
import dev.catalogo.data.photo.PhotoCropper
import dev.catalogo.data.store.CropHistoryStore
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.common.Bar
import dev.catalogo.ui.common.Confirm
import dev.catalogo.ui.common.Empty
import dev.catalogo.ui.common.OneLine
import dev.catalogo.ui.common.Thumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A photo in the editor: its source and, once cropped, the new JPEG. */
internal data class Slot(val source: Uri, val bytes: ByteArray? = null, val key: Int, val edit: CropEdit? = null, val framing: Framing? = null)

/**
 * Order, cover (the first photo), removal and crop of one account's photos.
 * Nothing is written until "Guardar".
 */
@Composable
fun PhotosScreen(vm: MainViewModel, p: CatalogState, number: Int, accountId: String) {
    val vintedAccount = Catalog.findAccount(p, accountId)
    val saving by vm.savingPhotos.collectAsStateWithLifecycle()
    var slots by remember { mutableStateOf<List<Slot>?>(null) }
    var original by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var cropping by remember { mutableStateOf<Int?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoWorking by remember { mutableStateOf(false) }

    LaunchedEffect(number, accountId) {
        val photos = vm.editablePhotos(number, accountId)
        original = photos
        val log = CropHistory.aligned(CropHistoryStore.load(context, accountId, number), photos.size)
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
            Bar(vintedAccount?.label?.let { "Fotos de $it" } ?: "Fotos", onBack = if (saving) null else leave, actions = {
                Button(
                    onClick = {
                        CropHistoryStore.save(context, accountId, number, list.orEmpty().map { it.edit })
                        vm.savePhotos(number, accountId, list.orEmpty().map { CatalogRepository.EditedPhoto(it.source, it.bytes) })
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
                        .aspectRatio(PhotoCropper.RATIO)
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
