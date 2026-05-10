package com.example.simplelector

import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.io.File
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

private const val DesktopVisualRenderMaxImageDimension = 2_000

private data class DesktopVisualPageCacheKey(
    val sourceId: String,
    val format: String,
    val pageNumber: Int,
    val theme: ReaderTheme,
)

private object DesktopVisualPageCache {
    private const val MAX_ENTRIES = 12
    private val pages = LinkedHashMap<DesktopVisualPageCacheKey, ImageBitmap>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun get(key: DesktopVisualPageCacheKey): ImageBitmap? = pages[key]

    @Synchronized
    fun put(key: DesktopVisualPageCacheKey, bitmap: ImageBitmap) {
        pages[key] = bitmap
        while (pages.size > MAX_ENTRIES) {
            val eldest = pages.entries.firstOrNull() ?: break
            pages.remove(eldest.key)
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
actual fun PlatformDocumentPage(
    sourceId: String,
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

    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    val visualPageFocusRequester = remember(sourceId, normalizedFormat, pageNumber) { FocusRequester() }
    val scope = rememberCoroutineScope()
    var renderError by remember(sourceId, normalizedFormat, pageNumber, theme) { mutableStateOf<Throwable?>(null) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, sourceId, normalizedFormat, pageNumber, theme) {
        value = null
        renderError = null
        val cacheKey = DesktopVisualPageCacheKey(sourceId, normalizedFormat, pageNumber, theme)
        DesktopVisualPageCache.get(cacheKey)?.let {
            value = it
            return@produceState
        }
        try {
            val rendered = withContext(Dispatchers.IO) {
                val file = File(sourceId)
                if (!file.isFile) {
                    throw IllegalStateException(appStrings().missingBookMessage)
                }
                renderDesktopVisualPageBitmap(file, normalizedFormat, pageNumber)
            }
            value = rendered?.also {
                DesktopVisualPageCache.put(cacheKey, it)
            }
        } catch (error: Throwable) {
            renderError = normalizeDesktopVisualDocumentException(error)
        }
    }
    LaunchedEffect(renderError) {
        renderError?.let { onRenderError?.invoke(it) }
    }

    LaunchedEffect(sourceId, normalizedFormat, pageNumber, theme) {
        withContext(Dispatchers.IO) {
            listOf(pageNumber - 1, pageNumber + 1)
                .filter { it > 0 }
                .forEach { adjacentPage ->
                    val cacheKey = DesktopVisualPageCacheKey(sourceId, normalizedFormat, adjacentPage, theme)
                    if (DesktopVisualPageCache.get(cacheKey) == null) {
                        try {
                            val file = File(sourceId)
                            if (!file.isFile) return@forEach
                            val rendered = renderDesktopVisualPageBitmap(file, normalizedFormat, adjacentPage) ?: return@forEach
                            DesktopVisualPageCache.put(cacheKey, rendered)
                        } catch (_: Throwable) {
                            // Ignore background prefetch failures so the visible page can handle missing-file errors.
                        }
                    }
                }
        }
    }
    LaunchedEffect(bitmap, sourceId, pageNumber, zoomLevel) {
        if (bitmap != null) {
            yield()
            runCatching {
                visualPageFocusRequester.requestFocus()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(12.dp)
            .background(desktopReaderBackground(theme)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            CircularProgressIndicator()
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .focusRequester(visualPageFocusRequester)
                    .focusable()
                    .pointerInput(zoomLevel) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Scroll) continue
                                val scrollDeltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (scrollDeltaY == 0f) continue
                                val updatedZoom = (zoomLevel + if (scrollDeltaY < 0f) 0.15f else -0.15f)
                                    .coerceIn(0.4f, 3f)
                                onZoomChange?.invoke(if (updatedZoom in 0.92f..1.08f) 1f else updatedZoom)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                    .pointerInput(zoomLevel) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                horizontalScrollState.scrollTo(
                                    (horizontalScrollState.value - dragAmount.x).roundToInt().coerceIn(0, horizontalScrollState.maxValue),
                                )
                                verticalScrollState.scrollTo(
                                    (verticalScrollState.value - dragAmount.y).roundToInt().coerceIn(0, verticalScrollState.maxValue),
                                )
                            }
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        val panStep = 96f
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (event.isShiftPressed) {
                                    scope.launch {
                                        horizontalScrollState.scrollTo(
                                            (horizontalScrollState.value - panStep).roundToInt().coerceAtLeast(0),
                                        )
                                    }
                                    true
                                } else {
                                    onPreviousPage?.invoke()
                                    true
                                }
                            }
                            Key.DirectionRight -> {
                                if (event.isShiftPressed) {
                                    scope.launch {
                                        horizontalScrollState.scrollTo(
                                            (horizontalScrollState.value + panStep).roundToInt().coerceAtMost(horizontalScrollState.maxValue),
                                        )
                                    }
                                    true
                                } else {
                                    onNextPage?.invoke()
                                    true
                                }
                            }
                            Key.DirectionUp -> {
                                scope.launch {
                                    verticalScrollState.scrollTo(
                                        (verticalScrollState.value - panStep).roundToInt().coerceAtLeast(0),
                                    )
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                scope.launch {
                                    verticalScrollState.scrollTo(
                                        (verticalScrollState.value + panStep).roundToInt().coerceAtMost(verticalScrollState.maxValue),
                                    )
                                }
                                true
                            }
                            Key.PageUp -> {
                                scope.launch {
                                    verticalScrollState.scrollTo(
                                        (verticalScrollState.value - panStep).roundToInt().coerceAtLeast(0),
                                    )
                                }
                                true
                            }
                            Key.PageDown -> {
                                scope.launch {
                                    verticalScrollState.scrollTo(
                                        (verticalScrollState.value + panStep).roundToInt().coerceAtMost(verticalScrollState.maxValue),
                                    )
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    .horizontalScroll(horizontalScrollState)
                    .verticalScroll(verticalScrollState),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    modifier = Modifier
                        .width((bitmap!!.width * zoomLevel).dp)
                        .height((bitmap!!.height * zoomLevel).dp),
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
) = Unit

internal fun renderDesktopPdfPage(file: File, pageNumber: Int): ByteArray? =
    runCatching {
        Loader.loadPDF(file).use { document ->
            val pageIndex = (pageNumber - 1).coerceIn(0, document.numberOfPages - 1)
            val renderer = PDFRenderer(document)
            val image = renderer.renderImageWithDPI(pageIndex, 110f, ImageType.RGB)
            ByteArrayOutputStream().use { output ->
                ImageIO.write(image, "png", output)
                output.toByteArray()
            }
        }
    }.getOrNull()

private fun renderDesktopVisualPageBitmap(
    file: File,
    format: String,
    pageNumber: Int,
): ImageBitmap? =
    when (format) {
        "pdf" -> renderDesktopPdfPageBitmap(file, pageNumber)
        "cbz" -> renderDesktopCbzPageBitmap(file, pageNumber)
        "cbr" -> renderDesktopCbrPageBitmap(file, pageNumber)
        else -> null
    }

private fun renderDesktopPdfPageBitmap(file: File, pageNumber: Int): ImageBitmap? =
    runCatching {
        Loader.loadPDF(file).use { document ->
            val pageIndex = (pageNumber - 1).coerceIn(0, document.numberOfPages - 1)
            PDFRenderer(document)
                .renderImageWithDPI(pageIndex, 140f, ImageType.RGB)
                .toComposeImageBitmap()
        }
    }.getOrNull()

private fun renderDesktopCbzPageBitmap(file: File, pageNumber: Int): ImageBitmap? {
    val pagePaths = loadDesktopCbzPageIndex(file)
    val targetPath = pagePaths.getOrNull((pageNumber - 1).coerceAtLeast(0)) ?: return null

    file.inputStream().buffered().use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && normalizeDesktopComicPath(entry.name) == targetPath) {
                    return ImageIO.read(zip)
                        ?.downscaleForDesktopReader(DesktopVisualRenderMaxImageDimension)
                        ?.toComposeImageBitmap()
                }
                zip.closeEntry()
            }
        }
    }
    return null
}

private fun renderDesktopCbrPageBitmap(file: File, pageNumber: Int): ImageBitmap? {
    val pagePaths = loadDesktopCbrPageIndex(file)
    val targetPath = pagePaths.getOrNull((pageNumber - 1).coerceAtLeast(0)) ?: return null

    Archive(file).use { archive ->
        archive.fileHeaders.forEach { header ->
            if (header.isDirectory) return@forEach
            if (normalizeDesktopComicPath(header.fileName.orEmpty()) != targetPath) return@forEach
            archive.getInputStream(header)?.use { input ->
                return ImageIO.read(input)
                    ?.downscaleForDesktopReader(DesktopVisualRenderMaxImageDimension)
                    ?.toComposeImageBitmap()
            }
        }
    }
    return null
}

private fun loadDesktopCbzPageIndex(file: File): List<String> {
    val sourceId = file.absolutePath
    DesktopComicPageIndexCache.get(sourceId)?.let { return it }

    val pages = mutableListOf<String>()
    file.inputStream().buffered().use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val path = normalizeDesktopComicPath(entry.name)
                    if (path.isSupportedDesktopVisualComicImage()) {
                        pages += path
                    }
                }
                zip.closeEntry()
            }
        }
    }

    return pages
        .sortedWith(compareByNaturalDesktopVisualComicPath<String> { it })
        .also { DesktopComicPageIndexCache.put(sourceId, it) }
}

