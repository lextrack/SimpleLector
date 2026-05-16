package com.example.simplelector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

private const val CbzRenderMaxImageDimension = 2_000
private const val NativeImageLogTag = "SimpleLectorNative"

private data class VisualPageCacheKey(
    val sourceId: String,
    val sourceVersionKey: String,
    val format: String,
    val pageNumber: Int,
    val theme: ReaderTheme,
)

private object VisualPageCache {
    private const val MAX_ENTRIES = 16
    private val pages = LinkedHashMap<VisualPageCacheKey, Bitmap>(MAX_ENTRIES, 0.75f, true)
    private val inFlight = mutableSetOf<VisualPageCacheKey>()

    @Synchronized
    fun get(key: VisualPageCacheKey): Bitmap? = pages[key]

    @Synchronized
    fun size(): Int = pages.size

    @Synchronized
    fun markInFlight(key: VisualPageCacheKey): Boolean =
        if (key in inFlight) {
            false
        } else {
            inFlight += key
            true
        }

    @Synchronized
    fun clearInFlight(key: VisualPageCacheKey) {
        inFlight -= key
    }

    @Synchronized
    fun isInFlight(key: VisualPageCacheKey): Boolean = key in inFlight

    @Synchronized
    fun put(key: VisualPageCacheKey, bitmap: Bitmap) {
        pages[key] = bitmap
        while (pages.size > MAX_ENTRIES) {
            val eldest = pages.entries.firstOrNull() ?: break
            pages.remove(eldest.key)?.recycle()
        }
    }
}

private object CbzPageIndexCache {
    private const val MAX_ENTRIES = 6
    private val indexes = LinkedHashMap<String, List<String>>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun get(sourceId: String): List<String>? = indexes[sourceId]

    @Synchronized
    fun put(sourceId: String, pages: List<String>) {
        indexes[sourceId] = pages
        while (indexes.size > MAX_ENTRIES) {
            val eldest = indexes.entries.firstOrNull() ?: break
            indexes.remove(eldest.key)
        }
    }
}

