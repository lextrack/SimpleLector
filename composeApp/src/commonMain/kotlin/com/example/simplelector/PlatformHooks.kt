package com.example.simplelector

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformDocumentPage(
    sourceId: String,
    sourceVersionKey: String,
    format: String,
    pageNumber: Int,
    theme: ReaderTheme,
    zoomLevel: Float = 1f,
    onZoomChange: ((Float) -> Unit)? = null,
    onPreviousPage: (() -> Unit)? = null,
    onNextPage: (() -> Unit)? = null,
    onRenderError: ((Throwable) -> Unit)? = null,
): Boolean

@Composable
expect fun PlatformReaderWindowEffect(
    fullscreen: Boolean,
    keepScreenOn: Boolean,
    lockRotation: Boolean,
)