private fun loadDesktopCbrPageIndex(file: File): List<String> {
    val sourceId = file.absolutePath
    DesktopComicPageIndexCache.get(sourceId)?.let { return it }

    val pages = mutableListOf<String>()
    Archive(file).use { archive ->
        archive.fileHeaders.forEach { header ->
            if (header.isDirectory) return@forEach
            val path = normalizeDesktopComicPath(header.fileName.orEmpty())
            if (path.isSupportedDesktopVisualComicImage()) {
                pages += path
            }
        }
    }

    return pages
        .sortedWith(compareByNaturalDesktopVisualComicPath<String> { it })
        .also { DesktopComicPageIndexCache.put(sourceId, it) }
}

private object DesktopComicPageIndexCache {
    private const val MAX_ENTRIES = 24
    private val pages = LinkedHashMap<String, List<String>>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun get(sourceId: String): List<String>? = pages[sourceId]

    @Synchronized
    fun put(sourceId: String, pagePaths: List<String>) {
        pages[sourceId] = pagePaths
        while (pages.size > MAX_ENTRIES) {
            val eldest = pages.entries.firstOrNull() ?: break
            pages.remove(eldest.key)
        }
    }
}

private fun BufferedImage.downscaleForDesktopReader(maxDimension: Int): BufferedImage {
    if (width <= maxDimension && height <= maxDimension) return this
    val scale = minOf(
        maxDimension.toDouble() / width.toDouble(),
        maxDimension.toDouble() / height.toDouble(),
    )
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    val scaled = getScaledInstance(targetWidth, targetHeight, java.awt.Image.SCALE_SMOOTH)
    return BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB).also { target ->
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(scaled, 0, 0, null)
        } finally {
            graphics.dispose()
        }
    }
}