@Composable
actual fun PlatformDocumentPage(
    sourceId: String,
    sourceVersionKey: String,
    format: String,
    pageNumber: Int,
    theme: ReaderTheme,
    zoomLevel: Float,
    onZoomChange: ((Float) -> Unit)?,
    onPreviousPage: (() -> Unit)?,
    onNextPage: (() -> Unit)?,
    onRenderError: ((Throwable) -> Unit)?,
): Boolean {
    val normalizedFormat = format.lowercase()
    if (normalizedFormat != "pdf" && normalizedFormat != "cbz" && normalizedFormat != "cbr") return false

    val context = LocalContext.current
    var renderError by remember(sourceId, sourceVersionKey, normalizedFormat, pageNumber, theme) { mutableStateOf<Throwable?>(null) }
    val bitmap by produceState<Bitmap?>(initialValue = null, sourceId, sourceVersionKey, normalizedFormat, pageNumber, theme) {
        value = null
        renderError = null
        val cacheKey = VisualPageCacheKey(sourceId, sourceVersionKey, normalizedFormat, pageNumber, theme)
        val shouldLogCache = normalizedFormat == "cbz" || normalizedFormat == "cbr"
        VisualPageCache.get(cacheKey)?.let {
            if (shouldLogCache) {
                debugLog(
                    NativeImageLogTag,
                    "Page cache HIT format=$normalizedFormat page=$pageNumber size=${VisualPageCache.size()}",
                )
            }
            value = it
            return@produceState
        }
        if (shouldLogCache) {
            debugLog(
                NativeImageLogTag,
                "Page cache MISS format=$normalizedFormat page=$pageNumber size=${VisualPageCache.size()}",
            )
        }
        if (!VisualPageCache.markInFlight(cacheKey)) {
            if (shouldLogCache) {
                debugLog(
                    NativeImageLogTag,
                    "Page cache WAIT format=$normalizedFormat page=$pageNumber size=${VisualPageCache.size()}",
                )
            }
            val awaited: Bitmap? = withContext(Dispatchers.IO) {
                var resolved: Bitmap? = null
                while (resolved == null && VisualPageCache.isInFlight(cacheKey)) {
                    resolved = VisualPageCache.get(cacheKey)
                    if (resolved != null) break
                    kotlinx.coroutines.delay(16)
                }
                resolved ?: VisualPageCache.get(cacheKey)
            }
            value = awaited
            return@produceState
        }
        try {
            val rendered = withContext(Dispatchers.IO) {
                renderVisualDocumentPage(
                    contentResolver = context.contentResolver,
                    cacheDir = context.cacheDir,
                    uri = Uri.parse(sourceId),
                    sourceVersionKey = sourceVersionKey,
                    format = normalizedFormat,
                    pageNumber = pageNumber,
                    theme = theme,
                )
            }
            value = rendered?.also {
                VisualPageCache.put(cacheKey, it)
                if (shouldLogCache) {
                    debugLog(
                        NativeImageLogTag,
                        "Page cache STORE format=$normalizedFormat page=$pageNumber size=${VisualPageCache.size()}",
                    )
                }
            }
        } catch (error: Throwable) {
            renderError = error
        } finally {
            VisualPageCache.clearInFlight(cacheKey)
        }
    }
    LaunchedEffect(renderError) {
        renderError?.let { onRenderError?.invoke(it) }
    }

    LaunchedEffect(sourceId, sourceVersionKey, normalizedFormat, pageNumber, theme) {
        withContext(Dispatchers.IO) {
            val shouldLogCache = normalizedFormat == "cbz" || normalizedFormat == "cbr"
            listOf(pageNumber - 1, pageNumber + 1)
                .filter { it > 0 }
                .forEach { adjacentPage ->
                    val cacheKey = VisualPageCacheKey(sourceId, sourceVersionKey, normalizedFormat, adjacentPage, theme)
                    if (VisualPageCache.get(cacheKey) == null) {
                        if (!VisualPageCache.markInFlight(cacheKey)) {
                            if (shouldLogCache) {
                                debugLog(
                                    NativeImageLogTag,
                                    "Prefetch SKIP inflight format=$normalizedFormat page=$adjacentPage from=$pageNumber size=${VisualPageCache.size()}",
                                )
                            }
                            return@forEach
                        }
                        if (shouldLogCache) {
                            debugLog(
                                NativeImageLogTag,
                                "Prefetch MISS format=$normalizedFormat page=$adjacentPage from=$pageNumber size=${VisualPageCache.size()}",
                            )
                        }
                        try {
                            val rendered = renderVisualDocumentPage(
                                contentResolver = context.contentResolver,
                                cacheDir = context.cacheDir,
                                uri = Uri.parse(sourceId),
                                sourceVersionKey = sourceVersionKey,
                                format = normalizedFormat,
                                pageNumber = adjacentPage,
                                theme = theme,
                            ) ?: return@forEach
                            VisualPageCache.put(cacheKey, rendered)
                            if (shouldLogCache) {
                                debugLog(
                                    NativeImageLogTag,
                                    "Prefetch STORE format=$normalizedFormat page=$adjacentPage from=$pageNumber size=${VisualPageCache.size()}",
                                )
                            }
                        } catch (_: Throwable) {
                        } finally {
                            VisualPageCache.clearInFlight(cacheKey)
                        }
                    } else if (shouldLogCache) {
                        debugLog(
                            NativeImageLogTag,
                            "Prefetch HIT format=$normalizedFormat page=$adjacentPage from=$pageNumber size=${VisualPageCache.size()}",
                        )
                    }
                }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(12.dp)
            .background(readerBackground(theme)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            CircularProgressIndicator()
        } else {
            val density = LocalDensity.current
            val widthDp = with(density) { (bitmap!!.width / density.density).dp }
            val heightDp = with(density) { (bitmap!!.height / density.density).dp }
            var gestureZoom by remember(sourceId, pageNumber) { mutableFloatStateOf(zoomLevel) }
            var imageOffset by remember(sourceId, pageNumber) { mutableStateOf(Offset.Zero) }
            LaunchedEffect(zoomLevel, sourceId, pageNumber) {
                gestureZoom = zoomLevel
                if (zoomLevel <= 1.01f) {
                    imageOffset = Offset.Zero
                }
            }
            val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                val updatedZoom = (gestureZoom * zoomChange).coerceIn(0.4f, 3f)
                gestureZoom = if (updatedZoom in 0.92f..1.08f) 1f else updatedZoom
                imageOffset = if (gestureZoom <= 1.01f) {
                    Offset.Zero
                } else {
                    imageOffset + panChange * gestureZoom
                }
                onZoomChange?.invoke(gestureZoom)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clipToBounds()
                    .transformable(
                        state = transformState,
                        enabled = onZoomChange != null,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .width(widthDp)
                        .height(heightDp)
                        .graphicsLayer {
                            scaleX = gestureZoom
                            scaleY = gestureZoom
                            translationX = imageOffset.x
                            translationY = imageOffset.y
                        },
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }
    return true
}

@Composable
actual fun PlatformReaderWindowEffect(
    fullscreen: Boolean,
    keepScreenOn: Boolean,
    lockRotation: Boolean,
) {
    val view = LocalView.current
    val activity = view.context as? android.app.Activity ?: return
    val window = activity.window

    DisposableEffect(fullscreen, keepScreenOn, lockRotation) {
        WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
        if (fullscreen) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        activity.requestedOrientation = if (lockRotation) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        onDispose {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

private fun renderPdfPage(
    contentResolver: android.content.ContentResolver,
    uri: Uri,
    pageNumber: Int,
    theme: ReaderTheme,
): Bitmap? {
    return contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val pageIndex = (pageNumber - 1).coerceIn(0, renderer.pageCount - 1)
            renderer.openPage(pageIndex).use { page ->
                val scale = 2
                val bitmap = Bitmap.createBitmap(
                    page.width * scale,
                    page.height * scale,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.eraseColor(pdfPageBackgroundColor(theme))
                val matrix = Matrix().apply {
                    postScale(scale.toFloat(), scale.toFloat())
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    } ?: throw IllegalStateException(appStrings().missingBookMessage)
}

private fun renderVisualDocumentPage(
    contentResolver: android.content.ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
    format: String,
    pageNumber: Int,
    theme: ReaderTheme,
): Bitmap? =
    when (format) {
        "pdf" -> renderPdfPage(contentResolver, uri, pageNumber, theme)
        "cbz" -> renderCbzPage(contentResolver, uri, sourceVersionKey, pageNumber)
        "cbr" -> renderCbrPage(contentResolver, cacheDir, uri, sourceVersionKey, pageNumber)
        else -> null
    }

private fun renderCbzPage(
    contentResolver: android.content.ContentResolver,
    uri: Uri,
    sourceVersionKey: String,
    pageNumber: Int,
): Bitmap? {
    val pagePaths = loadCbzPageIndex(contentResolver, uri, sourceVersionKey)
    val targetPath = pagePaths.getOrNull((pageNumber - 1).coerceAtLeast(0)) ?: return null

    contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && normalizeCbzEntryPath(entry.name) == targetPath) {
                    return decodeCbzBitmapFromBytes(
                        encodedBytes = zip.readBytes(),
                        sourceLabel = targetPath,
                        maxDimension = CbzRenderMaxImageDimension,
                    )
                }
                zip.closeEntry()
            }
        }
    } ?: throw IllegalStateException(appStrings().missingBookMessage)
    return null
}

private fun renderCbrPage(
    contentResolver: android.content.ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
    pageNumber: Int,
): Bitmap? {
    val pagePaths = loadCbrPageIndex(contentResolver, cacheDir, uri, sourceVersionKey)
    val targetPath = pagePaths.getOrNull((pageNumber - 1).coerceAtLeast(0)) ?: return null

    return withTempAndroidCbrArchive(contentResolver, cacheDir, uri, sourceVersionKey) { archive ->
        var renderedBitmap: Bitmap? = null
        for (header in archive.fileHeaders) {
            if (header.isDirectory) continue
            if (normalizeCbzEntryPath(header.fileName.orEmpty()) != targetPath) continue
            val entryInput = archive.getInputStream(header) ?: break
            renderedBitmap = entryInput.use {
                decodeCbzBitmapFromBytes(
                    encodedBytes = it.readBytes(),
                    sourceLabel = targetPath,
                    maxDimension = CbzRenderMaxImageDimension,
                )
            }
            break
        }
        renderedBitmap
    } ?: throw IllegalStateException(appStrings().missingBookMessage)
}

private fun loadCbzPageIndex(
    contentResolver: android.content.ContentResolver,
    uri: Uri,
    sourceVersionKey: String,
): List<String> {
    val sourceCacheKey = "${uri}|$sourceVersionKey"
    CbzPageIndexCache.get(sourceCacheKey)?.let { return it }

    val pages = mutableListOf<String>()
    contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val path = normalizeCbzEntryPath(entry.name)
                    if (path.isSupportedCbzEntryImage()) {
                        pages += path
                    }
                }
                zip.closeEntry()
            }
        }
    } ?: throw IllegalStateException(appStrings().missingBookMessage)

    val sortedPages = pages.sortedWith(compareByNaturalCbzEntryPath<String> { it })
    CbzPageIndexCache.put(sourceCacheKey, sortedPages)
    return sortedPages
}

