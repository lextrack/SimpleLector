package com.example.simplelector

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun AppBackButton(
    theme: ReaderTheme,
    onClick: () -> Unit,
) {
    val strings = rememberAppStrings()
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = backButtonContainerColor(theme),
            contentColor = backButtonContentColor(theme),
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(strings.back)
    }
}

@Composable
fun libraryPathFolderColor(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.Sepia -> Color(0xFF8B4A12)
    ReaderTheme.DarkSepia -> Color(0xFFD29A52)
    ReaderTheme.Dark -> Color(0xFF91C4FF)
    ReaderTheme.Light -> MaterialTheme.colorScheme.primary
}

@Composable
fun libraryPathFileColor(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.Sepia -> Color(0xFF473526)
    ReaderTheme.DarkSepia -> Color(0xFFE0C7AA)
    ReaderTheme.Dark -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
    ReaderTheme.Light -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
}

@Composable
private fun backButtonContainerColor(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.Sepia -> Color(0xFFB7732F)
    ReaderTheme.DarkSepia -> Color(0xFF8F6130)
    ReaderTheme.Dark -> Color(0xFF314355)
    ReaderTheme.Light -> MaterialTheme.colorScheme.primary
}

@Composable
private fun backButtonContentColor(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.Sepia -> Color(0xFFFFF4DE)
    ReaderTheme.DarkSepia -> Color(0xFFFFEACD)
    ReaderTheme.Dark -> Color(0xFFEAF4FF)
    ReaderTheme.Light -> MaterialTheme.colorScheme.onPrimary
}