private fun normalizeDesktopVisualDocumentException(error: Throwable): Throwable =
    if (generateSequence(error) { it.cause }.any { it is UnsupportedRarV5Exception }) {
        IllegalStateException(appStrings().unsupportedCbrRar5Message)
    } else {
        error
    }

private fun normalizeDesktopComicPath(path: String): String =
    path.replace('\\', '/').trimStart('/').lowercase()

private fun String.isSupportedDesktopVisualComicImage(): Boolean =
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

private fun <T> compareByNaturalDesktopVisualComicPath(selector: (T) -> String): Comparator<T> =
    Comparator { left, right ->
        compareNaturalDesktopVisualComicPath(selector(left), selector(right))
    }

private fun compareNaturalDesktopVisualComicPath(left: String, right: String): Int {
    val leftParts = tokenizeNaturalDesktopVisualComicPath(left)
    val rightParts = tokenizeNaturalDesktopVisualComicPath(right)
    val minSize = minOf(leftParts.size, rightParts.size)
    for (index in 0 until minSize) {
        val result = compareNaturalDesktopVisualComicPart(leftParts[index], rightParts[index])
        if (result != 0) return result
    }
    return leftParts.size.compareTo(rightParts.size)
}

private fun tokenizeNaturalDesktopVisualComicPath(path: String): List<String> =
    Regex("""\d+|\D+""")
        .findAll(path.lowercase())
        .map { it.value }
        .toList()

private fun compareNaturalDesktopVisualComicPart(left: String, right: String): Int {
    val leftNumber = left.toLongOrNull()
    val rightNumber = right.toLongOrNull()
    return when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        else -> left.compareTo(right)
    }
}

private fun desktopReaderBackground(theme: ReaderTheme): Color =
    readerPageColor(theme)
