package dev.catalogo.ui.sales

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.catalogo.core.model.CatalogState
import dev.catalogo.core.sales.ShippingLabels
import dev.catalogo.ui.MainViewModel
import dev.catalogo.ui.common.BigButton
import dev.catalogo.ui.common.Muted
import dev.catalogo.ui.common.Tile
import dev.catalogo.ui.common.Tone

/**
 * The shipping labels: ask the PC for them, see how it goes and open the PDF in the printer's
 * app. For every sale still to be sent, or only for the order [only].
 */
@Composable
fun LabelsCard(vm: MainViewModel, p: CatalogState, only: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val text by vm.labelsText.collectAsStateWithLifecycle()
    val job = state.labels
    val count = remember(p, only) { ShippingLabels.requests(p, only).size }
    val working = job.lot != 0L
    val finished = job.file.isNotBlank() || job.warnings.isNotEmpty()
    if (!working && !finished && count == 0) return

    Tile {
        when {
            working -> {
                Text("Sacando etiquetas", style = MaterialTheme.typography.titleMedium)
                Muted(text.ifBlank { "Esperando al PC…" })
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton(onClick = { vm.stopLabels() }, modifier = Modifier.fillMaxWidth()) { Text("Parar") }
            }
            finished -> {
                if (job.file.isNotBlank()) {
                    Text(
                        if (job.count == 1) "1 etiqueta en Descargas" else "${job.count} etiquetas en Descargas",
                        style = MaterialTheme.typography.titleMedium, color = SaleGreen,
                    )
                } else {
                    Text("No ha salido ninguna etiqueta", style = MaterialTheme.typography.titleMedium, color = Tone.Warn.color)
                }
                for (warning in job.warnings) Muted(warning)
                if (job.file.isNotBlank()) BigButton("Abrir en 4BarCode", onClick = { vm.openLabels() }, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = { vm.dismissLabels() }, modifier = Modifier.fillMaxWidth()) { Text("Quitar") }
            }
        }
        if (!working && !finished) Text("Etiquetas de envío", style = MaterialTheme.typography.titleMedium)
        if (!working && count > 0) {
            val ask = when {
                only != null -> "Sacar su etiqueta"
                count == 1 -> "Sacar 1 etiqueta"
                else -> "Sacar $count etiquetas"
            }
            FilledTonalButton(onClick = { vm.printLabels(only) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) {
                Text(ask)
            }
        }
    }
}

/**
 * Hands the PDF to 4BarCode; if it does not offer itself for it, lets the user choose.
 * Returns what to tell the user when it could not go straight in.
 */
fun openInPrinter(context: Context, pdf: Uri): String? {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(pdf, "application/pdf")
    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, pdf)
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
    val packages = context.packageManager
    for (intent in listOf(view, send)) {
        val found = packages.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val i = ShippingLabels.printer(found.map { it.activityInfo.packageName to it.loadLabel(packages).toString() })
        if (i < 0) continue
        val target = found[i].activityInfo
        val direct = Intent(intent).setComponent(ComponentName(target.packageName, target.name)).addFlags(flags)
        if (runCatching { context.startActivity(direct) }.isSuccess) return null
    }
    val chooser = Intent.createChooser(view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Abrir etiquetas").addFlags(flags)
    return if (runCatching { context.startActivity(chooser) }.isSuccess) {
        "4BarCode no se ofrece para abrirlo: elige otra o impórtalo desde Descargas"
    } else {
        "No hay ninguna app que abra el PDF: impórtalo desde Descargas en 4BarCode"
    }
}
