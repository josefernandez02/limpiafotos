package dev.catalogo.ui.sales

import androidx.compose.runtime.getValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val SPANISH = Locale("es", "ES")

internal val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM", SPANISH)

private val FULL_FORMAT = DateTimeFormatter.ofPattern("d 'de' MMMM yyyy", SPANISH)

internal val MONTH_FORMAT = DateTimeFormatter.ofPattern("LLLL yyyy", SPANISH)

internal fun date(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())

fun longDate(at: Long): String = FULL_FORMAT.format(date(at))
