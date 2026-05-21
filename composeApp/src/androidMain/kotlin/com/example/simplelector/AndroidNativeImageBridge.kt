package com.example.simplelector

import android.graphics.Bitmap
import java.io.File

internal object AndroidNativeImageBridge {
    private const val NativeImageLogTag = "SimpleLectorNative"
    private const val ScaleModeFit = 0
    private const val ScaleModeCenterCrop = 1
    private const val ColorConfigArgb8888 = 0
    private const val ColorConfigRgb565 = 1
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

    fun decodeBitmapFile(
        file: File,
        request: AndroidBitmapRequest,
    ): Bitmap? {
        if (request.maxWidth <= 0 || request.maxHeight <= 0 || !ensureLoaded()) return null
        val encodedBytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return decodeBitmapBytes(
            encodedBytes = encodedBytes,
            request = request.copy(sourceLabel = file.name),
        )
    }

    fun decodeBitmapBytes(
        encodedBytes: ByteArray,
        request: AndroidBitmapRequest,
    ): Bitmap? {
        if (request.maxWidth <= 0 || request.maxHeight <= 0 || encodedBytes.isEmpty() || !ensureLoaded()) return null
        val bitmap = nativeDecodeBitmap(
            encodedBytes = encodedBytes,
            maxWidth = request.maxWidth,
            maxHeight = request.maxHeight,
            scaleMode = request.scaleMode.toNativeValue(),
            colorConfig = request.colorConfig.toNativeValue(),
        )
        if (bitmap != null) {
            debugLog(
                NativeImageLogTag,
                "Native decode OK for ${request.sourceLabel} (${request.maxWidth}x${request.maxHeight}, mode=${request.scaleMode}, config=${request.colorConfig}, ${bitmap.width}x${bitmap.height})",
            )
        } else {
            debugLog(
                NativeImageLogTag,
                "Native decode returned null for ${request.sourceLabel} (${request.maxWidth}x${request.maxHeight}, mode=${request.scaleMode}, config=${request.colorConfig})",
            )
        }
        return bitmap
    }

    fun decodeAndCompressFile(
        file: File,
        request: AndroidCompressedImageRequest,
    ): ByteArray? {
        if (request.maxWidth <= 0 || request.maxHeight <= 0 || request.quality !in 1..100 || !ensureLoaded()) return null
        val encodedBytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return decodeAndCompressBytes(
            encodedBytes = encodedBytes,
            request = request.copy(sourceLabel = file.name),
        )
    }

    fun decodeAndCompressBytes(
        encodedBytes: ByteArray,
        request: AndroidCompressedImageRequest,
    ): ByteArray? {
        if (request.maxWidth <= 0 || request.maxHeight <= 0 || request.quality !in 1..100 || encodedBytes.isEmpty() || !ensureLoaded()) return null
        val compressed = nativeDecodeAndCompress(
            encodedBytes = encodedBytes,
            maxWidth = request.maxWidth,
            maxHeight = request.maxHeight,
            scaleMode = request.scaleMode.toNativeValue(),
            colorConfig = request.colorConfig.toNativeValue(),
            quality = request.quality,
        )
        if (compressed != null) {
            debugLog(
                NativeImageLogTag,
                "Native decode+compress OK for ${request.sourceLabel} (${request.maxWidth}x${request.maxHeight}, mode=${request.scaleMode}, config=${request.colorConfig}, quality=${request.quality}, bytes=${compressed.size})",
            )
        } else {
            debugLog(
                NativeImageLogTag,
                "Native decode+compress returned null for ${request.sourceLabel} (${request.maxWidth}x${request.maxHeight}, mode=${request.scaleMode}, config=${request.colorConfig}, quality=${request.quality})",
            )
        }
        return compressed
    }

    private fun AndroidImageScaleMode.toNativeValue(): Int =
        when (this) {
            AndroidImageScaleMode.Fit -> ScaleModeFit
            AndroidImageScaleMode.CenterCrop -> ScaleModeCenterCrop
        }

    private fun AndroidImageColorConfig.toNativeValue(): Int =
        when (this) {
            AndroidImageColorConfig.Argb8888 -> ColorConfigArgb8888
            AndroidImageColorConfig.Rgb565 -> ColorConfigRgb565
        }

    private external fun nativeDecodeBitmap(
        encodedBytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        scaleMode: Int,
        colorConfig: Int,
    ): Bitmap?

    private external fun nativeDecodeAndCompress(
        encodedBytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        scaleMode: Int,
        colorConfig: Int,
        quality: Int,
    ): ByteArray?
}
