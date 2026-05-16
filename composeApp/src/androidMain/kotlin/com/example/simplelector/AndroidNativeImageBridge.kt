package com.example.simplelector

import android.graphics.Bitmap
import java.io.File

internal object AndroidNativeImageBridge {
    private const val NativeImageLogTag = "SimpleLectorNative"
    private var loadAttempted = false
    private var available = false

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (!loadAttempted) {
            val loadResult = runCatching {
                System.loadLibrary("simplelector_native")
                true
            }
            available = loadResult.getOrDefault(false)
            loadAttempted = true
            if (available) {
                debugLog(NativeImageLogTag, "Native image library loaded")
            } else {
                debugLog(
                    NativeImageLogTag,
                    "Native image library unavailable, using Kotlin fallback: ${loadResult.exceptionOrNull()?.message ?: "unknown error"}",
                )
            }
        }
        return available
    }

    fun isAvailable(): Boolean = ensureLoaded()

    fun decodeScaledBitmapFile(
        file: File,
        maxDimension: Int,
    ): Bitmap? {
        if (maxDimension <= 0 || !ensureLoaded()) return null
        val encodedBytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return decodeScaledBitmapBytes(
            encodedBytes = encodedBytes,
            maxDimension = maxDimension,
            sourceLabel = file.name,
        )
    }

    fun decodeScaledBitmapBytes(
        encodedBytes: ByteArray,
        maxDimension: Int,
        sourceLabel: String,
    ): Bitmap? {
        if (maxDimension <= 0 || encodedBytes.isEmpty() || !ensureLoaded()) return null
        val bitmap = nativeDecodeScaledBitmap(encodedBytes, maxDimension)
        if (bitmap != null) {
            debugLog(
                NativeImageLogTag,
                "Native decode OK for $sourceLabel (maxDimension=$maxDimension, ${bitmap.width}x${bitmap.height})",
            )
        } else {
            debugLog(
                NativeImageLogTag,
                "Native decode returned null for $sourceLabel (maxDimension=$maxDimension)",
            )
        }
        return bitmap
    }

    private external fun nativeDecodeScaledBitmap(
        encodedBytes: ByteArray,
        maxDimension: Int,
    ): Bitmap?
}
