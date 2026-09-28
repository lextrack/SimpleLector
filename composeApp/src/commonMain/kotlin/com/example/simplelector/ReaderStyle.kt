package com.example.simplelector

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max

fun Book.progressPercent(): Int = ((progressPage.toFloat() / max(1, totalPages).toFloat()) * 100).toInt()

fun Book.libraryProgressLabel(): String =
    if (hasRealPageCount) "$progressPage/$totalPages" else appStrings().unopened

fun Book.libraryProgressDetail(): String =
    if (hasRealPageCount) "${progressPercent()}%" else appStrings().tapToRead

fun formatColor(format: String): Color = when (format.lowercase()) {
    "pdf" -> Color(0xFFB3261E)
    "epub" -> Color(0xFF1B6E43)
    "md", "markdown" -> Color(0xFF8C5A22)
    "mobi", "azw", "azw3" -> Color(0xFF5A3E9C)
    "cbz", "cbr" -> Color(0xFF9A5A00)
    else -> Color(0xFF356B8C)
}

fun lightReaderColors(theme: ReaderTheme) = when (theme) {
    ReaderTheme.Sepia -> lightColorScheme(
        background = Color(0xFFE7D7B8),
        surface = Color(0xFFF1E3C7),
        surfaceVariant = Color(0xFFD9C6A4),
        onSurface = Color(0xFF241A10),
        onSurfaceVariant = Color(0xFF4E3C28),
        primary = Color(0xFF6A4B22),
    )
    else -> lightColorScheme()
}

fun darkReaderColors(theme: ReaderTheme) = when (theme) {
    ReaderTheme.PurplePink -> darkColorScheme(
        primary = Color(0xFFF2A7D6),
        onPrimary = Color(0xFF580038),
        primaryContainer = Color(0xFF7A1557),
        onPrimaryContainer = Color(0xFFFFD7EA),
        secondary = Color(0xFFD6B9FF),
        onSecondary = Color(0xFF3B1A63),
        secondaryContainer = Color(0xFF54377C),
        onSecondaryContainer = Color(0xFFECDCFF),
        tertiary = Color(0xFFFFB1C8),
        onTertiary = Color(0xFF651B35),
        tertiaryContainer = Color(0xFF86334F),
        onTertiaryContainer = Color(0xFFFFD9E2),
        background = Color(0xFF1A1022),
        onBackground = Color(0xFFF5EAF3),
        surface = Color(0xFF22162B),
        onSurface = Color(0xFFF5EAF3),
        surfaceVariant = Color(0xFF4C3D52),
        onSurfaceVariant = Color(0xFFD3C2D0),
        outline = Color(0xFF9E8C9A),
    )
    ReaderTheme.DarkSepia -> darkColorScheme(
        background = Color(0xFF18120D),
        surface = Color(0xFF221A14),
        surfaceVariant = Color(0xFF30241B),
        onSurface = Color(0xFFF1E4D0),
        onSurfaceVariant = Color(0xFFD0B89C),
        primary = Color(0xFFD29A52),
    )
    else -> darkColorScheme(
        background = Color(0xFF101113),
        surface = Color(0xFF181A1D),
        surfaceVariant = Color(0xFF24272B),
        primary = Color(0xFF9ACBFF),
    )
}

fun readerPageColor(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.PurplePink -> Color(0xFF1C1024)
    ReaderTheme.Dark -> Color(0xFF111315)
    ReaderTheme.Sepia -> Color(0xFFF0DFC0)
    ReaderTheme.DarkSepia -> Color(0xFF1B140F)
    else -> Color(0xFFFFFCF7)
}

fun readerTextColor(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.PurplePink -> Color(0xFFF7EAF5)
    ReaderTheme.Dark -> Color(0xFFE8EAED)
    ReaderTheme.Sepia -> Color(0xFF261B10)
    ReaderTheme.DarkSepia -> Color(0xFFF3E6D2)
    else -> Color(0xFF1C1B1F)
}
