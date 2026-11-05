package dev.catalogo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Colors = darkColorScheme(
    primary = Color(0xFFECECEC),
    onPrimary = Color(0xFF111213),
    secondary = Color(0xFF8D9095),
    onSecondary = Color(0xFF111213),
    primaryContainer = Color(0xFF2A2C2F),
    onPrimaryContainer = Color(0xFFECECEC),
    secondaryContainer = Color(0xFF2A2C2F),
    onSecondaryContainer = Color(0xFFECECEC),
    background = Color(0xFF111213),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF111213),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF1C1D1F),
    onSurfaceVariant = Color(0xFF8D9095),
    surfaceContainer = Color(0xFF1C1D1F),
    surfaceContainerHigh = Color(0xFF222326),
    surfaceContainerHighest = Color(0xFF2A2C2F),
    surfaceContainerLow = Color(0xFF17181A),
    outline = Color(0xFF2C2E31),
    outlineVariant = Color(0xFF2C2E31),
    error = Color(0xFFE5484D),
    onError = Color(0xFF111213),
)

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)

@Composable
fun CatalogoTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = Colors,
        typography = base.copy(
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        ),
        content = content,
    )
}
