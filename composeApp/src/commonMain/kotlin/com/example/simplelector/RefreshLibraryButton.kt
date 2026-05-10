package com.example.simplelector

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate

@Composable
fun RefreshLibraryButton(
    enabled: Boolean,
    isRefreshing: Boolean,
    onClick: () -> Unit,
) {
    val strings = rememberAppStrings()
    val transition = rememberInfiniteTransition(label = "refreshLibrary")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "refreshLibraryRotation",
    )

    IconButton(
        enabled = enabled,
        onClick = onClick,
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = if (isRefreshing) strings.loadingLibraryTitle else strings.loadingLibraryTitle,
            modifier = Modifier.rotate(if (isRefreshing) rotation else 0f),
        )
    }
}
