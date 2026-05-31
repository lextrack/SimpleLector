package com.example.simplelector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

internal enum class AndroidImageScaleMode {
    Fit,
    CenterCrop,
}

internal enum class AndroidImageColorConfig {
    Argb8888,
    Rgb565,
}

internal data class AndroidBitmapRequest(
    val maxWidth: Int,
    val maxHeight: Int,
    val scaleMode: AndroidImageScaleMode,
    val colorConfig: AndroidImageColorConfig,
    val sourceLabel: String,
)

internal data class AndroidCompressedImageRequest(
    val maxWidth: Int,
    val maxHeight: Int,
    val scaleMode: AndroidImageScaleMode,
    val colorConfig: AndroidImageColorConfig,
    val quality: Int,
    val sourceLabel: String,
)

internal object AndroidImagePipeline {
    private const val NativeImageLogTag = "SimpleLectorNative"

    fun decodeBitmapFromFile(
        file: File,
        request: AndroidBitmapRequest,
    ): Bitmap? {
        AndroidNativeImageBridge.decodeBitmapFile(file, request)?.let { return it }
        return decodeBitmapFileFallback(file, request)
    }

    fun decodeBitmapFromBytes(
        encodedBytes: ByteArray,
        request: AndroidBitmapRequest,
    ): Bitmap? {
        AndroidNativeImageBridge.decodeBitmapBytes(encodedBytes, request)?.let { return it }
        return decodeBitmapBytesFallback(encodedBytes, request)
    }

    fun createCompressedBytesFromFile(
        file: File,
        request: AndroidCompressedImageRequest,
    ): ByteArray? {
        AndroidNativeImageBridge.decodeAndCompressFile(file, request)?.let { return it }
        val bitmapRequest = AndroidBitmapRequest(
            maxWidth = request.maxWidth,
            maxHeight = request.maxHeight,
            scaleMode = request.scaleMode,
            colorConfig = request.colorConfig,
            sourceLabel = request.sourceLabel,
        )
        val bitmap = decodeBitmapFileFallback(file, bitmapRequest) ?: return null
        return bitmap.compressToWebpAndRecycle(request.quality)
    }

    fun decodeReaderBitmapFromFile(
        file: File,
        maxDimension: Int,
    ): Bitmap? = decodeBitmapFromFile(
        file = file,
        request = AndroidBitmapRequest(
            maxWidth = maxDimension,
            maxHeight = maxDimension,
            scaleMode = AndroidImageScaleMode.Fit,
            colorConfig = AndroidImageColorConfig.Rgb565,
            sourceLabel = "reader image ${file.name}",
        ),
    )

    fun decodeReaderBitmapFromBytes(
        encodedBytes: ByteArray,
        sourceLabel: String,
        maxDimension: Int,
    ): Bitmap? = decodeBitmapFromBytes(
        encodedBytes = encodedBytes,
        request = AndroidBitmapRequest(
            maxWidth = maxDimension,
            maxHeight = maxDimension,
            scaleMode = AndroidImageScaleMode.Fit,
            colorConfig = AndroidImageColorConfig.Rgb565,
            sourceLabel = "reader image $sourceLabel",
        ),
    )

    fun createThumbnailBytesFromFile(
        file: File,
        maxDimension: Int,
        quality: Int,
    ): ByteArray? = createCompressedBytesFromFile(
        file = file,
        request = AndroidCompressedImageRequest(
            maxWidth = maxDimension,
            maxHeight = maxDimension,
            scaleMode = AndroidImageScaleMode.CenterCrop,
            colorConfig = AndroidImageColorConfig.Rgb565,
            quality = quality,
            sourceLabel = "cover/thumbnail ${file.name}",
        ),
    )

    private fun decodeBitmapFileFallback(
        file: File,
        request: AndroidBitmapRequest,
    ): Bitmap? {
        debugLog(
            NativeImageLogTag,
            "Falling back to BitmapFactory for ${request.sourceLabel} (${request.maxWidth}x${request.maxHeight}, mode=${request.scaleMode}, config=${request.colorConfig})",
        )

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxWidth = request.maxWidth,
                maxHeight = request.maxHeight,
                scaleMode = request.scaleMode,
            )
            inPreferredConfig = request.colorConfig.toBitmapConfig()
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return postProcessBitmap(decoded, request.maxWidth, request.maxHeight, request.scaleMode)
    }

    private fun decodeBitmapBytesFallback(
        encodedBytes: ByteArray,
        request: AndroidBitmapRequest,
    ): Bitmap? {
        debugLog(
            NativeImageLogTag,
            "Falling back to BitmapFactory for ${request.sourceLabel} (${request.maxWidth}x${request.maxHeight}, mode=${request.scaleMode}, config=${request.colorConfig})",
        )

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(encodedBytes, 0, encodedBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxWidth = request.maxWidth,
                maxHeight = request.maxHeight,
                scaleMode = request.scaleMode,
            )
            inPreferredConfig = request.colorConfig.toBitmapConfig()
        }
        val decoded = BitmapFactory.decodeByteArray(encodedBytes, 0, encodedBytes.size, options) ?: return null
        return postProcessBitmap(decoded, request.maxWidth, request.maxHeight, request.scaleMode)
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int,
        scaleMode: AndroidImageScaleMode,
    ): Int {
        var sampleSize = 1
        var scaledWidth = width
        var scaledHeight = height
        while (true) {
            val nextSampleSize = sampleSize * 2
            val nextWidth = width / nextSampleSize
            val nextHeight = height / nextSampleSize
            if (nextWidth <= 0 || nextHeight <= 0) break
            val keepSampling = when (scaleMode) {
                AndroidImageScaleMode.Fit -> nextWidth > maxWidth || nextHeight > maxHeight
                AndroidImageScaleMode.CenterCrop -> nextWidth > maxWidth && nextHeight > maxHeight
            }
            if (!keepSampling) break
            sampleSize = nextSampleSize
            scaledWidth = nextWidth
            scaledHeight = nextHeight
        }
        return if (scaledWidth <= 0 || scaledHeight <= 0) 1 else sampleSize.coerceAtLeast(1)
    }

    private fun postProcessBitmap(
        bitmap: Bitmap,
        maxWidth: Int,
        maxHeight: Int,
        scaleMode: AndroidImageScaleMode,
    ): Bitmap =
        when (scaleMode) {
            AndroidImageScaleMode.Fit -> bitmap
            AndroidImageScaleMode.CenterCrop -> bitmap.centerCrop(maxWidth, maxHeight)
        }

    private fun Bitmap.centerCrop(
        maxWidth: Int,
        maxHeight: Int,
    ): Bitmap {
        val cropWidth = minOf(width, maxWidth)
        val cropHeight = minOf(height, maxHeight)
        if (cropWidth <= 0 || cropHeight <= 0) return this
        if (cropWidth == width && cropHeight == height) return this
        val offsetX = ((width - cropWidth) / 2).coerceAtLeast(0)
        val offsetY = ((height - cropHeight) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(this, offsetX, offsetY, cropWidth, cropHeight)
        recycle()
        return cropped
    }

    private fun AndroidImageColorConfig.toBitmapConfig(): Bitmap.Config =
        when (this) {
            AndroidImageColorConfig.Argb8888 -> Bitmap.Config.ARGB_8888
            AndroidImageColorConfig.Rgb565 -> Bitmap.Config.RGB_565
        }

    private fun Bitmap.compressToWebpAndRecycle(quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            recycle()
            out.toByteArray()
        }
}
