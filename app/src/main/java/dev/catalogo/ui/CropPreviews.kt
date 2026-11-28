package dev.catalogo.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.catalogo.data.PhotoCropper
import dev.catalogo.data.Framing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val PREVIEW_NAMES = listOf(0.75f to "Ficha 3:4", 0.8f to "Móvil 4:5", 2f / 3f to "PC 2:3", 1f to "Cuadro 1:1")

/**
 * One preview in big, same proportions as the real thing: what Vinted would show in that container. It is drawn from a bigger copy
 * of the photo than the editor's, so it is as sharp as the photo allows. «Resaltar relleno» tints what is not photo but mirrored
 * backdrop; two fingers zoom to look at an edge, a double tap goes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewScreen(source: Uri, quick: ImageBitmap, f: Framing, ratio: Float, onPick: (Float) -> Unit, onClose: () -> Unit) {
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
                    val fw = if (ratio >= PhotoCropper.RATIO) size.width else size.height * PhotoCropper.RATIO
                    val fh = fw / PhotoCropper.RATIO
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

/**
 * How the framed photo looks in a container of another shape: the 3:4 image fills it and what falls outside is cut,
 * as Vinted does in its grids. If the garment shows complete in all three, it is safe on any phone.
 */
@Composable
internal fun FramePreview(img: ImageBitmap, f: Framing, bg: Color, ratio: Float, label: String, height: Dp, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.height(height).aspectRatio(ratio).clip(RoundedCornerShape(6.dp)).background(bg)) {
            val fw = if (ratio >= PhotoCropper.RATIO) size.width else size.height * PhotoCropper.RATIO
            val fh = fw / PhotoCropper.RATIO
            val left = (size.width - fw) / 2f
            val top = (size.height - fh) / 2f
            drawFramed(img, f, left, top, fw)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    }
}

/** The same drawing the saved JPEG gets (see [PhotoCropper.draw]), so the previews do not lie. */
internal fun DrawScope.drawFramed(img: ImageBitmap, f: Framing, left: Float, top: Float, fw: Float) {
    drawIntoCanvas { PhotoCropper.draw(it.nativeCanvas, img.asAndroidBitmap(), f, left, top, fw) }
}