private fun loadCbrPageIndex(
    contentResolver: android.content.ContentResolver,
    cacheDir: File,
    uri: Uri,
    sourceVersionKey: String,
): List<String> {
    val sourceCacheKey = "${uri}|$sourceVersionKey"
    CbzPageIndexCache.get(sourceCacheKey)?.let { return it }

    val pages = mutableListOf<String>()
    withTempAndroidCbrArchive(contentResolver, cacheDir, uri, sourceVersionKey) { archive ->
        archive.fileHeaders.forEach { header ->
            if (header.isDirectory) return@forEach
            val path = normalizeCbzEntryPath(header.fileName.orEmpty())
            if (path.isSupportedCbzEntryImage()) {
                pages += path
            }
        }
    } ?: throw IllegalStateException(appStrings().missingBookMessage)

    val sortedPages = pages.sortedWith(compareByNaturalCbzEntryPath<String> { it })
    CbzPageIndexCache.put(sourceCacheKey, sortedPages)
    return sortedPages
}

private fun decodeCbzBitmapFromFile(
    file: File,
    maxDimension: Int,
): Bitmap? {
    AndroidNativeImageBridge.decodeScaledBitmapFile(file, maxDimension)?.let { return it }
    debugLog(
        NativeImageLogTag,
        "Falling back to BitmapFactory for reader image ${file.name} (maxDimension=$maxDimension)",
    )

    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateCbzInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private fun decodeCbzBitmapFromBytes(
    encodedBytes: ByteArray,
    sourceLabel: String,
    maxDimension: Int,
): Bitmap? {
    AndroidNativeImageBridge.decodeScaledBitmapBytes(
        encodedBytes = encodedBytes,
        maxDimension = maxDimension,
        sourceLabel = sourceLabel,
    )?.let { return it }
    debugLog(
        NativeImageLogTag,
        "Falling back to BitmapFactory for reader image $sourceLabel (maxDimension=$maxDimension)",
    )

    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeByteArray(encodedBytes, 0, encodedBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateCbzInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(encodedBytes, 0, encodedBytes.size, options)
}

private fun calculateCbzInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
): Int {
    var sampleSize = 1
    var scaledWidth = width
    var scaledHeight = height
    while (scaledWidth > maxDimension || scaledHeight > maxDimension) {
        sampleSize *= 2
        scaledWidth = width / sampleSize
        scaledHeight = height / sampleSize
    }
    return sampleSize.coerceAtLeast(1)
}

private fun normalizeCbzEntryPath(path: String): String =
    path.replace('\\', '/').trimStart('/')

private fun String.isSupportedCbzEntryImage(): Boolean =
    lowercase().let { path ->
        path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".jpe") ||
            path.endsWith(".jfif") ||
            path.endsWith(".png") ||
            path.endsWith(".webp") ||
            path.endsWith(".gif") ||
            path.endsWith(".bmp") ||
            path.endsWith(".avif")
    }

