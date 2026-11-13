package dev.catalogo.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the app does with articles, the AI and the PC, in a file that
 * rewrites itself (two files of [MAX_BYTES] at most). It also goes to the PC
 * ([Drafts] uploads it), so a failure can be traced next to the PC's own log:
 * what failed, where, when and why. Keys and tokens are never written.
 */
object AppLog {
    private const val MAX_BYTES = 512 * 1024L
    private var file: File? = null
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun init(context: Context) {
        val dir = File(context.filesDir, "registro").apply { mkdirs() }
        file = File(dir, "app.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            e("app", "cierre inesperado en ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
        i("app", "inicio")
    }

    fun i(tag: String, msg: String) = write("INFO", tag, msg, null)
    fun w(tag: String, msg: String, e: Throwable? = null) = write("AVISO", tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = write("ERROR", tag, msg, e)

    /** The last [maxBytes] of the log (both files), for the PC. */
    @Synchronized
    fun tail(maxBytes: Int = 256 * 1024): String {
        val f = file ?: return ""
        val old = File(f.parentFile, "app.1.log")
        val text = (if (old.exists()) old.readText() else "") + (if (f.exists()) f.readText() else "")
        return if (text.length > maxBytes) text.takeLast(maxBytes) else text
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String, e: Throwable?) {
        val clean = msg.replace(Regex("(?i)(key=|token|authorization:?)\\S*"), "$1***")
        when (level) {
            "ERROR" -> Log.e(tag, clean, e)
            "AVISO" -> Log.w(tag, clean, e)
            else -> Log.i(tag, clean)
        }
        val f = file ?: return
        runCatching {
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "app.1.log")
                old.delete()
                f.renameTo(old)
            }
            val line = buildString {
                append(time.format(Date())).append(' ').append(level.padEnd(5)).append(' ').append(tag).append(": ").append(clean)
                if (e != null) append('\n').append(Log.getStackTraceString(e).lines().take(25).joinToString("\n"))
                append('\n')
            }
            f.appendText(line)
        }
    }
}
