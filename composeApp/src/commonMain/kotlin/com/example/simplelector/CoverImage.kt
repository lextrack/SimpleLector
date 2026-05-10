package com.example.simplelector

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeCoverImage(bytes: ByteArray): ImageBitmap?