private fun <T> compareByNaturalCbzEntryPath(selector: (T) -> String): Comparator<T> =
    Comparator { left, right ->
        compareNaturalCbzEntryPaths(selector(left), selector(right))
    }

private fun compareNaturalCbzEntryPaths(left: String, right: String): Int {
    val leftParts = tokenizeNaturalCbzEntryPath(left)
    val rightParts = tokenizeNaturalCbzEntryPath(right)
    val minSize = minOf(leftParts.size, rightParts.size)
    for (index in 0 until minSize) {
        val result = compareNaturalCbzEntryPart(leftParts[index], rightParts[index])
        if (result != 0) return result
    }
    return leftParts.size.compareTo(rightParts.size)
}

private fun tokenizeNaturalCbzEntryPath(path: String): List<String> =
    Regex("""\d+|\D+""")
        .findAll(path.lowercase())
        .map { it.value }
        .toList()

private fun compareNaturalCbzEntryPart(left: String, right: String): Int {
    val leftNumber = left.toLongOrNull()
    val rightNumber = right.toLongOrNull()
    return when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        else -> left.compareTo(right)
    }
}

private fun readerBackground(theme: ReaderTheme): androidx.compose.ui.graphics.Color =
    readerPageColor(theme)

private fun readerBackgroundColor(theme: ReaderTheme): Int =
    readerPageColor(theme).toArgb()

private fun pdfPageBackgroundColor(theme: ReaderTheme): Int =
    when (theme) {
        ReaderTheme.Dark -> android.graphics.Color.WHITE
        ReaderTheme.Sepia -> android.graphics.Color.rgb(240, 223, 192)
        ReaderTheme.DarkSepia -> android.graphics.Color.rgb(224, 206, 178)
        ReaderTheme.Light -> android.graphics.Color.rgb(255, 252, 247)
    }
